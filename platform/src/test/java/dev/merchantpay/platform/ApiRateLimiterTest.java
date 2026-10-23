package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.web.ApiRateLimiter;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ApiRateLimiterTest extends FinancialFixture {
  final long second = 1_800_000_000L;

  @BeforeEach
  void quotaTable() {
    jdbc.execute(
        "CREATE TABLE api_rate_limits(key_id VARCHAR(64),window_second BIGINT,requests INT NOT NULL,PRIMARY KEY(key_id,window_second))");
  }

  ApiRateLimiter limiter(JdbcTemplate database) {
    return new ApiRateLimiter(
        database,
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
        16);
  }

  @Test
  void anExistingWindowBelowTheLimitNeedsOneStatementAndNoCounterRead() {
    jdbc.update("INSERT INTO api_rate_limits VALUES('warm',?,100)", second);
    var statements = new AtomicInteger();
    var observed =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            statements.incrementAndGet();
            return super.update(sql, args);
          }

          @Override
          public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            statements.incrementAndGet();
            return super.queryForObject(sql, type, args);
          }
        };
    try (var limiter = limiter(observed)) {
      assertThat(limiter.allow("warm", second)).isTrue();
    }
    assertThat(statements.get())
        .as("SQL round trips for an existing, available quota window")
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT requests FROM api_rate_limits WHERE key_id='warm'", Integer.class))
        .isEqualTo(101);
  }

  @Test
  void boundariesKeysWindowsAndProcessReplacementShareTheSameDurableQuota() {
    jdbc.update("INSERT INTO api_rate_limits VALUES('a',?,499)", second);
    try (var first = limiter(jdbc)) {
      assertThat(first.allow("a", second)).isTrue();
      assertThat(first.allow("a", second)).isFalse();
      assertThat(first.allow("b", second)).isTrue();
      assertThat(first.allow("a", second + 1)).isTrue();
      assertThat(first.allow("a", second)).isFalse();
    }
    try (var replacement = limiter(jdbc)) {
      assertThat(replacement.allow("a", second)).isFalse();
      assertThat(replacement.allow("a", second + 1)).isTrue();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT requests FROM api_rate_limits WHERE key_id='a' AND window_second=?",
                Integer.class,
                second))
        .isEqualTo(503);
  }

  @Test
  void twoInstancesGrantExactlyFiveHundredAndCoalesceTheirConcurrentWrites() throws Exception {
    var writes = new AtomicInteger();
    var merged = new AtomicInteger();
    var counting =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO api_rate_limits")) {
              writes.incrementAndGet();
              if (((Number) args[2]).intValue() > 1) merged.incrementAndGet();
            }
            return super.update(sql, args);
          }
        };
    try (var first = limiter(counting);
        var secondInstance = limiter(counting);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var results = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 650; i++) {
        var instance = i % 2 == 0 ? first : secondInstance;
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  return instance.allow("shared", second);
                }));
      }
      start.countDown();
      int granted = 0;
      for (var result : results) if (result.get(15, TimeUnit.SECONDS)) granted++;
      assertThat(granted).isEqualTo(500);
      assertThat(merged.get()).isPositive();
      assertThat(writes.get()).isLessThan(650);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT requests FROM api_rate_limits WHERE key_id='shared'", Integer.class))
        .isEqualTo(650);
  }

  @Test
  void failureAfterIncrementRollsBackAndDoesNotGrantOrPoisonTheNextBatch() {
    jdbc.update("INSERT INTO api_rate_limits VALUES('a',?,499)", second);
    var failNext = new java.util.concurrent.atomic.AtomicBoolean(true);
    var failing =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            int changed = super.update(sql, args);
            if (failNext.getAndSet(false))
              throw new TransientDataAccessResourceException("probe failure after mutation");
            return changed;
          }
        };
    try (var broken = limiter(failing)) {
      assertThatThrownBy(() -> broken.allow("a", second))
          .isInstanceOfSatisfying(
              ApiRateLimiter.Unavailable.class,
              e ->
                  assertThat(e.reason())
                      .isEqualTo(
                          dev.merchantpay.platform.common.ApiException.RetryReason.QUOTA_DATABASE));
      assertThat(
              jdbc.queryForObject(
                  "SELECT requests FROM api_rate_limits WHERE key_id='a'", Integer.class))
          .isEqualTo(499);
      assertThat(broken.allow("a", second)).isTrue();
      assertThat(broken.allow("a", second)).isFalse();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aCommitFailureCannotPublishAnySuccessfulAllowance(boolean existingWindow) {
    if (existingWindow) jdbc.update("INSERT INTO api_rate_limits VALUES('a',?,100)", second);
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            throw new TransactionSystemException("probe commit failure");
          }
        };
    manager.setRollbackOnCommitFailure(true);
    try (var broken = new ApiRateLimiter(jdbc, new TransactionTemplate(manager), 16)) {
      assertThatThrownBy(() -> broken.allow("a", second))
          .isInstanceOfSatisfying(
              ApiRateLimiter.Unavailable.class,
              e ->
                  assertThat(e.reason())
                      .isEqualTo(
                          dev.merchantpay.platform.common.ApiException.RetryReason.QUOTA_DATABASE));
    }
    assertThat(count("api_rate_limits")).isEqualTo(existingWindow ? 1 : 0);
    if (existingWindow)
      assertThat(
              jdbc.queryForObject(
                  "SELECT requests FROM api_rate_limits WHERE key_id='a'", Integer.class))
          .isEqualTo(100);
  }

  @Test
  void aClosedInstanceFailsClosed() {
    var closed = limiter(jdbc);
    closed.close();
    assertThatThrownBy(() -> closed.allow("a", second))
        .isInstanceOfSatisfying(
            ApiRateLimiter.Unavailable.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(
                        dev.merchantpay.platform.common.ApiException.RetryReason
                            .QUOTA_UNAVAILABLE));
    assertThat(count("api_rate_limits")).isZero();
  }

  @Test
  void interruptedCallersCannotReleaseUnfinishedDatabaseWorkAndCloseIsBounded() throws Exception {
    var entered = new CountDownLatch(16);
    var release = new CountDownLatch(1);
    var commits = new AtomicInteger();
    var cancelled = new AtomicInteger();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            if (commits.incrementAndGet() <= 16) {
              entered.countDown();
              boolean resumed = false;
              while (!resumed) {
                try {
                  release.await();
                  resumed = true;
                } catch (InterruptedException ignored) {
                  /* Model a commit transport ignoring interruption. */
                }
              }
            }
            super.doCommit(status);
          }
        };
    var limiter = new ApiRateLimiter(jdbc, new TransactionTemplate(manager), 16);
    var callers = new ArrayList<Thread>();
    try {
      for (int i = 0; i < 16; i++) {
        String key = "held-" + i;
        callers.add(
            Thread.startVirtualThread(
                () -> {
                  try {
                    limiter.allow(key, second);
                  } catch (TransientDataAccessException expected) {
                    cancelled.incrementAndGet();
                  }
                }));
      }
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      for (var caller : callers) caller.interrupt();
      for (var caller : callers) caller.join(2000);
      assertThat(cancelled.get()).isEqualTo(16);
      assertThatThrownBy(() -> limiter.allow("after-interruption", second))
          .isInstanceOfSatisfying(
              ApiRateLimiter.Unavailable.class,
              e ->
                  assertThat(e.reason())
                      .isEqualTo(
                          dev.merchantpay.platform.common.ApiException.RetryReason.QUOTA_CAPACITY));
      assertThat(commits.get()).isEqualTo(16);
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        executor.submit(limiter::close).get(8, TimeUnit.SECONDS);
      }
    } finally {
      release.countDown();
      limiter.close();
    }
  }
}
