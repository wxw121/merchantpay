package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.NotificationPolicy;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", version = "1.0")
public class OpenApiController {
  private final Access access;
  private final PaymentService payments;
  private final RefundService refunds;
  private final MerchantService merchants;
  private final NotificationPolicy urls;
  private final TransactionTemplate transactions;
  private dev.merchantpay.platform.sharding.ShardReads shardReads;
  private dev.merchantpay.platform.sharding.ShardMoney shardMoney;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShardMoney(dev.merchantpay.platform.sharding.ShardMoney money) {
    this.shardMoney = money;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShardReads(dev.merchantpay.platform.sharding.ShardReads reader) {
    this.shardReads = reader;
  }

  public OpenApiController(
      Access access,
      PaymentService payments,
      RefundService refunds,
      MerchantService merchants,
      NotificationPolicy urls,
      TransactionTemplate transactions) {
    this.access = access;
    this.payments = payments;
    this.refunds = refunds;
    this.merchants = merchants;
    this.urls = urls;
    this.transactions = transactions;
  }

  @PostMapping("/payments")
  @ResponseStatus(HttpStatus.ACCEPTED)
  PaymentService.PaymentView create(@RequestBody PaymentService.CreatePayment input) {
    access.require("MERCHANT");
    urls.validate(input.notificationUrl());
    String merchant = access.merchant(null, true);
    if (shardMoney != null) return shardMoney.create(merchant, input, access.caller().actor());
    return transactions.execute(
        tx -> {
          var result = payments.create(merchant, input);
          merchants.audit(
              merchant,
              access.caller().actor(),
              "PAYMENT_CREATE",
              result.id(),
              result.merchantOrderNo());
          return result;
        });
  }

  @GetMapping("/payments/{id}")
  PaymentService.PaymentView payment(@PathVariable String id) {
    String merchant = access.merchant(null, true);
    return shardReads == null ? payments.get(merchant, id) : shardReads.payment(merchant, id);
  }

  @PostMapping("/payments/{id}/close")
  @ResponseStatus(HttpStatus.ACCEPTED)
  PaymentService.PaymentView close(@PathVariable String id) {
    String merchant = access.merchant(null, true);
    if (shardMoney != null) return shardMoney.close(merchant, id, access.caller().actor());
    return transactions.execute(
        tx -> {
          var result = payments.requestClose(merchant, id);
          merchants.audit(
              merchant, access.caller().actor(), "PAYMENT_CLOSE", id, "Close requested");
          return result;
        });
  }

  @PostMapping("/payments/{id}/refunds")
  @ResponseStatus(HttpStatus.ACCEPTED)
  RefundService.RefundView refund(
      @PathVariable String id, @RequestBody RefundService.CreateRefund input) {
    String merchant = access.merchant(null, true);
    if (shardMoney != null) return shardMoney.refund(merchant, id, input, access.caller().actor());
    return transactions.execute(
        tx -> {
          var result = refunds.create(merchant, id, input);
          merchants.audit(
              merchant,
              access.caller().actor(),
              "REFUND_CREATE",
              result.id(),
              result.merchantRefundNo());
          return result;
        });
  }

  @GetMapping("/refunds/{id}")
  RefundService.RefundView refund(@PathVariable String id) {
    String merchant = access.merchant(null, true);
    return shardReads == null ? refunds.get(merchant, id) : shardReads.refund(merchant, id);
  }
}
