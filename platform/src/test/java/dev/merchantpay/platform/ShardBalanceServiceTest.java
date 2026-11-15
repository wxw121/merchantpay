package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.finance.ShardBalanceService;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ShardBalanceServiceTest {
  private static final String PAYMENT = "p4_00000000-0000-0000-0000-000000000004";
  private static final Actor FINANCE = new Actor("finance", null, Set.of("FINANCE"));

  private record Fixture(
      Map<Integer, DataSource> sources, JdbcTemplate core, ShardBalanceService service) {
    JdbcTemplate target(int physical) {
      return new JdbcTemplate(sources.get(physical));
    }

    long checkpoints() {
      return core.queryForObject("SELECT COUNT(*) FROM balance_checkpoints", Long.class);
    }
  }

  private static Fixture fixture() {
    var sources = new HashMap<Integer, DataSource>();
    for (int physical = 0; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:shard_balance_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      sources.put(physical, source);
      var local = new JdbcTemplate(source);
      local.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      local.update("INSERT INTO v3_shard_identity VALUES(1,?)", physical);
      local.execute(
          "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
      local.execute(
          "CREATE TABLE ledger_journals(id VARCHAR(64) PRIMARY KEY,payment_id VARCHAR(64),merchant_id VARCHAR(64),currency VARCHAR(3),source_type VARCHAR(32),source_id VARCHAR(64),created_at TIMESTAMP)");
      local.execute(
          "CREATE TABLE ledger_entries(id BIGINT PRIMARY KEY,journal_id VARCHAR(64),payment_id VARCHAR(64),merchant_id VARCHAR(64),account_code VARCHAR(40),side VARCHAR(8),amount_minor BIGINT,currency VARCHAR(3))");
      local.execute("CREATE TABLE payments(status VARCHAR(24))");
      local.execute("CREATE TABLE refunds(status VARCHAR(24))");
      local.execute("CREATE TABLE outbox_tasks(status VARCHAR(24))");
      local.execute("CREATE TABLE inbox_notifications(status VARCHAR(24))");
      local.execute(
          "CREATE TABLE domain_events(sequence_no BIGINT,event_id VARCHAR(160),event_type VARCHAR(64),delivery_mode VARCHAR(16))");
      local.execute("CREATE TABLE event_receipts(consumer_name VARCHAR(64),event_id VARCHAR(160))");
      local.execute("CREATE TABLE event_quarantine(status VARCHAR(16))");
      if (physical == 0) {
        local.execute(
            "CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT,epoch BIGINT)");
        local.execute(
            "CREATE TABLE v3_shard_cutovers(bucket INT PRIMARY KEY,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
        for (int bucket = 0; bucket < 16; bucket++) {
          local.update("INSERT INTO v3_shard_routes VALUES(?,0,1)", bucket);
          local.update("INSERT INTO v3_bucket_fences VALUES(?,1,'ACTIVE')", bucket);
        }
        local.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=4");
        local.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
        local.update("INSERT INTO v3_shard_cutovers VALUES(4,1,2,1,'ACTIVE')");
        local.execute("CREATE TABLE merchants(id VARCHAR(64) PRIMARY KEY)");
        local.update("INSERT INTO merchants VALUES('merchant-a')");
        local.execute(
            "CREATE TABLE finance_settlements(id VARCHAR(64),merchant_id VARCHAR(64),status VARCHAR(24),net_minor BIGINT,version BIGINT,journal_id VARCHAR(64))");
        local.execute(
            "CREATE TABLE settlement_lines(settlement_id VARCHAR(64),revision BIGINT,signed_minor BIGINT)");
        local.execute(
            "CREATE TABLE balance_checkpoints(id VARCHAR(36) PRIMARY KEY,merchant_id VARCHAR(64),cutoff_at TIMESTAMP,source_hash VARCHAR(64),source_count BIGINT,account_totals VARCHAR(2000),status VARCHAR(16),detail VARCHAR(2000),actor VARCHAR(160),created_at TIMESTAMP,source_routes_hash VARCHAR(64),source_event_watermarks VARCHAR(128))");
        local.execute(
            "CREATE TABLE merchant_finance_holds(merchant_id VARCHAR(64),hold_type VARCHAR(32),checkpoint_id VARCHAR(36),active BOOLEAN,reason VARCHAR(2000),updated_at TIMESTAMP,PRIMARY KEY(merchant_id,hold_type))");
        local.execute(
            "CREATE TABLE audit_records(id VARCHAR(36),merchant_id VARCHAR(64),actor VARCHAR(160),operation VARCHAR(64),resource_id VARCHAR(64),detail VARCHAR(2000),created_at TIMESTAMP)");
      } else if (physical == 1) {
        local.update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
      }
    }
    var core = new JdbcTemplate(sources.get(0));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(sources.get(0)));
    var service =
        new ShardBalanceService(
            core,
            new ShardExportReadGate(sources),
            tx,
            Clock.systemUTC(),
            JsonMapper.builder().build());
    return new Fixture(sources, core, service);
  }

  private static void journal(JdbcTemplate source, String id) {
    source.update(
        "INSERT INTO ledger_journals VALUES(?,?,?,'CNY','PAYMENT',?,CURRENT_TIMESTAMP)",
        id,
        PAYMENT,
        "merchant-a",
        PAYMENT);
    source.update(
        "INSERT INTO ledger_entries VALUES(1,?,?,?,'CHANNEL_RECEIVABLE','DEBIT',100,'CNY')",
        id,
        PAYMENT,
        "merchant-a");
    source.update(
        "INSERT INTO ledger_entries VALUES(2,?,?,?,'MERCHANT_PAYABLE','CREDIT',100,'CNY')",
        id,
        PAYMENT,
        "merchant-a");
  }

  @Test
  void retiredCoreCopyIsExcludedAndTargetImbalanceFreezesSettlement() {
    var fixture = fixture();
    journal(fixture.core(), "journal-4");
    journal(fixture.target(1), "journal-4");
    assertThatThrownBy(() -> fixture.service().checkpoint("merchant-a", "verify", false, FINANCE))
        .isInstanceOf(ApiException.class);
    var good = fixture.service().checkpoint("merchant-a", "verify", true, FINANCE);
    assertThat(good.get("status")).isEqualTo("CHECKED");
    assertThat(((Number) good.get("sourceCount")).longValue()).isEqualTo(2);
    assertThat(good.get("sourceRoutesHash").toString()).hasSize(64);
    assertThat(
            fixture
                .core()
                .queryForObject(
                    "SELECT active FROM merchant_finance_holds WHERE merchant_id='merchant-a'",
                    Boolean.class))
        .isFalse();
    fixture.target(1).update("UPDATE ledger_entries SET amount_minor=101 WHERE id=1");
    var broken = fixture.service().checkpoint("merchant-a", "verify again", true, FINANCE);
    assertThat(broken.get("status")).isEqualTo("FAILED");
    assertThat(
            fixture
                .core()
                .queryForObject(
                    "SELECT active FROM merchant_finance_holds WHERE merchant_id='merchant-a'",
                    Boolean.class))
        .isTrue();
  }

  @Test
  void wrongPhysicalSourceCannotPublishOrClearTheExistingHold() {
    var fixture = fixture();
    journal(fixture.target(1), "journal-4");
    journal(fixture.target(2), "wrong-source");
    assertThatThrownBy(() -> fixture.service().checkpoint("merchant-a", "verify", true, FINANCE))
        .isInstanceOf(ApiException.class);
    assertThat(fixture.checkpoints()).isZero();
  }

  @Test
  void foreignMerchantEntryFreezesTheCheckpoint() {
    var fixture = fixture();
    journal(fixture.target(1), "journal-4");
    fixture.target(1).update("UPDATE ledger_entries SET merchant_id='merchant-b' WHERE id=2");
    var result = fixture.service().checkpoint("merchant-a", "verify", true, FINANCE);
    assertThat(result.get("status")).isEqualTo("FAILED");
    assertThat(result.get("detail").toString()).contains("其他商户分录");
    assertThat(
            fixture
                .core()
                .queryForObject(
                    "SELECT active FROM merchant_finance_holds WHERE merchant_id='merchant-a'",
                    Boolean.class))
        .isTrue();
  }

  @Test
  void pendingWorkCannotPublishCheckpoint() {
    var fixture = fixture();
    journal(fixture.target(1), "journal-4");
    fixture.target(2).update("INSERT INTO outbox_tasks VALUES('PENDING')");
    assertThatThrownBy(() -> fixture.service().checkpoint("merchant-a", "verify", true, FINANCE))
        .isInstanceOf(ApiException.class);
    assertThat(fixture.checkpoints()).isZero();
  }
}
