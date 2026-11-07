package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ShardRoutingMigrationBackfillTest {
  @Test
  void backfillsArchiveBucketAndEventLocatorIdentity() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:history_backfill_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute(
        "CREATE TABLE v3_history_archives(id VARCHAR(36) PRIMARY KEY,merchant_id VARCHAR(64),payment_id VARCHAR(64),created_at TIMESTAMP)");
    jdbc.execute("CREATE TABLE v3_history_event_locators(event_id VARCHAR(160) PRIMARY KEY)");
    jdbc.execute(
        "CREATE TABLE domain_events(id VARCHAR(64),event_id VARCHAR(160) UNIQUE,payload_hash VARCHAR(64))");
    jdbc.update(
        "INSERT INTO v3_history_archives VALUES('archive','merchant',?,CURRENT_TIMESTAMP)",
        "pa_00000000-0000-0000-0000-000000000000");
    jdbc.update("INSERT INTO v3_history_event_locators VALUES('event-1')");
    jdbc.update("INSERT INTO domain_events VALUES('row-1','event-1','payload-sha')");
    try (var connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V43__history_archive_bucket_index.sql"));
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V48__cold_history_event_state.sql"));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT logical_bucket FROM v3_history_archives WHERE id='archive'", Integer.class))
        .isEqualTo(10);
    assertThat(
            jdbc.queryForMap(
                "SELECT state,event_row_id,event_payload_hash FROM v3_history_event_locators WHERE event_id='event-1'"))
        .containsEntry("STATE", "WARM_COPY")
        .containsEntry("EVENT_ROW_ID", "row-1")
        .containsEntry("EVENT_PAYLOAD_HASH", "payload-sha");
  }

  @Test
  void backfillsDirectAndInboxRoutedBuckets() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:route_backfill_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute(
        "CREATE TABLE outbox_tasks(id VARCHAR(64),aggregate_id VARCHAR(64),kind VARCHAR(40),status VARCHAR(16),queue_class VARCHAR(16),due_at TIMESTAMP,lease_until TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE outbox_task_history(id VARCHAR(64),aggregate_id VARCHAR(64),kind VARCHAR(40))");
    jdbc.execute("CREATE TABLE inbox_notifications(id VARCHAR(64),aggregate_id VARCHAR(64))");
    jdbc.execute(
        "CREATE TABLE domain_events(id VARCHAR(64),aggregateid VARCHAR(64),event_type VARCHAR(40))");
    jdbc.execute(
        "CREATE TABLE payments(id VARCHAR(64),merchant_id VARCHAR(64),channel VARCHAR(32),status VARCHAR(24),channel_reference VARCHAR(128),amount_minor BIGINT,currency VARCHAR(3))");
    jdbc.execute(
        "CREATE TABLE refunds(id VARCHAR(64),merchant_id VARCHAR(64),channel VARCHAR(32),status VARCHAR(24),channel_reference VARCHAR(128),amount_minor BIGINT)");
    String direct = "p4_00000000-0000-0000-0000-000000000000";
    String inbox = "pa_00000000-0000-0000-0000-000000000000";
    jdbc.update("INSERT INTO inbox_notifications VALUES('inbox',?)", inbox);
    jdbc.update(
        "INSERT INTO outbox_tasks(id,aggregate_id,kind) VALUES('direct',?,'PAYMENT')", direct);
    jdbc.update(
        "INSERT INTO outbox_tasks(id,aggregate_id,kind) VALUES('inbox','inbox','PROCESS_INBOX')");
    jdbc.update(
        "INSERT INTO outbox_task_history(id,aggregate_id,kind) VALUES('history','inbox','PROCESS_INBOX')");
    jdbc.update("INSERT INTO domain_events VALUES('direct',?,'PAYMENT_FACT')", direct);
    jdbc.update("INSERT INTO domain_events VALUES('inbox','inbox','PROCESS_INBOX')");

    try (var connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection,
          new ClassPathResource("db/migration/V26__shard_task_routes_and_receipts.sql"));
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V27__shard_event_sources.sql"));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT logical_bucket FROM outbox_tasks WHERE id='direct'", Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT logical_bucket FROM outbox_tasks WHERE id='inbox'", Integer.class))
        .isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "SELECT logical_bucket FROM outbox_task_history WHERE id='history'", Integer.class))
        .isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "SELECT logical_bucket FROM domain_events WHERE id='direct'", Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT logical_bucket FROM domain_events WHERE id='inbox'", Integer.class))
        .isEqualTo(10);
  }
}
