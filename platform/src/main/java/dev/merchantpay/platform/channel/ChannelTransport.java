package dev.merchantpay.platform.channel;

import dev.merchantpay.platform.control.ChannelControl;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Isolates remote calls before financial processing, preserving the originally selected channel.
 */
@Service
public class ChannelTransport {
  private final ChannelApi mock;
  private final AlipaySandbox alipay;
  private final ChannelControl controls;

  public ChannelTransport(ChannelApi mock, AlipaySandbox alipay, ChannelControl controls) {
    this.mock = mock;
    this.alipay = alipay;
    this.controls = controls;
  }

  private <T> T call(String channel, Supplier<T> action) {
    var lease = controls.enter(channel);
    boolean healthy = false;
    try {
      T result = action.get();
      healthy = true;
      return result;
    } finally {
      controls.leave(lease, healthy);
    }
  }

  public ChannelApi.Receipt pay(String channel, String token, ChannelApi.Request input) {
    return call(
        channel,
        () ->
            AlipaySandbox.CHANNEL.equals(channel)
                ? alipay.pay(input)
                : mock.pay(channel, token, input));
  }

  public ChannelApi.Receipt refund(String channel, String token, ChannelApi.Request input) {
    return call(
        channel,
        () ->
            AlipaySandbox.CHANNEL.equals(channel)
                ? alipay.refund(input)
                : mock.refund(channel, token, input));
  }

  public ChannelApi.Receipt query(String channel, String operation, String id, String token) {
    return call(
        channel,
        () ->
            AlipaySandbox.CHANNEL.equals(channel)
                ? alipay.query(operation, id)
                : mock.query(channel, operation, id, token));
  }

  public ChannelApi.Receipt close(String channel, String id, String token) {
    return call(
        channel,
        () ->
            AlipaySandbox.CHANNEL.equals(channel)
                ? alipay.close(id)
                : mock.close(channel, id, token));
  }
}
