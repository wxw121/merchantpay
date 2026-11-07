package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.sharding.*;
import java.time.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class MySqlShardRoutingMigrationTest {
  @Test
  void v27BackfillsAuthoritativeEventBucketsIncludingInboxEvents() throws Exception {
    try (var db = MySqlFinanceDatabase.container()) {
      db.start();
      var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
      Flyway.configure()
          .dataSource(source)
          .target("26")
          .locations("classpath:db/migration")
          .load()
          .migrate();
      var jdbc = new JdbcTemplate(source);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
      var clock = Clock.systemUTC();
      var journal =
          new EventJournal(
              jdbc, clock, tools.jackson.databind.json.JsonMapper.builder().build(), "POLLING");
      var outbox = new OutboxService(jdbc, clock, false, journal);
      String payment = "p1_552ea710-7879-4b42-876a-f145cdcf51d3";
      jdbc.update(
          "INSERT INTO inbox_notifications(id,channel,fingerprint,aggregate_id,operation,raw_body,status,created_at,updated_at) VALUES ('source-inbox','MOCK_A','source-fingerprint',?,'PAYMENT','{}','RECEIVED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
          payment);
      tx.executeWithoutResult(
          ignored -> {
            outbox.enqueue("event-payment", "CHANNEL_PAYMENT", payment, "merchant-a");
            outbox.enqueue("event-inbox", "PROCESS_INBOX", "source-inbox", "merchant-a");
          });
      try (var connection = source.getConnection()) {
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("db/migration/V27__shard_event_sources.sql"));
      }
      assertThat(
              jdbc.queryForList(
                  "SELECT logical_bucket FROM domain_events ORDER BY event_id", Integer.class))
          .containsExactly(1, 1);
    }
  }

  @Test
  void preV26ArchivedChannelAndInboxTasksRemainClaimableAfterReplay() throws Exception {
    try (var db = MySqlFinanceDatabase.container()) {
      db.start();
      var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
      Flyway.configure()
          .dataSource(source)
          .target("25")
          .locations("classpath:db/migration")
          .load()
          .migrate();
      var jdbc = new JdbcTemplate(source);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
      var old = Clock.fixed(Instant.now().minusSeconds(172800), ZoneOffset.UTC);
      var outbox = new OutboxService(jdbc, old);
      String payment = "p1_552ea710-7879-4b42-876a-f145cdcf51d3";
      jdbc.update(
          "INSERT INTO inbox_notifications(id,channel,fingerprint,aggregate_id,operation,raw_body,status,created_at,updated_at) VALUES ('old-inbox','MOCK_A','fingerprint',?,'PAYMENT','{}','PROCESSED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
          payment);
      tx.executeWithoutResult(
          ignored -> {
            outbox.enqueue("legacy-channel", "CHANNEL_PAYMENT", payment, "merchant-a");
            outbox.enqueue("legacy-inbox", "PROCESS_INBOX", "old-inbox", "merchant-a");
          });
      jdbc.update("UPDATE outbox_tasks SET status='DONE'");
      var queue = new TaskQueue(jdbc, tx, Clock.systemUTC());
      assertThat(queue.archiveDone(10)).isEqualTo(2);
      try (var connection = source.getConnection()) {
        ScriptUtils.executeSqlScript(
            connection,
            new ClassPathResource("db/migration/V26__shard_task_routes_and_receipts.sql"));
      }
      assertThat(jdbc.queryForList("SELECT logical_bucket FROM outbox_task_history", Integer.class))
          .containsExactlyInAnyOrder(1, 1);
      tx.executeWithoutResult(
          ignored -> {
            jdbc.update("INSERT INTO outbox_tasks SELECT * FROM outbox_task_history");
            jdbc.update("DELETE FROM outbox_task_history");
            jdbc.update("UPDATE outbox_tasks SET status='PENDING'");
          });
      var routes =
          new ShardTransactions(jdbc, java.util.Map.of(0, source, 1, source, 2, source, 3, source));
      var expected = routes.currentRoute(1);
      var fast =
          routes.write(
              expected,
              selected -> queue.claimBucket(expected.bucket(), expected.epoch(), "FAST", 4));
      var inbox =
          routes.write(
              expected,
              selected -> queue.claimBucket(expected.bucket(), expected.epoch(), "INBOX", 4));
      assertThat(fast).hasSize(1);
      assertThat(inbox).hasSize(1);
      assertThat(jdbc.queryForList("SELECT lease_epoch FROM outbox_tasks", Long.class))
          .containsExactlyInAnyOrder(1L, 1L);
    }
  }
}
