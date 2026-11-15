package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.DatabaseRole;
import dev.merchantpay.platform.outbox.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(
    name = "merchantpay.sharding.projection-consumer-enabled",
    havingValue = "true")
public class ShardProjectionKafkaConfiguration {
  private final ShardProjectionService shards;

  public ShardProjectionKafkaConfiguration(ShardProjectionService shards) {
    this.shards = shards;
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> shardProjectionListenerFactory(
      @Value("${spring.kafka.bootstrap-servers:127.0.0.1:19092}") String brokers,
      EventDelivery prototype,
      org.springframework.core.env.Environment environment) {
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

  @KafkaListener(
      topicPattern = "outbox\\.event\\.merchantpay(?:\\.s[0-3])?",
      groupId = ShardProjectionService.GROUP,
      containerFactory = "shardProjectionListenerFactory")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    DatabaseRole.worker(
        () -> {
          try {
            shards.consume(record.topic(), record.partition(), record.offset(), record.value());
          } catch (EventDelivery.InvalidEvent invalid) {
            shards.quarantine(
                record.topic(),
                record.partition(),
                record.offset(),
                record.value(),
                invalid.getMessage());
          }
          acknowledgment.acknowledge();
        });
  }
}
