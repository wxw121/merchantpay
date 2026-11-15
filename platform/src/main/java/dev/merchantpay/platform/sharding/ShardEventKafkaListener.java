package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.DatabaseRole;
import dev.merchantpay.platform.outbox.EventDelivery;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "merchantpay.sharding.cdc-enabled", havingValue = "true")
public class ShardEventKafkaListener {
  private final ShardEventDelivery shards;

  public ShardEventKafkaListener(ShardEventDelivery shards) {
    this.shards = shards;
  }

  @KafkaListener(
      topicPattern = "outbox\\.event\\.merchantpay(?:\\.s[0-3])?",
      groupId = EventDelivery.CONSUMER,
      containerFactory = "shardEventListenerFactory")
  public void onEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgement) {
    DatabaseRole.worker(
        () -> {
          try {
            shards.deliver(record.topic(), record.partition(), record.offset(), record.value());
          } catch (EventDelivery.InvalidEvent invalid) {
            shards.quarantine(
                record.topic(),
                record.partition(),
                record.offset(),
                record.value(),
                invalid.getMessage());
          }
          acknowledgement.acknowledge();
        });
  }
}
