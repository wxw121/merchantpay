package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.web.ApiRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ApiRequestGateTest extends FinancialFixture {
  final long second = 1_800_000_000L;
  final Instant expiry = Instant.now().plusSeconds(601);

  @BeforeEach
  void tables() {
    jdbc.execute(
        "CREATE TABLE signature_nonces(key_id VARCHAR(64),nonce VARCHAR(100),expires_at TIMESTAMP(6) NOT NULL,PRIMARY KEY(key_id,nonce))");
    jdbc.execute(
        "CREATE TABLE api_rate_limits(key_id VARCHAR(64),window_second BIGINT,requests INT NOT NULL,PRIMARY KEY(key_id,window_second))");
  }

  ApiRateLimiter limiter() {
    return new ApiRateLimiter(
        jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), 16);
  }

  @Test
  void quotaRejectionConsumesNonceAndReplayDoesNotConsumeQuota() {
    jdbc.update("INSERT INTO api_rate_limits VALUES ('key',?,499)", second);
    try (var gate = limiter()) {
      assertThat(gate.admit("key", second, "one", expiry))
          .isEqualTo(ApiRateLimiter.Decision.ALLOWED);
      assertThat(gate.admit("key", second, "two", expiry))
          .isEqualTo(ApiRateLimiter.Decision.RATE_LIMITED);
      assertThat(gate.admit("key", second, "two", expiry))
          .isEqualTo(ApiRateLimiter.Decision.REPLAY);
    }
    try (var replacement = limiter()) {
      assertThat(replacement.admit("key", second + 1, "one", expiry))
          .isEqualTo(ApiRateLimiter.Decision.REPLAY);
      assertThat(replacement.admit("other", second, "one", expiry))
          .isEqualTo(ApiRateLimiter.Decision.ALLOWED);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT requests FROM api_rate_limits WHERE key_id='key' AND window_second=?",
                Integer.class,
                second))
        .isEqualTo(501);
    assertThat(count("signature_nonces")).isEqualTo(3);
  }

  @Test
  void nonceAndQuotaShareACommitAndDuplicateInBatchDoesNotPoisonNeighbours() throws Exception {
    var commits = new AtomicInteger();
    var metrics = new SimpleMeterRegistry();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            commits.incrementAndGet();
          }
        };
    var start = new CountDownLatch(1);
    try (var gate = new ApiRateLimiter(jdbc, new TransactionTemplate(manager), 16, false, metrics);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<Future<ApiRateLimiter.Decision>>();
      for (int i = 0; i < 48; i++) {
        int n = i;
        jobs.add(
            executor.submit(
                () -> {
                  start.await();
                  return gate.admit("key", second, n < 8 ? "same" : "unique-" + n, expiry);
                }));
      }
      start.countDown();
      int allowed = 0, replayed = 0;
      for (var job : jobs) {
        var result = job.get(5, TimeUnit.SECONDS);
        if (result == ApiRateLimiter.Decision.ALLOWED) allowed++;
        if (result == ApiRateLimiter.Decision.REPLAY) replayed++;
      }
      assertThat(allowed).isEqualTo(41);
      assertThat(replayed).isEqualTo(7);
    }
    assertThat(commits.get()).isBetween(1, 47);
    assertThat(metrics.get("merchantpay.api.gate.batch.size").summary().totalAmount())
        .isEqualTo(48);
    assertThat(metrics.get("merchantpay.api.gate.commits").counter().count())
        .isEqualTo(commits.get());
    assertThat(count("signature_nonces")).isEqualTo(41);
    assertThat(
            jdbc.queryForObject(
                "SELECT requests FROM api_rate_limits WHERE key_id='key'", Integer.class))
        .isEqualTo(41);
  }

  @Test
  void combinedConcurrentRequestsStillGrantExactlyFiveHundredWithBoundedStatements()
      throws Exception {
    var largest = new AtomicInteger();
    var observed =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO signature_nonces"))
              largest.accumulateAndGet(args.length / 3, Math::max);
            return super.update(sql, args);
          }
        };
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var start = new CountDownLatch(1);
    try (var one = new ApiRateLimiter(observed, tx, 128);
        var two = new ApiRateLimiter(observed, tx, 128);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<Future<ApiRateLimiter.Decision>>();
      for (int i = 0; i < 520; i++) {
        var gate = i % 2 == 0 ? one : two;
        int n = i;
        jobs.add(
            executor.submit(
                () -> {
                  start.await();
                  return gate.admit("key", second, "nonce-" + n, expiry);
                }));
      }
      start.countDown();
      int allowed = 0, limited = 0;
      for (var job : jobs) {
        var result = job.get(5, TimeUnit.SECONDS);
        if (result == ApiRateLimiter.Decision.ALLOWED) allowed++;
        if (result == ApiRateLimiter.Decision.RATE_LIMITED) limited++;
      }
      assertThat(allowed).isEqualTo(500);
      assertThat(limited).isEqualTo(20);
    }
    assertThat(largest.get()).isBetween(1, 64);
    assertThat(count("signature_nonces")).isEqualTo(520);
    assertThat(jdbc.queryForObject("SELECT requests FROM api_rate_limits", Integer.class))
        .isEqualTo(520);
  }

  @Test
  void invalidNonceDataFailsClosedRatherThanBeingIgnored() {
    try (var gate = limiter()) {
      assertThatThrownBy(() -> gate.admit("key", second, "x".repeat(101), expiry))
          .isInstanceOf(ApiRateLimiter.Unavailable.class);
    }
    assertThat(count("signature_nonces")).isZero();
    assertThat(count("api_rate_limits")).isZero();
  }

  @Test
  void twoInstancesAllowOnlyOneDurableNonce() throws Exception {
    var start = new CountDownLatch(1);
    try (var one = limiter();
        var two = limiter();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<Future<ApiRateLimiter.Decision>>();
      for (int i = 0; i < 40; i++) {
        var gate = i % 2 == 0 ? one : two;
        jobs.add(
            executor.submit(
                () -> {
                  start.await();
                  return gate.admit("key", second, "same", expiry);
                }));
      }
      start.countDown();
      int allowed = 0;
      for (var job : jobs)
        if (job.get(5, TimeUnit.SECONDS) == ApiRateLimiter.Decision.ALLOWED) allowed++;
      assertThat(allowed).isEqualTo(1);
    }
    assertThat(count("signature_nonces")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT requests FROM api_rate_limits", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void neitherNonceNorAllowanceIsPublishedBeforeCommit() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            entered.countDown();
            try {
              if (!release.await(3, TimeUnit.SECONDS))
                throw new IllegalStateException("commit not released");
            } catch (InterruptedException failure) {
              throw new IllegalStateException(failure);
            }
            super.doCommit(status);
          }
        };
    try (var gate = new ApiRateLimiter(jdbc, new TransactionTemplate(manager), 16);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var job = executor.submit(() -> gate.admit("key", second, "commit", expiry));
      try {
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(job.isDone()).isFalse();
        assertThat(count("signature_nonces")).isZero();
        assertThat(count("api_rate_limits")).isZero();
      } finally {
        release.countDown();
      }
      assertThat(job.get(2, TimeUnit.SECONDS)).isEqualTo(ApiRateLimiter.Decision.ALLOWED);
    }
  }

  @Test
  void quotaFailureRollsBackNonceAndPublishesNoAllowance() {
    var broken =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            if (sql.contains("api_rate_limits"))
              throw new org.springframework.dao.TransientDataAccessResourceException(
                  "injected quota failure");
            return super.update(sql, args);
          }
        };
    try (var gate =
        new ApiRateLimiter(
            broken,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            16)) {
      assertThatThrownBy(() -> gate.admit("key", second, "retry", expiry))
          .isInstanceOf(ApiRateLimiter.Unavailable.class);
    }
    assertThat(count("signature_nonces")).isZero();
    assertThat(count("api_rate_limits")).isZero();
    try (var gate = limiter()) {
      assertThat(gate.admit("key", second, "retry", expiry))
          .isEqualTo(ApiRateLimiter.Decision.ALLOWED);
    }
  }

  @Test
  void commitFailurePublishesNoAllowanceAndRollsBackBothWrites() {
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            throw new TransactionSystemException("injected commit failure");
          }
        };
    manager.setRollbackOnCommitFailure(true);
    try (var gate = new ApiRateLimiter(jdbc, new TransactionTemplate(manager), 16)) {
      assertThatThrownBy(() -> gate.admit("key", second, "failed", expiry))
          .isInstanceOf(ApiRateLimiter.Unavailable.class);
    }
    assertThat(count("signature_nonces")).isZero();
    assertThat(count("api_rate_limits")).isZero();
  }
}
