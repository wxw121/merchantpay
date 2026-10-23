package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.common.DatabaseRole;
import io.micrometer.core.instrument.*;
import java.time.*;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gauges expose a real refresh watermark; collection errors never manufacture a healthy zero. */
@Component
public class BusinessMetrics {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final AtomicLong pending = new AtomicLong(),
      dead = new AtomicLong(),
      refreshed = new AtomicLong(),
      oldest = new AtomicLong();

  public BusinessMetrics(JdbcTemplate jdbc, Clock clock, MeterRegistry registry) {
    this.jdbc = jdbc;
    this.clock = clock;
    Gauge.builder("merchantpay.tasks.pending", pending, AtomicLong::doubleValue).register(registry);
    Gauge.builder("merchantpay.tasks.dead", dead, AtomicLong::doubleValue).register(registry);
    Gauge.builder("merchantpay.tasks.oldest.seconds", oldest, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder(
            "merchantpay.metrics.staleness.seconds",
            () ->
                refreshed.get() == 0
                    ? Double.NaN
                    : Math.max(0, clock.instant().getEpochSecond() - refreshed.get()))
        .register(registry);
  }

  @Scheduled(fixedDelay = 15000)
  void refresh() {
    DatabaseRole.worker(this::refreshWorker);
  }

  private void refreshWorker() {
    try {
      long pendingCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM outbox_tasks WHERE status IN ('PENDING','RUNNING')",
              Long.class);
      long deadCount =
          jdbc.queryForObject("SELECT COUNT(*) FROM outbox_tasks WHERE status='DEAD'", Long.class);
      var minimum =
          jdbc.queryForObject(
              "SELECT MIN(created_at) FROM outbox_tasks WHERE status IN ('PENDING','RUNNING')",
              java.sql.Timestamp.class);
      pending.set(pendingCount);
      dead.set(deadCount);
      oldest.set(
          minimum == null
              ? 0
              : Math.max(0, Duration.between(minimum.toInstant(), clock.instant()).getSeconds()));
      refreshed.set(clock.instant().getEpochSecond());
    } catch (org.springframework.dao.DataAccessException unavailable) {
      /* Keep the last values and let staleness alert. */
    }
  }
}
