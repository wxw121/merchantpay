package dev.merchantpay.platform.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "merchantpay.events-consumer-enabled", havingValue = "true")
public class EventKafkaListener {
  private final EventDelivery delivery;

  public EventKafkaListener(EventDelivery delivery) {
    this.delivery = delivery;
  }

  @KafkaListener(
      topics = "${merchantpay.events-topic:outbox.event.merchantpay}",
      groupId = EventDelivery.CONSUMER,
      containerFactory = "eventListenerFactory")
  public void onEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgement) {
    try {
      delivery.deliver(record.topic(), record.partition(), record.offset(), record.value());
    } catch (EventDelivery.InvalidEvent invalid) {
      delivery.quarantine(
          record.topic(),
          record.partition(),
          record.offset(),
          record.value(),
          invalid.getMessage());
    }
    acknowledgement.acknowledge();
  }
}
