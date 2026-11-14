package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;

/** Exact tenant-scoped reads only; this does not route legacy writes or global lists. */
public class ShardReads {
  private final ShardTransactions transactions;

  public ShardReads(ShardTransactions transactions) {
    this.transactions = transactions;
  }

  public PaymentService.PaymentView payment(String merchant, String id) {
    return transactions.read(
        PaymentBuckets.forPaymentId(id), jdbc -> PaymentService.get(jdbc, merchant, id));
  }

  public RefundService.RefundView refund(String merchant, String id) {
    return transactions.read(
        PaymentBuckets.forRefundId(id), jdbc -> RefundService.get(jdbc, merchant, id));
  }

  public record SandboxCheckout(PaymentService.PaymentView payment, String qrCode) {}

  public SandboxCheckout sandboxCheckout(String merchant, String id) {
    return transactions.read(
        PaymentBuckets.forPaymentId(id),
        jdbc -> {
          var payment = PaymentService.get(jdbc, merchant, id);
          if (!"ALIPAY_SANDBOX".equals(payment.channel()))
            throw new ApiException("NOT_SANDBOX", 409, "该支付未选择沙箱渠道");
          var rows =
              jdbc.queryForList(
                  "SELECT qr_code FROM sandbox_checkouts WHERE payment_id=?", String.class, id);
          return new SandboxCheckout(payment, rows.isEmpty() ? "" : rows.getFirst());
        });
  }
}
