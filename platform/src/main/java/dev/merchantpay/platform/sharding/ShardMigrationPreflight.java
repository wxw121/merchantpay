package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only inventory for an offline bucket move. Counts are observations, not a copy manifest. */
@Service
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
public final class ShardMigrationPreflight {
  public record Count(long source, long target) {}

  public record Inspection(
      String planId,
      int bucket,
      int sourceShard,
      int targetShard,
      long sourceEpoch,
      Map<String, Count> bucketRows,
      Map<String, Long> coreReferences,
      Map<String, Boolean> idRemapping,
      Map<Integer, Long> eventWatermarks,
      List<String> blockers,
      boolean executionEnabled) {}

  static record Probe(String name, String sql, String key) {
    List<String> cursorColumns() {
      return switch (name) {
        case "domain_events" -> List.of("sequence_no");
        case "domain_event_versions" -> List.of("merchant_id", "aggregate_id");
        case "projection_counters" -> List.of("generation", "payment_id");
        case "sandbox_checkouts" -> List.of("payment_id");
        case "event_receipts" -> List.of("consumer_name", "event_id");
        case "projection_receipts" -> List.of("event_id");
        case "v3_history_event_locators" -> List.of("event_id");
        default -> List.of("id");
      };
    }

    private List<String> cursorExpressions() {
      return switch (name) {
        case "notification_deliveries" -> List.of("d.id");
        case "outbox_events" -> List.of("e.id");
        case "event_receipts" -> List.of("r.consumer_name", "r.event_id");
        case "projection_receipts" -> List.of("r.event_id");
        default -> cursorColumns();
      };
    }

    String pageSql(boolean after) {
      String rows = rowSql();
      int where = rows.indexOf(" WHERE ");
      int order = rows.lastIndexOf(" ORDER BY ");
      if (where < 0 || order <= where) throw new IllegalStateException("Unsafe copy probe");
      if (!after) return rows + " LIMIT ?";
      var columns = cursorExpressions();
      String cursor =
          columns.size() == 1
              ? columns.getFirst() + ">?"
              : "("
                  + columns.getFirst()
                  + ">? OR ("
                  + columns.getFirst()
                  + "=? AND "
                  + columns.get(1)
                  + ">?))";
      return rows.substring(0, where + 7)
          + "("
          + rows.substring(where + 7, order)
          + ") AND "
          + cursor
          + rows.substring(order)
          + " LIMIT ?";
    }

    Object[] pageArgs(int bucket, String cursorA, String cursorB, int limit) {
      var args = new ArrayList<Object>(Arrays.asList(probeArgs(this, bucket)));
      if (cursorA != null) {
        Object first =
            "domain_events".equals(name) || "ledger_entries".equals(name)
                ? Long.parseLong(cursorA)
                : cursorA;
        args.add(first);
        if (cursorColumns().size() == 2) {
          if (cursorB == null) throw new IllegalStateException("Missing composite cursor");
          args.add(first);
          args.add(cursorB);
        }
      }
      args.add(limit);
      return args.toArray();
    }

    String existenceSql() {
      return sql.replaceFirst("^SELECT COUNT\\(\\*\\)", "SELECT 1") + " LIMIT 1";
    }

    String rowSql() {
      String projection =
          switch (name) {
            case "notification_deliveries" -> "SELECT d.*";
            case "outbox_events" -> "SELECT e.*";
            case "event_receipts", "projection_receipts" -> "SELECT r.*";
            default -> "SELECT *";
          };
      String order =
          switch (name) {
            case "domain_events" -> "sequence_no";
            case "domain_event_versions" -> "merchant_id,aggregate_id";
            case "projection_counters" -> "generation,payment_id";
            case "sandbox_checkouts" -> "payment_id";
            case "event_receipts" -> "r.consumer_name,r.event_id";
            case "projection_receipts" -> "r.event_id";
            case "v3_history_event_locators" -> "event_id";
            case "notification_deliveries" -> "d.id";
            case "outbox_events" -> "e.id";
            default -> "id";
          };
      return sql.replaceFirst("^SELECT COUNT\\(\\*\\)", projection) + " ORDER BY " + order;
    }

