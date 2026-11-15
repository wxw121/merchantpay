package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.channel.ChannelEvidenceWrites;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

class ChannelReceiptBulkSqlTest extends ChannelEvidenceWritesTest {
  @Override
  ChannelEvidenceWrites writer(DataSourceTransactionManager manager, int maximum) {
    return new ChannelEvidenceWrites(
        manager, new SimpleMeterRegistry(), maximum, Duration.ofMillis(50), jdbc, true);
  }

  final Timestamp time = Timestamp.from(Instant.parse("2026-10-30T00:00:00.123456Z"));

  void seed(String key) {
    jdbc.update(
        "INSERT INTO channel_attempts(id,aggregate_id,payment_id,channel,operation,request_key,status,created_at,updated_at) VALUES (?,?,'p','MOCK_A','CHANNEL_PAYMENT',?,'PREPARED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        key,
        key,
        key);
  }

  String body(String key) {
    return jdbc.queryForObject(
        "SELECT response_body FROM channel_attempts WHERE id=?", String.class, key);
  }

  DataSourceTransactionManager manager() {
    return new DataSourceTransactionManager(jdbc.getDataSource());
  }

  JdbcTemplate counted(AtomicInteger calls) {
    return new JdbcTemplate(jdbc.getDataSource()) {
      @Override
      public int update(String sql, Object... args) {
        if (sql.startsWith("UPDATE channel_attempts")) calls.incrementAndGet();
        return super.update(sql, args);
      }
    };
  }

  ChannelEvidenceWrites writer(
      DataSourceTransactionManager manager, JdbcTemplate sql, boolean enabled) {
    return new ChannelEvidenceWrites(
        manager, new SimpleMeterRegistry(), 64, Duration.ofMillis(50), sql, enabled);
  }

