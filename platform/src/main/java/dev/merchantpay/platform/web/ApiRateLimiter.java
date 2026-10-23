package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.ApiException.RetryReason;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Coalesce concurrent checks; MySQL remains the durable authority for every allowance. */
@Component
public class ApiRateLimiter implements AutoCloseable {
  public static final class Unavailable extends TransientDataAccessResourceException {
    private final RetryReason reason;

    public Unavailable(RetryReason reason, Throwable cause) {
      super("API quota could not be confirmed", cause);
      this.reason = reason;
    }

    public RetryReason reason() {
      return reason;
    }
  }

  private record Window(String key, long second) {}

  private static final class Group {
    final ArrayList<CompletableFuture<Boolean>> requests = new ArrayList<>();
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ConcurrentHashMap<Window, Group> pending = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore workers;
  private volatile boolean closed;

  public ApiRateLimiter(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      @Value("${merchantpay.api-max-inflight:16}") int maximumWork) {
    if (maximumWork < 1 || maximumWork > 128)
      throw new IllegalArgumentException("API quota work capacity must be 1..128");
    this.jdbc = jdbc;
    this.workers = new Semaphore(maximumWork);
    this.transactions = new TransactionTemplate(transactions.getTransactionManager());
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.transactions.setTimeout(3);
  }

  public boolean allow(String key, long second) {
    if (closed) throw new Unavailable(RetryReason.QUOTA_UNAVAILABLE, null);
    var window = new Window(key, second);
    var result = new CompletableFuture<Boolean>();
    try {
      pending.compute(
          window,
          (ignored, group) -> {
            if (group == null) {
              group = new Group();
              group.requests.add(result);
              var owner = group;
              if (!workers.tryAcquire()) throw new Unavailable(RetryReason.QUOTA_CAPACITY, null);
              try {
                executor.execute(() -> flush(window, owner));
              } catch (RejectedExecutionException rejected) {
                workers.release();
                throw rejected;
              }
            } else {
              group.requests.add(result);
            }
            return group;
          });
      boolean allowed = result.get(5, TimeUnit.SECONDS);
      if (closed) throw new Unavailable(RetryReason.QUOTA_UNAVAILABLE, null);
      return allowed;
    } catch (InterruptedException interrupted) {
      result.cancel(false);
      Thread.currentThread().interrupt();
      throw new Unavailable(RetryReason.QUOTA_INTERRUPTED, interrupted);
    } catch (ExecutionException failure) {
      result.cancel(false);
      throw new Unavailable(RetryReason.QUOTA_DATABASE, failure);
    } catch (TimeoutException failure) {
      result.cancel(false);
      throw new Unavailable(RetryReason.QUOTA_TIMEOUT, failure);
    } catch (RejectedExecutionException failure) {
      result.cancel(false);
      throw new Unavailable(RetryReason.QUOTA_UNAVAILABLE, failure);
    }
  }

  private void flush(Window window, Group group) {
    try {
      Thread.sleep(10);
      // Map operations serialize enqueue/removal; after removal this batch has a single owner.
      pending.remove(window, group);
      int size = group.requests.size();
      int allowed =
          transactions.execute(
              tx -> {
                // The conditional mutation both locks and reserves the complete group. A hit
                // needs no counter read; missing or partially available windows use the exact path.
                if (jdbc.update(
                        "UPDATE api_rate_limits SET requests=requests+? WHERE key_id=? AND window_second=? AND requests<=?",
                        size,
                        window.key(),
                        window.second(),
                        500 - size)
                    == 1) return size;
                jdbc.update(
                    "INSERT INTO api_rate_limits(key_id,window_second,requests) VALUES (?,?,?) ON DUPLICATE KEY UPDATE requests=requests+?",
                    window.key(),
                    window.second(),
                    size,
                    size);
                int end =
                    jdbc.queryForObject(
                        "SELECT requests FROM api_rate_limits WHERE key_id=? AND window_second=?",
                        Integer.class,
                        window.key(),
                        window.second());
                return Math.max(0, Math.min(size, 500 - (end - size)));
              });
      // Publish only after commit; a batch crossing 500 grants exactly the remaining positions.
      for (int i = 0; i < size; i++) group.requests.get(i).complete(i < allowed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(window, group, interrupted);
    } catch (RuntimeException failure) {
      fail(window, group, failure);
    } finally {
      // A timed-out caller cannot release capacity still occupied by JDBC/COMMIT.
      workers.release();
    }
  }

  private void fail(Window window, Group group, Exception failure) {
    pending.remove(window, group);
    for (var request : group.requests) request.completeExceptionally(failure);
  }

  @Override
  @PreDestroy
  public void close() {
    closed = true;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
