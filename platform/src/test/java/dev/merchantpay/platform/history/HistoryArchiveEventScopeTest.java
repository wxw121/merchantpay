package dev.merchantpay.platform.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HistoryArchiveEventScopeTest {
  @Test
  void paymentRootArchiveIncludesItsRefundAndInboxProcessingEvents() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:archive_scope_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""));
    jdbc.execute(
        "CREATE TABLE refunds(id VARCHAR(64),payment_id VARCHAR(64),merchant_id VARCHAR(64))");
    jdbc.execute("CREATE TABLE inbox_notifications(id VARCHAR(64),aggregate_id VARCHAR(64))");
    jdbc.execute(
        "CREATE TABLE domain_events(sequence_no BIGINT,event_id VARCHAR(160),"
            + "aggregateid VARCHAR(64),merchant_id VARCHAR(64),event_type VARCHAR(64))");
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    String refund = "r4_00000000-0000-0000-0000-000000000000";
    jdbc.update("INSERT INTO refunds VALUES(?,?,'merchant')", refund, payment);
    jdbc.update("INSERT INTO inbox_notifications VALUES('inbox-pay',?)", payment);
    jdbc.update("INSERT INTO inbox_notifications VALUES('inbox-refund',?)", refund);
    jdbc.update(
        "INSERT INTO inbox_notifications VALUES('inbox-other',"
            + "'p5_00000000-0000-0000-0000-000000000000')");
    jdbc.update("INSERT INTO domain_events VALUES(1,'payment',?,'merchant','PAYMENT')", payment);
    jdbc.update("INSERT INTO domain_events VALUES(2,'refund',?,'merchant','REFUND')", refund);
    jdbc.update(
        "INSERT INTO domain_events VALUES(3,'inbox-pay','inbox-pay','merchant','PROCESS_INBOX')");
    jdbc.update(
        "INSERT INTO domain_events VALUES(4,'inbox-refund','inbox-refund','merchant','PROCESS_INBOX')");
    jdbc.update("INSERT INTO domain_events VALUES(5,'other','other','merchant','OTHER')");
    jdbc.update("INSERT INTO domain_events VALUES(6,'wrong-tenant',?,'other','PAYMENT')", payment);
    jdbc.update(
        "INSERT INTO domain_events VALUES(7,'inbox-other','inbox-other',"
            + "'merchant','PROCESS_INBOX')");
    assertThat(
            jdbc.query(
                HistoryArchiveService.EVENTS_SQL,
                (rs, ignored) -> rs.getString("event_id"),
                "merchant",
                payment,
                payment,
                "merchant",
                payment,
                payment,
                "merchant"))
        .containsExactly("payment", "refund", "inbox-pay", "inbox-refund");
  }
}
