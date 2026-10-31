package dev.merchantpay.platform.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.outbox.TaskQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TaskPrefetchWorkerTest {
  final TaskQueue queue = mock(TaskQueue.class);
  final ChannelGateway gateway = mock(ChannelGateway.class);
  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  final TaskQueue.Task a = new TaskQueue.Task("a", "ea", "CHANNEL_PAYMENT", "pa", "ma", 1, "la");
  final TaskQueue.Task b = new TaskQueue.Task("b", "eb", "CHANNEL_PAYMENT", "pb", "mb", 1, "lb");

  TaskWorker worker(boolean enabled) {
    when(queue.claimBatch(anyInt(), anyString())).thenReturn(List.of());
    when(queue.claimBatch(anyInt(), eq("FAST"))).thenReturn(List.of(a, b));
    when(queue.completeBatch(anyList()))
        .thenAnswer(c -> ((List<?>) c.getArgument(0)).stream().map(t -> true).toList());
    return new TaskWorker(
        queue, gateway, null, null, null, Clock.systemUTC(), metrics, null, null, 96, enabled);
  }

  void drained(TaskWorker worker) throws Exception {
    var field = TaskWorker.class.getDeclaredField("capacity");
    field.setAccessible(true);
    var permits = (Semaphore) field.get(worker);
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> permits.availablePermits() == 96);
  }

  @Test
  void enabledBatchUsesSnapshotsOnceForClaimedTasks() throws Exception {
    var ia = new ChannelGateway.TaskInput(a, null, null);
    var ib = new ChannelGateway.TaskInput(b, null, null);
    when(gateway.prefetch(List.of(a, b))).thenReturn(Map.of("a", ia, "b", ib));
    try (var worker = worker(true)) {
      worker.poll();
      drained(worker);
      verify(gateway).prefetch(List.of(a, b));
      verify(gateway).execute(a, ia);
      verify(gateway).execute(b, ib);
      verify(gateway, never()).execute(any());
    }
  }

  @Test
  void disabledFlagRetainsIndividualPath() throws Exception {
    try (var worker = worker(false)) {
      worker.poll();
      drained(worker);
      verify(gateway, never()).prefetch(any());
      verify(gateway).execute(a);
      verify(gateway).execute(b);
    }
  }

  @Test
  void prefetchFailureFallsBackAndReturnsAllPermits() throws Exception {
    when(gateway.prefetch(any())).thenThrow(new IllegalStateException("query unavailable"));
    try (var worker = worker(true)) {
      worker.poll();
      drained(worker);
      verify(gateway).execute(a);
      verify(gateway).execute(b);
      assertThat(metrics.get("merchantpay.channel.prefetch.failures").counter().count())
          .isEqualTo(1);
    }
  }

  @Test
  void executorRejectionRetriesClaimsAndReleasesCapacity() throws Exception {
    try (var worker = worker(true)) {
      when(queue.claimBatch(anyInt(), eq("FAST")))
          .thenAnswer(
              c -> {
                worker.close();
                return List.of(a, b);
              });
      worker.poll();
      drained(worker);
      verify(queue).retry(eq(a), any(RejectedExecutionException.class));
      verify(queue).retry(eq(b), any(RejectedExecutionException.class));
      var field = TaskWorker.class.getDeclaredField("laneCapacities");
      field.setAccessible(true);
      assertThat(((Semaphore[]) field.get(worker))[1].availablePermits()).isEqualTo(64);
    }
  }
}
