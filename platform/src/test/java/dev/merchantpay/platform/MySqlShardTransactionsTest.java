package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.sharding.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real InnoDB regression definitions only; not part of native checks and not run on this PC. */
class MySqlShardTransactionsTest extends MySqlShardFixture {

  @Test
  void writesAndReadsUseOneSelectedDatabaseAndRollbackAllLocalChanges() {
    for (int bucket : List.of(0, 1, 2, 3)) {
      routes.write(bucket, 2, sql -> sql.update("INSERT INTO tx_probe VALUES (1,?)", bucket));
      Integer actual =
          routes.read(
              bucket,
              sql -> sql.queryForObject("SELECT value_no FROM tx_probe WHERE id=1", Integer.class));
      assertThat(actual).isEqualTo(bucket);
    }
    assertThatThrownBy(
            () ->
                routes.write(
                    2,
                    2,
                    sql -> {
                      sql.update("INSERT INTO tx_probe VALUES (2,9)");
                      throw new IllegalArgumentException("business failure");
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new JdbcTemplate(SOURCES.get(2))
                .queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void nestedSameBucketJoinsButCrossBucketAndReadUpgradePoisonTheOuterTransaction() {
    routes.write(
        0,
        2,
        outer ->
            routes.read(
                0,
                inner -> {
                  assertThat(inner).isSameAs(outer);
                  return inner.update("INSERT INTO tx_probe VALUES (1,1)");
                }));
    assertThatThrownBy(
            () ->
                routes.write(
                    0,
                    2,
                    sql -> {
                      sql.update("INSERT INTO tx_probe VALUES (2,2)");
                      try {
                        routes.read(4, other -> 1);
                      } catch (IllegalStateException expected) {
                        /* caller swallows */
                      }
                      return 1;
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class)).isEqualTo(1);
    assertThatThrownBy(() -> routes.read(0, sql -> routes.write(0, 2, inner -> 1)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> routes.write(0, 2, sql -> routes.write(0, 1, inner -> 1)))
        .isInstanceOf(ApiException.class);
    Integer afterFailure = routes.read(1, sql -> 7);
    assertThat(afterFailure).isEqualTo(7);
  }

  @Test
  void unmanagedOuterTransactionCannotSecretlyJoinOrCommitAfterARejectedRoute() {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(0)));
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> {
                      core.update("INSERT INTO tx_probe VALUES (1,1)");
                      try {
                        routes.read(1, sql -> 1);
                      } catch (IllegalStateException expected) {
                        /* caller swallows */
                      }
                      return null;
                    }))
        .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class)).isZero();
  }

