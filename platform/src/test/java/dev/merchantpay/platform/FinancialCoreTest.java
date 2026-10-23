package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.refund.RefundService;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Fast real-SQL checks; MySQL-specific locking and recovery have a separate acceptance suite. */
class FinancialCoreTest extends FinancialFixture {
  @Test
  void unknownObservationPreservesCloseIntentAndRepeatedCloseIsIdempotent() {
    var payment = createPayment("close-timeout", "10000");
    payments.requestClose("merchant-a", payment.id());
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("CLOSE_PENDING");
    assertThat(payments.requestClose("merchant-a", payment.id()).status())
        .isEqualTo("CLOSE_PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE kind='CHANNEL_CLOSE'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void aPreviouslyRecordedCloseIntentRemainsIdempotentForLegacyUnknownState() {
    var payment = createPayment("legacy-close-timeout", "10000");
    payments.requestClose("merchant-a", payment.id());
    jdbc.update("UPDATE payments SET status='UNKNOWN' WHERE id=?", payment.id());
    assertThat(payments.requestClose("merchant-a", payment.id()).status())
        .isEqualTo("CLOSE_PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE kind='CHANNEL_CLOSE'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void disabledMerchantCannotStartANewRefundButHistoryRemainsReadable() {
    var payment = paid("disabled-refund", "10000");
    jdbc.update("UPDATE merchants SET enabled=FALSE WHERE id='merchant-a'");
    assertThatThrownBy(
            () ->
                refunds.create(
                    "merchant-a", payment.id(), new RefundService.CreateRefund("blocked", "100")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("停用");
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void duplicateBusinessKeysReturnTheSamePayment() {
    var first = createPayment("order-1", "10000");
    var duplicate = createPayment("order-1", "10000");
    assertThat(duplicate.id()).isEqualTo(first.id());
    assertThat(count("payments")).isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(1);
  }

  @Test
  void duplicateBusinessKeysWithDifferentAmountsAreRejected() {
    createPayment("order-1", "10000");
    assertThatThrownBy(() -> createPayment("order-1", "10001"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("幂等");
    assertThat(count("payments")).isEqualTo(1);
  }

  @Test
  void oneSuccessFactPostsOnlyOneBalancedJournalDespiteDifferentDeliveryIds() {
    var payment = createPayment("repeat", "10000");
    for (int i = 0; i < 100; i++) {
      payments.applyResult(
          payment.id(), new ChannelResult.Success("MOCK_A", "receipt-1", 10000, "CNY"));
    }
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(count("ledger_journals")).isEqualTo(1);
    assertThat(count("ledger_entries")).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT amount_minor FROM ledger_entries WHERE account_code='MERCHANT_PAYABLE'",
                Long.class))
        .isEqualTo(9940);
  }

  @Test
  void paymentAndLedgerRollBackIfTheDurableEventCannotBeWritten() {
    var payment = createPayment("rollback", "10000");
    jdbc.execute(
        "ALTER TABLE outbox_tasks ADD CONSTRAINT reject_success CHECK (kind <> 'PAYMENT_SUCCEEDED')");
    assertThatThrownBy(
            () ->
                payments.applyResult(
                    payment.id(),
                    new ChannelResult.Success("MOCK_A", "receipt-rollback", 10000, "CNY")))
        .isInstanceOf(RuntimeException.class);
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("PROCESSING");
    assertThat(count("ledger_journals")).isZero();
    assertThat(count("ledger_entries")).isZero();
  }

  @Test
  void uncertainResultCanRecoverAndOldFailureDoesNotUndoSuccess() {
    var payment = createPayment("unknown", "10000");
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("UNKNOWN");
    payments.applyResult(
        payment.id(), new ChannelResult.Success("MOCK_A", "receipt-recovered", 10000, "CNY"));
    assertThatThrownBy(
            () ->
                payments.applyResult(
                    payment.id(), new ChannelResult.Failure("MOCK_A", "OLD_FAILED")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("证据");
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(count("ledger_journals")).isEqualTo(1);
  }

  @Test
  void successfulRefundKeepsItsMoneyButRejectsContradictoryFailureEvidence() {
    var payment = paid("refund-evidence", "10000");
    var refund =
        refunds.create(
            "merchant-a", payment.id(), new RefundService.CreateRefund("r-evidence", "3000"));
    refunds.applyResult(
        refund.id(), new ChannelResult.Success("MOCK_A", "ref-success", 3000, "CNY"));
    assertThatThrownBy(
            () ->
                refunds.applyResult(
                    refund.id(), new ChannelResult.Failure("MOCK_A", "contradiction")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("证据");
    assertThat(payments.get("merchant-a", payment.id()).refundedMinor()).isEqualTo("3000");
    assertThat(refunds.get("merchant-a", refund.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(count("ledger_journals")).isEqualTo(2);
  }

  @Test
  void definitiveFailureCreatesOneDurableMerchantNotification() {
    var payment = createPayment("declined", "100");
    payments.applyResult(payment.id(), new ChannelResult.Failure("MOCK_A", "DECLINED"));
    payments.applyResult(payment.id(), new ChannelResult.Failure("MOCK_A", "DECLINED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE kind='PAYMENT_FAILED'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void mismatchedEvidenceCannotMarkAPaymentSuccessful() {
    var payment = createPayment("mismatch", "10000");
    assertThatThrownBy(
            () ->
                payments.applyResult(
                    payment.id(), new ChannelResult.Success("MOCK_A", "mismatch", 9999, "CNY")))
        .isInstanceOf(ApiException.class);
    assertThat(count("ledger_journals")).isZero();
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("PROCESSING");
  }

  @Test
  void anotherMerchantCannotReadThePayment() {
    var payment = createPayment("isolation", "10000");
    assertThatThrownBy(() -> payments.get("merchant-b", payment.id()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void concurrentRefundsCannotReserveMoreThanWasPaid() throws Exception {
    var payment = paid("concurrent", "10000");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          executor.invokeAll(
              List.<Callable<Boolean>>of(
                  () -> tryRefund(payment.id(), "refund-80", "8000"),
                  () -> tryRefund(payment.id(), "refund-30", "3000")));
      int accepted = 0;
      for (var result : results) {
        if (result.get()) {
          accepted++;
        }
      }
      assertThat(accepted).isEqualTo(1);
    }
    var current = payments.get("merchant-a", payment.id());
    assertThat(Long.parseLong(current.reservedMinor())).isBetween(3000L, 8000L);
    assertThat(count("refunds")).isEqualTo(1);
  }

  boolean tryRefund(String paymentId, String businessNo, String amount) {
    try {
      refunds.create("merchant-a", paymentId, new RefundService.CreateRefund(businessNo, amount));
      return true;
    } catch (ApiException exception) {
      assertThat(exception.code()).isEqualTo("REFUND_LIMIT_EXCEEDED");
      return false;
    }
  }

  @Test
  void unknownRefundRetainsItsReservationAndFailureReleasesOnlyOnce() {
    var payment = paid("refund-unknown", "10000");
    var refund =
        refunds.create("merchant-a", payment.id(), new RefundService.CreateRefund("r-1", "8000"));
    refunds.applyResult(refund.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    assertThat(payments.get("merchant-a", payment.id()).reservedMinor()).isEqualTo("8000");
    assertThatThrownBy(
            () ->
                refunds.create(
                    "merchant-a", payment.id(), new RefundService.CreateRefund("r-2", "3000")))
        .isInstanceOf(ApiException.class);
    refunds.applyResult(refund.id(), new ChannelResult.Failure("MOCK_A", "DECLINED"));
    refunds.applyResult(refund.id(), new ChannelResult.Failure("MOCK_A", "DECLINED"));
    assertThat(payments.get("merchant-a", payment.id()).reservedMinor()).isEqualTo("0");
    assertThat(payments.get("merchant-a", payment.id()).refundedMinor()).isEqualTo("0");
  }

  @Test
  void outOfOrderPartialRefundsReturnTheExactCumulativeFee() {
    var payment = paid("rounding", "101");
    var first =
        refunds.create("merchant-a", payment.id(), new RefundService.CreateRefund("r-first", "50"));
    var second =
        refunds.create(
            "merchant-a", payment.id(), new RefundService.CreateRefund("r-second", "51"));
    refunds.applyResult(
        second.id(), new ChannelResult.Success("MOCK_A", "r-second-ref", 51, "CNY"));
    refunds.applyResult(first.id(), new ChannelResult.Success("MOCK_A", "r-first-ref", 50, "CNY"));
    refunds.applyResult(first.id(), new ChannelResult.Success("MOCK_A", "r-first-ref", 50, "CNY"));
    var current = payments.get("merchant-a", payment.id());
    assertThat(current.refundedMinor()).isEqualTo("101");
    assertThat(current.reservedMinor()).isEqualTo("0");
    assertThat(current.refundedFeeMinor()).isEqualTo("1");
    assertThat(count("ledger_journals")).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries",
                Long.class))
        .isZero();
  }
}
