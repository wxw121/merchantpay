package dev.merchantpay.platform.projection;

import dev.merchantpay.platform.common.DatabaseRole;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "merchantpay.read-model-worker-enabled", havingValue = "true")
public class ProjectionWorker implements AutoCloseable {
  private final ProjectionService projection;
  private final int batchSize, concurrency;
  private final Semaphore available;
  private final ExecutorService executor;
  private volatile boolean closed;

  public ProjectionWorker(
      ProjectionService projection,
      @Value("${merchantpay.projection-batch-size:128}") int batchSize,
      @Value("${merchantpay.projection-concurrency:2}") int concurrency) {
    if (batchSize < 1 || batchSize > 256 || concurrency < 1 || concurrency > 8)
      throw new IllegalArgumentException("Projection batches must be 1..256 and concurrency 1..8");
    this.projection = projection;
    this.batchSize = batchSize;
    this.concurrency = concurrency;
    available = new Semaphore(concurrency);
    executor =
        Executors.newFixedThreadPool(
            concurrency, Thread.ofVirtual().name("projection-delivery-", 0).factory());
  }

  @Scheduled(fixedDelayString = "${merchantpay.projection-poll-delay:50}")
  void poll() {
    DatabaseRole.worker(
        () -> {
          for (int i = 0; i < concurrency && !closed && available.tryAcquire(); i++) {
            boolean submitted = false;
            try {
              var work = projection.claim(batchSize);
              if (work.isEmpty()) break;
              // A permit is acquired before claim, so leases never wait in an executor backlog.
              executor.execute(
                  () -> {
                    try {
                      DatabaseRole.worker(() -> projection.deliver(work));
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
    if (!closed) DatabaseRole.worker(projection::scanNext);
  }

  @Scheduled(fixedDelay = 5000)
  void recoverPolling() {
    if (!closed) DatabaseRole.worker(projection::recoverPollingFacts);
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
