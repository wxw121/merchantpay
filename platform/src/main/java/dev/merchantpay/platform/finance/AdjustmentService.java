package dev.merchantpay.platform.finance;

import static dev.merchantpay.platform.finance.FinanceSupport.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.query.Queries;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdjustmentService {
  private final FinanceSupport f;
  private final LedgerService ledger;
  private final OutboxService events;

  public AdjustmentService(FinanceSupport f, LedgerService ledger, OutboxService events) {
    this.f = f;
    this.ledger = ledger;
    this.events = events;
  }

  public List<Map<String, Object>> cases(String merchant, Actor actor) {
    return f.list("finance_cases", merchant, actor);
  }

  public List<Map<String, Object>> proposals(String merchant, Actor actor) {
    return f.list("finance_adjustments", merchant, actor);
  }

  public Map<String, Object> caseDetail(String id, Actor actor) {
    var result = new LinkedHashMap<>(f.row("finance_cases", id, actor, false));
    result.put(
        "issue",
        f.jdbc
            .query(
                "SELECT i.*,b.channel FROM recon_issues i JOIN recon_batches b ON b.id=i.batch_id WHERE i.id=?",
                Queries.ROW,
                result.get("issueId"))
            .getFirst());
    return result;
  }

  public Map<String, Object> detail(String id, Actor actor) {
    var result = new LinkedHashMap<>(f.row("finance_adjustments", id, actor, false));
    result.put(
        "reviews",
        f.jdbc.query(
            "SELECT * FROM finance_reviews WHERE object_type='ADJUSTMENT' AND object_id=? ORDER BY object_version",
            Queries.ROW,
            id));
    return result;
  }

  public Map<String, Object> openCase(String issueId, Actor actor) {
    actor.require("FINANCE");
    return f.transaction(
        () -> {
          var rows =
              f.jdbc.query(
                  "SELECT i.*,b.status AS batch_status,b.channel FROM recon_issues i JOIN recon_batches b ON b.id=i.batch_id WHERE i.id=? FOR UPDATE",
                  Queries.ROW,
                  issueId);
          if (rows.isEmpty()) throw new ApiException("NOT_FOUND", 404, "差异不存在");
          var issue = rows.getFirst();
          actor.checkMerchant(text(issue, "merchantId"));
          if (!"COMPLETE".equals(issue.get("batchStatus"))) throw conflict("只能处理已完成对账的差异");
          var existing =
              f.jdbc.query("SELECT * FROM finance_cases WHERE issue_id=?", Queries.ROW, issueId);
          if (!existing.isEmpty()) return existing.getFirst();
          String id = "case_" + UUID.randomUUID();
          f.jdbc.update(
              "INSERT INTO finance_cases(id,issue_id,merchant_id,payment_id,status,version,created_by,created_at,updated_at) VALUES (?,?,?,?,'OPEN',1,?,?,?)",
              id,
              issueId,
              issue.get("merchantId"),
              root(issue),
              actor.id(),
              f.now(),
              f.now());
          f.audit(
              text(issue, "merchantId"),
              actor,
              "CASE_OPEN",
              id,
              "Reconciliation issue: " + issueId);
          return f.row("finance_cases", id, actor, false);
        });
  }

  private String root(Map<String, Object> issue) {
    String operation = text(issue, "operation");
    String aggregate = text(issue, "aggregateId");
    String sql =
        "REFUND".equals(operation)
            ? "SELECT p.id FROM refunds r JOIN payments p ON p.id=r.payment_id WHERE r.id=? AND p.merchant_id=? AND p.channel=?"
            : "PAYMENT".equals(operation)
                ? "SELECT id FROM payments WHERE id=? AND merchant_id=? AND channel=?"
                : null;
    if (sql == null) return null;
    var rows =
        f.jdbc.queryForList(
            sql, String.class, aggregate, issue.get("merchantId"), issue.get("channel"));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Map<String, Object> investigate(
      String id, long expected, String assignee, String paymentId, String evidence, Actor actor) {
    actor.require("FINANCE");
    reason(evidence);
    if (assignee == null || assignee.isBlank() || assignee.length() > 160)
      throw new ApiException("INVALID_ASSIGNEE", 400, "请输入负责人的身份标识");
    String requestedRoot = paymentId == null || paymentId.isBlank() ? null : paymentId;
    return f.transaction(
        () -> {
          var view = f.row("finance_cases", id, actor, false);
          // Root reassignment publishes a freeze just like finalized reconciliation does.
          f.merchantLock(text(view, "merchantId"));
          var c = f.row("finance_cases", id, actor, true);
          version(c, expected);
          if ("RESOLVED".equals(c.get("status"))) throw conflict("已解决的案件不可修改");
          var issue =
              f.jdbc
                  .query(
                      "SELECT i.*,b.channel FROM recon_issues i JOIN recon_batches b ON b.id=i.batch_id WHERE i.id=?",
                      Queries.ROW,
                      c.get("issueId"))
                  .getFirst();
          String known = root(issue);
          if (known != null && !known.equals(requestedRoot)) throw conflict("支付根必须与原始差异一致");
          if (requestedRoot != null
              && f.jdbc
                  .queryForList(
                      "SELECT id FROM payments WHERE id=? AND merchant_id=? AND channel=?",
                      requestedRoot,
                      c.get("merchantId"),
                      issue.get("channel"))
                  .isEmpty()) throw conflict("请选择相同商户及渠道的有效支付根");
          f.jdbc.update(
              "UPDATE finance_cases SET payment_id=?,assigned_to=?,evidence=?,status='INVESTIGATED',version=version+1,updated_at=? WHERE id=?",
              requestedRoot,
              assignee,
              evidence,
              f.now(),
              id);
          f.audit(
              text(c, "merchantId"),
              actor,
              "CASE_INVESTIGATE",
              id,
              "Root=" + requestedRoot + "; assignee=" + assignee + "; " + evidence);
          return f.row("finance_cases", id, actor, false);
        });
  }

  private static long amount(String direction, String value) {
    if ("NONE".equals(direction) && "0".equals(value)) return 0;
    if (!Set.of("INCREASE", "DECREASE").contains(Objects.toString(direction, "")))
      throw new ApiException("INVALID_DIRECTION", 400, "请选择增记、减记或无需调账");
    return MoneyRules.parsePositive(value);
  }

  private static void investigated(Map<String, Object> c) {
    if (!"INVESTIGATED".equals(c.get("status"))) throw conflict("请先指派并调查差异");
  }

  private static void monetaryRoot(Map<String, Object> c, String direction) {
    if (!"NONE".equals(direction) && c.get("paymentId") == null)
      throw conflict("金额调账必须绑定相同商户及渠道的支付根；未知支付只能无需调账关闭");
  }

  private static String digest(
      String id,
      Map<String, Object> c,
      long version,
      String direction,
      long amount,
      String reason) {
    return Signatures.sha256(
        id
            + "|"
            + c.get("id")
            + "|"
            + c.get("version")
            + "|"
            + c.get("paymentId")
            + "|"
            + version
            + "|"
            + direction
            + "|"
            + amount
            + "|"
            + reason);
  }

  public Map<String, Object> propose(
      String caseId, long caseVersion, String direction, String value, String why, Actor actor) {
    actor.require("FINANCE");
    long amount = amount(direction, value);
    reason(why);
    return f.transaction(
        () -> {
          var c = f.row("finance_cases", caseId, actor, true);
          version(c, caseVersion);
          investigated(c);
          monetaryRoot(c, direction);
          if (!f.jdbc
              .queryForList("SELECT id FROM finance_adjustments WHERE case_id=?", caseId)
              .isEmpty()) throw conflict("案件已有方案，请修改原方案");
          String id = "adj_" + UUID.randomUUID();
          String hash = digest(id, c, 1, direction, amount, why);
          f.jdbc.update(
              "INSERT INTO finance_adjustments(id,case_id,merchant_id,payment_id,case_version,version,status,direction,amount_minor,reason,proposer,last_editor,digest,created_at,updated_at) VALUES (?,?,?,?,?,1,'DRAFT',?,?,?,?,?,?,?,?)",
              id,
              caseId,
              c.get("merchantId"),
              c.get("paymentId"),
              caseVersion,
              direction,
              amount,
              why,
              actor.id(),
              actor.id(),
              hash,
              f.now(),
              f.now());
          f.audit(text(c, "merchantId"), actor, "ADJUSTMENT_PROPOSE", id, why);
          return f.row("finance_adjustments", id, actor, false);
        });
  }

  public Map<String, Object> revise(
      String id, long expected, String direction, String value, String why, Actor actor) {
    actor.require("FINANCE");
    long amount = amount(direction, value);
    reason(why);
    return f.transaction(
        () -> {
          var a = f.row("finance_adjustments", id, actor, true);
          version(a, expected);
          if ("EXECUTED".equals(a.get("status"))) throw conflict("已执行方案不可修改");
          var c = f.row("finance_cases", text(a, "caseId"), actor, true);
          investigated(c);
          monetaryRoot(c, direction);
          f.jdbc.update(
              "UPDATE finance_adjustments SET payment_id=?,case_version=?,version=version+1,status='DRAFT',direction=?,amount_minor=?,reason=?,last_editor=?,digest=?,approved_digest=NULL,approved_version=NULL,reviewer=NULL,updated_at=? WHERE id=?",
              c.get("paymentId"),
              c.get("version"),
              direction,
              amount,
              why,
              actor.id(),
              digest(id, c, expected + 1, direction, amount, why),
              f.now(),
              id);
          f.audit(text(a, "merchantId"), actor, "ADJUSTMENT_REVISE", id, why);
          return f.row("finance_adjustments", id, actor, false);
        });
  }

  public Map<String, Object> review(
      String id, long expected, boolean approve, String why, Actor actor) {
    actor.require("FINANCE");
    return f.transaction(
        () -> {
          var a = f.row("finance_adjustments", id, actor, true);
          var c = f.row("finance_cases", text(a, "caseId"), actor, true);
          investigated(c);
          if (number(c, "version") != number(a, "caseVersion")) throw conflict("案件证据已变化，请更新方案");
          f.review(
              "ADJUSTMENT",
              a,
              expected,
              text(a, "digest"),
              approve,
              why,
              actor,
              "finance_adjustments");
          return f.row("finance_adjustments", id, actor, false);
        });
  }

  public Map<String, Object> execute(String id, long expected, String why, Actor actor) {
    actor.require("FINANCE");
    reason(why);
    return f.transaction(
        () -> {
          var a = f.row("finance_adjustments", id, actor, true);
          version(a, expected);
          if ("EXECUTED".equals(a.get("status"))) return a;
          approved(a, expected, text(a, "digest"));
          var c = f.row("finance_cases", text(a, "caseId"), actor, true);
          investigated(c);
          if (number(c, "version") != number(a, "caseVersion")) throw conflict("案件证据已变化，请重新提议审批");
          String journal = null;
          if (number(a, "amountMinor") > 0)
            journal =
                ledger.postAdjustment(
                    id,
                    text(a, "paymentId"),
                    text(a, "merchantId"),
                    "INCREASE".equals(a.get("direction")),
                    number(a, "amountMinor"),
                    f.clock.instant());
          f.jdbc.update(
              "UPDATE finance_adjustments SET status='EXECUTED',journal_id=?,updated_at=? WHERE id=?",
              journal,
              f.now(),
              id);
          f.jdbc.update(
              "UPDATE finance_cases SET status='RESOLVED',version=version+1,updated_at=? WHERE id=?",
              f.now(),
              c.get("id"));
          f.audit(text(a, "merchantId"), actor, "ADJUSTMENT_EXECUTE", id, why);
          events.enqueue(
              "adjustment-executed:" + id, "ADJUSTMENT_EXECUTED", id, text(a, "merchantId"));
          return f.row("finance_adjustments", id, actor, false);
        });
  }
}
