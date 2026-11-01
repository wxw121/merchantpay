package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.web.Access;
import dev.merchantpay.platform.web.OperationsController;
import java.sql.Timestamp;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

class V2RecoveryPolicyTest extends FinancialFixture {
  Instant now;

  @org.junit.jupiter.api.BeforeEach
  void recoveryClock() {
    now = Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  TaskQueue queue(Instant instant) {
    return new TaskQueue(
        jdbc,
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  @Test
  void firstUnknownTimeSurvivesRepeatedObservationsAndCloseRequests() {
    var payment = createPayment("unknown-age", "100");
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    var since = timestamp("payments", payment.id(), "unknown_since");
    assertThat(since).isNotNull();
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "timeout again"));
    payments.requestClose("merchant-a", payment.id());
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "close timeout"));
    assertThat(timestamp("payments", payment.id(), "unknown_since")).isEqualTo(since);
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("CLOSE_PENDING");

    var paid = paid("unknown-refund-age", "100");
    var refund =
        refunds.create(
            "merchant-a",
            paid.id(),
            new dev.merchantpay.platform.refund.RefundService.CreateRefund("refund-age", "40"));
    refunds.applyResult(refund.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    var refundSince = timestamp("refunds", refund.id(), "unknown_since");
    assertThat(refundSince).isNotNull();
    refunds.applyResult(refund.id(), new ChannelResult.Unknown("MOCK_A", "again"));
    assertThat(timestamp("refunds", refund.id(), "unknown_since")).isEqualTo(refundSince);
    assertThat(payments.get("merchant-a", paid.id()).reservedMinor()).isEqualTo("40");
  }

