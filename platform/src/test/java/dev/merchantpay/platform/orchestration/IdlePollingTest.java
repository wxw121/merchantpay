package dev.merchantpay.platform.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.ChannelGateway;
import dev.merchantpay.platform.outbox.TaskQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IdlePollingTest {
  @Test
  void idleLanesReduceDatabaseCallsAndStillFindNewWorkWithinOneHundredMilliseconds()
      throws Exception {
    var queue = mock(TaskQueue.class);
    when(queue.claimBatch(anyInt(), anyString())).thenReturn(List.of());
    try (var worker =
        new TaskWorker(
            queue,
            mock(ChannelGateway.class),
            null,
            null,
            null,
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            null,
            null,
            30)) {
      var nanos = new java.util.concurrent.atomic.AtomicLong();
      worker.nanoTime = nanos::get;
      // Same scheduler instant: repeated polls must not repeat an empty database transaction.
      worker.poll();
      worker.poll();
      worker.poll();
      verify(queue, times(4)).claimBatch(anyInt(), anyString());
      nanos.set(TimeUnit.MILLISECONDS.toNanos(10));
      worker.poll();
      verify(queue, times(8)).claimBatch(anyInt(), anyString());
      nanos.set(TimeUnit.MILLISECONDS.toNanos(20));
      worker.poll();
      verify(queue, times(8)).claimBatch(anyInt(), anyString());
      nanos.set(TimeUnit.MILLISECONDS.toNanos(30));
      worker.poll();
      nanos.set(TimeUnit.MILLISECONDS.toNanos(80));
      worker.poll();
      nanos.set(TimeUnit.MILLISECONDS.toNanos(179));
      worker.poll();
      verify(queue, times(16)).claimBatch(anyInt(), anyString());
      nanos.set(TimeUnit.MILLISECONDS.toNanos(180));
      worker.poll();
      verify(queue, times(20)).claimBatch(anyInt(), anyString());
    }
  }

  @Test
  void expiredCleanupHasFiniteBatchSizeAndRunBudget() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.execute(
            anyString(), any(org.springframework.jdbc.core.PreparedStatementCallback.class)))
        .thenReturn(500);
    try (var worker =
        new TaskWorker(
            mock(TaskQueue.class),
            null,
            null,
            null,
            jdbc,
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            null,
            null,
            30)) {
      worker.expireSecurityWindows();
      var calls = mockingDetails(jdbc).getInvocations();
      assertThat(calls).hasSizeBetween(2, 8);
      assertThat(calls)
          .allSatisfy(call -> assertThat((String) call.getArgument(0)).contains("LIMIT 500"));
    }
  }

  @Test
  void cleanupGivesEachDatabaseDeleteAOneSecondDeadline() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var statement = mock(java.sql.PreparedStatement.class);
    when(jdbc.execute(
            anyString(), any(org.springframework.jdbc.core.PreparedStatementCallback.class)))
        .thenAnswer(
            call ->
                ((org.springframework.jdbc.core.PreparedStatementCallback<Integer>)
                        call.getArgument(1))
                    .doInPreparedStatement(statement));
    try (var worker =
        new TaskWorker(
            mock(TaskQueue.class),
            null,
            null,
            null,
            jdbc,
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            null,
            null,
            30)) {
      worker.expireSecurityWindows();
      verify(statement, times(2)).setQueryTimeout(1);
    }
  }
}
