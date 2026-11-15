package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline core-origin cutover. An incomplete stage keeps the bucket fenced. */
@Service
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled",
      "merchantpay.sharding.migration-cutover-enabled"
    },
    havingValue = "true")
public final class ShardMigrationCutover {
  public record Cutover(
      String planId,
      int bucket,
      long sourceEpoch,
      long targetEpoch,
      int targetShard,
      long planVersion,
      String manifestHash,
      String state,
      String initiatedBy,
      String reason,
      Instant createdAt,
      Instant updatedAt,
      Instant activatedAt) {}

  public record FinanceHandoff(
      String planId,
      int bucket,
      long sourcePayableEntries,
      long mappedEntries,
      long mappedReservations,
      long sourceReservations,
      boolean routeCompleted) {}

  private final ShardCatalog catalog;
  private final ShardMigrationReadiness readiness;
  private final ShardMigrationManifest manifests;
  private final ShardExportReadGate gate;
  private final JdbcTemplate core;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ShardMigrationCutover(
      ShardCatalog catalog,
      ShardMigrationReadiness readiness,
      ShardMigrationManifest manifests,
      ShardExportReadGate gate,
      JdbcTemplate core,
      TransactionTemplate transactions,
      Clock clock) {
    this.catalog = catalog;
    this.readiness = readiness;
    this.manifests = manifests;
    this.gate = gate;
    this.core = core;
    this.clock = clock;
    tx = new TransactionTemplate(transactions.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(21_600);
  }

  public Cutover read(String id, Actor actor) {
    catalog.detail(id, actor);
    return stored(id)
        .orElseThrow(() -> new ApiException("SHARD_CUTOVER_NOT_FOUND", 404, "该计划尚未开始切流"));
  }

  public FinanceHandoff financeHandoff(String id, Actor actor) {
    var plan = catalog.detail(id, actor);
    String prefix = "p" + Integer.toHexString(plan.bucket()) + "_";
    var sourceJdbc = new JdbcTemplate(gate.source(plan.sourceShard()));
    long source =
        Objects.requireNonNull(
            sourceJdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE LEFT(payment_id,3)=? AND account_code='MERCHANT_PAYABLE'",
                Long.class,
                prefix));
    long aliases = count("SELECT COUNT(*) FROM v3_shard_finance_handoff_hops WHERE plan_id=?", id);
    long transferred =
        count(
            "SELECT COUNT(*) FROM v3_shard_finance_handoff_hops h JOIN settlement_reservations r ON r.physical_shard=h.target_shard AND r.entry_id=h.target_entry_id WHERE h.plan_id=?",
            id);
    long old =
        count(
            "SELECT COUNT(*) FROM settlement_reservations r JOIN "
                + "v3_shard_finance_handoff_hops h ON h.source_shard=r.physical_shard "
                + "AND h.source_entry_id=r.entry_id WHERE h.plan_id=?",
            id);
    var completed = stored(id);
    return new FinanceHandoff(
        id,
        plan.bucket(),
        source,
        aliases,
        transferred,
        old,
        completed.isPresent()
            && "ACTIVE".equals(completed.get().state())
            && source == aliases
            && old == 0);
  }

  private long count(String sql, Object... args) {
    return Objects.requireNonNull(core.queryForObject(sql, Long.class, args));
  }

  public Cutover execute(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = catalog.detail(id, actor);
    if (!writersStopped
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000
        || expectedVersion < 1
        || expectedVersion == Long.MAX_VALUE
        || plan.sourceShard() != 0
        || plan.targetShard() == 0) throw conflict("须保持停写并提供有效的物理0来源切流计划及原因");
    try {
      var prior = stored(id);
      if (prior.isEmpty()) {
        if (!"PLANNED".equals(plan.state()) || plan.version() != expectedVersion)
          throw conflict("计划状态或版本已变化");
        prior =
            java.util.Optional.of(
                Objects.requireNonNull(tx.execute(ignored -> prepare(plan, reason, actor))));
      }
      check(prior.orElseThrow(), plan, expectedVersion, reason);
      if ("PREPARING".equals(prior.get().state())) {
        var preparing = prior.get();
        prepareTargetFence(preparing);
        tx.executeWithoutResult(ignored -> route(plan, preparing, actor));
      }
      var routed = stored(id).orElseThrow();
      if ("ROUTED".equals(routed.state())) {
        tx.executeWithoutResult(ignored -> activate(plan, routed, actor));
        tx.executeWithoutResult(ignored -> finish(plan, routed, actor));
      }
      var completed = stored(id).orElseThrow();
      check(completed, catalog.detail(id, actor), expectedVersion, reason);
      if (!"ACTIVE".equals(completed.state())) throw conflict("切流尚未完成，请保持停写后重试");
      var observed = gate.capture();
      var route = observed.routes().get(plan.bucket());
      if (route.physical() != completed.targetShard()
          || route.epoch() != completed.targetEpoch()
          || !observed.retiredSources().containsKey(plan.bucket()))
        throw conflict("已完成切流的目录或来源栅栏不一致");
      return completed;
    } catch (DataAccessException unavailable) {
      var failure = new ApiException("SHARD_CUTOVER_UNAVAILABLE", 503, "无法完整核对切流阶段，请保持停写并按原版本重试");
      failure.initCause(unavailable);
      throw failure;
    }
  }

