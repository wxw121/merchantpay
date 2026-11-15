package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Resumable noncore-to-core move. Each request commits at most one cleanup or copy page. */
@Service
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled", "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled", "merchantpay.sharding.migration-batch-enabled",
      "merchantpay.sharding.migration-cutover-enabled", "merchantpay.sharding.reverse-enabled"
    },
    havingValue = "true")
public final class ShardReverseMigration {
  public record Status(
      String planId, int bucket, String state, String table, long copied, long total) {}

  private final ShardCatalog catalog;
  private final ShardExportReadGate gate;
  private final JdbcTemplate core;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ShardReverseMigration(
      ShardCatalog catalog,
      ShardExportReadGate gate,
      JdbcTemplate core,
      TransactionTemplate transactions,
      Clock clock) {
    this.catalog = catalog;
    this.gate = gate;
    this.core = core;
    this.clock = clock;
    tx = new TransactionTemplate(transactions.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(21_600);
  }

  public Status next(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = catalog.detail(id, actor);
    if (!writersStopped
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000
        || expectedVersion < 1
        || expectedVersion == Long.MAX_VALUE
        || plan.sourceEpoch() == Long.MAX_VALUE
        || plan.version() != expectedVersion && plan.version() != expectedVersion + 1
        || plan.sourceShard() < 1
        || plan.targetShard() != 0
        || !List.of("PLANNED", "MOVED").contains(plan.state()))
      throw conflict("反向搬迁须保持停写、有效计划版本及原因");
    var run = readRun(id);
    if ("MOVED".equals(plan.state())) {
      if (run == null
          || !"ACTIVE".equals(run.get("state"))
          || plan.version() != expectedVersion + 1) throw conflict("回迁完成状态不一致");
      gate.capture();
      return status(plan, "ACTIVE", null, 0, 0);
    }
    if (run == null) {
      tx.executeWithoutResult(ignored -> prepare(plan, reason, actor));
      return status(plan, "PREPARED", null, 0, 0);
    }
    verifyRun(plan, run, reason);
    String state = (String) run.get("state");
    if ("PREPARED".equals(state)) {
      fenceSource(plan);
      tx.executeWithoutResult(ignored -> recordSource(plan, reason, actor));
      return status(plan, "SOURCE_FENCED", null, 0, 0);
    }
    if ("SOURCE_FENCED".equals(state) || "CLEANING".equals(state))
      return Objects.requireNonNull(tx.execute(ignored -> cleanNext(plan)));
    if ("COPYING".equals(state))
      return Objects.requireNonNull(tx.execute(ignored -> copyNext(plan, reason, actor)));
    if ("VERIFIED".equals(state))
      return Objects.requireNonNull(tx.execute(ignored -> activate(plan, reason, actor)));
    throw conflict("回迁状态无效");
  }

  public Status status(String id, Actor actor) {
    var plan = catalog.detail(id, actor);
    if (plan.sourceShard() < 1 || plan.targetShard() != 0) throw conflict("该计划不是反向搬迁");
    var run = readRun(id);
    if (run == null) {
      if (!"PLANNED".equals(plan.state())) throw conflict("回迁完成记录缺失");
      return status(plan, "NOT_STARTED", null, 0, 0);
    }
    if (((Number) run.get("bucket")).intValue() != plan.bucket()
        || ((Number) run.get("source_shard")).intValue() != plan.sourceShard()
        || ((Number) run.get("source_epoch")).longValue() != plan.sourceEpoch()
        || !(((Number) run.get("plan_version")).longValue() == plan.version()
            || "ACTIVE".equals(run.get("state"))
                && ((Number) run.get("plan_version")).longValue() + 1 == plan.version()))
      throw conflict("回迁记录与计划归属不一致");
    return status(plan, (String) run.get("state"), null, 0, 0);
  }

  private void prepare(ShardCatalog.Plan plan, String reason, Actor actor) {
    lockedPlan(plan);
    var snapshot = gate.capture();
    var route = snapshot.routes().get(plan.bucket());
    if (route.physical() != plan.sourceShard() || route.epoch() != plan.sourceEpoch())
      throw conflict("当前目录不是回迁来源");
    var prior =
        core.queryForList(
            "SELECT plan_id,manifest_hash FROM v3_shard_cutovers "
                + "WHERE bucket=? AND target_shard=? AND target_epoch=? AND state='ACTIVE'",
            plan.bucket(),
            plan.sourceShard(),
            plan.sourceEpoch());
    if (prior.size() != 1) throw conflict("缺少唯一的已完成前向搬迁");
    String predecessor = (String) prior.getFirst().get("plan_id");
    var old = manifest(predecessor);
    if (!old.hash().equals(prior.getFirst().get("manifest_hash"))
        || !ShardMigrationManifest.snapshot(core, plan.bucket()).equals(old.tables()))
      throw conflict("核心旧副本与前次搬迁清单不一致，不能清理");
    var source = source(plan);
    identity(source, plan.sourceShard());
    sourceFence(source, plan, "ACTIVE");
    allDrained();
    drained(source, plan);
    String prefix = paymentPrefix(plan.bucket());
    if (count(
                core,
                "SELECT COUNT(*) FROM settlement_lines l LEFT JOIN finance_settlements s "
                    + "ON s.id=l.settlement_id WHERE LEFT(l.payment_id,3)=? AND "
                    + "(s.id IS NULL OR s.status NOT IN ('MOCK_PAID','CARRIED_FORWARD','CANCELLED'))",
                prefix)
            != 0
        || count(
                core,
                "SELECT COUNT(*) FROM sharded_adjustment_intents "
                    + "WHERE LEFT(payment_id,3)=? AND status='PREPARED'",
                prefix)
            != 0) throw conflict("结算或财务调整尚未完成");
    verifyPaidReservations(prefix);
    if (core.update(
            "INSERT INTO v3_shard_reverse_runs(plan_id,predecessor_plan_id,bucket,"
                + "source_shard,source_epoch,target_epoch,plan_version,state,requested_by,reason,"
                + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PREPARED',?,?,?,?)",
            plan.id(),
            predecessor,
            plan.bucket(),
            plan.sourceShard(),
            plan.sourceEpoch(),
            plan.sourceEpoch() + 1,
            plan.version(),
            actor.id(),
            reason,
            now(),
            now())
        != 1) throw conflict("回迁准备记录未写入");
  }

  private void fenceSource(ShardCatalog.Plan plan) {
    try (var connection = gate.source(plan.sourceShard()).getConnection()) {
      connection.setAutoCommit(false);
      try {
        var source = jdbc(connection);
        identity(source, plan.sourceShard());
        var fences =
            source.queryForList(
                "SELECT route_epoch,access_state FROM v3_bucket_fences "
                    + "WHERE bucket=? FOR UPDATE",
                plan.bucket());
        if (fences.size() != 1 || epoch(fences.getFirst()) != plan.sourceEpoch())
          throw conflict("来源桶栅栏版本已变化");
        if ("ACTIVE".equals(fences.getFirst().get("access_state"))) {
          drained(source, plan);
          if (source.update(
                  "UPDATE v3_bucket_fences SET access_state='FENCED' "
                      + "WHERE bucket=? AND route_epoch=? AND access_state='ACTIVE'",
                  plan.bucket(),
                  plan.sourceEpoch())
              != 1) throw conflict("来源桶隔离失败");
        } else if (!"FENCED".equals(fences.getFirst().get("access_state"))) {
          throw conflict("来源桶栅栏状态无效");
        }
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        throw failure;
      }
    } catch (SQLException unavailable) {
      throw new ApiException("SHARD_REVERSE_SOURCE_UNAVAILABLE", 503, "来源栅栏无法确认；须保持停写并按相同计划重试");
    }
  }

  private void recordSource(ShardCatalog.Plan plan, String reason, Actor actor) {
    lockedPlan(plan);
    var run = readRun(plan.id());
    verifyRun(plan, run, reason);
    if (!"PREPARED".equals(run.get("state"))) throw conflict("来源隔离阶段已变化");
    var source = source(plan);
    sourceFence(source, plan, "FENCED");
    drained(source, plan);
    var tables = ShardMigrationManifest.snapshot(source, plan.bucket());
    String hash = ShardMigrationManifest.manifestHash(tables);
    core.update(
        "INSERT INTO v3_shard_isolations(plan_id,bucket,source_epoch,plan_version,"
            + "state,isolated_by,isolation_reason,created_at,updated_at) "
            + "VALUES(?,?,?,?,'QUIESCED',?,?,?,?)",
        plan.id(),
        plan.bucket(),
        plan.sourceEpoch(),
        plan.version(),
        actor.id(),
        reason,
        now(),
        now());
    core.update(
        "INSERT INTO v3_shard_source_manifests(plan_id,bucket,source_epoch,"
            + "plan_version,table_count,manifest_hash,recorded_by,reason,created_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?)",
        plan.id(),
        plan.bucket(),
        plan.sourceEpoch(),
        plan.version(),
        tables.size(),
        hash,
        actor.id(),
        reason,
        now());
    for (var table : tables)
      core.update(
          "INSERT INTO v3_shard_source_manifest_tables(plan_id,table_name,"
              + "source_count,source_hash) VALUES(?,?,?,?)",
          plan.id(),
          table.name(),
          table.count(),
          table.hash());
    core.update(
        "UPDATE v3_shard_reverse_runs SET state='SOURCE_FENCED',source_hash=?,"
            + "updated_at=? WHERE plan_id=? AND state='PREPARED'",
        hash,
        now(),
        plan.id());
  }

  private Status cleanNext(ShardCatalog.Plan plan) {
    lockedPlan(plan);
    var run = readRun(plan.id());
    var predecessor = manifest((String) run.get("predecessor_plan_id"));
    if (!predecessor.tables().equals(ShardMigrationManifest.snapshot(core, plan.bucket()))
        && "SOURCE_FENCED".equals(run.get("state"))) throw conflict("清理前核心旧副本与前次清单不一致");
    sourceFence(source(plan), plan, "FENCED");
    core.update(
        "UPDATE v3_shard_reverse_runs SET state='CLEANING',updated_at=? "
            + "WHERE plan_id=? AND state='SOURCE_FENCED'",
        now(),
        plan.id());
    var probes = new ArrayList<>(ShardMigrationPreflight.BUCKET_PROBES);
    Collections.reverse(probes);
    for (var probe : probes) {
      int deleted = deletePage(core, probe, plan.bucket());
      if (deleted > 0) return status(plan, "CLEANING", probe.name(), deleted, 0);
    }
    for (var probe : ShardMigrationPreflight.BUCKET_PROBES)
      if (!core.queryForList(
              probe.existenceSql(), ShardMigrationPreflight.probeArgs(probe, plan.bucket()))
          .isEmpty()) throw conflict("核心旧副本清理不完整");
    var fence =
        core.queryForMap(
            "SELECT route_epoch,access_state FROM v3_bucket_fences " + "WHERE bucket=? FOR UPDATE",
            plan.bucket());
    if (epoch(fence) != 1 && epoch(fence) >= plan.sourceEpoch()
        || !"FENCED".equals(fence.get("access_state"))) throw conflict("核心旧桶栅栏状态异常");
    core.update(
        "UPDATE v3_bucket_fences SET route_epoch=? WHERE bucket=? "
            + "AND route_epoch=? AND access_state='FENCED'",
        plan.sourceEpoch() + 1,
        plan.bucket(),
        epoch(fence));
    var current = manifest(plan.id());
    core.update(
        "INSERT INTO v3_shard_copy_batch_plans(plan_id,bucket,source_epoch,"
            + "plan_version,source_hash,copy_reason,state,created_at,updated_at) "
            + "VALUES(?,?,?,?,?,?,'COPYING',?,?)",
        plan.id(),
        plan.bucket(),
        plan.sourceEpoch(),
        plan.version(),
        current.hash(),
        current.reason(),
        now(),
        now());
    core.update(
        "UPDATE v3_shard_reverse_runs SET state='COPYING',updated_at=? "
            + "WHERE plan_id=? AND state='CLEANING'",
        now(),
        plan.id());
    return status(plan, "COPYING", null, 0, 0);
  }

  private Status copyNext(ShardCatalog.Plan plan, String reason, Actor actor) {
    lockedPlan(plan);
    var source = source(plan);
    sourceFence(source, plan, "FENCED");
    var targetFence =
        core.queryForMap(
            "SELECT route_epoch,access_state FROM " + "v3_bucket_fences WHERE bucket=? FOR UPDATE",
            plan.bucket());
    if (epoch(targetFence) != plan.sourceEpoch() + 1
        || !"FENCED".equals(targetFence.get("access_state"))) throw conflict("复制期间核心目标桶未保持隔离");
    var manifest = manifest(plan.id());
    if (!reason.equals(manifest.reason())) throw conflict("续跑原因与来源清单不一致");
    var batch =
        core.queryForMap(
            "SELECT state,source_hash,copy_reason FROM "
                + "v3_shard_copy_batch_plans WHERE plan_id=? FOR UPDATE",
            plan.id());
    if (!"COPYING".equals(batch.get("state"))
        || !manifest.hash().equals(batch.get("source_hash"))
        || !reason.equals(batch.get("copy_reason"))) throw conflict("复制批次证据不一致");
    for (var probe : ShardMigrationPreflight.BUCKET_PROBES) {
      var expected =
          manifest.tables().stream()
              .filter(t -> t.name().equals(probe.name()))
              .findFirst()
              .orElseThrow();
      var progress = ShardMigrationBatchCopy.progress(core, plan.id(), probe.name());
      if (progress == null) {
        if (!core.queryForList(
                probe.existenceSql(), ShardMigrationPreflight.probeArgs(probe, plan.bucket()))
            .isEmpty()) throw conflict("目标表出现未经登记的行");
        boolean remap = List.of("ledger_entries", "domain_events").contains(probe.name());
        core.update(
            "INSERT INTO v3_shard_copy_batch_tables(plan_id,table_name,source_count,"
                + "source_hash,remap_id) VALUES(?,?,?,?,?)",
            plan.id(),
            probe.name(),
            expected.count(),
            expected.hash(),
            remap);
        progress = ShardMigrationBatchCopy.progress(core, plan.id(), probe.name());
      }
      ShardMigrationBatchCopy.verifyProgress(progress, expected);
      if (progress.complete()) continue;
      var page = ShardMigrationBatchCopy.page(source, probe, plan.bucket(), progress);
      long copied = Math.addExact(progress.copiedCount(), page.rows().size());
      if (copied > expected.count() || page.rows().isEmpty() && copied != expected.count())
        throw conflict("来源页与来源清单行数不一致");
      boolean remap = progress.remapId();
      core.execute(
          (ConnectionCallback<Void>)
              connection -> {
                ShardMigrationBatchCopy.insert(connection, probe, plan.id(), remap, page);
                return null;
              });
      String a = page.rows().isEmpty() ? progress.cursorA() : page.rows().getLast().cursorA();
      String b = page.rows().isEmpty() ? progress.cursorB() : page.rows().getLast().cursorB();
      core.update(
          "UPDATE v3_shard_copy_batch_tables SET copied_count=?,cursor_a=?,"
              + "cursor_b=?,complete=? WHERE plan_id=? AND table_name=? AND copied_count=?",
          copied,
          a,
          b,
          copied == expected.count(),
          plan.id(),
          probe.name(),
          progress.copiedCount());
      return status(plan, "COPYING", probe.name(), copied, expected.count());
    }
    if (!ShardMigrationManifest.snapshot(source, plan.bucket()).equals(manifest.tables())
        || !ShardMigrationManifest.snapshot(core, plan.bucket(), plan.id())
            .equals(manifest.tables())) throw conflict("来源与目标逐表摘要不一致");
    long total = manifest.tables().stream().mapToLong(ShardMigrationManifest.Table::count).sum();
    core.update(
        "UPDATE v3_shard_copy_batch_plans SET state='VERIFIED',updated_at=? "
            + "WHERE plan_id=? AND state='COPYING'",
        now(),
        plan.id());
    core.update(
        "INSERT INTO v3_shard_copy_runs(plan_id,bucket,source_epoch,plan_version,"
            + "manifest_hash,target_hash,total_rows,copied_by,reason,created_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?)",
        plan.id(),
        plan.bucket(),
        plan.sourceEpoch(),
        plan.version(),
        manifest.hash(),
        manifest.hash(),
        total,
        actor.id(),
        reason,
        now());
    core.update(
        "UPDATE v3_shard_reverse_runs SET state='VERIFIED',updated_at=? "
            + "WHERE plan_id=? AND state='COPYING'",
        now(),
        plan.id());
    return status(plan, "VERIFIED", null, total, total);
  }

  private Status activate(ShardCatalog.Plan plan, String reason, Actor actor) {
    lockedPlan(plan);
    var source = source(plan);
    sourceFence(source, plan, "FENCED");
    allDrained();
    drained(source, plan);
    var manifest = manifest(plan.id());
    if (!reason.equals(manifest.reason())
        || !ShardMigrationManifest.snapshot(source, plan.bucket()).equals(manifest.tables())
        || !ShardMigrationManifest.snapshot(core, plan.bucket(), plan.id())
            .equals(manifest.tables())) throw conflict("切流前来源或目标清单已变化");
    var targetFence =
        core.queryForMap(
            "SELECT route_epoch,access_state FROM " + "v3_bucket_fences WHERE bucket=? FOR UPDATE",
            plan.bucket());
    if (epoch(targetFence) != plan.sourceEpoch() + 1
        || !"FENCED".equals(targetFence.get("access_state"))) throw conflict("核心目标桶未保持隔离");
    transferFinance(plan, source);
    var now = now();
    if (core.update(
                "UPDATE v3_shard_routes SET physical_shard=0,epoch=? WHERE bucket=? "
                    + "AND physical_shard=? AND epoch=?",
                plan.sourceEpoch() + 1,
                plan.bucket(),
                plan.sourceShard(),
                plan.sourceEpoch())
            != 1
        || core.update(
                "UPDATE v3_bucket_fences SET access_state='ACTIVE' WHERE bucket=? "
                    + "AND route_epoch=? AND access_state='FENCED'",
                plan.bucket(),
                plan.sourceEpoch() + 1)
            != 1
        || core.update(
                "UPDATE v3_shard_migrations SET state='MOVED',open_bucket=NULL,"
                    + "version=version+1,updated_at=? WHERE id=? AND state='PLANNED' AND version=?",
                now,
                plan.id(),
                plan.version())
            != 1) throw conflict("回迁目录、栅栏或计划提交失败");
    core.update(
        "INSERT INTO v3_shard_cutovers(plan_id,bucket,source_epoch,target_epoch,"
            + "target_shard,plan_version,manifest_hash,state,initiated_by,reason,created_at,"
            + "updated_at,activated_at) VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?)",
        plan.id(),
        plan.bucket(),
        plan.sourceEpoch(),
        plan.sourceEpoch() + 1,
        0,
        plan.version(),
        manifest.hash(),
        actor.id(),
        reason,
        now,
        now,
        now);
    core.update(
        "UPDATE v3_shard_reverse_runs SET state='ACTIVE',updated_at=? "
            + "WHERE plan_id=? AND state='VERIFIED'",
        now,
        plan.id());
    core.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,"
            + "created_at) VALUES(?,NULL,?,'V3_SHARD_REVERSE_ACTIVE',?,?,?)",
        UUID.randomUUID().toString(),
        actor.id(),
        plan.id(),
        reason,
        now);
    return status(plan, "ACTIVE", null, 0, 0);
  }