    String canonicalRowSql() {
      String key = "ledger_entries".equals(name) ? "id" : "sequence_no";
      String predicate =
          "ledger_entries".equals(name)
              ? "LEFT(e.payment_id,3)=?"
              : "(e.logical_bucket=? OR LEFT(e.aggregateid,3) IN (?,?))";
      return "SELECT e.*,m.source_id AS v3_source_id FROM "
          + name
          + " e LEFT JOIN v3_shard_copy_id_map m ON m.plan_id=? AND m.entity_name='"
          + name
          + "' AND m.target_id=e."
          + key
          + " WHERE "
          + predicate
          + " ORDER BY COALESCE(m.source_id,e."
          + key
          + ")";
    }
  }

  static final List<Probe> BUCKET_PROBES =
      List.of(
          new Probe("payments", "SELECT COUNT(*) FROM payments WHERE LEFT(id,3)=?", "payment"),
          new Probe(
              "refunds", "SELECT COUNT(*) FROM refunds WHERE LEFT(payment_id,3)=?", "payment"),
          new Probe(
              "ledger_journals",
              "SELECT COUNT(*) FROM ledger_journals WHERE LEFT(payment_id,3)=?",
              "payment"),
          new Probe(
              "ledger_entries",
              "SELECT COUNT(*) FROM ledger_entries WHERE LEFT(payment_id,3)=?",
              "payment"),
          new Probe(
              "channel_attempts",
              "SELECT COUNT(*) FROM channel_attempts WHERE LEFT(payment_id,3)=?",
              "payment"),
          new Probe(
              "inbox_notifications",
              "SELECT COUNT(*) FROM inbox_notifications WHERE LEFT(aggregate_id,3) IN (?,?)",
              "both"),
          new Probe(
              "outbox_tasks",
              "SELECT COUNT(*) FROM outbox_tasks WHERE logical_bucket=? OR LEFT(aggregate_id,3) IN (?,?)",
              "routed"),
          new Probe(
              "outbox_task_history",
              "SELECT COUNT(*) FROM outbox_task_history WHERE logical_bucket=? OR LEFT(aggregate_id,3) IN (?,?)",
              "routed"),
          new Probe(
              "domain_events",
              "SELECT COUNT(*) FROM domain_events WHERE logical_bucket=? OR LEFT(aggregateid,3) IN (?,?)",
              "routed"),
          new Probe(
              "domain_event_versions",
              "SELECT COUNT(*) FROM domain_event_versions WHERE LEFT(aggregate_id,3) IN (?,?)",
              "both"),
          new Probe(
              "projection_batches",
              "SELECT COUNT(*) FROM projection_batches WHERE LEFT(payment_id,3)=?",
              "payment"),
          new Probe(
              "projection_counters",
              "SELECT COUNT(*) FROM projection_counters WHERE LEFT(payment_id,3)=?",
              "payment"),
          new Probe(
              "v3_history_archives",
              "SELECT COUNT(*) FROM v3_history_archives WHERE logical_bucket=?",
              "bucket"),
          new Probe(
              "v3_history_event_locators",
              "SELECT COUNT(*) FROM v3_history_event_locators WHERE logical_bucket=?",
              "bucket"),
          new Probe(
              "sandbox_checkouts",
              "SELECT COUNT(*) FROM sandbox_checkouts WHERE LEFT(payment_id,3)=?",
              "payment"),
          new Probe(
              "alipay_evidence",
              "SELECT COUNT(*) FROM alipay_evidence WHERE LEFT(aggregate_id,3) IN (?,?)",
              "both"),
          new Probe(
              "evidence_conflicts",
              "SELECT COUNT(*) FROM evidence_conflicts WHERE LEFT(aggregate_id,3) IN (?,?)",
              "both"),
          new Probe(
              "audit_records",
              "SELECT COUNT(*) FROM audit_records WHERE LEFT(resource_id,3) IN (?,?)",
              "both"),
          new Probe(
              "notification_deliveries",
              "SELECT COUNT(*) FROM notification_deliveries d JOIN (SELECT id,aggregate_id,logical_bucket FROM outbox_tasks UNION ALL SELECT id,aggregate_id,logical_bucket FROM outbox_task_history) t ON t.id=d.task_id WHERE t.logical_bucket=? OR LEFT(t.aggregate_id,3) IN (?,?)",
              "routed"),
          new Probe(
              "outbox_events",
              "SELECT COUNT(*) FROM outbox_events e WHERE EXISTS (SELECT 1 FROM domain_events d WHERE d.id=e.id AND (d.logical_bucket=? OR LEFT(d.aggregateid,3) IN (?,?))) OR EXISTS (SELECT 1 FROM outbox_tasks t WHERE t.id=e.id AND (t.logical_bucket=? OR LEFT(t.aggregate_id,3) IN (?,?))) OR EXISTS (SELECT 1 FROM outbox_task_history h WHERE h.id=e.id AND (h.logical_bucket=? OR LEFT(h.aggregate_id,3) IN (?,?))) OR EXISTS (SELECT 1 FROM v3_history_event_locators l WHERE l.event_id=e.event_id AND l.event_row_id=e.id AND l.state='COLD' AND l.logical_bucket=?)",
              "registry_cold"),
          new Probe(
              "event_receipts",
              "SELECT COUNT(*) FROM event_receipts r WHERE EXISTS (SELECT 1 FROM domain_events e WHERE e.event_id=r.event_id AND (e.logical_bucket=? OR LEFT(e.aggregateid,3) IN (?,?))) OR EXISTS (SELECT 1 FROM v3_history_event_locators l WHERE l.event_id=r.event_id AND l.state='COLD' AND l.logical_bucket=?)",
              "routed_cold"),
          new Probe(
              "projection_receipts",
              "SELECT COUNT(*) FROM projection_receipts r WHERE EXISTS (SELECT 1 FROM domain_events e WHERE e.event_id=r.event_id AND (e.logical_bucket=? OR LEFT(e.aggregateid,3) IN (?,?))) OR EXISTS (SELECT 1 FROM v3_history_event_locators l WHERE l.event_id=r.event_id AND l.state='COLD' AND l.logical_bucket=?)",
              "routed_cold"));

