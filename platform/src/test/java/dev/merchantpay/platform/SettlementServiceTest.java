package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.finance.SettlementService;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.refund.RefundService;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SettlementServiceTest extends FinanceFixture {
  SettlementService settlements;

  @BeforeEach
  void settlementService() {
    settlements =
        new SettlementService(
            support,
            new LedgerService(jdbc),
            new OutboxService(
                jdbc,
                Clock.systemUTC(),
                false,
                new EventJournal(
                    jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "POLLING")));
  }

  String draft() {
    return (String) settlements.create("merchant-a", Instant.now(), "日终结算", proposer).get("id");
  }

  void approve(String id, long version) {
    settlements.review(id, version, true, "独立核对来源及金额", reviewer);
  }

  @Test
  void paysExactSourcesOnceAndNeverReselectsPaidEntries() {
    paid("settle", "10000");
    String id = draft();
    assertThatThrownBy(() -> settlements.review(id, 1, true, "自己审批", proposer))
        .isInstanceOf(ApiException.class);
    approve(id, 1);
    settlements.execute(id, 1, "执行模拟付款", proposer);
    settlements.execute(id, 1, "重试", proposer);
    assertThat(settlements.detail(id, proposer).get("status")).isEqualTo("MOCK_PAID");
    assertThat(settlements.detail(id, proposer).get("netMinor")).isEqualTo("9940");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE source_type='SETTLEMENT' AND payment_id IS NULL",
                Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries WHERE entry_kind='SETTLEMENT'",
                Long.class))
        .isZero();
    assertThatThrownBy(this::draft).isInstanceOf(ApiException.class);
  }

  @Test
  void pendingDraftBlocksConcurrentAllocationAndCancelReleasesSources() throws Exception {
    paid("concurrent-settle", "1000");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                try {
                  return draft();
                } catch (ApiException e) {
                  return "conflict";
                }
              });
      var second =
          executor.submit(
              () -> {
                try {
                  return draft();
                } catch (ApiException e) {
                  return "conflict";
                }
              });
      var values =
          java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertThat(values).filteredOn(v -> v.equals("conflict")).hasSize(1);
      String id = values.stream().filter(v -> !v.equals("conflict")).findFirst().orElseThrow();
      settlements.cancel(id, 1, "取消重新检查", proposer);
      assertThat(draft()).isNotEqualTo(id);
    }
    assertThat(count("settlement_reservations")).isEqualTo(1);
  }

  @Test
  void newFinalizedIssueFreezesAnAlreadyApprovedSettlementAtExecution() {
    String root = paid("late-issue", "1000").id();
    String id = draft();
    approve(id, 1);
    issue(root);
    assertThatThrownBy(() -> settlements.execute(id, 1, "执行", proposer))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("差异");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE source_type='SETTLEMENT'", Long.class))
        .isZero();
  }

  @Test
  void rebuildInvalidatesReviewAndPreservesOldSnapshotLines() {
    paid("rebuild1", "1000");
    String id = draft();
    approve(id, 1);
    paid("rebuild2", "2000");
    settlements.rebuild(id, 1, Instant.now(), "纳入新增入账", proposer);
    assertThat(count("settlement_lines")).isEqualTo(3);
    assertThatThrownBy(() -> settlements.execute(id, 1, "执行", proposer))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> settlements.execute(id, 2, "执行", proposer))
        .isInstanceOf(ApiException.class);
    approve(id, 2);
    settlements.execute(id, 2, "模拟付款", proposer);
    assertThat(settlements.detail(id, proposer).get("netMinor")).isEqualTo("2982");
  }

  @Test
  void postPaidRefundBecomesNegativeCarryAndCombinesWithLaterPayment() {
    var payment = paid("paid-before-refund", "10000");
    String first = draft();
    approve(first, 1);
    settlements.execute(first, 1, "模拟付款", proposer);
    var refund =
        refunds.create(
            "merchant-a", payment.id(), new RefundService.CreateRefund("later-refund", "10000"));
    refunds.applyResult(
        refund.id(), new ChannelResult.Success("MOCK_A", "late-refund", 10000, "CNY"));
    String carry = draft();
    approve(carry, 1);
    settlements.execute(carry, 1, "负数结转", proposer);
    assertThat(settlements.detail(carry, proposer).get("status")).isEqualTo("CARRIED_FORWARD");
    assertThat(settlements.detail(carry, proposer).get("netMinor")).isEqualTo("-9940");
    paid("later-payment", "20000");
    String next = draft();
    assertThat(settlements.detail(next, proposer).get("netMinor"))
        .as(
            "journals=%s, snapshot=%s",
            jdbc.queryForList("SELECT * FROM ledger_journals"), settlements.detail(next, proposer))
        .isEqualTo("9940");
    settlements.cancel(next, 1, "撤销并恢复结转归属", proposer);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_reservations WHERE settlement_id=?",
                Long.class,
                carry))
        .isEqualTo(1);
    String retry = draft();
    approve(retry, 1);
    settlements.execute(retry, 1, "合并后模拟付款", proposer);
    assertThat(settlements.detail(first, proposer).get("netMinor")).isEqualTo("9940");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE source_type='SETTLEMENT'", Long.class))
        .isEqualTo(2);
  }

  @Test
  void eventFailureRollsBackPayoutAndRetainsApprovedSources() {
    paid("settlement-rollback", "1000");
    String id = draft();
    approve(id, 1);
    jdbc.update("UPDATE event_transport SET mode='CDC'");
    assertThatThrownBy(() -> settlements.execute(id, 1, "模拟付款", proposer))
        .isInstanceOf(IllegalStateException.class);
    assertThat(settlements.detail(id, proposer).get("status")).isEqualTo("APPROVED");
    assertThat(count("settlement_reservations")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journals WHERE source_type='SETTLEMENT'", Long.class))
        .isZero();
  }

  @Test
  void postingAndCutoffUseSameMicrosecondPrecisionWithoutRoundingIntoFuture() {
    String root = paid("precision", "1000").id();
    var at = Instant.parse("2030-01-01T00:00:00.123456900Z");
    tx.executeWithoutResult(
        t ->
            new LedgerService(jdbc)
                .postAdjustment("precision-adj", root, "merchant-a", true, 50, at));
    var fixed = Clock.fixed(at, ZoneOffset.UTC);
    var service =
        new SettlementService(
            new dev.merchantpay.platform.finance.FinanceSupport(jdbc, tx, fixed),
            new LedgerService(jdbc),
            new OutboxService(jdbc, fixed));
    var result = service.create("merchant-a", at, "相同瞬间的微秒边界", proposer);
    assertThat(result.get("netMinor")).isEqualTo("1044");
  }

  @Test
  void independentlyApprovedRootlessNoAdjustmentResolvesUnknownChannelFreeze() {
    paid("merchant-funds", "1000");
    String issueId = issue("external-channel-only");
    jdbc.update("UPDATE recon_issues SET issue_type='CHANNEL_ONLY' WHERE id=?", issueId);
    assertThatThrownBy(this::draft).isInstanceOf(ApiException.class);
    String caseId = (String) adjustments.openCase(issueId, proposer).get("id");
    adjustments.investigate(caseId, 1, proposer.id(), null, "已核验：渠道重复外部行，不属于本地支付", proposer);
    assertThatThrownBy(
            () -> adjustments.propose(caseId, 2, "INCREASE", "10", "不能为未知支付改账", proposer))
        .isInstanceOf(ApiException.class);
    String id =
        (String) adjustments.propose(caseId, 2, "NONE", "0", "无需调账，独立关闭差异", proposer).get("id");
    adjustments.review(id, 1, true, "复核渠道证据，无本地支付关联", reviewer);
    adjustments.execute(id, 1, "关闭渠道差异", proposer);
    assertThat(adjustments.detail(id, proposer).get("paymentId")).isNull();
    assertThat(count("payments")).isEqualTo(1);
    assertThat(count("ledger_journals")).isEqualTo(1);
    assertThat(settlements.detail(draft(), proposer).get("netMinor")).isEqualTo("994");
  }

  @Test
  void investigationCannotPublishANewFreezeInsidePayoutCheckAndCommit() throws Exception {
    String oldRoot = createPayment("unposted-root", "1000").id();
    String target = paid("payout-target", "1000").id();
    String caseId = (String) adjustments.openCase(issue("external-issue"), proposer).get("id");
    adjustments.investigate(caseId, 1, proposer.id(), oldRoot, "初始渠道调查绑定", proposer);
    String id = draft();
    approve(id, 1);
    var checked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pausing =
        new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public <T> java.util.List<T> query(
              String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
            var rows = super.query(sql, mapper, args);
            if (sql.contains("FROM recon_issues i") && sql.contains("c.status<>'RESOLVED'")) {
              checked.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("test latch timeout");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            }
            return rows;
          }
        };
    var guarded =
        new SettlementService(
            new dev.merchantpay.platform.finance.FinanceSupport(pausing, tx, Clock.systemUTC()),
            new LedgerService(jdbc),
            new OutboxService(
                jdbc,
                Clock.systemUTC(),
                false,
                new EventJournal(
                    jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "POLLING")));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var execution = executor.submit(() -> guarded.execute(id, 1, "模拟核销", proposer));
      try {
        assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
        var started = new CountDownLatch(1);
        var assignment =
            executor.submit(
                () -> {
                  started.countDown();
                  return adjustments.investigate(
                      caseId, 2, proposer.id(), target, "补充渠道证据，重新绑定", proposer);
                });
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> assignment.get(250, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        assertThat(
                jdbc.queryForObject(
                    "SELECT payment_id FROM finance_cases WHERE id=?", String.class, caseId))
            .isEqualTo(oldRoot);
        release.countDown();
        assertThat(execution.get(10, TimeUnit.SECONDS).get("status")).isEqualTo("MOCK_PAID");
        assertThat(assignment.get(10, TimeUnit.SECONDS).get("paymentId")).isEqualTo(target);
      } finally {
        release.countDown();
      }
    }
  }
}
