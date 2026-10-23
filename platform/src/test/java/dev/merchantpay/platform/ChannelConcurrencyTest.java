package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.merchantpay.platform.channel.ChannelApi;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:channel-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "merchantpay.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "merchantpay.channel-token=test",
      "merchantpay.callback-secret=test",
      "merchantpay.workers-enabled=false",
      "merchantpay.metrics-password=test-metrics",
      "merchantpay.channel-http-connections-per-route=12",
      "spring.http.serviceclient.mock-channel.read-timeout=8s",
      "spring.security.oauth2.client.registration.keycloak.client-secret=test"
    })
@Import(SecurityIntegrationTest.TestOidc.class)
class ChannelConcurrencyTest {
  static final CountDownLatch ENTERED = new CountDownLatch(16);
  static final CountDownLatch RELEASE = new CountDownLatch(1);
  static final ExecutorService SERVER_THREADS = Executors.newVirtualThreadPerTaskExecutor();
  static final HttpServer SERVER = server();

  static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(SERVER_THREADS);
      server.createContext(
          "/channels/MOCK_A/health",
          exchange -> {
            ENTERED.countDown();
            try {
              RELEASE.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
            }
            byte[] body =
                "{\"channel\":\"MOCK_A\",\"pendingCallbacks\":0,\"pendingPayments\":0,\"oldestCallbackAt\":\"\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      server.start();
      return server;
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  @DynamicPropertySource
  static void endpoint(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.http.serviceclient.mock-channel.base-url",
        () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    RELEASE.countDown();
    SERVER.stop(0);
    SERVER_THREADS.close();
  }

  @Autowired ChannelApi api;

  @Test
  void configuredChannelClientLetsTwelveRequestsReachTheServerBeforeAnyResponse() throws Exception {
    int enteredBeforeRelease;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var requests = new ArrayList<Future<ChannelApi.Health>>();
      try {
        for (int index = 0; index < 16; index++)
          requests.add(executor.submit(() -> api.health("MOCK_A", null, "test")));
        ENTERED.await(1, TimeUnit.SECONDS);
        enteredBeforeRelease = 16 - (int) ENTERED.getCount();
      } finally {
        RELEASE.countDown();
      }
      for (var request : requests)
        assertThat(request.get(10, TimeUnit.SECONDS).channel()).isEqualTo("MOCK_A");
    }
    assertThat(enteredBeforeRelease)
        .as("the real generated channel client must honor the configured concurrency")
        .isEqualTo(12);
  }
}
