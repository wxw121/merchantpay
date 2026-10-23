package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.refund.RefundService;
import dev.merchantpay.platform.web.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

class MySqlConsoleSnapshotTest extends FinancialFixture {
  static final MySQLContainer MYSQL =
      new MySQLContainer(MySqlTestImage.IMAGE)
          .withDatabaseName("snapshot_test")
          .withUsername("test")
          .withPassword("test");

  @BeforeAll
  static void startMySql() {
    MYSQL.start();
  }

  @AfterAll
  static void stopMySql() {
    MYSQL.stop();
  }

  @Override
  DriverManagerDataSource source() {
    var source =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    var sql = new JdbcTemplate(source);
    for (var table :
        sql.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()",
            String.class)) {
      if (!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected test table");
      sql.execute("DROP TABLE " + table);
    }
    return source;
  }

  @Test
  void detailNeverMixesOldRefundTotalsWithNewLedgerEntries() throws Exception {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V2__security_and_evidence.sql"));
    var payment = paid("snapshot-page", "10000");
    var refund =
        refunds.create(
            "merchant-a", payment.id(), new RefundService.CreateRefund("snapshot-r", "3000"));
    var read = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var pausing =
        new PaymentService(jdbc, tx, null, null, Clock.systemUTC(), 16) {
          @Override
          public PaymentView get(String merchant, String id) {
            var value = payments.get(merchant, id);
            read.countDown();
            try {
              if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("Not resumed");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError(interrupted);
            }
            return value;
          }
        };
    var controller =
        new ConsoleController(
            new Access(),
            new Queries(jdbc, Clock.systemUTC(), "test"),
            pausing,
            refunds,
            null,
            null,
            jdbc,
            tx,
            Clock.systemUTC());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var detail =
          executor.submit(
              () ->
                  ScopedValue.where(
                          Access.CURRENT,
                          new Access.Caller("test", "merchant-a", Set.of("MERCHANT")))
                      .call(() -> controller.detail(payment.id(), null)));
      try {
        assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
        refunds.applyResult(
            refund.id(), new ChannelResult.Success("MOCK_A", "snapshot-refund", 3000, "CNY"));
        resume.countDown();
        var result = detail.get(10, TimeUnit.SECONDS);
        assertThat(((PaymentService.PaymentView) result.get("payment")).refundedMinor())
            .isEqualTo("0");
        assertThat((List<?>) result.get("entries")).hasSize(3);
        assertThat((List<?>) result.get("journals")).hasSize(1);
      } finally {
        resume.countDown();
      }
    }
  }
}
