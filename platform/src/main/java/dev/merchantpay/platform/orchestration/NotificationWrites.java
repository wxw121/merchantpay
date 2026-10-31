package dev.merchantpay.platform.orchestration;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Caller-led, bounded commits; notification workers retain their permits while awaiting JDBC. */
@Component
public final class NotificationWrites implements AutoCloseable {
  private record Pending(Runnable action, CompletableFuture<Void> result) {}

  private final TransactionTemplate transactions;
  private final MeterRegistry metrics;
  private final int maximum;
  private final Duration delay;
  private List<Pending> pending;
  private volatile boolean closed;

  public NotificationWrites(
      PlatformTransactionManager manager,
      MeterRegistry metrics,
      @Value("${merchantpay.notification-write-batch-size:1}") int maximum,
      @Value("${merchantpay.notification-write-batch-delay:0ms}") Duration delay) {
    if (maximum < 1
        || maximum > 64
        || delay == null
        || delay.isNegative()
        || delay.compareTo(Duration.ofMillis(50)) > 0)
      throw new IllegalArgumentException("Notification batches require 1..64 writes and 0..50ms");
    transactions = new TransactionTemplate(manager);
    transactions.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(3);
    this.metrics = metrics;
    this.maximum = maximum;
    this.delay = delay;
  }

  public void execute(Runnable action) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Notification writes must not span an outer transaction");
    if (closed) throw new IllegalStateException("Notification writes are closed");
    if (maximum == 1) {
      action.run();
      return;
    }
    var request = new Pending(action, new CompletableFuture<>());
    List<Pending> group;
    boolean leader;
    synchronized (this) {
      if (closed) throw new IllegalStateException("Notification writes are closed");
      leader = pending == null || pending.size() == maximum;
      if (leader) pending = new ArrayList<>();
      group = pending;
      group.add(request);
    }
    if (leader) {
      Throwable interrupted = null;
      try {
        Thread.sleep(delay);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        interrupted = failure;
      }
      synchronized (this) {
        if (pending == group) pending = null;
        group = List.copyOf(group);
      }
      try {
        if (interrupted != null)
          throw new IllegalStateException(
              "Notification group interrupted before commit", interrupted);
        var writes = group;
        transactions.executeWithoutResult(ignored -> writes.forEach(item -> item.action().run()));
        metrics.summary("merchantpay.notification.write.batch.size").record(group.size());
        metrics
            .counter("merchantpay.notification.write.batches", "outcome", "committed")
            .increment();
        group.forEach(item -> item.result().complete(null));
      } catch (RuntimeException | Error failure) {
        metrics.counter("merchantpay.notification.write.batches", "outcome", "failed").increment();
        group.forEach(item -> item.result().completeExceptionally(failure));
      }
    }
    try {
      request.result().join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      if (failure.getCause() instanceof Error fatal) throw fatal;
      throw failure;
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
  }
}
