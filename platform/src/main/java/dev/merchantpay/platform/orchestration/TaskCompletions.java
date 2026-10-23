package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.outbox.TaskQueue;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Worker callers retain their capacity and heartbeat until their commit group finishes. */
final class TaskCompletions {
  private record Pending(TaskQueue.Task task, CompletableFuture<Boolean> result) {}

  private final TaskQueue queue;
  private final MeterRegistry metrics;
  private List<Pending> pending;

  TaskCompletions(TaskQueue queue, MeterRegistry metrics) {
    this.queue = queue;
    this.metrics = metrics;
  }

  boolean complete(TaskQueue.Task task) {
    var request = new Pending(task, new CompletableFuture<>());
    List<Pending> group;
    boolean leader;
    synchronized (this) {
      leader = pending == null || pending.size() == 64;
      if (leader) pending = new ArrayList<>();
      group = pending;
      group.add(request);
    }
    if (leader) {
      Throwable failure = null;
      try {
        Thread.sleep(10);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        failure = interrupted;
      }
      synchronized (this) {
        if (pending == group) pending = null;
        group = List.copyOf(group);
      }
      try {
        if (failure != null)
          throw new IllegalStateException("Task completion interrupted before commit", failure);
        var outcomes = queue.completeBatch(group.stream().map(Pending::task).toList());
        if (outcomes.size() != group.size())
          throw new IllegalStateException("Missing completion results");
        metrics.summary("merchantpay.tasks.completion.batch.size").record(group.size());
        metrics.counter("merchantpay.tasks.completion.batches", "outcome", "committed").increment();
        for (int index = 0; index < group.size(); index++)
          group.get(index).result().complete(outcomes.get(index));
      } catch (RuntimeException | Error unavailable) {
        metrics.counter("merchantpay.tasks.completion.batches", "outcome", "failed").increment();
        for (var item : group) item.result().completeExceptionally(unavailable);
        if (unavailable instanceof Error fatal) throw fatal;
      }
    }
    // An interrupted follower still owns its worker slot until JDBC/COMMIT actually finishes.
    return request.result().join();
  }
}
