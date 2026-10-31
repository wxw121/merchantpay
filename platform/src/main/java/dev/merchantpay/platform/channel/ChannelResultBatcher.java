package dev.merchantpay.platform.channel;

import dev.merchantpay.platform.common.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Coalesces independent channel outcomes into fewer database commits. */
@Component
public final class ChannelResultBatcher implements AutoCloseable {
  private record Pending(String lockKey, Runnable action, CompletableFuture<Void> result) {}

  private final TransactionTemplate transactions;
  private final MeterRegistry metrics;
  private final int maxBatch;
  private final Duration delay;
  private List<Pending> pending;
  private boolean closed;

  public ChannelResultBatcher(
      PlatformTransactionManager transactionManager,
      MeterRegistry metrics,
      @Value("${merchantpay.channel-result-batch-size:1}") int maxBatch,
      @Value("${merchantpay.channel-result-batch-delay:0ms}") Duration delay) {
    if (maxBatch < 1 || maxBatch > 64)
      throw new IllegalArgumentException("Channel result batch size must be between 1 and 64");
    if (delay == null || delay.isNegative() || delay.compareTo(Duration.ofMillis(50)) > 0)
      throw new IllegalArgumentException("Channel result batch delay must be between 0ms and 50ms");
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.transactions.setTimeout(3);
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.maxBatch = maxBatch;
    this.delay = delay;
  }

  public void execute(String lockKey, Runnable action) {
    var request =
        new Pending(
            Objects.requireNonNull(lockKey, "lockKey"),
            Objects.requireNonNull(action, "action"),
            new CompletableFuture<>());
    List<Pending> group;
    boolean leader;
    synchronized (this) {
      if (closed) throw new RejectedExecutionException("Channel result batcher is closed");
      leader = pending == null || pending.size() == maxBatch;
      if (leader) pending = new ArrayList<>();
      group = pending;
      group.add(request);
    }
    if (leader) executeAsLeader(group);
    await(request);
  }

  private void executeAsLeader(List<Pending> group) {
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
    if (interrupted != null) {
      fail(
          group,
          new IllegalStateException("Channel result batch interrupted before commit", interrupted));
      return;
    }
    executeGroup(group.stream().sorted(Comparator.comparing(Pending::lockKey)).toList());
  }

  private void executeGroup(List<Pending> group) {
    try {
      transactions.executeWithoutResult(ignored -> group.forEach(item -> item.action().run()));
      metrics.summary("merchantpay.channel.result.batch.size").record(group.size());
      metrics.counter("merchantpay.channel.result.batches", "outcome", "committed").increment();
      group.forEach(item -> item.result().complete(null));
    } catch (ApiException conflict) {
      if (group.size() == 1) {
        metrics.counter("merchantpay.channel.result.batches", "outcome", "failed").increment();
        fail(group, conflict);
        return;
      }
      metrics.counter("merchantpay.channel.result.batches", "outcome", "split").increment();
      int middle = group.size() / 2;
      executeGroup(group.subList(0, middle));
      executeGroup(group.subList(middle, group.size()));
    } catch (RuntimeException | Error failure) {
      metrics.counter("merchantpay.channel.result.batches", "outcome", "failed").increment();
      fail(group, failure);
    }
  }

  private static void fail(List<Pending> group, Throwable failure) {
    group.forEach(item -> item.result().completeExceptionally(failure));
  }

  private static void await(Pending request) {
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
