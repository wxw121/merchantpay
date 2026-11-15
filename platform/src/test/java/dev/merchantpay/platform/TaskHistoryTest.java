package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.outbox.TaskQueue;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TaskHistoryTest extends FinancialFixture {
  TaskQueue archiveQueue() {
    return new TaskQueue(
        jdbc,
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
        Clock.fixed(Instant.now().plus(Duration.ofDays(2)), ZoneOffset.UTC));
  }

  @Test
  void archiveFailureRollsBackBothTablesAndDoesNotMoveRunningOrDeadTasks() {
    createPayment("archive-rollback-a", "100");
    createPayment("archive-rollback-b", "100");
    var queue = archiveQueue();
    var tasks = queue.claimBatch(10);
    queue.completeBatch(tasks);
    jdbc.update("UPDATE outbox_tasks SET updated_at=?", java.sql.Timestamp.from(Instant.now()));
    String rejected = tasks.getFirst().id();
    jdbc.execute(
        "ALTER TABLE outbox_task_history ADD CONSTRAINT archive_failed CHECK(id<>'"
            + rejected
            + "')");
    assertThatThrownBy(() -> queue.archiveDone(10)).isInstanceOf(RuntimeException.class);
    assertThat(count("outbox_tasks")).isEqualTo(2);
    assertThat(count("outbox_task_history")).isZero();
    jdbc.execute("ALTER TABLE outbox_task_history DROP CONSTRAINT archive_failed");
    jdbc.update("UPDATE outbox_tasks SET status='DEAD' WHERE id=?", rejected);
    assertThat(queue.archiveDone(10)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM outbox_tasks", String.class))
        .isEqualTo("DEAD");
  }

  @Test
  void archivedNotificationRemainsVisibleAndCanBeReplayedWithSameIdentity() throws Exception {
    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
        schemaConnection,
        new org.springframework.core.io.ClassPathResource(
            "db/migration/V2__security_and_evidence.sql"));
    var payment = paid("archive-replay", "100");
    var queue = archiveQueue();
    var tasks = queue.claimBatch(10);
    queue.completeBatch(tasks);
    jdbc.update("UPDATE outbox_tasks SET updated_at=?", java.sql.Timestamp.from(Instant.now()));
    jdbc.update(
        "UPDATE outbox_tasks SET retry_started_at=?",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(2))));
    queue.archiveDone(10);
    String notice =
        tasks.stream()
            .filter(t -> t.kind().equals("PAYMENT_SUCCEEDED"))
            .findFirst()
            .orElseThrow()
            .id();
    var previousWindow =
        jdbc.queryForObject(
            "SELECT retry_started_at FROM outbox_task_history WHERE id=?",
            java.sql.Timestamp.class,
            notice);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var queries =
        new dev.merchantpay.platform.query.Queries(jdbc, Clock.systemUTC(), "cursor-test");
    var merchants =
        new dev.merchantpay.platform.merchant.MerchantService(
            jdbc,
            null,
            new dev.merchantpay.platform.common.NotificationPolicy(""),
            tx,
            Clock.systemUTC());
    var controller =
        new dev.merchantpay.platform.web.ConsoleController(
            new dev.merchantpay.platform.web.Access(),
            queries,
            payments,
            refunds,
            merchants,
            null,
            jdbc,
            tx,
            Clock.systemUTC(),
            new OutboxService(jdbc, Clock.systemUTC()));
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .build();
    var caller =
        new dev.merchantpay.platform.web.Access.Caller(
            "test", "merchant-a", java.util.Set.of("MERCHANT"));
    ScopedValue.where(dev.merchantpay.platform.web.Access.CURRENT, caller)
        .run(
            () -> {
              try {
                mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                            "/api/console/tasks/{id}/replay", notice))
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isOk());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    assertThat(count("outbox_task_records")).isEqualTo(2);
    assertThat(count("outbox_events")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                    "SELECT retry_started_at FROM outbox_tasks WHERE id=?",
                    java.sql.Timestamp.class,
                    notice)
                .toInstant())
        .isAfter(previousWindow.toInstant().plus(Duration.ofDays(1)));
    assertThat(queue.claimBatch(10))
        .singleElement()
        .extracting(TaskQueue.Task::id)
        .isEqualTo(notice);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE operation='TASK_REPLAY'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void completedHistoryLeavesHotQueueWhileEventIdentityAndAuditFactsRemain() throws Exception {
    var now = Instant.now().plusSeconds(1);
    var queue =
        new TaskQueue(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            Clock.fixed(now.plus(Duration.ofDays(2)), ZoneOffset.UTC));
    var payment = paid("archive-money", "100");
    var claimed = queue.claimBatch(10);
    assertThat(queue.completeBatch(claimed)).allMatch(Boolean::booleanValue);
    jdbc.update("UPDATE outbox_tasks SET updated_at=?", java.sql.Timestamp.from(now));
    assertThat(queue.archiveDone(1)).isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(1);
    assertThat(count("outbox_task_history")).isEqualTo(1);
    assertThat(count("outbox_task_records")).isEqualTo(2);
    assertThat(count("outbox_events")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_task_records WHERE event_id=?",
                Long.class,
                "payment-success:" + payment.id()))
        .isEqualTo(1);
    assertThat(queue.archiveDone(10)).isEqualTo(1);
    assertThat(queue.archiveDone(10)).isEqualTo(0);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    t ->
                        new dev.merchantpay.platform.outbox.OutboxService(jdbc, Clock.systemUTC())
                            .enqueue(
                                "payment-success:" + payment.id(),
                                "PAYMENT_SUCCEEDED",
                                payment.id(),
                                "merchant-a")))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(count("outbox_tasks")).isZero();
  }
}
