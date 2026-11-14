package dev.merchantpay.platform.orchestration;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionStatus;

class NotificationWritesTest {
  @Test
  void concurrentWritesCommitAsGroupsAndReturnOnlyAfterTheirRowsAreVisible() throws Exception {
    var jdbc = database();
    var commits = new AtomicInteger();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
            super.doCommit(status);
          }
        };
    try (var writes =
            new NotificationWrites(manager, new SimpleMeterRegistry(), 16, Duration.ofMillis(30));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var results = new ArrayList<Future<?>>();
      for (int i = 0; i < 16; i++) {
        int id = i;
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  writes.execute(
                      () -> jdbc.update("INSERT INTO writes_probe VALUES (?, 'SENDING')", id));
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT status FROM writes_probe WHERE id=?", String.class, id))
                      .isEqualTo("SENDING");
                  return null;
                }));
      }
      start.countDown();
      for (var result : results) result.get(10, TimeUnit.SECONDS);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM writes_probe", Integer.class))
          .isEqualTo(16);
      assertThat(commits.get()).isBetween(1, 15);
    } finally {
      jdbc.execute("SHUTDOWN");
    }
  }

  @Test
  void failedCommitRollsBackAndDoesNotAuthorizeTheNextAction() {
    var jdbc = database();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            throw new IllegalStateException("injected commit failure");
          }
        };
    manager.setRollbackOnCommitFailure(true);
    var nextAction = new AtomicInteger();
    try (var writes =
        new NotificationWrites(manager, new SimpleMeterRegistry(), 16, Duration.ZERO)) {
      assertThatThrownBy(
              () -> {
                writes.execute(() -> jdbc.update("INSERT INTO writes_probe VALUES (1, 'SENDING')"));
                nextAction.incrementAndGet();
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("commit failure");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM writes_probe", Integer.class)).isZero();
      assertThat(nextAction.get()).isZero();
    } finally {
      jdbc.execute("SHUTDOWN");
    }
  }

  @Test
  void interruptedFollowerRemainsBlockedUntilTheDatabaseCommitReturns() throws Exception {
    var jdbc = database();
    var inCommit = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var leader = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var manager =
        new DataSourceTransactionManager(jdbc.getDataSource()) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            leader.set(Thread.currentThread());
            inCommit.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("test timeout");
            } catch (InterruptedException failure) {
              throw new IllegalStateException(failure);
            }
            super.doCommit(status);
          }
        };
    var metrics = new SimpleMeterRegistry();
    try (var writes = new NotificationWrites(manager, metrics, 16, Duration.ofMillis(40))) {
      var done = new CountDownLatch(2);
      var errors = new ConcurrentLinkedQueue<Throwable>();
      Runnable work =
          () -> {
            try {
              long id = Thread.currentThread().threadId();
              writes.execute(
                  () -> jdbc.update("INSERT INTO writes_probe VALUES (?, 'SENDING')", id));
            } catch (Throwable failure) {
              errors.add(failure);
            } finally {
              done.countDown();
            }
          };
      Thread first = Thread.ofVirtual().start(work);
      Thread second = Thread.ofVirtual().start(work);
      try {
        assertThat(inCommit.await(3, TimeUnit.SECONDS)).isTrue();
        (leader.get() == first ? second : first).interrupt();
        assertThat(done.await(100, TimeUnit.MILLISECONDS)).isFalse();
      } finally {
        release.countDown();
        first.join(5000);
        second.join(5000);
      }
      assertThat(done.getCount()).isZero();
      assertThat(errors).isEmpty();
    } finally {
      jdbc.execute("SHUTDOWN");
    }
  }

  JdbcTemplate database() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("CREATE TABLE writes_probe(id BIGINT PRIMARY KEY, status VARCHAR(20))");
    return jdbc;
  }
}
