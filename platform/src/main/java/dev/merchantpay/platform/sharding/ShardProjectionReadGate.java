package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.ApiException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Checks a common serving generation and the live bucket ownership before a global read. */
public final class ShardProjectionReadGate {
  private record Route(int bucket, int physical, long epoch) {}

  private record Fence(long epoch, String state) {}

  private final Map<Integer, JdbcTemplate> sources;

  public ShardProjectionReadGate(Map<Integer, DataSource> databases) {
    if (databases == null || !databases.keySet().equals(Set.of(0, 1, 2, 3)))
      throw new IllegalArgumentException("Four projection sources are required");
    var configured = new HashMap<Integer, JdbcTemplate>();
    databases.forEach(
        (id, source) -> {
          var jdbc = new JdbcTemplate(Objects.requireNonNull(source));
          jdbc.setQueryTimeout(3);
          configured.put(id, jdbc);
        });
    sources = Map.copyOf(configured);
  }

  public String generation() {
    String common = null;
    for (int physical = 0; physical < 4; physical++) {
      var jdbc = sources.get(physical);
      Integer identity =
          jdbc.queryForObject(
              "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
      if (!Objects.equals(identity, physical)) throw unavailable();
      String mode =
          jdbc.queryForObject("SELECT mode FROM event_transport WHERE id=1", String.class);
      if (!"CDC".equals(mode)) throw unavailable();
      var control = jdbc.queryForMap("SELECT active_generation FROM projection_control WHERE id=1");
      String active = (String) control.get("active_generation");
      if (active == null || common != null && !common.equals(active)) throw unavailable();
      var serving =
          jdbc.queryForList(
              "SELECT scan_complete FROM projection_generations WHERE id=? AND state='SERVING'",
              Boolean.class,
              active);
      if (serving.size() != 1 || !Boolean.TRUE.equals(serving.getFirst())) throw unavailable();
      common = active;
    }
    var routes = routes();
    if (routes.stream().anyMatch(route -> route.physical() != 0)
        && !sources
            .get(0)
            .queryForList(
                "SELECT plan_id FROM v3_shard_cutovers WHERE state='ROUTED' LIMIT 1", String.class)
            .isEmpty()) throw unavailable();
    var fences = new HashMap<Integer, Map<Integer, Fence>>();
    for (int physical = 0; physical < 4; physical++) {
      var local = new HashMap<Integer, Fence>();
      for (var row :
          sources
              .get(physical)
              .queryForList("SELECT bucket,route_epoch,access_state FROM v3_bucket_fences")) {
        int bucket = ((Number) row.get("bucket")).intValue();
        if (local.put(
                bucket,
                new Fence(
                    ((Number) row.get("route_epoch")).longValue(),
                    row.get("access_state").toString()))
            != null) throw unavailable();
      }
      fences.put(physical, local);
    }
    for (var route : routes) {
      for (int physical = 0; physical < 4; physical++) {
        var fence = fences.get(physical).remove(route.bucket());
        if (physical == route.physical()) {
          if (fence == null || fence.epoch() != route.epoch() || !"ACTIVE".equals(fence.state()))
            throw unavailable();
        } else if (fence != null && "ACTIVE".equals(fence.state())) {
          throw unavailable();
        }
      }
    }
    for (var local : fences.values())
      if (local.values().stream().anyMatch(fence -> "ACTIVE".equals(fence.state())))
        throw unavailable();
    if (!routes.equals(routes())) throw unavailable();
    for (int physical = 0; physical < 4; physical++) {
      String active =
          sources
              .get(physical)
              .queryForObject(
                  "SELECT active_generation FROM projection_control WHERE id=1", String.class);
      if (!common.equals(active)) throw unavailable();
    }
    return common;
  }

  private List<Route> routes() {
    var rows =
        sources
            .get(0)
            .query(
                "SELECT bucket,physical_shard,epoch FROM v3_shard_routes ORDER BY bucket",
                (rs, row) -> new Route(rs.getInt(1), rs.getInt(2), rs.getLong(3)));
    if (rows.size() != 16) throw unavailable();
    for (int bucket = 0; bucket < 16; bucket++) {
      var route = rows.get(bucket);
      if (route.bucket() != bucket
          || route.physical() < 0
          || route.physical() > 3
          || route.epoch() < 1) throw unavailable();
    }
    return rows;
  }

  private static ApiException unavailable() {
    return new ApiException("SHARDED_READ_MODEL_PENDING", 503, "四库服务代或桶归属尚未一致，请查询权威支付记录");
  }
}
