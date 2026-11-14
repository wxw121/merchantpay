package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.PaymentBuckets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** A route snapshot for authority checks during a four-source bulk read. */
public final class ShardExportReadGate {
  public record Route(int bucket, int physical, long epoch) {}

  /** Bucket -> retired physical source and its last active epoch. */
  public record Snapshot(List<Route> routes, Map<Integer, Map<Integer, Long>> retiredSources) {}

  private final Map<Integer, DataSource> sources;
  private final Map<Integer, JdbcTemplate> jdbc;

  public ShardExportReadGate(Map<Integer, DataSource> sources) {
    if (sources == null || !sources.keySet().equals(Set.of(0, 1, 2, 3)))
      throw new IllegalArgumentException("Four export sources are required");
    this.sources = Map.copyOf(sources);
    var configured = new HashMap<Integer, JdbcTemplate>();
    sources.forEach(
        (id, source) -> {
          var local = new JdbcTemplate(source);
          local.setQueryTimeout(3);
          configured.put(id, local);
        });
    jdbc = Map.copyOf(configured);
  }

  public Snapshot capture() {
    return capture(null);
  }

  public Snapshot captureForMigration(ShardCatalog.Plan plan) {
    if (plan == null || !"PLANNED".equals(plan.state())) throw unavailable();
    return capture(plan);
  }

