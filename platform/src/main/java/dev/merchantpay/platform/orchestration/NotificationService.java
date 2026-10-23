package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.http.client.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Service
public class NotificationService {
  private final PaymentService payments;
  private final RefundService refunds;
  private final MerchantService merchants;
  private final NotificationPolicy policy;
  private final JsonMapper json;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final RestClient external, local;

  public NotificationService(
      PaymentService payments,
      RefundService refunds,
      MerchantService merchants,
      NotificationPolicy policy,
      JsonMapper json,
      JdbcTemplate jdbc,
      Clock clock) {
    this.payments = payments;
    this.refunds = refunds;
    this.merchants = merchants;
    this.policy = policy;
    this.json = json;
    this.jdbc = jdbc;
    this.clock = clock;
    var settings =
        HttpClientSettings.defaults()
            .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(5))
            .withRedirects(HttpRedirects.DONT_FOLLOW);
    external =
        RestClient.builder()
            .requestFactory(
                ClientHttpRequestFactoryBuilder.httpComponents()
                    .build(settings.withInetAddressFilter(InetAddressFilter.externalAddresses())))
            .build();
    local =
        RestClient.builder()
            .requestFactory(ClientHttpRequestFactoryBuilder.httpComponents().build(settings))
            .build();
  }

  public void deliver(TaskQueue.Task task) {
    boolean isRefund = task.kind().startsWith("REFUND_");
    var refund = isRefund ? refunds.get(task.merchantId(), task.aggregateId()) : null;
    var payment =
        payments.get(task.merchantId(), isRefund ? refund.paymentId() : task.aggregateId());
    String url = policy.validate(payment.notificationUrl());
    if (url == null) {
      return;
    }
    var key = merchants.notificationKey(task.merchantId());
    String body =
        json.writeValueAsString(
            Map.of(
                "eventId",
                task.eventId(),
                "eventType",
                task.kind(),
                "contractVersion",
                1,
                "data",
                isRefund ? refund : payment));
    String timestamp = Long.toString(clock.instant().getEpochSecond()),
        nonce = UUID.randomUUID().toString();
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO notification_deliveries(id,task_id,event_id,merchant_id,url,status,created_at) VALUES (?,?,?,?,?,'SENDING',?)",
        id,
        task.id(),
        task.eventId(),
        task.merchantId(),
        url,
        Timestamp.from(clock.instant()));
    try {
      var response =
          (policy.isLocalDemo(url) ? local : external)
              .post()
              .uri(url)
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .header("X-Key-Id", key.keyId())
              .header("X-Timestamp", timestamp)
              .header("X-Nonce", nonce)
              .header(
                  "X-Signature",
                  Signatures.hmac(key.secret(), timestamp + "\n" + nonce + "\n" + body))
              .body(body)
              .retrieve()
              .toBodilessEntity();
      int status = response.getStatusCode().value();
      if (status / 100 != 2) {
        throw new IllegalStateException("Webhook HTTP " + status);
      }
      jdbc.update(
          "UPDATE notification_deliveries SET status='DELIVERED',http_status=? WHERE id=?",
          status,
          id);
    } catch (RuntimeException failure) {
      jdbc.update(
          "UPDATE notification_deliveries SET status='FAILED',error_detail=? WHERE id=?",
          failure.getClass().getSimpleName(),
          id);
      throw new IllegalStateException("Merchant webhook delivery failed", failure);
    }
  }
}
