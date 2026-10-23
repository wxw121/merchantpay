package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.TaskQueue;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DurableTaskTest extends FinancialFixture {
  @Test
  void workerAcknowledgementsUseExplicitTransactions() throws Exception {
    createPayment("ack-one", "100");
    createPayment("ack-two", "100");
    var outsideTransaction = new java.util.concurrent.atomic.AtomicInteger();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var queue =
        new TaskQueue(jdbc, tx, Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC)) {
          @Override
          public boolean complete(Task task) {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) outsideTransaction.incrementAndGet();
            return super.complete(task);
          }
        };
    try (var worker =
        new dev.merchantpay.platform.orchestration.TaskWorker(
            queue,
            org.mockito.Mockito.mock(dev.merchantpay.platform.channel.ChannelGateway.class),
            null,
            null,
            jdbc,
            Clock.systemUTC(),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            null,
            null,
            30)) {
      var poll = worker.getClass().getDeclaredMethod("poll");
      poll.setAccessible(true);
      poll.invoke(worker);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE status='DONE'", Long.class))
        .isEqualTo(2);
    assertThat(outsideTransaction.get()).as("individual auto-commit acknowledgements").isZero();
  }

  @Test
  void aBulkJobCannotConsumeTheFastQueueSlots() {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    tx.executeWithoutResult(
        ignored ->
            new dev.merchantpay.platform.outbox.OutboxService(jdbc, Clock.systemUTC())
                .enqueue("bulk-job", "EXPORT", "export-1", "merchant-a"));
    var payment = createPayment("fast-after-bulk", "100");
    var queue = queue(Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC));
    assertThat(queue.claimBatch(1, false))
        .singleElement()
        .extracting(TaskQueue.Task::aggregateId)
        .isEqualTo(payment.id());
    assertThat(queue.claimBatch(1, true))
        .singleElement()
        .extracting(TaskQueue.Task::kind)
        .isEqualTo("EXPORT");
  }

  @Test
  void claimsBoundedBatchWithIndependentLeaseTokens() {
    for (int i = 0; i < 10; i++) {
      createPayment("batch-" + i, "100");
    }
    var queue = queue(Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC));
    var tasks = queue.claimBatch(4);
    assertThat(tasks).hasSize(4);
    assertThat(tasks.stream().map(TaskQueue.Task::id).distinct().count()).isEqualTo(4);
    assertThat(tasks.stream().map(TaskQueue.Task::token).distinct().count()).isEqualTo(4);
    assertThat(queue.claimBatch(10)).hasSize(6);
  }

  TaskQueue queue(Clock clock) {
    return new TaskQueue(
        jdbc,
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
        clock);
  }

  @Test
  void completionBatchRollsBackAllAcknowledgementsIfOneWriteFails() {
    createPayment("batch-ack-1", "100");
    createPayment("batch-ack-2", "100");
    var queue = queue(Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC));
    var tasks = queue.claimBatch(2);
    var last = tasks.stream().map(TaskQueue.Task::id).sorted().toList().getLast();
    jdbc.execute(
        "ALTER TABLE outbox_tasks ADD CONSTRAINT reject_batch_ack CHECK (status <> 'DONE' OR id <> '"
            + last
            + "')");
    assertThatThrownBy(() -> queue.completeBatch(tasks)).isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE status='RUNNING'", Long.class))
        .isEqualTo(2);
    jdbc.execute("ALTER TABLE outbox_tasks DROP CONSTRAINT reject_batch_ack");
    assertThat(queue.completeBatch(tasks)).containsExactly(true, true);
    assertThat(queue.completeBatch(tasks)).containsExactly(false, false);
  }

  @Test
  void mixedStaleAndCurrentLeasesAreIndividuallyFencedInInputOrder() {
    createPayment("batch-fence-1", "100");
    createPayment("batch-fence-2", "100");
    var now = Instant.now().plusSeconds(1);
    var firstQueue = queue(Clock.fixed(now, ZoneOffset.UTC));
    var old = firstQueue.claimBatch(2);
    var recovered = queue(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
    var current = recovered.claimBatch(2);
    var currentFirst =
        current.stream()
            .filter(task -> task.id().equals(old.getFirst().id()))
            .findFirst()
            .orElseThrow();
    var currentSecond =
        current.stream()
            .filter(task -> task.id().equals(old.getLast().id()))
            .findFirst()
            .orElseThrow();
    assertThat(
            recovered.completeBatch(
                List.of(old.getFirst(), currentSecond, currentFirst, currentFirst)))
        .containsExactly(false, true, true, false);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE status='DONE'", Long.class))
        .isEqualTo(2);
    assertThatThrownBy(() -> recovered.completeBatch(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> recovered.completeBatch(current)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void concurrentWorkersClaimEachTaskOnlyOnce() throws Exception {
    createPayment("task", "100");
    var queue = queue(Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          executor.invokeAll(
              List.<Callable<Integer>>of(
                  () -> queue.claim().isPresent() ? 1 : 0,
                  () -> queue.claim().isPresent() ? 1 : 0));
      assertThat(results.get(0).get() + results.get(1).get()).isEqualTo(1);
    }
  }

  @Test
  void crashedWorkerLeaseCanBeRecoveredAndItsLateAcknowledgementIsFenced() {
    createPayment("lease", "100");
    var now = Instant.now().plusSeconds(1);
    var first = queue(Clock.fixed(now, ZoneOffset.UTC)).claim().orElseThrow();
    var secondQueue = queue(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
    var second = secondQueue.claim().orElseThrow();
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.token()).isNotEqualTo(first.token());
    assertThat(secondQueue.complete(first)).isFalse();
    assertThat(secondQueue.complete(second)).isTrue();
    assertThat(jdbc.queryForObject("SELECT status FROM outbox_tasks", String.class))
        .isEqualTo("DONE");
  }

  @Test
  void staleConflictCannotQuarantineAReclaimedTaskAndCurrentConflictStopsAutomaticRetries() {
    var payment = createPayment("conflict-lease", "100");
    var now = Instant.now().plusSeconds(1);
    var first = queue(Clock.fixed(now, ZoneOffset.UTC)).claim().orElseThrow();
    var replacementQueue = queue(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
    var replacement = replacementQueue.claim().orElseThrow();
    var conflict =
        new dev.merchantpay.platform.common.ApiException(
            "CHANNEL_EVIDENCE_CONFLICT", 409, "receipt reused");
    replacementQueue.retry(first, conflict);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM outbox_tasks WHERE id=?", String.class, replacement.id()))
        .isEqualTo("RUNNING");
    replacementQueue.retry(replacement, conflict);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM outbox_tasks WHERE id=?", String.class, replacement.id()))
        .isEqualTo("DEAD");
    assertThat(queue(Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC)).claimBatch(1)).isEmpty();
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("PROCESSING");
  }
}
