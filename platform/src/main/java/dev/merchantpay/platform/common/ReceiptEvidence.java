package dev.merchantpay.platform.common;

/** Raw channel evidence checked against the authoritative row while its payment root is locked. */
public record ReceiptEvidence(
    String operation,
    String status,
    String channel,
    String amountMinor,
    String feeMinor,
    String currency,
    String reference) {
  public void verify(long amount, String expectedChannel, boolean closeIntent) {
    if (status == null) throw new ApiException("INVALID_CHANNEL_RECEIPT", 400, "回执状态缺失");
    if (!java.util.Set.of("SUCCEEDED", "FAILED", "CLOSED").contains(status)) return;
    boolean tombstone =
        closeIntent
            && "PAYMENT".equals(operation)
            && "CLOSED".equals(status)
            && channel != null
            && channel.startsWith("MOCK_")
            && "0".equals(amountMinor)
            && "0".equals(feeMinor)
            && reference != null
            && reference.startsWith("mock-close-");
    if (!expectedChannel.equals(channel)
        || !"CNY".equals(currency)
        || !tombstone && !Long.toString(amount).equals(amountMinor))
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "渠道回执金额、币种或渠道与持锁原业务不一致");
  }
}
