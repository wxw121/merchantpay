package dev.merchantpay.platform;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.finance.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.*;
import java.time.Clock;
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
  void financeSchema() {
    for (String file :
        new String[] {
          "V4__reconciliation.sql",
          "V18__reliable_domain_events.sql",
          "V19__financial_workflows.sql"
        })
      ScriptUtils.executeSqlScript(schemaConnection, new ClassPathResource("db/migration/" + file));
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
