package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.outbox.*;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventDeliveryTest extends DomainEventTest {
  EventDelivery delivery;
  final Actor ops = new Actor("operator", null, Set.of("OPS"));

  @BeforeEach
  void consumer() {
    delivery = new EventDelivery(jdbc, tx, Clock.systemUTC(), JsonMapper.builder().build());
  }

  String emit(String id, String kind) {
    jdbc.update("UPDATE event_transport SET mode='CDC' WHERE id=1");
    var cdc = new EventJournal(jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "CDC");
    var outbox = new OutboxService(jdbc, Clock.systemUTC(), false, cdc);
    tx.executeWithoutResult(t -> outbox.enqueue(id, kind, "p0_one", "merchant-a"));
    return jdbc.queryForObject(
        "SELECT payload FROM domain_events WHERE event_id=?", String.class, id);
  }

  @Test
  void delayedCdcCannotRestartTheOriginalNotificationWindow() {
    String body = emit("delayed-notification", "PAYMENT_SUCCEEDED");
    var event = JsonMapper.builder().build().readValue(body, DomainEvent.class);
    var occurred = java.time.Instant.parse(event.occurredAt());
    var received =
        occurred
            .plus(java.time.Duration.ofHours(25))
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var delayedClock = Clock.fixed(received, java.time.ZoneOffset.UTC);
    new EventDelivery(jdbc, tx, delayedClock, JsonMapper.builder().build())
        .deliver("events", 0, 1, body);
    var started =
        jdbc.queryForObject("SELECT retry_started_at FROM outbox_tasks", java.sql.Timestamp.class)
            .toInstant();
    assertThat(java.time.Duration.between(occurred, started).abs())
        .isLessThan(java.time.Duration.ofMillis(1));
    var queue = new TaskQueue(jdbc, tx, delayedClock);
    var task = queue.claimBatch(1, "NOTIFY").getFirst();
    assertThat(queue.notificationMayRun(task)).isFalse();
    assertThat(jdbc.queryForObject("SELECT status FROM outbox_tasks", String.class))
        .isEqualTo("DEAD");
  }

  @Test
  void repeatedKafkaRecordSchedulesOneDurableTask() {
    String body = emit("event-one", "PAYMENT_SUCCEEDED");
    delivery.deliver("events", 0, 1, body);
    delivery.deliver("events", 0, 2, body);
    assertThat(count("event_receipts")).isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(1);
  }

  @Test
  void pollingSnapshotAndDescriptiveFinanceEventDoNotCreateDuplicateTasks() {
    tx.executeWithoutResult(
        t -> events.enqueue("polling", "PAYMENT_SUCCEEDED", "p0_one", "merchant-a"));
    String polling =
        jdbc.queryForObject(
            "SELECT payload FROM domain_events WHERE event_id='polling'", String.class);
    delivery.deliver("events", 0, 1, polling);
    String finance = emit("adjustment", "ADJUSTMENT_EXECUTED");
    delivery.deliver("events", 0, 2, finance);
    assertThat(count("event_receipts")).isEqualTo(2);
    assertThat(count("outbox_tasks")).isEqualTo(1);
  }

  @Test
  void crossTenantOrChangedContractCannotSchedule() {
    String body = emit("event-one", "PAYMENT_SUCCEEDED");
    assertThatThrownBy(
            () -> delivery.deliver("events", 0, 1, body.replace("merchant-a", "merchant-b")))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
    assertThatThrownBy(
            () ->
                delivery.deliver(
                    "events", 0, 2, body.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
    assertThat(count("event_receipts")).isZero();
    assertThat(count("outbox_tasks")).isZero();
  }

  @Test
  void databaseFailureRollsBackReceiptAndTaskAndCanBeRetried() {
    String body = emit("event-one", "PAYMENT_SUCCEEDED");
    jdbc.execute(
        "ALTER TABLE outbox_tasks ADD CONSTRAINT fail_new_tasks CHECK(kind<>'PAYMENT_SUCCEEDED')");
    assertThatThrownBy(() -> delivery.deliver("events", 0, 1, body))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(count("event_receipts")).isZero();
    jdbc.execute("ALTER TABLE outbox_tasks DROP CONSTRAINT fail_new_tasks");
    delivery.deliver("events", 0, 1, body);
    assertThat(count("outbox_tasks")).isEqualTo(1);
  }

  @Test
  void scalarCoercionCannotDisguiseUnsupportedContractVersions() {
    String body = emit("strict-contract", "PAYMENT_SUCCEEDED");
    for (String changed :
        java.util.List.of(
            body.replace("\"schemaVersion\":1", "\"schemaVersion\":1.5"),
            body.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            body.replace("\"aggregateVersion\":1", "\"aggregateVersion\":1.5"))) {
      assertThatThrownBy(() -> delivery.deliver("events", 0, 1, changed))
          .isInstanceOf(EventDelivery.InvalidEvent.class);
    }
    assertThat(count("event_receipts")).isZero();
  }

  @Test
  void durableQuarantineAndReplayRequireOpsAndPreserveIdentity() {
    String body = emit("event-one", "PAYMENT_SUCCEEDED");
    String id = delivery.quarantine("events", 0, 1, body, "temporary routing failure");
    assertThat(delivery.quarantine("events", 0, 1, body, "retry")).isEqualTo(id);
    assertThatThrownBy(() -> delivery.replay(id, new Actor("finance", null, Set.of("FINANCE"))))
        .isInstanceOf(dev.merchantpay.platform.common.ApiException.class);
    delivery.replay(id, ops);
    delivery.replay(id, ops);
    assertThat(count("outbox_tasks")).isEqualTo(1);
    assertThat(count("event_receipts")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT status FROM event_quarantine WHERE id=?", String.class, id))
        .isEqualTo("REPLAYED");
    assertThat(count("audit_records")).isEqualTo(1);
  }

  @Test
  void invalidQuarantineRetainsFailedReplayInsteadOfAcknowledgingAValidTask() {
    String id = delivery.quarantine("events", 0, 1, "not-json", "invalid JSON");
    var result = delivery.replay(id, ops);
    assertThat(result.get("status")).isEqualTo("FAILED");
    assertThat(count("event_receipts")).isZero();
    assertThat(count("outbox_tasks")).isZero();
  }

  @Test
  void concurrentConsumersCannotDuplicateTask() throws Exception {
    String body = emit("concurrent", "PAYMENT_SUCCEEDED");
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          java.util.stream.IntStream.range(0, 8)
              .mapToObj(n -> pool.submit(() -> delivery.deliver("events", 0, n, body)))
              .toList();
      for (var f : futures) f.get(10, TimeUnit.SECONDS);
    }
    assertThat(count("event_receipts")).isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(1);
  }
}
