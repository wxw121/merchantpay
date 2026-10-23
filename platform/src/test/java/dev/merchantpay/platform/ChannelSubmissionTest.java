package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.refund.RefundService;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ChannelSubmissionTest extends FinancialFixture {
  ChannelApi api;
  ChannelGateway gateway;

  @BeforeEach
  void gateway() {
    api = mock(ChannelApi.class);
    gateway =
        new ChannelGateway(
            api,
            payments,
            refunds,
            jdbc,
            JsonMapper.builder().build(),
            "test",
            Clock.systemUTC(),
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
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
