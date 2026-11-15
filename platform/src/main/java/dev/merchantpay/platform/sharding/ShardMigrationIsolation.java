package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomic core-origin write isolation. Data copying and route changes are separate, disabled work.
 */
@Service
@ConditionalOnProperty(
    name = {"merchantpay.sharding.enabled", "merchantpay.sharding.migration-quiesce-enabled"},
    havingValue = "true")
public final class ShardMigrationIsolation {
  public record Isolation(
      String planId,
      int bucket,
      long sourceEpoch,
      long planVersion,
      String state,
      String isolatedBy,
      String isolationReason,
      String releasedBy,
      String releaseReason,
      Instant createdAt,
      Instant updatedAt) {}

  private static final RowMapper<Isolation> ROW =
      (rs, ignored) ->
          new Isolation(
              rs.getString("plan_id"),
              rs.getInt("bucket"),
              rs.getLong("source_epoch"),
              rs.getLong("plan_version"),
              rs.getString("state"),
              rs.getString("isolated_by"),
              rs.getString("isolation_reason"),
              rs.getString("released_by"),
              rs.getString("release_reason"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());

  private final ShardCatalog catalog;
  private final ShardMigrationPreflight preflight;
  private final JdbcTemplate core;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ShardMigrationIsolation(
      ShardCatalog catalog,
      ShardMigrationPreflight preflight,
      JdbcTemplate core,
      TransactionTemplate transactions,
      Clock clock) {
    this.catalog = catalog;
    this.preflight = preflight;
    this.core = core;
    this.clock = clock;
    tx = new TransactionTemplate(transactions.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(30);
  }

  public Isolation status(String id, Actor actor) {
    catalog.detail(id, actor);
    return row(id, false)
        .orElseThrow(() -> new ApiException("SHARD_ISOLATION_NOT_FOUND", 404, "迁移计划尚未隔离"));
  }

  public Isolation quiesce(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = corePlan(id, expectedVersion, actor);
    requireStopped(writersStopped, reason);
    if (row(id, false).isEmpty()) {
      var inspected = preflight.inspect(id, actor);
      if (!inspected.blockers().isEmpty()) throw conflict("搬迁预检存在阻断项，请先排空并核对来源");
    }
    return Objects.requireNonNull(
        tx.execute(
            ignored -> {
              lockedPlan(plan);
              var existing = row(id, true);
              var fence = fence(plan.bucket());
              if (existing.isPresent()) {
                var isolation = existing.get();
                if (!"QUIESCED".equals(isolation.state())
                    || !reason.equals(isolation.isolationReason())
                    || !fence.equals(new Fence(plan.sourceEpoch(), "FENCED")))
                  throw conflict("隔离状态或请求原因已变化，请核对原计划");
                return isolation;
              }
              if (!fence.equals(new Fence(plan.sourceEpoch(), "ACTIVE")))
                throw conflict("来源桶栅栏已变化，不能开始隔离");
              if (!targetEmpty(plan)) throw conflict("目标分片已有该桶数据，不能开始无复制隔离");
              requireCoreDrained();
              int changed =
                  core.update(
                      "UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=? AND route_epoch=? AND access_state='ACTIVE'",
                      plan.bucket(),
                      plan.sourceEpoch());
              if (changed != 1) throw conflict("来源桶栅栏已变化");
              var now = Timestamp.from(clock.instant());
              if (core.update(
                      "INSERT INTO v3_shard_isolations(plan_id,bucket,source_epoch,plan_version,state,isolated_by,isolation_reason,created_at,updated_at) VALUES (?,?,?,?,'QUIESCED',?,?,?,?)",
                      id,
                      plan.bucket(),
                      plan.sourceEpoch(),
                      expectedVersion,
                      actor.id(),
                      reason,
                      now,
                      now)
                  != 1) throw conflict("隔离记录未写入");
              audit(actor, "V3_SHARD_QUIESCE", id, reason, now);
              return row(id, false).orElseThrow();
            }));
  }

  public Isolation release(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = corePlan(id, expectedVersion, actor);
    requireStopped(writersStopped, reason);
    if (!targetEmpty(plan)) throw conflict("目标分片已有该桶数据，不能按无复制流程解除隔离");
    return Objects.requireNonNull(
        tx.execute(
            ignored -> {
              lockedPlan(plan);
              var isolation = row(id, true).orElseThrow(() -> conflict("迁移计划尚未隔离"));
              var fence = fence(plan.bucket());
              if ("RELEASED".equals(isolation.state())) {
                if (!fence.equals(new Fence(plan.sourceEpoch(), "ACTIVE")))
                  throw conflict("已解除的隔离与来源桶栅栏不一致");
                return isolation;
              }
              if (!"QUIESCED".equals(isolation.state())
                  || !fence.equals(new Fence(plan.sourceEpoch(), "FENCED")))
                throw conflict("隔离状态与来源桶栅栏不一致");
              if (!core.queryForList(
                      "SELECT plan_id FROM v3_shard_copy_runs WHERE plan_id=? AND state<>'DISCARDED' FOR UPDATE",
                      String.class,
                      id)
                  .isEmpty()) throw conflict("目标库复制已有持久记录，须先完成后续切流或安全回退");
              if (!targetEmpty(plan)) throw conflict("目标分片已有该桶数据，不能解除隔离");
              requireCoreDrained();
              int changed =
                  core.update(
                      "UPDATE v3_bucket_fences SET access_state='ACTIVE' WHERE bucket=? AND route_epoch=? AND access_state='FENCED'",
                      plan.bucket(),
                      plan.sourceEpoch());
              if (changed != 1) throw conflict("来源桶栅栏已变化");
              var now = Timestamp.from(clock.instant());
              if (core.update(
                      "UPDATE v3_shard_isolations SET state='RELEASED',released_by=?,release_reason=?,updated_at=? WHERE plan_id=? AND state='QUIESCED'",
                      actor.id(),
                      reason,
                      now,
                      id)
                  != 1) throw conflict("隔离记录已变化");
              audit(actor, "V3_SHARD_QUIESCE_RELEASE", id, reason, now);
              return row(id, false).orElseThrow();
            }));
  }

  private ShardCatalog.Plan corePlan(String id, long expectedVersion, Actor actor) {
    if (expectedVersion < 1) throw conflict("迁移计划版本无效");
    var plan = catalog.detail(id, actor);
    if (plan.sourceShard() != 0 || plan.targetShard() == 0) throw conflict("当前只支持从物理库0隔离到目标库的计划");
    if (plan.version() != expectedVersion || !"PLANNED".equals(plan.state()))
      throw conflict("迁移计划状态或版本已变化");
    return plan;
  }

  private void lockedPlan(ShardCatalog.Plan expected) {
    var route =
        core.queryForMap(
            "SELECT physical_shard,epoch FROM v3_shard_routes WHERE bucket=? FOR UPDATE",
            expected.bucket());
    if (((Number) route.get("physical_shard")).intValue() != 0
        || ((Number) route.get("epoch")).longValue() != expected.sourceEpoch())
      throw conflict("目录版本或物理来源已变化");
    var plan =
        core.queryForMap(
            "SELECT bucket,source_shard,target_shard,source_epoch,version,state FROM v3_shard_migrations WHERE id=? FOR UPDATE",
            expected.id());
    if (((Number) plan.get("bucket")).intValue() != expected.bucket()
        || ((Number) plan.get("source_shard")).intValue() != 0
        || ((Number) plan.get("target_shard")).intValue() != expected.targetShard()
        || ((Number) plan.get("source_epoch")).longValue() != expected.sourceEpoch()
        || ((Number) plan.get("version")).longValue() != expected.version()
        || !"PLANNED".equals(plan.get("state"))) throw conflict("迁移计划状态或版本已变化");
    var identities =
        core.queryForList("SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
    if (identities.size() != 1 || identities.getFirst() != 0) throw conflict("核心来源物理身份不一致");
  }

  private record Fence(long epoch, String state) {}

  private Fence fence(int bucket) {
    var rows =
        core.query(
            "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=? FOR UPDATE",
            (rs, ignored) -> new Fence(rs.getLong(1), rs.getString(2)),
            bucket);
    if (rows.size() != 1) throw conflict("来源桶栅栏缺失");
    return rows.getFirst();
  }

  private Optional<Isolation> row(String id, boolean lock) {
    var rows =
        core.query(
            "SELECT * FROM v3_shard_isolations WHERE plan_id=?" + (lock ? " FOR UPDATE" : ""),
            ROW,
            id);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private void requireCoreDrained() {
    try {
      core.execute(
          (org.springframework.jdbc.core.ConnectionCallback<Void>)
              connection -> {
                EventTransportMaintenance.requireDrained(connection);
                return null;
              });
    } catch (IllegalStateException pending) {
      throw conflict("核心来源仍有未排空的业务任务或事件");
    }
    if (Objects.requireNonNull(
            core.queryForObject(
                "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'", Long.class))
        != 0) throw conflict("核心来源仍有隔离事件");
  }

  private boolean targetEmpty(ShardCatalog.Plan plan) {
    try {
      return preflight.targetEmpty(plan);
    } catch (DataAccessException unavailable) {
      throw new ApiException("SHARD_ISOLATION_TARGET_UNAVAILABLE", 503, "无法核对目标分片是否为空，来源栅栏保持原状态");
    }
  }

  private void audit(Actor actor, String action, String id, String reason, Timestamp now) {
    core.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,NULL,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor.id(),
        action,
        id,
        reason,
        now);
  }

  private static void requireStopped(boolean writersStopped, String reason) {
    if (!writersStopped || reason == null || reason.isBlank() || reason.length() > 1000)
      throw conflict("须实际停止所有写入及消费者，并提供最多1000字的操作原因");
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARD_ISOLATION_CONFLICT", 409, message);
  }
}
