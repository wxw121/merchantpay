package dev.merchantpay.platform.finance;

import static dev.merchantpay.platform.finance.FinanceSupport.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.sharding.*;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Core approval and a recoverable, payment-owned shard journal for each adjustment. */
@Service
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
public final class ShardAdjustmentService {
  private record Intent(
      String id,
      long version,
      String payment,
      int physical,
      long epoch,
      String digest,
      String merchant,
      String channel,
      boolean increase,
      long amount) {
    ShardCatalog.Route route() {
      return new ShardCatalog.Route(PaymentBuckets.forPaymentId(payment), physical, epoch);
    }
  }

  private final FinanceSupport f;
  private final ShardExportReadGate gate;
  private final ShardTransactions shards;
  private final LedgerService ledger;
  private final OutboxService events;
  private final Clock clock;
  private final TransactionTemplate tx;

  public ShardAdjustmentService(
      FinanceSupport f,
      ShardExportReadGate gate,
      ShardTransactions shards,
      LedgerService ledger,
      OutboxService events,
      TransactionTemplate template,
      Clock clock) {
    this.f = f;
    this.gate = gate;
    this.shards = shards;
    this.ledger = ledger;
    this.events = events;
    this.clock = clock;
    tx = new TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(30);
  }

  public List<Map<String, Object>> cases(String merchant, Actor actor) {
    return list("finance_cases", merchant, actor);
  }

  public List<Map<String, Object>> proposals(String merchant, Actor actor) {
    return list("finance_adjustments", merchant, actor);
  }

  public Map<String, Object> caseDetail(String id, Actor actor) {
    var result = new LinkedHashMap<>(row("finance_cases", id, actor, false));
    result.put("issue", issue(text(result, "issueId")));
    return result;
  }

  public Map<String, Object> detail(String id, Actor actor) {
    var result = new LinkedHashMap<>(row("finance_adjustments", id, actor, false));
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
    var route = moved();
    return tx.execute(
        t -> {
          var found =
              f.jdbc.query(
                  "SELECT i.*,b.status AS batch_status,b.channel FROM recon_issues i JOIN recon_batches b ON b.id=i.batch_id WHERE i.id=? FOR UPDATE",
                  Queries.ROW,
                  issueId);
          if (found.isEmpty()) throw new ApiException("NOT_FOUND", 404, "差异不存在");
          var issue = found.getFirst();
          actor.checkMerchant(text(issue, "merchantId"));
          if (!"COMPLETE".equals(issue.get("batchStatus"))) throw conflict("只能处理已正式封版的对账差异");
          gate.validateForPublish(route);
          var existing =
              f.jdbc.query("SELECT * FROM finance_cases WHERE issue_id=?", Queries.ROW, issueId);
          if (!existing.isEmpty()) return existing.getFirst();
          String id = "case_" + UUID.randomUUID();
          f.jdbc.update(
              "INSERT INTO finance_cases(id,issue_id,merchant_id,payment_id,status,version,created_by,created_at,updated_at) VALUES(?,?,?,?,'OPEN',1,?,?,?)",
              id,
              issueId,
              issue.get("merchantId"),
              root(issue, route),
              actor.id(),
              f.now(),
              f.now());
          f.audit(text(issue, "merchantId"), actor, "SHARD_CASE_OPEN", id, issueId);
          return row("finance_cases", id, actor, false);
        });
  }

