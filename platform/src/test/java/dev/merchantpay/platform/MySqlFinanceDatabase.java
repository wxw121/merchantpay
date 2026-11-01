package dev.merchantpay.platform;

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
    for (String table :
        sql.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",
            String.class)) {
      if (!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected test table");
      sql.execute("DROP TABLE " + table);
    }
    return source;
  }
}
