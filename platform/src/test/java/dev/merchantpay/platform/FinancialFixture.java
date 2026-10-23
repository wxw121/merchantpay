package dev.merchantpay.platform;

import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Fast real-SQL checks; MySQL-specific locking and recovery have a separate acceptance suite. */
abstract class FinancialFixture {
  JdbcTemplate jdbc;
  PaymentService payments;
  RefundService refunds;
  Connection schemaConnection;

  @BeforeEach
  void database() throws Exception {
    var source = source();
    // H2 2.4.240 CHECK(IN ...) retains the creating session (#4302); keep it alive.
    schemaConnection = source.getConnection();
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V1__core.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V7__isolated_task_queues.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V12__payment_backpressure.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V14__completed_task_history.sql"));
    jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO merchants(id, name, enabled, notification_url) VALUES ('merchant-a','演示商户 A',true,null)");
    jdbc.update(
        "INSERT INTO merchants(id, name, enabled, notification_url) VALUES ('merchant-b','演示商户 B',true,null)");
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    var ledger = new LedgerService(jdbc);
    var outbox = new OutboxService(jdbc, Clock.systemUTC());
    payments = new PaymentService(jdbc, transaction, ledger, outbox, Clock.systemUTC(), 16);
    refunds = new RefundService(jdbc, transaction, payments, ledger, outbox, Clock.systemUTC());
  }

  DriverManagerDataSource source() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "sa",
        "");
  }

  @AfterEach
  void closeDatabase() throws Exception {
    if (jdbc != null && schemaConnection.getMetaData().getURL().startsWith("jdbc:h2:")) {
      jdbc.execute("SHUTDOWN");
    }
    if (schemaConnection != null) {
      schemaConnection.close();
    }
  }

  PaymentService.PaymentView createPayment(String order, String amount) {
    return payments.create(
        "merchant-a", new PaymentService.CreatePayment(order, amount, "CNY", "MOCK_A", null));
  }

  PaymentService.PaymentView paid(String order, String amount) {
    var payment = createPayment(order, amount);
    return payments.applyResult(
        payment.id(),
        new ChannelResult.Success("MOCK_A", "channel-" + order, Long.parseLong(amount), "CNY"));
  }

  long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }
}