  public Map<String, Object> investigate(
      String id, long expected, String assignee, String paymentId, String evidence, Actor actor) {
    actor.require("FINANCE");
    reason(evidence);
    if (assignee == null || assignee.isBlank() || assignee.length() > 160)
      throw new ApiException("INVALID_ASSIGNEE", 400, "请输入负责人的身份标识");
    String requested = paymentId == null || paymentId.isBlank() ? null : paymentId;
    var route = moved();
    return tx.execute(
        t -> {
          var view = row("finance_cases", id, actor, false);
          f.merchantLock(text(view, "merchantId"));
          var current = row("finance_cases", id, actor, true);
          version(current, expected);
          if ("RESOLVED".equals(current.get("status"))) throw conflict("已核销案件不可修改");
          gate.validateForPublish(route);
          var issue = issue(text(current, "issueId"));
          String known = root(issue, route);
          if (known != null && !known.equals(requested)) throw conflict("支付根与原始差异不一致");
          if (requested != null)
            requirePayment(route, requested, text(current, "merchantId"), text(issue, "channel"));
          f.jdbc.update(
              "UPDATE finance_cases SET payment_id=?,assigned_to=?,evidence=?,status='INVESTIGATED',version=version+1,updated_at=? WHERE id=?",
              requested,
              assignee,
              evidence,
              f.now(),
              id);
          f.audit(text(current, "merchantId"), actor, "SHARD_CASE_INVESTIGATE", id, evidence);
          return row("finance_cases", id, actor, false);
        });
  }

  public Map<String, Object> propose(
      String caseId, long expected, String direction, String value, String why, Actor actor) {
    actor.require("FINANCE");
    moved();
    long amount = amount(direction, value);
    reason(why);
    return tx.execute(
        t -> {
          var c = row("finance_cases", caseId, actor, true);
          version(c, expected);
          investigated(c);
          monetaryRoot(c, direction);
          if (!f.jdbc
              .queryForList("SELECT id FROM finance_adjustments WHERE case_id=?", caseId)
              .isEmpty()) throw conflict("案件已有方案，请修改原方案");
          String id = "adj_" + UUID.randomUUID();
          String hash = digest(id, c, 1, direction, amount, why);
          f.jdbc.update(
              "INSERT INTO finance_adjustments(id,case_id,merchant_id,payment_id,case_version,version,status,direction,amount_minor,reason,proposer,last_editor,digest,created_at,updated_at) VALUES(?,?,?,?,?,1,'DRAFT',?,?,?,?,?,?,?,?)",
              id,
              caseId,
              c.get("merchantId"),
              c.get("paymentId"),
              expected,
              direction,
              amount,
              why,
              actor.id(),
              actor.id(),
              hash,
              f.now(),
              f.now());
          f.audit(text(c, "merchantId"), actor, "SHARD_ADJUSTMENT_PROPOSE", id, why);
          return row("finance_adjustments", id, actor, false);
        });
  }

  public Map<String, Object> revise(
      String id, long expected, String direction, String value, String why, Actor actor) {
    actor.require("FINANCE");
    moved();
    long amount = amount(direction, value);
    reason(why);
    return tx.execute(
        t -> {
          var a = row("finance_adjustments", id, actor, true);
          version(a, expected);
          if ("EXECUTED".equals(a.get("status")) || intentExists(id))
            throw conflict("已开始执行的方案不可修改");
          var c = row("finance_cases", text(a, "caseId"), actor, true);
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
          f.audit(text(a, "merchantId"), actor, "SHARD_ADJUSTMENT_REVISE", id, why);
          return row("finance_adjustments", id, actor, false);
        });
  }

  public Map<String, Object> review(
      String id, long expected, boolean approve, String why, Actor actor) {
    actor.require("FINANCE");
    moved();
    return tx.execute(
        t -> {
          var a = row("finance_adjustments", id, actor, true);
          if (intentExists(id)) throw conflict("已开始执行的方案不可再次审批");
          var c = row("finance_cases", text(a, "caseId"), actor, true);
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
          return row("finance_adjustments", id, actor, false);
        });
  }

  public Map<String, Object> execute(String id, long expected, String why, Actor actor) {
    actor.require("FINANCE");
    reason(why);
    var route = gate.capture();
    Intent intent = tx.execute(t -> prepare(id, expected, route, actor));
    if (intent != null) postOrRecover(intent, actor);
    return tx.execute(t -> finalizeExecution(id, expected, why, intent, actor));
  }