  private void transferFinance(ShardCatalog.Plan plan, JdbcTemplate source) {
    String prefix = paymentPrefix(plan.bucket());
    if (count(
            core,
            "SELECT COUNT(*) FROM settlement_lines l LEFT JOIN finance_settlements s "
                + "ON s.id=l.settlement_id WHERE LEFT(l.payment_id,3)=? AND "
                + "(s.id IS NULL OR s.status NOT IN ('MOCK_PAID','CARRIED_FORWARD','CANCELLED'))",
            prefix)
        != 0) throw conflict("切流前仍有未完成结算");
    if (count(
            core,
            "SELECT COUNT(*) FROM sharded_adjustment_intents "
                + "WHERE LEFT(payment_id,3)=? AND status='PREPARED'",
            prefix)
        != 0) throw conflict("切流前仍有未完成调账");
    verifyPaidReservations(prefix);
    long cursor = 0;
    while (true) {
      var entries =
          source.queryForList(
              "SELECT id AS entry_id,payment_id,merchant_id,"
                  + "journal_id,account_code FROM ledger_entries WHERE LEFT(payment_id,3)=? "
                  + "AND account_code='MERCHANT_PAYABLE' AND id>? ORDER BY id LIMIT 500",
              prefix,
              cursor);
      if (entries.isEmpty()) break;
      for (var entry : entries) {
        long sourceId = ((Number) entry.get("entry_id")).longValue();
        var mapping =
            core.queryForList(
                "SELECT target_id FROM v3_shard_copy_id_map "
                    + "WHERE plan_id=? AND entity_name='ledger_entries' AND source_id=?",
                Long.class,
                plan.id(),
                sourceId);
        if (mapping.size() != 1) throw conflict("回迁应付分录编号映射缺失");
        long targetId = mapping.getFirst();
        var target =
            core.queryForList(
                "SELECT payment_id,merchant_id,journal_id,account_code "
                    + "FROM ledger_entries WHERE id=? FOR UPDATE",
                targetId);
        if (target.size() != 1) throw conflict("核心目标分录缺失");
        for (String column : List.of("payment_id", "merchant_id", "journal_id", "account_code"))
          if (!Objects.equals(entry.get(column), target.getFirst().get(column)))
            throw conflict("回迁目标分录内容不一致");
        ShardMigrationCutover.recordFinanceHop(core, plan, sourceId, targetId, entry);
        var reservations =
            core.queryForList(
                "SELECT settlement_id,revision FROM "
                    + "settlement_reservations WHERE physical_shard=? AND entry_id=? FOR UPDATE",
                plan.sourceShard(),
                sourceId);
        var settlementCursor =
            core.queryForList(
                "SELECT last_entry_id FROM "
                    + "sharded_settlement_cursors WHERE merchant_id=? AND physical_shard=?",
                Long.class,
                entry.get("merchant_id"),
                plan.sourceShard());
        if (!settlementCursor.isEmpty()
            && sourceId <= settlementCursor.getFirst()
            && reservations.isEmpty()) throw conflict("来源结算游标覆盖了未预占分录");
        for (var reservation : reservations)
          if (core.update(
                  "UPDATE settlement_reservations SET physical_shard=0,entry_id=? "
                      + "WHERE physical_shard=? AND entry_id=? AND settlement_id=? AND revision=?",
                  targetId,
                  plan.sourceShard(),
                  sourceId,
                  reservation.get("settlement_id"),
                  reservation.get("revision"))
              != 1) throw conflict("结算预占移交失败");
        cursor = sourceId;
      }
    }
    if (count(
            core,
            "SELECT COUNT(*) FROM settlement_reservations r JOIN "
                + "v3_shard_finance_handoff_hops h ON h.plan_id=? "
                + "AND h.source_shard=r.physical_shard AND h.source_entry_id=r.entry_id",
            plan.id())
        != 0) throw conflict("仍有来源分录预占未移交");
    verifyPaidReservations(prefix);
  }

