package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxEventRegistryMaintenance;
import dev.merchantpay.platform.outbox.TaskQueue;
import java.time.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

class MySqlOptimizationTest extends FinancialFixture {
  static final MySQLContainer MYSQL =
      new MySQLContainer(MySqlTestImage.IMAGE)
          .withDatabaseName("optimization_test")
          .withUsername("test")
          .withPassword("test");

  @BeforeAll
  static void start() {
    MYSQL.start();
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @Override
  DriverManagerDataSource source() {
    String url =
        MYSQL.getJdbcUrl()
            + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
            + "rewriteBatchedStatements=true";
    var source = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
    var sql = new JdbcTemplate(source);
    sql.execute("DROP VIEW IF EXISTS outbox_task_records");
    for (String table :
        sql.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",
            String.class)) sql.execute("DROP TABLE " + table);
    return source;
  }

  @Test
  void threeEntriesAndJournalExecuteAsTwoServerInserts() {
    var payment = createPayment("mysql-batch-wire", "100");
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    tx.executeWithoutResult(
        t -> {
          int before = inserts();
          new LedgerService(jdbc).postPayment(payment.id(), "merchant-a", 100, 1, Instant.now());
          assertThat(inserts() - before)
              .as("Connector/J must rewrite three JDBC batch elements to one server INSERT")
              .isEqualTo(2);
        });
    assertThat(count("ledger_entries")).isEqualTo(3);
  }

  int inserts() {
    return Integer.parseInt(
        jdbc.queryForObject("SHOW SESSION STATUS LIKE 'Com_insert'", (rs, row) -> rs.getString(2)));
  }

