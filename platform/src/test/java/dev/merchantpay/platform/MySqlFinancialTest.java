package dev.merchantpay.platform;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/** The same observable money invariants, on real InnoDB and concurrent connections. */
class MySqlFinancialTest extends FinancialCoreTest {
  @org.junit.jupiter.api.Test
  void logicalBackupRestoresCommittedMoneyAndIdempotency() throws Exception {
    var payment = paid("backup", "10000");
    var refund =
        refunds.create(
            "merchant-a",
            payment.id(),
            new dev.merchantpay.platform.refund.RefundService.CreateRefund(
                "backup-refund", "3000"));
    refunds.applyResult(
        refund.id(),
        new dev.merchantpay.platform.common.ChannelResult.Success(
            "MOCK_A", "backup-refund-receipt", 3000, "CNY"));
    var dump =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=test mysqldump --single-transaction --no-tablespaces --set-gtid-purged=OFF -utest financial_test");
    org.assertj.core.api.Assertions.assertThat(dump.getExitCode()).isZero();
    org.assertj.core.api.Assertions.assertThat(dump.getStdout())
        .contains("ledger_journals", "ledger_entries");
    jdbc.execute("DROP VIEW IF EXISTS outbox_task_records");
    for (var table :
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",
            String.class)) {
      if (!table.matches("[a-z_]+")) {
        throw new IllegalStateException("Unexpected test table");
      }
      jdbc.execute("DROP TABLE " + table);
    }
    MYSQL.copyFileToContainer(
        org.testcontainers.images.builder.Transferable.of(dump.getStdout()),
        "/tmp/project-backup.sql");
    var restored =
        MYSQL.execInContainer(
            "sh", "-c", "MYSQL_PWD=test mysql -utest financial_test < /tmp/project-backup.sql");
    org.assertj.core.api.Assertions.assertThat(restored.getExitCode()).isZero();
    org.assertj.core.api.Assertions.assertThat(
            payments.get("merchant-a", payment.id()).refundedMinor())
        .isEqualTo("3000");
    payments.applyResult(
        payment.id(),
        new dev.merchantpay.platform.common.ChannelResult.Success(
            "MOCK_A", "channel-backup", 10000, "CNY"));
    org.assertj.core.api.Assertions.assertThat(count("ledger_journals")).isEqualTo(2);
    org.assertj.core.api.Assertions.assertThat(count("ledger_entries")).isEqualTo(6);
  }

  static final MySQLContainer MYSQL =
      new MySQLContainer(MySqlTestImage.IMAGE)
          .withDatabaseName("financial_test")
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
    sql.execute("DROP VIEW IF EXISTS outbox_task_records");
    for (var table :
        sql.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",
            String.class)) {
      if (!table.matches("[a-z_]+")) {
        throw new IllegalStateException("Unexpected test table name");
      }
      sql.execute("DROP TABLE " + table);
    }
    return source;
  }
}
