package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.ApiException;
import java.util.HashSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Core-only BULK and finance workflows stop when a bucket moves from the core. */
@Component
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
public final class ShardCoreOnlyGuard {
  private final JdbcTemplate core;

  public ShardCoreOnlyGuard(JdbcTemplate core) {
    this.core = core;
  }

  public void requireCoreOnly() {
    var rows =
        core.queryForList(
            "SELECT r.bucket,r.physical_shard,r.epoch,f.route_epoch,f.access_state "
                + "FROM v3_shard_routes r LEFT JOIN v3_bucket_fences f ON f.bucket=r.bucket");
    var buckets = new HashSet<Integer>();
    for (var row : rows) {
      int bucket = ((Number) row.get("bucket")).intValue();
      if (bucket < 0
          || bucket > 15
          || !buckets.add(bucket)
          || ((Number) row.get("physical_shard")).intValue() != 0
          || row.get("route_epoch") == null
          || ((Number) row.get("epoch")).longValue()
              != ((Number) row.get("route_epoch")).longValue()
          || !"ACTIVE".equals(row.get("access_state"))) throw unavailable();
    }
    if (buckets.size() != 16) throw unavailable();
  }

  private static ApiException unavailable() {
    return new ApiException(
        "SHARDED_CORE_WORKFLOW_PENDING", 503, "已有目标库路由或核心桶栅栏不一致，单库对账、导出和财务写入已停用");
  }
}
