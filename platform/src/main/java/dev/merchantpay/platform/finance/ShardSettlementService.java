package dev.merchantpay.platform.finance;

import static dev.merchantpay.platform.finance.FinanceSupport.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Core-owned settlement reservations over four immutable, stopped-writer ledger sources. */
@Service
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
public final class ShardSettlementService {
  private record Source(
      int physical,
      long entry,
      String payment,
      String journal,
      long signed,
      String priorSettlement,
      Long priorRevision) {}

  private record Snapshot(long net, String hash) {}

  private final FinanceSupport f;
  private final ShardExportReadGate gate;
  private final LedgerService ledger;
  private final OutboxService events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ShardSettlementService(
      FinanceSupport f,
      ShardExportReadGate gate,
      LedgerService ledger,
      OutboxService events,
      TransactionTemplate template,
      Clock clock) {
    this.f = f;
    this.gate = gate;
    this.ledger = ledger;
    this.events = events;
    this.clock = clock;
    tx = new TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(180);
  }

  public List<Map<String, Object>> list(String merchant, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    String scope = actor.scope(merchant, false);
    return scope == null
        ? f.jdbc.query(
            "SELECT * FROM finance_settlements WHERE source_routes_hash IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 100",
            Queries.ROW)
        : f.jdbc.query(
            "SELECT * FROM finance_settlements WHERE source_routes_hash IS NOT NULL AND merchant_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
            Queries.ROW,
            scope);
  }

  public Map<String, Object> detail(String id, Actor actor) {
    var result = new LinkedHashMap<>(row(id, actor, false));
    result.put(
        "lines",
        f.jdbc.query(
            "SELECT * FROM settlement_lines WHERE settlement_id=? AND revision=? ORDER BY physical_shard,entry_id",
            Queries.ROW,
            id,
            result.get("version")));
    result.put(
        "reviews",
        f.jdbc.query(
            "SELECT * FROM finance_reviews WHERE object_type='SETTLEMENT' AND object_id=? ORDER BY object_version",
            Queries.ROW,
            id));
    return result;
  }

  public Map<String, Object> create(
      String merchant, Instant until, String why, boolean writersStopped, Actor actor) {
    actor.require("FINANCE");
    String scoped = actor.scope(merchant, true);
    reason(why);
    Timestamp cutoff = cutoff(until);
    stopped(writersStopped);
    var route = gate.capture();
    requireMoved(route);
    return tx.execute(
        t -> {
          f.merchantLock(scoped);
          gate.validateForPublish(route);
          String watermarks = drainedWatermarks(route);
          noPending(scoped, null);
          String id = "stl_" + UUID.randomUUID();
          var snapshot = reserve(id, 1, scoped, cutoff, route);
          requireWatermarks(route, watermarks);
          f.jdbc.update(
              "INSERT INTO finance_settlements(id,merchant_id,version,status,cutoff_at,source_hash,source_routes_hash,source_event_watermarks,net_minor,reason,proposer,last_editor,created_at,updated_at) VALUES (?,?,1,'DRAFT',?,?,?,?,?,?,?,?,?,?)",
              id,
              scoped,
              cutoff,
              snapshot.hash(),
              Signatures.sha256(route.routes().toString()),
              watermarks,
              snapshot.net(),
              why,
              actor.id(),
              actor.id(),
              f.now(),
              f.now());
          f.audit(scoped, actor, "SHARD_SETTLEMENT_CREATE", id, why);
          return row(id, actor, false);
        });
  }

  public Map<String, Object> rebuild(
      String id, long expected, Instant until, String why, boolean writersStopped, Actor actor) {
    actor.require("FINANCE");
    reason(why);
    Timestamp cutoff = cutoff(until);
    stopped(writersStopped);
    var route = gate.capture();
    requireMoved(route);
    return tx.execute(
        t -> {
          var current = locked(id, actor);
          version(current, expected);
          editable(current);
          gate.validateForPublish(route);
          String watermarks = drainedWatermarks(route);
          release(id, expected);
          var snapshot = reserve(id, expected + 1, text(current, "merchantId"), cutoff, route);
          requireWatermarks(route, watermarks);
          f.jdbc.update(
              "UPDATE finance_settlements SET version=version+1,status='DRAFT',cutoff_at=?,source_hash=?,source_routes_hash=?,source_event_watermarks=?,net_minor=?,reason=?,last_editor=?,approved_digest=NULL,approved_version=NULL,reviewer=NULL,updated_at=? WHERE id=?",
              cutoff,
              snapshot.hash(),
              Signatures.sha256(route.routes().toString()),
              watermarks,
              snapshot.net(),
              why,
              actor.id(),
              f.now(),
              id);
          f.audit(text(current, "merchantId"), actor, "SHARD_SETTLEMENT_REBUILD", id, why);
          return row(id, actor, false);
        });
  }