  private void verifyPaidReservations(String prefix) {
    if (count(
            core,
            "SELECT COUNT(*) FROM settlement_lines l JOIN finance_settlements s "
                + "ON s.id=l.settlement_id AND l.revision=s.version "
                + "LEFT JOIN v3_shard_finance_handoffs h ON h.source_shard=l.physical_shard "
                + "AND h.source_entry_id=l.entry_id "
                + "WHERE LEFT(l.payment_id,3)=? AND s.status IN ('MOCK_PAID','CARRIED_FORWARD') "
                + "AND NOT EXISTS (SELECT 1 FROM settlement_reservations r "
                + "WHERE r.physical_shard=COALESCE(h.target_shard,l.physical_shard) "
                + "AND r.entry_id=COALESCE(h.target_entry_id,l.entry_id) "
                + "AND r.settlement_id=l.settlement_id AND r.revision=l.revision)",
            prefix)
        != 0) throw conflict("已结算明细缺少当前归属预占");
  }

  static int deletePage(JdbcTemplate jdbc, ShardMigrationPreflight.Probe probe, int bucket) {
    var keys =
        jdbc.query(
            probe.pageSql(false),
            (rs, ignored) -> {
              var values = new ArrayList<Object>();
              for (var column : probe.cursorColumns()) values.add(rs.getObject(column));
              return values;
            },
            probe.pageArgs(bucket, null, null, ShardMigrationBatchCopy.BATCH_SIZE));
    String where =
        String.join(" AND ", probe.cursorColumns().stream().map(column -> column + "=?").toList());
    for (var key : keys)
      if (jdbc.update("DELETE FROM " + probe.name() + " WHERE " + where, key.toArray()) != 1)
        throw conflict("核心旧副本清理主键发生变化");
    return keys.size();
  }

