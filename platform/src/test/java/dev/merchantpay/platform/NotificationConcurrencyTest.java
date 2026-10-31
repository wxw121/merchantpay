package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import dev.merchantpay.platform.common.NotificationPolicy;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.orchestration.NotificationService;
import dev.merchantpay.platform.outbox.TaskQueue;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

class NotificationConcurrencyTest extends FinancialFixture {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 16})
  void allSixteenNotificationWorkersReachTheRealReceiverBeforeAnyResponse(int batchSize)
      throws Exception {
    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
        schemaConnection,
        new org.springframework.core.io.ClassPathResource("db/migration/V3__notifications.sql"));
    var entered = new CountDownLatch(16);
    var release = new CountDownLatch(1);
    var uncommittedAtReceiver = new java.util.concurrent.atomic.AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        var clients = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(serverThreads);
      server.createContext(
          "/webhook",
          exchange -> {
            var payload =
                JsonMapper.builder()
                    .build()
                    .readValue(exchange.getRequestBody().readAllBytes(), java.util.Map.class);
            if (jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notification_deliveries WHERE event_id=? AND status='SENDING'",
                    Integer.class,
                    payload.get("eventId"))
                != 1) uncommittedAtReceiver.incrementAndGet();
            entered.countDown();
            try {
              release.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
          });
      server.start();
      try {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
        var payment = paid("notification-pool", "100");
        jdbc.update("UPDATE payments SET notification_url=? WHERE id=?", url, payment.id());
        var merchants = mock(MerchantService.class);
        when(merchants.notificationKey("merchant-a"))
            .thenReturn(new MerchantService.Key("key", "merchant-a", "secret"));
        var notifications =
            new NotificationService(
                payments,
                refunds,
                merchants,
                new NotificationPolicy(url),
                JsonMapper.builder().build(),
                jdbc,
                Clock.systemUTC(),
                new dev.merchantpay.platform.orchestration.NotificationWrites(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        jdbc.getDataSource()),
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                    batchSize,
                    java.time.Duration.ofMillis(batchSize == 1 ? 0 : 10)));
        var pending = new ArrayList<Future<?>>();
        long observed;
        try {
          for (int i = 0; i < 16; i++) {
            String id = "notice-" + i;
            pending.add(
                clients.submit(
                    () ->
                        notifications.deliver(
                            new TaskQueue.Task(
                                id, id, "PAYMENT_SUCCEEDED", payment.id(), "merchant-a", 1, id))));
          }
          entered.await(1500, TimeUnit.MILLISECONDS);
          observed = 16 - entered.getCount();
        } finally {
          release.countDown();
        }
        for (var request : pending) request.get(10, TimeUnit.SECONDS);
        assertThat(observed)
            .as("actual concurrent webhook requests before releasing receiver")
            .isEqualTo(16);
        assertThat(uncommittedAtReceiver.get()).isZero();
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notification_deliveries WHERE status='DELIVERED'",
                    Long.class))
            .isEqualTo(16);
      } finally {
        release.countDown();
        server.stop(0);
      }
    }
  }
}