  public Map<String, Object> review(
      String id, long expected, boolean approve, String why, Actor actor) {
    actor.require("FINANCE");
    return tx.execute(
        t -> {
          var current = row(id, actor, true);
          f.review(
              "SETTLEMENT",
              current,
              expected,
              text(current, "sourceHash"),
              approve,
              why,
              actor,
              "finance_settlements");
          return row(id, actor, false);
        });
  }

  public Map<String, Object> cancel(String id, long expected, String why, Actor actor) {
    actor.require("FINANCE");
    reason(why);
    return tx.execute(
        t -> {
          var current = locked(id, actor);
          version(current, expected);
          if ("CANCELLED".equals(current.get("status"))) return current;
          editable(current);
          release(id, expected);
          f.jdbc.update(
              "UPDATE finance_settlements SET status='CANCELLED',updated_at=? WHERE id=?",
              f.now(),
              id);
          f.audit(text(current, "merchantId"), actor, "SHARD_SETTLEMENT_CANCEL", id, why);
          return row(id, actor, false);
        });
  }

  public Map<String, Object> execute(String id, long expected, String why, Actor actor) {
    actor.require("FINANCE");
    reason(why);
    var route = gate.capture();
    requireMoved(route);
    return tx.execute(
        t -> {
          var current = locked(id, actor);
          version(current, expected);
          if (Set.of("MOCK_PAID", "CARRIED_FORWARD").contains(text(current, "status")))
            return current;
          approved(current, expected, text(current, "sourceHash"));
          if (!Signatures.sha256(route.routes().toString()).equals(current.get("sourceRoutesHash")))
            throw conflict("来源路由已变化，先完成搬迁核对并重建结算");
          gate.validateForPublish(route);
          String merchant = text(current, "merchantId");
          assertUnfrozen(merchant);
          var lines = lines(id, expected);
          if (lines.isEmpty()) throw conflict("结算来源为空");
          long total = 0;
          for (var line : lines) {
            int physical = ((Number) line.get("physical_shard")).intValue();
            long entry = ((Number) line.get("entry_id")).longValue();
            if (f.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM settlement_reservations WHERE physical_shard=? AND entry_id=? AND settlement_id=? AND revision=?",
                    Long.class,
                    physical,
                    entry,
                    id,
                    expected)
                != 1) throw conflict("结算来源预占已变化");
            verifyLine(
                route, line, merchant, Timestamp.from(Instant.parse(text(current, "cutoffAt"))));
            total = Math.addExact(total, ((Number) line.get("signed_minor")).longValue());
          }
          if (total != number(current, "netMinor")) throw conflict("结算来源金额不一致");
          long net = number(current, "netMinor");
          String journal =
              net > 0 ? ledger.postSettlement(id, merchant, net, clock.instant()) : null;
          for (var line : lines)
            if (line.get("prior_settlement_id") == null)
              f.jdbc.update(
                  "INSERT INTO sharded_settlement_cursors(merchant_id,physical_shard,last_entry_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE last_entry_id=GREATEST(last_entry_id,VALUES(last_entry_id))",
                  merchant,
                  line.get("physical_shard"),
                  line.get("entry_id"));
          String status = net > 0 ? "MOCK_PAID" : "CARRIED_FORWARD";
          f.jdbc.update(
              "UPDATE finance_settlements SET status=?,journal_id=?,updated_at=? WHERE id=?",
              status,
              journal,
              f.now(),
              id);
          f.audit(merchant, actor, "SHARD_SETTLEMENT_EXECUTE", id, status + ": " + why);
          events.enqueue("settlement-executed:" + id, "SETTLEMENT_EXECUTED", id, merchant);
          return row(id, actor, false);
        });
  }

  private Snapshot reserve(
      String id,
      long revision,
      String merchant,
      Timestamp cutoff,
      ShardExportReadGate.Snapshot route) {
    assertUnfrozen(merchant);
    var candidates = sources(merchant, cutoff, route);
    if (candidates.isEmpty()) throw conflict("截止时间内没有可结算来源");
    long net = 0;
    var witness =
        new StringBuilder(merchant)
            .append('|')
            .append(cutoff.toInstant())
            .append('|')
            .append(revision);
    for (var source : candidates) {
      net = Math.addExact(net, source.signed());
      witness
          .append('|')
          .append(source.physical())
          .append(':')
          .append(source.entry())
          .append(':')
          .append(source.signed())
          .append(':')
          .append(source.payment())
          .append(':')
          .append(source.journal());
      f.jdbc.update(
          "INSERT INTO settlement_lines(settlement_id,revision,physical_shard,entry_id,payment_id,journal_id,signed_minor,prior_settlement_id,prior_revision) VALUES(?,?,?,?,?,?,?,?,?)",
          id,
          revision,
          source.physical(),
          source.entry(),
          source.payment(),
          source.journal(),
          source.signed(),
          source.priorSettlement(),
          source.priorRevision());
      if (source.priorSettlement() == null)
        f.jdbc.update(
            "INSERT INTO settlement_reservations(physical_shard,entry_id,settlement_id,revision) VALUES(?,?,?,?)",
            source.physical(),
            source.entry(),
            id,
            revision);
      else if (f.jdbc.update(
              "UPDATE settlement_reservations SET settlement_id=?,revision=? WHERE physical_shard=? AND entry_id=? AND settlement_id=? AND revision=?",
              id,
              revision,
              source.physical(),
              source.entry(),
              source.priorSettlement(),
              source.priorRevision())
          != 1) throw conflict("结转来源归属已变化");
    }
    return new Snapshot(net, Signatures.sha256(witness.toString()));
  }

  private List<Source> sources(
      String merchant, Timestamp cutoff, ShardExportReadGate.Snapshot route) {
    var result = new ArrayList<Source>();
    var carry =
        f.jdbc.queryForList(
            "SELECT COALESCE(h.target_shard,l.physical_shard) AS physical_shard,COALESCE(h.target_entry_id,l.entry_id) AS entry_id,l.payment_id,l.journal_id,l.signed_minor,r.settlement_id,r.revision FROM settlement_lines l JOIN finance_settlements s ON s.id=l.settlement_id LEFT JOIN v3_shard_finance_handoffs h ON h.source_shard=l.physical_shard AND h.source_entry_id=l.entry_id AND h.payment_id=l.payment_id JOIN settlement_reservations r ON r.physical_shard=COALESCE(h.target_shard,l.physical_shard) AND r.entry_id=COALESCE(h.target_entry_id,l.entry_id) AND r.settlement_id=l.settlement_id AND r.revision=l.revision WHERE s.merchant_id=? AND s.status='CARRIED_FORWARD' ORDER BY physical_shard,entry_id",
            merchant);
    for (var row : carry) {
      verifyLine(route, row, merchant, cutoff);
      result.add(source(row, true));
    }
    if (result.size() > 1000) throw conflict("负数结转来源超过1000条");
    for (int physical = 0; physical < 4; physical++) {
      var local = new JdbcTemplate(gate.source(physical));
      long cursor =
          f
              .jdbc
              .queryForList(
                  "SELECT last_entry_id FROM sharded_settlement_cursors WHERE merchant_id=? AND physical_shard=?",
                  Long.class,
                  merchant,
                  physical)
              .stream()
              .findFirst()
              .orElse(0L);
      String retired = physical == 0 ? retiredSourcePredicate(route) : "";
      String base =
          " FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE e.merchant_id=? AND j.merchant_id=? AND e.account_code='MERCHANT_PAYABLE' AND j.source_type IN ('PAYMENT','REFUND','ADJUSTMENT') AND e.id>?"
              + retired;
      long last = cursor;
      while (true) {
        var rows =
            local.queryForList(
                "SELECT e.id,e.payment_id,e.journal_id,e.side,e.amount_minor,e.currency,j.payment_id AS journal_payment,j.currency AS journal_currency,j.source_type,j.source_id,j.created_at AS journal_created"
                    + base
                    + " ORDER BY e.id LIMIT 500",
                merchant,
                merchant,
                last);
        var unavailable = unavailableEntries(physical, rows);
        for (var row : rows) {
          long entry = ((Number) row.get("id")).longValue();
          last = entry;
          if (unavailable.contains(entry)) continue;
          addCandidate(result, local, row, merchant, cutoff, route, physical);
        }
        if (rows.size() < 500) break;
      }
      var moved =
          f.jdbc.queryForList(
              "SELECT h.target_entry_id,CASE WHEN EXISTS(SELECT 1 FROM settlement_lines l JOIN finance_settlements s ON s.id=l.settlement_id WHERE l.physical_shard=h.source_shard AND l.entry_id=h.source_entry_id AND l.payment_id=h.payment_id AND s.merchant_id=h.merchant_id AND s.status IN ('MOCK_PAID','CARRIED_FORWARD')) THEN 1 ELSE 0 END AS completed_line FROM v3_shard_finance_handoffs h WHERE h.target_shard=? AND h.merchant_id=? AND NOT EXISTS(SELECT 1 FROM settlement_reservations r WHERE r.physical_shard=h.target_shard AND r.entry_id=h.target_entry_id) ORDER BY h.target_entry_id LIMIT 1001",
              physical,
              merchant);
      if (result.size() + moved.size() > 1000) throw conflict("可结算来源超过1000条，请分批处理，但不可拆分负数结转");
      for (var alias : moved) {
        if (((Number) alias.get("completed_line")).intValue() != 0)
          throw conflict("迁入分录已有完成结算但预占缺失，禁止重复结算");
        var rows =
            local.queryForList(
                "SELECT e.id,e.payment_id,e.journal_id,e.side,e.amount_minor,e.currency,j.payment_id AS journal_payment,j.currency AS journal_currency,j.source_type,j.source_id,j.created_at AS journal_created FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE e.id=? AND e.merchant_id=? AND j.merchant_id=? AND e.account_code='MERCHANT_PAYABLE'",
                alias.get("target_entry_id"),
                merchant,
                merchant);
        if (rows.size() != 1) throw conflict("迁移的结算来源分录缺失");
        addCandidate(result, local, rows.getFirst(), merchant, cutoff, route, physical);
      }
    }
    return result;
  }

  private Set<Long> unavailableEntries(int physical, List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return Set.of();
    var ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    var args = new ArrayList<Object>(ids.size() + 1);
    args.add(physical);
    args.addAll(ids);
    var unavailable =
        new HashSet<Long>(
            f.jdbc.queryForList(
                "SELECT target_entry_id FROM v3_shard_finance_handoffs WHERE target_shard=? AND target_entry_id IN ("
                    + placeholders
                    + ")",
                Long.class,
                args.toArray()));
    unavailable.addAll(
        f.jdbc.queryForList(
            "SELECT entry_id FROM settlement_reservations WHERE physical_shard=? AND entry_id IN ("
                + placeholders
                + ")",
            Long.class,
            args.toArray()));
    return unavailable;
  }

  private void addCandidate(
      List<Source> result,
      JdbcTemplate local,
      Map<String, Object> row,
      String merchant,
      Timestamp cutoff,
      ShardExportReadGate.Snapshot route,
      int physical) {
    if (((java.util.Date) row.get("journal_created")).toInstant().isAfter(cutoff.toInstant()))
      throw conflict("截止时间早于尚未预占的来源凭证；停写后请选择更新的截止时间");
    String payment = (String) row.get("payment_id");
    if (payment == null
        || !payment.equals(row.get("journal_payment"))
        || !"CNY".equals(row.get("currency"))
        || !"CNY".equals(row.get("journal_currency"))) throw conflict("来源凭证属性不一致");
    owned(route, physical, payment);
    sourceFact(local, row, merchant, payment);
    if (result.size() == 1000) throw conflict("可结算来源超过1000条，请分批处理，但不可拆分负数结转");
    result.add(
        new Source(
            physical,
            ((Number) row.get("id")).longValue(),
            payment,
            (String) row.get("journal_id"),
            signed(row),
            null,
            null));
  }

  private static Source source(Map<String, Object> row, boolean carried) {
    return new Source(
        ((Number) row.get("physical_shard")).intValue(),
        ((Number) row.get("entry_id")).longValue(),
        (String) row.get("payment_id"),
        (String) row.get("journal_id"),
        ((Number) row.get("signed_minor")).longValue(),
        carried ? (String) row.get("settlement_id") : null,
        carried ? ((Number) row.get("revision")).longValue() : null);
  }

  static String retiredSourcePredicate(ShardExportReadGate.Snapshot route) {
    if (route.retiredSources().isEmpty()) return "";
    var prefixes =
        route.retiredSources().entrySet().stream()
            .filter(entry -> entry.getValue().containsKey(0))
            .map(Map.Entry::getKey)
            .sorted()
            .map(bucket -> "'p" + Integer.toHexString(bucket) + "_'")
            .toList();
    if (prefixes.isEmpty()) return "";
    return " AND LEFT(e.payment_id,3) NOT IN (" + String.join(",", prefixes) + ")";
  }

  private void verifyLine(
      ShardExportReadGate.Snapshot route,
      Map<String, Object> line,
      String merchant,
      Timestamp cutoff) {
    int physical = ((Number) line.get("physical_shard")).intValue();
    if (physical < 0 || physical > 3) throw conflict("来源物理库无效");
    var rows =
        new JdbcTemplate(gate.source(physical))
            .queryForList(
                "SELECT e.id,e.payment_id,e.journal_id,e.side,e.amount_minor,e.currency,j.payment_id AS journal_payment,j.currency AS journal_currency,j.source_type,j.source_id FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE e.id=? AND e.merchant_id=? AND j.merchant_id=? AND e.account_code='MERCHANT_PAYABLE' AND j.source_type IN ('PAYMENT','REFUND','ADJUSTMENT') AND j.created_at<=?",
                line.get("entry_id"),
                merchant,
                merchant,
                cutoff);
    if (rows.size() != 1) throw conflict("权威来源凭证已变化");
    var value = rows.getFirst();
    String payment = (String) value.get("payment_id");
    if (payment == null
        || !payment.equals(value.get("journal_payment"))
        || !"CNY".equals(value.get("currency"))
        || !"CNY".equals(value.get("journal_currency"))
        || !payment.equals(line.get("payment_id"))
        || !Objects.equals(value.get("journal_id"), line.get("journal_id"))
        || signed(value) != ((Number) line.get("signed_minor")).longValue())
      throw conflict("权威来源凭证已变化");
    owned(route, physical, payment);
    sourceFact(new JdbcTemplate(gate.source(physical)), value, merchant, payment);
  }

  private void sourceFact(
      JdbcTemplate local, Map<String, Object> row, String merchant, String payment) {
    String kind = (String) row.get("source_type");
    String sourceId = (String) row.get("source_id");
    long found;
    if ("PAYMENT".equals(kind) && payment.equals(sourceId))
      found =
          local.queryForObject(
              "SELECT COUNT(*) FROM payments WHERE id=? AND merchant_id=? AND status='SUCCEEDED'",
              Long.class,
              payment,
              merchant);
    else if ("REFUND".equals(kind))
      found =
          local.queryForObject(
              "SELECT COUNT(*) FROM refunds r JOIN payments p ON p.id=r.payment_id WHERE r.id=? AND r.payment_id=? AND r.merchant_id=? AND r.status='SUCCEEDED' AND p.merchant_id=r.merchant_id AND p.status='SUCCEEDED'",
              Long.class,
              sourceId,
              payment,
              merchant);
    else if ("ADJUSTMENT".equals(kind))
      found =
          f.jdbc.queryForObject(
              "SELECT COUNT(*) FROM finance_adjustments WHERE id=? AND payment_id=? AND merchant_id=? AND status='EXECUTED' AND journal_id=?",
              Long.class,
              sourceId,
              payment,
              merchant,
              row.get("journal_id"));
    else throw conflict("来源凭证类型或业务根无效");
    if (found != 1) throw conflict("来源凭证尚无已完成的权威业务事实");
  }

  private static long signed(Map<String, Object> row) {
    long amount = ((Number) row.get("amount_minor")).longValue();
    if (amount <= 0 || !Set.of("CREDIT", "DEBIT").contains(row.get("side")))
      throw conflict("来源凭证金额或方向无效");
    return "CREDIT".equals(row.get("side")) ? amount : -amount;
  }

  private static void owned(ShardExportReadGate.Snapshot route, int physical, String payment) {
    int bucket;
    try {
      bucket = PaymentBuckets.forPaymentId(payment);
    } catch (ApiException invalid) {
      throw conflict("来源支付根无效");
    }
    if (route.routes().get(bucket).physical() != physical) throw conflict("来源凭证与目录桶归属不符");
  }

  private void release(String id, long revision) {
    for (var line : lines(id, revision)) {
      int changed =
          line.get("prior_settlement_id") == null
              ? f.jdbc.update(
                  "DELETE FROM settlement_reservations WHERE physical_shard=? AND entry_id=? AND settlement_id=? AND revision=?",
                  line.get("physical_shard"),
                  line.get("entry_id"),
                  id,
                  revision)
              : f.jdbc.update(
                  "UPDATE settlement_reservations SET settlement_id=?,revision=? WHERE physical_shard=? AND entry_id=? AND settlement_id=? AND revision=?",
                  line.get("prior_settlement_id"),
                  line.get("prior_revision"),
                  line.get("physical_shard"),
                  line.get("entry_id"),
                  id,
                  revision);
      if (changed != 1) throw conflict("结算来源归属已变化，不能释放");
    }
  }

  private List<Map<String, Object>> lines(String id, long revision) {
    return f.jdbc.queryForList(
        "SELECT * FROM settlement_lines WHERE settlement_id=? AND revision=? ORDER BY physical_shard,entry_id",
        id,
        revision);
  }

  private void noPending(String merchant, String except) {
    if (!f.jdbc
        .queryForList(
            "SELECT id FROM finance_settlements WHERE merchant_id=? AND status IN ('DRAFT','APPROVED','REJECTED') AND id<>? LIMIT 1",
            merchant,
            except == null ? "" : except)
        .isEmpty()) throw conflict("该商户有未完成的结算，请先处理或取消");
  }

  private void assertUnfrozen(String merchant) {
    if (f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM merchant_finance_holds WHERE merchant_id=? AND active=true",
            Long.class,
            merchant)
        > 0) throw conflict("商户账务检查未通过，结算冻结");
    if (f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM recon_issues i JOIN recon_batches b ON b.id=i.batch_id LEFT JOIN finance_cases c ON c.issue_id=i.id WHERE i.merchant_id=? AND b.status='COMPLETE' AND (c.id IS NULL OR c.status<>'RESOLVED')",
            Long.class,
            merchant)
        > 0) throw conflict("商户存在未解决的正式对账差异，结算冻结");
  }

  private String drainedWatermarks(ShardExportReadGate.Snapshot route) {
    gate.validate(route);
    for (int physical = 0; physical < 4; physical++) {
      try (var c = gate.source(physical).getConnection()) {
        EventTransportMaintenance.requireDrained(c);
        try (var s =
                c.prepareStatement(
                    "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'");
            var rows = s.executeQuery()) {
          rows.next();
          if (rows.getLong(1) != 0) throw conflict("来源隔离事件尚未处理");
        }
      } catch (java.sql.SQLException failure) {
        throw new IllegalStateException("Settlement source unavailable: " + physical, failure);
      } catch (IllegalStateException pending) {
        throw conflict("来源任务或事件尚未排空");
      }
    }
    return watermarks(route);
  }

  private String watermarks(ShardExportReadGate.Snapshot route) {
    gate.validate(route);
    var values = new StringJoiner(",");
    for (int physical = 0; physical < 4; physical++)
      values.add(
          Long.toString(
              new JdbcTemplate(gate.source(physical))
                  .queryForObject(
                      "SELECT COALESCE(MAX(sequence_no),0) FROM domain_events", Long.class)));
    gate.validate(route);
    return values.toString();
  }

  private void requireWatermarks(ShardExportReadGate.Snapshot route, String expected) {
    if (!expected.equals(watermarks(route))) throw conflict("来源事件水位变化，须重新停写并重建结算");
  }

  private Map<String, Object> row(String id, Actor actor, boolean lock) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    var rows =
        f.jdbc.query(
            "SELECT * FROM finance_settlements WHERE id=? AND source_routes_hash IS NOT NULL"
                + (lock ? " FOR UPDATE" : ""),
            Queries.ROW,
            id);
    if (rows.isEmpty()) throw new ApiException("NOT_FOUND", 404, "四库结算不存在");
    actor.checkMerchant(text(rows.getFirst(), "merchantId"));
    return rows.getFirst();
  }

  private Map<String, Object> locked(String id, Actor actor) {
    var view = row(id, actor, false);
    f.merchantLock(text(view, "merchantId"));
    return row(id, actor, true);
  }

  private Timestamp cutoff(Instant instant) {
    if (instant == null || instant.isAfter(clock.instant()))
      throw new ApiException("INVALID_CUTOFF", 400, "结算截止时间必填且不能在未来");
    return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
  }

  private static void stopped(boolean value) {
    if (!value)
      throw new ApiException("SHARD_FINANCE_STOP_REQUIRED", 409, "须实际停止全部写入并排空任务后才能预占四库来源");
  }

  private static void requireMoved(ShardExportReadGate.Snapshot route) {
    if (route.routes().stream().allMatch(item -> item.physical() == 0))
      throw conflict("全部桶仍在核心库，请使用原单库财务入口");
  }

  private static void editable(Map<String, Object> row) {
    if (!Set.of("DRAFT", "APPROVED", "REJECTED").contains(text(row, "status")))
      throw conflict("已付款、已结转或已取消的结算不可修改");
  }
}
