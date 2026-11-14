package dev.merchantpay.simulator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;

class CallbackWorkerTest {
  private static final String SECRET = "callback-secret";
  private static final String CALLBACK_URL = "https://merchant.example/callback";
  private static final ChannelStore.Receipt RECEIPT =
      new ChannelStore.Receipt("p1", "PAYMENT", "MOCK_A", "SUCCEEDED", "ref-p1", "100", "CNY", "1");
  private final ChannelStore store = mock(ChannelStore.class);
  private final HttpClient client = mock(HttpClient.class);
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void signsAndAcknowledgesEachDeliveryInStandardMode() throws Exception {
    returnsPendingReceipt("standard-v1", 2);
    var accepted = response(204);
    when(client.send(any(), anyBodyHandler())).thenReturn(accepted);

    worker().deliver();

    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(2)).send(request.capture(), anyBodyHandler());
    assertThat(request.getAllValues())
        .allSatisfy(
            callback -> {
              assertThat(callback.uri()).isEqualTo(URI.create(CALLBACK_URL));
              assertThat(callback.method()).isEqualTo("POST");
              assertThat(callback.headers().firstValue("Content-Type"))
                  .contains("application/json");
              var timestamp = callback.headers().firstValue("X-Channel-Timestamp").orElseThrow();
              assertThat(timestamp).matches("[0-9]+");
              var body = body(callback);
              assertThat(callback.headers().firstValue("X-Channel-Signature"))
                  .contains(signature(timestamp, body));
              assertThat(json.readValue(body, ChannelStore.Receipt.class)).isEqualTo(RECEIPT);
            });
    verify(store, times(2)).notified(RECEIPT);
  }

  @Test
  void rejectedCallbackStaysPendingForTheNextPoll() throws Exception {
    returnsPendingReceipt("demo", 2);
    var rejected = response(503);
    var accepted = response(204);
    when(client.send(any(), anyBodyHandler())).thenReturn(rejected).thenReturn(accepted);
    var worker = worker();

    worker.deliver();

    verify(client).send(any(), anyBodyHandler());
    verify(store, never()).notified(RECEIPT);

    worker.deliver();

    verify(client, times(3)).send(any(), anyBodyHandler());
    verify(store, times(2)).notified(RECEIPT);
  }

  @Test
  void transportFailureLeavesTheReceiptPending() throws Exception {
    returnsPendingReceipt("demo", 1);
    when(client.send(any(), anyBodyHandler())).thenThrow(new IOException("unreachable"));

    worker().deliver();

    verify(client).send(any(), anyBodyHandler());
    verify(store, never()).notified(RECEIPT);
  }

  @Test
  void interruptionLeavesReceiptPendingAndPreservesThreadInterrupt() throws Exception {
    returnsPendingReceipt("demo", 1);
    when(client.send(any(), anyBodyHandler())).thenThrow(new InterruptedException());
    var worker = worker();

    try {
      worker.deliver();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    verify(client).send(any(), anyBodyHandler());
    verify(store, never()).notified(RECEIPT);
  }

  private void returnsPendingReceipt(String mode, int remaining) {
    when(store.profile()).thenReturn(new StandardProfile(mode, 20261019));
    when(store.pendingNotifications()).thenReturn(List.of(RECEIPT));
    when(store.remainingNotifications(RECEIPT)).thenReturn(remaining);
  }

  private CallbackWorker worker() {
    var builder = mock(HttpClient.Builder.class);
    when(builder.connectTimeout(Duration.ofSeconds(2))).thenReturn(builder);
    when(builder.build()).thenReturn(client);
    try (var factory = mockStatic(HttpClient.class)) {
      factory.when(HttpClient::newBuilder).thenReturn(builder);
      return new CallbackWorker(store, json, CALLBACK_URL, SECRET);
    }
  }

  private static HttpResponse<Object> response(int status) {
    @SuppressWarnings("unchecked")
    HttpResponse<Object> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    return response;
  }

  private static BodyHandler<Object> anyBodyHandler() {
    return ArgumentMatchers.any();
  }

  private static String body(HttpRequest request) throws Exception {
    var subscriber = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<ByteBuffer>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
              }

              @Override
              public void onNext(ByteBuffer bytes) {
                subscriber.onNext(List.of(bytes));
              }

              @Override
              public void onError(Throwable error) {
                subscriber.onError(error);
              }

              @Override
              public void onComplete() {
                subscriber.onComplete();
              }
            });
    return subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS);
  }

  private static String signature(String timestamp, String body) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
  }
}
