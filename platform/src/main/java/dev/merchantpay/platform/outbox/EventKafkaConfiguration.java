package dev.merchantpay.platform.outbox;

import java.util.HashMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(name = "merchantpay.events-consumer-enabled", havingValue = "true")
public class EventKafkaConfiguration {
  private java.util.Map<String, String> transportProperties = java.util.Map.of();

  @org.springframework.beans.factory.annotation.Autowired
  public void configure(org.springframework.core.env.Environment environment) {
    transportProperties =
        org.springframework.boot.context.properties.bind.Binder.get(environment)
            .bind(
                "spring.kafka.properties",
                org.springframework.boot.context.properties.bind.Bindable.mapOf(
                    String.class, String.class))
            .orElse(java.util.Map.of());
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> eventListenerFactory(
      @Value("${spring.kafka.bootstrap-servers:127.0.0.1:19092}") String brokers,
      EventDelivery delivery) {
    var properties = new HashMap<String, Object>(transportProperties);
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 32);
    properties.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 1048576);
    properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));
    factory.setConcurrency(1);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setSyncCommits(true);
    var errors =
        new DefaultErrorHandler(
            (record, failure) ->
                delivery.quarantine(
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
