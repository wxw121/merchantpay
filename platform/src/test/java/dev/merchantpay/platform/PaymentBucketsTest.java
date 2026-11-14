package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.payment.PaymentService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentBucketsTest {
  @Test
  void businessKeysRetainTheV1V2HashRuleWithoutTrimmingOrChangingUnicode() {
    for (String merchant : new String[] {"merchant-a", "商户甲", "m_03"})
      for (String order : new String[] {"order-1", "订单一", " order-1", "order-1 "}) {
        int legacy =
            Integer.parseInt(PaymentService.sha256(merchant + "\0" + order).substring(0, 2), 16)
                & 15;
        assertThat(PaymentBuckets.forBusinessKey(merchant, order)).isEqualTo(legacy);
      }
  }

  @Test
  void paymentAndRefundIdentifiersResolveTheSameBucketForV4AndV7Suffixes() {
    for (String suffix :
        new String[] {
          "552ea710-7879-4b42-876a-f145cdcf51d3", "019a3f20-4211-7c21-8a17-52775e16683a"
        })
      for (int bucket = 0; bucket < 16; bucket++) {
        String marker = Integer.toHexString(bucket);
        assertThat(PaymentBuckets.forPaymentId("p" + marker + "_" + suffix)).isEqualTo(bucket);
        assertThat(PaymentBuckets.forRefundId("r" + marker + "_" + suffix)).isEqualTo(bucket);
      }
  }

  @Test
  void malformedOrWrongAggregateIdentifiersCannotBeSilentlyRouted() {
    for (String id :
        new String[] {
          "p10_" + UUID.randomUUID(),
          "pg_" + UUID.randomUUID(),
          "p0_bad",
          "r0_" + UUID.randomUUID(),
          "p0_00000000-0000-0000-0000-000000000000/extra"
        })
      assertThatThrownBy(() -> PaymentBuckets.forPaymentId(id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> PaymentBuckets.forPaymentId(null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> PaymentBuckets.forBusinessKey("merchant-a", "x\0y"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> PaymentBuckets.forBusinessKey("", "order"))
        .isInstanceOf(ApiException.class);
  }
}
