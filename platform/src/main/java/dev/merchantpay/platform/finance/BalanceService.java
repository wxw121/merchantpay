package dev.merchantpay.platform.finance;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.query.Queries;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Consistent authoritative scan. No query projection or cached balance can publish this hold. */
@Service
public class BalanceService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final JsonMapper json;

  public BalanceService(
      JdbcTemplate jdbc, TransactionTemplate template, Clock clock, JsonMapper json) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.json = json;
    tx = new TransactionTemplate(template.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(180);
  }

  public List<Map<String, Object>> list(String merchant, Actor actor) {
    actor.require("FINANCE", "AUDITOR", "ADMIN", "OPS", "MERCHANT");
    return jdbc.query(
        "SELECT * FROM balance_checkpoints WHERE merchant_id=? ORDER BY created_at DESC,id DESC LIMIT 50",
        Queries.ROW,
        actor.scope(merchant, true));
  }

  public List<Map<String, Object>> holds(String merchant, Actor actor) {
    actor.require("FINANCE", "AUDITOR", "ADMIN", "OPS", "MERCHANT");
    return jdbc.query(
        "SELECT * FROM merchant_finance_holds WHERE merchant_id=? ORDER BY hold_type",
        Queries.ROW,
        actor.scope(merchant, true));
  }

  public void assertUnheld(String merchant) {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM merchant_finance_holds WHERE merchant_id=? AND active=true",
            Long.class,
            merchant)
        > 0) throw new ApiException("BALANCE_HOLD", 409, "账务检查未通过，已冻结结算；完成新的权威检查后处理");
  }

  public Map<String, Object> checkpoint(String merchant, String reason, Actor actor) {
    actor.require("FINANCE", "OPS");
    String scope = actor.scope(merchant, true);
    FinanceSupport.reason(reason);
    return tx.execute(
        t -> {
          if (jdbc.queryForList("SELECT id FROM merchants WHERE id=? FOR UPDATE", scope).isEmpty())
            throw new ApiException("NOT_FOUND", 404, "商户不存在");
          Timestamp cutoff = Timestamp.from(clock.instant());
          String id = UUID.randomUUID().toString();
          MessageDigest digest;
          try {
            digest = MessageDigest.getInstance("SHA-256");
          } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
          }
          var totals = new TreeMap<String, BigInteger>();
          var issues = new ArrayList<String>();
          long count = 0, started = System.nanoTime();
          String afterJournal = "", current = null;
          long afterEntry = 0;
          BigInteger journalBalance = BigInteger.ZERO;
          while (true) {
            if (System.nanoTime() - started > java.util.concurrent.TimeUnit.SECONDS.toNanos(150))
              throw new ApiException("CHECKPOINT_BUDGET", 409, "扫描超过预算，未发布检查点或解除冻结；请在维护窗口处理");
            var rows =
                jdbc.queryForList(
                    "SELECT e.id,e.journal_id,e.payment_id,e.merchant_id,e.account_code,e.side,e.amount_minor,e.currency,j.merchant_id AS journal_merchant,j.currency AS journal_currency FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE e.merchant_id=? AND j.created_at<=? AND (e.journal_id>? OR (e.journal_id=? AND e.id>?)) ORDER BY e.journal_id,e.id LIMIT 500",
                    scope,
                    cutoff,
                    afterJournal,
                    afterJournal,
                    afterEntry);
            if (rows.isEmpty()) break;
            for (var row : rows) {
              String journal = row.get("journal_id").toString();
              if (!journal.equals(current)) {
                if (current != null && journalBalance.signum() != 0)
                  issue(issues, "不平衡凭证：" + current);
                current = journal;
                journalBalance = BigInteger.ZERO;
              }
              BigInteger amount = new BigInteger(row.get("amount_minor").toString());
              String side = row.get("side").toString();
              if (amount.signum() < 0
                  || !Set.of("DEBIT", "CREDIT").contains(side)
                  || !scope.equals(row.get("journal_merchant"))
                  || !"CNY".equals(row.get("currency"))
                  || !row.get("currency").equals(row.get("journal_currency")))
                issue(issues, "分录属性异常：" + row.get("id"));
              BigInteger signed = "DEBIT".equals(side) ? amount : amount.negate();
              journalBalance = journalBalance.add(signed);
              totals.merge(row.get("account_code").toString(), signed, BigInteger::add);
              digest.update((json.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8));
              count++;
              afterJournal = journal;
              afterEntry = ((Number) row.get("id")).longValue();
            }
          }
          if (current != null && journalBalance.signum() != 0) issue(issues, "不平衡凭证：" + current);
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ledger_entries e LEFT JOIN ledger_journals j ON j.id=e.journal_id WHERE e.merchant_id=? AND j.id IS NULL",
                  Long.class,
                  scope)
              > 0) issue(issues, "存在孤立分录");
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ledger_journals j WHERE j.merchant_id=? AND j.created_at<=? AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.journal_id=j.id)",
                  Long.class,
                  scope,
                  cutoff)
              > 0) issue(issues, "存在空凭证");
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM finance_settlements s WHERE s.merchant_id=? AND s.status<>'CANCELLED' AND (s.net_minor<>(SELECT COALESCE(SUM(l.signed_minor),0) FROM settlement_lines l WHERE l.settlement_id=s.id AND l.revision=s.version) OR (s.status='MOCK_PAID' AND NOT EXISTS (SELECT 1 FROM ledger_journals j WHERE j.id=s.journal_id AND j.source_type='SETTLEMENT' AND j.source_id=s.id AND j.merchant_id=s.merchant_id)))",
                  Long.class,
                  scope)
              > 0) issue(issues, "结算来源合计或付款凭证不一致");
          var values = new TreeMap<String, String>();
          totals.forEach((key, value) -> values.put(key, value.toString()));
          String hash = HexFormat.of().formatHex(digest.digest());
          boolean valid = issues.isEmpty();
          String detail = valid ? "双重记账及结算来源核对通过；借方为正，贷方为负" : String.join("；", issues);
          jdbc.update(
              "INSERT INTO balance_checkpoints(id,merchant_id,cutoff_at,source_hash,source_count,account_totals,status,detail,actor,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
              id,
              scope,
              cutoff,
              hash,
              count,
              json.writeValueAsString(values),
              valid ? "CHECKED" : "FAILED",
              detail,
              actor.id(),
              Timestamp.from(clock.instant()));
          jdbc.update(
              "INSERT INTO merchant_finance_holds(merchant_id,hold_type,checkpoint_id,active,reason,updated_at) VALUES (?,'BALANCE_INTEGRITY',?,?,?,?) ON DUPLICATE KEY UPDATE checkpoint_id=VALUES(checkpoint_id),active=VALUES(active),reason=VALUES(reason),updated_at=VALUES(updated_at)",
              scope,
              id,
              !valid,
              detail,
              Timestamp.from(clock.instant()));
          jdbc.update(
              "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,?,?,'BALANCE_CHECKPOINT',?,?,?)",
              UUID.randomUUID().toString(),
              scope,
              actor.id(),
              id,
              reason + "; " + hash,
              Timestamp.from(clock.instant()));
          return jdbc.query("SELECT * FROM balance_checkpoints WHERE id=?", Queries.ROW, id)
              .getFirst();
        });
  }

  private static void issue(List<String> issues, String detail) {
    if (issues.size() < 10) issues.add(detail);
  }
}
