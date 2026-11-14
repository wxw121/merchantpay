package dev.merchantpay.platform;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.finance.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.*;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

abstract class FinanceFixture extends FinancialFixture {
  final Actor proposer = new Actor("finance-one", null, Set.of("FINANCE"));
  final Actor reviewer = new Actor("finance-two", null, Set.of("FINANCE"));
  FinanceSupport support;
  AdjustmentService adjustments;
  TransactionTemplate tx;

  @BeforeEach
  void financeSchema() throws Exception {
    for (String file :
        new String[] {
          "V4__reconciliation.sql",
          "V18__reliable_domain_events.sql",
          "V19__financial_workflows.sql"
        })
      ScriptUtils.executeSqlScript(schemaConnection, new ClassPathResource("db/migration/" + file));
    if ("MySQL".equals(schemaConnection.getMetaData().getDatabaseProductName())) {
      ScriptUtils.executeSqlScript(
          schemaConnection,
          new ClassPathResource("db/migration/V32__sharded_settlement_sources.sql"));
    } else {
      // H2 cannot parse MySQL's DROP/ADD PRIMARY KEY in V32.
      jdbc.execute(
          "ALTER TABLE settlement_lines ADD COLUMN physical_shard TINYINT NOT NULL DEFAULT 0");
      replacePrimaryKey("settlement_lines", "settlement_id,revision,physical_shard,entry_id");
      jdbc.execute(
          "ALTER TABLE settlement_reservations ADD COLUMN physical_shard TINYINT NOT NULL DEFAULT 0");
      replacePrimaryKey("settlement_reservations", "physical_shard,entry_id");
      jdbc.execute("ALTER TABLE finance_settlements ADD COLUMN source_routes_hash VARCHAR(64)");
      jdbc.execute(
          "ALTER TABLE finance_settlements ADD COLUMN source_event_watermarks VARCHAR(120)");
      jdbc.execute(
          "CREATE TABLE sharded_settlement_cursors (merchant_id VARCHAR(64) NOT NULL,"
              + "physical_shard TINYINT NOT NULL,last_entry_id BIGINT NOT NULL DEFAULT 0,"
              + "PRIMARY KEY(merchant_id,physical_shard))");
    }
    ScriptUtils.executeSqlScript(
        schemaConnection,
        new ClassPathResource("db/migration/V33__sharded_adjustment_intents.sql"));
    tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    support = new FinanceSupport(jdbc, tx, Clock.systemUTC());
    var events =
        new OutboxService(
            jdbc,
            Clock.systemUTC(),
            false,
            new EventJournal(jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "POLLING"));
    adjustments = new AdjustmentService(support, new LedgerService(jdbc), events);
  }

  private void replacePrimaryKey(String table, String columns) throws Exception {
    try (var keys =
        schemaConnection.getMetaData().getPrimaryKeys(null, null, table.toUpperCase(Locale.ROOT))) {
      if (!keys.next()) throw new IllegalStateException("Missing fixture primary key: " + table);
      String name = keys.getString("PK_NAME");
      if (name == null || !name.matches("[A-Za-z0-9_]+"))
        throw new IllegalStateException("Invalid fixture primary key: " + table);
      jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + name);
      jdbc.execute("ALTER TABLE " + table + " ADD PRIMARY KEY(" + columns + ")");
    }
  }

  String issue(String paymentId) {
    String id = java.util.UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO recon_batches(id,merchant_id,channel,business_day,version,sha256,storage_key,status,created_at,updated_at) VALUES (?,'merchant-a','MOCK_A',CURRENT_DATE,1,'hash','key','COMPLETE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        id);
    jdbc.update(
        "INSERT INTO recon_issues(id,batch_id,merchant_id,line_no,operation,aggregate_id,issue_type,created_at) VALUES (?,?,'merchant-a',1,'PAYMENT',?,'AMOUNT_MISMATCH',CURRENT_TIMESTAMP)",
        id,
        id,
        paymentId);
    return id;
  }

  String investigated(String root) {
    String id = (String) adjustments.openCase(issue(root), proposer).get("id");
    adjustments.investigate(id, 1, proposer.id(), root, "已核对原始渠道流水", proposer);
    return id;
  }
}
