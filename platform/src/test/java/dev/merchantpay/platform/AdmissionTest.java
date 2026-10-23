package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.web.ApiAvailability;
import dev.merchantpay.platform.web.ApiRateLimiter;
import dev.merchantpay.platform.web.SignatureFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import java.sql.SQLException;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;

class AdmissionTest {
  final Clock clock = Clock.systemUTC();
  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  final ApiAvailability availability = new ApiAvailability(metrics);

  MerchantService merchant(boolean unavailable) {
    return new MerchantService(null, null, null, null, clock) {
      @Override
      public Key key(String id) {
        if (unavailable)
          throw new CannotGetJdbcConnectionException("pool exhausted", new SQLException());
        return new Key(id, "merchant-a", "test-secret");
      }
    };
  }

  JdbcTemplate jdbc() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    return jdbc;
  }

  MockHttpServletRequest request() {
    return request("POST", "/api/v1/payments");
  }

  MockHttpServletRequest request(String method, String path) {
    String time = Long.toString(clock.instant().getEpochSecond()),
        nonce = UUID.randomUUID().toString();
    var request = new MockHttpServletRequest(method, path);
    request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("X-Key-Id", "key");
    request.addHeader("X-Timestamp", time);
    request.addHeader("X-Nonce", nonce);
    request.addHeader(
        "X-Signature", Signatures.sign("test-secret", time, nonce, method, path, "{}"));
    return request;
  }

  ApiRateLimiter limiter() {
    var limiter = mock(ApiRateLimiter.class);
    when(limiter.allow(anyString(), anyLong())).thenReturn(true);
    return limiter;
  }

  @Test
  void paymentFloodLeavesReservedRefundAndQueryCapacityWithinOriginalTotal() throws Exception {
    var filter =
        new SignatureFilter(
            merchant(false),
            jdbc(),
            clock,
            JsonMapper.builder().build(),
            limiter(),
            16,
            availability);
    var release = new CountDownLatch(1);
    var active = new AtomicInteger();
    var peak = new AtomicInteger();
    var requests = new java.util.ArrayList<Future<?>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        for (int i = 0; i < 16; i++) {
          var response = new MockHttpServletResponse();
          var reached = new CountDownLatch(1);
          requests.add(
              executor.submit(
                  () -> {
                    try {
                      filter.doFilter(
                          request(),
                          response,
                          (req, res) -> {
                            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                            reached.countDown();
                            try {
                              release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                              throw new ServletException(e);
                            } finally {
                              active.decrementAndGet();
                            }
                          });
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  }));
          org.awaitility.Awaitility.await()
              .atMost(Duration.ofSeconds(3))
              .until(() -> reached.getCount() == 0 || response.getStatus() == 503);
        }
        assertThat(active.get()).isEqualTo(10);
        for (var endpoint :
            java.util.List.of(
                new String[] {"POST", "/api/v1/payments/p/refunds"},
                new String[] {"GET", "/api/v1/payments/p"})) {
          var response = new MockHttpServletResponse();
          var passed = new AtomicInteger();
          filter.doFilter(
              request(endpoint[0], endpoint[1]), response, (req, res) -> passed.incrementAndGet());
          assertThat(response.getStatus()).isEqualTo(200);
          assertThat(passed.get()).isEqualTo(1);
        }
        assertThat(peak.get()).isLessThanOrEqualTo(16);
      } finally {
        release.countDown();
      }
      for (var pending : requests) pending.get(5, TimeUnit.SECONDS);
    }
    filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {});
  }

  @Test
  void overloadIsRejectedBeforeDatabaseWorkAndPermitsSurviveFailures() throws Exception {
    var filter =
        new SignatureFilter(
            merchant(false),
            jdbc(),
            clock,
            JsonMapper.builder().build(),
            limiter(),
            1,
            availability);
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var completed = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                filter.doFilter(
                    request(),
                    new MockHttpServletResponse(),
                    (req, res) -> {
                      entered.countDown();
                      try {
                        resume.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(failure);
                      }
                    });
                return null;
              });
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var busy = new MockHttpServletResponse();
        filter.doFilter(
            request(),
            busy,
            (req, res) -> {
              throw new AssertionError("Overloaded request entered business logic");
            });
        assertThat(busy.getStatus()).isEqualTo(503);
        assertThat(busy.getHeader("Retry-After")).isEqualTo("1");
        assertThat(busy.getHeader("X-Retry-Reason")).isEqualTo("API_INFLIGHT");
        assertThat(
                metrics
                    .get("merchantpay.api.unavailable")
                    .tag("reason", "API_INFLIGHT")
                    .counter()
                    .count())
            .isEqualTo(1);
      } finally {
        resume.countDown();
      }
      first.get(5, TimeUnit.SECONDS);
      assertThatThrownBy(
              () ->
                  filter.doFilter(
                      request(),
                      new MockHttpServletResponse(),
                      (req, res) -> {
                        throw new ServletException("downstream");
                      }))
          .isInstanceOf(ServletException.class);
      filter.doFilter(
          request(), new MockHttpServletResponse(), (req, res) -> completed.incrementAndGet());
      assertThat(completed.get()).isEqualTo(1);
    }
  }

  @Test
  void connectionExhaustionInAuthenticationReturnsRetryable503() throws Exception {
    var filter =
        new SignatureFilter(
            merchant(true),
            jdbc(),
            clock,
            JsonMapper.builder().build(),
            limiter(),
            1,
            availability);
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request(),
        response,
        (req, res) -> {
          throw new AssertionError("Unavailable request entered business logic");
        });
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("RETRY_WITH_SAME_BUSINESS_KEY");
    assertThat(response.getHeader("X-Retry-Reason")).isEqualTo("DATABASE_UNAVAILABLE");
    assertThat(metrics.get("merchantpay.api.stage").tag("stage", "AUTH").timer().count())
        .isEqualTo(1);
  }

  @Test
  void quotaFailureReturnsRetryable503WithoutEnteringBusinessLogic() throws Exception {
    var limiter = limiter();
    when(limiter.allow(anyString(), anyLong()))
        .thenThrow(new org.springframework.dao.TransientDataAccessResourceException("quota down"));
    var filter =
        new SignatureFilter(
            merchant(false), jdbc(), clock, JsonMapper.builder().build(), limiter, 1, availability);
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request(),
        response,
        (req, res) -> {
          throw new AssertionError("Unconfirmed quota entered business logic");
        });
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentAsString()).contains("RETRY_WITH_SAME_BUSINESS_KEY");
    assertThat(response.getHeader("X-Retry-Reason")).isEqualTo("DATABASE_TRANSIENT");
  }

  @Test
  void quotaCapacityIsDistinguishedFromDatabaseFailure() throws Exception {
    var limiter = limiter();
    when(limiter.allow(anyString(), anyLong()))
        .thenThrow(
            new ApiRateLimiter.Unavailable(
                dev.merchantpay.platform.common.ApiException.RetryReason.QUOTA_CAPACITY, null));
    var filter =
        new SignatureFilter(
            merchant(false), jdbc(), clock, JsonMapper.builder().build(), limiter, 1, availability);
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request(),
        response,
        (req, res) -> {
          throw new AssertionError("Quota unavailable");
        });
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("X-Retry-Reason")).isEqualTo("QUOTA_CAPACITY");
    assertThat(
            metrics
                .get("merchantpay.api.unavailable")
                .tag("reason", "QUOTA_CAPACITY")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(metrics.get("merchantpay.api.stage").tag("stage", "QUOTA").timer().count())
        .isEqualTo(1);
  }
}
