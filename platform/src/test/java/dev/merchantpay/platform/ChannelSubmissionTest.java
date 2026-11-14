package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.refund.RefundService;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ChannelSubmissionTest extends FinancialFixture {
  ChannelApi api;
  ChannelGateway gateway;

  @BeforeEach
  void gateway() {
    api = mock(ChannelApi.class);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    gateway =
        new ChannelGateway(
            api,
            payments,
            refunds,
            jdbc,
            JsonMapper.builder().build(),
            "test",
            Clock.systemUTC(),
            tx,
            new ChannelResultBatcher(
                tx.getTransactionManager(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                1,
                Duration.ZERO),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  @Test
  void concurrentGatewayResultsShareOneFinancialCommit() throws Exception {
    var firstAttempt = task("PAYMENT", "batch-gateway-a");
    var secondAttempt = task("PAYMENT", "batch-gateway-b");
    var firstTask = reclaimed(firstAttempt);
    var secondTask = reclaimed(secondAttempt);
    when(api.query("MOCK_A", "PAYMENT", firstTask.aggregateId(), "test"))
        .thenReturn(receipt(firstTask));
    when(api.query("MOCK_A", "PAYMENT", secondTask.aggregateId(), "test"))
        .thenReturn(receipt(secondTask));
    var commits = new AtomicInteger();
    var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            commits.incrementAndGet();
          }
        };
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);

    try (var batcher = new ChannelResultBatcher(manager, metrics, 32, Duration.ofMillis(50));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var observed =
          new ChannelGateway(
              api,
              payments,
              refunds,
              jdbc,
              JsonMapper.builder().build(),
              "test",
              Clock.systemUTC(),
              new TransactionTemplate(manager),
              batcher,
              metrics);
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                observed.execute(firstTask);
                return null;
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                observed.execute(secondTask);
                return null;
              });
      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(3, TimeUnit.SECONDS);
      second.get(3, TimeUnit.SECONDS);
    }

    assertThat(payments.get("merchant-a", firstTask.aggregateId()).status()).isEqualTo("SUCCEEDED");
    assertThat(payments.get("merchant-a", secondTask.aggregateId()).status())
        .isEqualTo("SUCCEEDED");
    for (var task : java.util.List.of(firstTask, secondTask)) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ledger_journals WHERE payment_id=?",
                  Long.class,
                  task.aggregateId()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE j.payment_id=?",
                  Long.class,
                  task.aggregateId()))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM channel_attempts WHERE aggregate_id=?",
                  String.class,
                  task.aggregateId()))
          .isEqualTo("SUCCEEDED");
    }
    assertThat(metrics.get("merchantpay.channel.result.batch.size").summary().max()).isEqualTo(2);
    assertThat(commits.get()).isEqualTo(1);
  }

  private static TaskQueue.Task reclaimed(TaskQueue.Task task) {
    return new TaskQueue.Task(
        task.id(),
        task.eventId(),
        task.kind(),
        task.aggregateId(),
        task.merchantId(),
        2,
        task.token());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PAYMENT", "REFUND"})
  void firstSubmissionPersistsPreparationAndPostsWithoutAQuery(String operation) {
    var task = task(operation, "first-submit");
    var receipt = receipt(task);
    when(api.query("MOCK_A", operation, task.aggregateId(), "test"))
        .thenReturn(
            new ChannelApi.Receipt(
                task.aggregateId(), operation, "MOCK_A", "NOT_FOUND", "", "0", "CNY", "0"));
    var submit =
        operation.equals("PAYMENT")
            ? when(api.pay(eq("MOCK_A"), eq("test"), any()))
            : when(api.refund(eq("MOCK_A"), eq("test"), any()));
    submit.thenAnswer(
        call -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT status FROM channel_attempts WHERE aggregate_id=?",
                      String.class,
                      task.aggregateId()))
              .isEqualTo("PREPARED");
          assertThat(count("ledger_journals")).isEqualTo(operation.equals("PAYMENT") ? 0 : 1);
          return receipt;
        });
    gateway.execute(task);
    gateway.execute(task);
    verify(api, never()).query(anyString(), anyString(), anyString(), anyString());
    assertThat(count("ledger_journals")).isEqualTo(operation.equals("PAYMENT") ? 1 : 2);
    assertThat(jdbc.queryForObject("SELECT response_body FROM channel_attempts", String.class))
        .isEqualTo(JsonMapper.builder().build().writeValueAsString(receipt));
    assertThat(jdbc.queryForObject("SELECT SUM(inflight) FROM payment_admission", Long.class))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PAYMENT", "REFUND"})
  void uncertainSubmissionQueriesTheOriginalAttemptWithoutSubmittingAgain(String operation) {
    var task = task(operation, "uncertain-submit");
    var submit =
        operation.equals("PAYMENT")
            ? when(api.pay(eq("MOCK_A"), eq("test"), any()))
            : when(api.refund(eq("MOCK_A"), eq("test"), any()));
    submit.thenThrow(new IllegalStateException("response lost after remote commit"));
    when(api.query("MOCK_A", operation, task.aggregateId(), "test")).thenReturn(receipt(task));
    assertThatThrownBy(() -> gateway.execute(task)).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM channel_attempts", String.class))
        .isEqualTo("PREPARED");
    // Even a replay carrying the original attempt count must recognize durable preparation.
    gateway.execute(task);
    gateway.execute(task);
    verify(api).query("MOCK_A", operation, task.aggregateId(), "test");
    if (operation.equals("PAYMENT")) verify(api).pay(eq("MOCK_A"), eq("test"), any());
    else verify(api).refund(eq("MOCK_A"), eq("test"), any());
    verifyNoMoreInteractions(api);
    assertThat(count("ledger_journals")).isEqualTo(operation.equals("PAYMENT") ? 1 : 2);
  }

  private TaskQueue.Task task(String operation, String order) {
    var payment =
        operation.equals("PAYMENT") ? createPayment(order, "10000") : paid(order, "10000");
    String id =
        operation.equals("PAYMENT")
            ? payment.id()
            : refunds
                .create("merchant-a", payment.id(), new RefundService.CreateRefund(order, "1000"))
                .id();
    return new TaskQueue.Task("task", order, "CHANNEL_" + operation, id, "merchant-a", 1, "lease");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "PAYMENT,true",
    "REFUND,true",
    "PAYMENT,false",
    "REFUND,false"
  })
  void preparedOrReclaimedProcessingOrdersQueryBeforeAnySubmission(
      String operation, boolean prepared) {
    var original = task(operation, "crash-before-result");
    var task =
        prepared
            ? original
            : new TaskQueue.Task(
                original.id(),
                original.eventId(),
                original.kind(),
                original.aggregateId(),
                original.merchantId(),
                2,
                original.token());
    if (prepared) {
      String payment =
          operation.equals("PAYMENT")
              ? task.aggregateId()
              : refunds.get("merchant-a", task.aggregateId()).paymentId();
      jdbc.update(
          "INSERT INTO channel_attempts(id,aggregate_id,payment_id,channel,operation,request_key,status,created_at,updated_at) VALUES (?,?,?,'MOCK_A',?,?,'PREPARED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          "existing-preparation",
          task.aggregateId(),
          payment,
          task.kind(),
          task.kind() + ":" + task.aggregateId());
    }
    when(api.query("MOCK_A", operation, task.aggregateId(), "test")).thenReturn(receipt(task));
    gateway.execute(task);
    verify(api).query("MOCK_A", operation, task.aggregateId(), "test");
    verifyNoMoreInteractions(api);
    assertThat(count("ledger_journals")).isEqualTo(operation.equals("PAYMENT") ? 1 : 2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PAYMENT", "REFUND"})
  void firstResponseConflictRemainsDurableWithoutPostingMoney(String operation) throws Exception {
    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
        schemaConnection,
        new org.springframework.core.io.ClassPathResource(
            "db/migration/V2__security_and_evidence.sql"));
    var task = task(operation, "first-conflict");
    var receipt =
        new ChannelApi.Receipt(
            "wrong-id", operation, "MOCK_A", "SUCCEEDED", "wrong-receipt", "10000", "CNY", "0");
    var submit =
        operation.equals("PAYMENT")
            ? when(api.pay(eq("MOCK_A"), eq("test"), any()))
            : when(api.refund(eq("MOCK_A"), eq("test"), any()));
    submit.thenReturn(receipt);
    assertThatThrownBy(() -> gateway.execute(task))
        .isInstanceOfSatisfying(
            dev.merchantpay.platform.common.ApiException.class,
            error -> assertThat(error.code()).isEqualTo("CHANNEL_EVIDENCE_CONFLICT"));
    assertThat(count("ledger_journals")).isEqualTo(operation.equals("PAYMENT") ? 0 : 1);
    assertThat(count("evidence_conflicts")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT response_body FROM channel_attempts", String.class))
        .isEqualTo(JsonMapper.builder().build().writeValueAsString(receipt));
    verify(api, never()).query(anyString(), anyString(), anyString(), anyString());
  }

  private ChannelApi.Receipt receipt(TaskQueue.Task task) {
    boolean payment = task.kind().equals("CHANNEL_PAYMENT");
    return new ChannelApi.Receipt(
        task.aggregateId(),
        payment ? "PAYMENT" : "REFUND",
        "MOCK_A",
        "SUCCEEDED",
        "receipt-" + task.aggregateId(),
        payment ? "10000" : "1000",
        "CNY",
        "0");
  }
}