  private Intent prepare(
      String id, long expected, ShardExportReadGate.Snapshot route, Actor actor) {
    var view = row("finance_adjustments", id, actor, false);
    f.merchantLock(text(view, "merchantId"));
    var a = row("finance_adjustments", id, actor, true);
    version(a, expected);
    if ("EXECUTED".equals(a.get("status"))) return null;
    approved(a, expected, text(a, "digest"));
    var c = row("finance_cases", text(a, "caseId"), actor, true);
    investigated(c);
    if (number(c, "version") != number(a, "caseVersion")) throw conflict("案件证据已变化，请重新提议审批");
    long amount = number(a, "amountMinor");
    if (amount == 0) {
      if (route.routes().stream().allMatch(item -> item.physical() == 0))
        throw conflict("全部桶仍在核心库，请使用原单库财务入口");
      gate.validateForPublish(route);
      return null;
    }
    String payment = text(a, "paymentId");
    int bucket = PaymentBuckets.forPaymentId(payment);
    var target = route.routes().get(bucket);
    var issue = issue(text(c, "issueId"));
    var old =
        f.jdbc.queryForList(
            "SELECT proposal_version,payment_id,physical_shard,route_epoch,digest FROM sharded_adjustment_intents WHERE adjustment_id=? FOR UPDATE",
            id);
    if (!old.isEmpty()) {
      var prior = old.getFirst();
      if (((Number) prior.get("proposal_version")).longValue() != expected
          || !payment.equals(prior.get("payment_id"))
          || !Objects.equals(prior.get("digest"), a.get("digest")))
        throw conflict("调账执行意图与当前审批不一致");
      var intent =
          new Intent(
              id,
              expected,
              payment,
              ((Number) prior.get("physical_shard")).intValue(),
              ((Number) prior.get("route_epoch")).longValue(),
              text(a, "digest"),
              text(a, "merchantId"),
              text(issue, "channel"),
              "INCREASE".equals(a.get("direction")),
              amount);
      if (journal(intent) != null) return intent;
      if (intent.physical() != target.physical() || intent.epoch() != target.epoch())
        throw conflict("路由变化前未确认目标凭证，必须先核对原分片执行意图");
      gate.validateForPublish(route);
      requirePayment(route, payment, intent.merchant(), intent.channel());
      return intent;
    }
    if (route.routes().stream().allMatch(item -> item.physical() == 0))
      throw conflict("全部桶仍在核心库，请使用原单库财务入口");
    gate.validateForPublish(route);
    requirePayment(route, payment, text(a, "merchantId"), text(issue, "channel"));
    f.jdbc.update(
        "INSERT INTO sharded_adjustment_intents(adjustment_id,proposal_version,payment_id,physical_shard,route_epoch,digest,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'PREPARED',?,?)",
        id,
        expected,
        payment,
        target.physical(),
        target.epoch(),
        text(a, "digest"),
        f.now(),
        f.now());
    return new Intent(
        id,
        expected,
        payment,
        target.physical(),
        target.epoch(),
        text(a, "digest"),
        text(a, "merchantId"),
        text(issue, "channel"),
        "INCREASE".equals(a.get("direction")),
        amount);
  }

  private void postOrRecover(Intent intent, Actor actor) {
    if (journal(intent) != null) return;
    shards.write(
        intent.route(),
        local -> {
          var payments =
              local.queryForList(
                  "SELECT id FROM payments WHERE id=? AND merchant_id=? AND channel=? FOR UPDATE",
                  intent.payment(),
                  intent.merchant(),
                  intent.channel());
          if (payments.size() != 1) throw conflict("目标支付根不存在或渠道不符");
          if (journal(intent) == null) {
            String posted =
                ledger
                    .forShard(local)
                    .postAdjustment(
                        intent.id(),
                        intent.payment(),
                        intent.merchant(),
                        intent.increase(),
                        intent.amount(),
                        clock.instant());
            MerchantService.audit(
                local,
                clock,
                intent.merchant(),
                actor.id(),
                "SHARD_ADJUSTMENT_LEDGER",
                intent.id(),
                posted);
          }
          return null;
        });
    if (journal(intent) == null) throw conflict("目标分录提交结果未知，须查证后重试");
  }

