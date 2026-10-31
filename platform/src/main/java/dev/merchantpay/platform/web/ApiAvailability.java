package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.ApiException.RetryReason;
import io.micrometer.core.instrument.*;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Fixed labels only: never record keys, merchants, URLs or exception messages as tags. */
@Component
public class ApiAvailability {
  public enum Stage {
    AUTH,
    NONCE,
    QUOTA,
    GATE,
    HANDLER
  }

  private final EnumMap<RetryReason, Counter> failures = new EnumMap<>(RetryReason.class);
  private final EnumMap<Stage, Timer> stages = new EnumMap<>(Stage.class);
  private final AtomicInteger active = new AtomicInteger();
  private final Counter borrowed;

  public ApiAvailability(MeterRegistry registry) {
    borrowed = registry.counter("merchantpay.api.admission.borrowed");
    Gauge.builder("merchantpay.api.active", active, AtomicInteger::doubleValue).register(registry);
    for (var reason : RetryReason.values())
      failures.put(
          reason, registry.counter("merchantpay.api.unavailable", "reason", reason.name()));
    for (var stage : Stage.values())
      stages.put(stage, registry.timer("merchantpay.api.stage", "stage", stage.name()));
  }

  public void entered() {
    active.incrementAndGet();
  }

  public void borrowedEntry() {
    borrowed.increment();
  }

  public void exited() {
    active.decrementAndGet();
  }

  public String rejected(RetryReason reason) {
    failures.get(reason).increment();
    return reason.name();
  }

  public <T> T time(Stage stage, Supplier<T> work) {
    return stages.get(stage).record(work);
  }

  public void elapsed(Stage stage, long started) {
    stages
        .get(stage)
        .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
  }

  public RetryReason reason(Exception failure) {
    if (failure instanceof ApiRateLimiter.Unavailable quota) return quota.reason();
    if (failure instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
        || failure instanceof org.springframework.transaction.CannotCreateTransactionException)
      return RetryReason.DATABASE_UNAVAILABLE;
    return RetryReason.DATABASE_TRANSIENT;
  }
}
