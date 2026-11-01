package dev.merchantpay.platform;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

class MySqlEventDeliveryTest extends EventKafkaListenerTest {
  static final MySQLContainer MYSQL =
      new MySQLContainer(MySqlTestImage.IMAGE)
          .withDatabaseName("v2_events")
          .withUsername("test")
          .withPassword("test")
          .withCommand("--innodb-buffer-pool-size=134217728")
          .withCreateContainerCmdModifier(
              c -> c.getHostConfig().withNanoCPUs(1000000000L).withMemory(805306368L));

  @BeforeAll
  static void start() {
    MYSQL.start();
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @Override
  DriverManagerDataSource source() {
    var source =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
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
