package dev.merchantpay.platform.projection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.DatabaseRole;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ProjectionWorkerTest {
  @Test
  void boundedParallelDeliveriesUseConfiguredBatchesWithoutQueueingLeases() throws Exception {
    var service = mock(ProjectionService.class);
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var row =
        new ProjectionService.Delivery(
            "batch", "token", "generation", "payment", "merchant", 1, "{}", "hash");
    when(service.claim(128))
        .thenAnswer(
            call -> {
              assertThat(DatabaseRole.isWorker()).isTrue();
              return List.of(row);
            });
    doAnswer(
            call -> {
              assertThat(DatabaseRole.isWorker()).isTrue();
              entered.countDown();
              if (!release.await(2, TimeUnit.SECONDS))
                throw new AssertionError("release timed out");
              return null;
            })
        .when(service)
        .deliver(anyList());
    try (var worker = new ProjectionWorker(service, 128, 2)) {
      try {
        worker.poll();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        worker.poll();
        verify(service, times(2)).claim(128);
        verify(service, never()).scanNext();
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void emptyQueueStopsClaimingAndInvalidBudgetsFailBeforeCreatingWorkers() {
    var service = mock(ProjectionService.class);
    when(service.claim(64)).thenReturn(List.of());
    try (var worker = new ProjectionWorker(service, 64, 4)) {
      worker.poll();
      verify(service).claim(64);
      verify(service, never()).deliver(anyList());
    }
    assertThatThrownBy(() -> new ProjectionWorker(service, 0, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProjectionWorker(service, 257, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProjectionWorker(service, 128, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
