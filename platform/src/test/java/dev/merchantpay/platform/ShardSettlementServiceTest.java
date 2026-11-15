package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.finance.ShardAdjustmentService;
import dev.merchantpay.platform.finance.ShardSettlementService;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import dev.merchantpay.platform.sharding.ShardTransactions;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

class ShardSettlementServiceTest extends FinanceFixture {
  ShardSettlementService settlements;
  ShardAdjustmentService shardedAdjustments;
  JdbcTemplate target;

  @BeforeEach
  void shardedFinance() {
    for (String file :
        List.of(
            "V21__projection_and_balance_checkpoints.sql",
            "V24__shard_catalog.sql",
            "V25__shard_ownership.sql",
            "V41__shard_finance_handoff.sql"))
      ScriptUtils.executeSqlScript(schemaConnection, new ClassPathResource("db/migration/" + file));
    jdbc.execute(
        "CREATE TABLE v3_shard_cutovers(bucket INT PRIMARY KEY,plan_id VARCHAR(36),source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
    jdbc.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=4");
    jdbc.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
    jdbc.update(
        "INSERT INTO v3_shard_cutovers(bucket,source_epoch,target_epoch,target_shard,state) VALUES(4,1,2,1,'ACTIVE')");
    Map<Integer, DataSource> sources = new HashMap<>();
    sources.put(0, jdbc.getDataSource());
    for (int physical = 1; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:shard_settlement_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      sources.put(physical, source);
      var local = new JdbcTemplate(source);
      local.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      local.update("INSERT INTO v3_shard_identity VALUES(1,?)", physical);
      local.execute(
          "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
      local.execute(
          "CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),channel VARCHAR(32),status VARCHAR(24))");
      local.execute(
          "CREATE TABLE refunds(id VARCHAR(64) PRIMARY KEY,payment_id VARCHAR(64),merchant_id VARCHAR(64),status VARCHAR(24))");
      local.execute("CREATE TABLE inbox_notifications(status VARCHAR(24))");
      local.execute("CREATE TABLE outbox_tasks(status VARCHAR(24))");
      local.execute(
          "CREATE TABLE domain_events(sequence_no BIGINT PRIMARY KEY,delivery_mode VARCHAR(16),event_type VARCHAR(64),event_id VARCHAR(160))");
      local.execute("CREATE TABLE event_receipts(consumer_name VARCHAR(64),event_id VARCHAR(160))");
      local.execute("CREATE TABLE event_quarantine(status VARCHAR(16))");
      local.execute(
          "CREATE TABLE audit_records(id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),actor VARCHAR(160),operation VARCHAR(64),resource_id VARCHAR(64),detail VARCHAR(2000),created_at TIMESTAMP)");
      local.execute(
          "CREATE TABLE ledger_journals(id VARCHAR(64) PRIMARY KEY,event_id VARCHAR(160) UNIQUE,payment_id VARCHAR(64),merchant_id VARCHAR(64),source_type VARCHAR(32),source_id VARCHAR(64),created_at TIMESTAMP,currency VARCHAR(3))");
      local.execute(
          "CREATE TABLE ledger_entries(id BIGINT AUTO_INCREMENT PRIMARY KEY,journal_id VARCHAR(64),payment_id VARCHAR(64),merchant_id VARCHAR(64),account_code VARCHAR(40),side VARCHAR(8),amount_minor BIGINT,currency VARCHAR(3))");
      if (physical == 1) {
        local.update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
        target = local;
      }
    }
    var gate = new ShardExportReadGate(sources);
    var events =
        new OutboxService(
            jdbc,
            Clock.systemUTC(),
            false,
            new dev.merchantpay.platform.outbox.EventJournal(
                jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "POLLING"));
    settlements =
        new ShardSettlementService(
            support, gate, new LedgerService(jdbc), events, tx, Clock.systemUTC());
    shardedAdjustments =
        new ShardAdjustmentService(
            support,
            gate,
            new ShardTransactions(jdbc, sources),
            new LedgerService(jdbc),
            events,
            tx,
            Clock.systemUTC());
  }

  private void source(long entry, long amount, String side, String type) {
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    String journal = "j_source_" + entry;
    String sourceId = "REFUND".equals(type) ? "r4_00000000-0000-0000-0000-000000000000" : payment;
    if (target.queryForObject("SELECT COUNT(*) FROM payments WHERE id=?", Long.class, payment) == 0)
      target.update("INSERT INTO payments VALUES(?,'merchant-a','MOCK_A','SUCCEEDED')", payment);
    if ("REFUND".equals(type))
      target.update("INSERT INTO refunds VALUES(?,?,'merchant-a','SUCCEEDED')", sourceId, payment);
    target.update(
        "INSERT INTO ledger_journals(id,payment_id,merchant_id,source_type,source_id,created_at,currency) VALUES(?,?,?,?,?,?,'CNY')",
        journal,
        payment,
        "merchant-a",
        type,
        sourceId,
        Timestamp.from(Instant.now().minusSeconds(60)));
    target.update(
        "INSERT INTO ledger_entries VALUES(?,?,?,'merchant-a','MERCHANT_PAYABLE',?,?,'CNY')",
        entry,
        journal,
        payment,
        side,
        amount);
  }

  private String draft() {
    return (String)
        settlements.create("merchant-a", Instant.now(), "四库来源核对", true, proposer).get("id");
  }

  @Test
  void reservesSourceOnceAndCancelReleasesIt() {
    source(1, 100, "CREDIT", "PAYMENT");
    assertThatThrownBy(
            () -> settlements.create("merchant-a", Instant.now(), "未停写", false, proposer))
        .hasMessageContaining("停止全部写入");
    String first = draft();
    assertThat(settlements.detail(first, proposer).get("netMinor")).isEqualTo("100");
    assertThatThrownBy(this::draft).hasMessageContaining("未完成的结算");
    settlements.cancel(first, 1, "重建来源", proposer);
    String second = draft();
    assertThat(second).isNotEqualTo(first);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_reservations WHERE physical_shard=1 AND entry_id=1",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void equalEntryIdsOnDifferentPhysicalSourcesHaveSeparateReservations() {
    String order = "core-source-0";
    while (PaymentBuckets.forBusinessKey("merchant-a", order) == 4) order += "x";
    String corePayment = paid(order, "1000").id();
    long entry =
        jdbc.queryForObject(
            "SELECT id FROM ledger_entries WHERE payment_id=? AND account_code='MERCHANT_PAYABLE'",
            Long.class,
            corePayment);
    source(entry, 100, "CREDIT", "PAYMENT");
    jdbc.update("UPDATE outbox_tasks SET status='DONE'");
    String settlement = draft();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_reservations WHERE settlement_id=? AND entry_id=?",
                Long.class,
                settlement,
                entry))
        .isEqualTo(2L);
  }

