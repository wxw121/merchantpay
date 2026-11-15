package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import dev.merchantpay.platform.sharding.AnalyticsPublicationService;
import dev.merchantpay.platform.sharding.ShardProjectionReadGate;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator-only preview. Flink aggregates are never a financial or sealed-bill authority. */
@RestController
@RequestMapping("/api/console/analytics")
@ConditionalOnProperty(
    name = {
      "merchantpay.analytics.flink-enabled", "merchantpay.sharding.enabled",
      "merchantpay.sharding.projection-enabled", "merchantpay.read-model-enabled"
    },
    havingValue = "true")
public final class FlinkPreReconciliationController {
  public record Totals(
      long payments,
      long succeeded,
      long succeededMinor,
      long refundedMinor,
      String latestSourceUpdatedAt) {}

  public record Bill(String id, int version, String status, long paymentRows, long paymentMinor) {}

  public record Preview(
      String generation,
      String merchantId,
      LocalDate day,
      String channel,
      String consistency,
      Totals stream,
      Bill bill,
      Long countDelta,
      Long minorDelta) {}

  private final Access access;
  private final ShardProjectionReadGate gate;
  private final ClickHouseReadModel clickhouse;
  private final JdbcTemplate core;
  private final Clock clock;
  private final AnalyticsPublicationService publications;

  public FlinkPreReconciliationController(
      Access access,
      ShardProjectionReadGate gate,
      ClickHouseReadModel clickhouse,
      JdbcTemplate core,
      Clock clock) {
    this(access, gate, clickhouse, core, clock, null);
  }

  @Autowired
  public FlinkPreReconciliationController(
      Access access,
      ShardProjectionReadGate gate,
      ClickHouseReadModel clickhouse,
      JdbcTemplate core,
      Clock clock,
      ObjectProvider<AnalyticsPublicationService> publications) {
    this.access = access;
    this.gate = gate;
    this.clickhouse = clickhouse;
    this.core = core;
    this.clock = clock;
    this.publications = publications == null ? null : publications.getIfAvailable();
  }

  @GetMapping("/pre-reconciliation")
  public Preview preview(
      @RequestParam String merchantId, @RequestParam LocalDate day, @RequestParam String channel) {
    access.require("ADMIN", "OPS", "FINANCE", "AUDITOR");
    String merchant = access.merchant(merchantId, true);
    if (channel == null || !channel.matches("[A-Z0-9_]{1,32}"))
      throw new ApiException("INVALID_CHANNEL", 400, "渠道无效");
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
    if (day.isAfter(today) || day.isBefore(today.minusYears(2)))
      throw new ApiException("INVALID_DAY", 400, "预对账日期超出范围");
    String generation = gate.generation();
    var aggregate =
        clickhouse.query(
            "SELECT payments,succeeded,succeeded_minor,refunded_minor,latest_source_updated_at "
                + "FROM merchantpay.v3_payment_aggregates FINAL "
                + "WHERE generation={generation:String} AND merchant_id={merchant:String} "
                + "AND business_day={day:Date} AND channel={channel:String} "
                + "AND deleted=0 LIMIT 1",
            Map.of(
                "generation",
                generation,
                "merchant",
                merchant,
                "day",
                day.toString(),
                "channel",
                channel));
    Totals totals =
        aggregate.isEmpty()
            ? null
            : new Totals(
                number(aggregate.getFirst(), "payments"),
                number(aggregate.getFirst(), "succeeded"),
                number(aggregate.getFirst(), "succeeded_minor"),
                number(aggregate.getFirst(), "refunded_minor"),
                sourceTime(aggregate.getFirst()));
    var batches =
        core.queryForList(
            "SELECT id,version,status,expected_count,processed_lines FROM recon_batches "
                + "WHERE merchant_id=? AND channel=? AND business_day=? "
                + "ORDER BY version DESC LIMIT 1",
            merchant,
            channel,
            Date.valueOf(day));
    Bill bill = null;
    if (!batches.isEmpty()) {
      var batch = batches.getFirst();
      String state = batch.get("status").toString();
      if (("PRELIMINARY".equals(state) || "COMPLETE".equals(state))
          && number(batch, "expected_count") == number(batch, "processed_lines")) {
        var channelRows =
            core.queryForMap(
                "SELECT COUNT(*) AS payment_rows,"
                    + "COALESCE(SUM(amount_minor),0) AS payment_minor "
                    + "FROM recon_rows WHERE batch_id=? AND operation='PAYMENT' "
                    + "AND status='SUCCEEDED'",
                batch.get("id"));
        bill =
            new Bill(
                batch.get("id").toString(),
                ((Number) batch.get("version")).intValue(),
                state,
                number(channelRows, "payment_rows"),
                number(channelRows, "payment_minor"));
      }
    }
    if (!generation.equals(gate.generation()))
      throw new ApiException("GENERATION_CHANGED", 409, "预对账期间投影代已切换");
    String consistency =
        publications != null
                && "VERIFIED_AT_WATERMARK".equals(publications.current(generation).status())
            ? "PRELIMINARY_AT_VERIFIED_WATERMARK"
            : "PRELIMINARY_UNVERIFIED";
    return new Preview(
        generation,
        merchant,
        day,
        channel,
        consistency,
        totals,
        bill,
        totals == null || bill == null ? null : totals.succeeded() - bill.paymentRows(),
        totals == null || bill == null ? null : totals.succeededMinor() - bill.paymentMinor());
  }

  private static long number(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value instanceof Number count) return count.longValue();
    if (value instanceof String text && text.matches("[0-9]{1,18}")) return Long.parseLong(text);
    throw new ApiException("ANALYTICS_SOURCE_CONFLICT", 503, "指标来源数值无效");
  }

  private static String sourceTime(Map<String, Object> row) {
    Object value = row.get("latest_source_updated_at");
    if (value instanceof String time && !time.isBlank() && time.length() <= 40) return time;
    throw new ApiException("ANALYTICS_SOURCE_CONFLICT", 503, "指标来源时间无效");
  }
}
