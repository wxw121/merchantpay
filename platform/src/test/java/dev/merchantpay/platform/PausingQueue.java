package dev.merchantpay.platform;

import dev.merchantpay.platform.outbox.TaskQueue;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Pause an old execution immediately before its second business checkpoint. */
final class PausingQueue extends TaskQueue {
  final CountDownLatch paused = new CountDownLatch(1), resume = new CountDownLatch(1);
  final AtomicInteger checkpoints = new AtomicInteger();

  PausingQueue(JdbcTemplate jdbc, TransactionTemplate tx) {
    super(jdbc, tx, Clock.systemUTC());
  }

  @Override
  public void fenced(Task task, Runnable checkpoint) {
    if (checkpoints.incrementAndGet() == 2) {
      paused.countDown();
      try {
        if (!resume.await(15, TimeUnit.SECONDS))
          throw new AssertionError("Test did not resume old worker");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    super.fenced(task, checkpoint);
  }
}