  private Map<String, Object> finalizeExecution(
      String id, long expected, String why, Intent intent, Actor actor) {
    var view = row("finance_adjustments", id, actor, false);
    f.merchantLock(text(view, "merchantId"));
    var a = row("finance_adjustments", id, actor, true);
    version(a, expected);
    if ("EXECUTED".equals(a.get("status"))) return a;
    approved(a, expected, text(a, "digest"));
    var c = row("finance_cases", text(a, "caseId"), actor, true);
    investigated(c);
    if (number(c, "version") != number(a, "caseVersion")) throw conflict("案件证据已变化，请重新提议审批");
    String posted = intent == null ? null : journal(intent);
    if (intent != null && posted == null) throw conflict("目标库调账凭证未确认");
    if (f.jdbc.update(
            "UPDATE finance_adjustments SET status='EXECUTED',journal_id=?,updated_at=? WHERE id=? AND status='APPROVED' AND version=?",
            posted,
            f.now(),
            id,
            expected)
        != 1) throw conflict("调账版本已变化");
    if (f.jdbc.update(
            "UPDATE finance_cases SET status='RESOLVED',version=version+1,updated_at=? WHERE id=? AND status='INVESTIGATED' AND version=?",
            f.now(),
            c.get("id"),
            c.get("version"))
        != 1) throw conflict("案件版本已变化");
    if (intent != null
        && f.jdbc.update(
                "UPDATE sharded_adjustment_intents SET status='FINALIZED',journal_id=?,updated_at=? WHERE adjustment_id=? AND proposal_version=? AND digest=?",
                posted,
                f.now(),
                id,
                expected,
                intent.digest())
            != 1) throw conflict("调账执行意图已变化");
    f.audit(text(a, "merchantId"), actor, "SHARD_ADJUSTMENT_EXECUTE", id, why);
    events.enqueue("adjustment-executed:" + id, "ADJUSTMENT_EXECUTED", id, text(a, "merchantId"));
    return row("finance_adjustments", id, actor, false);
  }

  private String journal(Intent intent) {
    var local = new JdbcTemplate(gate.source(intent.physical()));
    var rows =
        local.queryForList(
            "SELECT id,payment_id,merchant_id,source_type,source_id FROM ledger_journals WHERE event_id=?",
            "adjustment-executed:" + intent.id());
    if (rows.isEmpty()) return null;
    if (rows.size() != 1) throw conflict("目标调账凭证不唯一");
    var row = rows.getFirst();
    if (!intent.payment().equals(row.get("payment_id"))
        || !intent.merchant().equals(row.get("merchant_id"))
        || !"ADJUSTMENT".equals(row.get("source_type"))
        || !intent.id().equals(row.get("source_id"))) throw conflict("目标调账凭证与执行意图不符");
    var entries =
        local.queryForList(
            "SELECT account_code,side,amount_minor FROM ledger_entries WHERE journal_id=? ORDER BY account_code",
            row.get("id"));
    if (entries.size() != 2) throw conflict("目标调账分录数量不符");
    String payable = intent.increase() ? "CREDIT" : "DEBIT";
    String clearing = intent.increase() ? "DEBIT" : "CREDIT";
    if (!"ADJUSTMENT_CLEARING".equals(entries.get(0).get("account_code"))
        || !clearing.equals(entries.get(0).get("side"))
        || ((Number) entries.get(0).get("amount_minor")).longValue() != intent.amount()
        || !"MERCHANT_PAYABLE".equals(entries.get(1).get("account_code"))
        || !payable.equals(entries.get(1).get("side"))
        || ((Number) entries.get(1).get("amount_minor")).longValue() != intent.amount())
      throw conflict("目标调账分录与审批金额不符");
    return (String) row.get("id");
  }

