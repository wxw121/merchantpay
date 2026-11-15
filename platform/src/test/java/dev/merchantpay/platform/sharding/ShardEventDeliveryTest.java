package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.DomainEvent;
import dev.merchantpay.platform.outbox.EventDelivery;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardEventDeliveryTest {
  private static final String CORE = "outbox.event.merchantpay";
  private static final String TARGET = CORE + ".s2";
  private static final String PAYLOAD = "shard-event-bytes";
  private static final Actor OPS = new Actor("operator", null, Set.of("OPS"));
  private final ShardTransactions routes = mock(ShardTransactions.class);
  private final EventDelivery prototype = mock(EventDelivery.class);
  private final EventDelivery coreDelivery = mock(EventDelivery.class);
  private final EventDelivery targetDelivery = mock(EventDelivery.class);
  private final DataSource coreSource = source();
  private final DataSource targetSource = source();
  private final JdbcTemplate core = new JdbcTemplate(coreSource);
  private final JdbcTemplate target = new JdbcTemplate(targetSource);
  private final ShardCatalog.Route owner = new ShardCatalog.Route(7, 2, 4);
  private ShardEventDelivery shards;

  @BeforeEach
  void fixture() {
    for (var jdbc : new JdbcTemplate[] {core, target}) {
      jdbc.execute(
          "CREATE TABLE domain_events(event_id VARCHAR(36) PRIMARY KEY,logical_bucket INT,delivery_mode VARCHAR(12))");
      jdbc.execute("CREATE TABLE event_transport(id INT PRIMARY KEY,mode VARCHAR(12))");
      jdbc.update("INSERT INTO event_transport VALUES (1,'CDC')");
      jdbc.execute("CREATE TABLE event_receipts(event_id VARCHAR(36),consumer_name VARCHAR(80))");
      jdbc.execute(
          "CREATE TABLE event_quarantine(id VARCHAR(36) PRIMARY KEY,topic VARCHAR(120),partition_no INT,"
              + "record_offset BIGINT,payload VARCHAR(100),payload_hash VARCHAR(64),reason VARCHAR(100),"
              + "status VARCHAR(16),attempts INT,created_at TIMESTAMP,updated_at TIMESTAMP,replayed_by VARCHAR(80))");
      jdbc.execute(
          "CREATE TABLE v3_history_event_locators(event_id VARCHAR(36) PRIMARY KEY,logical_bucket INT,state VARCHAR(12))");
    }
    when(prototype.forShard(any(JdbcTemplate.class)))
        .thenAnswer(
            invocation -> {
              JdbcTemplate jdbc = invocation.getArgument(0);
              if (jdbc.getDataSource() == coreSource) return coreDelivery;
              if (jdbc.getDataSource() == targetSource) return targetDelivery;
              return mock(EventDelivery.class);
            });
    shards =
        new ShardEventDelivery(
            routes, Map.of(0, coreSource, 1, source(), 2, targetSource, 3, source()), prototype);
  }

  @Test
  void routesTheAuthoritativeEventThroughAnEpochFencedTarget() {
    givenTargetEvent();
    useTargetWrite();

    shards.deliver(TARGET, 3, 55, PAYLOAD);

    verify(routes).currentRoute(7);
    verify(routes).write(eq(owner), any());
    verify(targetDelivery).deliver(TARGET, 3, 55, PAYLOAD);
    verifyNoInteractions(coreDelivery);
  }

  @Test
  void resolvesColdLocatorOnAuthoritativeSourceWithoutShiftingItToCore() {
    when(targetDelivery.verifyStored(PAYLOAD)).thenReturn(event("CHANNEL_PAYMENT"));
    target.update("INSERT INTO v3_history_event_locators VALUES ('event',7,'COLD')");
    when(routes.currentRoute(7)).thenReturn(owner);
    useTargetWrite();

    shards.deliver(TARGET, 3, 55, PAYLOAD);

    verify(targetDelivery).deliver(TARGET, 3, 55, PAYLOAD);
    verifyNoInteractions(coreDelivery);
  }

  @Test
  void legacyCoreTopicMayDeliverUnbucketedBulkWork() {
    when(coreDelivery.verifyStored(PAYLOAD)).thenReturn(event("RECONCILE"));
    core.update("INSERT INTO domain_events VALUES ('event',NULL,'CDC')");

    shards.deliver(CORE, 3, 55, PAYLOAD);

    verify(coreDelivery).deliver(CORE, 3, 55, PAYLOAD);
    verifyNoInteractions(routes, targetDelivery);
  }

  @Test
  void rejectsCopiedEventsWhenTheRouteBelongsToAnotherPhysicalDatabase() {
    givenTargetEvent();
    when(routes.currentRoute(7)).thenReturn(new ShardCatalog.Route(7, 1, 5));

    assertThatThrownBy(() -> shards.deliver(TARGET, 3, 55, PAYLOAD))
        .isInstanceOf(EventDelivery.InvalidEvent.class)
        .hasMessageContaining("authoritative route");
    verify(targetDelivery, never()).deliver(anyString(), anyInt(), anyLong(), anyString());
  }

  @Test
  void rejectsBulkWorkOnATargetAndRoutedWorkWithoutABucket() {
    when(targetDelivery.verifyStored(PAYLOAD))
        .thenReturn(event("EXPORT"), event("CHANNEL_PAYMENT"));
    target.update("INSERT INTO domain_events VALUES ('event',NULL,'CDC')");

    assertThatThrownBy(() -> shards.deliver(TARGET, 3, 55, PAYLOAD))
        .isInstanceOf(EventDelivery.InvalidEvent.class)
        .hasMessageContaining("BULK");
    assertThatThrownBy(() -> shards.deliver(TARGET, 3, 55, PAYLOAD))
        .isInstanceOf(EventDelivery.InvalidEvent.class)
        .hasMessageContaining("no routable bucket");
    verifyNoInteractions(routes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"outbox.event.merchantpay.s4", "other.events", ""})
  void unknownTopicsCannotQuarantineInAnArbitraryShard(String topic) {
    assertThatThrownBy(() -> shards.quarantine(topic, 1, 2, PAYLOAD, "invalid"))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
    verifyNoInteractions(coreDelivery, targetDelivery);
  }

  @Test
  void showsOnlySelectedSourceBacklogAndQuarantine() {
    givenTargetEvent();
    target.update("INSERT INTO domain_events VALUES ('received',7,'CDC'),('polling',7,'POLLING')");
    target.update("INSERT INTO event_receipts VALUES ('received',?)", EventDelivery.CONSUMER);
    insertQuarantine("a", TARGET, PAYLOAD, "QUARANTINED");
    insertQuarantine("b", TARGET, PAYLOAD, "REPLAYED");
    core.update("INSERT INTO event_quarantine(id,status) VALUES ('core','QUARANTINED')");

    assertThat(shards.status(2))
        .containsEntry("physicalShard", 2)
        .containsEntry("shardedCdcEnabled", true)
        .containsEntry("unreceived", 1L)
        .containsEntry("quarantined", 1L);
    assertThat(shards.quarantined(2, "a"))
        .singleElement()
        .satisfies(item -> assertThat(item).containsEntry("id", "b").doesNotContainKey("payload"));
    assertThatThrownBy(() -> shards.quarantined(2, "x".repeat(65)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> shards.status(4)).isInstanceOf(ApiException.class);
  }

  @Test
  void replaysIntactQuarantineOnlyUnderItsOriginalShardFence() {
    givenTargetEvent();
    useTargetWrite();
    insertQuarantine("id", TARGET, PAYLOAD, "QUARANTINED");
    when(targetDelivery.replay("id", OPS)).thenReturn(Map.of("id", "id", "status", "REPLAYED"));

    assertThat(shards.replay(2, "id", OPS)).containsEntry("status", "REPLAYED");

    verify(routes).write(eq(owner), any());
    verify(targetDelivery).replay("id", OPS);
    verifyNoInteractions(coreDelivery);
  }

  @Test
  void rejectsDamagedMisdirectedOrConcurrentlyEditedReplayEvidence() {
    givenTargetEvent();
    insertQuarantine("wrong-shard", CORE, PAYLOAD, "QUARANTINED");
    insertQuarantine("wrong-topic", "unknown", PAYLOAD, "QUARANTINED");
    insertQuarantine("processed", TARGET, PAYLOAD, "REPLAYED");
    insertQuarantine("damaged", TARGET, "original", "QUARANTINED");
    target.update("UPDATE event_quarantine SET payload=? WHERE id='damaged'", PAYLOAD);
    insertQuarantine("changed", TARGET, PAYLOAD, "QUARANTINED");
    when(routes.write(eq(owner), any()))
        .thenAnswer(
            invocation -> {
              target.update("UPDATE event_quarantine SET record_offset=999 WHERE id='changed'");
              Function<JdbcTemplate, ?> action = invocation.getArgument(1);
              return action.apply(target);
            });

    assertThat(code(() -> shards.replay(2, "missing", OPS))).isEqualTo("NOT_FOUND");
    assertThat(code(() -> shards.replay(2, "wrong-shard", OPS))).isEqualTo("EVENT_SOURCE_CONFLICT");
    assertThat(code(() -> shards.replay(2, "wrong-topic", OPS))).isEqualTo("EVENT_SOURCE_CONFLICT");
    assertThat(code(() -> shards.replay(2, "damaged", OPS))).isEqualTo("TRUNCATED_EVIDENCE");
    assertThat(shards.replay(2, "processed", OPS)).containsEntry("status", "REPLAYED");
    assertThat(code(() -> shards.replay(2, "changed", OPS))).isEqualTo("EVENT_SOURCE_CONFLICT");
    verify(targetDelivery, never()).replay(anyString(), any());
  }

  private void givenTargetEvent() {
    when(targetDelivery.verifyStored(PAYLOAD)).thenReturn(event("CHANNEL_PAYMENT"));
    target.update("INSERT INTO domain_events VALUES ('event',7,'CDC')");
    when(routes.currentRoute(7)).thenReturn(owner);
  }

  private void useTargetWrite() {
    when(routes.write(eq(owner), any()))
        .thenAnswer(
            invocation -> {
              Function<JdbcTemplate, ?> action = invocation.getArgument(1);
              return action.apply(target);
            });
  }

  private void insertQuarantine(String id, String topic, String payload, String status) {
    target.update(
        "INSERT INTO event_quarantine(id,topic,partition_no,record_offset,payload,payload_hash,status) VALUES (?,?,0,1,?,?,?)",
        id,
        topic,
        payload,
        Signatures.sha256(payload),
        status);
  }

  private static String code(Runnable action) {
    return org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, action::run).code();
  }

  private static DomainEvent event(String type) {
    return new DomainEvent(
        1, "event", type, "p7_payment", "merchant", 1, "2026-11-14T00:00:00Z", null);
  }

  private static DriverManagerDataSource source() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:shard_event_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
  }
}
