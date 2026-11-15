package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.*;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class DomainEventTest extends FinancialFixture {
  TransactionTemplate tx;
  EventJournal journal;
  OutboxService events;

  @BeforeEach
  void eventSchema() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V18__reliable_domain_events.sql"));
    tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    journal = new EventJournal(jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "POLLING");
    journal.verifyTransport();
    events = new OutboxService(jdbc, Clock.systemUTC(), false, journal);
  }

  @Test
  void eventRegistryAndTaskCommitTogetherWithVersionedContract() {
    org.slf4j.MDC.put("requestId", "trace-fixture");
    try {
      tx.executeWithoutResult(
          t -> events.enqueue("payment-success:one", "PAYMENT_SUCCEEDED", "p0_one", "merchant-a"));
    } finally {
      org.slf4j.MDC.remove("requestId");
    }
    assertThat(count("domain_events")).isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
    String payload = jdbc.queryForObject("SELECT payload FROM domain_events", String.class);
    var event = JsonMapper.builder().build().readValue(payload, DomainEvent.class);
    assertThat(event.schemaVersion()).isEqualTo(1);
    assertThat(event.aggregateVersion()).isEqualTo(1);
    assertThat(event.eventId()).isEqualTo("payment-success:one");
    assertThat(event.merchantId()).isEqualTo("merchant-a");
    assertThat(event.traceId()).isEqualTo("trace-fixture");
    assertThat(jdbc.queryForObject("SELECT delivery_mode FROM domain_events", String.class))
        .isEqualTo("POLLING");
  }

  @Test
  void rollbackLeavesNoEventRegistryTaskOrAggregateVersion() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    t -> {
                      events.enqueue("rollback", "PAYMENT_SUCCEEDED", "p0_one", "merchant-a");
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    for (String table :
        new String[] {"domain_events", "domain_event_versions", "outbox_tasks", "outbox_events"}) {
      assertThat(count(table)).as(table).isZero();
    }
  }

  @Test
  void cdcEventIsDurableButWaitsForConsumerBeforeScheduling() {
    jdbc.update("UPDATE event_transport SET mode='CDC' WHERE id=1");
    var cdc = new EventJournal(jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "CDC");
    cdc.verifyTransport();
    var producer = new OutboxService(jdbc, Clock.systemUTC(), false, cdc);
    tx.executeWithoutResult(
        t -> producer.enqueue("cdc", "PAYMENT_SUCCEEDED", "p0_one", "merchant-a"));
    assertThat(count("domain_events")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
    assertThat(count("outbox_tasks")).isZero();
    assertThat(jdbc.queryForObject("SELECT delivery_mode FROM domain_events", String.class))
        .isEqualTo("CDC");
  }

  @Test
  void configuredWritersCannotMixTransportModes() {
    jdbc.update("UPDATE event_transport SET mode='CDC' WHERE id=1");
    assertThatThrownBy(journal::verifyTransport).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    t -> events.enqueue("wrong-mode", "PAYMENT_SUCCEEDED", "p0_one", "merchant-a")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("outbox_events")).isZero();
  }

  @Test
  void aggregateVersionsAdvanceInsideSameTransaction() {
    tx.executeWithoutResult(
        t -> {
          events.enqueue("first", "PAYMENT_SUCCEEDED", "p0_one", "merchant-a");
          events.enqueue("second", "PAYMENT_FAILED", "p0_one", "merchant-a");
          events.enqueue("other", "PAYMENT_SUCCEEDED", "p1_two", "merchant-a");
        });
    assertThat(
            jdbc.queryForList(
                "SELECT aggregate_version FROM domain_events WHERE aggregateid='p0_one' ORDER BY sequence_no",
                Long.class))
        .containsExactly(1L, 2L);
    assertThat(
            jdbc.queryForObject(
                "SELECT aggregate_version FROM domain_events WHERE aggregateid='p1_two'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void journalRequiresExistingBusinessTransaction() {
    assertThatThrownBy(
            () -> journal.append("id", "evt", "PAYMENT_SUCCEEDED", "p0_one", "merchant-a"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("domain_events")).isZero();
  }
}
