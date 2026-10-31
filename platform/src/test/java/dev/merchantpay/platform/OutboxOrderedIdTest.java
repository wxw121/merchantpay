package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.merchantpay.platform.outbox.OutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxOrderedIdTest extends FinancialFixture {
  @Test
  void orderedOutboxIdsKeepThePermanentEventAndTaskLinked() {
    var firstTime = Instant.parse("2026-10-28T00:00:00Z");
    var nextTime = firstTime.plusMillis(1);
    var first = new OutboxService(jdbc, Clock.fixed(firstTime, ZoneOffset.UTC), true);
    var second = new OutboxService(jdbc, Clock.fixed(nextTime, ZoneOffset.UTC), true);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));

    tx.executeWithoutResult(
        ignored -> {
          first.enqueue("ordered:first", "CHANNEL_PAYMENT", "p_first", "merchant-a");
          second.enqueue("ordered:second", "CHANNEL_PAYMENT", "p_second", "merchant-a");
        });

    var ids = jdbc.queryForList("SELECT id FROM outbox_events ORDER BY event_id", String.class);
    assertThat(ids).hasSize(2);
    assertThat(UUID.fromString(ids.get(0)).version()).isEqualTo(7);
    assertThat(UUID.fromString(ids.get(1)).version()).isEqualTo(7);
    assertThat(ids.get(0)).isLessThan(ids.get(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks t JOIN outbox_events e ON e.id=t.id AND e.event_id=t.event_id",
                Long.class))
        .isEqualTo(2);
  }
}
