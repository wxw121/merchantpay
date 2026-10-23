package dev.merchantpay.simulator;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CallbackWorker {
  private final ChannelStore store;
  private final JsonMapper json;
  private final String url;
  private final String secret;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public CallbackWorker(
      ChannelStore store,
      JsonMapper json,
      @Value("${merchantpay.callback-url}") String url,
      @Value("${merchantpay.callback-secret}") String secret) {
    this.store = store;
    this.json = json;
    this.url = url;
    this.secret = secret;
  }

  @Scheduled(
      fixedDelayString = "#{'${merchantpay.standard-profile:demo}' == 'standard-v1' ? 25 : 1000}")
  void deliver() {
    if (store.profile().enabled()) {
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        for (var receipt : store.pendingNotifications()) executor.submit(() -> deliver(receipt));
      }
      return;
    }
    for (var receipt : store.pendingNotifications()) {
      deliver(receipt);
    }
  }

  private void deliver(ChannelStore.Receipt receipt) {
    for (int remaining = store.remainingNotifications(receipt); remaining > 0; remaining--) {
      try {
        String raw = json.writeValueAsString(receipt),
            timestamp = Long.toString(Instant.now().getEpochSecond());
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature =
            HexFormat.of()
                .formatHex(mac.doFinal((timestamp + "\n" + raw).getBytes(StandardCharsets.UTF_8)));
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Channel-Timestamp", timestamp)
                    .header("X-Channel-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(raw))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 == 2) {
          store.notified(receipt);
        } else {
          return;
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception failure) {
        /* The durable receipt remains unacknowledged for the next polling cycle. */
        return;
      }
    }
  }
}
