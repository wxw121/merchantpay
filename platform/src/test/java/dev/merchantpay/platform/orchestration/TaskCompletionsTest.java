package dev.merchantpay.platform.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.outbox.TaskQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TaskCompletionsTest {
  TaskQueue.Task task(String id) {
    return new TaskQueue.Task(id, "e" + id, "CHANNEL_PAYMENT", "p" + id, "m", 1, "lease" + id);
  }

  @Test
  void concurrentCallersWaitForCommittedResultsAndReceiveTheirOwnLeaseOutcome() throws Exception {
    var queue = mock(TaskQueue.class);
    var metrics = new SimpleMeterRegistry();
    var completions = new TaskCompletions(queue, metrics);
    var start = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var sizes = new ConcurrentLinkedQueue<Integer>();
    when(queue.completeBatch(anyList()))
        .thenAnswer(
            call -> {
              List<TaskQueue.Task> tasks = call.getArgument(0);
              sizes.add(tasks.size());
              entered.countDown();
              if (!commit.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("commit not released");
              return tasks.stream().map(item -> !item.id().equals("0")).toList();
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var work = new ArrayList<Future<Boolean>>();
      for (int index = 0; index < 16; index++) {
        var item = task(Integer.toString(index));
        work.add(
            executor.submit(
                () -> {
                  start.await();
                  return completions.complete(item);
                }));
      }
      try {
        start.countDown();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(work).noneMatch(Future::isDone);
      } finally {
        commit.countDown();
      }
      for (int index = 0; index < work.size(); index++)
        assertThat(work.get(index).get(5, TimeUnit.SECONDS)).isEqualTo(index != 0);
    }
    assertThat(sizes).allMatch(size -> size > 0 && size <= 64);
    assertThat(sizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(16);
    assertThat(metrics.get("merchantpay.tasks.completion.batch.size").summary().totalAmount())
        .isEqualTo(16);
    assertThat(
            metrics
                .get("merchantpay.tasks.completion.batches")
                .tag("outcome", "committed")
                .counter()
                .count())
        .isEqualTo(sizes.size());
  }

  @Test
  void interruptedCallerCannotReturnWhileItsCommitIsStillRunning() throws Exception {
    var queue = mock(TaskQueue.class);
    var completions = new TaskCompletions(queue, new SimpleMeterRegistry());
    var entered = new CountDownLatch(1);
    var interruptSeen = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var result = new CompletableFuture<Boolean>();
    when(queue.completeBatch(anyList()))
        .thenAnswer(
            call -> {
              entered.countDown();
              boolean interrupted = false;
              while (true) {
                try {
                  if (!commit.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("commit not released");
                  break;
                } catch (InterruptedException ignored) {
                  interrupted = true;
                  interruptSeen.countDown();
                }
              }
              if (interrupted) Thread.currentThread().interrupt();
              return List.of(true);
            });
    var caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    result.complete(completions.complete(task("blocked")));
                  } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                  }
                });
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      caller.interrupt();
      assertThat(interruptSeen.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(result).isNotDone();
    } finally {
      commit.countDown();
    }
    assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
    caller.join();
  }

  @Test
  void failedCommitDoesNotPublishSuccessAndTheNextGroupCanRecover() {
    var queue = mock(TaskQueue.class);
    var metrics = new SimpleMeterRegistry();
    var completions = new TaskCompletions(queue, metrics);
    when(queue.completeBatch(anyList()))
        .thenThrow(new IllegalStateException("commit failed"))
        .thenReturn(List.of(true));
    assertThatThrownBy(() -> completions.complete(task("fail")))
        .isInstanceOf(CompletionException.class)
        .hasRootCauseMessage("commit failed");
    assertThat(completions.complete(task("retry"))).isTrue();
    assertThat(
            metrics
                .get("merchantpay.tasks.completion.batches")
                .tag("outcome", "failed")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(metrics.get("merchantpay.tasks.completion.batch.size").summary().totalAmount())
        .isEqualTo(1);
  }

  @Test
  void interruptionBeforeFlushFailsWithoutAcknowledgingTheTask() throws Exception {
    var queue = mock(TaskQueue.class);
    var completions = new TaskCompletions(queue, new SimpleMeterRegistry());
    var result = new CompletableFuture<Boolean>();
    var caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  Thread.currentThread().interrupt();
                  try {
                    assertThatThrownBy(() -> completions.complete(task("interrupted")))
                        .isInstanceOf(CompletionException.class);
                    result.complete(Thread.currentThread().isInterrupted());
                  } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                  }
                });
    assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
    caller.join();
    verifyNoInteractions(queue);
  }
}
