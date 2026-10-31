package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

class TaskClaimSqlTest extends FinancialFixture {
  Clock clock;

  void seed(int size) {
    for (int i = 0; i < size; i++) createPayment("claim-sql-" + i, "100");
    clock =
        Clock.fixed(
            jdbc.queryForObject("SELECT MAX(due_at) FROM outbox_tasks", Timestamp.class)
                .toInstant()
                .plusSeconds(1),
            ZoneOffset.UTC);
  }

  TaskQueue queue(JdbcTemplate db, Clock time, boolean bulk) {
    return new TaskQueue(
        db,
        new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),
        time,
        true,
        bulk);
  }

  TaskQueue queue() {
    return queue(jdbc, clock, true);
  }

  final class CountingJdbc extends JdbcTemplate {
    final AtomicInteger batchedItems = new AtomicInteger(), updates = new AtomicInteger();

    CountingJdbc() {
      super(jdbc.getDataSource());
    }

    @Override
    public int update(String sql, Object... args) {
      updates.incrementAndGet();
      return super.update(sql, args);
    }

    @Override
    public <T> int[][] batchUpdate(
        String sql, Collection<T> items, int size, ParameterizedPreparedStatementSetter<T> setter) {
      batchedItems.addAndGet(items.size());
      return super.batchUpdate(sql, items, size, setter);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 8, 64})
  void selectedRowsHaveIndependentDurableTokensAndOneMultiItemUpdate(int size) {
    seed(size);
    var db = new CountingJdbc();
    var tasks = queue(db, clock, true).claimBatch(size);
    assertThat(tasks).hasSize(size);
    assertThat(tasks.stream().map(TaskQueue.Task::token).distinct()).hasSize(size);
    assertThat(db.updates.get()).isEqualTo(size == 1 ? 0 : 1);
    assertThat(db.batchedItems.get()).isEqualTo(size == 1 ? 1 : 0);
    for (var task : tasks) {
      var row =
          jdbc.queryForMap(
              "SELECT status,attempts,lease_token,lease_until FROM outbox_tasks WHERE id=?",
              task.id());
      assertThat(row.get("status")).isEqualTo("RUNNING");
      assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(task.attempts()).isEqualTo(1);
      assertThat(row.get("lease_token")).isEqualTo(task.token());
      assertThat(
              jdbc.queryForObject(
                      "SELECT lease_until FROM outbox_tasks WHERE id=?", Timestamp.class, task.id())
                  .toInstant())
          .isEqualTo(clock.instant().plusSeconds(60));
    }
  }

  @Test
  void oldConstructorsKeepTheirClaimWritePath() {
    seed(3);
    var db = new CountingJdbc();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
    assertThat(new TaskQueue(db, tx, clock).claimBatch(2)).hasSize(2);
    assertThat(new TaskQueue(db, tx, clock, true).claimBatch(1)).hasSize(1);
    assertThat(db.updates.get()).isZero();
    assertThat(db.batchedItems.get()).isEqualTo(3);
  }

  @Test
  void expiredOwnersAreReplacedBeforePendingTasksAndOldTokensCannotComplete() {
    seed(3);
    var old = queue(jdbc, clock, false).claimBatch(2);
    var recovering = queue(jdbc, Clock.offset(clock, Duration.ofSeconds(61)), true);
    var current = recovering.claimBatch(3);
    assertThat(current).hasSize(3);
    assertThat(current.subList(0, 2).stream().map(TaskQueue.Task::id))
        .containsExactlyInAnyOrderElementsOf(old.stream().map(TaskQueue.Task::id).toList());
    assertThat(current.get(0).attempts()).isEqualTo(2);
    assertThat(current.get(1).attempts()).isEqualTo(2);
    assertThat(current.get(2).attempts()).isEqualTo(1);
    for (var previous : old) {
      assertThat(recovering.complete(previous)).isFalse();
      assertThat(
              current.stream()
                  .filter(t -> t.id().equals(previous.id()))
                  .findFirst()
                  .orElseThrow()
                  .token())
          .isNotEqualTo(previous.token());
    }
    assertThat(recovering.completeBatch(current)).containsExactly(true, true, true);
  }

  @Test
  void queueClassAndDueTimeRemainIsolated() {
    seed(2);
    var delayed = jdbc.queryForObject("SELECT MAX(id) FROM outbox_tasks", String.class);
    jdbc.update(
        "UPDATE outbox_tasks SET due_at=? WHERE id=?",
        Timestamp.from(clock.instant().plusSeconds(120)),
        delayed);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    tx.executeWithoutResult(
        ignored ->
            new OutboxService(jdbc, clock).enqueue("claim-bulk", "EXPORT", "export", "merchant-a"));
    assertThat(queue().claimBatch(64, "INBOX")).isEmpty();
    assertThat(queue().claimBatch(64, "FAST")).hasSize(1);
    assertThat(queue().claimBatch(64, "BULK"))
        .singleElement()
        .extracting(TaskQueue.Task::kind)
        .isEqualTo("EXPORT");
    assertThat(
            queue(jdbc, Clock.offset(clock, Duration.ofSeconds(121)), true).claimBatch(64, "FAST"))
        .extracting(TaskQueue.Task::id)
        .contains(delayed);
  }

  @Test
  void twoInstancesNeverClaimTheSameExecution() throws Exception {
    seed(32);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var one =
          executor.submit(
              () -> {
                start.await();
                return queue().claimBatch(32);
              });
      var two =
          executor.submit(
              () -> {
                start.await();
                return queue().claimBatch(32);
              });
      start.countDown();
      var all = new ArrayList<>(one.get(5, TimeUnit.SECONDS));
      all.addAll(two.get(5, TimeUnit.SECONDS));
      assertThat(all).hasSize(32);
      assertThat(all.stream().map(TaskQueue.Task::id).distinct()).hasSize(32);
      assertThat(all.stream().map(TaskQueue.Task::token).distinct()).hasSize(32);
    }
  }

  @Test
  void noClaimIsReturnedOrVisibleBeforeCommitAndOtherWorkersSkipItsLocks() throws Exception {
    seed(2);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            entered.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("commit not released");
            } catch (InterruptedException failure) {
              throw new IllegalStateException(failure);
            }
            super.doCommit(status);
          }
        };
    var claimant = new TaskQueue(jdbc, new TransactionTemplate(manager), clock, true, true);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = executor.submit(() -> claimant.claimBatch(2));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(result.isDone()).isFalse();
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_tasks WHERE status='PENDING'", Long.class))
            .isEqualTo(2);
        assertThat(queue().claimBatch(2)).isEmpty();
      } finally {
        release.countDown();
      }
      assertThat(result.get(3, TimeUnit.SECONDS)).hasSize(2);
    }
  }

  @Test
  void aConstraintFailureRollsBackAllClaims() {
    seed(2);
    var id = jdbc.queryForObject("SELECT MAX(id) FROM outbox_tasks", String.class);
    jdbc.execute(
        "ALTER TABLE outbox_tasks ADD CONSTRAINT reject_claim CHECK(status<>'RUNNING' OR id<>'"
            + id
            + "')");
    assertThatThrownBy(() -> queue().claimBatch(2)).isInstanceOf(RuntimeException.class);
    assertUnclaimed();
    jdbc.execute("ALTER TABLE outbox_tasks DROP CONSTRAINT reject_claim");
    assertThat(queue().claimBatch(2)).hasSize(2);
  }

  @Test
  void wrongAffectedRowCountRollsBackAllClaims() {
    seed(2);
    var bad =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            return super.update(sql, args) - 1;
          }
        };
    assertThatThrownBy(() -> queue(bad, clock, true).claimBatch(2))
        .isInstanceOf(IllegalStateException.class);
    assertUnclaimed();
  }

  @Test
  void commitFailureReturnsNoClaimsAndRollsBack() {
    seed(2);
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            throw new TransactionSystemException("injected commit failure");
          }
        };
    manager.setRollbackOnCommitFailure(true);
    var claimant = new TaskQueue(jdbc, new TransactionTemplate(manager), clock, true, true);
    assertThatThrownBy(() -> claimant.claimBatch(2)).isInstanceOf(TransactionSystemException.class);
    assertUnclaimed();
  }

  @Test
  void invalidBoundsAndOuterTransactionsAreRejected() {
    seed(2);
    var q = queue();
    assertThatThrownBy(() -> q.claimBatch(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> q.claimBatch(65)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> q.claimBatch(2, "unknown"))
        .isInstanceOf(IllegalArgumentException.class);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> q.claimBatch(2)))
        .isInstanceOf(IllegalStateException.class);
    assertUnclaimed();
  }

  void assertUnclaimed() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE status='PENDING' AND attempts=0 AND lease_token IS NULL",
                Long.class))
        .isEqualTo(2);
  }
}
