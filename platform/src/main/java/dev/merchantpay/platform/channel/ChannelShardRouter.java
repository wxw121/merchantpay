package dev.merchantpay.platform.channel;

/** Routes verified channel facts to their authoritative shard. */
public interface ChannelShardRouter {
  void applyVerified(ChannelApi.Receipt receipt);

  void storeVerified(
      ChannelApi.Receipt receipt, String body, String fingerprint, String signedForm);
}
