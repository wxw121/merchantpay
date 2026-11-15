package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.outbox.EventKafkaConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "merchantpay.sharding.cdc-enabled", havingValue = "true")
public class ShardEventKafkaConfiguration {
  @Bean
  ShardEventDelivery shardEventDelivery(
      ShardTransactions routes, ShardConnections sources, EventDelivery prototype) {
    return new ShardEventDelivery(routes, sources.targets(), prototype);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> shardEventListenerFactory(
      @Value("${spring.kafka.bootstrap-servers:127.0.0.1:19092}") String brokers,
      @Value("${merchantpay.event-transport:POLLING}") String transport,
      @Value("${merchantpay.events-consumer-enabled:false}") boolean consumer,
      @Value("${merchantpay.sharding.channel-workers-enabled:false}") boolean workers,
      @Value("${merchantpay.read-model-consumer-enabled:false}") boolean projectionConsumer,
      @Value("${merchantpay.read-model-worker-enabled:false}") boolean projectionWorker,
      EventDelivery prototype,
      ShardEventDelivery shards,
      org.springframework.core.env.Environment environment) {
    if (!"CDC".equalsIgnoreCase(transport)
        || !consumer
        || !workers
        || projectionConsumer
        || projectionWorker)
      throw new IllegalArgumentException(
          "Shard CDC requires CDC transport, event consumer and shard workers; core-only projection components must be disabled");
    var base = new EventKafkaConfiguration();
    base.configure(environment);
    var factory = base.eventListenerFactory(brokers, prototype);
    var errors =
        new DefaultErrorHandler(
            (record, failure) ->
                shards.quarantine(
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    java.util.Objects.toString(record.value(), ""),
                    failure.getClass().getSimpleName()),
            new FixedBackOff(1000, 2));
    errors.setCommitRecovered(true);
    errors.setAckAfterHandle(true);
    errors.setResetStateOnRecoveryFailure(true);
    factory.setCommonErrorHandler(errors);
    return factory;
  }
}
