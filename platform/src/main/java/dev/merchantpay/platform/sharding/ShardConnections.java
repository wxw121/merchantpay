package dev.merchantpay.platform.sharding;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.*;
import javax.sql.DataSource;

/** Owns only the extra lazy pools; it is deliberately not a Spring DataSource bean. */
public final class ShardConnections implements AutoCloseable {
  private final Map<Integer, DataSource> targets;

  private ShardConnections(Map<Integer, DataSource> targets) {
    this.targets = Map.copyOf(targets);
  }

  public static ShardConnections configured(
      DataSource core, String coreUrl, ShardSettings settings) {
    Objects.requireNonNull(core);
    if (settings == null
        || settings.getTargets() == null
        || !settings.getTargets().keySet().equals(Set.of(1, 2, 3))
        || settings.getConnectionsPerShard() < 1
        || settings.getConnectionsPerShard() > 16)
      throw new IllegalArgumentException(
          "Sharding requires targets 1..3 and 1..16 connections each");
    var databases = new HashSet<String>();
    String declaredCore = database(coreUrl);
    verifyCore(core, declaredCore);
    databases.add(declaredCore);
    for (var target : settings.getTargets().values()) {
      if (target == null
          || target.username() == null
          || target.username().isBlank()
          || target.password() == null
          || target.password().isBlank())
        throw new IllegalArgumentException("Shard credentials are required");
      if (!databases.add(database(target.url())))
        throw new IllegalArgumentException("Shard databases must be distinct");
    }
    var sources = new LinkedHashMap<Integer, DataSource>();
    sources.put(0, core);
    try {
      for (int id = 1; id < 4; id++) {
        var target = settings.getTargets().get(id);
        var pool = new HikariDataSource();
        sources.put(id, pool);
        pool.setPoolName("merchantpay-shard-" + id);
        pool.setJdbcUrl(target.url());
        pool.setUsername(target.username());
        pool.setPassword(target.password());
        pool.setMinimumIdle(0);
        pool.setMaximumPoolSize(settings.getConnectionsPerShard());
        pool.setConnectionTimeout(3000);
        pool.setValidationTimeout(1000);
        pool.setInitializationFailTimeout(-1);
        pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
      }
      return new ShardConnections(sources);
    } catch (RuntimeException failure) {
      sources.forEach(
          (id, source) -> {
            if (id != 0) ((HikariDataSource) source).close();
          });
      throw failure;
    }
  }

  private static String database(String url) {
    try {
      if (url == null || !url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
      var uri = URI.create(url.substring(5));
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getPath() == null
          || !uri.getPath().matches("/[A-Za-z0-9_-]+")
          || uri.getPort() == 0
          || uri.getPort() > 65535) throw new IllegalArgumentException();
      return uri.getHost().toLowerCase(Locale.ROOT)
          + ":"
          + (uri.getPort() < 0 ? 3306 : uri.getPort())
          + uri.getPath().toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException invalid) {
      // Do not include JDBC URLs, parser causes or credentials in validation errors.
      throw new IllegalArgumentException("Shard URL must name one MySQL host and database");
    }
  }

  private static void verifyCore(DataSource source, String declared) {
    if (source
        instanceof org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource routed) {
      var resolved = routed.getResolvedDataSources();
      if (resolved.isEmpty()) throw new IllegalArgumentException("Core routing pools are required");
      for (var target : resolved.values()) verifyCore(target, declared);
      if (routed.getResolvedDefaultDataSource() != null)
        verifyCore(routed.getResolvedDefaultDataSource(), declared);
    } else {
      String actual;
      if (source instanceof HikariDataSource pool) actual = pool.getJdbcUrl();
      else if (source instanceof org.springframework.jdbc.datasource.DriverManagerDataSource direct)
        actual = direct.getUrl();
      else throw new IllegalArgumentException("Unsupported core DataSource for shard validation");
      if (!declared.equals(database(actual)))
        throw new IllegalArgumentException("Core pool URL must match the declared core database");
    }
  }

  public Map<Integer, DataSource> targets() {
    return targets;
  }

  @Override
  public void close() {
    targets.forEach(
        (id, source) -> {
          if (id != 0) ((HikariDataSource) source).close();
        });
  }
}
