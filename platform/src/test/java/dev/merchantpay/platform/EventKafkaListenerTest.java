package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.outbox.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class EventKafkaListenerTest extends EventDeliveryTest {
  @Test
  void offsetIsAcknowledgedOnlyAfterDurableTaskAndReceiptCommit() {
    String body = emit("ack", "PAYMENT_SUCCEEDED");
    var listener = new EventKafkaListener(delivery);
    Acknowledgment ack = mock(Acknowledgment.class);
    doAnswer(
            invocation -> {
              assertThat(count("event_receipts")).isEqualTo(1);
              assertThat(count("outbox_tasks")).isEqualTo(1);
              return null;
            })
        .when(ack)
        .acknowledge();
    listener.onEvent(new ConsumerRecord<>("events", 0, 0, "p0_one", body), ack);
    verify(ack).acknowledge();
  }

  @Test
  void poisonIsCommittedToQuarantineBeforeAcknowledgement() {
    var listener = new EventKafkaListener(delivery);
    Acknowledgment ack = mock(Acknowledgment.class);
    doAnswer(
            invocation -> {
              assertThat(count("event_quarantine")).isEqualTo(1);
              return null;
            })
        .when(ack)
        .acknowledge();
    listener.onEvent(new ConsumerRecord<>("events", 0, 0, "key", "bad-json"), ack);
    verify(ack).acknowledge();
    assertThat(count("outbox_tasks")).isZero();
  }

  @Test
  void databaseFailureCannotAcknowledgeKafkaPosition() {
    EventDelivery failing = mock(EventDelivery.class);
    doThrow(new org.springframework.dao.TransientDataAccessResourceException("offline"))
        .when(failing)
        .deliver(anyString(), anyInt(), anyLong(), anyString());
    Acknowledgment ack = mock(Acknowledgment.class);
    assertThatThrownBy(
            () ->
                new EventKafkaListener(failing)
                    .onEvent(new ConsumerRecord<>("events", 0, 0, "key", "body"), ack))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    verifyNoInteractions(ack);
    verify(failing, never()).quarantine(anyString(), anyInt(), anyLong(), anyString(), anyString());
  }
}
