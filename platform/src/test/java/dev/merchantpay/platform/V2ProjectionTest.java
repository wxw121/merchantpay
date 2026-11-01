package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.projection.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

class V2ProjectionTest extends FinanceFixture {
  ProjectionService projection;
  ClickHouseReadModel target;
  EventJournal journal;
  JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void schema() {
    ScriptUtils.executeSqlScript(
        schemaConnection,
        new ClassPathResource("db/migration/V21__projection_and_balance_checkpoints.sql"));
    target = mock(ClickHouseReadModel.class);
    projection =
        new ProjectionService(
            jdbc,
            tx,
            Clock.systemUTC(),
            json,
            new EventDelivery(jdbc, tx, Clock.systemUTC(), json),
            target);
    journal = new EventJournal(jdbc, Clock.systemUTC(), json, "POLLING");
  }

  String event(String payment, String id) {
    tx.executeWithoutResult(
        t ->
            journal.append(
                UUID.randomUUID().toString(), id, "PAYMENT_SUCCEEDED", payment, "merchant-a"));
    return jdbc.queryForObject(
        "SELECT payload FROM domain_events WHERE event_id=?", String.class, id);
  }

  @Test
  void configurableClaimExceedsLegacyTwentyWithoutLosingLeaseFencing() {
    var p = paid("larger-batch", "1000");
    for (int i = 1; i <= 150; i++)
      jdbc.update(
          "INSERT INTO projection_batches(id,generation,payment_id,merchant_id,revision,snapshot,snapshot_hash,state,due_at,created_at) VALUES (?,'v2-initial',?,'merchant-a',?,'{}','hash','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          UUID.randomUUID().toString(),
          p.id(),
          i);
    var first = projection.claim(128);
    var second = projection.claim(128);
    assertThat(first).hasSize(128);
    assertThat(second).hasSize(22);
    assertThat(first.stream().map(ProjectionService.Delivery::id))
        .doesNotContainAnyElementsOf(second.stream().map(ProjectionService.Delivery::id).toList());
    assertThat(projection.claim(128)).isEmpty();
    assertThatThrownBy(() -> projection.claim(257)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retainedMysqlFactCanRepairAConsumerStartingAfterKafkaRetentionWithoutCreatingTasks() {
    var p = paid("retention", "1000");
    event(p.id(), "retained-source");
    jdbc.update("UPDATE domain_events SET delivery_mode='CDC'");
    long tasks = count("outbox_tasks");
    projection.recoverPollingFacts();
    assertThat(count("projection_receipts")).isZero();
    assertThat(
            projection.recoverSourceFacts(
                "approved source recovery", new Actor("ops", null, Set.of("OPS"))))
        .isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(tasks);
    assertThat(count("projection_batches")).isEqualTo(1);
  }

  @Test
  void unknownStateHasAReplayableFactAndRepeatedObservationDoesNotMoveItsSourceTime() {
    var outbox = new OutboxService(jdbc, Clock.systemUTC(), false, journal);
    var service =
        new dev.merchantpay.platform.payment.PaymentService(
            jdbc,
            tx,
            new dev.merchantpay.platform.ledger.LedgerService(jdbc),
            outbox,
            Clock.systemUTC(),
            16);
    var payment =
        service.create(
            "merchant-a",
            new dev.merchantpay.platform.payment.PaymentService.CreatePayment(
                "unknown-event", "1000", "CNY", "MOCK_A", null));
    projection.recoverPollingFacts();
    service.applyResultWithoutView(payment.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    projection.recoverPollingFacts();
    var first = service.get("merchant-a", payment.id());
    service.applyResultWithoutView(payment.id(), new ChannelResult.Unknown("MOCK_A", "retry"));
    assertThat(service.get("merchant-a", payment.id()).updatedAt()).isEqualTo(first.updatedAt());
    assertThat(count("domain_events")).isEqualTo(2);
    assertThat(count("outbox_tasks")).isEqualTo(1);
    String snapshot =
        jdbc.queryForObject(
            "SELECT snapshot FROM projection_batches ORDER BY revision DESC LIMIT 1", String.class);
    assertThat(json.readTree(snapshot).get("status").asString()).isEqualTo("UNKNOWN");
  }

  @Test
  void duplicateReceiptCreatesOneIntentAndNewEventCapturesWholeLatestState() {
    var p = paid("projection", "1000");
    String first = event(p.id(), "fixture-one");
    projection.consume("topic", 0, 1, first);
    projection.consume("topic", 0, 2, first);
    assertThat(count("projection_batches")).isEqualTo(1);
    jdbc.update("UPDATE payments SET reserved_minor=100 WHERE id=?", p.id());
    projection.consume("topic", 0, 3, event(p.id(), "fixture-two"));
    var rows =
        jdbc.queryForList("SELECT revision,snapshot FROM projection_batches ORDER BY revision");
    assertThat(rows).hasSize(2);
    assertThat(
            json.readTree(rows.getLast().get("snapshot").toString())
                .get("reservedMinor")
                .asString())
        .isEqualTo("100");
  }

  @Test
  void failedRemoteWriteRetainsIntentAndExpiredTokenCannotConfirmNewClaim() {
    var p = paid("retry", "1000");
    projection.consume("topic", 0, 1, event(p.id(), "fixture"));
    var first = projection.claim();
    jdbc.update("UPDATE projection_batches SET lease_until=TIMESTAMP '2020-01-01 00:00:00'");
    var second = projection.claim();
    projection.deliver(first);
    assertThat(jdbc.queryForObject("SELECT state FROM projection_batches", String.class))
        .isEqualTo("LEASED");
    doThrow(new IllegalStateException("offline")).when(target).write(anyList());
    projection.deliver(second);
    assertThat(jdbc.queryForObject("SELECT state FROM projection_batches", String.class))
        .isEqualTo("PENDING");
    assertThat(count("projection_receipts")).isEqualTo(1);
  }

  @Test
  void malformedEventsNeverBecomeSourceFactsAndNonOperatorCannotPromote() {
    assertThatThrownBy(() -> projection.consume("topic", 0, 1, "{}"))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
    assertThat(count("projection_batches")).isZero();
    assertThatThrownBy(
            () ->
                new ProjectionPromotion(jdbc, target, json, Clock.systemUTC(), tx)
                    .promote(
                        "v2-initial",
                        true,
                        "verify",
                        new Actor("m", "merchant-a", Set.of("MERCHANT"))))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void promotionRequiresStoppedWritersAndExactSourceSnapshot() {
    paid("not-projected", "1000");
    jdbc.update("UPDATE projection_generations SET scan_complete=true");
    var promote = new ProjectionPromotion(jdbc, target, json, Clock.systemUTC(), tx);
    var ops = new Actor("ops", null, Set.of("OPS"));
    assertThatThrownBy(() -> promote.promote("v2-initial", false, "verify", ops))
        .isInstanceOf(ApiException.class);
    when(target.query(anyString(), anyMap())).thenReturn(List.of());
    assertThatThrownBy(() -> promote.promote("v2-initial", true, "verify", ops))
        .isInstanceOf(ApiException.class);
    assertThat(
            jdbc.queryForObject("SELECT active_generation FROM projection_control", String.class))
        .isNull();
  }
}
