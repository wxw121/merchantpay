package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.orchestration.ShardChannelConfiguration;
import dev.merchantpay.platform.orchestration.ShardChannels;
import dev.merchantpay.platform.sharding.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ShardChannelConfigurationTest {
  @Test
  void receiptReservationIsLazyAndKeepsTheOriginalConnectionBudget() {
    try (var core = new com.zaxxer.hikari.HikariDataSource()) {
      core.setJdbcUrl("jdbc:mysql://dbhost/core");
      core.setUsername("app");
      core.setPassword("secret");
      core.setMaximumPoolSize(8);
      core.setMinimumIdle(0);
      try (var receipts = new ReceiptDirectory(core, java.time.Clock.systemUTC(), 2)) {
        assertThat(core.getMaximumPoolSize()).isEqualTo(6);
        assertThat(core.getHikariPoolMXBean()).isNull();
      }
      assertThat(core.getMaximumPoolSize()).isEqualTo(8);
      assertThatThrownBy(() -> new ReceiptDirectory(core, java.time.Clock.systemUTC(), 6))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void unsetMinimumIdleIsNormalizedBeforeReservationAndRestoration() {
    try (var core = new com.zaxxer.hikari.HikariDataSource()) {
      core.setJdbcUrl("jdbc:mysql://dbhost/core");
      core.setUsername("app");
      core.setPassword("secret");
      core.setMaximumPoolSize(8);
      try (var receipts = new ReceiptDirectory(core, java.time.Clock.systemUTC(), 2)) {
        assertThat(core.getMinimumIdle()).isEqualTo(6);
        assertThat(core.getMaximumPoolSize()).isEqualTo(6);
        assertThat(core.getHikariPoolMXBean()).isNull();
      }
      assertThat(core.getMinimumIdle()).isEqualTo(8);
      assertThat(core.getMaximumPoolSize()).isEqualTo(8);
    }
  }

  @Test
  void defaultOffCreatesNoDispatchersAndEnablingWithoutShardingFailsClosed() {
    new ApplicationContextRunner()
        .withUserConfiguration(ShardChannelConfiguration.class)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(ShardChannels.class)
                    .doesNotHaveBean(ReceiptDirectory.class));
    new ApplicationContextRunner()
        .withUserConfiguration(ShardChannelConfiguration.class)
        .withPropertyValues("merchantpay.sharding.channel-workers-enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }
}
