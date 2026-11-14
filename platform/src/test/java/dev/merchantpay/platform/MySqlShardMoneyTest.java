package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.control.ChannelControl;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import dev.merchantpay.platform.sharding.*;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class MySqlShardMoneyTest extends MySqlShardFixture {
  ShardMoney money;

  @BeforeEach
  void money() {
    core.update("INSERT INTO merchants(id,name,enabled) VALUES ('merchant-a','Merchant A',TRUE)");
    money = services(false);
  }

  ShardMoney services(boolean slots) {
    var clock = Clock.systemUTC();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(0)));
    var ledger = new LedgerService(core);
    var outbox =
        new OutboxService(
            core,
            clock,
            false,
            new EventJournal(core, clock, JsonMapper.builder().build(), "POLLING"));
    var payment = new PaymentService(core, tx, ledger, outbox, clock, 16, slots);
    payment.setChannelControl(new ChannelControl(core, tx, clock, false, false));
    var refund = new RefundService(core, tx, payment, ledger, outbox, clock);
    return new ShardMoney(routes, SOURCES, payment, refund, ledger, outbox, clock);
  }

  String order(int bucket) {
    for (int index = 0; index < 10000; index++) {
      String value = "shard-money-" + index;
      if (PaymentBuckets.forBusinessKey("merchant-a", value) == bucket) return value;
    }
    throw new AssertionError("No fixture order");
  }

  PaymentService.CreatePayment input(int bucket) {
    return new PaymentService.CreatePayment(order(bucket), "10000", "CNY", "MOCK_A", null);
  }

  @Test
  void fourShardsKeepPaymentRefundLedgerEventsAndAuditTogetherWithoutMerchantReplicas() {
    for (int bucket : java.util.List.of(0, 1, 2, 3)) {
      var expected = routes.currentRoute(bucket);
      var payment = money.create(expected, "merchant-a", input(bucket), "merchant");
      money.paymentResult(
          expected,
          payment.id(),
          new ChannelResult.Success("MOCK_A", "receipt-" + bucket, 10000, "CNY"),
          null);
      var refund =
          money.refund(
              expected,
              "merchant-a",
              payment.id(),
              new RefundService.CreateRefund("refund", "3000"),
              "merchant");
      money.refundResult(
          expected,
          refund.id(),
          new ChannelResult.Success("MOCK_A", "refund-" + bucket, 3000, "CNY"),
          null);
      var target = new JdbcTemplate(SOURCES.get(bucket));
      assertThat(target.queryForObject("SELECT COUNT(*) FROM payments", Integer.class))
          .isEqualTo(1);
      assertThat(target.queryForObject("SELECT COUNT(*) FROM ledger_journals", Integer.class))
          .isEqualTo(2);
      assertThat(target.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class))
          .isEqualTo(6);
      assertThat(target.queryForObject("SELECT COUNT(*) FROM domain_events", Integer.class))
          .isEqualTo(4);
      assertThat(target.queryForObject("SELECT COUNT(*) FROM audit_records", Integer.class))
          .isEqualTo(2);
      if (bucket != 0)
        assertThat(target.queryForObject("SELECT COUNT(*) FROM merchants", Integer.class)).isZero();
      assertThat(new ShardReads(routes).payment("merchant-a", payment.id()).refundedMinor())
          .isEqualTo("3000");
    }
  }

  @Test
  void disabledControlMerchantStillAllowsIdenticalRetriesButRejectsNewBusiness() {
    var expected = routes.currentRoute(1);
    var payment = money.create(expected, "merchant-a", input(1), "merchant");
    money.paymentResult(
        expected,
        payment.id(),
        new ChannelResult.Success("MOCK_A", "disabled-receipt", 10000, "CNY"),
        null);
    var refundInput = new RefundService.CreateRefund("same-refund", "100");
    var refund = money.refund(expected, "merchant-a", payment.id(), refundInput, "merchant");
    core.update("UPDATE merchants SET enabled=FALSE WHERE id='merchant-a'");
    assertThat(money.create(expected, "merchant-a", input(1), "merchant").id())
        .isEqualTo(payment.id());
    var changed =
        new PaymentService.CreatePayment(
            input(1).merchantOrderNo(), "20000", "CNY", "MOCK_A", null);
    assertThatThrownBy(() -> money.create(expected, "merchant-a", changed, "merchant"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> money.create(routes.currentRoute(2), "merchant-a", input(2), "merchant"))
        .isInstanceOf(ApiException.class)
        .hasMessage("商户不存在或已停用");
    assertThat(money.refund(expected, "merchant-a", payment.id(), refundInput, "merchant").id())
        .isEqualTo(refund.id());
    assertThatThrownBy(
            () ->
                money.refund(
                    expected,
                    "merchant-a",
                    payment.id(),
                    new RefundService.CreateRefund("new-refund", "100"),
                    "merchant"))
        .isInstanceOf(ApiException.class)
        .hasMessage("商户不存在或已停用");
    assertThat(new ShardReads(routes).payment("merchant-a", payment.id()).reservedMinor())
        .isEqualTo("100");
  }

  @Test
  void auditingFailureRollsBackBusinessAdmissionAndEventsAndDoesNotCacheInitialization() {
    money = services(true);
    var target = new JdbcTemplate(SOURCES.get(1));
    target.execute(
        "ALTER TABLE audit_records ADD CONSTRAINT test_no_create CHECK(operation<>'PAYMENT_CREATE')");
    try {
      assertThatThrownBy(
              () -> money.create(routes.currentRoute(1), "merchant-a", input(1), "merchant"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(target.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isZero();
      assertThat(target.queryForObject("SELECT SUM(inflight) FROM payment_admission", Long.class))
          .isZero();
      assertThat(target.queryForObject("SELECT COUNT(*) FROM domain_events", Integer.class))
          .isZero();
      assertThat(
              target.queryForObject(
                  "SELECT slot_mode FROM payment_admission_mode WHERE id=1", Boolean.class))
          .isFalse();
      assertThat(
              target.queryForObject(
                  "SELECT COUNT(*) FROM payment_admission_slots WHERE payment_id IS NOT NULL",
                  Integer.class))
          .isZero();
      assertThat(core.queryForObject("SELECT COUNT(*) FROM audit_records", Integer.class)).isZero();
    } finally {
      target.execute("ALTER TABLE audit_records DROP CHECK test_no_create");
    }
    assertThat(money.create(routes.currentRoute(1), "merchant-a", input(1), "merchant").status())
        .isEqualTo("PROCESSING");
    assertThat(
            target.queryForObject(
                "SELECT slot_mode FROM payment_admission_mode WHERE id=1", Boolean.class))
        .isTrue();
  }

  @Test
  void invalidNestedBusinessBucketPoisonsTheEnclosingTransactionEvenWhenCaught() {
    assertThatThrownBy(
            () ->
                routes.write(
                    routes.currentRoute(1),
                    sql -> {
                      sql.update("INSERT INTO tx_probe VALUES (1,1)");
                      try {
                        money.create(routes.currentRoute(1), "merchant-a", input(2), "merchant");
                      } catch (IllegalArgumentException expected) {
                        /* must not commit the probe */
                      }
                      return 1;
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new JdbcTemplate(SOURCES.get(1))
                .queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class))
        .isZero();
  }

  @Test
  void nonzeroHttpAdmissionAndWrongPinnedRouteHaveNoSideEffects() {
    assertThatThrownBy(() -> money.create("merchant-a", input(1), "merchant"))
        .isInstanceOf(ApiException.class)
        .hasMessage("目标分片交付条件尚未齐备，暂不开放受理");
    var actual = routes.currentRoute(1);
    var wrong = new ShardCatalog.Route(1, 2, actual.epoch());
    assertThatThrownBy(() -> money.create(wrong, "merchant-a", input(1), "merchant"))
        .isInstanceOf(ApiException.class);
    var payment = money.create(actual, "merchant-a", input(1), "merchant");
    assertThatThrownBy(
            () ->
                money.paymentResult(wrong, payment.id(), new ChannelResult.Pending("MOCK_A"), null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                money.refund(
                    actual,
                    "merchant-b",
                    payment.id(),
                    new RefundService.CreateRefund("wrong", "10"),
                    "merchant"))
        .isInstanceOf(ApiException.class)
        .hasMessage("支付单不存在");
    assertThat(core.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isZero();
    assertThat(
            new JdbcTemplate(SOURCES.get(1))
                .queryForObject("SELECT COUNT(*) FROM refunds", Integer.class))
        .isZero();
  }

  @Test
  void explicitTargetHttpGateRoutesAnAcceptedRequestToTheTargetTransaction() {
    var admission = mock(ShardTargetHttpAdmission.class);
    money.setTargetHttpAdmission(admission);
    var payment = money.create("merchant-a", input(1), "merchant");
    verify(admission).require(routes.currentRoute(1));
    assertThat(
            new JdbcTemplate(SOURCES.get(1))
                .queryForObject(
                    "SELECT COUNT(*) FROM payments WHERE id=?", Long.class, payment.id()))
        .isEqualTo(1);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM payments", Long.class)).isZero();
  }

  @Test
  void targetHttpFlagWithoutCompletedCutoverCannotWriteMoney() {
    money.setTargetHttpAdmission(new ShardTargetHttpAdmission(core));
    assertThatThrownBy(() -> money.create("merchant-a", input(1), "merchant"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            failure ->
                assertThat(((ApiException) failure).code())
                    .isEqualTo("SHARD_TARGET_HTTP_UNAVAILABLE"));
    assertThat(
            new JdbcTemplate(SOURCES.get(1))
                .queryForObject("SELECT COUNT(*) FROM payments", Long.class))
        .isZero();
    assertThat(
            new JdbcTemplate(SOURCES.get(1))
                .queryForObject("SELECT COUNT(*) FROM audit_records", Long.class))
        .isZero();
  }

  @Test
  void invalidChannelAmountCannotCreateMoneyOrJournalFacts() {
    var expected = routes.currentRoute(1);
    var payment = money.create(expected, "merchant-a", input(1), "merchant");
    assertThatThrownBy(
            () ->
                money.paymentResult(
                    expected,
                    payment.id(),
                    new ChannelResult.Success("MOCK_A", "wrong-amount", 9999, "CNY"),
                    null))
        .isInstanceOf(ApiException.class);
    var evidence =
        new ReceiptEvidence("PAYMENT", "SUCCEEDED", "MOCK_A", "10000", "60", "USD", "bad-evidence");
    assertThatThrownBy(
            () ->
                money.paymentResult(
                    expected,
                    payment.id(),
                    new ChannelResult.Success("MOCK_A", "bad-evidence", 10000, "CNY"),
                    evidence))
        .isInstanceOf(ApiException.class);
    var target = new JdbcTemplate(SOURCES.get(1));
    assertThat(new ShardReads(routes).payment("merchant-a", payment.id()).status())
        .isEqualTo("PROCESSING");
    assertThat(target.queryForObject("SELECT COUNT(*) FROM ledger_journals", Integer.class))
        .isZero();
    assertThat(target.queryForObject("SELECT COUNT(*) FROM domain_events", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentFirstUseCommitsOneConsistentAdmissionModeAndBothBusinessRequests()
      throws Exception {
    money = services(true);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return money.create(routes.currentRoute(1), "merchant-a", input(1), "merchant");
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return money.create(routes.currentRoute(5), "merchant-a", input(5), "merchant");
              });
      start.countDown();
      assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).id())
          .isNotEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS).id());
    }
    var target = new JdbcTemplate(SOURCES.get(1));
    assertThat(
            target.queryForObject(
                "SELECT slot_mode FROM payment_admission_mode WHERE id=1", Boolean.class))
        .isTrue();
    assertThat(target.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isEqualTo(2);
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM payment_admission_slots WHERE payment_id IS NOT NULL",
                Integer.class))
        .isEqualTo(2);
    assertThat(target.queryForObject("SELECT COUNT(*) FROM domain_events", Integer.class))
        .isEqualTo(2);
  }
}
