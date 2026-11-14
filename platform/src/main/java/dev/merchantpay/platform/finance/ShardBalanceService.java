package dev.merchantpay.platform.finance;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Stopped-writer authoritative checkpoint over the four current physical owners. */
@Service
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
public final class ShardBalanceService {
  private static final long SCAN_BUDGET_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(150);
  private final JdbcTemplate core;
  private final ShardExportReadGate gate;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final JsonMapper json;

  public ShardBalanceService(
      JdbcTemplate core,
      ShardExportReadGate gate,
      TransactionTemplate template,
      Clock clock,
      JsonMapper json) {
    this.core = core;
    this.gate = gate;
    this.clock = clock;
    this.json = json;
    tx = new TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(180);
  }

  public List<Map<String, Object>> list(String merchant, Actor actor) {
    actor.require("FINANCE", "AUDITOR", "ADMIN", "OPS", "MERCHANT");
    return core.query(
        "SELECT * FROM balance_checkpoints WHERE merchant_id=? "
            + "AND source_routes_hash IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 50",
        Queries.ROW,
        actor.scope(merchant, true));
  }

  public List<Map<String, Object>> holds(String merchant, Actor actor) {
    actor.require("FINANCE", "AUDITOR", "ADMIN", "OPS", "MERCHANT");
    return core.query(
        "SELECT * FROM merchant_finance_holds WHERE merchant_id=? ORDER BY hold_type",
        Queries.ROW,
        actor.scope(merchant, true));
  }

  public Map<String, Object> checkpoint(
      String merchant, String reason, boolean writersStopped, Actor actor) {
    actor.require("FINANCE", "OPS");
    String scope = actor.scope(merchant, true);
    String auditReason = FinanceSupport.reason(reason);
    if (auditReason.length() > 1500)
      throw new ApiException("INVALID_REASON", 400, "四库余额检查原因最多1500字");
    if (!writersStopped) throw conflict("须实际停止四库写入者及消费者后才能执行余额检查");
    var route = gate.capture();
    if (route.routes().stream().allMatch(item -> item.physical() == 0))
      throw conflict("全部桶仍在核心库，请使用原单库余额检查");
    String expectedWatermarks = drainedWatermarks(route);
    return tx.execute(
        ignored -> {
          if (core.queryForList("SELECT id FROM merchants WHERE id=? FOR UPDATE", scope).isEmpty())
            throw new ApiException("NOT_FOUND", 404, "商户不存在");
          gate.validateForPublish(route);
          Timestamp cutoff = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
          var scan = new Scan(scope, cutoff);
          for (int physical = 0; physical < 4; physical++)
            scanSource(gate.source(physical), route, physical, scan);
          if (core.queryForObject(
                  "SELECT COUNT(*) FROM finance_settlements s WHERE s.merchant_id=? AND s.status<>'CANCELLED' AND (s.net_minor<>(SELECT COALESCE(SUM(l.signed_minor),0) FROM settlement_lines l WHERE l.settlement_id=s.id AND l.revision=s.version) OR (s.status='MOCK_PAID' AND NOT EXISTS (SELECT 1 FROM ledger_journals j WHERE j.id=s.journal_id AND j.source_type='SETTLEMENT' AND j.source_id=s.id AND j.merchant_id=s.merchant_id)))",
                  Long.class,
                  scope)
              > 0) scan.issue("结算来源合计或付款凭证不一致");
          if (!expectedWatermarks.equals(drainedWatermarks(route)))
            throw conflict("四库事件水位已变化，未发布余额检查结果");
          gate.validateForPublish(route);
          String id = UUID.randomUUID().toString();
          String hash = HexFormat.of().formatHex(scan.digest.digest());
          var totals = new TreeMap<String, String>();
          scan.totals.forEach((name, value) -> totals.put(name, value.toString()));
          boolean valid = scan.issues.isEmpty();
          String detail = valid ? "四库权威分录及凭证核对通过；借方为正，贷方为负" : String.join("；", scan.issues);
          var now = Timestamp.from(clock.instant());
          core.update(
              "INSERT INTO balance_checkpoints(id,merchant_id,cutoff_at,source_hash,source_count,account_totals,status,detail,actor,created_at,source_routes_hash,source_event_watermarks) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              scope,
              cutoff,
              hash,
              scan.count,
              json.writeValueAsString(totals),
              valid ? "CHECKED" : "FAILED",
              detail,
              actor.id(),
              now,
              Signatures.sha256(route.routes().toString()),
              expectedWatermarks);
          core.update(
              "INSERT INTO merchant_finance_holds(merchant_id,hold_type,checkpoint_id,active,reason,updated_at) VALUES (?,'BALANCE_INTEGRITY',?,?,?,?) ON DUPLICATE KEY UPDATE checkpoint_id=VALUES(checkpoint_id),active=VALUES(active),reason=VALUES(reason),updated_at=VALUES(updated_at)",
              scope,
              id,
              !valid,
              detail,
              now);
          core.update(
              "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,?,?,'SHARD_BALANCE_CHECKPOINT',?,?,?)",
              UUID.randomUUID().toString(),
              scope,
              actor.id(),
              id,
              auditReason
                  + "; routes="
                  + Signatures.sha256(route.routes().toString())
                  + "; watermarks="
                  + expectedWatermarks
                  + "; hash="
                  + hash,
              now);
          return core.query("SELECT * FROM balance_checkpoints WHERE id=?", Queries.ROW, id)
              .getFirst();
        });
  }

