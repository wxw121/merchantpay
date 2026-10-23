package dev.merchantpay.platform.web;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ApiException.RetryReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class ApiAvailabilityTest {
  @Test
  void mvcFailuresHaveOneBoundedReasonAndSameRetryContract() {
    var registry = new SimpleMeterRegistry();
    var advice = new ApiErrors(new ApiAvailability(registry));
    var capacity =
        advice.domain(
            new ApiException(
                "RETRY_WITH_SAME_BUSINESS_KEY", 503, "busy", RetryReason.PAYMENT_CAPACITY));
    assertThat(capacity.getHeaders().getFirst("X-Retry-Reason")).isEqualTo("PAYMENT_CAPACITY");
    assertThat(capacity.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    var database = advice.transientFailure(new CannotAcquireLockException("no raw sql in metrics"));
    assertThat(database.getHeaders().getFirst("X-Retry-Reason")).isEqualTo("DATABASE_TRANSIENT");
    assertThat(database.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    advice.domain(new ApiException("RETRY_WITH_SAME_BUSINESS_KEY", 503, "future reason"));
    advice.domain(new ApiException("INVALID_REQUEST", 400, "bad"));
    assertThat(
            registry
                .get("merchantpay.api.unavailable")
                .tag("reason", "PAYMENT_CAPACITY")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("merchantpay.api.unavailable")
                .tag("reason", "DATABASE_TRANSIENT")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("merchantpay.api.unavailable")
                .tag("reason", "UNCLASSIFIED")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.find("merchantpay.api.unavailable").counters())
        .hasSize(RetryReason.values().length);
  }
}
