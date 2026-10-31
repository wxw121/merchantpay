package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentAdmissionSlotsTest extends FinancialFixture {
  @BeforeEach
  void slotMode() {
    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
        schemaConnection,
        new org.springframework.core.io.ClassPathResource(
            "db/migration/V17__payment_admission_slots.sql"));
    configure(true);
  }

  void configure(boolean slots) {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    new ApplicationContextRunner()
        .withPropertyValues("merchantpay.payment-admission-slots=" + slots)
        .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> jdbc)
        .withBean(TransactionTemplate.class, () -> tx)
        .withBean(LedgerService.class, () -> new LedgerService(jdbc))
        .withBean(OutboxService.class, () -> new OutboxService(jdbc, Clock.systemUTC()))
        .withBean(Clock.class, Clock::systemUTC)
        .withUserConfiguration(PaymentService.class)
        .run(context -> payments = context.getBean(PaymentService.class));
  }

  @Test
  void switchingBackAfterMigratedPaymentsFinishRebuildsTheLegacyCapacity() {
    var orders = sameBucket(17);
    for (int i = 0; i < 16; i++) createPayment(orders.get(i), "100");
    // V17 can backfill a full preexisting bucket; its old counter remains until controlled switch.
    jdbc.update("UPDATE payment_admission SET inflight=16 WHERE logical_shard=0");
    var active = jdbc.queryForList("SELECT id FROM payments", String.class);
    for (var id : active) payments.applyResult(id, new ChannelResult.Failure("MOCK_A", "declined"));
    assertThat(occupied()).isZero();
    configure(false);
    assertThat(createPayment(orders.getLast(), "100").status()).isEqualTo("PROCESSING");
    assertThat(jdbc.queryForObject("SELECT SUM(inflight) FROM payment_admission", Long.class))
        .isEqualTo(1);
  }

  @Test
  void switchingBackWithAnUnknownPaymentFailsClosed() {
    var payment = createPayment(sameBucket(1).getFirst(), "100");
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    assertThatThrownBy(() -> configure(false)).hasStackTraceContaining("drained");
    assertThat(occupied()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT slot_mode FROM payment_admission_mode WHERE id=1", Boolean.class))
        .isTrue();
  }

  @Test
  void anotherCreationAndOutcomeDoNotNeedTheLegacySharedCounterLock() throws Exception {
    try (var blocker = jdbc.getDataSource().getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.executeUpdate(
            "UPDATE payment_admission SET inflight=inflight WHERE logical_shard=0");
      }
      try {
        var created = executor.submit(() -> createPayment(sameBucket(1).getFirst(), "100"));
        assertThat(created).succeedsWithin(Duration.ofSeconds(2));
        var payment = created.get();
        assertThat(occupied()).isEqualTo(1);
        var posted =
            executor.submit(
                () ->
                    payments.applyResult(
                        payment.id(),
                        new ChannelResult.Success("MOCK_A", "slots-success", 100, "CNY")));
        assertThat(posted).succeedsWithin(Duration.ofSeconds(2));
        assertThat(posted.get().status()).isEqualTo("SUCCEEDED");
        assertThat(occupied()).isZero();
        assertThat(count("ledger_journals")).isEqualTo(1);
      } finally {
        blocker.rollback();
      }
    }
  }

  @Test
  void fullBucketKeepsUnknownAndIdempotentOwnershipAndReusesOnlyTerminalSlots() {
    var orders = sameBucket(17);
    var first = createPayment(orders.getFirst(), "100");
    for (int i = 1; i < 16; i++) createPayment(orders.get(i), "100");
    assertThat(occupied()).isEqualTo(16);
    assertThat(createPayment(orders.getFirst(), "100").id()).isEqualTo(first.id());
    assertThatThrownBy(() -> createPayment(orders.getFirst(), "101"))
        .isInstanceOf(ApiException.class);
    payments.applyResult(first.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    assertThatThrownBy(() -> createPayment(orders.get(16), "100")).isInstanceOf(ApiException.class);
    assertThat(occupied()).isEqualTo(16);
    payments.applyResult(first.id(), new ChannelResult.Failure("MOCK_A", "declined"));
    payments.applyResult(first.id(), new ChannelResult.Failure("MOCK_A", "declined"));
    assertThat(createPayment(orders.get(16), "100").status()).isEqualTo("PROCESSING");
    assertThat(occupied()).isEqualTo(16);
  }

  @Test
  void concurrentClaimersNeverExceedSixteenOwnersOrDoubleClaimOnePayment() throws Exception {
    var orders = sameBucket(40);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (var order : orders)
        results.add(
            executor.submit(
                () -> {
                  try {
                    createPayment(order, "100");
                    return true;
                  } catch (ApiException full) {
                    assertThat(full.status()).isEqualTo(503);
                    return false;
                  }
                }));
      int accepted = 0;
      for (var result : results)
        if (result.get(15, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
      assertThat(accepted).isBetween(1, 16);
      assertThat(occupied()).isEqualTo(accepted);
      assertThat(count("payments")).isEqualTo(accepted);
      assertThat(count("outbox_tasks")).isEqualTo(accepted);
    }
  }

  @Test
  void failedCreationRollsBackSlotAndPaymentTogether() {
    jdbc.execute("DROP VIEW outbox_task_records");
    jdbc.execute("DROP TABLE outbox_tasks");
    assertThatThrownBy(() -> createPayment(sameBucket(1).getFirst(), "100"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(occupied()).isZero();
    assertThat(count("payments")).isZero();
  }

  long occupied() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_admission_slots WHERE payment_id IS NOT NULL", Long.class);
  }

  java.util.List<String> sameBucket(int count) {
    var orders = new ArrayList<String>();
    for (int i = 0; orders.size() < count; i++) {
      String order = "slots-" + i;
      if ((Integer.parseInt(PaymentService.sha256("merchant-a\0" + order).substring(0, 2), 16) & 15)
          == 0) orders.add(order);
    }
    return orders;
  }
}
