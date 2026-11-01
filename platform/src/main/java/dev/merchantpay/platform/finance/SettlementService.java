package dev.merchantpay.platform.finance;

import static dev.merchantpay.platform.finance.FinanceSupport.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.query.Queries;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SettlementService {
  private final FinanceSupport f;
  private final LedgerService ledger;
  private final OutboxService events;
  private BalanceService balances;

  @org.springframework.beans.factory.annotation.Autowired
  public void setBalances(BalanceService balances) {
    this.balances = balances;
  }

  public SettlementService(FinanceSupport f, LedgerService ledger, OutboxService events) {
    this.f = f;
    this.ledger = ledger;
    this.events = events;
  }

  public List<Map<String, Object>> list(String merchant, Actor actor) {
    return f.list("finance_settlements", merchant, actor);
  }

  public Map<String, Object> detail(String id, Actor actor) {
    var row = new LinkedHashMap<>(f.row("finance_settlements", id, actor, false));
    row.put(
        "lines",
        f.jdbc.query(
            "SELECT * FROM settlement_lines WHERE settlement_id=? AND revision=? ORDER BY entry_id",
            Queries.ROW,
            id,
            row.get("version")));
    row.put(
        "reviews",
        f.jdbc.query(
            "SELECT * FROM finance_reviews WHERE object_type='SETTLEMENT' AND object_id=? ORDER BY object_version",
            Queries.ROW,
            id));
    return row;
  }

  private Timestamp cutoff(Instant input) {
    if (input == null || input.isAfter(f.clock.instant()))
      throw new ApiException("INVALID_CUTOFF", 400, "结算截止时间必填且不能在未来");
    return Timestamp.from(input.truncatedTo(ChronoUnit.MICROS));
  }

  private Map<String, Object> locked(String id, Actor actor) {
    var view = f.row("finance_settlements", id, actor, false);
    f.merchantLock(text(view, "merchantId"));
    return f.row("finance_settlements", id, actor, true);
  }

  private void noPending(String merchant) {
    if (!f.jdbc
        .queryForList(
            "SELECT id FROM finance_settlements WHERE merchant_id=? AND status IN ('DRAFT','APPROVED','REJECTED') LIMIT 1",
            merchant)
        .isEmpty()) throw conflict("该商户有未完成的结算，请先处理或取消");
  }

  private List<Map<String, Object>> sources(String merchant, Timestamp cutoff) {
    // ponytail: bounded 1000-source batches; explicit rejection until a reviewed pagination policy
    // exists.
    var rows =
        f.jdbc.query(
            "SELECT e.id AS entry_id,e.payment_id,e.journal_id,e.side,e.amount_minor,r.settlement_id AS prior_settlement_id,r.revision AS prior_revision FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id LEFT JOIN settlement_reservations r ON r.entry_id=e.id LEFT JOIN finance_settlements s ON s.id=r.settlement_id WHERE e.merchant_id=? AND e.account_code='MERCHANT_PAYABLE' AND j.source_type IN ('PAYMENT','REFUND','ADJUSTMENT') AND j.created_at<=? AND (r.entry_id IS NULL OR s.status='CARRIED_FORWARD') ORDER BY e.id LIMIT 1001",
            Queries.ROW,
            merchant,
            cutoff);
    if (rows.size() > 1000) throw conflict("可结算来源超过1000条，请选择更早截止时间；负数结转不能被拆分绕过");
    if (rows.isEmpty()) throw conflict("截止时间内没有可结算来源");
    // Always include every outstanding carry source, even when an earlier cutoff was requested.
    long allCarry =
        f.jdbc.queryForObject(
            "SELECT COUNT(*) FROM settlement_reservations r JOIN finance_settlements s ON s.id=r.settlement_id WHERE s.merchant_id=? AND s.status='CARRIED_FORWARD'",
            Long.class,
            merchant);
    if (rows.stream().filter(r -> r.get("priorSettlementId") != null).count() != allCarry)
      throw conflict("截止时间不能遗漏尚未抵扣的负数结转来源");
    assertUnfrozen(
        merchant,
        rows.stream().map(r -> text(r, "paymentId")).collect(java.util.stream.Collectors.toSet()));
    return rows;
  }

  private void assertUnfrozen(String merchant, Set<String> paymentIds) {
    if (balances != null) balances.assertUnheld(merchant);
    // COMPLETE publication and this check share the short merchant lock; issues remain immutable.
    var issues =
        f.jdbc.query(
            "SELECT COALESCE(c.payment_id,CASE WHEN i.operation='PAYMENT' THEN p.id WHEN i.operation='REFUND' THEN rp.id ELSE NULL END) AS payment_id FROM recon_issues i JOIN recon_batches b ON b.id=i.batch_id LEFT JOIN finance_cases c ON c.issue_id=i.id LEFT JOIN payments p ON i.operation='PAYMENT' AND p.id=i.aggregate_id AND p.merchant_id=i.merchant_id AND p.channel=b.channel LEFT JOIN refunds r ON i.operation='REFUND' AND r.id=i.aggregate_id AND r.merchant_id=i.merchant_id LEFT JOIN payments rp ON rp.id=r.payment_id AND rp.merchant_id=i.merchant_id AND rp.channel=b.channel WHERE i.merchant_id=? AND b.status='COMPLETE' AND (c.id IS NULL OR c.status<>'RESOLVED')",
            Queries.ROW,
            merchant);
    for (var issue : issues)
      if (issue.get("paymentId") == null || paymentIds.contains(text(issue, "paymentId")))
        throw conflict("来源存在未解决的对账差异，已冻结结算；请先调查并独立审批处理");
  }

  private record Snapshot(long net, String hash) {}

  private Snapshot reserve(String id, long revision, String merchant, Timestamp cutoff) {
    var rows = sources(merchant, cutoff);
    long net = 0;
    var witness = new StringBuilder();
    witness.append(merchant).append('|').append(cutoff.toInstant()).append('|').append(revision);
    for (var row : rows) {
      long signed = number(row, "amountMinor") * ("CREDIT".equals(row.get("side")) ? 1 : -1);
      try {
        net = Math.addExact(net, signed);
      } catch (ArithmeticException overflow) {
        throw conflict("结算金额溢出");
      }
      witness
          .append('|')
          .append(row.get("entryId"))
          .append(':')
          .append(signed)
          .append(':')
          .append(row.get("paymentId"))
          .append(':')
          .append(row.get("journalId"));
      f.jdbc.update(
          "INSERT INTO settlement_lines(settlement_id,revision,entry_id,payment_id,journal_id,signed_minor,prior_settlement_id,prior_revision) VALUES (?,?,?,?,?,?,?,?)",
          id,
          revision,
          row.get("entryId"),
          row.get("paymentId"),
          row.get("journalId"),
          signed,
          row.get("priorSettlementId"),
          row.get("priorRevision"));
      if (row.get("priorSettlementId") == null) {
        f.jdbc.update(
            "INSERT INTO settlement_reservations(entry_id,settlement_id,revision) VALUES (?,?,?)",
            row.get("entryId"),
            id,
            revision);
      } else {
        int updated =
            f.jdbc.update(
                "UPDATE settlement_reservations SET settlement_id=?,revision=? WHERE entry_id=? AND settlement_id=? AND revision=?",
                id,
                revision,
                row.get("entryId"),
                row.get("priorSettlementId"),
                row.get("priorRevision"));
        if (updated != 1) throw conflict("结转来源归属已变化");
      }
    }
    return new Snapshot(net, Signatures.sha256(witness.toString()));
  }

  private void release(String id, long revision) {
    var lines =
        f.jdbc.query(
            "SELECT * FROM settlement_lines WHERE settlement_id=? AND revision=?",
            Queries.ROW,
            id,
            revision);
    for (var line : lines) {
      int changed =
          line.get("priorSettlementId") == null
              ? f.jdbc.update(
                  "DELETE FROM settlement_reservations WHERE entry_id=? AND settlement_id=? AND revision=?",
                  line.get("entryId"),
                  id,
                  revision)
              : f.jdbc.update(
                  "UPDATE settlement_reservations SET settlement_id=?,revision=? WHERE entry_id=? AND settlement_id=? AND revision=?",
                  line.get("priorSettlementId"),
                  line.get("priorRevision"),
                  line.get("entryId"),
                  id,
                  revision);
      if (changed != 1) throw conflict("结算来源归属已变化，不能释放");
    }
  }

  private static void editable(Map<String, Object> row) {
    if (!Set.of("DRAFT", "APPROVED", "REJECTED").contains(text(row, "status")))
      throw conflict("已付款、已结转或已取消的结算不可修改");
  }

  public Map<String, Object> create(String merchant, Instant until, String why, Actor actor) {
    actor.require("FINANCE");
    String scoped = actor.scope(merchant, true);
    reason(why);
    Timestamp cutoff = cutoff(until);
    return f.transaction(
        () -> {
          f.merchantLock(scoped);
          noPending(scoped);
          String id = "stl_" + UUID.randomUUID();
          var snapshot = reserve(id, 1, scoped, cutoff);
          f.jdbc.update(
              "INSERT INTO finance_settlements(id,merchant_id,version,status,cutoff_at,source_hash,net_minor,reason,proposer,last_editor,created_at,updated_at) VALUES (?,?,1,'DRAFT',?,?,?,?,?,?,?,?)",
              id,
              scoped,
              cutoff,
              snapshot.hash(),
              snapshot.net(),
              why,
              actor.id(),
              actor.id(),
              f.now(),
              f.now());
          f.audit(scoped, actor, "SETTLEMENT_CREATE", id, why);
          return f.row("finance_settlements", id, actor, false);
        });
  }

  public Map<String, Object> rebuild(
      String id, long expected, Instant until, String why, Actor actor) {
    actor.require("FINANCE");
    reason(why);
    Timestamp cutoff = cutoff(until);
    return f.transaction(
        () -> {
          var row = locked(id, actor);
          version(row, expected);
          editable(row);
          release(id, expected);
          var snapshot = reserve(id, expected + 1, text(row, "merchantId"), cutoff);
          f.jdbc.update(
              "UPDATE finance_settlements SET version=version+1,status='DRAFT',cutoff_at=?,source_hash=?,net_minor=?,reason=?,last_editor=?,approved_digest=NULL,approved_version=NULL,reviewer=NULL,updated_at=? WHERE id=?",
              cutoff,
              snapshot.hash(),
              snapshot.net(),
              why,
              actor.id(),
              f.now(),
              id);
          f.audit(text(row, "merchantId"), actor, "SETTLEMENT_REBUILD", id, why);
          return f.row("finance_settlements", id, actor, false);
        });
  }

  public Map<String, Object> review(
      String id, long expected, boolean approve, String why, Actor actor) {
    actor.require("FINANCE");
    return f.transaction(
        () -> {
          var row = f.row("finance_settlements", id, actor, true);
          f.review(
              "SETTLEMENT",
              row,
              expected,
              text(row, "sourceHash"),
              approve,
              why,
              actor,
              "finance_settlements");
          return f.row("finance_settlements", id, actor, false);
        });
  }

  public Map<String, Object> cancel(String id, long expected, String why, Actor actor) {
    actor.require("FINANCE");
    reason(why);
    return f.transaction(
        () -> {
          var row = locked(id, actor);
          version(row, expected);
          if ("CANCELLED".equals(row.get("status"))) return row;
          editable(row);
          release(id, expected);
          f.jdbc.update(
              "UPDATE finance_settlements SET status='CANCELLED',updated_at=? WHERE id=?",
              f.now(),
              id);
          f.audit(text(row, "merchantId"), actor, "SETTLEMENT_CANCEL", id, why);
          return f.row("finance_settlements", id, actor, false);
        });
  }

  public Map<String, Object> execute(String id, long expected, String why, Actor actor) {
    actor.require("FINANCE");
    reason(why);
    return f.transaction(
        () -> {
          var row = locked(id, actor);
          version(row, expected);
          if (Set.of("MOCK_PAID", "CARRIED_FORWARD").contains(text(row, "status"))) return row;
          approved(row, expected, text(row, "sourceHash"));
          var lines =
              f.jdbc.query(
                  "SELECT l.payment_id,r.entry_id FROM settlement_lines l LEFT JOIN settlement_reservations r ON r.entry_id=l.entry_id AND r.settlement_id=l.settlement_id AND r.revision=l.revision WHERE l.settlement_id=? AND l.revision=?",
                  Queries.ROW,
                  id,
                  expected);
          if (lines.isEmpty() || lines.stream().anyMatch(l -> l.get("entryId") == null))
            throw conflict("结算来源归属已变化");
          assertUnfrozen(
              text(row, "merchantId"),
              lines.stream()
                  .map(l -> text(l, "paymentId"))
                  .collect(java.util.stream.Collectors.toSet()));
          long net = number(row, "netMinor");
          String journal =
              net > 0
                  ? ledger.postSettlement(id, text(row, "merchantId"), net, f.clock.instant())
                  : null;
          String status = net > 0 ? "MOCK_PAID" : "CARRIED_FORWARD";
          f.jdbc.update(
              "UPDATE finance_settlements SET status=?,journal_id=?,updated_at=? WHERE id=?",
              status,
              journal,
              f.now(),
              id);
          f.audit(text(row, "merchantId"), actor, "SETTLEMENT_EXECUTE", id, status + ": " + why);
          events.enqueue(
              "settlement-executed:" + id, "SETTLEMENT_EXECUTED", id, text(row, "merchantId"));
          return f.row("finance_settlements", id, actor, false);
        });
  }
}
