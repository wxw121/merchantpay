package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.TaskQueue;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class RecoveryWindowMigrationTest {
  @Test
  void upgradePreservesLatestAuthorizedReplayWithoutExtendingOrdinaryRetries() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(source);
    var now = Instant.parse("2026-11-01T10:00:00Z");
    try (var connection = source.getConnection()) {
      try {
        for (var file :
            new String[] {
              "V1__core.sql", "V7__isolated_task_queues.sql", "V14__completed_task_history.sql"
            })
          ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + file));
        task(jdbc, "outbox_tasks", "replayed", "PENDING", now.minus(Duration.ofDays(7)), now);
        task(jdbc, "outbox_tasks", "old", "PENDING", now.minus(Duration.ofDays(3)), now);
        task(jdbc, "outbox_tasks", "fresh", "PENDING", now.minusSeconds(3600), now);
        task(jdbc, "outbox_task_history", "archived", "DONE", now.minus(Duration.ofDays(7)), now);
        audit(jdbc, "replayed", "merchant-a", "TASK_REPLAY", now.minusSeconds(7200));
        audit(jdbc, "replayed", "merchant-a", "TASK_REPLAY", now.minusSeconds(900));
        audit(jdbc, "replayed", "merchant-b", "TASK_REPLAY", now.minusSeconds(60));
        audit(jdbc, "replayed", "merchant-a", "OTHER_OPERATION", now.minusSeconds(30));
        audit(jdbc, "archived", "merchant-a", "TASK_REPLAY", now.minusSeconds(600));
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("db/migration/V23__recovery_windows.sql"));
        assertThat(started(jdbc, "replayed")).isEqualTo(now.minusSeconds(900));
        assertThat(started(jdbc, "archived")).isEqualTo(now.minusSeconds(600));
        assertThat(started(jdbc, "old")).isEqualTo(now.minus(Duration.ofDays(3)));
        assertThat(started(jdbc, "fresh")).isEqualTo(now.minusSeconds(3600));
        var queue =
            new TaskQueue(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                Clock.fixed(now, ZoneOffset.UTC));
        var tasks = queue.claimBatch(10, "NOTIFY");
        assertThat(tasks).hasSize(3);
        for (var task : tasks)
          assertThat(queue.notificationMayRun(task))
              .as(task.id())
              .isEqualTo(!task.id().equals("old"));
        assertThat(
                jdbc.queryForObject(
                    "SELECT event_id FROM outbox_task_history WHERE id='archived'", String.class))
            .isEqualTo("event-archived");
      } finally {
        jdbc.execute("SHUTDOWN");
      }
    }
  }

  private static void task(
      JdbcTemplate jdbc, String table, String id, String status, Instant created, Instant now) {
    jdbc.update(
        "INSERT INTO "
            + table
            + "(id,event_id,kind,aggregate_id,merchant_id,payload,status,attempts,due_at,created_at,updated_at,queue_class) VALUES (?,?,'PAYMENT_SUCCEEDED',?,'merchant-a','{}',?,0,?,?,?,'NOTIFY')",
        id,
        "event-" + id,
        "payment-" + id,
        status,
        Timestamp.from(now),
        Timestamp.from(created),
        Timestamp.from(now));
  }

  private static void audit(
      JdbcTemplate jdbc, String task, String merchant, String operation, Instant time) {
    jdbc.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,?,'operator',?,?,'authorized',?)",
        UUID.randomUUID().toString(),
        merchant,
        operation,
        task,
        Timestamp.from(time));
  }

  private static Instant started(JdbcTemplate jdbc, String id) {
    return jdbc.queryForObject(
            "SELECT retry_started_at FROM outbox_task_records WHERE id=?", Timestamp.class, id)
        .toInstant();
  }
}
