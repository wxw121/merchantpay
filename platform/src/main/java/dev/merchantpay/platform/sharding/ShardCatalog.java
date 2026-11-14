package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Preparatory control plane. Plans never change the application's active DataSource. */
@Service
public class ShardCatalog {
  public record Route(int bucket, int physicalShard, long epoch) {}

  public record PhysicalShard(int id, String name, String state) {}

  public record Directory(
      List<Route> routes, List<PhysicalShard> physicalShards, boolean executionEnabled) {}

  public record Resolution(
      String kind,
      int bucket,
      int physicalShard,
      long epoch,
      boolean existenceVerified,
      boolean executionEnabled) {}

  public record Plan(
      String id,
      String requestKey,
      int bucket,
      int sourceShard,
      int targetShard,
      long sourceEpoch,
      long version,
      String state,
      String proposer,
      String reason,
      String cancelledBy,
      String cancellationReason,
      Instant createdAt,
      Instant updatedAt) {}

  private record StoredPlan(Plan value, String hash) {}

  private static final RowMapper<Route> ROUTE =
      (rs, row) -> new Route(rs.getInt("bucket"), rs.getInt("physical_shard"), rs.getLong("epoch"));
  private static final RowMapper<StoredPlan> PLAN =
      (rs, row) ->
          new StoredPlan(
              new Plan(
                  rs.getString("id"),
                  rs.getString("request_key"),
                  rs.getInt("bucket"),
                  rs.getInt("source_shard"),
                  rs.getInt("target_shard"),
                  rs.getLong("source_epoch"),
                  rs.getLong("version"),
                  rs.getString("state"),
                  rs.getString("proposer"),
                  rs.getString("reason"),
                  rs.getString("cancelled_by"),
                  rs.getString("cancellation_reason"),
                  rs.getTimestamp("created_at").toInstant(),
                  rs.getTimestamp("updated_at").toInstant()),
              rs.getString("request_hash"));

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ShardCatalog(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactions.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(5);
  }

  private static void operator(Actor actor) {
    actor.require("ADMIN", "OPS");
    if (actor.merchantId() != null && !actor.merchantId().isBlank())
      throw new ApiException("FORBIDDEN", 403, "分片目录仅对平台范围的管理员与运维开放");
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARD_PLAN_CONFLICT", 409, message);
  }

  public Directory directory(Actor actor) {
    operator(actor);
    return new Directory(
        jdbc.query("SELECT * FROM v3_shard_routes ORDER BY bucket", ROUTE),
        jdbc.query(
            "SELECT * FROM v3_physical_shards ORDER BY id",
            (rs, row) ->
                new PhysicalShard(rs.getInt("id"), rs.getString("name"), rs.getString("state"))),
        false);
  }

  public Resolution resolve(String kind, String merchant, String key, Actor actor) {
    operator(actor);
    int bucket =
        switch (Objects.toString(kind, "")) {
          case "BUSINESS" -> PaymentBuckets.forBusinessKey(merchant, key);
          case "PAYMENT" -> PaymentBuckets.forPaymentId(key);
          case "REFUND" -> PaymentBuckets.forRefundId(key);
          default -> throw new ApiException("INVALID_ROUTING_KIND", 400, "请选择商户业务号、支付号或退款号");
        };
    var route = route(bucket, false);
    return new Resolution(kind, bucket, route.physicalShard(), route.epoch(), false, false);
  }

  private Route route(int bucket, boolean lock) {
    if (bucket < 0 || bucket > 15) throw new ApiException("INVALID_BUCKET", 400, "逻辑桶必须在0至15之间");
    var rows =
        jdbc.query(
            "SELECT * FROM v3_shard_routes WHERE bucket=?" + (lock ? " FOR UPDATE" : ""),
            ROUTE,
            bucket);
    if (rows.size() != 1)
      throw new ApiException("SHARD_DIRECTORY_INCOMPLETE", 503, "分片目录缺失，请核对迁移状态");
    return rows.getFirst();
  }

  private List<StoredPlan> byKey(String key) {
    return jdbc.query("SELECT * FROM v3_shard_migrations WHERE request_key=?", PLAN, key);
  }

  private static Plan repeat(StoredPlan stored, String hash) {
    if (!stored.hash().equals(hash)) throw conflict("请求键已用于不同的迁移计划，请保留原请求或使用新请求键");
    return stored.value();
  }

