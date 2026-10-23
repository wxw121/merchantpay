package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.zaxxer.hikari.HikariDataSource;
import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.orchestration.*;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.payment.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:database-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.hikari.maximum-pool-size=4",
      "merchantpay.database-isolation-enabled=true",
      "merchantpay.database-api-connections=1",
      "merchantpay.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "merchantpay.channel-token=test",
      "merchantpay.callback-secret=test",
      "merchantpay.workers-enabled=false",
      "merchantpay.metrics-password=test-metrics",
      "spring.security.oauth2.client.registration.keycloak.client-secret=test"
    })
@AutoConfigureMockMvc
@Import(SecurityIntegrationTest.TestOidc.class)
class DatabaseIsolationTest {
  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired PaymentService payments;
  @Autowired TaskQueue queue;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  PaymentService.PaymentView payment() {
    jdbc.update(
        "INSERT INTO merchants(id,name,enabled) VALUES('pool-test','pool-test',TRUE) ON DUPLICATE KEY UPDATE enabled=TRUE");
    return payments.create(
        "pool-test",
        new PaymentService.CreatePayment(
            UUID.randomUUID().toString(), "10000", "CNY", "MOCK_A", null));
  }

  HikariDataSource apiPool() {
    assertThat(context.getBeansOfType(HikariDataSource.class))
        .as("independently reserved API and worker pools")
        .hasSize(2);
    var api = context.getBean("apiDataSource", HikariDataSource.class);
    var worker = context.getBean("workerDataSource", HikariDataSource.class);
    assertThat(api.getMaximumPoolSize()).isEqualTo(1);
    assertThat(worker.getMaximumPoolSize()).isEqualTo(3);
    return api;
  }

  @Test
  void anExistingTransactionKeepsItsConnectionWhenTheLexicalRoleChanges() {
    apiPool();
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> {
          jdbc.update("INSERT INTO merchants(id,name,enabled) VALUES('role-outer','outer',TRUE)");
          dev.merchantpay.platform.common.DatabaseRole.worker(
              () -> {
                assertThat(
                        jdbc.update(
                            "UPDATE merchants SET name='same-transaction' WHERE id='role-outer'"))
                    .isEqualTo(1);
                jdbc.update(
                    "INSERT INTO merchants(id,name,enabled) VALUES('role-inner','inner',TRUE)");
              });
          status.setRollbackOnly();
        });
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchants WHERE id IN ('role-outer','role-inner')",
                Long.class))
        .isZero();
  }

  @Test
  void scheduledMetricsDoNotWaitForTheApiPoolOrBlockOtherScheduledWork() throws Exception {
    var api = apiPool();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      try (var held = api.getConnection()) {
        var refresh = BusinessMetrics.class.getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        executor
            .submit(
                () -> {
                  refresh.invoke(context.getBean(BusinessMetrics.class));
                  return null;
                })
            .get(2, TimeUnit.SECONDS);
      }
    } finally {
      executor.close();
    }
  }

  @Test
  void durableCallbackDoesNotWaitForAnExhaustedApiPool() throws Exception {
    var payment = payment();
    var api = apiPool();
    String body =
        "{\"aggregateId\":\""
            + payment.id()
            + "\",\"operation\":\"PAYMENT\",\"channel\":\"MOCK_A\",\"status\":\"SUCCEEDED\",\"reference\":\"pool-callback\",\"amountMinor\":\"10000\",\"currency\":\"CNY\",\"feeMinor\":\"60\"}";
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      try (var held = api.getConnection()) {
        var response =
            executor.submit(
                () ->
                    mvc.perform(
                            post("/api/channel/callback")
                                .contentType("application/json")
                                .content(body)
                                .header("X-Channel-Timestamp", timestamp)
                                .header(
                                    "X-Channel-Signature",
                                    Signatures.hmac("test", timestamp + "\n" + body)))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertThat(response.get(2, TimeUnit.SECONDS)).isEqualTo(200);
      }
    } finally {
      executor.close();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_notifications WHERE aggregate_id=?",
                Long.class,
                payment.id()))
        .isEqualTo(1);
  }

  @Test
  void taskClaimAndAcknowledgementUseReservedWorkerConnections() throws Exception {
    var payment = payment();
    var api = apiPool();
    var observed = new JdbcTemplate(context.getBean("workerDataSource", DataSource.class));
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try (var worker =
        new TaskWorker(
            queue,
            mock(ChannelGateway.class),
            mock(InboxService.class),
            mock(NotificationService.class),
            jdbc,
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            null,
            null,
            30)) {
      try {
        try (var held = api.getConnection()) {
          var poll = TaskWorker.class.getDeclaredMethod("poll");
          poll.setAccessible(true);
          executor
              .submit(
                  () -> {
                    poll.invoke(worker);
                    return null;
                  })
              .get(2, TimeUnit.SECONDS);
          org.awaitility.Awaitility.await()
              .atMost(Duration.ofSeconds(2))
              .untilAsserted(
                  () ->
                      assertThat(
                              observed.queryForObject(
                                  "SELECT status FROM outbox_tasks WHERE event_id=?",
                                  String.class,
                                  "channel-payment:" + payment.id()))
                          .isEqualTo("DONE"));
        }
      } finally {
        executor.close();
      }
    }
  }
}
