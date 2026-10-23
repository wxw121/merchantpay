package dev.merchantpay.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyRulesTest {
  @ParameterizedTest
  @CsvSource({"1,1", "10000,10000", "1000000000,1000000000"})
  void acceptsOnlyPositiveMinorUnitsWithinTheBusinessLimit(String value, long expected) {
    assertThat(MoneyRules.parsePositive(value)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "-1",
        "1.0",
        "1e2",
        " 100",
        "+100",
        "01",
        "1000000001",
        "99999999999999999999"
      })
  void rejectsNonCanonicalOrOutOfRangeAmounts(String value) {
    assertThatThrownBy(() -> MoneyRules.parsePositive(value)).isInstanceOf(ApiException.class);
  }

  @ParameterizedTest
  @CsvSource({"1,0", "83,0", "84,1", "10000,60", "1000000000,6000000"})
  void roundsTheSixPerThousandFeeToTheNearestMinorUnit(long amount, long expected) {
    assertThat(MoneyRules.fee(amount)).isEqualTo(expected);
  }

  @Test
  void cumulativeRefundsReturnExactlyTheOriginalFeeAtFullRefund() {
    assertThat(MoneyRules.refundedFee(60, 10000, 3000)).isEqualTo(18);
    assertThat(MoneyRules.refundedFee(60, 10000, 10000)).isEqualTo(60);
    assertThat(MoneyRules.refundedFee(1, 101, 50)).isZero();
    assertThat(MoneyRules.refundedFee(1, 101, 101)).isEqualTo(1);
  }

  @Test
  void refusesToComputeARefundFeeBeyondTheOriginalPayment() {
    assertThatThrownBy(() -> MoneyRules.refundedFee(60, 10000, 10001))
        .isInstanceOf(ApiException.class);
  }
}
