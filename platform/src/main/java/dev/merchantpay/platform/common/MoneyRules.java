package dev.merchantpay.platform.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyRules {
  public static final long MAX_PAYMENT = 1_000_000_000L;

  private MoneyRules() {}

  public static long parsePositive(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,9}")) {
      throw invalidAmount();
    }
    long amount = Long.parseLong(value);
    if (amount > MAX_PAYMENT) {
      throw invalidAmount();
    }
    return amount;
  }

  public static long fee(long amount) {
    if (amount < 1 || amount > MAX_PAYMENT) {
      throw invalidAmount();
    }
    return BigDecimal.valueOf(amount)
        .multiply(new BigDecimal("0.006"))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  public static long refundedFee(long originalFee, long originalAmount, long cumulativeRefunded) {
    if (originalAmount < 1
        || originalAmount > MAX_PAYMENT
        || originalFee < 0
        || originalFee > originalAmount
        || cumulativeRefunded < 0
        || cumulativeRefunded > originalAmount) {
      throw invalidAmount();
    }
    return BigDecimal.valueOf(originalFee)
        .multiply(BigDecimal.valueOf(cumulativeRefunded))
        .divide(BigDecimal.valueOf(originalAmount), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private static ApiException invalidAmount() {
    return new ApiException("INVALID_AMOUNT", 400, "金额必须为合法的正整数分，且不超过单笔限额");
  }
}