  private String root(Map<String, Object> issue, ShardExportReadGate.Snapshot route) {
    String operation = text(issue, "operation");
    String aggregate = text(issue, "aggregateId");
    int bucket;
    try {
      bucket =
          "PAYMENT".equals(operation)
              ? PaymentBuckets.forPaymentId(aggregate)
              : "REFUND".equals(operation) ? PaymentBuckets.forRefundId(aggregate) : -1;
    } catch (ApiException invalid) {
      return null;
    }
    if (bucket < 0) return null;
    var local = new JdbcTemplate(gate.source(route.routes().get(bucket).physical()));
    var found =
        "PAYMENT".equals(operation)
            ? local.queryForList(
                "SELECT id FROM payments WHERE id=? AND merchant_id=? AND channel=?",
                String.class,
                aggregate,
                issue.get("merchantId"),
                issue.get("channel"))
            : local.queryForList(
                "SELECT p.id FROM refunds r JOIN payments p ON p.id=r.payment_id WHERE r.id=? AND r.merchant_id=? AND p.merchant_id=? AND p.channel=?",
                String.class,
                aggregate,
                issue.get("merchantId"),
                issue.get("merchantId"),
                issue.get("channel"));
    if (found.isEmpty()) return null;
    String payment = found.getFirst();
    if (PaymentBuckets.forPaymentId(payment) != bucket) throw conflict("退款支付根与桶归属不符");
    return payment;
  }

  private void requirePayment(
      ShardExportReadGate.Snapshot route, String payment, String merchant, String channel) {
    int bucket = PaymentBuckets.forPaymentId(payment);
    var local = new JdbcTemplate(gate.source(route.routes().get(bucket).physical()));
    if (local
            .queryForList(
                "SELECT id FROM payments WHERE id=? AND merchant_id=? AND channel=?",
                payment,
                merchant,
                channel)
            .size()
        != 1) throw conflict("请选择相同商户及渠道的有效支付根");
  }

  private ShardExportReadGate.Snapshot moved() {
    var route = gate.capture();
    if (route.routes().stream().allMatch(item -> item.physical() == 0))
      throw conflict("全部桶仍在核心库，请使用原单库财务入口");
    return route;
  }

  private Map<String, Object> issue(String id) {
    var found =
        f.jdbc.query(
            "SELECT i.*,b.channel,b.status AS batch_status FROM recon_issues i JOIN recon_batches b ON b.id=i.batch_id WHERE i.id=?",
            Queries.ROW,
            id);
    if (found.isEmpty()) throw new ApiException("NOT_FOUND", 404, "差异不存在");
    return found.getFirst();
  }

  private Map<String, Object> row(String table, String id, Actor actor, boolean lock) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    var found =
        f.jdbc.query(
            "SELECT * FROM " + table + " WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            Queries.ROW,
            id);
    if (found.isEmpty()) throw new ApiException("NOT_FOUND", 404, "财务对象不存在");
    actor.checkMerchant(text(found.getFirst(), "merchantId"));
    return found.getFirst();
  }

  private List<Map<String, Object>> list(String table, String merchant, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    String scope = actor.scope(merchant, false);
    return scope == null
        ? f.jdbc.query(
            "SELECT * FROM " + table + " ORDER BY created_at DESC,id DESC LIMIT 100", Queries.ROW)
        : f.jdbc.query(
            "SELECT * FROM "
                + table
                + " WHERE merchant_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
            Queries.ROW,
            scope);
  }

  private boolean intentExists(String id) {
    return !f.jdbc
        .queryForList(
            "SELECT adjustment_id FROM sharded_adjustment_intents WHERE adjustment_id=?", id)
        .isEmpty();
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
      throw conflict("金额调账必须绑定同商户、同渠道的支付根");
  }

  private static String digest(
      String id, Map<String, Object> c, long version, String direction, long amount, String why) {
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
            + why);
  }
}
