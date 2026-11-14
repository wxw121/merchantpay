package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.TaskQueue;
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

class TaskCompletionSqlTest extends FinancialFixture {
  Clock clock = Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC);

  TaskQueue queue(boolean bulk) {
    return queue(jdbc, clock, bulk);
  }

  TaskQueue queue(JdbcTemplate db, Clock time, boolean bulk) {
    return new TaskQueue(
        db,
        new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),
        time,
        bulk);
  }

  List<TaskQueue.Task> tasks(int count) {
    for (int i = 0; i < count; i++) createPayment("completion-" + i, "100");
    clock = Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC);
    return queue(false).claimBatch(count);
  }

  final class CountingJdbc extends JdbcTemplate {
    final AtomicInteger reads = new AtomicInteger(), updates = new AtomicInteger();

    CountingJdbc() {
      super(jdbc.getDataSource());
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      reads.incrementAndGet();
      return super.query(sql, mapper, args);
    }

    @Override
    public int update(String sql, Object... args) {
      updates.incrementAndGet();
      return super.update(sql, args);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 8, 64})
  void multipleCompletionsUseOneLeaseReadAndOneUpdate(int size) {
    var tasks = tasks(size);
    var db = new CountingJdbc();
    assertThat(queue(db, clock, true).completeBatch(tasks))
        .hasSize(size)
        .allMatch(Boolean::booleanValue);
    assertThat(db.reads.get()).isEqualTo(1);
    assertThat(db.updates.get()).isEqualTo(1);
    assertThat(queue(true).completeBatch(tasks)).allMatch(value -> !value);
  }

  @Test
  void originalConstructorAndSingleTaskKeepTheOriginalSqlPath() {
    var tasks = tasks(3);
    var db = new CountingJdbc();
    var original =
        new TaskQueue(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),
            clock);
    assertThat(original.completeBatch(tasks.subList(0, 2))).containsExactly(true, true);
    assertThat(db.reads.get()).isZero();
    assertThat(db.updates.get()).isEqualTo(2);
    assertThat(queue(db, clock, true).completeBatch(tasks.subList(2, 3))).containsExactly(true);
    assertThat(db.reads.get()).isZero();
    assertThat(db.updates.get()).isEqualTo(3);
  }

  @Test
  void staleAndDuplicateRequestsKeepTheirIndividualInputOrderResults() {
    var old = tasks(2);
    var recovered = queue(jdbc, Clock.offset(clock, Duration.ofSeconds(61)), true);
    var current = recovered.claimBatch(2);
    var one =
        current.stream().filter(t -> t.id().equals(old.getFirst().id())).findFirst().orElseThrow();
    var two =
        current.stream().filter(t -> t.id().equals(old.getLast().id())).findFirst().orElseThrow();
    assertThat(recovered.completeBatch(List.of(old.getFirst(), two, one, one)))
        .containsExactly(false, true, true, false);
    assertThat(recovered.completeBatch(List.of(two, one))).containsExactly(false, false);
  }

  @Test
  void aRejectedUpdateRollsBackTheCompleteGroup() {
    var tasks = tasks(2);
    var id = tasks.getLast().id();
    jdbc.execute(
        "ALTER TABLE outbox_tasks ADD CONSTRAINT reject_bulk_ack CHECK(status <> 'DONE' OR id <> '"
            + id
            + "')");
    assertThatThrownBy(() -> queue(true).completeBatch(tasks)).isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE status='RUNNING'", Long.class))
        .isEqualTo(2);
    jdbc.execute("ALTER TABLE outbox_tasks DROP CONSTRAINT reject_bulk_ack");
    assertThat(queue(true).completeBatch(tasks)).containsExactly(true, true);
  }

  @Test
  void lockedOwnershipCannotBeReclaimedAndNoResultIsPublishedBeforeCommit() throws Exception {
    var tasks = tasks(2);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            entered.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("test commit not released");
            } catch (InterruptedException failure) {
              throw new IllegalStateException(failure);
            }
            super.doCommit(status);
          }
        };
    var completing = new TaskQueue(jdbc, new TransactionTemplate(manager), clock, true);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = executor.submit(() -> completing.completeBatch(tasks));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(result.isDone()).isFalse();
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_tasks WHERE status='DONE'", Long.class))
            .isZero();
        assertThat(queue(jdbc, Clock.offset(clock, Duration.ofSeconds(61)), true).claimBatch(2))
            .isEmpty();
        assertThat(result.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      assertThat(result.get(3, TimeUnit.SECONDS)).containsExactly(true, true);
    }
  }

  @Test
  void twoInstancesCompletingOppositeOrdersGrantEachIdOnlyOnce() throws Exception {
    var tasks = tasks(8);
    var reverse = new ArrayList<>(tasks);
    Collections.reverse(reverse);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return queue(true).completeBatch(tasks);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return queue(true).completeBatch(reverse);
              });
      start.countDown();
      long completed =
          first.get(5, TimeUnit.SECONDS).stream().filter(Boolean::booleanValue).count()
              + second.get(5, TimeUnit.SECONDS).stream().filter(Boolean::booleanValue).count();
      assertThat(completed).isEqualTo(8);
    }
  }

  @Test
  void failedCommitRollsBackAndDoesNotReturnSuccess() {
    var tasks = tasks(2);
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            throw new TransactionSystemException("injected commit failure");
          }
        };
    manager.setRollbackOnCommitFailure(true);
    var completing = new TaskQueue(jdbc, new TransactionTemplate(manager), clock, true);
    assertThatThrownBy(() -> completing.completeBatch(tasks))
        .isInstanceOf(TransactionSystemException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE status='RUNNING'", Long.class))
        .isEqualTo(2);
  }

  @Test
  void inconsistentAffectedRowCountRollsBack() {
    var tasks = tasks(2);
    var misreported =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            int changed = super.update(sql, args);
            return changed - 1;
          }
        };
    assertThatThrownBy(() -> queue(misreported, clock, true).completeBatch(tasks))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE status='RUNNING'", Long.class))
        .isEqualTo(2);
  }

  @Test
  void invalidSizesAndOuterBusinessTransactionAreStillRejected() {
    var task = tasks(1).getFirst();
    var queue = queue(true);
    assertThatThrownBy(() -> queue.completeBatch(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queue.completeBatch(Collections.nCopies(65, task)))
        .isInstanceOf(IllegalArgumentException.class);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    assertThatThrownBy(
            () -> tx.executeWithoutResult(ignored -> queue.completeBatch(List.of(task, task))))
        .isInstanceOf(IllegalStateException.class);
  }
}
