package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import dev.merchantpay.platform.sharding.ShardProjectionReadGate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Merchant-scoped operational aggregates from the common serving generation. */
@RestController
@RequestMapping("/api/console/analytics")
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.projection-enabled",
      "merchantpay.read-model-enabled"
    },
    havingValue = "true")
public final class AnalyticsController {
  public record Report(
      String generation,
      String dimension,
      String from,
      String until,
      String observedAt,
      String consistency,
      String sourceWatermarkStatus,
      List<Map<String, Object>> rows) {}

  private final Access access;
  private final ShardProjectionReadGate gate;
  private final ClickHouseReadModel clickhouse;
  private final Clock clock;

  public AnalyticsController(
      Access access, ShardProjectionReadGate gate, ClickHouseReadModel clickhouse, Clock clock) {
    this.access = access;
    this.gate = gate;
    this.clickhouse = clickhouse;
    this.clock = clock;
  }

  @GetMapping("/payments")
  public Report payments(
      @RequestParam String merchantId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String until,
      @RequestParam(defaultValue = "DAY") String dimension) {
    access.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    String merchant = access.merchant(merchantId, true);
    Instant end;
    Instant start;
    try {
      end = until == null ? clock.instant() : Instant.parse(until);
      start = from == null ? end.minus(Duration.ofDays(7)) : Instant.parse(from);
    } catch (DateTimeException invalid) {
      throw new ApiException("INVALID_RANGE", 400, "分析时间范围无效");
    }
    if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0)
      throw new ApiException("INVALID_RANGE", 400, "分析时间范围最多31天");
    if (!"DAY".equals(dimension) && !"CHANNEL".equals(dimension))
      throw new ApiException("INVALID_DIMENSION", 400, "分析维度无效");
    String generation = gate.generation();
    String group =
        "DAY".equals(dimension)
            ? "toString(toDate(toTimeZone(created_at,'Asia/Shanghai')))"
            : "JSONExtractString(snapshot,'channel')";
    String sql =
        "SELECT "
            + group
            + " AS label,count() AS payments,"
            + "countIf(status='SUCCEEDED') AS succeeded,"
            + "sumIf(amount_minor,status='SUCCEEDED') AS succeeded_minor,"
            + "sum(refunded_minor) AS refunded_minor,"
            + "countIf(status='SUCCEEDED' AND succeeded_at IS NOT NULL "
            + "AND succeeded_at>=created_at) AS latency_samples,"
            + "avgIf(dateDiff('millisecond',created_at,succeeded_at),"
            + "status='SUCCEEDED' AND succeeded_at IS NOT NULL "
            + "AND succeeded_at>=created_at) AS avg_success_latency_ms,"
            + "quantileTDigestIf(0.99)(dateDiff('millisecond',created_at,succeeded_at),"
            + "status='SUCCEEDED' AND succeeded_at IS NOT NULL "
            + "AND succeeded_at>=created_at) AS p99_success_latency_ms,"
            + "max(source_updated_at) AS latest_source_updated_at "
            + "FROM merchantpay.payment_projection FINAL "
            + "WHERE generation={generation:String} AND merchant_id={merchant:String} "
            + "AND created_at>={from:DateTime64(6)} AND created_at<{until:DateTime64(6)} "
            + "GROUP BY label ORDER BY label LIMIT 101";
    var rows =
        clickhouse.query(
            sql,
            Map.of(
                "generation",
                generation,
                "merchant",
                merchant,
                "from",
                start.toString().replace('T', ' ').replace("Z", ""),
                "until",
                end.toString().replace('T', ' ').replace("Z", "")));
    if (rows.size() > 100)
      throw new ApiException("ANALYTICS_GROUP_LIMIT", 422, "分析分组超过100个，无法完整展示");
    if (!generation.equals(gate.generation()))
      throw new ApiException("GENERATION_CHANGED", 409, "分析查询期间投影代已切换");
    return new Report(
        generation,
        dimension,
        start.toString(),
        end.toString(),
        clock.instant().toString(),
        "EVENTUAL",
        "UNVERIFIED",
        rows.stream().map(AnalyticsController::metrics).toList());
  }

  private static Map<String, Object> metrics(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.put("success_rate_pct", percentage(row.get("succeeded"), row.get("payments")));
    result.put(
        "refund_rate_pct", percentage(row.get("refunded_minor"), row.get("succeeded_minor")));
    if (new BigDecimal(row.get("latency_samples").toString()).signum() == 0) {
      result.put("avg_success_latency_ms", null);
      result.put("p99_success_latency_ms", null);
    }
    return Collections.unmodifiableMap(result);
  }

  private static BigDecimal percentage(Object part, Object whole) {
    var denominator = new BigDecimal(whole.toString());
    if (denominator.signum() == 0) return null;
    return new BigDecimal(part.toString())
        .multiply(BigDecimal.valueOf(100))
        .divide(denominator, 2, RoundingMode.HALF_UP);
  }
}
