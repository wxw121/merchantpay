package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventTransportTest extends DomainEventTest {
  @Test
  void registryRecoveryIncludesDomainOnlyEventsAndLegacyTaskFacts() throws Exception {
    tx.executeWithoutResult(
        t -> events.enqueue("finance", "ADJUSTMENT_EXECUTED", "adjustment", "merchant-a"));
    tx.executeWithoutResult(
        t ->
            new OutboxService(jdbc, Clock.systemUTC())
                .enqueue("legacy", "EXPORT", "export", "merchant-a"));
    jdbc.update("DELETE FROM outbox_events");
    assertThat(OutboxEventRegistryMaintenance.reconcile(jdbc.getDataSource()).inserted())
        .isEqualTo(2);
    assertThat(OutboxEventRegistryMaintenance.reconcile(jdbc.getDataSource()).inserted()).isZero();
    assertThat(count("outbox_tasks")).isEqualTo(1);
  }

  @Test
  void stoppedAndDrainedSwitchRecordsCutoffAndRejectsOldWriters() throws Exception {
    tx.executeWithoutResult(
        t -> events.enqueue("description", "ADJUSTMENT_EXECUTED", "a", "merchant-a"));
    try (var c = jdbc.getDataSource().getConnection()) {
      assertThatThrownBy(() -> EventTransportMaintenance.switchMode(c, "POLLING", "CDC", false))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(EventTransportMaintenance.switchMode(c, "POLLING", "CDC", true)).isEqualTo(1);
    }
    assertThatThrownBy(journal::verifyTransport).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void quarantineDoesNotMakeAnUndeliveredCdcTaskDrained() throws Exception {
    jdbc.update("UPDATE event_transport SET mode='CDC'");
    var cdc =
        new OutboxService(
            jdbc,
            Clock.systemUTC(),
            false,
            new EventJournal(jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "CDC"));
    tx.executeWithoutResult(t -> cdc.enqueue("waiting", "EXPORT", "e", "merchant-a"));
    try (var c = jdbc.getDataSource().getConnection()) {
      assertThatThrownBy(() -> EventTransportMaintenance.switchMode(c, "CDC", "POLLING", true))
          .isInstanceOf(IllegalStateException.class);
    }
    var delivery = new EventDelivery(jdbc, tx, Clock.systemUTC(), JsonMapper.builder().build());
    delivery.deliver(
        "topic", 0, 0, jdbc.queryForObject("SELECT payload FROM domain_events", String.class));
    try (var c = jdbc.getDataSource().getConnection()) {
      assertThatThrownBy(() -> EventTransportMaintenance.switchMode(c, "CDC", "POLLING", true))
          .isInstanceOf(IllegalStateException.class);
      jdbc.update("UPDATE outbox_tasks SET status='DONE'");
      assertThat(EventTransportMaintenance.switchMode(c, "CDC", "POLLING", true)).isEqualTo(1);
    }
  }
}
