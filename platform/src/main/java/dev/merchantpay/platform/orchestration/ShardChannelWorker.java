package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.common.DatabaseRole;
import dev.merchantpay.platform.sharding.ShardTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = {"merchantpay.sharding.channel-workers-enabled", "merchantpay.workers-enabled"},
    havingValue = "true")
public final class ShardChannelWorker implements AutoCloseable {
  private final ShardChannels channels;
  private final ShardTransactions routes;
  private final TaskWorker legacy;
  private final MeterRegistry metrics;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2);
  private final Map<String, Long> deadlines = new HashMap<>();
  private volatile boolean closed;
  private int firstBucket, firstLane;

  public ShardChannelWorker(
      ShardChannels channels, ShardTransactions routes, TaskWorker legacy, MeterRegistry metrics) {
    this.channels = channels;
    this.routes = routes;
    this.legacy = legacy;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelay = 10)
  void poll() {
    if (!closed) DatabaseRole.worker(this::pollWorker);
  }

  private void pollWorker() {
    String lane =
        switch (firstLane) {
          case 0 -> "INBOX";
          case 1 -> "FAST";
          default -> "NOTIFY";
        };
    firstLane = (firstLane + 1) % 3;
    if (!legacy.wantsQueue(lane)) return;
    Semaphore permits = legacy.channelLaneCapacity(lane);
    int start = firstBucket;
    firstBucket = (firstBucket + 1) % 16;
    for (int offset = 0; offset < 16 && !closed; offset++) {
      int bucket = (start + offset) % 16;
      String key = lane + bucket;
      Long deadline = deadlines.get(key);
      if (deadline != null && System.nanoTime() - deadline < 0) continue;
      int slots =
          Math.min(
              64,
              Math.min(permits.availablePermits(), legacy.sharedFastCapacity().availablePermits()));
      if (slots == 0 || !permits.tryAcquire(slots)) return;
      if (!legacy.sharedFastCapacity().tryAcquire(slots)) {
        permits.release(slots);
        return;
      }
      List<ShardChannels.Execution> tasks;
      try {
        tasks = channels.claim(routes.currentRoute(bucket), lane, slots);
      } catch (RuntimeException failure) {
        legacy.sharedFastCapacity().release(slots);
        permits.release(slots);
        deadlines.put(key, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
        metrics.counter("merchantpay.shards.claim.errors").increment();
        continue;
      }
      legacy.sharedFastCapacity().release(slots - tasks.size());
      permits.release(slots - tasks.size());
      if (tasks.isEmpty())
        deadlines.put(key, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
      else deadlines.remove(key);
      for (var task : tasks) {
        try {
          executor.submit(() -> DatabaseRole.worker(() -> execute(task, permits)));
        } catch (RejectedExecutionException stopped) {
          legacy.sharedFastCapacity().release();
          permits.release();
        }
      }
    }
  }

  private void execute(ShardChannels.Execution execution, Semaphore permits) {
    ScheduledFuture<?> heartbeat = null;
    try {
      heartbeat =
          heartbeats.scheduleAtFixedRate(
              () ->
                  DatabaseRole.worker(
                      () -> {
                        try {
                          channels.renew(execution);
                        } catch (RuntimeException lost) {
                          metrics.counter("merchantpay.shards.renew.errors").increment();
                        }
                      }),
              15,
              15,
              TimeUnit.SECONDS);
      if (channels.execute(execution)) {
        channels.complete(execution);
        metrics.counter("merchantpay.shards.tasks.completed").increment();
      }
    } catch (RuntimeException failure) {
      try {
        channels.retry(execution, failure);
      } catch (RuntimeException unavailable) {
        metrics.counter("merchantpay.shards.retry.errors").increment();
      }
    } finally {
      if (heartbeat != null) heartbeat.cancel(false);
      legacy.sharedFastCapacity().release();
      permits.release();
    }
  }

  @Override
  public void close() {
    closed = true;
    executor.close();
    heartbeats.shutdown();
  }
}