  @Test
  void concurrentArchiversMoveCompletedRowOnceWithPermanentIdentity() throws Exception {
    var payment = createPayment("mysql-archive-race", "100");
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var queue =
        new TaskQueue(
            jdbc, tx, Clock.fixed(Instant.now().plus(Duration.ofDays(2)), ZoneOffset.UTC));
    var task = queue.claim().orElseThrow();
    assertThat(queue.complete(task)).isTrue();
    jdbc.update(
        "UPDATE outbox_tasks SET updated_at=? WHERE id=?",
        java.sql.Timestamp.from(Instant.now()),
        task.id());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> queue.archiveDone(1));
      var second = executor.submit(() -> queue.archiveDone(1));
      assertThat(first.get() + second.get()).isEqualTo(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_task_records WHERE id=?", Long.class, task.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_id=?",
                Long.class,
                "channel-payment:" + payment.id()))
        .isEqualTo(1);
  }

  @Test
  void migrationBackfillsPermanentEventRegistryForExistingRows() throws Exception {
    // Rewind only this fresh, isolated test schema to the pre-V14 state.
    jdbc.execute("DROP VIEW outbox_task_records");
    jdbc.execute("DROP TABLE outbox_task_history");
    jdbc.execute("DROP TABLE outbox_events");
    jdbc.execute("DROP INDEX ix_task_archive_due ON outbox_tasks");
    jdbc.update(
        "INSERT INTO outbox_tasks(id,event_id,kind,aggregate_id,merchant_id,payload,status,due_at,created_at,updated_at,queue_class) VALUES ('old-task','old-event','CHANNEL_PAYMENT','p','m','{}','DONE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'FAST')");
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V14__completed_task_history.sql"));
    assertThat(
            jdbc.queryForObject(
                "SELECT event_id FROM outbox_events WHERE id='old-task'", String.class))
        .isEqualTo("old-event");
  }

  @Test
  void oldBinaryWriteIsReconciledBeforeReupgradeAndRemainsUniqueAfterArchive() throws Exception {
    jdbc.update(
        "INSERT INTO outbox_tasks(id,event_id,kind,aggregate_id,merchant_id,payload,status,due_at,created_at,updated_at,queue_class) VALUES ('rollback-task','rollback-event','CHANNEL_PAYMENT','p','m','{}','DONE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)-INTERVAL 2 DAY,CURRENT_TIMESTAMP(6)-INTERVAL 2 DAY,'FAST')");
    assertThat(count("outbox_events")).isZero();
    var reconciled = OutboxEventRegistryMaintenance.reconcile(jdbc.getDataSource());
    assertThat(reconciled.inserted()).isEqualTo(1);
    assertThat(OutboxEventRegistryMaintenance.reconcile(jdbc.getDataSource()).inserted()).isZero();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var queue = new TaskQueue(jdbc, tx, Clock.systemUTC());
    assertThat(queue.archiveDone(1)).isEqualTo(1);
    assertThat(count("outbox_tasks")).isZero();
    assertThat(count("outbox_task_history")).isEqualTo(1);
    var outbox = new dev.merchantpay.platform.outbox.OutboxService(jdbc, Clock.systemUTC());
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    ignored -> outbox.enqueue("rollback-event", "CHANNEL_PAYMENT", "p", "m")))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(count("outbox_task_records")).isEqualTo(1);
  }

  @Test
  void conflictingRollbackIdentityFailsBeforeChangingRegistry() throws Exception {
    jdbc.update(
        "INSERT INTO outbox_tasks(id,event_id,kind,aggregate_id,merchant_id,payload,status,due_at,created_at,updated_at,queue_class) VALUES ('rollback-task','rollback-event','CHANNEL_PAYMENT','p','m','{}','DONE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'FAST')");
    jdbc.update(
        "INSERT INTO outbox_events(id,event_id) VALUES ('rollback-task','different-event')");
    assertThatThrownBy(() -> OutboxEventRegistryMaintenance.reconcile(jdbc.getDataSource()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Conflicting outbox event identity");
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void cleanupUsesLimitedDeletesAndPreservesUnexpiredReplayAndQuotaWindows() throws Exception {
    assertThat(
            dev.merchantpay.platform.orchestration.TaskWorker.class
                .getDeclaredMethod("expireSecurityWindows")
                .getAnnotation(Scheduled.class)
                .fixedDelay())
        .isLessThanOrEqualTo(250);
    assertThat(
            dev.merchantpay.platform.orchestration.TaskArchiver.class
                .getDeclaredMethod("archiveCompletedTasks")
                .getAnnotation(Scheduled.class)
                .fixedDelay())
        .isLessThanOrEqualTo(250);
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V2__security_and_evidence.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V15__quota_expiry_index.sql"));
    Instant now = Instant.now();
    var old = java.sql.Timestamp.from(now.minusSeconds(1));
    jdbc.batchUpdate(
        "INSERT INTO signature_nonces(key_id,nonce,expires_at) VALUES (?,?,?)",
        java.util.stream.IntStream.range(0, 2001).boxed().toList(),
        500,
        (statement, n) -> {
          statement.setString(1, "old");
          statement.setString(2, "nonce-" + n);
          statement.setTimestamp(3, old);
        });
    jdbc.batchUpdate(
        "INSERT INTO api_rate_limits(key_id,window_second,requests) VALUES (?,?,1)",
        java.util.stream.IntStream.range(0, 2001).boxed().toList(),
        500,
        (statement, n) -> {
          statement.setString(1, "key-" + n);
          statement.setLong(2, now.minusSeconds(601).getEpochSecond());
        });
    jdbc.update(
        "INSERT INTO signature_nonces(key_id,nonce,expires_at) VALUES ('live','nonce',?)",
        java.sql.Timestamp.from(now.plusSeconds(600)));
    jdbc.update(
        "INSERT INTO api_rate_limits(key_id,window_second,requests) VALUES ('live',?,1)",
        now.getEpochSecond());
    try (var worker =
        new dev.merchantpay.platform.orchestration.TaskWorker(
            mock(TaskQueue.class),
            null,
            null,
            null,
            jdbc,
            Clock.fixed(now, ZoneOffset.UTC),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            null,
            null,
            30)) {
      var cleanup = worker.getClass().getDeclaredMethod("expireSecurityWindows");
      cleanup.setAccessible(true);
      cleanup.invoke(worker);
      assertThat(count("signature_nonces")).isBetween(2L, 1502L);
      assertThat(count("api_rate_limits")).isBetween(2L, 1502L);
      for (int round = 0;
          round < 5 && (count("signature_nonces") > 1 || count("api_rate_limits") > 1);
          round++) cleanup.invoke(worker);
      assertThat(count("signature_nonces")).isEqualTo(1);
      assertThat(count("api_rate_limits")).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT nonce FROM signature_nonces WHERE key_id='live'", String.class))
          .isEqualTo("nonce");
    }
  }
}
