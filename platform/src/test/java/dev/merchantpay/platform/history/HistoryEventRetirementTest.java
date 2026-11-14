package dev.merchantpay.platform.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

class HistoryEventRetirementTest {
  @Test
  void retirementRequiresBothReceiptsAndCommitsColdLocatorWithDeletion() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:retire_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute(
        "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,"
            + "access_state VARCHAR(16))");
    jdbc.update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
    jdbc.execute(
        "CREATE TABLE v3_history_event_locators(event_id VARCHAR(160) PRIMARY KEY,"
            + "merchant_id VARCHAR(64),payment_id VARCHAR(64),logical_bucket INT,"
            + "archive_id VARCHAR(36),payload_hash VARCHAR(64),event_payload_hash VARCHAR(64),"
            + "event_row_id VARCHAR(64),"
            + "state VARCHAR(12),retired_at TIMESTAMP,retired_by VARCHAR(160),"
            + "retire_reason VARCHAR(1000))");
    jdbc.execute(
        "CREATE TABLE domain_events(id VARCHAR(36),event_id VARCHAR(160),"
            + "merchant_id VARCHAR(64),logical_bucket INT,payload VARCHAR(1000),"
            + "payload_hash VARCHAR(64),event_type VARCHAR(64),delivery_mode VARCHAR(16))");
    jdbc.execute(
        "CREATE TABLE payments(id VARCHAR(64),merchant_id VARCHAR(64),"
            + "status VARCHAR(16),updated_at TIMESTAMP)");
    jdbc.execute("CREATE TABLE refunds(payment_id VARCHAR(64),status VARCHAR(16))");
    jdbc.execute("CREATE TABLE outbox_tasks(status VARCHAR(16))");
    jdbc.execute("CREATE TABLE inbox_notifications(status VARCHAR(16))");
    jdbc.execute(
        "CREATE TABLE event_receipts(consumer_name VARCHAR(64),event_id VARCHAR(160),"
            + "payload_hash VARCHAR(64))");
    jdbc.execute(
        "CREATE TABLE projection_receipts(event_id VARCHAR(160),payload_hash VARCHAR(64))");
    jdbc.execute("CREATE TABLE event_quarantine(status VARCHAR(16))");
    jdbc.execute("CREATE TABLE projection_errors(state VARCHAR(16),payload_hash VARCHAR(64))");
    jdbc.execute(
        "CREATE TABLE audit_records(id VARCHAR(36),merchant_id VARCHAR(64),"
            + "actor VARCHAR(160),operation VARCHAR(64),resource_id VARCHAR(160),"
            + "detail VARCHAR(1000),created_at TIMESTAMP)");
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    String payload = "{\"eventId\":\"event-1\"}";
    String hash = Signatures.sha256(payload);
    jdbc.update(
        "INSERT INTO payments VALUES(?,?,?,?)",
        payment,
        "merchant",
        "SUCCEEDED",
        Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    String rowId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO domain_events VALUES(?,?,?,?,?,?,?,?)",
        rowId,
        "event-1",
        "merchant",
        4,
        payload,
        hash,
        "PAYMENT_SUCCEEDED",
        "CDC");
    var json = JsonMapper.builder().build();
    String archived =
        json.writeValueAsString(
            jdbc.query("SELECT * FROM domain_events WHERE event_id='event-1'", Queries.ROW)
                .getFirst());
    jdbc.update(
        "INSERT INTO v3_history_event_locators(event_id,merchant_id,payment_id,"
            + "logical_bucket,archive_id,payload_hash,event_payload_hash,event_row_id,state) "
            + "VALUES(?,?,?,?,?,?,?,?,'WARM_COPY')",
        "event-1",
        "merchant",
        payment,
        4,
        UUID.randomUUID().toString(),
        Signatures.sha256(archived),
        hash,
        rowId);
    jdbc.update(
        "INSERT INTO event_receipts VALUES(?,?,?)", EventDelivery.CONSUMER, "event-1", hash);
    var archive = mock(HistoryArchiveService.class);
    var fact =
        new HistoryParquetCodec.Fact(
            "EVENT",
            "merchant",
            payment,
            "event-1",
            1_700_000_000_000_000L,
            Signatures.sha256(archived),
            archived);
    when(archive.readEvent(eq("merchant"), eq(payment), eq("event-1"), any())).thenReturn(fact);
    var gate = mock(ShardExportReadGate.class);
    var routes =
        java.util.stream.IntStream.range(0, 16)
            .mapToObj(
                bucket ->
                    new ShardExportReadGate.Route(bucket, bucket == 4 ? 1 : 0, bucket == 4 ? 2 : 1))
            .toList();
    when(gate.capture()).thenReturn(new ShardExportReadGate.Snapshot(routes, java.util.Map.of()));
    when(gate.source(1)).thenReturn(source);
    var retirement =
        new HistoryEventRetirement(
            archive,
            gate,
            json,
            Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC));
    var actor = new Actor("ops", null, Set.of("OPS"));
    assertThatThrownBy(
            () ->
                retirement.retire("merchant", payment, "event-1", "archive verified", true, actor))
        .hasMessageContaining("投影回执");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_events", Long.class)).isEqualTo(1L);
    jdbc.update("INSERT INTO projection_receipts VALUES(?,?)", "event-1", hash);
    assertThat(
            retirement
                .retire("merchant", payment, "event-1", "archive verified", true, actor)
                .state())
        .isEqualTo("COLD");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_events", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT state FROM v3_history_event_locators", String.class))
        .isEqualTo("COLD");
    assertThat(
            retirement
                .retire("merchant", payment, "event-1", "archive verified", true, actor)
                .state())
        .isEqualTo("COLD");
  }
}
