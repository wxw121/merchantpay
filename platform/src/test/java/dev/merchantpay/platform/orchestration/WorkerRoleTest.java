package dev.merchantpay.platform.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.outbox.TaskQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerRoleTest {
  @Test
  void selectedRolesNeverClaimOtherQueues() {
    var queue = mock(TaskQueue.class);
    when(queue.claimBatch(anyInt(), anyString())).thenReturn(List.of());
    try (var worker =
        new TaskWorker(
            queue,
            null,
            null,
            null,
            null,
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            null,
            null,
            30)) {
      worker.setWorkerQueues("INBOX,BULK");
      worker.poll();
      verify(queue).claimBatch(anyInt(), eq("INBOX"));
      verify(queue).claimBatch(anyInt(), eq("BULK"));
      verifyNoMoreInteractions(queue);
      assertThatThrownBy(() -> worker.setWorkerQueues("FAST,typo"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