  private final class Scan {
    final String merchant;
    final Timestamp cutoff;
    final MessageDigest digest;
    final Map<String, BigInteger> totals = new TreeMap<>();
    final List<String> issues = new ArrayList<>();
    final long started = System.nanoTime();
    long count;

    Scan(String merchant, Timestamp cutoff) {
      this.merchant = merchant;
      this.cutoff = cutoff;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    void budget() {
      if (System.nanoTime() - started > SCAN_BUDGET_NANOS)
        throw new ApiException("CHECKPOINT_BUDGET", 409, "四库扫描超过预算，未发布检查点或解除冻结；请在维护窗口处理");
    }

    void issue(String detail) {
      if (issues.size() < 10) issues.add(detail);
    }
  }

  private void scanSource(
      DataSource source, ShardExportReadGate.Snapshot route, int physical, Scan scan) {
    try (var connection = source.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      String journal = null;
      BigInteger balance = BigInteger.ZERO;
      try (var statement =
          connection.prepareStatement(
              "SELECT e.id,e.journal_id,e.payment_id,e.merchant_id,e.account_code,e.side,e.amount_minor,e.currency,j.payment_id AS journal_payment,j.merchant_id AS journal_merchant,j.currency AS journal_currency FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE e.merchant_id=? AND j.created_at<=? ORDER BY e.journal_id,e.id",
              ResultSet.TYPE_FORWARD_ONLY,
              ResultSet.CONCUR_READ_ONLY)) {
        statement.setString(1, scan.merchant);
        statement.setTimestamp(2, scan.cutoff);
        fetch(statement, connection);
        try (var rows = statement.executeQuery()) {
          var meta = rows.getMetaData();
          while (rows.next()) {
            scan.budget();
            if (!gate.includeOwned(route, physical, "journals", rows)) continue;
            String current = rows.getString("journal_id");
            if (!current.equals(journal)) {
              if (journal != null && balance.signum() != 0)
                scan.issue("不平衡凭证：" + physical + ":" + journal);
              journal = current;
              balance = BigInteger.ZERO;
            }
            BigInteger amount = BigInteger.valueOf(rows.getLong("amount_minor"));
            String side = rows.getString("side");
            String account = rows.getString("account_code");
            String currency = rows.getString("currency");
            if (amount.signum() < 0
                || !"DEBIT".equals(side) && !"CREDIT".equals(side)
                || account == null
                || !scan.merchant.equals(rows.getString("journal_merchant"))
                || !Objects.equals(rows.getString("payment_id"), rows.getString("journal_payment"))
                || !"CNY".equals(currency)
                || !currency.equals(rows.getString("journal_currency")))
              scan.issue("分录属性异常：" + physical + ":" + rows.getLong("id"));
            BigInteger signed = "DEBIT".equals(side) ? amount : amount.negate();
            balance = balance.add(signed);
            scan.totals.merge(Objects.toString(account, ""), signed, BigInteger::add);
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("physicalShard", physical);
            for (int column = 1; column <= meta.getColumnCount(); column++)
              evidence.put(meta.getColumnLabel(column), rows.getObject(column));
            scan.digest.update(
                (json.writeValueAsString(evidence) + "\n").getBytes(StandardCharsets.UTF_8));
            scan.count = Math.addExact(scan.count, 1);
          }
        }
      }
      if (journal != null && balance.signum() != 0) scan.issue("不平衡凭证：" + physical + ":" + journal);
      scanMissing(connection, route, physical, scan);
      connection.commit();
    } catch (SQLException failure) {
      throw new ApiException("SHARDED_BALANCE_SOURCE_UNAVAILABLE", 503, "四库账务来源不可用，未发布余额检查结果");
    }
  }

