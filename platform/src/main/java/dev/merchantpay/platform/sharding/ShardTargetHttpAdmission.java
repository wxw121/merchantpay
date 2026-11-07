package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.ApiException;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** A target HTTP request needs durable, completed cutover evidence for its exact route. */
public final class ShardTargetHttpAdmission {
  private final JdbcTemplate core;

  public ShardTargetHttpAdmission(JdbcTemplate control) {
    core = new JdbcTemplate(Objects.requireNonNull(control.getDataSource()));
    core.setQueryTimeout(3);
  }

  public void require(ShardCatalog.Route route) {
    if (route == null
        || route.bucket() < 0
        || route.bucket() > 15
        || route.physicalShard() < 1
        || route.physicalShard() > 3
        || route.epoch() < 2) throw unavailable();
    try {
      var rows =
          core.queryForList(
              """
          SELECT c.plan_id FROM v3_shard_cutovers c
          JOIN v3_shard_migrations m ON m.id=c.plan_id
          JOIN v3_shard_isolations i ON i.plan_id=c.plan_id
          JOIN v3_shard_copy_runs copy_run ON copy_run.plan_id=c.plan_id
          JOIN v3_shard_routes r ON r.bucket=c.bucket
          JOIN v3_bucket_fences source_fence ON source_fence.bucket=c.bucket
          JOIN v3_shard_identity identity_row ON identity_row.id=1
          WHERE c.bucket=? AND c.target_shard=? AND c.target_epoch=?
            AND c.state='ACTIVE' AND c.source_epoch>0
            AND c.target_epoch=c.source_epoch+1
            AND m.bucket=c.bucket AND m.source_shard=0
            AND m.target_shard=c.target_shard AND m.source_epoch=c.source_epoch
            AND m.version=c.plan_version+1 AND m.state='MOVED'
            AND i.bucket=c.bucket AND i.source_epoch=c.source_epoch
            AND i.plan_version=c.plan_version AND i.state='QUIESCED'
            AND copy_run.bucket=c.bucket AND copy_run.source_epoch=c.source_epoch
            AND copy_run.plan_version=c.plan_version AND copy_run.state='VERIFIED'
            AND copy_run.manifest_hash=c.manifest_hash
            AND copy_run.target_hash=c.manifest_hash
            AND r.physical_shard=c.target_shard AND r.epoch=c.target_epoch
            AND source_fence.route_epoch=c.source_epoch
            AND source_fence.access_state='FENCED'
            AND identity_row.physical_shard=0
          LIMIT 2
          """,
              String.class,
              route.bucket(),
              route.physicalShard(),
              route.epoch());
      if (rows.size() != 1) throw unavailable();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private static ApiException unavailable() {
    return new ApiException(
        "SHARD_TARGET_HTTP_UNAVAILABLE",
        503,
        "目标桶缺少已完成切流证据，暂不开放资金受理",
        ApiException.RetryReason.DATABASE_UNAVAILABLE);
  }
}
