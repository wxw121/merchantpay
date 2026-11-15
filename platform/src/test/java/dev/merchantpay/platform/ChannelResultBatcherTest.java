package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.merchantpay.platform.channel.ChannelResultBatcher;
import dev.merchantpay.platform.common.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class ChannelResultBatcherTest extends FinancialFixture {
  @Test
  void concurrentResultsShareOneOuterCommit() throws Exception {
    var commits = new AtomicInteger();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            commits.incrementAndGet();
          }
        };
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);

    try (var batcher =
            new ChannelResultBatcher(
                manager, new SimpleMeterRegistry(), 32, Duration.ofMillis(50));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                batcher.execute("payment-a", () -> insertAudit("batch-a"));
                return null;
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                batcher.execute("payment-b", () -> insertAudit("batch-b"));
                return null;
              });

      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(3, TimeUnit.SECONDS);
      second.get(3, TimeUnit.SECONDS);
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE id IN ('batch-a','batch-b')", Long.class))
        .isEqualTo(2);
    assertThat(commits.get()).isEqualTo(1);
  }

  @Test
  void businessConflictIsolatedFromValidResult() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var validRuns = new AtomicInteger();

    try (var batcher =
            new ChannelResultBatcher(
                new DataSourceTransactionManager(jdbc.getDataSource()),
                new SimpleMeterRegistry(),
                32,
                Duration.ofMillis(50));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var valid =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                batcher.execute(
                    "payment-a",
                    () -> {
                      validRuns.incrementAndGet();
                      insertAudit("isolated-valid");
                    });
                return null;
              });
      var conflict =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                batcher.execute(
                    "payment-z",
                    () -> {
                      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "conflict");
                    });
                return null;
              });

      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      valid.get(3, TimeUnit.SECONDS);
      assertThatThrownBy(() -> conflict.get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(ApiException.class);
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE id = 'isolated-valid'", Long.class))
        .isEqualTo(1);
    assertThat(validRuns.get()).isEqualTo(2);
  }

  @Test
  void infrastructureFailureFailsWholeBatchWithoutReplayingActions() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var validRuns = new AtomicInteger();

    try (var batcher =
            new ChannelResultBatcher(
                new DataSourceTransactionManager(jdbc.getDataSource()),
                new SimpleMeterRegistry(),
                32,
                Duration.ofMillis(50));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var valid =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                batcher.execute(
                    "payment-a",
                    () -> {
                      validRuns.incrementAndGet();
                      insertAudit("infrastructure-rollback");
                    });
                return null;
              });
      var unavailable =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                batcher.execute(
                    "payment-z",
                    () -> {
                      throw new IllegalStateException("database unavailable");
                    });
                return null;
              });

      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThatThrownBy(() -> valid.get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> unavailable.get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE id = 'infrastructure-rollback'",
                Long.class))
        .isZero();
    assertThat(validRuns.get()).isEqualTo(1);
  }

  private void insertAudit(String id) {
    jdbc.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,'merchant-a','test','BATCH_TEST',?,'test',CURRENT_TIMESTAMP)",
        id,
        id);
  }
}
