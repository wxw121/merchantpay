package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.sharding.*;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardConnectionsTest {
  private static final String CORE = "jdbc:mysql://dbhost/core?connectTimeout=1000";

  private ShardSettings settings() {
    var settings = new ShardSettings();
    var targets = new LinkedHashMap<Integer, ShardSettings.Target>();
    for (int id = 1; id < 4; id++)
      targets.put(id, new ShardSettings.Target("jdbc:mysql://dbhost/shard_" + id, "app", "secret"));
    settings.setTargets(targets);
    return settings;
  }

  @Test
  void additionalPoolsAreLazyBoundedAndDoNotOwnTheCorePool() {
    var core = new DriverManagerDataSource(CORE, "app", "secret");
    try (var pools = ShardConnections.configured(core, CORE, settings())) {
      assertThat(pools.targets()).hasSize(4).containsEntry(0, core);
      for (int id = 1; id < 4; id++) {
        var target = (com.zaxxer.hikari.HikariDataSource) pools.targets().get(id);
        assertThat(target.getMinimumIdle()).isZero();
        assertThat(target.getMaximumPoolSize()).isEqualTo(2);
        assertThat(target.getHikariPoolMXBean()).isNull();
      }
      assertThatThrownBy(() -> pools.targets().clear())
          .isInstanceOf(UnsupportedOperationException.class);
    }
    assertThat(core.getUrl()).isEqualTo(CORE);
  }

  @Test
  void validationRejectsMissingExtraAliasedOrInvalidTargetsWithoutLeakingSecrets() {
    for (int variant = 0; variant < 10; variant++) {
      var settings = settings();
      switch (variant) {
        case 0 -> settings.getTargets().remove(3);
        case 1 -> settings.getTargets().put(0, settings.getTargets().get(1));
        case 2 -> settings.getTargets().put(4, settings.getTargets().get(1));
        case 3 ->
            settings
                .getTargets()
                .put(
                    1,
                    new ShardSettings.Target(
                        "jdbc:mysql://DBHOST:3306/core?password=hidden", "app", "secret"));
        case 4 ->
            settings
                .getTargets()
                .put(
                    2,
                    new ShardSettings.Target(
                        "jdbc:mysql://DBHOST:3306/shard_1?x=y", "app", "secret"));
        case 5 -> settings.setConnectionsPerShard(17);
        case 6 ->
            settings
                .getTargets()
                .put(
                    1,
                    new ShardSettings.Target(
                        "jdbc:mysql://app:secret@dbhost/shard_1", "app", "secret"));
        case 7 ->
            settings
                .getTargets()
                .put(1, new ShardSettings.Target("jdbc:mysql://dbhost/shard_1", "", "secret"));
        case 8 ->
            settings
                .getTargets()
                .put(1, new ShardSettings.Target("jdbc:mysql://dbhost/shard_1", "app", " "));
        case 9 ->
            settings
                .getTargets()
                .put(2, new ShardSettings.Target("jdbc:mysql://dbhost/SHARD_1", "app", "secret"));
      }
      assertThatThrownBy(
              () -> ShardConnections.configured(new DriverManagerDataSource(CORE), CORE, settings))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("secret")
          .hasMessageNotContaining("hidden");
    }
  }

  @Test
  void defaultOffDoesNotRegisterTargetPoolsOrReplaceThePrimaryDatasource() {
    var core = new DriverManagerDataSource();
    new ApplicationContextRunner()
        .withBean("dataSource", javax.sql.DataSource.class, () -> core)
        .withUserConfiguration(ShardConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(javax.sql.DataSource.class);
              assertThat(context.getBean(javax.sql.DataSource.class)).isSameAs(core);
              assertThat(context)
                  .doesNotHaveBean(ShardConnections.class)
                  .doesNotHaveBean(ShardReads.class);
            });
  }

  @Test
  void enabledConfigurationBindsAllTargetsWithoutConnectingOrReplacingCoreBeans() {
    var core = new DriverManagerDataSource(CORE, "app", "secret");
    var properties = new java.util.ArrayList<String>();
    properties.add("merchantpay.sharding.enabled=true");
    properties.add("spring.datasource.url=" + CORE);
    for (int id = 1; id < 4; id++) {
      String prefix = "merchantpay.sharding.targets[" + id + "].";
      properties.add(prefix + "url=jdbc:mysql://dbhost/shard_" + id);
      properties.add(prefix + "username=app");
      properties.add(prefix + "password=secret");
    }
    new ApplicationContextRunner()
        .withBean("dataSource", javax.sql.DataSource.class, () -> core)
        .withUserConfiguration(ShardConfiguration.class)
        .withPropertyValues(properties.toArray(String[]::new))
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(javax.sql.DataSource.class)
                  .hasSingleBean(ShardReads.class);
              assertThat(context.getBean(javax.sql.DataSource.class)).isSameAs(core);
              assertThat(context)
                  .doesNotHaveBean(
                      org.springframework.transaction.PlatformTransactionManager.class);
              var target =
                  (com.zaxxer.hikari.HikariDataSource)
                      context.getBean(ShardConnections.class).targets().get(1);
              assertThat(target.getJdbcUrl()).isEqualTo("jdbc:mysql://dbhost/shard_1");
              assertThat(target.getHikariPoolMXBean()).isNull();
            });
  }

  @Test
  void effectiveCorePoolOverridesCannotBypassDatabaseAliasValidation() {
    try (var core = new com.zaxxer.hikari.HikariDataSource()) {
      core.setJdbcUrl("jdbc:mysql://dbhost/shard_1");
      assertThatThrownBy(() -> ShardConnections.configured(core, CORE, settings()))
          .isInstanceOf(IllegalArgumentException.class);
      var routed =
          new org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
              return false;
            }
          };
      routed.setTargetDataSources(
          java.util.Map.of(false, new DriverManagerDataSource(CORE), true, core));
      routed.afterPropertiesSet();
      assertThatThrownBy(() -> ShardConnections.configured(routed, CORE, settings()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(core.getHikariPoolMXBean()).isNull();
    }
  }
}
