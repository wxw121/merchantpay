package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.common.DatabaseRole;
import io.micrometer.core.instrument.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "merchantpay.v2-metrics-enabled", havingValue = "true")
public class V2Metrics {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final AtomicLong projectionDead = new AtomicLong(),
      projectionAge = new AtomicLong(),
      quarantine = new AtomicLong(),
      holds = new AtomicLong(),
      circuits = new AtomicLong(),
      unknownAge = new AtomicLong(),
      unknownManual = new AtomicLong(),
      unknownLongTerm = new AtomicLong(),
      refreshed = new AtomicLong();

  public V2Metrics(JdbcTemplate jdbc, Clock clock, MeterRegistry registry) {
    this.jdbc = jdbc;
    this.clock = clock;
    Gauge.builder("merchantpay.projection.dead", projectionDead, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder("merchantpay.projection.oldest.seconds", projectionAge, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder("merchantpay.events.quarantined", quarantine, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder("merchantpay.finance.holds", holds, AtomicLong::doubleValue).register(registry);
    Gauge.builder("merchantpay.channels.open", circuits, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder("merchantpay.unknown.oldest.seconds", unknownAge, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder("merchantpay.unknown.manual", unknownManual, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder("merchantpay.unknown.long.term", unknownLongTerm, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder(
            "merchantpay.v2.metrics.staleness.seconds",
            () ->
                refreshed.get() == 0
                    ? Double.NaN
                    : Math.max(0, clock.instant().getEpochSecond() - refreshed.get()))
        .register(registry);
  }

  @Scheduled(fixedDelay = 15000)
  void refresh() {
    DatabaseRole.worker(
        () -> {
          try {
            long d =
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM projection_batches WHERE state='DEAD'", Long.class);
            Timestamp oldest =
                jdbc.queryForObject(
                    "SELECT MIN(created_at) FROM projection_batches WHERE state<>'DONE'",
                    Timestamp.class);
            long q =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'",
                        Long.class)
                    + jdbc.queryForObject(
                        "SELECT COUNT(*) FROM projection_errors WHERE state NOT IN ('REPLAYED','DISMISSED')",
                        Long.class);
            long h =
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM merchant_finance_holds WHERE active=true", Long.class);
            long c =
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM channel_controls WHERE open_until IS NOT NULL",
                    Long.class);
            var unknown =
                jdbc.queryForObject(
                    "SELECT MIN(unknown_since) AS oldest,SUM(CASE WHEN unknown_since<=? THEN 1 ELSE 0 END) AS manual_count,SUM(CASE WHEN unknown_since<=? THEN 1 ELSE 0 END) AS long_count FROM (SELECT unknown_since FROM payments WHERE status IN ('UNKNOWN','CLOSE_PENDING') AND unknown_since IS NOT NULL UNION ALL SELECT unknown_since FROM refunds WHERE status='UNKNOWN' AND unknown_since IS NOT NULL) u",
                    (rs, index) ->
                        new long[] {
                          rs.getTimestamp("oldest") == null
                              ? 0
                              : Math.max(
                                  0,
                                  Duration.between(
                                          rs.getTimestamp("oldest").toInstant(), clock.instant())
                                      .getSeconds()),
                          rs.getLong("manual_count"),
                          rs.getLong("long_count")
                        },
                    Timestamp.from(clock.instant().minusSeconds(1800)),
                    Timestamp.from(clock.instant().minusSeconds(86400)));
            projectionDead.set(d);
            projectionAge.set(
                oldest == null
                    ? 0
                    : Math.max(
                        0, Duration.between(oldest.toInstant(), clock.instant()).getSeconds()));
            quarantine.set(q);
            holds.set(h);
            circuits.set(c);
            unknownAge.set(unknown[0]);
            unknownManual.set(unknown[1]);
            unknownLongTerm.set(unknown[2]);
            refreshed.set(clock.instant().getEpochSecond());
          } catch (org.springframework.dao.DataAccessException unavailable) {
            /* Last observation remains visible with its staleness. */
          }
        });
  }
}
