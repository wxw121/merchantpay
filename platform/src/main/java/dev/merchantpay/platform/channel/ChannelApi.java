package dev.merchantpay.platform.channel;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

@HttpExchange(accept = "application/json")
public interface ChannelApi {
  record Health(
      String channel, long pendingCallbacks, long pendingPayments, String oldestCallbackAt) {}

  @GetExchange("/channels/{channel}/health")
  Health health(
      @PathVariable String channel,
      @RequestParam(required = false) String merchantId,
      @RequestHeader("X-Channel-Token") String token);

  record Request(
      String aggregateId,
      String paymentId,
      String merchantId,
      String businessNo,
      String amountMinor,
      String currency,
      java.time.Instant businessCreatedAt) {}

  record Receipt(
      String aggregateId,
      String operation,
      String channel,
      String status,
      String reference,
      String amountMinor,
      String currency,
      String feeMinor) {}

  @PostExchange("/channels/{channel}/payments")
  Receipt pay(
      @PathVariable String channel,
      @RequestHeader("X-Channel-Token") String token,
      @RequestBody Request input);

  @PostExchange("/channels/{channel}/refunds")
  Receipt refund(
      @PathVariable String channel,
      @RequestHeader("X-Channel-Token") String token,
      @RequestBody Request input);

  @GetExchange("/channels/{channel}/{operation}/{id}")
  Receipt query(
      @PathVariable String channel,
      @PathVariable String operation,
      @PathVariable String id,
      @RequestHeader("X-Channel-Token") String token);

  @PostExchange("/channels/{channel}/payments/{id}/close")
  Receipt close(
      @PathVariable String channel,
      @PathVariable String id,
      @RequestHeader("X-Channel-Token") String token);
}
