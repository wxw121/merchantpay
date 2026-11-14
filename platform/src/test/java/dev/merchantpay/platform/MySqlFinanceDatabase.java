package dev.merchantpay.platform;

import java.sql.SQLException;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

final class MySqlFinanceDatabase {
  static MySQLContainer container() {
    return new MySQLContainer(MySqlTestImage.IMAGE)
        .withDatabaseName("v2_finance")
        .withUsername("test")
        .withPassword("test")
        .withCommand("--innodb-buffer-pool-size=134217728")
        .withCreateContainerCmdModifier(
            c -> c.getHostConfig().withNanoCPUs(1000000000L).withMemory(805306368L));
  }

  static DriverManagerDataSource clean(MySQLContainer db) {
    var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    var sql = new JdbcTemplate(source);
    sql.execute("DROP VIEW IF EXISTS outbox_task_records");
    // DDL commits implicitly in MySQL; disable FK checks only on the cleanup connection.
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      var tables = new ArrayList<String>();
      try (var rows =
          statement.executeQuery(
              "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")) {
        while (rows.next()) {
          var table = rows.getString(1);
          if (!table.matches("[a-z_][a-z0-9_]*"))
            throw new IllegalStateException("Unexpected test table");
          tables.add(table);
        }
      }
      statement.execute("SET FOREIGN_KEY_CHECKS=0");
      try {
        for (var table : tables) statement.execute("DROP TABLE " + table);
      } finally {
        statement.execute("SET FOREIGN_KEY_CHECKS=1");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Could not clean MySQL test schema", failure);
    }
    return source;
  }
}