  @Test
  void closingAnOrderBeforeTheFirstTimeoutStillRecordsItsFirstUnknownTime() {
    var payment = createPayment("close-timeout", "100");
    payments.requestClose("merchant-a", payment.id());
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "close timeout"));
    assertThat(timestamp("payments", payment.id(), "unknown_since")).isNotNull();
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("CLOSE_PENDING");
  }

  @Test
  void channelRecoveryDoesNotStopAtTwelveAttemptsAndBecomesLongTerm() {
    var payment = createPayment("long-recovery", "100");
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    jdbc.update(
        "UPDATE payments SET unknown_since=? WHERE id=?",
        Timestamp.from(now.minus(Duration.ofHours(24))),
        payment.id());
    jdbc.update("UPDATE outbox_tasks SET attempts=11");
    var queue = queue(now);
    var task = queue.claim().orElseThrow();
    queue.retry(task, new IllegalStateException("still unknown"));
    assertThat(value(task.id(), "status")).isEqualTo("PENDING");
    assertThat(value(task.id(), "recovery_stage")).isEqualTo("LONG_TERM");
    var due = timestamp("outbox_tasks", task.id(), "due_at").toInstant();
    assertThat(Duration.between(now, due))
        .isBetween(Duration.ofMillis(150000), Duration.ofSeconds(300));
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("UNKNOWN");
    queue.retry(task, new IllegalStateException("stale owner"));
    assertThat(timestamp("outbox_tasks", task.id(), "due_at").toInstant()).isEqualTo(due);
  }

  @Test
  void queryBackoffStartsAtOneSecondAndIsJitteredWithinFiveMinutes() {
    createPayment("query-jitter", "100");
    var queue = queue(now);
    var task = queue.claim().orElseThrow();
    queue.retry(task, new IllegalStateException("query"));
    assertThat(timestamp("outbox_tasks", task.id(), "due_at").toInstant())
        .isEqualTo(now.plusSeconds(1));
    var observed = new java.util.HashSet<Timestamp>();
    for (int i = 0; i < 24; i++) {
      jdbc.update(
          "UPDATE outbox_tasks SET status='RUNNING',lease_token=?,lease_until=?,attempts=12 WHERE id=?",
          task.token(),
          Timestamp.from(now.plusSeconds(60)),
          task.id());
      var retry =
          new TaskQueue.Task(
              task.id(),
              task.eventId(),
              task.kind(),
              task.aggregateId(),
              task.merchantId(),
              12,
              task.token());
      queue.retry(retry, new IllegalStateException("query again"));
      var due = timestamp("outbox_tasks", task.id(), "due_at");
      assertThat(Duration.between(now, due.toInstant()))
          .isBetween(Duration.ofSeconds(150), Duration.ofSeconds(300));
      observed.add(due);
    }
    assertThat(observed.size()).isGreaterThan(1);
  }

  @Test
  void notificationRetriesBeyondTwelveButCannotStartAtTheTwentyFourHourBoundary() {
    paid("notify-window", "100");
    jdbc.update(
        "UPDATE outbox_tasks SET attempts=11,retry_started_at=? WHERE queue_class='NOTIFY'",
        Timestamp.from(now.minus(Duration.ofHours(23))));
    var queue = queue(now);
    var task = queue.claimBatch(1, "NOTIFY").getFirst();
    assertThat(queue.notificationMayRun(task)).isTrue();
    queue.retry(task, new IllegalStateException("merchant unavailable"));
    assertThat(value(task.id(), "status")).isEqualTo("PENDING");
    var later = queue(now.plus(Duration.ofHours(1)));
    var reclaimed = later.claimBatch(1, "NOTIFY").getFirst();
    assertThat(later.notificationMayRun(reclaimed)).isFalse();
    assertThat(value(task.id(), "status")).isEqualTo("DEAD");
    assertThat(count("ledger_journals")).isEqualTo(1);
  }

  @Test
  void retryDueTimeNeverExceedsNotificationDeadlineAndOldLeaseCannotExpireNewOwner() {
    paid("notify-cap", "100");
    jdbc.update(
        "UPDATE outbox_tasks SET attempts=10,retry_started_at=? WHERE queue_class='NOTIFY'",
        Timestamp.from(now.minus(Duration.ofHours(24)).plusSeconds(1)));
    var queue = queue(now);
    var task = queue.claimBatch(1, "NOTIFY").getFirst();
    queue.retry(task, new IllegalStateException("unavailable"));
    assertThat(timestamp("outbox_tasks", task.id(), "due_at").toInstant())
        .isEqualTo(now.plusSeconds(1));
    var nextQueue = queue(now.plusSeconds(1));
    var current = nextQueue.claimBatch(1, "NOTIFY").getFirst();
    assertThat(nextQueue.notificationMayRun(task)).isFalse();
    assertThat(value(task.id(), "status")).isEqualTo("RUNNING");
    assertThat(nextQueue.notificationMayRun(current)).isFalse();
    assertThat(value(task.id(), "status")).isEqualTo("DEAD");
  }

  @Test
  void operatorCanFilterManualAndLongTermQueuesUsingStableUnknownAge() throws Exception {
    var manual = createPayment("manual", "100");
    var longTerm = createPayment("long-term", "100");
    for (var p : java.util.List.of(manual, longTerm))
      payments.applyResult(p.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    jdbc.update(
        "UPDATE payments SET unknown_since=? WHERE id=?",
        Timestamp.from(now.minusSeconds(1800)),
        manual.id());
    jdbc.update(
        "UPDATE payments SET unknown_since=? WHERE id=?",
        Timestamp.from(now.minusSeconds(86400)),
        longTerm.id());
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new OperationsController(
                    new Access(), null, null, jdbc, payments, Clock.fixed(now, ZoneOffset.UTC)))
            .build();
    ScopedValue.where(Access.CURRENT, new Access.Caller("ops", null, Set.of("OPS")))
        .call(
            () -> {
              mvc.perform(
                      get("/api/console/operations/unknown")
                          .param("merchantId", "merchant-a")
                          .param("stage", "MANUAL"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.length()").value(1))
                  .andExpect(jsonPath("$[0].id").value(manual.id()))
                  .andExpect(jsonPath("$[0].recoveryStage").value("MANUAL"));
              mvc.perform(
                      get("/api/console/operations/unknown")
                          .param("merchantId", "merchant-a")
                          .param("stage", "LONG_TERM"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$[0].id").value(longTerm.id()));
              return null;
            });
  }

  @Test
  void evidenceConflictStillQuarantinesChannelDelivery() {
    createPayment("conflicting-recovery", "100");
    var queue = queue(now);
    var task = queue.claim().orElseThrow();
    queue.retry(
        task,
        new dev.merchantpay.platform.common.ApiException(
            "CHANNEL_EVIDENCE_CONFLICT", 409, "conflict"));
    assertThat(value(task.id(), "status")).isEqualTo("DEAD");
  }

  Timestamp timestamp(String table, String id, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id=?", Timestamp.class, id);
  }

  String value(String id, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM outbox_tasks WHERE id=?", String.class, id);
  }
}