  private Cutover prepare(ShardCatalog.Plan plan, String reason, Actor actor) {
    var report = readiness.requireReadyLocked(plan, actor);
    var existing = stored(plan.id());
    if (existing.isPresent()) return existing.get();
    if (plan.sourceEpoch() == Long.MAX_VALUE) throw conflict("来源目录版本无法递增");
    var now = Timestamp.from(clock.instant());
    if (core.update(
            "INSERT INTO v3_shard_cutovers(plan_id,bucket,source_epoch,target_epoch,target_shard,plan_version,manifest_hash,state,initiated_by,reason,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PREPARING',?,?,?,?)",
            plan.id(),
            plan.bucket(),
            plan.sourceEpoch(),
            plan.sourceEpoch() + 1,
            plan.targetShard(),
            plan.version(),
            report.manifestHash(),
            actor.id(),
            reason,
            now,
            now)
        != 1) throw conflict("切流准备记录未写入");
    audit(actor, "V3_SHARD_CUTOVER_PREPARE", plan.id(), reason);
    return stored(plan.id()).orElseThrow();
  }

  private void prepareTargetFence(Cutover cutover) {
    try (var connection = gate.source(cutover.targetShard()).getConnection()) {
      connection.setAutoCommit(false);
      try {
        var target = jdbc(connection);
        identity(target, cutover.targetShard());
        var fences =
            target.queryForList(
                "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=? FOR UPDATE",
                cutover.bucket());
        if (fences.isEmpty()) {
          if (target.update(
                  "INSERT INTO v3_bucket_fences(bucket,route_epoch,access_state) VALUES (?,?,'FENCED')",
                  cutover.bucket(),
                  cutover.targetEpoch())
              != 1) throw conflict("目标桶栅栏未准备");
        } else if (fences.size() != 1
            || !matches(fences.getFirst(), cutover.targetEpoch(), "FENCED")) {
          throw conflict("目标桶已有不同版本或活跃的栅栏");
        }
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException unavailable) {
      throw new ApiException("SHARD_CUTOVER_TARGET_UNAVAILABLE", 503, "无法准备目标桶栅栏，来源仍保持隔离");
    }
  }

  private void route(ShardCatalog.Plan plan, Cutover cutover, Actor actor) {
    readiness.requireReadyLocked(plan, actor);
    check(stored(plan.id()).orElseThrow(), plan, cutover.planVersion(), cutover.reason());
    if (!"PREPARING".equals(stored(plan.id()).orElseThrow().state())) throw conflict("切流阶段已变化，请重试");
    targetFence(cutover, "FENCED");
    transferFinanceEntries(plan);
    if (core.update(
            "UPDATE v3_shard_routes SET physical_shard=?,epoch=? WHERE bucket=? AND physical_shard=0 AND epoch=?",
            cutover.targetShard(),
            cutover.targetEpoch(),
            cutover.bucket(),
            cutover.sourceEpoch())
        != 1) throw conflict("目录来源或版本已变化");
    var now = Timestamp.from(clock.instant());
    if (core.update(
                "UPDATE v3_shard_migrations SET state='ROUTED',updated_at=? WHERE id=? AND state='PLANNED' AND version=?",
                now,
                plan.id(),
                cutover.planVersion())
            != 1
        || core.update(
                "UPDATE v3_shard_cutovers SET state='ROUTED',updated_at=? WHERE plan_id=? AND state='PREPARING'",
                now,
                plan.id())
            != 1) throw conflict("计划或切流阶段已变化");
    audit(actor, "V3_SHARD_CUTOVER_ROUTE", plan.id(), cutover.reason());
  }

  /** Core entry aliases and reservation ownership change atomically with the route. */
  private void transferFinanceEntries(ShardCatalog.Plan plan) {
    String prefix = "p" + Integer.toHexString(plan.bucket()) + "_";
    var target = new JdbcTemplate(gate.source(plan.targetShard()));
    long cursor = 0;
    while (true) {
      var entriesToMove =
          core.queryForList(
              "SELECT e.id AS entry_id,e.payment_id,e.merchant_id,e.journal_id,e.account_code,r.settlement_id,r.revision FROM ledger_entries e LEFT JOIN settlement_reservations r ON r.physical_shard=? AND r.entry_id=e.id WHERE LEFT(e.payment_id,3)=? AND e.account_code='MERCHANT_PAYABLE' AND e.id>? ORDER BY e.id LIMIT 500 FOR UPDATE",
              plan.sourceShard(),
              prefix,
              cursor);
      if (entriesToMove.isEmpty()) break;
      for (var row : entriesToMove) {
        long sourceId = ((Number) row.get("entry_id")).longValue();
        var mapped =
            target.queryForList(
                "SELECT target_id FROM v3_shard_copy_id_map WHERE plan_id=? AND entity_name='ledger_entries' AND source_id=?",
                Long.class,
                plan.id(),
                sourceId);
        if (mapped.size() > 1) throw conflict("目标分录映射不唯一");
        long targetId = mapped.isEmpty() ? sourceId : mapped.getFirst();
        var entries =
            target.queryForList(
                "SELECT payment_id,merchant_id,journal_id,account_code FROM ledger_entries WHERE id=?",
                targetId);
        if (entries.size() != 1) throw conflict("结算预占的目标分录缺失");
        var entry = entries.getFirst();
        for (String column : List.of("payment_id", "merchant_id", "journal_id", "account_code"))
          if (!Objects.equals(entry.get(column), row.get(column))) throw conflict("结算预占的目标分录与来源不符");
        recordFinanceHop(core, plan, sourceId, targetId, row);
        if (row.get("settlement_id") != null
            && core.update(
                    "UPDATE settlement_reservations SET physical_shard=?,entry_id=? WHERE physical_shard=? AND entry_id=? AND settlement_id=? AND revision=?",
                    plan.targetShard(),
                    targetId,
                    plan.sourceShard(),
                    sourceId,
                    row.get("settlement_id"),
                    row.get("revision"))
                != 1) throw conflict("结算预占交接失败，目录未切换");
        cursor = sourceId;
      }
    }
  }

  static void recordFinanceHop(
      JdbcTemplate core,
      ShardCatalog.Plan plan,
      long sourceId,
      long targetId,
      java.util.Map<String, Object> row) {
    if (core.update(
            "INSERT INTO v3_shard_finance_handoff_hops(plan_id,source_shard,source_entry_id,"
                + "target_shard,target_entry_id,merchant_id,payment_id) VALUES(?,?,?,?,?,?,?)",
            plan.id(),
            plan.sourceShard(),
            sourceId,
            plan.targetShard(),
            targetId,
            row.get("merchant_id"),
            row.get("payment_id"))
        != 1) throw conflict("结算分录逐次交接证据未写入");
    var aliases =
        core.queryForList(
            "SELECT source_shard,source_entry_id,merchant_id,payment_id FROM "
                + "v3_shard_finance_handoffs WHERE target_shard=? AND target_entry_id=? FOR UPDATE",
            plan.sourceShard(),
            sourceId);
    if (aliases.size() > 1) throw conflict("结算来源有多个原始编号");
    if (aliases.isEmpty()) {
      if (core.update(
              "INSERT INTO v3_shard_finance_handoffs(plan_id,source_shard,"
                  + "source_entry_id,target_shard,target_entry_id,merchant_id,payment_id) "
                  + "VALUES(?,?,?,?,?,?,?)",
              plan.id(),
              plan.sourceShard(),
              sourceId,
              plan.targetShard(),
              targetId,
              row.get("merchant_id"),
              row.get("payment_id"))
          != 1) throw conflict("结算来源当前归属未写入");
    } else {
      var alias = aliases.getFirst();
      if (!Objects.equals(alias.get("merchant_id"), row.get("merchant_id"))
          || !Objects.equals(alias.get("payment_id"), row.get("payment_id"))
          || core.update(
                  "UPDATE v3_shard_finance_handoffs SET target_shard=?,target_entry_id=? "
                      + "WHERE source_shard=? AND source_entry_id=? AND target_shard=? "
                      + "AND target_entry_id=?",
                  plan.targetShard(),
                  targetId,
                  alias.get("source_shard"),
                  alias.get("source_entry_id"),
                  plan.sourceShard(),
                  sourceId)
              != 1) throw conflict("结算来源当前归属或原始事实已变化");
    }
  }

  private void activate(ShardCatalog.Plan plan, Cutover cutover, Actor actor) {
    routedCore(plan, cutover);
    var manifest = manifests.read(plan.id(), actor);
    if (!manifest.hash().equals(cutover.manifestHash())
        || !ShardMigrationManifest.snapshot(core, plan.bucket()).equals(manifest.tables()))
      throw conflict("来源数据或清单在目录切换后发生变化");
    try (var connection = gate.source(cutover.targetShard()).getConnection()) {
      connection.setAutoCommit(false);
      try {
        var target = jdbc(connection);
        identity(target, cutover.targetShard());
        var fences =
            target.queryForList(
                "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=? FOR UPDATE",
                cutover.bucket());
        if (fences.size() != 1) throw conflict("目标桶栅栏缺失");
        var fence = fences.getFirst();
        if (matches(fence, cutover.targetEpoch(), "FENCED")) {
          if (!ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id())
              .equals(manifest.tables())) throw conflict("目标数据在激活前发生变化");
          EventTransportMaintenance.requireDrained(connection);
          if (target.update(
                  "UPDATE v3_bucket_fences SET access_state='ACTIVE' WHERE bucket=? AND route_epoch=? AND access_state='FENCED'",
                  cutover.bucket(),
                  cutover.targetEpoch())
              != 1) throw conflict("目标桶栅栏激活失败");
        } else {
          if (!matches(fence, cutover.targetEpoch(), "ACTIVE")) throw conflict("目标桶栅栏与目录版本不一致");
          if (!ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id())
              .equals(manifest.tables())) throw conflict("已激活的目标数据与复制清单不一致");
          EventTransportMaintenance.requireDrained(connection);
        }
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException unavailable) {
      throw new ApiException("SHARD_CUTOVER_TARGET_UNAVAILABLE", 503, "目标栅栏尚未激活，请保持停写并重试");
    } catch (IllegalStateException pending) {
      throw conflict("目标业务任务尚未排空，目标栅栏仍保持隔离");
    }
  }