  private void scanMissing(
      Connection connection, ShardExportReadGate.Snapshot route, int physical, Scan scan)
      throws SQLException {
    try (var orphan =
        connection.prepareStatement(
            "SELECT e.id,e.payment_id FROM ledger_entries e LEFT JOIN ledger_journals j ON j.id=e.journal_id WHERE e.merchant_id=? AND j.id IS NULL",
            ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY)) {
      orphan.setString(1, scan.merchant);
      fetch(orphan, connection);
      try (var rows = orphan.executeQuery()) {
        while (rows.next()) {
          scan.budget();
          if (gate.includeOwned(route, physical, "journals", rows))
            scan.issue("存在孤立分录：" + physical + ":" + rows.getLong("id"));
        }
      }
    }
    try (var empty =
        connection.prepareStatement(
            "SELECT j.id,j.payment_id FROM ledger_journals j WHERE j.merchant_id=? AND j.created_at<=? AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.journal_id=j.id)",
            ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY)) {
      empty.setString(1, scan.merchant);
      empty.setTimestamp(2, scan.cutoff);
      fetch(empty, connection);
      try (var rows = empty.executeQuery()) {
        while (rows.next()) {
          scan.budget();
          if (gate.includeOwned(route, physical, "journals", rows))
            scan.issue("存在空凭证：" + physical + ":" + rows.getString("id"));
        }
      }
    }
    try (var foreign =
        connection.prepareStatement(
            "SELECT j.id,j.payment_id FROM ledger_journals j WHERE j.merchant_id=? AND j.created_at<=? AND EXISTS (SELECT 1 FROM ledger_entries e WHERE e.journal_id=j.id AND e.merchant_id<>?)",
            ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY)) {
      foreign.setString(1, scan.merchant);
      foreign.setTimestamp(2, scan.cutoff);
      foreign.setString(3, scan.merchant);
      fetch(foreign, connection);
      try (var rows = foreign.executeQuery()) {
        while (rows.next()) {
          scan.budget();
          if (gate.includeOwned(route, physical, "journals", rows))
            scan.issue("凭证含其他商户分录：" + physical + ":" + rows.getString("id"));
        }
      }
    }
  }

  private static void fetch(PreparedStatement statement, Connection connection)
      throws SQLException {
    statement.setQueryTimeout(30);
    statement.setFetchSize(
        connection.getMetaData().getURL().startsWith("jdbc:mysql:") ? Integer.MIN_VALUE : 1000);
  }

  private String drainedWatermarks(ShardExportReadGate.Snapshot route) {
    gate.validate(route);
    for (int physical = 0; physical < 4; physical++) {
      try (var connection = gate.source(physical).getConnection()) {
        EventTransportMaintenance.requireDrained(connection);
        try (var statement =
                connection.prepareStatement(
                    "SELECT 1 FROM event_quarantine WHERE status<>'REPLAYED' LIMIT 1");
            var rows = statement.executeQuery()) {
          if (rows.next()) throw conflict("四库仍有隔离事件，不能发布余额检查");
        }
      } catch (SQLException failure) {
        throw new ApiException("SHARDED_BALANCE_SOURCE_UNAVAILABLE", 503, "无法核对四库任务和事件排空状态");
      } catch (IllegalStateException pending) {
        throw conflict("四库任务或事件尚未排空");
      }
    }
    return watermarks(route);
  }

  private String watermarks(ShardExportReadGate.Snapshot route) {
    gate.validate(route);
    var values = new StringJoiner(",");
    try {
      for (int physical = 0; physical < 4; physical++) {
        var local = new JdbcTemplate(gate.source(physical));
        values.add(
            Long.toString(
                Objects.requireNonNull(
                    local.queryForObject(
                        "SELECT COALESCE(MAX(sequence_no),0) FROM domain_events", Long.class))));
      }
    } catch (DataAccessException failure) {
      throw new ApiException("SHARDED_BALANCE_SOURCE_UNAVAILABLE", 503, "无法核对四库事件水位，未发布余额检查结果");
    }
    gate.validate(route);
    return values.toString();
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARDED_BALANCE_CONFLICT", 409, message);
  }
}
