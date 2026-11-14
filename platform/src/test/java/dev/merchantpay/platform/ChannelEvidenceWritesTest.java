package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.payment.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

class ChannelEvidenceWritesTest extends FinancialFixture {
  int audit(String id) {
    return jdbc.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,'merchant-a','test','EVIDENCE',?,'test',CURRENT_TIMESTAMP)",
        id,
        id);
  }

  DataSourceTransactionManager manager() {
    return new DataSourceTransactionManager(jdbc.getDataSource());
  }

  ChannelEvidenceWrites writer(DataSourceTransactionManager manager, int maximum) {
    return new ChannelEvidenceWrites(
        manager, new SimpleMeterRegistry(), maximum, Duration.ofMillis(50));
  }

  List<Future<Integer>> pair(
      ExecutorService executor, ChannelEvidenceWrites writer, IntSupplier a, IntSupplier b)
      throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var first =
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              return writer.execute("a", a);
            });
    var second =
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              return writer.execute("z", b);
            });
    assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    return List.of(first, second);
  }

  @Test
  void twoIndependentWritesShareOneCommitAndReturnTheirOwnCounts() throws Exception {
    var commits = new AtomicInteger();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            commits.incrementAndGet();
          }
        };
    try (var writer = writer(manager, 64);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fs =
          pair(
              executor,
              writer,
              () -> audit("one"),
              () -> {
                audit("two");
                return 7;
              });
      assertThat(fs.get(0).get(3, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(fs.get(1).get(3, TimeUnit.SECONDS)).isEqualTo(7);
    }
    assertThat(count("audit_records")).isEqualTo(2);
    assertThat(commits.get()).isEqualTo(1);
  }

  @Test
  void commitMustFinishBeforeAnyCallerReturns() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            entered.countDown();
            try {
              if (!release.await(3, TimeUnit.SECONDS))
                throw new IllegalStateException("commit gate");
            } catch (InterruptedException e) {
              throw new IllegalStateException(e);
            }
            super.doCommit(status);
          }
        };
    try (var writer = writer(manager, 64);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fs = pair(executor, writer, () -> audit("one"), () -> audit("two"));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(fs).allMatch(f -> !f.isDone());
        assertThat(count("audit_records")).isZero();
      } finally {
        release.countDown();
      }
      for (var f : fs) assertThat(f.get(3, TimeUnit.SECONDS)).isEqualTo(1);
    }
  }

  @Test
  void oneFailureRollsBackAllWithoutReplayingTheValidAction() throws Exception {
    var calls = new AtomicInteger();
    try (var writer = writer(manager(), 64);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fs =
          pair(
              executor,
              writer,
              () -> {
                calls.incrementAndGet();
                return audit("one");
              },
              () -> {
                throw new IllegalStateException("injected failure");
              });
      for (var f : fs)
        assertThatThrownBy(() -> f.get(3, TimeUnit.SECONDS))
            .hasCauseInstanceOf(IllegalStateException.class);
    }
    assertThat(calls.get()).isEqualTo(1);
    assertThat(count("audit_records")).isZero();
  }

  @Test
  void lostCommitAcknowledgementFailsCallersEvenIfWritesWereCommitted() throws Exception {
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            throw new TransactionSystemException("lost commit ack");
          }
        };
    try (var writer = writer(manager, 64);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fs = pair(executor, writer, () -> audit("one"), () -> audit("two"));
      for (var f : fs)
        assertThatThrownBy(() -> f.get(3, TimeUnit.SECONDS))
            .hasCauseInstanceOf(TransactionSystemException.class);
    }
    assertThat(count("audit_records")).isEqualTo(2);
  }

  @Test
  void singleItemModePreservesOriginalOuterTransaction() {
    try (var writer = writer(manager(), 1)) {
      new TransactionTemplate(manager())
          .executeWithoutResult(
              status -> {
                assertThat(writer.execute("a", () -> audit("one"))).isEqualTo(1);
                status.setRollbackOnly();
              });
    }
    assertThat(count("audit_records")).isZero();
  }

  @Test
  void enabledGroupsRejectAnOuterTransactionBeforeExecuting() {
    var calls = new AtomicInteger();
    try (var writer = writer(manager(), 64)) {
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(manager())
                      .executeWithoutResult(status -> writer.execute("a", calls::incrementAndGet)))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(calls.get()).isZero();
  }

  @Test
  void closedWriterRejectsNewActions() {
    var writer = writer(manager(), 64);
    writer.close();
    var calls = new AtomicInteger();
    assertThatThrownBy(() -> writer.execute("a", calls::incrementAndGet))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls.get()).isZero();
  }

  @Test
  void interruptedLeaderFailsBeforeJdbcAndKeepsTheInterruptFlag() {
    try (var writer = writer(manager(), 64)) {
      Thread.currentThread().interrupt();
      try {
        assertThatThrownBy(() -> writer.execute("a", () -> audit("one")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
    }
    assertThat(count("audit_records")).isZero();
  }

  @Test
  void invalidSizesAndDelayAreRejected() {
    for (int size : List.of(0, 65))
      assertThatThrownBy(() -> writer(manager(), size))
          .isInstanceOf(IllegalArgumentException.class);
    for (var delay : List.of(Duration.ofMillis(-1), Duration.ofMillis(51)))
      assertThatThrownBy(
              () -> new ChannelEvidenceWrites(manager(), new SimpleMeterRegistry(), 2, delay))
          .isInstanceOf(IllegalArgumentException.class);
  }

  int prepare(String id) {
    return jdbc.update(
        "INSERT IGNORE INTO channel_attempts(id,aggregate_id,payment_id,channel,operation,request_key,status,created_at,updated_at) VALUES (?,'p','p','MOCK_A','CHANNEL_PAYMENT','request','PREPARED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        id);
  }

  @Test
  void duplicatePreparationKeepsTheIndividualInsertDecision() throws Exception {
    try (var writer = writer(manager(), 64);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fs = pair(executor, writer, () -> prepare("first"), () -> prepare("second"));
      assertThat(fs.get(0).get(3, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(fs.get(1).get(3, TimeUnit.SECONDS)).isZero();
    }
    assertThat(count("channel_attempts")).isEqualTo(1);
  }

  String independentEvidence(String column, String payment) {
    try (var connection = jdbc.getDataSource().getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT "
                    + column
                    + " FROM channel_attempts WHERE aggregate_id=? AND operation='CHANNEL_PAYMENT'")) {
      statement.setQueryTimeout(2);
      statement.setString(1, payment);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  TaskQueue.Task task(String order) {
    createPayment(order, "10000");
    return new TaskQueue(
            jdbc,
            new TransactionTemplate(manager()),
            Clock.offset(Clock.systemUTC(), Duration.ofMinutes(1)))
        .claim()
        .orElseThrow();
  }

  ChannelGateway gateway(ChannelApi api, PaymentService observed, ChannelEvidenceWrites writer) {
    var tx = new TransactionTemplate(manager());
    var metrics = new SimpleMeterRegistry();
    return new ChannelGateway(
        api,
        observed,
        refunds,
        jdbc,
        JsonMapper.builder().build(),
        "test",
        Clock.systemUTC(),
        tx,
        new ChannelResultBatcher(manager(), metrics, 1, Duration.ZERO),
        metrics,
        writer);
  }

  @Test
  void gatewayRequiresCommittedPreparationBeforeNetworkAndCommittedReceiptBeforeMoney() {
    var task = task("evidence-order");
    var api = mock(ChannelApi.class);
    var applied = new AtomicInteger();
    when(api.pay(eq("MOCK_A"), eq("test"), any()))
        .thenAnswer(
            invocation -> {
              assertThat(independentEvidence("status", task.aggregateId())).isEqualTo("PREPARED");
              return new ChannelApi.Receipt(
                  task.aggregateId(),
                  "PAYMENT",
                  "MOCK_A",
                  "SUCCEEDED",
                  "receipt-" + task.aggregateId(),
                  "10000",
                  "CNY",
                  "60");
            });
    var observed =
        new PaymentService(
            jdbc,
            new TransactionTemplate(manager()),
            new LedgerService(jdbc),
            new OutboxService(jdbc, Clock.systemUTC()),
            Clock.systemUTC(),
            16) {
          @Override
          public PaymentView applyResult(String id, ChannelResult result) {
            assertThat(independentEvidence("response_body", id)).contains("SUCCEEDED");
            applied.incrementAndGet();
            return super.applyResult(id, result);
          }
        };
    try (var writer = writer(manager(), 64)) {
      gateway(api, observed, writer).execute(task);
    }
    assertThat(applied.get()).isEqualTo(1);
    verify(api).pay(eq("MOCK_A"), eq("test"), any());
    assertThat(payments.get("merchant-a", task.aggregateId()).status()).isEqualTo("SUCCEEDED");
    assertThat(count("ledger_journals")).isEqualTo(1);
  }

  @Test
  void preparationCommitUnknownNeverPermitsTheNetworkCall() {
    var task = task("unknown-prepare");
    var api = mock(ChannelApi.class);
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            throw new TransactionSystemException("lost commit ack");
          }
        };
    try (var writer = writer(manager, 64)) {
      assertThatThrownBy(() -> gateway(api, payments, writer).execute(task))
          .isInstanceOf(TransactionSystemException.class);
    }
    verifyNoInteractions(api);
    assertThat(independentEvidence("status", task.aggregateId())).isEqualTo("PREPARED");
    assertThat(payments.get("merchant-a", task.aggregateId()).status()).isEqualTo("PROCESSING");
    assertThat(count("ledger_journals")).isZero();
  }

  @Test
  void receiptCommitUnknownKeepsRawEvidenceAndDoesNotApplySuccessfulMoney() {
    var task = task("unknown-receipt");
    var api = mock(ChannelApi.class);
    var commits = new AtomicInteger();
    when(api.pay(eq("MOCK_A"), eq("test"), any()))
        .thenReturn(
            new ChannelApi.Receipt(
                task.aggregateId(),
                "PAYMENT",
                "MOCK_A",
                "SUCCEEDED",
                "receipt-unknown",
                "10000",
                "CNY",
                "60"));
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (commits.incrementAndGet() == 2)
              throw new TransactionSystemException("lost receipt ack");
          }
        };
    try (var writer = writer(manager, 64)) {
      assertThatThrownBy(() -> gateway(api, payments, writer).execute(task))
          .isInstanceOf(TransactionSystemException.class);
    }
    verify(api).pay(eq("MOCK_A"), eq("test"), any());
    assertThat(independentEvidence("response_body", task.aggregateId())).contains("SUCCEEDED");
    assertThat(payments.get("merchant-a", task.aggregateId()).status()).isEqualTo("UNKNOWN");
    assertThat(count("ledger_journals")).isZero();
  }
}
