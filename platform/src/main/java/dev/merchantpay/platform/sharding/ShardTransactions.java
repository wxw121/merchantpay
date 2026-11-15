package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.ApiException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;

/** Explicit local transactions: callers must use the provided JDBC handle for every local write. */
public final class ShardTransactions {
  private record Target(JdbcTemplate jdbc, DataSourceTransactionManager manager) {}

  private record Bound(
      ShardCatalog.Route route,
      boolean readOnly,
      JdbcTemplate jdbc,
      TransactionStatus status,
      DataSource source,
      Object connectionHolder) {}

  private final ScopedValue<Bound> current = ScopedValue.newInstance();
  private final JdbcTemplate control;
  private final Map<Integer, Target> targets;
  private final Map<DataSource, String> writeLockSuffixes = new ConcurrentHashMap<>();

  public ShardTransactions(JdbcTemplate control, Map<Integer, DataSource> sources) {
    this.control = new JdbcTemplate(Objects.requireNonNull(control.getDataSource()));
    this.control.setQueryTimeout(3);
    if (sources == null || !sources.keySet().equals(Set.of(0, 1, 2, 3)))
      throw new IllegalArgumentException("Exactly four shard DataSources are required");
    var configured = new HashMap<Integer, Target>();
    sources.forEach(
        (id, source) -> {
          var jdbc = new JdbcTemplate(Objects.requireNonNull(source));
          jdbc.setQueryTimeout(3);
          configured.put(id, new Target(jdbc, new DataSourceTransactionManager(source)));
        });
    this.targets = Map.copyOf(configured);
  }

  public <T> T read(int bucket, Function<JdbcTemplate, T> action) {
    return execute(bucket, -1, 0, true, action);
  }

  public <T> T write(int bucket, long expectedEpoch, Function<JdbcTemplate, T> action) {
    if (expectedEpoch < 1) {
      poison();
      throw new IllegalArgumentException("A positive route epoch is required for writes");
    }
    return execute(bucket, -1, expectedEpoch, false, action);
  }

  public ShardCatalog.Route currentRoute(int bucket) {
    if (bucket < 0 || bucket > 15) {
      poison();
      throw new IllegalArgumentException("A bucket in 0..15 is required");
    }
    return route(bucket);
  }

  public ShardCatalog.Route boundRoute() {
    if (!current.isBound() || current.get().readOnly())
      throw new IllegalStateException("A bound writable shard transaction is required");
    requireSameTransaction(current.get());
    return current.get().route();
  }

  public <T> T write(ShardCatalog.Route expected, Function<JdbcTemplate, T> action) {
    if (expected == null
        || expected.physicalShard() < 0
        || expected.physicalShard() > 3
        || expected.epoch() < 1) {
      poison();
      throw new IllegalArgumentException("A complete positive expected route is required");
    }
    return execute(expected.bucket(), expected.physicalShard(), expected.epoch(), false, action);
  }

  private <T> T execute(
      int bucket,
      int expectedPhysical,
      long expectedEpoch,
      boolean readOnly,
      Function<JdbcTemplate, T> action) {
    if (bucket < 0 || bucket > 15 || action == null) {
      poison();
      throw new IllegalArgumentException("A bucket in 0..15 and an action are required");
    }
    if (current.isBound()) {
      var bound = current.get();
      requireSameTransaction(bound);
      if (bound.route().bucket() != bucket || bound.readOnly() && !readOnly) {
        poison();
        throw new IllegalStateException(
            "A shard transaction cannot change bucket or upgrade a read");
      }
      if (expectedEpoch != 0 && bound.route().epoch() != expectedEpoch
          || expectedPhysical >= 0 && bound.route().physicalShard() != expectedPhysical) {
        poison();
        throw unavailable();
      }
      try {
        return action.apply(bound.jdbc());
      } catch (RuntimeException | Error failure) {
        poison();
        throw failure;
      }
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      poison();
      throw new IllegalStateException("Choose a shard before opening a transaction");
    }
    var route = route(bucket);
    if (expectedEpoch != 0 && route.epoch() != expectedEpoch
        || expectedPhysical >= 0 && route.physicalShard() != expectedPhysical) throw unavailable();
    var target = targets.get(route.physicalShard());
    if (target == null) throw unavailable();
    var tx = new TransactionTemplate(target.manager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setReadOnly(readOnly);
    tx.setTimeout(5);
    return tx.execute(
        status -> {
          var bound =
              new Bound(
                  route,
                  readOnly,
                  target.jdbc(),
                  status,
                  target.manager().getDataSource(),
                  TransactionSynchronizationManager.getResource(target.manager().getDataSource()));
          return ScopedValue.where(current, bound)
              .call(
                  () -> {
                    verify(target.jdbc(), route, !readOnly);
                    T result = action.apply(target.jdbc());
                    requireSameTransaction(bound);
                    if (status.isRollbackOnly())
                      throw new IllegalStateException(
                          "The shard transaction was marked for rollback");
                    if (readOnly) verify(target.jdbc(), route, false);
                    if (!route.equals(route(bucket))) throw unavailable();
                    return result;
                  });
        });
  }

  private void requireSameTransaction(Bound bound) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || bound.connectionHolder() == null
        || TransactionSynchronizationManager.getResource(bound.source())
            != bound.connectionHolder()) {
      poison();
      throw new IllegalStateException("The original shard transaction was suspended or replaced");
    }
  }

  private ShardCatalog.Route route(int bucket) {
    var rows =
        control.query(
            "SELECT bucket,physical_shard,epoch FROM v3_shard_routes WHERE bucket=?",
            (rs, row) -> new ShardCatalog.Route(rs.getInt(1), rs.getInt(2), rs.getLong(3)),
            bucket);
    if (rows.size() != 1 || rows.getFirst().epoch() < 1) throw unavailable();
    if (rows.getFirst().physicalShard() != 0
        && !control
            .queryForList(
                "SELECT plan_id FROM v3_shard_cutovers WHERE bucket=? AND state='ROUTED' LIMIT 1",
                String.class,
                bucket)
            .isEmpty()) throw unavailable();
    return rows.getFirst();
  }

  private void verify(JdbcTemplate jdbc, ShardCatalog.Route route, boolean lock) {
    String suffix =
        lock
            ? writeLockSuffixes.computeIfAbsent(
                jdbc.getDataSource(),
                ignored ->
                    jdbc.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<String>)
                            connection ->
                                "H2".equals(connection.getMetaData().getDatabaseProductName())
                                    ? " FOR UPDATE"
                                    : " FOR SHARE"))
            : "";
    var identity =
        jdbc.queryForList(
            "SELECT physical_shard FROM v3_shard_identity WHERE id=1" + suffix, Integer.class);
    if (identity.size() != 1 || identity.getFirst() != route.physicalShard()) throw unavailable();
    var valid =
        jdbc.query(
            "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=?" + suffix,
            (rs, row) -> rs.getLong(1) == route.epoch() && "ACTIVE".equals(rs.getString(2)),
            route.bucket());
    if (valid.size() != 1 || !valid.getFirst()) throw unavailable();
  }

  private void poison() {
    if (current.isBound()) current.get().status().setRollbackOnly();
    for (var resource : TransactionSynchronizationManager.getResourceMap().values())
      if (resource instanceof ResourceHolderSupport holder) holder.setRollbackOnly();
  }

  private static ApiException unavailable() {
    return new ApiException(
        "SHARD_ROUTE_UNAVAILABLE",
        503,
        "分片身份、目录版本或桶归属不一致，请使用原业务号稍后重试",
        ApiException.RetryReason.DATABASE_UNAVAILABLE);
  }
}
