package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.control.*;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.outbox.TaskRecovery;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.query.Queries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console")
public class OperationsController {
  public record Control(
      @Min(1) long expectedVersion,
      int maxInflight,
      int maxPerSecond,
      int failureThreshold,
      int openSeconds,
      boolean enabled,
      @NotBlank String reason) {}

  public record Route(
      @Min(0) long expectedVersion, int priority, boolean enabled, @NotBlank String reason) {}

  private final Access access;
  private final ChannelControl channels;
  private final RedisAdmission redis;
  private final JdbcTemplate jdbc;
  private final PaymentService payments;
  private final Clock clock;

  public OperationsController(
      Access access,
      ChannelControl channels,
      RedisAdmission redis,
      JdbcTemplate jdbc,
      PaymentService payments,
      Clock clock) {
    this.access = access;
    this.channels = channels;
    this.redis = redis;
    this.jdbc = jdbc;
    this.payments = payments;
    this.clock = clock;
  }

  private Actor actor() {
    var c = access.caller();
    return new Actor(c.actor(), c.merchantId(), c.roles());
  }

  private void operator() {
    EventDelivery.requireOperator(actor());
  }

  @GetMapping("/channel-controls")
  List<Map<String, Object>> controls() {
    return channels.status(actor());
  }

  @GetMapping("/available-channels")
  Map<String, Object> available() {
    access.require("ADMIN", "OPS", "MERCHANT", "FINANCE", "AUDITOR");
    return Map.of(
        "routingEnabled",
        channels.routingEnabled(),
        "channels",
        List.of("MOCK_A", "MOCK_B", "ALIPAY_SANDBOX").stream().filter(channels::supports).toList());
  }

  @PostMapping("/channel-controls/{channel}")
  void configure(@PathVariable String channel, @Valid @RequestBody Control input) {
    channels.configure(
        channel,
        input.expectedVersion(),
        input.maxInflight(),
        input.maxPerSecond(),
        input.failureThreshold(),
        input.openSeconds(),
        input.enabled(),
        input.reason(),
        actor());
  }

  @GetMapping("/merchants/{id}/routes")
  List<Map<String, Object>> routes(@PathVariable String id) {
    return channels.routes(id, actor());
  }

  @PostMapping("/merchants/{id}/routes/{channel}")
  void route(
      @PathVariable String id, @PathVariable String channel, @Valid @RequestBody Route input) {
    channels.route(
        id,
        channel,
        input.expectedVersion(),
        input.priority(),
        input.enabled(),
        input.reason(),
        actor());
  }

  @GetMapping("/payments/{id}/checkout")
  Map<String, Object> checkout(
      @PathVariable String id, @RequestParam(required = false) String merchantId) {
    var payment = payments.get(access.merchant(merchantId, true), id);
    if (!"ALIPAY_SANDBOX".equals(payment.channel()))
      throw new ApiException("NOT_SANDBOX", 409, "该支付未选择沙箱渠道");
    var rows =
        jdbc.queryForList(
            "SELECT qr_code FROM sandbox_checkouts WHERE payment_id=?", String.class, id);
    return Map.of(
        "paymentId",
        id,
        "environment",
        "ALIPAY_SANDBOX",
        "status",
        payment.status(),
        "qrCode",
        rows.isEmpty() ? "" : rows.getFirst(),
        "externallyVerified",
        false);
  }

