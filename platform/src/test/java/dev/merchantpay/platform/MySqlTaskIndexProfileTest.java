package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.TaskQueue;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

class MySqlTaskIndexProfileTest {
  static final MySQLContainer MYSQL =
      new MySQLContainer(MySqlTestImage.IMAGE)
          .withDatabaseName("task_index_test")
          .withUsername("test")
          .withPassword("test")
          .withCommand("--innodb-buffer-pool-size=134217728")
          .withCreateContainerCmdModifier(
              c -> c.getHostConfig().withNanoCPUs(1_000_000_000L).withMemory(805306368L));
  static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
  JdbcTemplate jdbc;
  DriverManagerDataSource source;

  @BeforeAll
  static void start() {
    MYSQL.start();
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void schema() throws Exception {
    source =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(source);
    jdbc.execute("DROP VIEW IF EXISTS outbox_task_records");
    for (var table :
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",
            String.class)) {
      if (!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected test table");
      jdbc.execute("DROP TABLE " + table);
    }
    try (var c = source.getConnection()) {
      for (var name :
          List.of(
              "V1__core.sql",
              "V6__timeline_and_cursor_indexes.sql",
              "V7__isolated_task_queues.sql",
              "V14__completed_task_history.sql",
              "V23__recovery_windows.sql"))
        ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/" + name));
    }
    var data = new ArrayList<Object[]>();
    for (int i = 0; i < 1200; i++) {
      String state = i < 1000 ? "DONE" : i < 1100 ? "PENDING" : "RUNNING";
      data.add(
          new Object[] {
            String.format("task-%04d", i),
            "event-" + i,
            "aggregate-" + i,
            "merchant-" + (i % 20),
            state,
            Timestamp.from(NOW.minusSeconds(86400L * 3)),
            Timestamp.from(NOW.minusSeconds(60)),
            "old-" + i,
            List.of("FAST", "INBOX", "NOTIFY", "BULK").get(i % 4)
          });
    }
    jdbc.batchUpdate(
        "INSERT INTO outbox_tasks(id,event_id,kind,aggregate_id,merchant_id,payload,status,due_at,lease_until,lease_token,created_at,updated_at,queue_class) VALUES (?,?,'CHANNEL_PAYMENT',?,?,'{}',?,?,?,?,CURRENT_TIMESTAMP(6)-INTERVAL 3 DAY,CURRENT_TIMESTAMP(6)-INTERVAL 3 DAY,?)",
        data);
    jdbc.update("INSERT INTO outbox_events(id,event_id) SELECT id,event_id FROM outbox_tasks");
    jdbc.execute("ANALYZE TABLE outbox_tasks");
  }

  void profile(String mode) throws Exception {
    try (var c = source.getConnection()) {
      ScriptUtils.executeSqlScript(
          c,
          new FileSystemResource(
              Path.of("..", "infra", "mysql", "local-max-task-indexes-" + mode + ".sql")));
    }
  }

  Map<String, String> indexes() {
    var result = new TreeMap<String, String>();
    jdbc.query(
        "SELECT index_name,column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='outbox_tasks' ORDER BY index_name,seq_in_index",
        rs -> {
          result.merge(rs.getString(1), rs.getString(2), (a, b) -> a + "," + b);
        });
    return result;
  }

  TaskQueue queue() {
    return new TaskQueue(
        jdbc,
        new TransactionTemplate(new DataSourceTransactionManager(source)),
        Clock.fixed(NOW, ZoneOffset.UTC),
        true,
        true);
  }

  @Test
  void trimAndRestoreOnlyTwoNonUniqueIndexesAndPreserveFacts() throws Exception {
    var original = indexes();
    var facts = jdbc.queryForList("SELECT * FROM outbox_tasks ORDER BY id");
    assertThat(original).hasSize(9);
    profile("trim");
    var expected = new TreeMap<>(original);
    expected.remove("ix_task_ready");
    expected.remove("ix_task_expired_lease");
    assertThat(indexes()).isEqualTo(expected);
    assertThat(jdbc.queryForList("SELECT * FROM outbox_tasks ORDER BY id")).isEqualTo(facts);
    assertThatThrownBy(
            () -> jdbc.update("INSERT INTO outbox_events(id,event_id) VALUES ('new','event-1')"))
        .isInstanceOf(DuplicateKeyException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO outbox_tasks(id,event_id,kind,aggregate_id,merchant_id,payload,status,attempts,due_at,lease_until,lease_token,last_error,created_at,updated_at,queue_class) SELECT 'new',event_id,kind,aggregate_id,merchant_id,payload,status,attempts,due_at,lease_until,lease_token,last_error,created_at,updated_at,queue_class FROM outbox_tasks WHERE id='task-0001'"))
        .isInstanceOf(DuplicateKeyException.class);
    profile("restore");
    assertThat(indexes()).isEqualTo(original);
    assertThat(jdbc.queryForList("SELECT * FROM outbox_tasks ORDER BY id")).isEqualTo(facts);
  }

  @Test
  void allFourLanePlansRetainOrderedRangeIndexes() throws Exception {
    profile("trim");
    for (String lane : List.of("FAST", "INBOX", "NOTIFY", "BULK")) {
      for (boolean expired : List.of(false, true)) {
        String state = expired ? "RUNNING" : "PENDING", time = expired ? "lease_until" : "due_at";
        var plan =
            jdbc.queryForMap(
                "EXPLAIN SELECT * FROM outbox_tasks WHERE status=? AND "
                    + time
                    + "<? AND queue_class=? ORDER BY "
                    + time
                    + ",id LIMIT 8 FOR UPDATE SKIP LOCKED",
                state,
                Timestamp.from(NOW),
                lane);
        assertThat(plan.get("key"))
            .isEqualTo(expired ? "ix_task_class_lease" : "ix_task_class_ready");
        assertThat(String.valueOf(plan.get("Extra"))).doesNotContainIgnoringCase("filesort");
      }
    }
  }

  @Test
  void expiredTakeoverAndLegacyGlobalClaimKeepTheirResults() throws Exception {
    profile("trim");
    var tasks = queue().claimBatch(30, "FAST");
    assertThat(tasks).hasSize(30);
    assertThat(tasks.subList(0, 25))
        .allMatch(t -> t.attempts() == 1 && t.id().compareTo("task-1100") >= 0);
    assertThat(tasks.subList(25, 30)).allMatch(t -> t.id().compareTo("task-1100") < 0);
    for (var task : tasks) {
      var stale =
          new TaskQueue.Task(
              task.id(),
              task.eventId(),
              task.kind(),
              task.aggregateId(),
              task.merchantId(),
              task.attempts(),
              "old-" + Integer.parseInt(task.id().substring(5)));
      assertThat(queue().complete(stale)).isFalse();
      assertThat(queue().complete(task)).isTrue();
    }
    assertThat(queue().claimBatch(8))
        .hasSize(8)
        .extracting(TaskQueue.Task::id)
        .doesNotHaveDuplicates();
  }

  @Test
  void laneClaimsStillSkipRowsLockedByAnotherConnection() throws Exception {
    profile("trim");
    try (var holder = source.getConnection();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      holder.setAutoCommit(false);
      var held = new HashSet<String>();
      try (var select =
          holder.prepareStatement(
              "SELECT id FROM outbox_tasks WHERE status='RUNNING' AND lease_until<? AND queue_class='FAST' ORDER BY lease_until,id LIMIT 2 FOR UPDATE SKIP LOCKED")) {
        select.setTimestamp(1, Timestamp.from(NOW));
        try (var rs = select.executeQuery()) {
          while (rs.next()) held.add(rs.getString(1));
        }
      }
      assertThat(held).hasSize(2);
      var claimed = workers.submit(() -> queue().claimBatch(4, "FAST")).get(3, TimeUnit.SECONDS);
      assertThat(claimed).hasSize(4).allMatch(t -> !held.contains(t.id()));
      holder.rollback();
    }
  }

  @Test
  void archiveAndReadIndexesRemainAvailable() throws Exception {
    profile("trim");
    assertThat(indexes())
        .containsEntry("ix_task_archive_due", "status,updated_at,id")
        .containsEntry("ix_task_merchant_time", "merchant_id,created_at,id")
        .containsEntry("ix_task_aggregate", "aggregate_id,created_at");
    assertThat(queue().archiveDone(64)).isEqualTo(64);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_task_records", Long.class))
        .isEqualTo(1200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class))
        .isEqualTo(1200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_task_history", Long.class))
        .isEqualTo(64);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_task_records WHERE aggregate_id='aggregate-1'",
                Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_task_records WHERE merchant_id='merchant-1'",
                Long.class))
        .isEqualTo(60);
  }
}
