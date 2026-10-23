package dev.merchantpay.platform;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

class MySqlDurableTaskTest extends DurableTaskTest {
  static final MySQLContainer MYSQL =
      new MySQLContainer(MySqlTestImage.IMAGE)
          .withDatabaseName("queue_test")
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
}
