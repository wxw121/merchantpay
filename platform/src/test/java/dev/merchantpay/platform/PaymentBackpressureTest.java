package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.payment.PaymentService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class PaymentBackpressureTest extends FinancialFixture {
  @Test
  void postingDoesNotHoldTheSharedAdmissionRowWhileWritingItsLedger() throws Exception {
    var orders = sameBucket(2);
    var first = createPayment(orders.getFirst(), "10000");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var ledger = org.mockito.Mockito.spy(new dev.merchantpay.platform.ledger.LedgerService(jdbc));
    org.mockito.Mockito.doAnswer(
            call -> {
              entered.countDown();
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("test ledger timeout");
              return call.callRealMethod();
            })
        .when(ledger)
        .postPayment(
            org.mockito.ArgumentMatchers.eq(first.id()),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any());
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    var posting =
        new PaymentService(
            jdbc,
            tx,
            ledger,
            new dev.merchantpay.platform.outbox.OutboxService(jdbc, java.time.Clock.systemUTC()),
            java.time.Clock.systemUTC(),
            16);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var result =
            executor.submit(
                () ->
                    posting.applyResult(
                        first.id(),
                        new ChannelResult.Success("MOCK_A", "late-release", 10000, "CNY")));
        assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var created = executor.submit(() -> createPayment(orders.get(1), "10000"));
        assertThat(created.get(3, java.util.concurrent.TimeUnit.SECONDS).status())
            .isEqualTo("PROCESSING");
        release.countDown();
        assertThat(result.get(3, java.util.concurrent.TimeUnit.SECONDS).status())
            .isEqualTo("SUCCEEDED");
      } finally {
        release.countDown();
      }
    }
    assertThat(inflight()).isEqualTo(1);
    assertThat(count("ledger_journals")).isEqualTo(1);
  }

  @Test
  void newPaymentUsesNoPaymentReadsAndReturnsTheCommittedRow() {
    var paymentReads = new java.util.concurrent.atomic.AtomicInteger();
    var observed =
        new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public <T> List<T> query(
              String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
            if (sql.contains("FROM payments")) paymentReads.incrementAndGet();
            return super.query(sql, mapper, args);
          }
        };
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    var service =
        new PaymentService(
            observed,
            tx,
            new dev.merchantpay.platform.ledger.LedgerService(observed),
            new dev.merchantpay.platform.outbox.OutboxService(
                observed, java.time.Clock.systemUTC()),
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-10-22T00:00:00.123456789Z"),
                java.time.ZoneOffset.UTC),
            16);
    var created =
        service.create(
            "merchant-a",
            new PaymentService.CreatePayment(
                "new-order-read-budget", "10000", "CNY", "MOCK_A", null));
    assertThat(paymentReads.get()).as("payment SELECTs on an uncontended new order").isZero();
    assertThat(payments.get("merchant-a", created.id())).isEqualTo(created);
    jdbc.update("UPDATE merchants SET enabled=FALSE WHERE id='merchant-a'");
    assertThat(
            service.create(
                "merchant-a",
                new PaymentService.CreatePayment(
                    "new-order-read-budget", "10000", "CNY", "MOCK_A", null)))
        .isEqualTo(created);
    assertThatThrownBy(
            () ->
                service.create(
                    "merchant-a",
                    new PaymentService.CreatePayment(
                        "new-order-read-budget", "10001", "CNY", "MOCK_A", null)))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    assertThatThrownBy(
            () ->
                service.create(
                    "merchant-a",
                    new PaymentService.CreatePayment(
                        "disabled-new", "10000", "CNY", "MOCK_A", null)))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo("MERCHANT_DISABLED"));
  }

  List<String> sameBucket(int count) {
    var result = new ArrayList<String>();
    for (int i = 0; result.size() < count; i++) {
      String order = "capacity-" + i;
      if ((Integer.parseInt(PaymentService.sha256("merchant-a\0" + order).substring(0, 2), 16) & 15)
          == 0) result.add(order);
    }
    return result;
  }

  @Test
  void fullBucketRejectsOnlyNewPaymentsAndTerminalOutcomeReopensCapacity() {
    var orders = sameBucket(18);
    var first = createPayment(orders.getFirst(), "10000");
    for (int i = 1; i < 16; i++) createPayment(orders.get(i), "10000");
    assertThatThrownBy(() -> createPayment(orders.get(16), "10000"))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.status()).isEqualTo(503);
              assertThat(error.retryReason()).isEqualTo(ApiException.RetryReason.PAYMENT_CAPACITY);
              assertThat(error.code()).isEqualTo("RETRY_WITH_SAME_BUSINESS_KEY");
            });
    assertThat(count("payments")).isEqualTo(16);
    assertThat(count("outbox_tasks")).isEqualTo(16);
    assertThat(createPayment(orders.getFirst(), "10000").id()).isEqualTo(first.id());
    assertThatThrownBy(() -> createPayment(orders.getFirst(), "10001"))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    assertThat(payments.get("merchant-a", first.id()).status()).isEqualTo("PROCESSING");
    payments.requestClose("merchant-a", first.id());
    payments.applyResult(first.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    assertThatThrownBy(() -> createPayment(orders.get(16), "10000"))
        .isInstanceOf(ApiException.class);
    payments.applyResult(first.id(), new ChannelResult.Closed("MOCK_A"));
    payments.applyResult(first.id(), new ChannelResult.Closed("MOCK_A"));
    assertThat(createPayment(orders.get(16), "10000").status()).isEqualTo("PROCESSING");
    assertThatThrownBy(() -> createPayment(orders.get(17), "10000"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void fullBucketRejectsNewOrderBeforeInsertingPaymentRow() {
    var orders = sameBucket(17);
    for (int i = 0; i < 16; i++) createPayment(orders.get(i), "10000");
    var insertAttempts = new java.util.concurrent.atomic.AtomicInteger();
    var observed =
        new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO payments(")) insertAttempts.incrementAndGet();
            return super.update(sql, args);
          }
        };
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    var service =
        new PaymentService(
            observed,
            tx,
            new dev.merchantpay.platform.ledger.LedgerService(observed),
            new dev.merchantpay.platform.outbox.OutboxService(
                observed, java.time.Clock.systemUTC()),
            java.time.Clock.systemUTC(),
            16);

    assertThatThrownBy(
            () ->
                service.create(
                    "merchant-a",
                    new PaymentService.CreatePayment(
                        orders.get(16), "10000", "CNY", "MOCK_A", null)))
        .isInstanceOfSatisfying(
            ApiException.class,
            error ->
                assertThat(error.retryReason())
                    .isEqualTo(ApiException.RetryReason.PAYMENT_CAPACITY));
    assertThat(insertAttempts.get()).isZero();
    assertThat(
            service
                .create(
                    "merchant-a",
                    new PaymentService.CreatePayment(
                        orders.getFirst(), "10000", "CNY", "MOCK_A", null))
                .id())
        .isNotBlank();
    assertThat(insertAttempts.get()).isZero();
  }

  @Test
  void capacityChangesRollBackWithCreationAndPostingAndReleaseExactlyOnce() {
    var payment = createPayment("rollback-capacity", "10000");
    jdbc.execute(
        "ALTER TABLE outbox_tasks ADD CONSTRAINT reject_success_capacity CHECK (kind <> 'PAYMENT_SUCCEEDED')");
    assertThatThrownBy(
            () ->
                payments.applyResult(
                    payment.id(),
                    new ChannelResult.Success("MOCK_A", "receipt-capacity", 10000, "CNY")))
        .isInstanceOf(RuntimeException.class);
    assertThat(inflight()).isEqualTo(1);
    assertThat(count("ledger_journals")).isZero();
    jdbc.execute("ALTER TABLE outbox_tasks DROP CONSTRAINT reject_success_capacity");
    payments.applyResult(
        payment.id(), new ChannelResult.Success("MOCK_A", "receipt-capacity", 10000, "CNY"));
    payments.applyResult(
        payment.id(), new ChannelResult.Success("MOCK_A", "receipt-capacity", 10000, "CNY"));
    assertThat(inflight()).isZero();
    jdbc.execute(
        "ALTER TABLE outbox_tasks ADD CONSTRAINT reject_creation_capacity CHECK (kind <> 'CHANNEL_PAYMENT' OR merchant_id <> 'merchant-b')");
    assertThatThrownBy(
            () ->
                payments.create(
                    "merchant-b",
                    new PaymentService.CreatePayment(
                        "rollback-new", "10000", "CNY", "MOCK_A", null)))
        .isInstanceOf(RuntimeException.class);
    assertThat(inflight()).isZero();
    assertThat(count("payments")).isEqualTo(1);
  }

  @Test
  void concurrentInstancesCannotOverbookAndRestartDoesNotResetCapacity() throws Exception {
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    var other =
        new PaymentService(
            jdbc,
            tx,
            new dev.merchantpay.platform.ledger.LedgerService(jdbc),
            new dev.merchantpay.platform.outbox.OutboxService(jdbc, java.time.Clock.systemUTC()),
            java.time.Clock.systemUTC(),
            16);
    var orders = sameBucket(40);
    int accepted = 0;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var work = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < orders.size(); i++) {
        String order = orders.get(i);
        var service = i % 2 == 0 ? payments : other;
        work.add(
            executor.submit(
                () -> {
                  try {
                    service.create(
                        "merchant-a",
                        new PaymentService.CreatePayment(order, "10000", "CNY", "MOCK_A", null));
                    return true;
                  } catch (ApiException rejection) {
                    assertThat(rejection.status()).isEqualTo(503);
                    return false;
                  }
                }));
      }
      for (var future : work) if (future.get(20, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
    }
    assertThat(accepted).isEqualTo(16);
    assertThat(inflight()).isEqualTo(16);
    assertThat(count("payments")).isEqualTo(16);
    assertThat(count("outbox_tasks")).isEqualTo(16);
    assertThatThrownBy(
            () ->
                other.create(
                    "merchant-a",
                    new PaymentService.CreatePayment(
                        sameBucket(41).getLast(), "10000", "CNY", "MOCK_A", null)))
        .isInstanceOf(ApiException.class);
  }

  long inflight() {
    return jdbc.queryForObject("SELECT SUM(inflight) FROM payment_admission", Long.class);
  }

  @Test
  void failedOutcomeAndDuplicateCreationDoNotLeakOrDoubleReleaseCapacity() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new ArrayList<java.util.concurrent.Future<PaymentService.PaymentView>>();
      for (int i = 0; i < 20; i++)
        results.add(
            executor.submit(
                () -> {
                  // InnoDB may abort a unique-key lock upgrade; clients retry the same business
                  // key.
                  for (int attempt = 0; ; attempt++) {
                    try {
                      return createPayment("same-business", "10000");
                    } catch (org.springframework.dao.TransientDataAccessException conflict) {
                      if (attempt >= 19) throw conflict;
                      Thread.sleep(5);
                    }
                  }
                }));
      String id = results.getFirst().get().id();
      for (var result : results) assertThat(result.get().id()).isEqualTo(id);
      assertThat(inflight()).isEqualTo(1);
      payments.applyResult(id, new ChannelResult.Failure("MOCK_A", "DECLINED"));
      payments.applyResult(id, new ChannelResult.Failure("MOCK_A", "DECLINED"));
      assertThat(inflight()).isZero();
      assertThat(count("payments")).isEqualTo(1);
    }
  }

  @Test
  void migrationCountsExistingUnknownAndCloseIntentWithoutDiscardingOverflow() throws Exception {
    var orders = sameBucket(18);
    for (int i = 0; i < 16; i++) createPayment(orders.get(i), "10000");
    var first = createPayment(orders.getFirst(), "10000");
    payments.applyResult(first.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    payments.requestClose("merchant-a", first.id());
    // Simulate a pre-upgrade backlog exceeding the new limit, without creating new money.
    jdbc.update("UPDATE payment_admission SET inflight=0");
    createPayment(orders.get(16), "10000");
    jdbc.execute("DROP TABLE payment_admission");
    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
        schemaConnection,
        new org.springframework.core.io.ClassPathResource(
            "db/migration/V12__payment_backpressure.sql"));
    assertThat(inflight()).isEqualTo(17);
    assertThatThrownBy(() -> createPayment(orders.get(17), "10000"))
        .isInstanceOf(ApiException.class);
    payments.applyResult(first.id(), new ChannelResult.Closed("MOCK_A"));
    assertThat(inflight()).isEqualTo(16);
    assertThatThrownBy(() -> createPayment(orders.get(17), "10000"))
        .isInstanceOf(ApiException.class);
    var second = createPayment(orders.get(1), "10000");
    payments.applyResult(second.id(), new ChannelResult.Failure("MOCK_A", "DECLINED"));
    assertThat(createPayment(orders.get(17), "10000").status()).isEqualTo("PROCESSING");
    assertThat(inflight()).isEqualTo(16);
  }
}