  private static void reason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 1000)
      throw new ApiException("INVALID_REASON", 400, "操作原因必填且最多1000字符");
  }

  public Plan plan(
      String requestKey, int bucket, int target, long expectedEpoch, String reason, Actor actor) {
    operator(actor);
    reason(reason);
    if (requestKey == null
        || !requestKey.matches("[A-Za-z0-9-]{1,64}")
        || target < 0
        || target > 3
        || expectedEpoch < 1) throw new ApiException("INVALID_SHARD_PLAN", 400, "请求键、目标分片或目录版本无效");
    String hash = Signatures.sha256(bucket + "\0" + target + "\0" + expectedEpoch + "\0" + reason);
    var existing = byKey(requestKey);
    if (!existing.isEmpty()) return repeat(existing.getFirst(), hash);
    try {
      return tx.execute(
          status -> {
            var source = route(bucket, true);
            var repeated = byKey(requestKey);
            if (!repeated.isEmpty()) return repeat(repeated.getFirst(), hash);
            if (source.epoch() != expectedEpoch) throw conflict("目录版本已变化，请刷新后重新核对");
            if (source.physicalShard() == target) throw conflict("目标分片与当前归属相同，无需创建搬迁计划");
            if (!jdbc.queryForList(
                    "SELECT id FROM v3_shard_migrations WHERE open_bucket=?", String.class, bucket)
                .isEmpty()) throw conflict("该桶已有未取消的迁移计划，请先核对原计划");
            String id = UUID.randomUUID().toString();
            var now =
                Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            jdbc.update(
                "INSERT INTO v3_shard_migrations(id,request_key,request_hash,bucket,open_bucket,source_shard,target_shard,source_epoch,version,state,proposer,reason,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,1,'PLANNED',?,?,?,?)",
                id,
                requestKey,
                hash,
                bucket,
                bucket,
                source.physicalShard(),
                target,
                expectedEpoch,
                actor.id(),
                reason,
                now,
                now);
            audit(actor, "V3_SHARD_PLAN", id, reason);
            return row(id, false).value();
          });
    } catch (DuplicateKeyException race) {
      var winner = byKey(requestKey);
      if (!winner.isEmpty()) return repeat(winner.getFirst(), hash);
      throw conflict("该桶的计划已被其他操作占用，请刷新核对");
    }
  }

  private StoredPlan row(String id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT * FROM v3_shard_migrations WHERE id=?" + (lock ? " FOR UPDATE" : ""), PLAN, id);
    if (rows.isEmpty()) throw new ApiException("SHARD_PLAN_NOT_FOUND", 404, "迁移计划不存在");
    return rows.getFirst();
  }

  public Plan detail(String id, Actor actor) {
    operator(actor);
    return row(id, false).value();
  }

  public List<Plan> plans(String state, Actor actor) {
    operator(actor);
    if (!Set.of("PLANNED", "ROUTED", "MOVED", "CANCELLED").contains(Objects.toString(state, "")))
      throw new ApiException("INVALID_PLAN_STATE", 400, "计划筛选状态无效");
    return jdbc
        .query(
            "SELECT * FROM v3_shard_migrations WHERE state=? ORDER BY updated_at DESC,id DESC LIMIT 100",
            PLAN,
            state)
        .stream()
        .map(StoredPlan::value)
        .toList();
  }

  public Plan cancel(String id, long expectedVersion, String reason, Actor actor) {
    operator(actor);
    reason(reason);
    if (expectedVersion < 1) throw new ApiException("INVALID_PLAN_VERSION", 400, "计划版本无效");
    return tx.execute(
        status -> {
          var preview = row(id, false).value();
          route(preview.bucket(), true);
          var plan = row(id, true).value();
          if (plan.version() != expectedVersion) throw conflict("计划版本已变化，请刷新后重新核对");
          if (plan.state().equals("CANCELLED")) return plan;
          if (!plan.state().equals("PLANNED")) throw conflict("该计划已进入切流阶段，不能取消");
          if (plan.sourceShard() > 0
              && plan.targetShard() == 0
              && !jdbc.queryForList(
                      "SELECT plan_id FROM v3_shard_reverse_runs " + "WHERE plan_id=? FOR UPDATE",
                      String.class,
                      id)
                  .isEmpty()) throw conflict("回迁已开始，须按原计划继续恢复，不能取消");
          if (!jdbc.queryForList(
                  "SELECT plan_id FROM v3_shard_isolations WHERE plan_id=? AND state='QUIESCED' FOR UPDATE",
                  String.class,
                  id)
              .isEmpty()) throw conflict("该计划的来源桶仍处于隔离状态，请先解除隔离");
          int changed =
              jdbc.update(
                  "UPDATE v3_shard_migrations SET state='CANCELLED',open_bucket=NULL,version=version+1,cancelled_by=?,cancellation_reason=?,updated_at=? WHERE id=? AND state='PLANNED' AND version=?",
                  actor.id(),
                  reason,
                  Timestamp.from(clock.instant()),
                  id,
                  expectedVersion);
          if (changed != 1) throw conflict("计划状态已变化，取消未提交");
          audit(actor, "V3_SHARD_PLAN_CANCEL", id, reason);
          return row(id, false).value();
        });
  }

  private void audit(Actor actor, String action, String id, String reason) {
    jdbc.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,NULL,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor.id(),
        action,
        id,
        reason,
        Timestamp.from(clock.instant()));
  }
}
