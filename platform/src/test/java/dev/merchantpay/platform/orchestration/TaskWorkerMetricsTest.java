package dev.merchantpay.platform.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.ChannelGateway;
import dev.merchantpay.platform.channel.InboxService;
import dev.merchantpay.platform.outbox.TaskQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskWorkerMetricsTest {
  @Test
  void executionDurationDistinguishesCompletionRetryAndLostLease() {
    var queue = mock(TaskQueue.class);
    var gateway = mock(ChannelGateway.class);
    var metrics = new SimpleMeterRegistry();
    var done = new TaskQueue.Task("1", "e1", "CHANNEL_PAYMENT", "p1", "m", 1, "t1");
    var retry = new TaskQueue.Task("2", "e2", "CHANNEL_PAYMENT", "p2", "m", 1, "t2");
    var lost = new TaskQueue.Task("3", "e3", "CHANNEL_PAYMENT", "p3", "m", 1, "t3");
    when(queue.claimBatch(20, "FAST")).thenReturn(List.of(done, retry, lost));
    when(queue.claimBatch(2, "BULK")).thenReturn(List.of());
    when(queue.completeBatch(anyList()))
        .thenAnswer(
            call -> {
              List<TaskQueue.Task> tasks = call.getArgument(0);
              return tasks.stream().map(task -> task.id().equals(done.id())).toList();
            });
    doThrow(new IllegalStateException("temporary")).when(gateway).execute(retry);
    try (var worker =
        new TaskWorker(
            queue,
            gateway,
            mock(InboxService.class),
            mock(NotificationService.class),
            null,
            Clock.systemUTC(),
            metrics,
            null,
            null,
            30)) {
      worker.poll();
    }
    for (String outcome : List.of("completed", "retried", "lease_lost")) {
      assertThat(
              metrics
                  .get("merchantpay.tasks.execution")
                  .tag("kind", "CHANNEL_PAYMENT")
                  .tag("outcome", outcome)
                  .timer()
                  .count())
          .isEqualTo(1);
    }
    verify(queue).retry(eq(retry), any(RuntimeException.class));
    assertThat(
            metrics
                .get("merchantpay.tasks.completed")
                .tag("kind", "CHANNEL_PAYMENT")
                .counter()
                .count())
        .isEqualTo(1);
  }
}
