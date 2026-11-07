package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShardMigrationBatchProbeTest {
  @Test
  void routedPredicateIsGroupedBeforeKeysetCursor() {
    var probe =
        ShardMigrationPreflight.BUCKET_PROBES.stream()
            .filter(item -> item.name().equals("domain_events"))
            .findFirst()
            .orElseThrow();
    assertThat(probe.pageSql(true))
        .contains(
            "WHERE (logical_bucket=? OR LEFT(aggregateid,3) IN (?,?)) AND sequence_no>? ORDER BY sequence_no LIMIT ?");
    assertThat(probe.pageArgs(4, "123", null, 1000)).containsExactly(4, "p4_", "r4_", 123L, 1000);
  }

  @Test
  void compositeCursorRetainsBothKeyParts() {
    var probe =
        ShardMigrationPreflight.BUCKET_PROBES.stream()
            .filter(item -> item.name().equals("event_receipts"))
            .findFirst()
            .orElseThrow();
    assertThat(probe.pageSql(true))
        .contains(
            "OR EXISTS (SELECT 1 FROM v3_history_event_locators l WHERE l.event_id=r.event_id AND l.state='COLD' AND l.logical_bucket=?)) AND (r.consumer_name>? OR (r.consumer_name=? AND r.event_id>?)) ORDER BY r.consumer_name,r.event_id LIMIT ?");
    assertThat(probe.pageArgs(2, "consumer", "event", 1000))
        .containsExactly(2, "p2_", "r2_", 2, "consumer", "consumer", "event", 1000);
  }

  @Test
  void registryPredicateCannotEscapeTheCursor() {
    var probe =
        ShardMigrationPreflight.BUCKET_PROBES.stream()
            .filter(item -> item.name().equals("outbox_events"))
            .findFirst()
            .orElseThrow();
    assertThat(probe.pageSql(true))
        .contains("WHERE (EXISTS (SELECT 1 FROM domain_events d")
        .contains(") AND e.id>? ORDER BY e.id LIMIT ?");
    assertThat(probe.pageArgs(7, "last-id", null, 1000)).hasSize(12).endsWith("last-id", 1000);
  }

  @Test
  void archivedEventLocatorUsesItsEventIdForPagingAndCleanup() {
    var probe =
        ShardMigrationPreflight.BUCKET_PROBES.stream()
            .filter(item -> item.name().equals("v3_history_event_locators"))
            .findFirst()
            .orElseThrow();
    assertThat(probe.cursorColumns()).containsExactly("event_id");
    assertThat(probe.pageSql(true))
        .contains("WHERE (logical_bucket=?) AND event_id>? ORDER BY event_id LIMIT ?");
    assertThat(probe.pageArgs(4, "event-42", null, 1000)).containsExactly(4, "event-42", 1000);
    assertThat(ShardMigrationPreflight.BUCKET_PROBES.indexOf(probe))
        .isGreaterThan(
            ShardMigrationPreflight.BUCKET_PROBES.stream()
                .filter(item -> item.name().equals("v3_history_archives"))
                .mapToInt(ShardMigrationPreflight.BUCKET_PROBES::indexOf)
                .findFirst()
                .orElseThrow());
  }
}
