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
  private final NotificationWrites writes;

  public NotificationService(
      PaymentService payments,
      RefundService refunds,
      MerchantService merchants,
      NotificationPolicy policy,
      JsonMapper json,
      JdbcTemplate jdbc,
      Clock clock) {
    this(payments, refunds, merchants, policy, json, jdbc, clock, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public NotificationService(
      PaymentService payments,
      RefundService refunds,
      MerchantService merchants,
      NotificationPolicy policy,
      JsonMapper json,
      JdbcTemplate jdbc,
      Clock clock,
      NotificationWrites writes) {
    this.payments = payments;
    this.refunds = refunds;
    this.merchants = merchants;
    this.policy = policy;
    this.json = json;
    this.jdbc = jdbc;
    this.clock = clock;
    this.writes = writes;
    var settings =
        HttpClientSettings.defaults()
            .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(5))
            .withRedirects(HttpRedirects.DONT_FOLLOW);
    var factories =
        ClientHttpRequestFactoryBuilder.httpComponents()
            .withConnectionManagerCustomizer(
                manager -> manager.setMaxConnPerRoute(16).setMaxConnTotal(16))
            .withDefaultRequestConfigCustomizer(
                config ->
                    config.setConnectionRequestTimeout(
                        org.apache.hc.core5.util.Timeout.ofSeconds(2)));
    external =
        RestClient.builder()
            .requestFactory(
                factories.build(
                    settings.withInetAddressFilter(InetAddressFilter.externalAddresses())))
            .build();
    local = RestClient.builder().requestFactory(factories.build(settings)).build();
  }

  public void deliver(TaskQueue.Task task) {
    deliver(task, null);
  }

  public record TaskInput(
      TaskQueue.Task task, PaymentService.PaymentView payment, RefundService.RefundView refund) {}

  public Map<String, TaskInput> prefetch(List<TaskQueue.Task> tasks) {
    if (tasks.size() > 64) throw new IllegalArgumentException("Notification batch exceeds64");
    var refundIds = new LinkedHashSet<String>();
    var paymentIds = new LinkedHashSet<String>();
    var taskIds = new HashSet<String>();
    for (var task : tasks) {
      if (!taskIds.add(task.id())
          || !Set.of(
                  "PAYMENT_SUCCEEDED",
                  "PAYMENT_FAILED",
                  "PAYMENT_CLOSED",
                  "REFUND_SUCCEEDED",
                  "REFUND_FAILED")
              .contains(task.kind()))
        throw new IllegalArgumentException("Invalid notification batch");
      if (task.kind().startsWith("REFUND_")) refundIds.add(task.aggregateId());
      else paymentIds.add(task.aggregateId());
    }
    var refundRows = refunds.loadForChannelTasks(refundIds);
    for (var refund : refundRows.values()) paymentIds.add(refund.paymentId());
    var paymentRows = payments.loadForChannelTasks(paymentIds);
    var inputs = new HashMap<String, TaskInput>();
    for (var task : tasks) {
      var refund = task.kind().startsWith("REFUND_") ? refundRows.get(task.aggregateId()) : null;
      var payment = paymentRows.get(refund == null ? task.aggregateId() : refund.paymentId());
      if (payment == null || task.kind().startsWith("REFUND_") && refund == null) continue;
      var input = new TaskInput(task, payment, refund);
      verify(task, input);
      inputs.put(task.id(), input);
    }
    return Map.copyOf(inputs);
  }

  private static void verify(TaskQueue.Task task, TaskInput input) {
    var p = input.payment();
    var r = input.refund();
    boolean refund = task.kind().startsWith("REFUND_");
    if (!task.equals(input.task())
        || p == null
        || !task.merchantId().equals(p.merchantId())
        || refund
            && (r == null
                || !task.aggregateId().equals(r.id())
                || !r.paymentId().equals(p.id())
                || !r.merchantId().equals(task.merchantId())
                || !r.channel().equals(p.channel()))
        || !refund && (r != null || !task.aggregateId().equals(p.id())))
      throw new IllegalArgumentException("Notification snapshot does not belong to claimed task");
  }

  public void deliver(TaskQueue.Task task, TaskInput input) {
    if (input != null) verify(task, input);
    boolean isRefund = task.kind().startsWith("REFUND_");
    var refund =
        input != null
            ? input.refund()
            : (isRefund ? refunds.get(task.merchantId(), task.aggregateId()) : null);
    var payment =
        input != null
            ? input.payment()
            : payments.get(task.merchantId(), isRefund ? refund.paymentId() : task.aggregateId());
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
    write(
        () ->
            jdbc.update(
                "INSERT INTO notification_deliveries(id,task_id,event_id,merchant_id,url,status,created_at) VALUES (?,?,?,?,?,'SENDING',?)",
                id,
                task.id(),
                task.eventId(),
                task.merchantId(),
                url,
                Timestamp.from(clock.instant())));
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
      write(
          () ->
              jdbc.update(
                  "UPDATE notification_deliveries SET status='DELIVERED',http_status=? WHERE id=?",
                  status,
                  id));
    } catch (RuntimeException failure) {
      write(
          () ->
              jdbc.update(
                  "UPDATE notification_deliveries SET status='FAILED',error_detail=? WHERE id=?",
                  failure.getClass().getSimpleName(),
                  id));
      throw new IllegalStateException("Merchant webhook delivery failed", failure);
    }
  }

  private void write(Runnable action) {
    if (writes == null) action.run();
    else writes.execute(action);
  }
}
