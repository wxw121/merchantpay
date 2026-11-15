package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.sharding.ShardCatalog;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ShardCatalogTest extends FinancialFixture {
  final Actor ops = new Actor("operator", null, Set.of("OPS"));
  ShardCatalog catalog;

  @BeforeEach
  void routes() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V24__shard_catalog.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V34__shard_quiescence.sql"));
    catalog =
        new ShardCatalog(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            Clock.systemUTC());
  }

  @Test
  void existingBucketsRemainInTheOriginalDatabaseAndResolutionIsExplicitlyNotExecution() {
    var directory = catalog.directory(ops);
    assertThat(directory.executionEnabled()).isFalse();
    assertThat(directory.routes())
        .hasSize(16)
        .allSatisfy(
            route -> {
              assertThat(route.physicalShard()).isZero();
              assertThat(route.epoch()).isEqualTo(1);
            });
    var payment = createPayment("route-compatible", "100");
    var result = catalog.resolve("PAYMENT", null, payment.id(), ops);
    assertThat(result.bucket()).isEqualTo(Character.digit(payment.id().charAt(1), 16));
    assertThat(result.physicalShard()).isZero();
    assertThat(result.existenceVerified()).isFalse();
  }

  @Test
  void requestIdentitySurvivesCancellationWhileNewRequestsCanPlanTheBucketAgain() {
    var plan = catalog.plan("request-one", 3, 2, 1, "准备核对搬迁", ops);
    assertThat(catalog.plan("request-one", 3, 2, 1, "准备核对搬迁", ops).id()).isEqualTo(plan.id());
    assertThat(count("v3_shard_migrations")).isEqualTo(1);
    assertThat(count("audit_records")).isEqualTo(1);
    assertThatThrownBy(() -> catalog.plan("request-one", 4, 2, 1, "准备核对搬迁", ops))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> catalog.plan("another-key", 3, 1, 1, "another plan", ops))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> catalog.cancel(plan.id(), 2, "取消", ops))
        .isInstanceOf(ApiException.class);
    var cancelled = catalog.cancel(plan.id(), 1, "取消本次准备", ops);
    assertThat(cancelled.state()).isEqualTo("CANCELLED");
    assertThat(cancelled.version()).isEqualTo(2);
    assertThat(catalog.plan("request-one", 3, 2, 1, "准备核对搬迁", ops).state()).isEqualTo("CANCELLED");
    assertThat(catalog.plan("replacement", 3, 1, 1, "重新准备", ops).id()).isNotEqualTo(plan.id());
    assertThat(catalog.directory(ops).routes())
        .allSatisfy(route -> assertThat(route.physicalShard()).isZero());
  }

  @Test
  void startedReversePlanCannotBeCancelledWhileItsSourceMayBeFenced() {
    jdbc.execute("CREATE TABLE v3_shard_reverse_runs(plan_id VARCHAR(36) PRIMARY KEY)");
    jdbc.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=3");
    var reverse = catalog.plan("reverse-one", 3, 0, 2, "回迁", ops);
    jdbc.update("INSERT INTO v3_shard_reverse_runs(plan_id) VALUES(?)", reverse.id());
    assertThatThrownBy(() -> catalog.cancel(reverse.id(), reverse.version(), "取消", ops))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("回迁已开始");
    assertThat(catalog.detail(reverse.id(), ops).state()).isEqualTo("PLANNED");
  }

  @Test
  void staleEpochInvalidTargetsAndTenantScopedOperatorsCannotCreatePlans() {
    assertThatThrownBy(() -> catalog.plan("stale", 0, 1, 2, "stale", ops))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> catalog.plan("no-change", 0, 0, 1, "no change", ops))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> catalog.plan("bad-target", 0, 4, 1, "bad target", ops))
        .isInstanceOf(ApiException.class);
    for (var actor :
        new Actor[] {
          new Actor("merchant", "merchant-a", Set.of("MERCHANT")),
          new Actor("scoped-ops", "merchant-a", Set.of("OPS")),
          new Actor("finance", null, Set.of("FINANCE"))
        }) {
      assertThatThrownBy(() -> catalog.directory(actor)).isInstanceOf(ApiException.class);
      assertThatThrownBy(() -> catalog.plan("forbidden", 1, 2, 1, "not allowed", actor))
          .isInstanceOf(ApiException.class);
    }
    assertThat(count("v3_shard_migrations")).isZero();
    assertThat(count("audit_records")).isZero();
  }

  @Test
  void auditFailureRollsBackThePlanAndKeepsTheBucketAvailable() {
    jdbc.execute(
        "ALTER TABLE audit_records ADD CONSTRAINT reject_shard_plan CHECK(operation<>'V3_SHARD_PLAN')");
    assertThatThrownBy(() -> catalog.plan("rollback", 5, 2, 1, "atomic plan", ops))
        .isInstanceOf(RuntimeException.class);
    assertThat(count("v3_shard_migrations")).isZero();
    jdbc.execute("ALTER TABLE audit_records DROP CONSTRAINT reject_shard_plan");
    assertThat(catalog.plan("rollback", 5, 2, 1, "atomic plan", ops).state()).isEqualTo("PLANNED");
  }

  @Test
  void failedCancellationAuditKeepsTheOriginalPlanAndReservation() {
    var plan = catalog.plan("cancel-rollback", 8, 3, 1, "prepare", ops);
    jdbc.execute(
        "ALTER TABLE audit_records ADD CONSTRAINT reject_shard_cancel CHECK(operation<>'V3_SHARD_PLAN_CANCEL')");
    assertThatThrownBy(() -> catalog.cancel(plan.id(), 1, "cancel", ops))
        .isInstanceOf(RuntimeException.class);
    assertThat(catalog.detail(plan.id(), ops).state()).isEqualTo("PLANNED");
    assertThat(catalog.detail(plan.id(), ops).version()).isEqualTo(1);
    assertThatThrownBy(() -> catalog.plan("occupied-after-failure", 8, 2, 1, "retry", ops))
        .isInstanceOf(ApiException.class);
    assertThat(count("audit_records")).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void concurrentPlansDoNotDuplicateTheOpenBucketOrItsAudit(boolean sameRequest) throws Exception {
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          pool.submit(
              () -> {
                gate.await();
                return catalog.plan("race-one", 7, 2, 1, "concurrent preparation", ops);
              });
      var second =
          pool.submit(
              () -> {
                gate.await();
                try {
                  return catalog.plan(
                      sameRequest ? "race-one" : "race-two",
                      7,
                      2,
                      1,
                      "concurrent preparation",
                      ops);
                } catch (ApiException conflict) {
                  assertThat(conflict.code()).isEqualTo("SHARD_PLAN_CONFLICT");
                  return null;
                }
              });
      gate.countDown();
      ShardCatalog.Plan winner;
      try {
        winner = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException failure) {
        assertThat(sameRequest).isFalse();
        assertThat(failure.getCause()).isInstanceOf(ApiException.class);
        winner = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      }
      var other = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(winner).isNotNull();
      if (sameRequest) assertThat(other.id()).isEqualTo(winner.id());
      assertThat(count("v3_shard_migrations")).isEqualTo(1);
      assertThat(count("audit_records")).isEqualTo(1);
    }
  }
}