  @Test
  void suspendedOrReplacedTransactionsCannotUseTheNestedShortcut() {
    for (int propagation :
        List.of(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED)) {
      var replacement = new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(1)));
      replacement.setPropagationBehavior(propagation);
      assertThatThrownBy(
              () ->
                  routes.write(
                      1,
                      2,
                      sql -> {
                        sql.update("INSERT INTO tx_probe VALUES (1,1)");
                        try {
                          replacement.execute(
                              status ->
                                  routes.write(
                                      1,
                                      2,
                                      inner -> inner.update("INSERT INTO tx_probe VALUES (2,2)")));
                        } catch (IllegalStateException expected) {
                          /* outer must still roll back */
                        }
                        return 1;
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              new JdbcTemplate(SOURCES.get(1))
                  .queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class))
          .isZero();
    }
  }

  @Test
  void wrongIdentityFencedBucketAndStaleEpochNeverInvokeTheCallbackOrFallBack() {
    var touched = new java.util.concurrent.atomic.AtomicBoolean();
    assertThatThrownBy(
            () ->
                routes.write(
                    1,
                    1,
                    sql -> {
                      touched.set(true);
                      return 1;
                    }))
        .isInstanceOf(ApiException.class);
    var target = new JdbcTemplate(SOURCES.get(1));
    target.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=1");
    assertThatThrownBy(
            () ->
                routes.read(
                    1,
                    sql -> {
                      touched.set(true);
                      return 1;
                    }))
        .isInstanceOf(ApiException.class);
    target.update("UPDATE v3_bucket_fences SET access_state='ACTIVE' WHERE bucket=1");
    target.update("UPDATE v3_shard_identity SET physical_shard=0 WHERE id=1");
    assertThatThrownBy(
            () ->
                routes.read(
                    1,
                    sql -> {
                      touched.set(true);
                      return 1;
                    }))
        .isInstanceOf(ApiException.class);
    assertThat(touched).isFalse();
    assertThat(core.queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class)).isZero();
  }

  @Test
  void readersDiscardResultsIfOwnershipChangesWhileTheQueryRuns() {
    assertThatThrownBy(
            () ->
                routes.read(
                    1,
                    sql -> {
                      // Direct DataSource access deliberately bypasses Spring's transaction-bound
                      // connection.
                      try (var independent = SOURCES.get(1).getConnection();
                          var statement =
                              independent.prepareStatement(
                                  "UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=1")) {
                        independent.setAutoCommit(true);
                        assertThat(statement.executeUpdate()).isEqualTo(1);
                      } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException(failure);
                      }
                      return "must not escape";
                    }))
        .isInstanceOf(ApiException.class);
    assertThat(
            new JdbcTemplate(SOURCES.get(1))
                .queryForObject(
                    "SELECT access_state FROM v3_bucket_fences WHERE bucket=1", String.class))
        .isEqualTo("FENCED");
    assertThatThrownBy(
            () ->
                routes.read(
                    2,
                    sql -> {
                      core.update("UPDATE v3_shard_routes SET epoch=3 WHERE bucket=2");
                      return "must not escape";
                    }))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void paymentAndRefundReadsUseEncodedOwnershipAndRetainMerchantIsolation() {
    String payment = "p1_552ea710-7879-4b42-876a-f145cdcf51d3";
    String refund = "r1_552ea710-7879-4b42-876a-f145cdcf51d3";
    var target = new JdbcTemplate(SOURCES.get(1));
    target.update(
        "INSERT INTO payments(id,merchant_id,merchant_order_no,request_hash,logical_shard,amount_minor,fee_minor,currency,status,channel,created_at,updated_at) VALUES (?,'merchant-a','same','hash',1,100,1,'CNY','SUCCEEDED','MOCK_A',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
        payment);
    target.update(
        "INSERT INTO refunds(id,payment_id,merchant_id,merchant_refund_no,amount_minor,status,channel,created_at,updated_at) VALUES (?,?,'merchant-a','refund',10,'PROCESSING','MOCK_A',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
        refund,
        payment);
    var reads = new ShardReads(routes);
    assertThat(reads.payment("merchant-a", payment).amountMinor()).isEqualTo("100");
    assertThat(reads.refund("merchant-a", refund).paymentId()).isEqualTo(payment);
    assertThatThrownBy(() -> reads.payment("merchant-b", payment))
        .isInstanceOf(ApiException.class)
        .hasMessage("支付单不存在");
    assertThatThrownBy(() -> reads.refund("merchant-b", refund))
        .isInstanceOf(ApiException.class)
        .hasMessage("退款单不存在");
    core.update("UPDATE v3_shard_routes SET physical_shard=2 WHERE bucket=1");
    assertThatThrownBy(() -> reads.payment("merchant-a", payment)).isInstanceOf(ApiException.class);
  }

  @Test
  void sharedFencesPermitConcurrentWritersAndHoldBackOwnershipChangesUntilCommit()
      throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var first =
          executor.submit(
              () ->
                  routes.write(
                      1,
                      2,
                      sql -> {
                        sql.update("INSERT INTO tx_probe VALUES (1,1)");
                        entered.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("release timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return 1;
                      }));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      try {
        var second =
            executor.submit(
                () -> routes.write(1, 2, sql -> sql.update("INSERT INTO tx_probe VALUES (2,2)")));
        assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        var moving =
            executor.submit(
                () ->
                    new JdbcTemplate(SOURCES.get(1))
                        .update(
                            "UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=1"));
        assertThatThrownBy(() -> moving.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(moving.get(3, TimeUnit.SECONDS)).isEqualTo(1);
      } finally {
        release.countDown();
      }
    }
  }
}
