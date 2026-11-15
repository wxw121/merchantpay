package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.DatabaseRole;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded four-source scanner and ClickHouse delivery; source transactions stay local. */
@Component
@ConditionalOnProperty(
    name = "merchantpay.sharding.projection-worker-enabled",
    havingValue = "true")
public class ShardProjectionWorker implements AutoCloseable {
  private final ShardProjectionService shards;
  private final int batchSize, concurrency;
  private final Semaphore available;
  private final ExecutorService executor;
  private final AtomicInteger cursor = new AtomicInteger();
  private volatile boolean closed;

  public ShardProjectionWorker(
      ShardProjectionService shards,
      @Value("${merchantpay.projection-batch-size:128}") int batchSize,
      @Value("${merchantpay.projection-concurrency:2}") int concurrency) {
    if (batchSize < 1 || batchSize > 256 || concurrency < 1 || concurrency > 8)
      throw new IllegalArgumentException("Projection batches must be 1..256 and concurrency 1..8");
    this.shards = shards;
    this.batchSize = batchSize;
    this.concurrency = concurrency;
    available = new Semaphore(concurrency);
    executor =
        Executors.newFixedThreadPool(
            concurrency, Thread.ofVirtual().name("shard-projection-", 0).factory());
  }

  private int nextSource() {
    return Math.floorMod(cursor.getAndIncrement(), 4);
  }

  @Scheduled(fixedDelayString = "${merchantpay.projection-poll-delay:50}")
  void poll() {
    DatabaseRole.worker(
        () -> {
          for (int i = 0; i < 4 && !closed && available.tryAcquire(); i++) {
            boolean submitted = false;
            int physical = nextSource();
            try {
              var work = shards.claim(physical, batchSize);
              if (work.isEmpty()) continue;
              executor.execute(
                  () -> {
                    try {
                      DatabaseRole.worker(() -> shards.deliver(physical, work));
                    } finally {
                      available.release();
                    }
                  });
              submitted = true;
            } catch (RejectedExecutionException shutdown) {
              if (!closed) throw shutdown;
              break;
            } finally {
              if (!submitted) available.release();
            }
          }
        });
  }

  @Scheduled(fixedDelay = 500)
  void scan() {
    if (!closed) DatabaseRole.worker(() -> shards.scanNext(nextSource()));
  }

  @Scheduled(fixedDelay = 5000)
  void recoverPolling() {
    if (!closed) DatabaseRole.worker(() -> shards.recoverPollingFacts(nextSource()));
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    closed = true;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(20, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
