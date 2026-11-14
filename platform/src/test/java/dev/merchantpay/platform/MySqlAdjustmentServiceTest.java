package dev.merchantpay.platform;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MySqlAdjustmentServiceTest extends AdjustmentServiceTest {
  static final org.testcontainers.mysql.MySQLContainer MYSQL = MySqlFinanceDatabase.container();

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
    return MySqlFinanceDatabase.clean(MYSQL);
  }
}
