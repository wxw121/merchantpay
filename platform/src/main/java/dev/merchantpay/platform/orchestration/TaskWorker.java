package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.DatabaseRole;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.reconciliation.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "merchantpay.workers-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TaskWorker implements AutoCloseable {
  private final TaskQueue queue;
  private final TaskCompletions completions;
  private final ChannelGateway gateway;
  private final InboxService inbox;
  private final NotificationService notifications;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final MeterRegistry metrics;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore capacity;
  private static final String[] FAST_LANES = {"INBOX", "FAST", "NOTIFY"};
  private final Semaphore[] laneCapacities;
  private final boolean rotateLanes;
  private final boolean channelTaskPrefetch;
  private int firstLane;
  private final Semaphore bulkCapacity = new Semaphore(2);
  private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2);
  private final ReconciliationService reconciliation;
  private final ExportService exports;
  private final java.util.Map<String, Integer> emptyPolls = new java.util.HashMap<>();
  private final java.util.Map<String, Long> nextPoll = new java.util.HashMap<>();
  // Monotonic deadlines are unaffected by wall-clock corrections used for financial timestamps.
  java.util.function.LongSupplier nanoTime = System::nanoTime;

  public TaskWorker(
      TaskQueue queue,
      ChannelGateway gateway,
      InboxService inbox,
      NotificationService notifications,
      JdbcTemplate jdbc,
      Clock clock,
      MeterRegistry metrics,
      ReconciliationService reconciliation,
      ExportService exports,
      @Value("${merchantpay.tasks-fast-concurrency:30}") int fastConcurrency) {
    this(
        queue,
        gateway,
        inbox,
        notifications,
        jdbc,
        clock,
        metrics,
        reconciliation,
        exports,
        fastConcurrency,
        false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public TaskWorker(
      TaskQueue queue,
      ChannelGateway gateway,
      InboxService inbox,
      NotificationService notifications,
      JdbcTemplate jdbc,
      Clock clock,
      MeterRegistry metrics,
      ReconciliationService reconciliation,
      ExportService exports,
      @Value("${merchantpay.tasks-fast-concurrency:30}") int fastConcurrency,
      @Value("${merchantpay.channel-task-prefetch:false}") boolean prefetch) {
    this.channelTaskPrefetch = prefetch;
    if (fastConcurrency < 1 || fastConcurrency > 256) {
      throw new IllegalArgumentException("Fast task concurrency must be between 1 and 256");
    }
    this.capacity = new Semaphore(fastConcurrency);
    int reserved = Math.max(1, fastConcurrency / 6);
    this.laneCapacities =
        new Semaphore[] {
          new Semaphore(reserved),
          new Semaphore(Math.max(1, fastConcurrency - 2 * reserved)),
          new Semaphore(reserved)
        };
    this.rotateLanes = fastConcurrency < 3;
    this.queue = queue;
    this.completions = new TaskCompletions(queue, metrics);
    this.gateway = gateway;
    this.inbox = inbox;
    this.notifications = notifications;
    this.jdbc = jdbc;
    this.clock = clock;
    this.metrics = metrics;
    this.reconciliation = reconciliation;
    this.exports = exports;
  }

  @Scheduled(fixedDelay = 10)
  void poll() {
    DatabaseRole.worker(this::pollWorker);
  }

  private void pollWorker() {
    int nextLane = firstLane;
    for (int offset = 0; offset < FAST_LANES.length; offset++) {
      int lane = (firstLane + offset) % FAST_LANES.length;
      if (pollCapacity(laneCapacities[lane], FAST_LANES[lane]))
        nextLane = (lane + 1) % FAST_LANES.length;
    }
    if (rotateLanes) firstLane = nextLane;
    pollCapacity(bulkCapacity, "BULK");
  }

  private boolean pollCapacity(Semaphore permits, String queueClass) {
    long started = nanoTime.getAsLong();
    Long deadline = nextPoll.get(queueClass);
    if (deadline != null && started - deadline < 0) return false;
    boolean fast = !queueClass.equals("BULK");
    int slots = Math.min(permits.availablePermits(), 64);
    if (fast) slots = Math.min(slots, capacity.availablePermits());
    if (slots == 0) {
      return false;
    }
    if (!permits.tryAcquire(slots)) return false;
    if (fast && !capacity.tryAcquire(slots)) {
      permits.release(slots);
      return false;
    }
    java.util.List<TaskQueue.Task> claimed;
    try {
      claimed = queue.claimBatch(slots, queueClass);
    } catch (RuntimeException unavailable) {
      permits.release(slots);
      if (fast) capacity.release(slots);
      metrics.counter("merchantpay.tasks.claim.errors").increment();
      nextPoll.put(queueClass, nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(100));
      return false;
    }
    if (claimed.isEmpty()) {
      int empties = Math.min(4, emptyPolls.getOrDefault(queueClass, 0) + 1);
      emptyPolls.put(queueClass, empties);
      int delay =
          switch (empties) {
            case 1 -> 10;
            case 2 -> 20;
            case 3 -> 50;
            default -> 100;
          };
      nextPoll.put(queueClass, nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(delay));
    } else {
      emptyPolls.remove(queueClass);
      nextPoll.remove(queueClass);
    }
    permits.release(slots - claimed.size());
    if (fast) capacity.release(slots - claimed.size());
    java.util.Map<String, ChannelGateway.TaskInput> inputs = java.util.Map.of();
    if (channelTaskPrefetch && queueClass.equals("FAST") && claimed.size() > 1) {
      try {
        inputs = gateway.prefetch(claimed);
      } catch (RuntimeException unavailable) {
        metrics.counter("merchantpay.channel.prefetch.failures").increment();
      }
    }
    for (var task : claimed) {
      var input = inputs.get(task.id());
      try {
        executor.submit(
            () ->
                DatabaseRole.worker(
                    () -> {
                      ScheduledFuture<?> heartbeat = null;
                      var sample = Timer.start(metrics);
                      String outcome = "failed";
                      try {
                        heartbeat =
                            heartbeats.scheduleAtFixedRate(
                                () -> DatabaseRole.worker(() -> queue.renew(task)),
                                15,
                                15,
                                TimeUnit.SECONDS);
                        switch (task.kind()) {
                          case "CHANNEL_PAYMENT", "CHANNEL_REFUND", "CHANNEL_CLOSE" -> {
                            if (input == null) gateway.execute(task);
                            else gateway.execute(task, input);
                          }
                          case "PROCESS_INBOX" -> inbox.process(task.aggregateId());
                          case "PAYMENT_SUCCEEDED",
                              "PAYMENT_FAILED",
                              "PAYMENT_CLOSED",
                              "REFUND_SUCCEEDED",
                              "REFUND_FAILED" ->
                              notifications.deliver(task);
                          case "RECONCILE" -> reconciliation.process(task.aggregateId(), task);
                          case "EXPORT" -> exports.process(task.aggregateId(), task);
                          default ->
                              throw new IllegalArgumentException(
                                  "Unsupported task type: " + task.kind());
                        }
                        outcome = completions.complete(task) ? "completed" : "lease_lost";
                        if (outcome.equals("completed"))
                          metrics
                              .counter("merchantpay.tasks.completed", "kind", task.kind())
                              .increment();
                      } catch (RuntimeException failure) {
                        retry(task, failure);
                        outcome = "retried";
                      } finally {
                        if (heartbeat != null) heartbeat.cancel(false);
                        permits.release();
                        if (fast) capacity.release();
                        sample.stop(
                            metrics.timer(
                                "merchantpay.tasks.execution",
                                "kind",
                                task.kind(),
                                "outcome",
                                outcome));
                      }
                    }));
      } catch (RejectedExecutionException unavailable) {
        permits.release();
        if (fast) capacity.release();
        retry(task, unavailable);
      }
    }
    return !claimed.isEmpty();
  }

  private void retry(TaskQueue.Task task, RuntimeException failure) {
    try {
      queue.retry(task, failure);
      metrics.counter("merchantpay.tasks.retried", "kind", task.kind()).increment();
    } catch (RuntimeException unavailable) {
      // Keep the durable lease for expiry/reclaim if retry persistence is unavailable.
      metrics.counter("merchantpay.tasks.retry.errors").increment();
    }
  }

  @Scheduled(fixedDelay = 250)
  void expireSecurityWindows() {
    DatabaseRole.worker(
        () -> {
          clean(
              "DELETE FROM signature_nonces WHERE expires_at<? ORDER BY expires_at LIMIT 500",
              Timestamp.from(clock.instant()));
          clean(
              "DELETE FROM api_rate_limits WHERE window_second<? ORDER BY window_second LIMIT 500",
              clock.instant().minusSeconds(600).getEpochSecond());
        });
  }

  private void clean(String sql, Object cutoff) {
    long started = nanoTime.getAsLong();
    for (int batch = 0; batch < 4; batch++) {
      // Budget limits starting another transaction; an in-flight database call can exceed it.
      if (batch > 0 && nanoTime.getAsLong() - started >= TimeUnit.MILLISECONDS.toNanos(50)) break;
      int deleted =
          java.util.Objects.requireNonNull(
              jdbc.execute(
                  sql,
                  (org.springframework.jdbc.core.PreparedStatementCallback<Integer>)
                      statement -> {
                        statement.setQueryTimeout(1);
                        statement.setObject(1, cutoff);
                        return statement.executeUpdate();
                      }));
      if (deleted < 500) break;
    }
  }

  @Override
  public void close() {
    executor.close();
    heartbeats.shutdown();
  }
}
