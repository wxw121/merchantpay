package dev.merchantpay.platform.common;

import java.util.regex.Pattern;

/** Stable V1/V2 identity rules; physical placement is a separate, versioned directory. */
public final class PaymentBuckets {
  private static final String SUFFIX =
      "_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final Pattern PAYMENT = Pattern.compile("p([0-9a-f])" + SUFFIX);
  private static final Pattern REFUND = Pattern.compile("r([0-9a-f])" + SUFFIX);

  private PaymentBuckets() {}

  public static int forBusinessKey(String merchant, String order) {
    if (!valid(merchant, 64) || !valid(order, 100))
      throw new ApiException("INVALID_ROUTING_KEY", 400, "商户及业务号不能为空、包含控制字符或超过长度限制");
    return Integer.parseInt(Signatures.sha256(merchant + "\0" + order).substring(0, 2), 16) & 15;
  }

  public static int forPaymentId(String id) {
    return parse(id, PAYMENT);
  }

  public static int forRefundId(String id) {
    return parse(id, REFUND);
  }

  private static boolean valid(String value, int maximum) {
    return value != null
        && !value.isBlank()
        && value.length() <= maximum
        && value.chars().noneMatch(Character::isISOControl);
  }

  private static int parse(String id, Pattern pattern) {
    if (id != null && id.length() == 39) {
      var match = pattern.matcher(id);
      if (match.matches()) return Integer.parseInt(match.group(1), 16);
    }
    throw new ApiException("INVALID_AGGREGATE_ID", 400, "支付号或退款号格式无效");
  }
}
