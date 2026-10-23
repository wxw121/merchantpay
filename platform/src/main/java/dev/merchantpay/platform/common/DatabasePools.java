package dev.merchantpay.platform.common;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "merchantpay.database-isolation-enabled", havingValue = "true")
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabasePools {
  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource apiDataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean
  @org.springframework.boot.flyway.autoconfigure.FlywayDataSource
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource workerDataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean
  @Primary
  DataSource dataSource(
      @Qualifier("apiDataSource") HikariDataSource api,
      @Qualifier("workerDataSource") HikariDataSource worker,
      @Value("${merchantpay.database-api-connections:12}") int apiConnections) {
    int total = api.getMaximumPoolSize();
    if (apiConnections < 1 || total - apiConnections < 3) {
      throw new IllegalArgumentException(
          "Isolated pools require positive API capacity and at least three worker connections");
    }
    resize(api, apiConnections, "merchantpay-api");
    resize(worker, total - apiConnections, "merchantpay-worker");
    var routed =
        new AbstractRoutingDataSource() {
          @Override
          protected Object determineCurrentLookupKey() {
            return DatabaseRole.isWorker();
          }
        };
    routed.setTargetDataSources(Map.of(false, api, true, worker));
    routed.setDefaultTargetDataSource(api);
    routed.setLenientFallback(false);
    routed.afterPropertiesSet();
    return routed;
  }

  private static void resize(HikariDataSource source, int maximum, String name) {
    int minimum = source.getMinimumIdle();
    source.setMaximumPoolSize(maximum);
    source.setMinimumIdle(minimum < 0 ? maximum : Math.min(minimum, maximum));
    source.setPoolName(name);
  }
}