  List<Future<?>> together(ExecutorService executor, Runnable... work) throws Exception {
    var ready = new CountDownLatch(work.length);
    var start = new CountDownLatch(1);
    var pending = new ArrayList<Future<?>>();
    for (var job : work)
      pending.add(
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                job.run();
                return null;
              }));
    assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    return pending;
  }

  @Test
  void twoReceiptsUseOneActualUpdateAndCommit() throws Exception {
    seed("a");
    seed("b");
    var sqlCalls = new AtomicInteger();
    var commits = new AtomicInteger();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            commits.incrementAndGet();
          }
        };
    try (var writer = writer(manager, counted(sqlCalls), true);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var f :
          together(
              executor,
              () -> writer.retainReceipt("MOCK_A", "a", "raw-a", time),
              () -> writer.retainReceipt("MOCK_A", "b", "raw-b", time))) f.get(3, TimeUnit.SECONDS);
    }
    assertThat(body("a")).isEqualTo("raw-a");
    assertThat(body("b")).isEqualTo("raw-b");
    assertThat(commits.get()).isEqualTo(1);
    assertThat(sqlCalls.get()).isEqualTo(1);
  }

  @Test
  void disabledBulkFlagKeepsIndividualUpdates() throws Exception {
    seed("a");
    seed("b");
    var calls = new AtomicInteger();
    try (var writer = writer(manager(), counted(calls), false);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var f :
          together(
              executor,
              () -> writer.retainReceipt("MOCK_A", "a", "raw-a", time),
              () -> writer.retainReceipt("MOCK_A", "b", "raw-b", time))) f.get(3, TimeUnit.SECONDS);
    }
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void missingPreparedRowRollsBackTheWholeReceiptGroup() throws Exception {
    seed("a");
    try (var writer = writer(manager(), jdbc, true);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var f :
          together(
              executor,
              () -> writer.retainReceipt("MOCK_A", "a", "raw-a", time),
              () -> writer.retainReceipt("MOCK_A", "missing", "raw-b", time)))
        assertThatThrownBy(() -> f.get(3, TimeUnit.SECONDS))
            .hasCauseInstanceOf(IllegalStateException.class);
    }
    assertThat(body("a")).isNull();
  }

  @Test
  void unknownCommitRetainsEvidenceButFailsEveryCaller() throws Exception {
    seed("a");
    seed("b");
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            throw new TransactionSystemException("commit acknowledgement lost");
          }
        };
    try (var writer = writer(manager, jdbc, true);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var f :
          together(
              executor,
              () -> writer.retainReceipt("MOCK_A", "a", "raw-a", time),
              () -> writer.retainReceipt("MOCK_A", "b", "raw-b", time)))
        assertThatThrownBy(() -> f.get(3, TimeUnit.SECONDS))
            .hasCauseInstanceOf(TransactionSystemException.class);
    }
    assertThat(body("a")).isEqualTo("raw-a");
    assertThat(body("b")).isEqualTo("raw-b");
  }

  @Test
  void noReceiptCallerReturnsBeforeCommitCompletes() throws Exception {
    seed("a");
    seed("b");
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
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            super.doCommit(status);
          }
        };
    try (var writer = writer(manager, jdbc, true);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fs =
          together(
              executor,
              () -> writer.retainReceipt("MOCK_A", "a", "raw-a", time),
              () -> writer.retainReceipt("MOCK_A", "b", "raw-b", time));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(fs).allMatch(f -> !f.isDone());
        assertThat(body("a")).isNull();
        assertThat(body("b")).isNull();
      } finally {
        release.countDown();
      }
      for (var f : fs) f.get(3, TimeUnit.SECONDS);
    }
  }

  @Test
  void genericActionsKeepTheirPositionBetweenReceiptWrites() throws Exception {
    seed("a");
    seed("z");
    var calls = new AtomicInteger();
    try (var writer = writer(manager(), counted(calls), true);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fs =
          together(
              executor,
              () -> writer.retainReceipt("MOCK_A", "a", "first", time),
              () ->
                  writer.execute(
                      "MOCK_A\0m",
                      () -> {
                        assertThat(body("a")).isEqualTo("first");
                        assertThat(body("z")).isNull();
                        return 7;
                      }),
              () -> writer.retainReceipt("MOCK_A", "z", "last", time));
      for (var f : fs) f.get(3, TimeUnit.SECONDS);
    }
    assertThat(calls.get()).isEqualTo(2);
  }

  void awaitQueued(ChannelEvidenceWrites writer, int size) throws Exception {
    var field = ChannelEvidenceWrites.class.getDeclaredField("pending");
    field.setAccessible(true);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < until) {
      synchronized (writer) {
        var list = (List<?>) field.get(writer);
        if (list != null && list.size() == size) return;
      }
      Thread.sleep(1);
    }
    throw new AssertionError("Receipt did not enter the pending group");
  }

  @Test
  void duplicateKeysRetainSubmissionOrderInsteadOfBeingSilentlyDeduplicated() throws Exception {
    seed("a");
    var calls = new AtomicInteger();
    try (var writer = writer(manager(), counted(calls), true);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> writer.retainReceipt("MOCK_A", "a", "earlier", time));
      awaitQueued(writer, 1);
      var second = executor.submit(() -> writer.retainReceipt("MOCK_A", "a", "later", time));
      first.get(3, TimeUnit.SECONDS);
      second.get(3, TimeUnit.SECONDS);
    }
    assertThat(body("a")).isEqualTo("later");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void laterGenericFailureRollsBackEarlierBulkUpdateWithoutReplay() throws Exception {
    seed("a");
    seed("b");
    var calls = new AtomicInteger();
    try (var writer = writer(manager(), counted(calls), true);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fs =
          together(
              executor,
              () -> writer.retainReceipt("MOCK_A", "a", "raw-a", time),
              () -> writer.retainReceipt("MOCK_A", "b", "raw-b", time),
              () ->
                  writer.execute(
                      "ZZZ",
                      () -> {
                        throw new IllegalStateException("generic failure");
                      }));
      for (var f : fs)
        assertThatThrownBy(() -> f.get(3, TimeUnit.SECONDS))
            .hasCauseInstanceOf(IllegalStateException.class);
    }
    assertThat(body("a")).isNull();
    assertThat(body("b")).isNull();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void identicalReceiptRetryStillRequiresAnExistingRow() {
    seed("a");
    try (var writer = writer(manager(), jdbc, true)) {
      writer.retainReceipt("MOCK_A", "a", "same", time);
      writer.retainReceipt("MOCK_A", "a", "same", time);
    }
    assertThat(body("a")).isEqualTo("same");
  }

  @Test
  void outerTransactionsAndNewRequestsAfterCloseAreRejected() {
    seed("a");
    var writer =
        new ChannelEvidenceWrites(
            manager(), new SimpleMeterRegistry(), 1, Duration.ZERO, jdbc, true);
    new TransactionTemplate(manager())
        .executeWithoutResult(
            tx ->
                assertThatThrownBy(() -> writer.retainReceipt("MOCK_A", "a", "outer", time))
                    .isInstanceOf(IllegalStateException.class));
    writer.close();
    assertThatThrownBy(() -> writer.retainReceipt("MOCK_A", "a", "closed", time))
        .isInstanceOf(IllegalStateException.class);
    assertThat(body("a")).isNull();
  }

  @Test
  void unchangedRowsReportedAsZeroStillRequireEveryPreparedKey() {
    seed("a");
    var changedRows =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public int update(String sql, Object... args) {
            super.update(sql, args);
            return 0;
          }
        };
    try (var writer = writer(manager(), changedRows, true)) {
      writer.retainReceipt("MOCK_A", "a", "same", time);
    }
    assertThat(body("a")).isEqualTo("same");
  }

  @Test
  void sameRequestKeyInDifferentChannelsRetainsBothBodies() throws Exception {
    seed("a");
    seed("b");
    jdbc.update("UPDATE channel_attempts SET channel='MOCK_B',request_key='a' WHERE id='b'");
    try (var writer = writer(manager(), jdbc, true);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var f :
          together(
              executor,
              () -> writer.retainReceipt("MOCK_A", "a", "raw-a", time),
              () ->
                  writer.retainReceipt(
                      "MOCK_B", "a", "raw-b", Timestamp.from(time.toInstant().plusSeconds(1)))))
        f.get(3, TimeUnit.SECONDS);
    }
    assertThat(body("a")).isEqualTo("raw-a");
    assertThat(body("b")).isEqualTo("raw-b");
    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at FROM channel_attempts WHERE id='b'", Timestamp.class))
        .isEqualTo(Timestamp.from(time.toInstant().plusSeconds(1)));
  }
}
