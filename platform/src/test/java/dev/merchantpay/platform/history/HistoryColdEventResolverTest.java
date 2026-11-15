package dev.merchantpay.platform.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.DomainEvent;
import dev.merchantpay.platform.outbox.EventDelivery;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

class HistoryColdEventResolverTest {
  @Test
  void verifiesColdReplayAgainstArchiveAndRejectsChangedPayload() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:cold_replay_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""));
    jdbc.execute(
        "CREATE TABLE v3_history_event_locators(event_id VARCHAR(160),"
            + "merchant_id VARCHAR(64),payment_id VARCHAR(64),logical_bucket INT,state VARCHAR(12))");
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    jdbc.update(
        "INSERT INTO v3_history_event_locators VALUES(?,?,?,?,?)",
        "event-1",
        "merchant",
        payment,
        4,
        "COLD");
    var json = JsonMapper.builder().build();
    var archive = mock(HistoryArchiveService.class);
    String raw = "{\"eventId\":\"event-1\"}";
    String row =
        json.writeValueAsString(
            Map.of("eventId", "event-1", "payload", raw, "payloadHash", Signatures.sha256(raw)));
    var fact =
        new HistoryParquetCodec.Fact(
            "EVENT",
            "merchant",
            payment,
            "event-1",
            1_700_000_000_000_000L,
            Signatures.sha256(row),
            row);
    when(archive.readEvent(eq("merchant"), eq(payment), eq("event-1"), any())).thenReturn(fact);
    var event =
        new DomainEvent(
            1,
            "event-1",
            "PAYMENT_SUCCEEDED",
            payment,
            "merchant",
            1,
            "2026-01-01T00:00:00Z",
            "trace");
    var resolver = new HistoryColdEventResolver(archive, json);
    assertThat(resolver.verify(jdbc, event, raw).bucket()).isEqualTo(4);
    assertThatThrownBy(() -> resolver.verify(jdbc, event, raw + " "))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
    jdbc.update("UPDATE v3_history_event_locators SET state='WARM_COPY'");
    assertThatThrownBy(() -> resolver.verify(jdbc, event, raw))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
  }
}