  private void lockedPlan(ShardCatalog.Plan plan) {
    identity(core, 0);
    var route =
        core.queryForMap(
            "SELECT physical_shard,epoch FROM v3_shard_routes " + "WHERE bucket=? FOR UPDATE",
            plan.bucket());
    var stored =
        core.queryForMap(
            "SELECT source_shard,target_shard,source_epoch,version,state "
                + "FROM v3_shard_migrations WHERE id=? FOR UPDATE",
            plan.id());
    if (((Number) route.get("physical_shard")).intValue() != plan.sourceShard()
        || ((Number) route.get("epoch")).longValue() != plan.sourceEpoch()
        || ((Number) stored.get("source_shard")).intValue() != plan.sourceShard()
        || ((Number) stored.get("target_shard")).intValue() != 0
        || ((Number) stored.get("source_epoch")).longValue() != plan.sourceEpoch()
        || ((Number) stored.get("version")).longValue() != plan.version()
        || !"PLANNED".equals(stored.get("state"))) throw conflict("回迁计划或目录已变化");
  }

  private static void verifyRun(ShardCatalog.Plan plan, Map<String, Object> run, String reason) {
    if (run == null
        || ((Number) run.get("bucket")).intValue() != plan.bucket()
        || ((Number) run.get("source_shard")).intValue() != plan.sourceShard()
        || ((Number) run.get("source_epoch")).longValue() != plan.sourceEpoch()
        || ((Number) run.get("target_epoch")).longValue() != plan.sourceEpoch() + 1
        || ((Number) run.get("plan_version")).longValue() != plan.version()
        || !reason.equals(run.get("reason"))) throw conflict("回迁记录与请求不一致");
  }

