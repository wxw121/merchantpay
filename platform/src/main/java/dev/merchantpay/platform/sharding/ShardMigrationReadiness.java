package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Stopped-writer evidence before a future route switch; no finance rows or route are changed. */
@Service
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled"
    },
    havingValue = "true")
public final class ShardMigrationReadiness {
  public record Report(
      String planId,
      int bucket,
      int sourceShard,
      int targetShard,
      long sourceEpoch,
      String manifestHash,
      String targetHash,
      Map<String, Long> counts,
      List<String> blockers,
      boolean checksPassed,
      boolean executionEnabled) {}

  private final ShardCatalog catalog;
  private final ShardMigrationManifest manifests;
  private final ShardExportReadGate gate;
  private final JdbcTemplate core;
  private final TransactionTemplate tx;

  public ShardMigrationReadiness(
      ShardCatalog catalog,
      ShardMigrationManifest manifests,
      ShardExportReadGate gate,
      JdbcTemplate core,
      TransactionTemplate transactions) {
    this.catalog = catalog;
    this.manifests = manifests;
    this.gate = gate;
    this.core = core;
    tx = new TransactionTemplate(transactions.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(21_600);
  }

  public Report inspect(String id, long expectedVersion, boolean writersStopped, Actor actor) {
    var plan = catalog.detail(id, actor);
    if (!writersStopped
        || expectedVersion < 1
        || plan.version() != expectedVersion
        || !"PLANNED".equals(plan.state())
        || plan.sourceShard() != 0
        || plan.targetShard() == 0) throw conflict("须保持停写，并使用物理0来源的当前计划版本");
    try {
      return Objects.requireNonNull(tx.execute(ignored -> inspectLocked(plan, actor)));
    } catch (DataAccessException unavailable) {
      throw new ApiException("SHARD_READINESS_UNAVAILABLE", 503, "无法完整核对迁移所需的来源、目标及财务状态");
    }
  }

  /** A later cutover must call this again while holding the same core route lock. */
  Report inspectLocked(ShardCatalog.Plan plan, Actor actor) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("A core transaction is required for locked readiness");
    manifests.locked(plan);
    var manifest = manifests.read(plan.id(), actor);
    var blockers = new ArrayList<String>();
    var counts = new LinkedHashMap<String, Long>();
    if (manifest.bucket() != plan.bucket()
        || manifest.sourceEpoch() != plan.sourceEpoch()
        || manifest.planVersion() != plan.version()) throw conflict("来源清单与当前计划不一致");
    var source = ShardMigrationManifest.snapshot(core, plan.bucket());
    if (!source.equals(manifest.tables())) blockers.add("SOURCE_MANIFEST_DRIFT");

    var copies =
        core.queryForList(
            "SELECT bucket,source_epoch,plan_version,state,manifest_hash,target_hash,total_rows FROM v3_shard_copy_runs WHERE plan_id=?",
            plan.id());
    if (copies.size() != 1) {
      blockers.add("VERIFIED_COPY_MISSING");
    } else {
      var copy = copies.getFirst();
      long total = manifest.tables().stream().mapToLong(ShardMigrationManifest.Table::count).sum();
      if (((Number) copy.get("bucket")).intValue() != plan.bucket()
          || ((Number) copy.get("source_epoch")).longValue() != plan.sourceEpoch()
          || ((Number) copy.get("plan_version")).longValue() != plan.version()
          || ((Number) copy.get("total_rows")).longValue() != total
          || !"VERIFIED".equals(copy.get("state"))
          || !manifest.hash().equals(copy.get("manifest_hash"))
          || !manifest.hash().equals(copy.get("target_hash")))
        blockers.add("VERIFIED_COPY_MISMATCH");
    }

    finance(plan, counts, blockers);
    local(core, "source", plan.bucket(), counts, blockers);
    try {
      core.execute(
          (ConnectionCallback<Void>)
              connection -> {
                try {
                  EventTransportMaintenance.requireDrained(connection);
                } catch (IllegalStateException pending) {
                  blockers.add("SOURCE_BUSINESS_NOT_DRAINED");
                }
                return null;
              });
    } catch (DataAccessException unavailable) {
      throw new ApiException("SHARD_READINESS_UNAVAILABLE", 503, "无法核对来源业务任务是否排空");
    }

    String targetHash;
    String targetGeneration;
    try (var connection = gate.source(plan.targetShard()).getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      var target = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      target.setQueryTimeout(21_600);
      var identity =
          target.queryForList(
              "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
      if (identity.size() != 1 || identity.getFirst() != plan.targetShard())
        blockers.add("TARGET_IDENTITY_MISMATCH");
      var fences =
          target.queryForList(
              "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=?",
              plan.bucket());
      if (fences.size() > 1
          || !fences.isEmpty() && !"FENCED".equals(fences.getFirst().get("access_state")))
        blockers.add("TARGET_FENCE_NOT_ISOLATED");
      local(target, "target", plan.bucket(), counts, blockers);
      try {
        EventTransportMaintenance.requireDrained(connection);
      } catch (IllegalStateException pending) {
        blockers.add("TARGET_BUSINESS_NOT_DRAINED");
      }
      var targetTables = ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id());
      targetHash = ShardMigrationManifest.manifestHash(targetTables);
      if (!targetTables.equals(manifest.tables())) blockers.add("TARGET_MANIFEST_DRIFT");
      targetGeneration = projection(target, "TARGET", plan.bucket(), blockers);
      connection.rollback();
    } catch (SQLException unavailable) {
      throw new ApiException("SHARD_READINESS_UNAVAILABLE", 503, "无法在目标库的一致快照中核对切流条件");
    }
    String sourceGeneration = projection(core, "SOURCE", plan.bucket(), blockers);
    if (sourceGeneration == null || !sourceGeneration.equals(targetGeneration))
      blockers.add("PROJECTION_GENERATION_MISMATCH");
    for (int physical = 1; physical < 4; physical++) {
      if (physical == plan.targetShard()) continue;
      try (var connection = gate.source(physical).getConnection()) {
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        var other = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        other.setQueryTimeout(21_600);
        var identity =
            other.queryForList(
                "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
        if (identity.size() != 1 || identity.getFirst() != physical)
          blockers.add("OTHER_SOURCE_IDENTITY_MISMATCH_" + physical);
        if (!other
            .queryForList(
                "SELECT bucket FROM v3_bucket_fences WHERE bucket=? AND access_state='ACTIVE'",
                Integer.class,
                plan.bucket())
            .isEmpty()) blockers.add("OTHER_SOURCE_ACTIVE_FENCE_" + physical);
        local(other, "source" + physical, plan.bucket(), counts, blockers);
        try {
          EventTransportMaintenance.requireDrained(connection);
        } catch (IllegalStateException pending) {
          blockers.add("OTHER_SOURCE_BUSINESS_NOT_DRAINED_" + physical);
        }
        String generation = projection(other, "SOURCE_" + physical, plan.bucket(), blockers);
        if (sourceGeneration == null || !sourceGeneration.equals(generation))
          blockers.add("PROJECTION_GENERATION_MISMATCH_" + physical);
        connection.rollback();
      } catch (SQLException unavailable) {
        throw new ApiException("SHARD_READINESS_UNAVAILABLE", 503, "无法核对其他物理来源的停写与投影状态");
      }
    }
    return new Report(
        plan.id(),
        plan.bucket(),
        plan.sourceShard(),
        plan.targetShard(),
        plan.sourceEpoch(),
        manifest.hash(),
        targetHash,
        Map.copyOf(counts),
        List.copyOf(blockers),
        blockers.isEmpty(),
        false);
  }

  Report requireReadyLocked(ShardCatalog.Plan plan, Actor actor) {
    var report = inspectLocked(plan, actor);
    if (!report.checksPassed()) throw conflict("切流前检查存在阻断项：" + String.join(",", report.blockers()));
    return report;
  }

  private void finance(ShardCatalog.Plan plan, Map<String, Long> counts, List<String> blockers) {
    String prefix = "p" + Integer.toHexString(plan.bucket()) + "_";
    counts.put(
        "financeCases",
        count(core, "SELECT COUNT(*) FROM finance_cases WHERE LEFT(payment_id,3)=?", prefix));
    counts.put(
        "financeAdjustments",
        count(core, "SELECT COUNT(*) FROM finance_adjustments WHERE LEFT(payment_id,3)=?", prefix));
    counts.put(
        "settlementLines",
        count(core, "SELECT COUNT(*) FROM settlement_lines WHERE LEFT(payment_id,3)=?", prefix));
    financeCount(
        counts,
        blockers,
        "pendingSettlementLines",
        "SELECT COUNT(*) FROM settlement_lines l LEFT JOIN finance_settlements s ON s.id=l.settlement_id WHERE LEFT(l.payment_id,3)=? AND (s.id IS NULL OR s.status NOT IN ('MOCK_PAID','CARRIED_FORWARD','CANCELLED'))",
        prefix);
    financeCount(
        counts,
        blockers,
        "unreservedCompletedLines",
        "SELECT COUNT(*) FROM settlement_lines l JOIN finance_settlements s ON s.id=l.settlement_id WHERE LEFT(l.payment_id,3)=? AND l.revision=s.version AND s.status IN ('MOCK_PAID','CARRIED_FORWARD') AND NOT EXISTS(SELECT 1 FROM settlement_reservations r WHERE r.physical_shard=l.physical_shard AND r.entry_id=l.entry_id AND r.settlement_id=l.settlement_id AND r.revision=l.revision)",
        prefix);
    counts.put(
        "sourceEntryReservations",
        count(
            core,
            "SELECT COUNT(*) FROM settlement_reservations r JOIN ledger_entries e ON r.physical_shard=? AND r.entry_id=e.id WHERE LEFT(e.payment_id,3)=?",
            plan.sourceShard(),
            prefix));
    financeCount(
        counts,
        blockers,
        "invalidEntryReservations",
        "SELECT COUNT(*) FROM settlement_reservations r JOIN ledger_entries e ON r.physical_shard=? AND r.entry_id=e.id LEFT JOIN settlement_lines l ON l.settlement_id=r.settlement_id AND l.revision=r.revision AND l.physical_shard=r.physical_shard AND l.entry_id=r.entry_id LEFT JOIN finance_settlements s ON s.id=r.settlement_id WHERE LEFT(e.payment_id,3)=? AND (l.entry_id IS NULL OR l.payment_id<>e.payment_id OR s.merchant_id<>e.merchant_id OR s.status NOT IN ('MOCK_PAID','CARRIED_FORWARD'))",
        plan.sourceShard(),
        prefix);
    counts.put(
        "adjustmentIntents",
        count(
            core,
            "SELECT COUNT(*) FROM sharded_adjustment_intents WHERE LEFT(payment_id,3)=?",
            prefix));
    financeCount(
        counts,
        blockers,
        "preparedAdjustmentIntents",
        "SELECT COUNT(*) FROM sharded_adjustment_intents WHERE LEFT(payment_id,3)=? AND status='PREPARED'",
        prefix);
    counts.put(
        "cursorCoveredEntries",
        count(
            core,
            "SELECT COUNT(*) FROM ledger_entries e JOIN sharded_settlement_cursors c ON c.merchant_id=e.merchant_id AND c.physical_shard=? WHERE LEFT(e.payment_id,3)=? AND e.account_code='MERCHANT_PAYABLE' AND e.id<=c.last_entry_id",
            plan.sourceShard(),
            prefix));
    financeCount(
        counts,
        blockers,
        "cursorUnreservedEntries",
        "SELECT COUNT(*) FROM ledger_entries e JOIN sharded_settlement_cursors c ON c.merchant_id=e.merchant_id AND c.physical_shard=? WHERE LEFT(e.payment_id,3)=? AND e.account_code='MERCHANT_PAYABLE' AND e.id<=c.last_entry_id AND NOT EXISTS(SELECT 1 FROM settlement_reservations r WHERE r.physical_shard=? AND r.entry_id=e.id)",
        plan.sourceShard(),
        prefix,
        plan.sourceShard());
    financeCount(
        counts,
        blockers,
        "orphanReservations",
        "SELECT COUNT(*) FROM settlement_reservations r LEFT JOIN settlement_lines l ON l.settlement_id=r.settlement_id AND l.revision=r.revision AND l.physical_shard=r.physical_shard AND l.entry_id=r.entry_id WHERE l.entry_id IS NULL");
  }

  private void financeCount(
      Map<String, Long> counts, List<String> blockers, String name, String sql, Object... args) {
    long value = count(core, sql, args);
    counts.put(name, value);
    if (value != 0) blockers.add("FINANCE_" + name.toUpperCase(java.util.Locale.ROOT));
  }

  private void local(
      JdbcTemplate jdbc,
      String label,
      int bucket,
      Map<String, Long> counts,
      List<String> blockers) {
    String prefix = "p" + Integer.toHexString(bucket) + "_";
    long slots =
        count(
            jdbc,
            "SELECT COUNT(*) FROM payment_admission_slots WHERE payment_id IS NOT NULL AND (logical_shard=? OR LEFT(payment_id,3)=?)",
            bucket,
            prefix);
    counts.put(label + "AdmissionSlots", slots);
    if (slots != 0) blockers.add(label.toUpperCase(java.util.Locale.ROOT) + "_ADMISSION_SLOTS");
    var admission =
        jdbc.queryForList(
            "SELECT inflight FROM payment_admission WHERE logical_shard=?", Long.class, bucket);
    if (admission.size() != 1)
      blockers.add(label.toUpperCase(java.util.Locale.ROOT) + "_ADMISSION_ROW");
    long inflight = admission.size() == 1 ? admission.getFirst() : -1;
    counts.put(label + "AdmissionInflight", inflight);
    if (inflight != 0)
      blockers.add(label.toUpperCase(java.util.Locale.ROOT) + "_ADMISSION_INFLIGHT");
    long quarantine = count(jdbc, "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'");
    counts.put(label + "EventQuarantine", quarantine);
    if (quarantine != 0)
      blockers.add(label.toUpperCase(java.util.Locale.ROOT) + "_EVENT_QUARANTINE");
  }

  private String projection(JdbcTemplate jdbc, String label, int bucket, List<String> blockers) {
    String prefix = "p" + Integer.toHexString(bucket) + "_";
    String refund = "r" + Integer.toHexString(bucket) + "_";
    String mode = jdbc.queryForObject("SELECT mode FROM event_transport WHERE id=1", String.class);
    if (!"CDC".equals(mode)) blockers.add(label + "_PROJECTION_NOT_CDC");
    if (count(
            jdbc,
            "SELECT COUNT(*) FROM projection_batches WHERE LEFT(payment_id,3)=? AND state IN ('PENDING','LEASED','DEAD')",
            prefix)
        != 0) blockers.add(label + "_PROJECTION_BATCH_PENDING");
    if (count(jdbc, "SELECT COUNT(*) FROM projection_errors WHERE state='QUARANTINED'") != 0)
      blockers.add(label + "_PROJECTION_QUARANTINE");
    if (count(
            jdbc,
            "SELECT COUNT(*) FROM domain_events d WHERE (d.logical_bucket=? OR LEFT(d.aggregateid,3) IN (?,?)) AND NOT EXISTS (SELECT 1 FROM projection_receipts r WHERE r.event_id=d.event_id)",
            bucket,
            prefix,
            refund)
        != 0) blockers.add(label + "_PROJECTION_MISSING_RECEIPTS");
    String active = activeGeneration(jdbc);
    var serving =
        active == null
            ? List.<Boolean>of()
            : jdbc.queryForList(
                "SELECT scan_complete FROM projection_generations WHERE id=? AND state='SERVING'",
                Boolean.class,
                active);
    if (serving.size() != 1 || !Boolean.TRUE.equals(serving.getFirst()))
      blockers.add(label + "_PROJECTION_NOT_SERVING");
    return active;
  }

  private String activeGeneration(JdbcTemplate jdbc) {
    var rows =
        jdbc.queryForList(
            "SELECT active_generation,building_generation FROM projection_control WHERE id=1");
    if (rows.size() != 1 || rows.getFirst().get("building_generation") != null) return null;
    return (String) rows.getFirst().get("active_generation");
  }

  private static long count(JdbcTemplate jdbc, String sql, Object... args) {
    return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARD_READINESS_CONFLICT", 409, message);
  }
}
