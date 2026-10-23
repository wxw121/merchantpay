package dev.merchantpay.simulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/channels/{channel}")
public class ChannelController {
  private final ChannelStore store;
  private final byte[] token;

  public ChannelController(
      ChannelStore store, @Value("${merchantpay.channel-token}") String token) {
    this.store = store;
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  private void authenticate(String header) {
    if (!MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  @GetMapping("/health")
  ChannelStore.Health health(
      @PathVariable String channel,
      @RequestParam(required = false) String merchantId,
      @RequestHeader("X-Channel-Token") String token) {
    authenticate(token);
    return store.health(channel, merchantId);
  }

  @PostMapping("/payments")
  ChannelStore.Receipt pay(
      @PathVariable String channel,
      @RequestHeader("X-Channel-Token") String token,
      @RequestBody ChannelStore.Request input) {
    authenticate(token);
    store.profile().delay(input.businessNo());
    var result = store.pay(channel, input);
    loseResponse(channel, "PAYMENT", input);
    return result;
  }

  @PostMapping("/refunds")
  ChannelStore.Receipt refund(
      @PathVariable String channel,
      @RequestHeader("X-Channel-Token") String token,
      @RequestBody ChannelStore.Request input) {
    authenticate(token);
    store.profile().delay(input.businessNo());
    var result = store.refund(channel, input);
    loseResponse(channel, "REFUND", input);
    return result;
  }

  @GetMapping("/{operation}/{id}")
  ChannelStore.Receipt query(
      @PathVariable String channel,
      @PathVariable String operation,
      @PathVariable String id,
      @RequestHeader("X-Channel-Token") String token) {
    authenticate(token);
    return store.query(channel, operation, id);
  }

  @PostMapping("/payments/{id}/close")
  ChannelStore.Receipt close(
      @PathVariable String channel,
      @PathVariable String id,
      @RequestHeader("X-Channel-Token") String token) {
    authenticate(token);
    return store.close(channel, id);
  }

  @GetMapping(value = "/bill", produces = "text/csv")
  String bill(
      @PathVariable String channel,
      @RequestParam String merchantId,
      @RequestParam LocalDate day,
      @RequestHeader("X-Channel-Token") String token) {
    authenticate(token);
    var zone = ZoneId.of("Asia/Shanghai");
    var rows =
        store.bill(
            channel,
            merchantId,
            day.atStartOfDay(zone).toInstant(),
            day.plusDays(1).atStartOfDay(zone).toInstant());
    var body =
        new StringBuilder("operation,aggregateId,status,amountMinor,feeMinor,currency,reference\n");
    long amount = 0;
    for (var row : rows) {
      body.append(row.operation())
          .append(',')
          .append(row.aggregateId())
          .append(',')
          .append(row.status())
          .append(',')
          .append(row.amountMinor())
          .append(',')
          .append(row.feeMinor())
          .append(',')
          .append(row.currency())
          .append(',')
          .append(row.reference())
          .append('\n');
      amount = Math.addExact(amount, Long.parseLong(row.amountMinor()));
    }
    return "#merchantpay-bill-v1,"
        + channel
        + ","
        + merchantId
        + ","
        + day
        + ","
        + rows.size()
        + ","
        + amount
        + "\n"
        + body;
  }

  private void loseResponse(String channel, String operation, ChannelStore.Request input) {
    if (input.businessNo().startsWith("lost:")
        && store.consumeLostResponse(channel, operation, input.aggregateId())) {
      throw new ResponseStatusException(
          HttpStatus.GATEWAY_TIMEOUT, "MOCK: accepted, response lost");
    }
  }
}
