package dev.merchantpay.platform.sharding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "merchantpay.sharding.target-http-writes-enabled",
    havingValue = "true")
public class ShardTargetHttpConfiguration {
  @Bean
  ShardTargetHttpAdmission shardTargetHttpAdmission(ShardMoney money, JdbcTemplate core) {
    var admission = new ShardTargetHttpAdmission(core);
    money.setTargetHttpAdmission(admission);
    return admission;
  }
}
