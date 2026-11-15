package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.DatabaseRole;
import dev.merchantpay.platform.outbox.EventDelivery;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.support.Acknowledgment;

class ShardEventKafkaListenerTest {
  private static final String TOPIC = "outbox.event.merchantpay.s2";
  private final ShardEventDelivery delivery = mock(ShardEventDelivery.class);
  private final Acknowledgment ack = mock(Acknowledgment.class);
  private final ShardEventKafkaListener listener = new ShardEventKafkaListener(delivery);
  private final ConsumerRecord<String, String> record =
      new ConsumerRecord<>(TOPIC, 1, 42, null, "payload");

  @Test
  void commitsTheKafkaOffsetOnlyAfterDeliveryUnderTheWorkerRole() {
    doAnswer(
            call -> {
              assertThat(DatabaseRole.isWorker()).isTrue();
              return null;
            })
        .when(delivery)
        .deliver(TOPIC, 1, 42, "payload");

    listener.onEvent(record, ack);

    var order = inOrder(delivery, ack);
    order.verify(delivery).deliver(TOPIC, 1, 42, "payload");
    order.verify(ack).acknowledge();
    verifyNoMoreInteractions(delivery, ack);
    assertThat(DatabaseRole.isWorker()).isFalse();
  }

  @Test
  void quarantinesAnInvalidEventBeforeAcknowledging() {
    doThrow(new EventDelivery.InvalidEvent("wrong source"))
        .when(delivery)
        .deliver(TOPIC, 1, 42, "payload");

    listener.onEvent(record, ack);

    var order = inOrder(delivery, ack);
    order.verify(delivery).deliver(TOPIC, 1, 42, "payload");
    order.verify(delivery).quarantine(TOPIC, 1, 42, "payload", "wrong source");
    order.verify(ack).acknowledge();
  }

  @Test
  void leavesTheOffsetUncommittedWhenQuarantineCannotBePersisted() {
    doThrow(new EventDelivery.InvalidEvent("wrong source"))
        .when(delivery)
        .deliver(TOPIC, 1, 42, "payload");
    var unavailable = new TransientDataAccessResourceException("database unavailable");
    when(delivery.quarantine(TOPIC, 1, 42, "payload", "wrong source")).thenThrow(unavailable);

    assertThatThrownBy(() -> listener.onEvent(record, ack)).isSameAs(unavailable);
    verifyNoInteractions(ack);
    assertThat(DatabaseRole.isWorker()).isFalse();
  }

  @Test
  void letsKafkaRetryTransientDeliveryErrorsWithoutQuarantining() {
    var unavailable = new TransientDataAccessResourceException("database unavailable");
    doThrow(unavailable).when(delivery).deliver(TOPIC, 1, 42, "payload");

    assertThatThrownBy(() -> listener.onEvent(record, ack)).isSameAs(unavailable);
    verify(delivery).deliver(TOPIC, 1, 42, "payload");
    verifyNoMoreInteractions(delivery);
    verifyNoInteractions(ack);
  }
}
