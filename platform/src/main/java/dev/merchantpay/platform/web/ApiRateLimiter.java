package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.ApiException.RetryReason;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Coalesce concurrent checks; MySQL remains the durable authority for every allowance. */
@Component
public class ApiRateLimiter implements AutoCloseable {
  public enum Decision {
    ALLOWED,
    REPLAY,
    RATE_LIMITED
  }

  public Decision admit(String key, long second, String nonce, Instant expiry) {
    return submit(key, second, java.util.Objects.requireNonNull(nonce), Timestamp.from(expiry));
  }

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

  private record Request(String nonce, Timestamp expiry, CompletableFuture<Decision> result) {}

  private static final class Group {
    final ArrayList<Request> requests = new ArrayList<>();
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ConcurrentHashMap<Window, Group> pending = new ConcurrentHashMap<>();
  private final ExecutorService executor;
  private final Semaphore workers;
  private volatile boolean closed;
  private final MeterRegistry metrics;

  public ApiRateLimiter(JdbcTemplate jdbc, TransactionTemplate transactions, int maximumWork) {
    this(jdbc, transactions, maximumWork, false);
  }

  public ApiRateLimiter(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      @Value("${merchantpay.api-max-inflight:16}") int maximumWork,
      @Value("${merchantpay.api-quota-dedicated-executor:false}") boolean dedicated) {
    this(jdbc, transactions, maximumWork, dedicated, null);
  }

  @Autowired
  public ApiRateLimiter(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      @Value("${merchantpay.api-max-inflight:16}") int maximumWork,
      @Value("${merchantpay.api-quota-dedicated-executor:false}") boolean dedicated,
      MeterRegistry metrics) {
    if (maximumWork < 1 || maximumWork > 128)
      throw new IllegalArgumentException("API quota work capacity must be 1..128");
    this.jdbc = jdbc;
    this.metrics = metrics;
    this.workers = new Semaphore(maximumWork);
    int parallelism = Math.min(4, maximumWork);
    this.executor =
        dedicated
            ? new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maximumWork),
                Thread.ofPlatform().daemon(true).name("api-quota-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy())
            : Executors.newVirtualThreadPerTaskExecutor();
    this.transactions = new TransactionTemplate(transactions.getTransactionManager());
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.transactions.setTimeout(3);
  }

  public boolean allow(String key, long second) {
    return submit(key, second, null, null) == Decision.ALLOWED;
  }

  private Decision submit(String key, long second, String nonce, Timestamp expiry) {
    if (closed) throw new Unavailable(RetryReason.QUOTA_UNAVAILABLE, null);
    var window = new Window(key, second);
    var result = new CompletableFuture<Decision>();
    var request = new Request(nonce, expiry, result);
    try {
      pending.compute(
          window,
          (ignored, group) -> {
            if (group == null) {
              group = new Group();
              group.requests.add(request);
              var owner = group;
              if (!workers.tryAcquire()) throw new Unavailable(RetryReason.QUOTA_CAPACITY, null);
              try {
                executor.execute(new Batch(window, owner));
              } catch (RejectedExecutionException rejected) {
                workers.release();
                throw rejected;
              }
            } else {
              group.requests.add(request);
            }
            return group;
          });
      var allowed = result.get(5, TimeUnit.SECONDS);
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
      var decisions =
          transactions.execute(
              tx -> {
                boolean[] fresh = claimNonces(window.key(), group.requests);
                int count = 0;
                for (boolean claimed : fresh) if (claimed) count++;
                int allowed = count == 0 ? 0 : reserveQuota(window, count);
                var results = new ArrayList<Decision>(size);
                for (boolean claimed : fresh) {
                  results.add(
                      !claimed
                          ? Decision.REPLAY
                          : allowed-- > 0 ? Decision.ALLOWED : Decision.RATE_LIMITED);
                }
                return results;
              });
      // Both durable decisions are published only after their shared commit.
      for (int i = 0; i < size; i++) group.requests.get(i).result().complete(decisions.get(i));
      if (metrics != null) {
        long nonces = group.requests.stream().filter(request -> request.nonce() != null).count();
        if (nonces > 0) {
          metrics.summary("merchantpay.api.gate.batch.size").record(nonces);
          metrics.counter("merchantpay.api.gate.commits").increment();
        }
      }
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

  private int reserveQuota(Window window, int size) {
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
  }

  private boolean[] claimNonces(String key, List<Request> requests) {
    boolean[] fresh = new boolean[requests.size()];
    java.util.Arrays.fill(fresh, true);
    var indices =
        java.util.stream.IntStream.range(0, requests.size())
            .filter(index -> requests.get(index).nonce() != null)
            .boxed()
            .sorted(java.util.Comparator.comparing(index -> requests.get(index).nonce()))
            .toList();
    // Statement size is bounded independently of the number of callers coalesced by the quota.
    for (int start = 0; start < indices.size(); start += 64) {
      var chunk = indices.subList(start, Math.min(start + 64, indices.size()));
      var bindings = new ArrayList<Object>(chunk.size() * 3);
      for (int index : chunk) {
        var request = requests.get(index);
        bindings.add(key);
        bindings.add(request.nonce());
        bindings.add(request.expiry());
      }
      try {
        jdbc.update(
            "INSERT INTO signature_nonces(key_id,nonce,expires_at) VALUES "
                + String.join(",", java.util.Collections.nCopies(chunk.size(), "(?,?,?)")),
            bindings.toArray());
      } catch (org.springframework.dao.DuplicateKeyException duplicate) {
        // InnoDB rolls back the failed INSERT statement, not this transaction. Only the rare
        // conflict chunk falls back to individual inserts; other SQL errors fail the whole batch.
        for (int index : chunk) {
          var request = requests.get(index);
          try {
            jdbc.update(
                "INSERT INTO signature_nonces(key_id,nonce,expires_at) VALUES (?,?,?)",
                key,
                request.nonce(),
                request.expiry());
          } catch (org.springframework.dao.DuplicateKeyException replay) {
            fresh[index] = false;
          }
        }
      }
    }
    return fresh;
  }

  private void fail(Window window, Group group, Exception failure) {
    pending.remove(window, group);
    for (var request : group.requests) request.result().completeExceptionally(failure);
  }

  private final class Batch implements Runnable {
    private final Window window;
    private final Group group;

    Batch(Window window, Group group) {
      this.window = window;
      this.group = group;
    }

    @Override
    public void run() {
      flush(window, group);
    }

    void cancelBeforeStart() {
      fail(window, group, new RejectedExecutionException("Quota executor closed"));
      workers.release();
    }
  }

  private void stopNow() {
    // Only never-started batches are returned; running JDBC retains its permit until finally.
    for (var work : executor.shutdownNow()) ((Batch) work).cancelBeforeStart();
  }

  @Override
  @PreDestroy
  public void close() {
    closed = true;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) stopNow();
    } catch (InterruptedException interrupted) {
      stopNow();
      Thread.currentThread().interrupt();
    }
  }
}
