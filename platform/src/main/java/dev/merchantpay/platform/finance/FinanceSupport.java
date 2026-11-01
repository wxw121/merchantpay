package dev.merchantpay.platform.finance;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.query.Queries;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared local transaction, approval and tenant rules for both financial workflows. */
@Component
public class FinanceSupport {
  final JdbcTemplate jdbc;
  final Clock clock;
  private final TransactionTemplate tx;

  public FinanceSupport(JdbcTemplate jdbc, TransactionTemplate template, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
    tx = new TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
  }

  <T> T transaction(Supplier<T> action) {
    return tx.execute(t -> action.get());
  }

  Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  static ApiException conflict(String message) {
    return new ApiException("FINANCE_CONFLICT", 409, message);
  }

  static void version(Map<String, Object> row, long expected) {
    if (expected < 1 || number(row, "version") != expected) throw conflict("版本已变化，请刷新后重新核对");
  }

  static long number(Map<String, Object> row, String key) {
    return Long.parseLong(row.get(key).toString());
  }

  static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), null);
  }

  static String reason(String value) {
    if (value == null || value.isBlank() || value.length() > 2000)
      throw new ApiException("INVALID_REASON", 400, "原因或证据必填，最长2000字");
    return value.strip();
  }

  Map<String, Object> row(String table, String id, Actor actor, boolean lock) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    var found =
        jdbc.query(
            "SELECT * FROM " + table + " WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            Queries.ROW,
            id);
    if (found.isEmpty()) throw new ApiException("NOT_FOUND", 404, "资源不存在");
    actor.checkMerchant(text(found.getFirst(), "merchantId"));
    return found.getFirst();
  }

  List<Map<String, Object>> list(String table, String merchant, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    String scope = actor.scope(merchant, false);
    return scope == null
        ? jdbc.query(
            "SELECT * FROM " + table + " ORDER BY created_at DESC,id DESC LIMIT 100", Queries.ROW)
        : jdbc.query(
            "SELECT * FROM "
                + table
                + " WHERE merchant_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
            Queries.ROW,
            scope);
  }

  void merchantLock(String merchant) {
    if (jdbc.queryForList("SELECT id FROM merchants WHERE id=? FOR UPDATE", merchant).isEmpty())
      throw new ApiException("NOT_FOUND", 404, "商户不存在");
  }

  void audit(String merchant, Actor actor, String operation, String id, String detail) {
    jdbc.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        merchant,
        actor.id(),
        operation,
        id,
        detail,
        now());
  }

  void review(
      String type,
      Map<String, Object> row,
      long expected,
      String digest,
      boolean approve,
      String reason,
      Actor actor,
      String table) {
    actor.require("FINANCE");
    version(row, expected);
    reason(reason);
    if (actor.id().equals(row.get("proposer")) || actor.id().equals(row.get("lastEditor")))
      throw conflict("提议人或最后编辑人不能审批自己的方案");
    String decision = approve ? "APPROVED" : "REJECTED";
    var previous =
        jdbc.query(
            "SELECT * FROM finance_reviews WHERE object_type=? AND object_id=? AND object_version=?",
            Queries.ROW,
            type,
            row.get("id"),
            expected);
    if (!previous.isEmpty()) {
      var review = previous.getFirst();
      if (actor.id().equals(review.get("reviewer"))
          && decision.equals(review.get("decision"))
          && digest.equals(review.get("digest"))) return;
      throw conflict("该版本已有审批记录；修改方案后再审批");
    }
    if (!"DRAFT".equals(row.get("status"))) throw conflict("只有草稿可审批");
    jdbc.update(
        "INSERT INTO finance_reviews(id,object_type,object_id,object_version,digest,reviewer,decision,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        type,
        row.get("id"),
        expected,
        digest,
        actor.id(),
        decision,
        reason,
        now());
    jdbc.update(
        "UPDATE "
            + table
            + " SET status=?,approved_digest=?,approved_version=?,reviewer=?,updated_at=? WHERE id=?",
        decision,
        approve ? digest : null,
        approve ? expected : null,
        actor.id(),
        now(),
        row.get("id"));
    audit(
        text(row, "merchantId"),
        actor,
        type + "_REVIEW",
        text(row, "id"),
        decision + ": " + reason);
  }

  static void approved(Map<String, Object> row, long expected, String digest) {
    version(row, expected);
    if (!"APPROVED".equals(row.get("status"))
        || row.get("approvedVersion") == null
        || number(row, "approvedVersion") != expected
        || !digest.equals(row.get("approvedDigest"))) throw conflict("必须由独立财务人员批准当前版本");
  }
}
