package dev.merchantpay.platform.sharding;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
@EnableConfigurationProperties({ShardSettings.class, DataSourceProperties.class})
public class ShardConfiguration {
  @Bean(destroyMethod = "close")
  ShardConnections shardConnections(
      DataSource core, DataSourceProperties properties, ShardSettings settings) {
    return ShardConnections.configured(core, properties.getUrl(), settings);
  }

  @Bean
  ShardTransactions shardTransactions(DataSource core, ShardConnections connections) {
    return new ShardTransactions(new JdbcTemplate(core), connections.targets());
  }

  @Bean
  ShardReads shardReads(ShardTransactions transactions) {
    return new ShardReads(transactions);
  }

  @Bean
  ShardExportReadGate shardExportReadGate(ShardConnections connections) {
    return new ShardExportReadGate(connections.targets());
  }
}
