package dev.merchantpay.simulator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ChannelControllerTest {
  private static final String TOKEN = "channel-secret";
  private static final String CHANNEL = "MOCK_A";
  private static final Instant CREATED = Instant.parse("2026-10-17T16:00:00Z");
  private final ChannelStore store = mock(ChannelStore.class);
  private final ChannelController controller = new ChannelController(store, TOKEN);

  @Test
  void rejectsWritesBeforeAccessingTheStore() {
    assertThatExceptionOfType(ResponseStatusException.class)
        .isThrownBy(() -> controller.pay(CHANNEL, "wrong", request("p1", "order-1")))
        .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(store);
  }

  @Test
  void acceptedPaymentCanBeQueriedOrClosedById() {
    var input = request("p1", "order-1");
    var payment = receipt("p1", "PAYMENT", "PENDING", "3900", "0");
    var closed = receipt("p1", "PAYMENT", "CLOSED", "3900", "0");
    var health = new ChannelStore.Health(CHANNEL, 0, 1, "");
    when(store.profile()).thenReturn(new StandardProfile("demo", 20261019));
    when(store.pay(CHANNEL, input)).thenReturn(payment);
    when(store.query(CHANNEL, "PAYMENT", "p1")).thenReturn(payment);
    when(store.close(CHANNEL, "p1")).thenReturn(closed);
    when(store.health(CHANNEL, "merchant-a")).thenReturn(health);

    assertThat(controller.pay(CHANNEL, TOKEN, input)).isEqualTo(payment);
    assertThat(controller.health(CHANNEL, "merchant-a", TOKEN)).isEqualTo(health);
    assertThat(controller.query(CHANNEL, "PAYMENT", "p1", TOKEN)).isEqualTo(payment);
    assertThat(controller.close(CHANNEL, "p1", TOKEN)).isEqualTo(closed);
    verify(store, never()).consumeLostResponse(anyString(), anyString(), anyString());
  }

  @Test
  void acceptedRefundLosesItsFirstResponseAndReturnsTheReceiptOnRetry() {
    var input = request("r1", "lost:refund-1");
    var refund = receipt("r1", "REFUND", "SUCCEEDED", "3900", "23");
    when(store.profile()).thenReturn(new StandardProfile("demo", 20261019));
    when(store.refund(CHANNEL, input)).thenReturn(refund);
    when(store.consumeLostResponse(CHANNEL, "REFUND", "r1")).thenReturn(true, false);

    assertThatExceptionOfType(ResponseStatusException.class)
        .isThrownBy(() -> controller.refund(CHANNEL, TOKEN, input))
        .satisfies(
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    assertThat(controller.refund(CHANNEL, TOKEN, input)).isEqualTo(refund);
    var calls = inOrder(store);
    calls.verify(store).refund(CHANNEL, input);
    calls.verify(store).consumeLostResponse(CHANNEL, "REFUND", "r1");
    calls.verify(store).refund(CHANNEL, input);
    calls.verify(store).consumeLostResponse(CHANNEL, "REFUND", "r1");
  }

  @Test
  void formatsBillRowsAndTotalsForTheShanghaiBusinessDay() {
    var until = Instant.parse("2026-10-18T16:00:00Z");
    when(store.bill(CHANNEL, "merchant-a", CREATED, until))
        .thenReturn(
            List.of(
                receipt("p1", "PAYMENT", "SUCCEEDED", "5000", "30"),
                receipt("r1", "REFUND", "SUCCEEDED", "3900", "23")));

    assertThat(controller.bill(CHANNEL, "merchant-a", LocalDate.of(2026, 10, 18), TOKEN))
        .isEqualTo(
            """
            #merchantpay-bill-v1,MOCK_A,merchant-a,2026-10-18,2,8900
            operation,aggregateId,status,amountMinor,feeMinor,currency,reference
            PAYMENT,p1,SUCCEEDED,5000,30,CNY,reference-p1
            REFUND,r1,SUCCEEDED,3900,23,CNY,reference-r1
            """);
  }

  private static ChannelStore.Request request(String id, String businessNo) {
    return new ChannelStore.Request(id, "p1", "merchant-a", businessNo, "3900", "CNY", CREATED);
  }

  private static ChannelStore.Receipt receipt(
      String id, String operation, String status, String amount, String fee) {
    return new ChannelStore.Receipt(
        id, operation, CHANNEL, status, "reference-" + id, amount, "CNY", fee);
  }
}
