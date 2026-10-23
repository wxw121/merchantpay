package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import dev.merchantpay.platform.common.DatabasePools;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabasePoolConfigurationTest {
  @ParameterizedTest
  @CsvSource({"4,2", "3,1", "32,31", "32,0"})
  void rejectsBudgetsWithoutHeadroomForTwoStreamingJobsAndTheirCheckpoints(int total, int api) {
    context(total, api)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalArgumentException.class);
            });
  }

  @org.junit.jupiter.api.Test
  void reservedPoolsKeepTheOriginalTotalConnectionBudget() {
    context(32, 12)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context.getBean("apiDataSource", HikariDataSource.class).getMaximumPoolSize())
                  .isEqualTo(12);
              assertThat(
                      context
                          .getBean("workerDataSource", HikariDataSource.class)
                          .getMaximumPoolSize())
                  .isEqualTo(20);
            });
  }

  private ApplicationContextRunner context(int total, int api) {
    return new ApplicationContextRunner()
        .withUserConfiguration(DatabasePools.class)
        .withPropertyValues(
            "merchantpay.database-isolation-enabled=true",
            "merchantpay.database-api-connections=" + api,
            "spring.datasource.url=jdbc:h2:mem:pool-config",
            "spring.datasource.username=sa",
            "spring.datasource.hikari.maximum-pool-size=" + total);
  }
}
