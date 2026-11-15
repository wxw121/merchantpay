package dev.merchantpay.platform.channel;

/** Claims a successful external receipt before applying its financial result. */
public interface ChannelReceiptClaims {
  void claim(ChannelApi.Receipt receipt, String merchant);
}