  private Snapshot capture(ShardCatalog.Plan migration) {
    var routes =
        jdbc.get(0)
            .query(
                "SELECT bucket,physical_shard,epoch FROM v3_shard_routes ORDER BY bucket",
                (rs, row) -> new Route(rs.getInt(1), rs.getInt(2), rs.getLong(3)));
    if (routes.size() != 16) throw unavailable();
    for (int bucket = 0; bucket < 16; bucket++) {
      var route = routes.get(bucket);
      if (route.bucket() != bucket
          || route.physical() < 0
          || route.physical() > 3
          || route.epoch() < 1) throw unavailable();
    }
    var history = new HashMap<Integer, List<Map<String, Object>>>();
    for (var row :
        jdbc.get(0)
            .queryForList(
                "SELECT bucket,source_epoch,target_epoch,target_shard,state FROM v3_shard_cutovers "
                    + "WHERE state IN ('ROUTED','ACTIVE') ORDER BY bucket,target_epoch")) {
      int bucket = ((Number) row.get("bucket")).intValue();
      if (bucket < 0 || bucket > 15) throw unavailable();
      history.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(row);
    }
    var retired = new HashMap<Integer, Map<Integer, Long>>();
    for (var route : routes) {
      int owner = 0;
      long epoch = 1;
      var priorOwners = new HashMap<Integer, Long>();
      for (var row : history.getOrDefault(route.bucket(), List.of())) {
        long sourceEpoch = ((Number) row.get("source_epoch")).longValue();
        long targetEpoch = ((Number) row.get("target_epoch")).longValue();
        int target = ((Number) row.get("target_shard")).intValue();
        if (!"ACTIVE".equals(row.get("state"))
            || sourceEpoch != epoch
            || targetEpoch != epoch + 1
            || target < 0
            || target > 3
            || target == owner) throw unavailable();
        priorOwners.put(owner, epoch);
        owner = target;
        epoch = targetEpoch;
      }
      if (owner != route.physical() || epoch != route.epoch()) throw unavailable();
      priorOwners.remove(owner);
      if (!priorOwners.isEmpty()) retired.put(route.bucket(), Map.copyOf(priorOwners));
    }
    for (int physical = 0; physical < 4; physical++) {
      var local = jdbc.get(physical);
      var identity =
          local.queryForList(
              "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
      if (identity.size() != 1 || identity.getFirst() != physical) throw unavailable();
      var fences = new HashMap<Integer, Map<String, Object>>();
      for (var fence :
          local.queryForList("SELECT bucket,route_epoch,access_state FROM v3_bucket_fences")) {
        int bucket = ((Number) fence.get("bucket")).intValue();
        if (bucket < 0 || bucket > 15 || fences.put(bucket, fence) != null) throw unavailable();
      }
      for (var route : routes) {
        var fence = fences.remove(route.bucket());
        if (route.physical() == physical) {
          if (fence == null
              || ((Number) fence.get("route_epoch")).longValue() != route.epoch()
              || !"ACTIVE".equals(fence.get("access_state"))
                  && !migrationFence(migration, route, fence)) throw unavailable();
        } else if (fence != null && "ACTIVE".equals(fence.get("access_state"))) {
          throw unavailable();
        }
        Long retiredEpoch = retired.getOrDefault(route.bucket(), Map.of()).get(physical);
        if (retiredEpoch != null
            && (fence == null
                || ((Number) fence.get("route_epoch")).longValue() != retiredEpoch
                || !"FENCED".equals(fence.get("access_state")))) throw unavailable();
      }
      if (fences.values().stream().anyMatch(fence -> "ACTIVE".equals(fence.get("access_state"))))
        throw unavailable();
    }
    var repeated =
        jdbc.get(0)
            .query(
                "SELECT bucket,physical_shard,epoch FROM v3_shard_routes ORDER BY bucket",
                (rs, row) -> new Route(rs.getInt(1), rs.getInt(2), rs.getLong(3)));
    if (!routes.equals(repeated)) throw unavailable();
    return new Snapshot(List.copyOf(routes), Map.copyOf(retired));
  }

  private boolean migrationFence(ShardCatalog.Plan plan, Route route, Map<String, Object> fence) {
    if (plan == null
        || !"FENCED".equals(fence.get("access_state"))
        || plan.bucket() != route.bucket()
        || plan.sourceShard() != route.physical()
        || plan.sourceEpoch() != route.epoch()) return false;
    return jdbc.get(0)
            .queryForObject(
                "SELECT COUNT(*) FROM v3_shard_isolations WHERE plan_id=? AND bucket=? "
                    + "AND source_epoch=? AND plan_version=? AND state='QUIESCED'",
                Long.class,
                plan.id(),
                plan.bucket(),
                plan.sourceEpoch(),
                plan.version())
        == 1L;
  }

  public void validate(Snapshot expected) {
    if (expected == null || !expected.equals(capture())) throw unavailable();
  }

  public void validateForMigration(Snapshot expected, ShardCatalog.Plan plan) {
    if (expected == null || !expected.equals(captureForMigration(plan))) throw unavailable();
  }

  /** Call inside the core task checkpoint transaction before publishing the result. */
  public void validateForPublish(Snapshot expected) {
    var locked =
        jdbc.get(0)
            .query(
                "SELECT bucket,physical_shard,epoch FROM v3_shard_routes ORDER BY bucket FOR UPDATE",
                (rs, row) -> new Route(rs.getInt(1), rs.getInt(2), rs.getLong(3)));
    if (expected == null || !expected.routes().equals(locked)) throw unavailable();
    validate(expected);
  }

  public DataSource source(int physical) {
    return sources.get(physical);
  }

  public void assertOwned(Snapshot snapshot, int physical, String resource, ResultSet row)
      throws SQLException {
    if (!includeOwned(snapshot, physical, resource, row)) throw unavailable();
  }

  /** Skip only a retired copy proven by a complete cutover chain; reject all other drift. */
  public boolean includeOwned(Snapshot snapshot, int physical, String resource, ResultSet row)
      throws SQLException {
    String payment =
        switch (resource) {
          case "payments" -> row.getString("id");
          case "refunds", "journals", "history" -> row.getString("payment_id");
          default -> throw new IllegalArgumentException("Unknown export resource");
        };
    if (payment == null && "journals".equals(resource)) {
      if (physical != 0) throw unavailable();
      return true;
    }
    int bucket;
    try {
      bucket = PaymentBuckets.forPaymentId(payment);
    } catch (ApiException invalid) {
      throw unavailable();
    }
    if ("history".equals(resource) && row.getInt("logical_bucket") != bucket) throw unavailable();
    if ("refunds".equals(resource)) {
      try {
        if (PaymentBuckets.forRefundId(row.getString("id")) != bucket) throw unavailable();
      } catch (ApiException invalid) {
        throw unavailable();
      }
    }
    if (snapshot.routes().get(bucket).physical() == physical) return true;
    if (snapshot.retiredSources().getOrDefault(bucket, Map.of()).containsKey(physical))
      return false;
    throw unavailable();
  }

  private static ApiException unavailable() {
    return new ApiException("SHARDED_EXPORT_SOURCE_CONFLICT", 503, "导出来源身份、桶归属或栅栏不一致，请核对搬迁状态后重试");
  }
}
