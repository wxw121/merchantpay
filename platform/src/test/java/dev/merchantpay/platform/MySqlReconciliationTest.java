package dev.merchantpay.platform;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

class MySqlReconciliationTest extends ReconciliationTest {
  static final MySQLContainer MYSQL =
      new MySQLContainer(MySqlTestImage.IMAGE)
          .withDatabaseName("recon_test")
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
      if (!table.matches("[a-z_]+")) {
        throw new IllegalStateException("Unexpected test table");
      }
      sql.execute("DROP TABLE " + table);
    }
    return source;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"20000,false", "40000,false", "20000,true"})
  void denseDuplicateMatchingDoesNotRescanEveryGroupForEachReturnedRow(
      int groupSize, boolean separatedGroup) throws Exception {
    int total = separatedGroup ? groupSize * 2 : groupSize;
    for (int first = 1; first <= total; first += 1000) {
      var args = new java.util.ArrayList<Object>();
      for (int offset = 0; offset < 1000; offset++) {
        int number = first + offset;
        args.add(number);
        args.add(
            separatedGroup && (number == 1 || number > groupSize)
                ? "tail-group"
                : "same-aggregate");
      }
      jdbc.update(
          "INSERT INTO recon_rows(batch_id,line_no,operation,aggregate_id,status,amount_minor,fee_minor,currency,reference,raw_line) VALUES "
              + String.join(
                  ",",
                  java.util.Collections.nCopies(
                      1000, "('cost-probe',?,'PAYMENT',?,'SUCCEEDED',100,1,'CNY','ref','row')")),
          args.toArray());
    }
    var plan = new java.util.concurrent.atomic.AtomicReference<String>();
    class PlanCaptured extends RuntimeException {}
    var observed =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public java.util.List<java.util.Map<String, Object>> queryForList(
              String sql, Object... args) {
            if (sql.startsWith("SELECT r.*")) {
              plan.set(super.queryForObject("EXPLAIN ANALYZE " + sql, String.class, args));
              throw new PlanCaptured();
            }
            return super.queryForList(sql, args);
          }
        };
    var service =
        new dev.merchantpay.platform.reconciliation.ReconciliationService(
            observed,
            new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    jdbc.getDataSource())),
            null,
            java.time.Clock.systemUTC(),
            directory.toString(),
            queue);
    var match =
        service
            .getClass()
            .getDeclaredMethod(
                "match",
                String.class,
                java.util.Map.class,
                dev.merchantpay.platform.outbox.TaskQueue.Task.class);
    match.setAccessible(true);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> match.invoke(service, "cost-probe", java.util.Map.of(), null))
        .hasCauseInstanceOf(PlanCaptured.class);
    String explanation = plan.get();
    org.assertj.core.api.Assertions.assertThat(explanation).isNotBlank();
    org.assertj.core.api.Assertions.assertThat(explanation).contains("ix_recon_match");
    double inspectedRows = 0;
    boolean indexObserved = false;
    for (String line : explanation.split("\n")) {
      if (!line.toLowerCase(java.util.Locale.ROOT).contains("index lookup on d ")) continue;
      var metrics =
          java.util.regex.Pattern.compile("actual time=.*?rows=([0-9.eE+-]+) loops=([0-9]+)")
              .matcher(line);
      org.assertj.core.api.Assertions.assertThat(metrics.find()).isTrue();
      inspectedRows += Double.parseDouble(metrics.group(1)) * Long.parseLong(metrics.group(2));
      indexObserved = true;
    }
    System.out.println("Duplicate detection execution plan:\n" + explanation);
    org.assertj.core.api.Assertions.assertThat(indexObserved).isTrue();
    // 500 returned rows should each inspect at most the current row and one other record.
    org.assertj.core.api.Assertions.assertThat(inspectedRows).isLessThanOrEqualTo(1500);
  }
}
