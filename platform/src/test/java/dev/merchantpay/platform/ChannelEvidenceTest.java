package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.refund.RefundService;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ChannelEvidenceTest extends FinancialFixture {
  TransactionTemplate tx;
  JsonMapper json;
  ChannelGateway gateway;
  InboxService inbox;

  @BeforeEach
  void evidenceSchema() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V2__security_and_evidence.sql"));
    tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    json = JsonMapper.builder().build();
    gateway = gateway(null);
    inbox =
        new InboxService(
            jdbc,
            tx,
            new OutboxService(jdbc, Clock.systemUTC()),
            gateway,
            json,
            Clock.systemUTC(),
            "test",
            false);
  }

  ChannelGateway gateway(ChannelApi api) {
    return new ChannelGateway(
        api,
        payments,
        refunds,
        jdbc,
        json,
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

  InboxService fastInbox() {
    return new InboxService(
        jdbc,
        tx,
        new OutboxService(jdbc, Clock.systemUTC()),
        gateway,
        json,
        Clock.systemUTC(),
        "test",
        true);
  }

  void receive(InboxService service, ChannelApi.Receipt receipt) {
    String raw = json.writeValueAsString(receipt);
    String time = Long.toString(Instant.now().getEpochSecond());
    service.receive(time, Signatures.hmac("test", time + "\n" + raw), raw);
  }

  @Test
  void matchingSuccessfulPaymentCallbackKeepsEvidenceWithoutCreatingASecondTask() {
    var payment = paid("fast-payment", "10000");
    var fastInbox = fastInbox();
    var receipt =
        new ChannelApi.Receipt(
            payment.id(),
            "PAYMENT",
            "MOCK_A",
            "SUCCEEDED",
            payment.channelReference(),
            "10000",
            "CNY",
            "60");
    String raw = json.writeValueAsString(receipt);
    String time = Long.toString(Instant.now().getEpochSecond());
    long tasks = count("outbox_tasks"),
        events = count("outbox_events"),
        journals = count("ledger_journals");

    fastInbox.receive(time, Signatures.hmac("test", time + "\n" + raw), raw);

    assertThat(jdbc.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("PROCESSED");
    assertThat(jdbc.queryForObject("SELECT raw_body FROM inbox_notifications", String.class))
        .isEqualTo(raw);
    assertThat(count("outbox_tasks")).isEqualTo(tasks);
    assertThat(count("outbox_events")).isEqualTo(events);
    assertThat(count("ledger_journals")).isEqualTo(journals);
    fastInbox.receive(time, Signatures.hmac("test", time + "\n" + raw), raw);
    assertThat(count("inbox_notifications")).isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(tasks);
  }

  @Test
  void matchingSuccessfulRefundCallbackKeepsEvidenceWithoutCreatingASecondTask() {
    var payment = paid("fast-refund", "10000");
    var refund =
        refunds.create(
            "merchant-a", payment.id(), new RefundService.CreateRefund("fast-refund", "1000"));
    refunds.applyResult(
        refund.id(), new ChannelResult.Success("MOCK_A", "fast-refund-reference", 1000, "CNY"));
    long tasks = count("outbox_tasks"), journals = count("ledger_journals");
    var receipt =
        new ChannelApi.Receipt(
            refund.id(),
            "REFUND",
            "MOCK_A",
            "SUCCEEDED",
            "fast-refund-reference",
            "1000",
            "CNY",
            "0");

    receive(fastInbox(), receipt);

    assertThat(jdbc.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("PROCESSED");
    assertThat(jdbc.queryForObject("SELECT raw_body FROM inbox_notifications", String.class))
        .isEqualTo(json.writeValueAsString(receipt));
    assertThat(count("outbox_tasks")).isEqualTo(tasks);
    assertThat(count("ledger_journals")).isEqualTo(journals);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "different,10000,CNY",
    "same,9999,CNY",
    "same,10000,USD"
  })
  void conflictingSuccessfulCallbackStillUsesWorkerAndRetainsEvidence(
      String reference, String amount, String currency) {
    var payment = paid("fast-conflict", "10000");
    var fastInbox = fastInbox();
    var receipt =
        new ChannelApi.Receipt(
            payment.id(),
            "PAYMENT",
            "MOCK_A",
            "SUCCEEDED",
            reference.equals("same") ? payment.channelReference() : reference,
            amount,
            currency,
            "60");
    long tasks = count("outbox_tasks"), journals = count("ledger_journals");

    receive(fastInbox, receipt);

    String id = jdbc.queryForObject("SELECT id FROM inbox_notifications", String.class);
    assertThat(jdbc.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("RECEIVED");
    assertThat(count("outbox_tasks")).isEqualTo(tasks + 1);
    fastInbox.process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("CONFLICT");
    assertThat(jdbc.queryForObject("SELECT raw_body FROM evidence_conflicts", String.class))
        .isEqualTo(json.writeValueAsString(receipt));
    assertThat(count("ledger_journals")).isEqualTo(journals);
  }

  @Test
  void callbackBeforeSuccessfulOutcomeKeepsRecoverableWorkerPath() {
    var payment = createPayment("fast-race", "10000");
    var fastInbox = fastInbox();
    var receipt =
        new ChannelApi.Receipt(
            payment.id(), "PAYMENT", "MOCK_A", "SUCCEEDED", "fast-race-ref", "10000", "CNY", "60");
    long tasks = count("outbox_tasks");

    receive(fastInbox, receipt);

    String id = jdbc.queryForObject("SELECT id FROM inbox_notifications", String.class);
    assertThat(jdbc.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("RECEIVED");
    assertThat(count("outbox_tasks")).isEqualTo(tasks + 1);
    gateway.apply(receipt);
    fastInbox.process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("PROCESSED");
    assertThat(count("ledger_journals")).isEqualTo(1);
  }

  @Test
  void disabledFastPathStillQueuesMatchingSuccessfulCallback() {
    var payment = paid("fast-disabled", "10000");
    long tasks = count("outbox_tasks");

    receive(
        inbox,
        new ChannelApi.Receipt(
            payment.id(),
            "PAYMENT",
            "MOCK_A",
            "SUCCEEDED",
            payment.channelReference(),
            "10000",
            "CNY",
            "60"));

    assertThat(jdbc.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("RECEIVED");
    assertThat(count("outbox_tasks")).isEqualTo(tasks + 1);
  }

  @Test
  void synchronousConflictEvidenceIsRetainedAfterReleasingTheResultTransaction() {
    var payment = createPayment("conflict-connection-budget", "10000");
    var api = mock(ChannelApi.class);
    var receipt =
        new ChannelApi.Receipt(
            payment.id(), "PAYMENT", "MOCK_A", "SUCCEEDED", "wrong-amount", "9999", "CNY", "0");
    when(api.query("MOCK_A", "PAYMENT", payment.id(), "test")).thenReturn(receipt);
    var suspended = new java.util.concurrent.atomic.AtomicInteger();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected Object doSuspend(Object transaction) {
            suspended.incrementAndGet();
            return super.doSuspend(transaction);
          }
        };
    var observed =
        new ChannelGateway(
            api,
            payments,
            refunds,
            jdbc,
            json,
            "test",
            Clock.systemUTC(),
            new TransactionTemplate(manager),
            new ChannelResultBatcher(
                manager,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                1,
                Duration.ZERO),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    var task =
        new TaskQueue.Task(
            "conflict-task",
            "conflict-connection-budget",
            "CHANNEL_PAYMENT",
            payment.id(),
            "merchant-a",
            2,
            "lease");
    assertThatThrownBy(() -> observed.execute(task))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code()).isEqualTo("CHANNEL_EVIDENCE_CONFLICT"));
    assertThat(count("evidence_conflicts")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM channel_attempts", String.class))
        .isEqualTo("CONFLICT");
    assertThat(count("ledger_journals")).isZero();
    assertThat(suspended.get())
        .as(
            "conflict persistence must not reserve a second connection while holding the result transaction")
        .isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"PAYMENT", "REFUND"})
  void finalAttemptWriteFailureRollsBackMoneyButRetainsRawReceiptForRecovery(String operation) {
    boolean isRefund = operation.equals("REFUND");
    var payment =
        isRefund ? paid("attempt-atomic", "10000") : createPayment("attempt-atomic", "10000");
    String aggregate =
        isRefund
            ? refunds
                .create(
                    "merchant-a",
                    payment.id(),
                    new RefundService.CreateRefund("attempt-atomic", "1000"))
                .id()
            : payment.id();
    var receipt =
        new ChannelApi.Receipt(
            aggregate,
            operation,
            "MOCK_A",
            "SUCCEEDED",
            "attempt-atomic-receipt",
            isRefund ? "1000" : "10000",
            "CNY",
            "0");
    var api = mock(ChannelApi.class);
    when(api.query("MOCK_A", operation, aggregate, "test")).thenReturn(receipt);
    var task =
        new TaskQueue.Task(
            "ack", "attempt-atomic", "CHANNEL_" + operation, aggregate, "merchant-a", 2, "lease");
    jdbc.execute(
        "ALTER TABLE channel_attempts ADD CONSTRAINT reject_final_attempt CHECK (status <> 'SUCCEEDED')");
    assertThatThrownBy(() -> gateway(api).execute(task)).isInstanceOf(RuntimeException.class);
    assertThat(
            isRefund
                ? refunds.get("merchant-a", aggregate).status()
                : payments.get("merchant-a", aggregate).status())
        .isEqualTo("UNKNOWN");
    assertThat(count("ledger_journals")).isEqualTo(isRefund ? 1 : 0);
    assertThat(jdbc.queryForObject("SELECT response_body FROM channel_attempts", String.class))
        .isEqualTo(json.writeValueAsString(receipt));
    assertThat(jdbc.queryForObject("SELECT status FROM channel_attempts", String.class))
        .isEqualTo("PREPARED");
    assertThat(jdbc.queryForObject("SELECT SUM(inflight) FROM payment_admission", Long.class))
        .isEqualTo(isRefund ? 0 : 1);
    assertThat(payments.get("merchant-a", payment.id()).reservedMinor())
        .isEqualTo(isRefund ? "1000" : "0");
    jdbc.execute("ALTER TABLE channel_attempts DROP CONSTRAINT reject_final_attempt");
    gateway(api).execute(task);
    gateway(api).execute(task);
    assertThat(count("ledger_journals")).isEqualTo(isRefund ? 2 : 1);
    assertThat(jdbc.queryForObject("SELECT status FROM channel_attempts", String.class))
        .isEqualTo("SUCCEEDED");
    assertThat(jdbc.queryForObject("SELECT SUM(inflight) FROM payment_admission", Long.class))
        .isZero();
    assertThat(payments.get("merchant-a", payment.id()).reservedMinor()).isEqualTo("0");
    verify(api, times(2)).query("MOCK_A", operation, aggregate, "test");
    verifyNoMoreInteractions(api);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "PAYMENT,false,false", "PAYMENT,false,true", "PAYMENT,true,false", "PAYMENT,true,true",
    "REFUND,false,false", "REFUND,false,true", "REFUND,true,false", "REFUND,true,true"
  })
  void reusedChannelReferenceIsRetainedAsAConflictWithoutChangingMoney(
      String operation, boolean callback, boolean otherMerchant) {
    var owner = paid("reference-owner", "10000");
    String merchant = otherMerchant ? "merchant-b" : "merchant-a";
    String reference = owner.channelReference();
    boolean isRefund = operation.equals("REFUND");
    var target =
        payments.create(
            merchant,
            new dev.merchantpay.platform.payment.PaymentService.CreatePayment(
                "reference-target", "10000", "CNY", "MOCK_A", null));
    String aggregate = target.id();
    if (isRefund) {
      var original =
          refunds.create(
              "merchant-a", owner.id(), new RefundService.CreateRefund("reference-owner", "1000"));
      reference = "shared-refund-reference";
      refunds.applyResult(
          original.id(), new ChannelResult.Success("MOCK_A", reference, 1000, "CNY"));
      payments.applyResult(
          target.id(), new ChannelResult.Success("MOCK_A", "target-payment-ref", 10000, "CNY"));
      aggregate =
          refunds
              .create(
                  merchant, target.id(), new RefundService.CreateRefund("reference-target", "2000"))
              .id();
    }
    var before = payments.get(merchant, target.id());
    long journals = count("ledger_journals");
    var receipt =
        new ChannelApi.Receipt(
            aggregate,
            operation,
            "MOCK_A",
            "SUCCEEDED",
            reference,
            isRefund ? "2000" : "10000",
            "CNY",
            "0");
    String raw = json.writeValueAsString(receipt);
    if (callback) {
      String timestamp = Long.toString(Instant.now().getEpochSecond());
      inbox.receive(timestamp, Signatures.hmac("test", timestamp + "\n" + raw), raw);
      String id =
          jdbc.queryForObject(
              "SELECT id FROM inbox_notifications WHERE aggregate_id=?", String.class, aggregate);
      inbox.process(id);
      inbox.process(id);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM inbox_notifications WHERE id=?", String.class, id))
          .isEqualTo("CONFLICT");
    } else {
      var api = mock(ChannelApi.class);
      when(api.query("MOCK_A", operation, aggregate, "test")).thenReturn(receipt);
      var task =
          new TaskQueue.Task(
              java.util.UUID.randomUUID().toString(),
              "reference-test",
              "CHANNEL_" + operation,
              aggregate,
              merchant,
              2,
              java.util.UUID.randomUUID().toString());
      assertThatThrownBy(() -> gateway(api).execute(task))
          .isInstanceOfSatisfying(
              ApiException.class,
              error -> assertThat(error.code()).isEqualTo("CHANNEL_EVIDENCE_CONFLICT"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM channel_attempts WHERE aggregate_id=?",
                  String.class,
                  aggregate))
          .isEqualTo("CONFLICT");
      assertThat(
              jdbc.queryForObject(
                  "SELECT response_body FROM channel_attempts WHERE aggregate_id=?",
                  String.class,
                  aggregate))
          .isEqualTo(raw);
    }
    assertThat(count("evidence_conflicts")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT merchant_id FROM evidence_conflicts", String.class))
        .isEqualTo(merchant);
    assertThat(jdbc.queryForObject("SELECT aggregate_id FROM evidence_conflicts", String.class))
        .isEqualTo(aggregate);
    assertThat(jdbc.queryForObject("SELECT raw_body FROM evidence_conflicts", String.class))
        .isEqualTo(raw);
    assertThat(count("ledger_journals")).isEqualTo(journals);
    assertThat(payments.get(merchant, target.id())).isEqualTo(before);
    if (isRefund) assertThat(refunds.get(merchant, aggregate).status()).isEqualTo("PROCESSING");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void workerQuarantinesEvidenceConflictsButStillRetriesTransportFailures(boolean conflict)
      throws Exception {
    var owner = paid("worker-reference-owner", "10000");
    jdbc.update("DELETE FROM outbox_tasks");
    var target = createPayment("worker-reference-target", "10000");
    // Exercise recovery of an earlier execution, whose response must be queried and validated.
    jdbc.update("UPDATE outbox_tasks SET attempts=1 WHERE aggregate_id=?", target.id());
    var api = mock(ChannelApi.class);
    if (conflict) {
      when(api.query("MOCK_A", "PAYMENT", target.id(), "test"))
          .thenReturn(
              new ChannelApi.Receipt(
                  target.id(),
                  "PAYMENT",
                  "MOCK_A",
                  "SUCCEEDED",
                  owner.channelReference(),
                  "10000",
                  "CNY",
                  "0"));
    } else {
      when(api.query("MOCK_A", "PAYMENT", target.id(), "test"))
          .thenThrow(new IllegalStateException("temporary transport failure"));
    }
    var queue = new TaskQueue(jdbc, tx, Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC));
    try (var worker =
        new dev.merchantpay.platform.orchestration.TaskWorker(
            queue,
            gateway(api),
            inbox,
            null,
            jdbc,
            Clock.systemUTC(),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            null,
            null,
            30)) {
      var poll = worker.getClass().getDeclaredMethod("poll");
      poll.setAccessible(true);
      poll.invoke(worker);
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT status FROM outbox_tasks WHERE aggregate_id=?",
                              String.class,
                              target.id()))
                      .isEqualTo(conflict ? "DEAD" : "PENDING"));
    }
    assertThat(payments.get("merchant-a", target.id()).status())
        .isEqualTo(conflict ? "PROCESSING" : "UNKNOWN");
    assertThat(count("evidence_conflicts")).isEqualTo(conflict ? 1 : 0);
    assertThat(count("ledger_journals")).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"PAYMENT", "REFUND"})
  void unrelatedJournalUniqueViolationIsNotDisguisedAsAReceiptConflict(String operation) {
    boolean isRefund = operation.equals("REFUND");
    var payment =
        isRefund ? paid("journal-error", "10000") : createPayment("journal-error", "10000");
    String aggregate =
        isRefund
            ? refunds
                .create(
                    "merchant-a",
                    payment.id(),
                    new RefundService.CreateRefund("journal-error", "1000"))
                .id()
            : payment.id();
    jdbc.update(
        "INSERT INTO ledger_journals(id,event_id,payment_id,merchant_id,source_type,source_id,currency,created_at) VALUES (?,?,?,'merchant-a',?,?,'CNY',?)",
        java.util.UUID.randomUUID().toString(),
        (isRefund ? "refund-success:" : "payment-success:") + aggregate,
        payment.id(),
        operation,
        aggregate,
        java.sql.Timestamp.from(Instant.now()));
    var before = payments.get("merchant-a", payment.id());
    assertThatThrownBy(
            () ->
                gateway.apply(
                    new ChannelApi.Receipt(
                        aggregate,
                        operation,
                        "MOCK_A",
                        "SUCCEEDED",
                        "unused-channel-reference",
                        isRefund ? "1000" : "10000",
                        "CNY",
                        "0")))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(count("evidence_conflicts")).isZero();
    assertThat(payments.get("merchant-a", payment.id())).isEqualTo(before);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"PAYMENT", "REFUND"})
  void correctlyMatchedFailureStillCompletesAndReleasesOnlyItsReservation(String operation) {
    boolean isRefund = operation.equals("REFUND");
    var payment =
        isRefund ? paid("valid-failure", "10000") : createPayment("valid-failure", "10000");
    var refund =
        isRefund
            ? refunds.create(
                "merchant-a", payment.id(), new RefundService.CreateRefund("valid-failure", "8000"))
            : null;
    String aggregate = isRefund ? refund.id() : payment.id();
    var receipt =
        new ChannelApi.Receipt(
            aggregate,
            operation,
            "MOCK_A",
            "FAILED",
            "failed-ref",
            isRefund ? "8000" : "10000",
            "CNY",
            "0");
    gateway.apply(receipt);
    gateway.apply(receipt);
    assertThat(
            isRefund
                ? refunds.get("merchant-a", aggregate).status()
                : payments.get("merchant-a", aggregate).status())
        .isEqualTo("FAILED");
    assertThat(payments.get("merchant-a", payment.id()).reservedMinor()).isEqualTo("0");
    assertThat(count("evidence_conflicts")).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void zeroAmountCloseTombstoneRequiresAnExistingCloseIntent(boolean requested) {
    var payment = createPayment("close-before-submit", "10000");
    if (requested) payments.requestClose("merchant-a", payment.id());
    var receipt =
        new ChannelApi.Receipt(
            payment.id(), "PAYMENT", "MOCK_A", "CLOSED", "mock-close-test", "0", "CNY", "0");
    if (requested) {
      gateway.apply(receipt);
      assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("CLOSED");
    } else {
      assertThatThrownBy(() -> gateway.apply(receipt)).isInstanceOf(ApiException.class);
      assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("PROCESSING");
    }
    assertThat(count("ledger_journals")).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "PAYMENT,1,CNY,false", "PAYMENT,10000,USD,false",
    "REFUND,1,CNY,false", "REFUND,8000,USD,false",
    "PAYMENT,1,CNY,true", "PAYMENT,10000,USD,true",
    "REFUND,1,CNY,true", "REFUND,8000,USD,true"
  })
  void mismatchedFailedEvidenceCannotChangeMoneyOrReleaseReservation(
      String operation, String amount, String currency, boolean callback) {
    boolean isRefund = operation.equals("REFUND");
    var payment =
        isRefund ? paid("failed-evidence", "10000") : createPayment("failed-evidence", "10000");
    var refund =
        isRefund
            ? refunds.create(
                "merchant-a",
                payment.id(),
                new RefundService.CreateRefund("failed-evidence", "8000"))
            : null;
    String aggregate = isRefund ? refund.id() : payment.id();
    var receipt =
        new ChannelApi.Receipt(
            aggregate, operation, "MOCK_A", "FAILED", "failed-ref", amount, currency, "0");
    if (callback) {
      String raw = json.writeValueAsString(receipt),
          timestamp = Long.toString(Instant.now().getEpochSecond());
      inbox.receive(timestamp, Signatures.hmac("test", timestamp + "\n" + raw), raw);
      String id = jdbc.queryForObject("SELECT id FROM inbox_notifications", String.class);
      inbox.process(id);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM inbox_notifications WHERE id=?", String.class, id))
          .isEqualTo("CONFLICT");
    } else {
      assertThatThrownBy(() -> gateway.apply(receipt))
          .isInstanceOfSatisfying(
              ApiException.class,
              error -> assertThat(error.code()).isEqualTo("CHANNEL_EVIDENCE_CONFLICT"));
    }
    assertThat(count("evidence_conflicts")).isEqualTo(1);
    assertThat(count("ledger_journals")).isEqualTo(isRefund ? 1 : 0);
    assertThat(payments.get("merchant-a", payment.id()).status())
        .isEqualTo(isRefund ? "SUCCEEDED" : "PROCESSING");
    assertThat(payments.get("merchant-a", payment.id()).reservedMinor())
        .isEqualTo(isRefund ? "8000" : "0");
    if (isRefund) assertThat(refunds.get("merchant-a", aggregate).status()).isEqualTo("PROCESSING");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "CHANNEL_PAYMENT,NOT_FOUND",
    "CHANNEL_REFUND,NOT_FOUND",
    "CHANNEL_CLOSE,PENDING"
  })
  void intermediateQueryIdentityConflictStopsFurtherChannelCalls(String kind, String status) {
    boolean isRefund = kind.equals("CHANNEL_REFUND");
    var payment =
        isRefund ? paid("routing-conflict", "10000") : createPayment("routing-conflict", "10000");
    var refund =
        isRefund
            ? refunds.create(
                "merchant-a",
                payment.id(),
                new RefundService.CreateRefund("routing-conflict", "8000"))
            : null;
    String aggregate = isRefund ? refund.id() : payment.id(),
        operation = isRefund ? "REFUND" : "PAYMENT";
    if (kind.equals("CHANNEL_CLOSE")) payments.requestClose("merchant-a", payment.id());
    var receipt =
        new ChannelApi.Receipt(
            "wrong-aggregate", operation, "MOCK_A", status, "remote-ref", "0", "CNY", "0");
    var api = mock(ChannelApi.class);
    when(api.query("MOCK_A", operation, aggregate, "test")).thenReturn(receipt);
    var correct =
        new ChannelApi.Receipt(
            aggregate,
            operation,
            "MOCK_A",
            kind.equals("CHANNEL_CLOSE") ? "CLOSED" : "SUCCEEDED",
            "correct-ref",
            isRefund ? "8000" : "10000",
            "CNY",
            "0");
    when(api.pay(eq("MOCK_A"), eq("test"), any())).thenReturn(correct);
    when(api.refund(eq("MOCK_A"), eq("test"), any())).thenReturn(correct);
    when(api.close("MOCK_A", aggregate, "test")).thenReturn(correct);
    var task =
        new TaskQueue.Task(
            java.util.UUID.randomUUID().toString(),
            "routing-conflict",
            kind,
            aggregate,
            "merchant-a",
            2,
            java.util.UUID.randomUUID().toString());
    assertThatThrownBy(() -> gateway(api).execute(task))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code()).isEqualTo("CHANNEL_EVIDENCE_CONFLICT"));
    verify(api).query("MOCK_A", operation, aggregate, "test");
    verifyNoMoreInteractions(api);
    assertThat(jdbc.queryForObject("SELECT aggregate_id FROM evidence_conflicts", String.class))
        .isEqualTo(aggregate);
    assertThat(jdbc.queryForObject("SELECT response_body FROM channel_attempts", String.class))
        .isEqualTo(json.writeValueAsString(receipt));
    assertThat(jdbc.queryForObject("SELECT status FROM channel_attempts", String.class))
        .isEqualTo("CONFLICT");
    assertThat(count("ledger_journals")).isEqualTo(isRefund ? 1 : 0);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"aggregateId", "operation", "channel"})
  void synchronousIdentityConflictPreservesRawReceiptAgainstTheExpectedAggregate(String field) {
    var payment = createPayment("wrong-identity", "10000");
    var receipt =
        new ChannelApi.Receipt(
            field.equals("aggregateId") ? "wrong-aggregate" : payment.id(),
            field.equals("operation") ? "REFUND" : "PAYMENT",
            field.equals("channel") ? "MOCK_B" : "MOCK_A",
            "SUCCEEDED",
            "remote-ref",
            "10000",
            "CNY",
            "60");
    var api = mock(ChannelApi.class);
    when(api.query("MOCK_A", "PAYMENT", payment.id(), "test")).thenReturn(receipt);
    var task =
        new TaskQueue.Task(
            java.util.UUID.randomUUID().toString(),
            "identity-test",
            "CHANNEL_PAYMENT",
            payment.id(),
            "merchant-a",
            2,
            java.util.UUID.randomUUID().toString());
    assertThatThrownBy(() -> gateway(api).execute(task))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code()).isEqualTo("CHANNEL_EVIDENCE_CONFLICT"));
    assertThat(jdbc.queryForObject("SELECT aggregate_id FROM evidence_conflicts", String.class))
        .isEqualTo(payment.id());
    assertThat(jdbc.queryForObject("SELECT raw_body FROM evidence_conflicts", String.class))
        .isEqualTo(json.writeValueAsString(receipt));
    assertThat(jdbc.queryForObject("SELECT response_body FROM channel_attempts", String.class))
        .isEqualTo(json.writeValueAsString(receipt));
    assertThat(jdbc.queryForObject("SELECT status FROM channel_attempts", String.class))
        .isEqualTo("CONFLICT");
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("PROCESSING");
    assertThat(count("ledger_journals")).isZero();
  }

  @Test
  void oppositeTerminalEvidenceIsPreservedForBothInboxAndSynchronousNormalization() {
    var payment = paid("opposite-evidence", "10000");
    String raw =
        json.writeValueAsString(
            new ChannelApi.Receipt(
                payment.id(),
                "PAYMENT",
                "MOCK_A",
                "FAILED",
                "failed-reference",
                "10000",
                "CNY",
                "60"));
    String time = Long.toString(Instant.now().getEpochSecond());
    inbox.receive(time, Signatures.hmac("test", time + "\n" + raw), raw);
    String id = jdbc.queryForObject("SELECT id FROM inbox_notifications", String.class);
    inbox.process(id);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM inbox_notifications WHERE id=?", String.class, id))
        .isEqualTo("CONFLICT");
    assertThat(count("evidence_conflicts")).isEqualTo(1);
    inbox.process(id);
    assertThat(count("evidence_conflicts")).isEqualTo(1);
    assertThatThrownBy(
            () ->
                gateway.apply(
                    new ChannelApi.Receipt(
                        payment.id(),
                        "PAYMENT",
                        "MOCK_A",
                        "CLOSED",
                        "closed-ref",
                        "10000",
                        "CNY",
                        "60")))
        .isInstanceOf(ApiException.class);
    assertThat(count("evidence_conflicts")).isEqualTo(2);
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(count("ledger_journals")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT raw_body FROM evidence_conflicts LIMIT 1", String.class))
        .contains(payment.id());
  }
}