  private final ShardCatalog catalog;
  private final ShardExportReadGate gate;
  private final JdbcTemplate core;

  public ShardMigrationPreflight(
      ShardCatalog catalog, ShardExportReadGate gate, JdbcTemplate core) {
    this.catalog = catalog;
    this.gate = gate;
    this.core = core;
  }

  public Inspection inspect(String id, Actor actor) {
    try {
      return scan(id, actor);
    } catch (DataAccessException unavailable) {
      var failure =
          new ApiException("SHARD_PREFLIGHT_UNAVAILABLE", 503, "无法读取完整的分片搬迁预检数据，请核对各来源的连接与迁移状态");
      failure.initCause(unavailable);
      throw failure;
    }
  }

  private Inspection scan(String id, Actor actor) {
    var plan = catalog.detail(id, actor);
    var snapshot = gate.captureForMigration(plan);
    var route = snapshot.routes().get(plan.bucket());
    var blockers = new ArrayList<String>();
    if (!"PLANNED".equals(plan.state())) blockers.add("PLAN_NOT_ACTIVE");
    if (route.physical() != plan.sourceShard() || route.epoch() != plan.sourceEpoch())
      blockers.add("PLAN_ROUTE_CHANGED");
    if (plan.sourceShard() == plan.targetShard()) blockers.add("SAME_SOURCE_AND_TARGET");
    String hex = Integer.toHexString(plan.bucket());
    String payment = "p" + hex + "_";
    String refund = "r" + hex + "_";
    var source = jdbc(plan.sourceShard());
    var target = jdbc(plan.targetShard());
    var watermarks = new LinkedHashMap<Integer, Long>();
    for (int physical = 0; physical < 4; physical++) {
      var local = jdbc(physical);
      watermarks.put(
          physical, count(local, "SELECT COALESCE(MAX(sequence_no),0) FROM domain_events"));
      try {
        local.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                connection -> {
                  EventTransportMaintenance.requireDrained(connection);
                  return null;
                });
      } catch (IllegalStateException pending) {
        blockers.add("SOURCE_" + physical + "_NOT_DRAINED");
      }
      if (count(local, "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'") != 0)
        blockers.add("SOURCE_" + physical + "_QUARANTINE");
      if (exists(
              local, "SELECT 1 FROM payment_admission_slots WHERE payment_id IS NOT NULL LIMIT 1")
          || exists(local, "SELECT 1 FROM payment_admission WHERE inflight<>0 LIMIT 1"))
        blockers.add("SOURCE_" + physical + "_ADMISSION_NOT_DRAINED");
      if (exists(
              local,
              "SELECT 1 FROM outbox_tasks WHERE kind='PROCESS_INBOX' AND logical_bucket IS NULL LIMIT 1")
          || exists(
              local,
              "SELECT 1 FROM outbox_task_history WHERE kind='PROCESS_INBOX' AND logical_bucket IS NULL LIMIT 1")
          || exists(
              local,
              "SELECT 1 FROM domain_events WHERE event_type='PROCESS_INBOX' AND logical_bucket IS NULL LIMIT 1"))
        blockers.add("SOURCE_" + physical + "_UNCLASSIFIED_INBOX");
      if (exists(
          local,
          "SELECT 1 FROM outbox_events e WHERE NOT EXISTS (SELECT 1 FROM domain_events d WHERE d.id=e.id AND d.event_id=e.event_id) AND NOT EXISTS (SELECT 1 FROM outbox_tasks t WHERE t.id=e.id AND t.event_id=e.event_id) AND NOT EXISTS (SELECT 1 FROM outbox_task_history h WHERE h.id=e.id AND h.event_id=e.event_id) AND NOT EXISTS (SELECT 1 FROM v3_history_event_locators l WHERE l.event_id=e.event_id AND l.event_row_id=e.id AND l.state='COLD') LIMIT 1"))
        blockers.add("SOURCE_" + physical + "_ORPHAN_EVENT_REGISTRY");
      if (exists(
              local,
              "SELECT 1 FROM event_receipts r WHERE NOT EXISTS (SELECT 1 FROM domain_events d WHERE d.event_id=r.event_id) AND NOT EXISTS (SELECT 1 FROM v3_history_event_locators l WHERE l.event_id=r.event_id AND l.state='COLD') LIMIT 1")
          || exists(
              local,
              "SELECT 1 FROM projection_receipts r WHERE NOT EXISTS (SELECT 1 FROM domain_events d WHERE d.event_id=r.event_id) AND NOT EXISTS (SELECT 1 FROM v3_history_event_locators l WHERE l.event_id=r.event_id AND l.state='COLD') LIMIT 1"))
        blockers.add("SOURCE_" + physical + "_ORPHAN_EVENT_RECEIPT");
      if (exists(
          local,
          "SELECT 1 FROM notification_deliveries d WHERE NOT EXISTS (SELECT 1 FROM outbox_tasks t WHERE t.id=d.task_id) AND NOT EXISTS (SELECT 1 FROM outbox_task_history h WHERE h.id=d.task_id) LIMIT 1"))
        blockers.add("SOURCE_" + physical + "_ORPHAN_NOTIFICATION");
    }
    var rows = new LinkedHashMap<String, Count>();
    for (var probe : BUCKET_PROBES) {
      Object[] args = probeArgs(probe, plan.bucket());
      long sourceCount = count(source, probe.sql(), args);
      long targetCount = count(target, probe.sql(), args);
      rows.put(probe.name(), new Count(sourceCount, targetCount));
      if (targetCount != 0) blockers.add("TARGET_HAS_" + probe.name().toUpperCase(Locale.ROOT));
    }
    var idRemapping =
        Map.of(
            "ledgerEntries", autoIdOverlap(source, target, plan.bucket(), "ledger_entries"),
            "domainEvents", autoIdOverlap(source, target, plan.bucket(), "domain_events"));
    if (exists(
        source,
        "SELECT 1 FROM channel_attempts WHERE LEFT(payment_id,3)=? AND status IN ('PREPARED','UNKNOWN') LIMIT 1",
        payment)) blockers.add("CHANNEL_ATTEMPT_UNRESOLVED");
    if (exists(
        source,
        "SELECT 1 FROM payments WHERE LEFT(id,3)=? AND logical_shard<>? LIMIT 1",
        payment,
        plan.bucket())) blockers.add("PAYMENT_BUCKET_MISMATCH");
    if (exists(
        source,
        "SELECT 1 FROM refunds r WHERE LEFT(r.payment_id,3)=? AND (LEFT(r.id,3)<>? OR NOT EXISTS (SELECT 1 FROM payments p WHERE p.id=r.payment_id)) LIMIT 1",
        payment,
        refund)) blockers.add("REFUND_ROOT_MISMATCH");
    if (exists(
        source,
        "SELECT 1 FROM ledger_journals j WHERE LEFT(j.payment_id,3)=? AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.id=j.payment_id) LIMIT 1",
        payment)) blockers.add("JOURNAL_ROOT_MISSING");
    if (exists(
        source,
        "SELECT 1 FROM ledger_entries e WHERE LEFT(e.payment_id,3)=? AND NOT EXISTS (SELECT 1 FROM ledger_journals j WHERE j.id=e.journal_id AND j.payment_id=e.payment_id) LIMIT 1",
        payment)) blockers.add("LEDGER_JOURNAL_MISMATCH");
    var references = new LinkedHashMap<String, Long>();
    references.put(
        "receiptClaims",
        count(
            core,
            "SELECT COUNT(*) FROM v3_receipt_claims WHERE LEFT(aggregate_id,3) IN (?,?)",
            payment,
            refund));
    references.put(
        "financeCases",
        count(core, "SELECT COUNT(*) FROM finance_cases WHERE LEFT(payment_id,3)=?", payment));
    references.put(
        "financeAdjustments",
        count(
            core, "SELECT COUNT(*) FROM finance_adjustments WHERE LEFT(payment_id,3)=?", payment));
    references.put(
        "settlementLines",
        count(core, "SELECT COUNT(*) FROM settlement_lines WHERE LEFT(payment_id,3)=?", payment));
    references.put(
        "settlementReservations",
        count(
            core,
            "SELECT COUNT(*) FROM settlement_reservations r JOIN settlement_lines l ON l.settlement_id=r.settlement_id AND l.revision=r.revision AND l.physical_shard=r.physical_shard AND l.entry_id=r.entry_id WHERE LEFT(l.payment_id,3)=?",
            payment));
    references.put(
        "sourceSettlementCursors",
        count(
            core,
            "SELECT COUNT(*) FROM sharded_settlement_cursors WHERE physical_shard=?",
            plan.sourceShard()));
    references.put(
        "adjustmentIntents",
        count(
            core,
            "SELECT COUNT(*) FROM sharded_adjustment_intents WHERE LEFT(payment_id,3)=?",
            payment));
    references.put(
        "preparedAdjustments",
        count(
            core,
            "SELECT COUNT(*) FROM sharded_adjustment_intents WHERE LEFT(payment_id,3)=? AND status='PREPARED'",
            payment));
    references.put(
        "pendingSettlementLines",
        count(
            core,
            "SELECT COUNT(*) FROM settlement_lines l LEFT JOIN finance_settlements s ON s.id=l.settlement_id WHERE LEFT(l.payment_id,3)=? AND (s.id IS NULL OR s.status NOT IN ('MOCK_PAID','CARRIED_FORWARD','CANCELLED'))",
            payment));
    references.put(
        "unreservedCompletedLines",
        count(
            core,
            "SELECT COUNT(*) FROM settlement_lines l JOIN finance_settlements s ON s.id=l.settlement_id WHERE LEFT(l.payment_id,3)=? AND l.revision=s.version AND s.status IN ('MOCK_PAID','CARRIED_FORWARD') AND NOT EXISTS(SELECT 1 FROM settlement_reservations r WHERE r.physical_shard=l.physical_shard AND r.entry_id=l.entry_id AND r.settlement_id=l.settlement_id AND r.revision=l.revision)",
            payment));
    references.put(
        "cursorUnreservedEntries",
        count(
            core,
            "SELECT COUNT(*) FROM ledger_entries e JOIN sharded_settlement_cursors c ON c.merchant_id=e.merchant_id AND c.physical_shard=? WHERE LEFT(e.payment_id,3)=? AND e.account_code='MERCHANT_PAYABLE' AND e.id<=c.last_entry_id AND NOT EXISTS(SELECT 1 FROM settlement_reservations r WHERE r.physical_shard=? AND r.entry_id=e.id)",
            plan.sourceShard(),
            payment,
            plan.sourceShard()));
    if (references.get("pendingSettlementLines") != 0
        || references.get("unreservedCompletedLines") != 0
        || references.get("cursorUnreservedEntries") != 0) blockers.add("FINANCE_HANDOFF_UNSAFE");
    if (references.get("preparedAdjustments") != 0) blockers.add("ADJUSTMENT_INCOMPLETE");
    gate.validateForMigration(snapshot, plan);
    if (!plan.equals(catalog.detail(id, actor))) blockers.add("PLAN_CHANGED_DURING_SCAN");
    for (int physical = 0; physical < 4; physical++)
      if (watermarks.get(physical)
          != count(jdbc(physical), "SELECT COALESCE(MAX(sequence_no),0) FROM domain_events"))
        blockers.add("SOURCE_" + physical + "_EVENT_WATERMARK_CHANGED");
    return new Inspection(
        plan.id(),
        plan.bucket(),
        plan.sourceShard(),
        plan.targetShard(),
        plan.sourceEpoch(),
        Map.copyOf(rows),
        Map.copyOf(references),
        idRemapping,
        Map.copyOf(watermarks),
        List.copyOf(blockers),
        false);
  }

  private JdbcTemplate jdbc(int physical) {
    var template = new JdbcTemplate(Objects.requireNonNull(gate.source(physical)));
    template.setQueryTimeout(3);
    return template;
  }

  private static long count(JdbcTemplate jdbc, String sql, Object... args) {
    return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
  }

  private static boolean exists(JdbcTemplate jdbc, String sql, Object... args) {
    return !jdbc.queryForList(sql, Integer.class, args).isEmpty();
  }

  /** Exact ID overlap, not merely the presence of rows from another target bucket. */
  static boolean autoIdOverlap(
      JdbcTemplate source, JdbcTemplate target, int bucket, String entity) {
    String payment = "p" + Integer.toHexString(bucket) + "_";
    String refund = "r" + Integer.toHexString(bucket) + "_";
    return switch (entity) {
      case "ledger_entries" ->
          autoIdOverlap(source, target, entity, "id", "LEFT(payment_id,3)=?", payment);
      case "domain_events" ->
          autoIdOverlap(
              source,
              target,
              entity,
              "sequence_no",
              "logical_bucket=? OR LEFT(aggregateid,3) IN (?,?)",
              bucket,
              payment,
              refund);
      default -> throw new IllegalArgumentException("Unknown copy ID entity");
    };
  }

  private static boolean autoIdOverlap(
      JdbcTemplate source,
      JdbcTemplate target,
      String table,
      String column,
      String predicate,
      Object... args) {
    var ids =
        source.query(
            "SELECT " + column + " FROM " + table + " WHERE " + predicate + " ORDER BY " + column,
            (rs, ignored) -> rs.getLong(1),
            args);
    for (int start = 0; start < ids.size(); start += 500) {
      var batch = ids.subList(start, Math.min(start + 500, ids.size()));
      String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
      if (exists(
          target,
          "SELECT 1 FROM " + table + " WHERE " + column + " IN (" + placeholders + ") LIMIT 1",
          batch.toArray())) return true;
    }
    return false;
  }

  /** Only the still-empty target may be used for a no-copy isolation release. */
  boolean targetEmpty(ShardCatalog.Plan plan) {
    var target = jdbc(plan.targetShard());
    var identity =
        target.queryForList(
            "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
    if (identity.size() != 1
        || identity.getFirst() != plan.targetShard()
        || exists(
            target,
            "SELECT 1 FROM v3_bucket_fences WHERE bucket=? AND access_state='ACTIVE' LIMIT 1",
            plan.bucket()))
      throw new ApiException("SHARD_ISOLATION_TARGET_CHANGED", 409, "目标分片身份或桶栅栏已变化，不能直接解除隔离");
    String payment = "p" + Integer.toHexString(plan.bucket()) + "_";
    if (exists(
            target,
            "SELECT 1 FROM payment_admission_slots WHERE LEFT(payment_id,3)=? LIMIT 1",
            payment)
        || exists(
            target,
            "SELECT 1 FROM payment_admission WHERE logical_shard=? AND inflight<>0 LIMIT 1",
            plan.bucket())
        || exists(target, "SELECT 1 FROM event_quarantine WHERE status<>'REPLAYED' LIMIT 1"))
      return false;
    for (var probe : BUCKET_PROBES)
      if (exists(target, probe.existenceSql(), probeArgs(probe, plan.bucket()))) return false;
    return true;
  }

  static Object[] probeArgs(Probe probe, int bucket) {
    String hex = Integer.toHexString(bucket);
    String payment = "p" + hex + "_";
    String refund = "r" + hex + "_";
    return switch (probe.key()) {
      case "bucket" -> new Object[] {bucket};
      case "payment" -> new Object[] {payment};
      case "both" -> new Object[] {payment, refund};
      case "routed" -> new Object[] {bucket, payment, refund};
      case "routed_cold" -> new Object[] {bucket, payment, refund, bucket};
      case "registry_cold" ->
          new Object[] {
            bucket, payment, refund, bucket, payment, refund, bucket, payment, refund, bucket
          };
      case "registry" ->
          new Object[] {bucket, payment, refund, bucket, payment, refund, bucket, payment, refund};
      default -> throw new IllegalStateException("Unknown bucket probe");
    };
  }
}
