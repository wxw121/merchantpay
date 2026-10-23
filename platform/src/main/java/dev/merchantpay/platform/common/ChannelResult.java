package dev.merchantpay.platform.common;

public sealed interface ChannelResult {
  String channel();

  record Success(String channel, String reference, long amountMinor, String currency)
      implements ChannelResult {}

  record Failure(String channel, String code) implements ChannelResult {}

  record Pending(String channel) implements ChannelResult {}

  record Unknown(String channel, String reason) implements ChannelResult {}

  record Closed(String channel) implements ChannelResult {}
}