  private void finish(ShardCatalog.Plan plan, Cutover cutover, Actor actor) {
    routedCore(plan, cutover);
    var manifest = manifests.read(plan.id(), actor);
    if (!manifest.hash().equals(cutover.manifestHash())
        || !ShardMigrationManifest.snapshot(core, plan.bucket()).equals(manifest.tables()))
      throw conflict("最终提交前来源数据或清单已变化");
    targetFence(cutover, "ACTIVE");
    var target = new JdbcTemplate(gate.source(cutover.targetShard()));
    if (!ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id())
        .equals(manifest.tables())) throw conflict("最终提交前目标数据已变化");
    var now = Timestamp.from(clock.instant());
    if (core.update(
                "UPDATE v3_shard_migrations SET state='MOVED',open_bucket=NULL,version=version+1,updated_at=? WHERE id=? AND state='ROUTED' AND version=?",
                now,
                plan.id(),
                cutover.planVersion())
            != 1
        || core.update(
                "UPDATE v3_shard_cutovers SET state='ACTIVE',activated_at=?,updated_at=? WHERE plan_id=? AND state='ROUTED'",
                now,
                now,
                plan.id())
            != 1) throw conflict("计划最终状态未提交，请保持停写并重试");
    audit(actor, "V3_SHARD_CUTOVER_ACTIVE", plan.id(), cutover.reason());
  }

  private void routedCore(ShardCatalog.Plan plan, Cutover cutover) {
    var route =
        core.queryForMap(
            "SELECT physical_shard,epoch FROM v3_shard_routes WHERE bucket=? FOR UPDATE",
            plan.bucket());
    if (!matchesRoute(route, cutover.targetShard(), cutover.targetEpoch()))
      throw conflict("切流目录与目标版本不一致");
    var storedPlan =
        core.queryForMap(
            "SELECT state,version,source_shard,target_shard,source_epoch FROM v3_shard_migrations WHERE id=? FOR UPDATE",
            plan.id());
    if (!"ROUTED".equals(storedPlan.get("state"))
        || ((Number) storedPlan.get("version")).longValue() != cutover.planVersion()
        || ((Number) storedPlan.get("source_shard")).intValue() != 0
        || ((Number) storedPlan.get("target_shard")).intValue() != cutover.targetShard()
        || ((Number) storedPlan.get("source_epoch")).longValue() != cutover.sourceEpoch())
      throw conflict("计划未保持待激活状态");
    var sourceFence =
        core.queryForMap(
            "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=? FOR UPDATE",
            plan.bucket());
    if (!matches(sourceFence, cutover.sourceEpoch(), "FENCED")) throw conflict("来源桶栅栏已变化");
    var current = stored(plan.id()).orElseThrow();
    if (!"ROUTED".equals(current.state())
        || current.targetEpoch() != cutover.targetEpoch()
        || !current.manifestHash().equals(cutover.manifestHash())) throw conflict("切流记录未保持待激活状态");
  }

  private void targetFence(Cutover cutover, String state) {
    var target = new JdbcTemplate(gate.source(cutover.targetShard()));
    identity(target, cutover.targetShard());
    var fences =
        target.queryForList(
            "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=?",
            cutover.bucket());
    if (fences.size() != 1 || !matches(fences.getFirst(), cutover.targetEpoch(), state))
      throw conflict("目标桶栅栏状态或版本已变化");
  }

  private static JdbcTemplate jdbc(Connection connection) {
    var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    jdbc.setQueryTimeout(21_600);
    return jdbc;
  }

  private static void identity(JdbcTemplate jdbc, int physical) {
    var rows =
        jdbc.queryForList("SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
    if (rows.size() != 1 || rows.getFirst() != physical) throw conflict("目标库物理身份不一致");
  }

  private static boolean matches(java.util.Map<String, Object> row, long epoch, String state) {
    return ((Number) row.get("route_epoch")).longValue() == epoch
        && state.equals(row.get("access_state"));
  }

  private static boolean matchesRoute(java.util.Map<String, Object> row, int physical, long epoch) {
    return ((Number) row.get("physical_shard")).intValue() == physical
        && ((Number) row.get("epoch")).longValue() == epoch;
  }

  private static void rollback(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollback) {
      failure.addSuppressed(rollback);
    }
  }

  private void check(Cutover row, ShardCatalog.Plan plan, long expectedVersion, String reason) {
    if (row.bucket() != plan.bucket()
        || row.sourceEpoch() != plan.sourceEpoch()
        || row.targetShard() != plan.targetShard()
        || row.targetEpoch() != row.sourceEpoch() + 1
        || row.planVersion() != expectedVersion
        || !row.reason().equals(reason)
        || !List.of("PREPARING", "ROUTED", "ACTIVE").contains(row.state()))
      throw conflict("切流记录与计划、版本或原因不一致");
    if ("ACTIVE".equals(row.state())
        && (plan.version() != expectedVersion + 1 || !"MOVED".equals(plan.state())))
      throw conflict("已完成切流与计划状态不一致");
    if ("PREPARING".equals(row.state())
        && (plan.version() != expectedVersion || !"PLANNED".equals(plan.state())))
      throw conflict("准备中的切流与计划状态不一致");
    if ("ROUTED".equals(row.state())
        && (plan.version() != expectedVersion || !"ROUTED".equals(plan.state())))
      throw conflict("未完成切流与计划状态不一致");
  }

  private java.util.Optional<Cutover> stored(String id) {
    var rows =
        core.query(
            "SELECT * FROM v3_shard_cutovers WHERE plan_id=?",
            (rs, ignored) ->
                new Cutover(
                    rs.getString("plan_id"),
                    rs.getInt("bucket"),
                    rs.getLong("source_epoch"),
                    rs.getLong("target_epoch"),
                    rs.getInt("target_shard"),
                    rs.getLong("plan_version"),
                    rs.getString("manifest_hash"),
                    rs.getString("state"),
                    rs.getString("initiated_by"),
                    rs.getString("reason"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getTimestamp("activated_at") == null
                        ? null
                        : rs.getTimestamp("activated_at").toInstant()),
            id);
    return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.getFirst());
  }

  private void audit(Actor actor, String action, String id, String reason) {
    core.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,NULL,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor.id(),
        action,
        id,
        reason,
        Timestamp.from(clock.instant()));
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARD_CUTOVER_CONFLICT", 409, message);
  }
}