  @Test
  void negativeSourceCarriesThenOffsetsTheNextPositiveSource() {
    source(1, 100, "DEBIT", "REFUND");
    String carry = draft();
    settlements.review(carry, 1, true, "独立复核", reviewer);
    settlements.execute(carry, 1, "负数结转", proposer);
    assertThat(settlements.detail(carry, proposer).get("status")).isEqualTo("CARRIED_FORWARD");
    jdbc.update("UPDATE outbox_tasks SET status='DONE' WHERE aggregate_id=?", carry);
    source(2, 150, "CREDIT", "PAYMENT");
    String next = draft();
    assertThat(settlements.detail(next, proposer).get("netMinor")).isEqualTo("50");
    settlements.review(next, 1, true, "复核抵扣", reviewer);
    settlements.execute(next, 1, "模拟付款", proposer);
    assertThat(settlements.detail(next, proposer).get("status")).isEqualTo("MOCK_PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE source_type='SETTLEMENT'", Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_entry_id FROM sharded_settlement_cursors WHERE merchant_id='merchant-a' AND physical_shard=1",
                Long.class))
        .isEqualTo(2L);
  }

  @Test
  void migratedPaidEntryWithoutReservationCannotBePaidAgain() {
    source(1, 100, "CREDIT", "PAYMENT");
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    var now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO finance_settlements(id,merchant_id,version,status,cutoff_at,source_hash,net_minor,reason,proposer,last_editor,created_at,updated_at) VALUES('paid-before-move','merchant-a',1,'MOCK_PAID',?,'hash',100,'paid','finance','finance',?,?)",
        now,
        now,
        now);
    jdbc.update(
        "INSERT INTO settlement_lines(settlement_id,revision,physical_shard,entry_id,payment_id,journal_id,signed_minor) VALUES('paid-before-move',1,0,99,?,'j_source_1',100)",
        payment);
    jdbc.update(
        "INSERT INTO v3_shard_finance_handoffs(plan_id,source_shard,source_entry_id,target_shard,target_entry_id,merchant_id,payment_id) VALUES(?,0,99,1,1,'merchant-a',?)",
        UUID.randomUUID().toString(),
        payment);

    assertThatThrownBy(this::draft).hasMessageContaining("已有完成结算但预占缺失");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_reservations WHERE physical_shard=1 AND entry_id=1",
                Long.class))
        .isZero();
  }

  @Test
  void adjustmentRecoversAfterTargetJournalCommitsButCoreFinalizationFails() {
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    target.update("INSERT INTO payments VALUES(?,'merchant-a','MOCK_A','SUCCEEDED')", payment);
    String issueId = issue(payment);
    String caseId = (String) shardedAdjustments.openCase(issueId, proposer).get("id");
    shardedAdjustments.investigate(caseId, 1, proposer.id(), payment, "核对目标库权威支付", proposer);
    String adjustment =
        (String)
            shardedAdjustments.propose(caseId, 2, "INCREASE", "100", "补记商户应付", proposer).get("id");
    shardedAdjustments.review(adjustment, 1, true, "独立审批", reviewer);
    jdbc.update("UPDATE event_transport SET mode='CDC' WHERE id=1");
    assertThatThrownBy(() -> shardedAdjustments.execute(adjustment, 1, "执行", proposer))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE event_id=?",
                Long.class,
                "adjustment-executed:" + adjustment))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM finance_adjustments WHERE id=?", String.class, adjustment))
        .isEqualTo("APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sharded_adjustment_intents WHERE adjustment_id=?",
                String.class,
                adjustment))
        .isEqualTo("PREPARED");
    jdbc.update("UPDATE event_transport SET mode='POLLING' WHERE id=1");
    shardedAdjustments.execute(adjustment, 1, "恢复完成", proposer);
    shardedAdjustments.execute(adjustment, 1, "幂等重试", proposer);
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE event_id=?",
                Long.class,
                "adjustment-executed:" + adjustment))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM finance_adjustments WHERE id=?", String.class, adjustment))
        .isEqualTo("EXECUTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sharded_adjustment_intents WHERE adjustment_id=?",
                String.class,
                adjustment))
        .isEqualTo("FINALIZED");
  }
}