  private Map<String, Object> readRun(String id) {
    var rows = core.queryForList("SELECT * FROM v3_shard_reverse_runs WHERE plan_id=?", id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private ShardMigrationManifest.Manifest manifest(String id) {
    var rows =
        core.queryForList(
            "SELECT bucket,source_epoch,plan_version,table_count,"
                + "manifest_hash,recorded_by,reason,created_at FROM v3_shard_source_manifests "
                + "WHERE plan_id=?",
            id);
    if (rows.size() != 1) throw conflict("来源清单缺失");
    var row = rows.getFirst();
    var tables =
        core.query(
            "SELECT table_name,source_count,source_hash FROM "
                + "v3_shard_source_manifest_tables WHERE plan_id=? ORDER BY table_name",
            (rs, ignored) ->
                new ShardMigrationManifest.Table(rs.getString(1), rs.getLong(2), rs.getString(3)),
            id);
    String hash = ShardMigrationManifest.manifestHash(tables);
    if (tables.size() != ShardMigrationPreflight.BUCKET_PROBES.size()
        || tables.size() != ((Number) row.get("table_count")).intValue()
        || !hash.equals(row.get("manifest_hash"))) throw conflict("来源清单不完整");
    return new ShardMigrationManifest.Manifest(
        id,
        ((Number) row.get("bucket")).intValue(),
        ((Number) row.get("source_epoch")).longValue(),
        ((Number) row.get("plan_version")).longValue(),
        hash,
        (String) row.get("recorded_by"),
        (String) row.get("reason"),
        ((Timestamp) row.get("created_at")).toInstant(),
        tables);
  }

  private JdbcTemplate source(ShardCatalog.Plan plan) {
    return new JdbcTemplate(gate.source(plan.sourceShard()));
  }

  private static JdbcTemplate jdbc(Connection connection) {
    var result = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    result.setQueryTimeout(21_600);
    return result;
  }

  private static void identity(JdbcTemplate jdbc, int expected) {
    var ids =
        jdbc.queryForList("SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
    if (ids.size() != 1 || ids.getFirst() != expected) throw conflict("来源物理身份不符");
  }

  private static void sourceFence(JdbcTemplate jdbc, ShardCatalog.Plan plan, String state) {
    var fences =
        jdbc.queryForList(
            "SELECT route_epoch,access_state FROM v3_bucket_fences " + "WHERE bucket=?",
            plan.bucket());
    if (fences.size() != 1
        || epoch(fences.getFirst()) != plan.sourceEpoch()
        || !state.equals(fences.getFirst().get("access_state"))) throw conflict("来源桶栅栏不符合回迁阶段");
  }

  private static void drained(JdbcTemplate source, ShardCatalog.Plan plan) {
    source.execute(
        (ConnectionCallback<Void>)
            connection -> {
              EventTransportMaintenance.requireDrained(connection);
              return null;
            });
    String prefix = paymentPrefix(plan.bucket());
    if (count(
                source,
                "SELECT COUNT(*) FROM channel_attempts WHERE LEFT(payment_id,3)=? "
                    + "AND status IN ('PREPARED','UNKNOWN')",
                prefix)
            != 0
        || count(source, "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'") != 0
        || count(
                source,
                "SELECT COUNT(*) FROM payment_admission_slots WHERE LEFT(payment_id,3)=?",
                prefix)
            != 0
        || count(
                source,
                "SELECT COUNT(*) FROM payment_admission WHERE logical_shard=? " + "AND inflight<>0",
                plan.bucket())
            != 0) throw conflict("来源仍有未决任务、渠道尝试或准入占用");
  }

  private void allDrained() {
    for (int physical = 0; physical < 4; physical++) {
      var local = new JdbcTemplate(gate.source(physical));
      identity(local, physical);
      local.execute(
          (ConnectionCallback<Void>)
              connection -> {
                try {
                  EventTransportMaintenance.requireDrained(connection);
                } catch (IllegalStateException pending) {
                  throw conflict("四库业务任务尚未排空");
                }
                return null;
              });
      if (count(local, "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'") != 0)
        throw conflict("四库仍有隔离事件");
    }
  }

  private static String paymentPrefix(int bucket) {
    return "p" + Integer.toHexString(bucket) + "_";
  }

  private static long count(JdbcTemplate jdbc, String sql, Object... args) {
    return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
  }

  private static long epoch(Map<String, Object> row) {
    return ((Number) row.get("route_epoch")).longValue();
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private static Status status(
      ShardCatalog.Plan plan, String state, String table, long copied, long total) {
    return new Status(plan.id(), plan.bucket(), state, table, copied, total);
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARD_REVERSE_CONFLICT", 409, message);
  }
}
