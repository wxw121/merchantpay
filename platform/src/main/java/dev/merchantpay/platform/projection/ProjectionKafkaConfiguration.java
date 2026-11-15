package dev.merchantpay.platform.projection;

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
@ConditionalOnProperty(name = "merchantpay.read-model-consumer-enabled", havingValue = "true")
public class ProjectionKafkaConfiguration {
  private final ProjectionService projection;

  public ProjectionKafkaConfiguration(ProjectionService projection) {
    this.projection = projection;
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> projectionListenerFactory(
      @Value("${spring.kafka.bootstrap-servers:127.0.0.1:19092}") String brokers,
      EventDelivery events,
      org.springframework.core.env.Environment environment) {
    var configuration = new EventKafkaConfiguration();
    configuration.configure(environment);
    var factory = configuration.eventListenerFactory(brokers, events);
    var errors =
        new DefaultErrorHandler(
            (record, failure) ->
                projection.quarantine(
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
      topics = "${merchantpay.events-topic:outbox.event.merchantpay}",
      groupId = ProjectionService.GROUP,
      containerFactory = "projectionListenerFactory")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    DatabaseRole.worker(
        () -> {
          try {
            projection.consume(record.topic(), record.partition(), record.offset(), record.value());
          } catch (EventDelivery.InvalidEvent invalid) {
            projection.quarantine(
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