  @GetMapping("/operations/summary")
  Map<String, Object> summary() {
    operator();
    var result = new LinkedHashMap<String, Object>();
    result.put("observedAt", clock.instant().toString());
    result.put("redisAdmission", redis.status());
    result.put(
        "queues",
        jdbc.query(
            "SELECT queue_class,status,COUNT(*) AS total,MIN(due_at) AS oldest_due FROM outbox_tasks GROUP BY queue_class,status",
            Queries.ROW));
    result.put(
        "channelFailures",
        jdbc.query(
            "SELECT channel,COUNT(*) AS total,MAX(created_at) AS latest FROM channel_attempts WHERE created_at>=? AND (status='UNKNOWN' OR status='FAILED') GROUP BY channel",
            Queries.ROW,
            Timestamp.from(clock.instant().minusSeconds(900))));
    result.put(
        "projection",
        jdbc.query(
            "SELECT state,COUNT(*) AS total,MIN(created_at) AS oldest FROM projection_batches WHERE state<>'DONE' GROUP BY state",
            Queries.ROW));
    result.put(
        "holds",
        jdbc.query(
            "SELECT hold_type,COUNT(*) AS total FROM merchant_finance_holds WHERE active=true GROUP BY hold_type",
            Queries.ROW));
    return result;
  }

  @GetMapping("/operations/unknown")
  List<Map<String, Object>> unknown(
      @RequestParam String merchantId, @RequestParam(defaultValue = "ALL") String stage) {
    operator();
    String scope = actor().scope(merchantId, true);
    if (!Set.of("ALL", "PRIORITY", "ALERT", "MANUAL", "LONG_TERM").contains(stage))
      throw new ApiException("INVALID_RECOVERY_STAGE", 400, "未知的查证阶段");
    var now = clock.instant();
    String range =
        switch (stage) {
          case "PRIORITY" -> " AND unknown_since>?";
          case "ALERT", "MANUAL" -> " AND unknown_since<=? AND unknown_since>?";
          case "LONG_TERM" -> " AND unknown_since<=?";
          default -> "";
        };
    var bounds =
        switch (stage) {
          case "PRIORITY" -> List.of(now.minusSeconds(120));
          case "ALERT" -> List.of(now.minusSeconds(120), now.minusSeconds(1800));
          case "MANUAL" -> List.of(now.minusSeconds(1800), now.minusSeconds(86400));
          case "LONG_TERM" -> List.of(now.minusSeconds(86400));
          default -> List.<Instant>of();
        };
    var parameters = new ArrayList<Object>();
    for (int i = 0; i < 2; i++) {
      parameters.add(scope);
      bounds.forEach(bound -> parameters.add(Timestamp.from(bound)));
    }
    var rows =
        jdbc.query(
            "SELECT id,id AS payment_id,'PAYMENT' AS operation,channel,status,amount_minor,unknown_since,updated_at FROM payments WHERE merchant_id=? AND status IN ('UNKNOWN','CLOSE_PENDING') AND unknown_since IS NOT NULL"
                + range
                + " UNION ALL SELECT id,payment_id,'REFUND' AS operation,channel,status,amount_minor,unknown_since,updated_at FROM refunds WHERE merchant_id=? AND status='UNKNOWN' AND unknown_since IS NOT NULL"
                + range
                + " ORDER BY unknown_since,id LIMIT 100",
            Queries.ROW,
            parameters.toArray());
    rows.forEach(
        row -> {
          var since = Instant.parse((String) row.get("unknownSince"));
          row.put("recoveryStage", TaskRecovery.stage(since, now));
          row.put(
              "unknownAgeSeconds",
              Long.toString(Math.max(0, Duration.between(since, now).getSeconds())));
        });
    return rows;
  }

  @GetMapping("/operations/reservations")
  List<Map<String, Object>> reservations(@RequestParam String merchantId) {
    operator();
    return jdbc.query(
        "SELECT id,payment_id,channel,status,amount_minor,created_at,updated_at FROM refunds WHERE merchant_id=? AND status IN ('PROCESSING','PENDING','UNKNOWN') ORDER BY created_at,id LIMIT 100",
        Queries.ROW,
        actor().scope(merchantId, true));
  }

  @GetMapping("/operations/dead-tasks")
  List<Map<String, Object>> dead() {
    operator();
    return jdbc.query(
        "SELECT id,event_id,kind,aggregate_id,merchant_id,attempts,last_error,retry_started_at,recovery_stage,updated_at FROM outbox_task_records WHERE status='DEAD' ORDER BY updated_at,id LIMIT 100",
        Queries.ROW);
  }
}
