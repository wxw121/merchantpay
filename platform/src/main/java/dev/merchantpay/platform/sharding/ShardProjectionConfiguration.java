package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "merchantpay.sharding.projection-enabled", havingValue = "true")
public class ShardProjectionConfiguration {
  @Bean
  ShardProjectionService shardProjectionService(
      ShardConnections connections,
      ShardTransactions routes,
      Clock clock,
      JsonMapper json,
      EventDelivery events,
      ClickHouseReadModel clickhouse,
      @Value("${merchantpay.sharding.money-writes-enabled:false}") boolean money,
      @Value("${merchantpay.sharding.cdc-enabled:false}") boolean cdc,
      @Value("${merchantpay.read-model-enabled:false}") boolean readModel,
      @Value("${merchantpay.read-model-consumer-enabled:false}") boolean coreConsumer,
      @Value("${merchantpay.read-model-worker-enabled:false}") boolean coreWorker,
      @Value("${merchantpay.sharding.projection-consumer-enabled:false}") boolean consumer,
      @Value("${merchantpay.sharding.projection-worker-enabled:false}") boolean worker) {
    if (!money || !cdc || !readModel || coreConsumer || coreWorker)
      throw new IllegalArgumentException(
          "Shard projection requires sharded money, CDC and ClickHouse; core-only projection must be disabled");
    return new ShardProjectionService(
        connections.targets(), clock, json, events, clickhouse, !consumer && !worker, routes);
  }

  @Bean
  ShardProjectionPromotion shardProjectionPromotion(
      ShardConnections connections, ClickHouseReadModel clickhouse, JsonMapper json, Clock clock) {
    return new ShardProjectionPromotion(connections.targets(), clickhouse, json, clock);
  }

  @Bean
  ShardProjectionReadGate shardProjectionReadGate(ShardConnections connections) {
    return new ShardProjectionReadGate(connections.targets());
  }
}
