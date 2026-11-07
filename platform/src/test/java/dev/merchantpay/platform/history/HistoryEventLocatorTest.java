package dev.merchantpay.platform.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.Signatures;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

class HistoryEventLocatorTest {
  @Test
  void locatorIsStableAcrossMultipleArchiveCopiesAndRejectsChangedEventFacts() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:locators_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""));
    jdbc.execute("CREATE TABLE v3_history_archives(id VARCHAR(36) PRIMARY KEY)");
    jdbc.execute(
        "CREATE TABLE domain_events(id VARCHAR(64),event_id VARCHAR(160) PRIMARY KEY,"
            + "merchant_id VARCHAR(64),logical_bucket INT,payload VARCHAR(1000),"
            + "payload_hash VARCHAR(64))");
    jdbc.execute(
        "CREATE TABLE v3_history_event_locators(event_id VARCHAR(160) PRIMARY KEY,"
            + "merchant_id VARCHAR(64),payment_id VARCHAR(64),logical_bucket INT,"
            + "archive_id VARCHAR(36),payload_hash VARCHAR(64),event_payload_hash VARCHAR(64),"
            + "event_row_id VARCHAR(64),"
            + "state VARCHAR(12) DEFAULT 'WARM_COPY',created_at TIMESTAMP,"
            + "FOREIGN KEY(archive_id) REFERENCES v3_history_archives(id))");
    String firstArchive = UUID.randomUUID().toString();
    String secondArchive = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO v3_history_archives VALUES(?)", firstArchive);
    jdbc.update("INSERT INTO v3_history_archives VALUES(?)", secondArchive);
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    var json = JsonMapper.builder().build();
    String sourcePayload = "{\"eventId\":\"event-1\"}";
    String payload =
        json.writeValueAsString(
            Map.of(
                "eventId",
                "event-1",
                "payload",
                sourcePayload,
                "payloadHash",
                Signatures.sha256(sourcePayload)));
    jdbc.update(
        "INSERT INTO domain_events VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        "event-1",
        "merchant",
        4,
        sourcePayload,
        Signatures.sha256(sourcePayload));
    var event =
        new HistoryParquetCodec.Fact(
            "EVENT",
            "merchant",
            payment,
            "event-1",
            1_700_000_000_000_000L,
            Signatures.sha256(payload),
            payload);
    var paymentFact =
        new HistoryParquetCodec.Fact(
            "PAYMENT",
            "merchant",
            payment,
            payment,
            event.occurredMicros(),
            Signatures.sha256("{}"),
            "{}");
    var now = Timestamp.from(Instant.parse("2026-11-06T00:00:00Z"));

    HistoryArchiveService.recordEventLocators(
        jdbc, List.of(paymentFact, event), "merchant", payment, 4, firstArchive, now, json, true);
    HistoryArchiveService.recordEventLocators(
        jdbc, List.of(event), "merchant", payment, 4, secondArchive, now, json, true);
    assertThat(
            jdbc.queryForObject(
                "SELECT archive_id FROM v3_history_event_locators WHERE event_id='event-1'",
                String.class))
        .isEqualTo(firstArchive);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v3_history_event_locators", Long.class))
        .isEqualTo(1L);

    String relocated =
        json.writeValueAsString(
            Map.of(
                "eventId",
                "event-1",
                "payload",
                sourcePayload,
                "payloadHash",
                Signatures.sha256(sourcePayload),
                "sequenceNo",
                999));
    var relocatedFact =
        new HistoryParquetCodec.Fact(
            "EVENT",
            "merchant",
            payment,
            "event-1",
            event.occurredMicros(),
            Signatures.sha256(relocated),
            relocated);
    HistoryArchiveService.recordEventLocators(
        jdbc, List.of(relocatedFact), "merchant", payment, 4, secondArchive, now, json, true);
    assertThat(
            jdbc.queryForObject(
                "SELECT archive_id FROM v3_history_event_locators WHERE event_id='event-1'",
                String.class))
        .isEqualTo(secondArchive);
    jdbc.update("UPDATE v3_history_event_locators SET state='COLD' WHERE event_id='event-1'");
    jdbc.update("DELETE FROM domain_events WHERE event_id='event-1'");
    HistoryArchiveService.recordEventLocators(
        jdbc, List.of(relocatedFact), "merchant", payment, 4, firstArchive, now, json, true);
    assertThat(
            jdbc.queryForObject(
                "SELECT archive_id FROM v3_history_event_locators WHERE event_id='event-1'",
                String.class))
        .isEqualTo(secondArchive);

    var changed =
        new HistoryParquetCodec.Fact(
            "EVENT",
            "merchant",
            payment,
            "event-1",
            event.occurredMicros(),
            Signatures.sha256("{\"changed\":true}"),
            "{\"changed\":true}");
    assertThatThrownBy(
            () ->
                HistoryArchiveService.recordEventLocators(
                    jdbc, List.of(changed), "merchant", payment, 4, secondArchive, now, json, true))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("归档事件原文缺少身份或负载");
  }
}
