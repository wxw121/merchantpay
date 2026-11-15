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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  void activeRequestsRemainVisibleUntilTheHandlerExitsEvenWhenTheCallerStopsWaiting()
      throws Exception {
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
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          executor.submit(
              () -> {
                filter.doFilter(
                    request(),
                    new MockHttpServletResponse(),
                    (req, res) -> {
                      entered.countDown();
                      try {
                        release.await();
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(interrupted);
                      }
                    });
                return null;
              });
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> pending.get(10, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        assertThat(metrics.find("merchantpay.api.active").gauge())
            .as("A client timeout does not end work still executing in the server")
            .isNotNull();
        assertThat(metrics.get("merchantpay.api.active").gauge().value()).isEqualTo(1);
        var rejected = new MockHttpServletResponse();
        filter.doFilter(
            request(), rejected, (req, res) -> fail("Capacity must reject this request"));
        assertThat(rejected.getStatus()).isEqualTo(503);
        assertThat(metrics.get("merchantpay.api.active").gauge().value()).isEqualTo(1);
      } finally {
        release.countDown();
      }
      pending.get(3, TimeUnit.SECONDS);
    }
    assertThat(metrics.get("merchantpay.api.active").gauge().value()).isZero();
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request(),
                    new MockHttpServletResponse(),
                    (req, res) -> {
                      throw new ServletException("probe failure");
                    }))
        .isInstanceOf(ServletException.class);
    assertThat(metrics.get("merchantpay.api.active").gauge().value()).isZero();
  }

  @ParameterizedTest
  @CsvSource({"1,1,0", "7,7,0", "13,11,1", "16,12,2", "128,96,16"})
  void writeBiasedPaymentFloodKeepsOtherEntryReservationsAndTheOriginalTotal(
      int maximum, int paymentEntries, int otherEntries) throws Exception {
    var jdbc = jdbc();
    var filter =
        new SignatureFilter(
            merchant(false),
            jdbc,
            clock,
            JsonMapper.builder().build(),
            limiter(),
            maximum,
            availability,
            true);
    var release = new CountDownLatch(1);
    var active = new AtomicInteger();
    var pending = new java.util.ArrayList<Future<?>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        for (int i = 0; i < paymentEntries; i++)
          holdEntry(filter, executor, pending, release, active, "POST", "/api/v1/payments");
        assertThat(active.get()).isEqualTo(paymentEntries);
        assertEntryRejected(filter, "POST", "/api/v1/payments");
        for (int i = 0; i < otherEntries; i++) {
          holdEntry(
              filter, executor, pending, release, active, "POST", "/api/v1/payments/p/refunds");
          holdEntry(filter, executor, pending, release, active, "GET", "/api/v1/payments/p");
        }
        assertThat(active.get()).isEqualTo(maximum);
        assertEntryRejected(filter, "POST", "/api/v1/payments");
        assertEntryRejected(filter, "POST", "/api/v1/payments/p/refunds");
        assertEntryRejected(filter, "GET", "/api/v1/payments/p");
        verify(jdbc, times(maximum)).update(anyString(), any(Object[].class));
      } finally {
        release.countDown();
      }
      for (var request : pending) request.get(5, TimeUnit.SECONDS);
    }
    assertThat(active.get()).isZero();
    for (var endpoint :
        java.util.List.of(
            new String[] {"POST", "/api/v1/payments"},
            new String[] {"POST", "/api/v1/payments/p/refunds"},
            new String[] {"GET", "/api/v1/payments/p"})) {
      var response = new MockHttpServletResponse();
      var passed = new AtomicInteger();
      filter.doFilter(
          request(endpoint[0], endpoint[1]), response, (req, res) -> passed.incrementAndGet());
      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(passed.get()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "1,1,0,POST,/api/v1/payments",
    "7,7,0,POST,/api/v1/payments",
    "13,11,1,POST,/api/v1/payments",
    "32,30,1,POST,/api/v1/payments",
    "128,120,4,POST,/api/v1/payments",
    "32,30,1,POST,/api/v1/payments/p/refunds",
    "32,30,1,GET,/api/v1/payments/p"
  })
  void borrowingUsesIdleEntriesAndLeavesOtherEntryFloors(
      int maximum, int floodEntries, int protectedEntries, String method, String path)
      throws Exception {
    var database = jdbc();
    var filter =
        new SignatureFilter(
            merchant(false),
            database,
            clock,
            JsonMapper.builder().build(),
            limiter(),
            maximum,
            availability,
            true,
            false,
            true);
    var release = new CountDownLatch(1);
    var active = new AtomicInteger();
    var pending = new java.util.ArrayList<Future<?>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        for (int i = 0; i < floodEntries; i++)
          holdEntry(filter, executor, pending, release, active, method, path);
        assertThat(active.get()).isEqualTo(floodEntries);
        assertEntryRejected(filter, method, path);
        for (var endpoint :
            java.util.List.of(
                new String[] {"POST", "/api/v1/payments"},
                new String[] {"POST", "/api/v1/payments/p/refunds"},
                new String[] {"GET", "/api/v1/payments/p"})) {
          if (endpoint[0].equals(method) && endpoint[1].equals(path)) continue;
          for (int i = 0; i < protectedEntries; i++)
            holdEntry(filter, executor, pending, release, active, endpoint[0], endpoint[1]);
        }
        assertThat(active.get()).isEqualTo(maximum);
        assertEntryRejected(filter, "POST", "/api/v1/payments");
        assertEntryRejected(filter, "POST", "/api/v1/payments/p/refunds");
        assertEntryRejected(filter, "GET", "/api/v1/payments/p");
        verify(database, times(maximum)).update(anyString(), any(Object[].class));
      } finally {
        release.countDown();
      }
      for (var future : pending) future.get(5, TimeUnit.SECONDS);
    }
    assertThat(metrics.get("merchantpay.api.active").gauge().value()).isZero();
    filter.doFilter(request(method, path), new MockHttpServletResponse(), (req, res) -> {});
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aBorrowedEntryReturnsToItsActualPoolAfterHandlerFailure(boolean coalesced) throws Exception {
    var quota = limiter();
    var database = jdbc();
    when(quota.admit(anyString(), anyLong(), anyString(), any(Instant.class)))
        .thenReturn(ApiRateLimiter.Decision.ALLOWED);
    var filter =
        new SignatureFilter(
            merchant(false),
            database,
            clock,
            JsonMapper.builder().build(),
            quota,
            32,
            availability,
            true,
            coalesced,
            true);
    var release = new CountDownLatch(1);
    var active = new AtomicInteger();
    var pending = new java.util.ArrayList<Future<?>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        for (int i = 0; i < 24; i++)
          holdEntry(filter, executor, pending, release, active, "POST", "/api/v1/payments");
        for (int i = 0; i < 12; i++)
          assertThatThrownBy(
                  () ->
                      filter.doFilter(
                          request(),
                          new MockHttpServletResponse(),
                          (req, res) -> {
                            throw new ServletException("handler failed");
                          }))
              .isInstanceOf(ServletException.class);
        assertThat(metrics.get("merchantpay.api.admission.borrowed").counter().count())
            .isEqualTo(12);
        for (int i = 0; i < 12; i++) {
          var invalid = request();
          invalid.removeHeader("X-Signature");
          invalid.addHeader("X-Signature", "0".repeat(64));
          var rejected = new MockHttpServletResponse();
          filter.doFilter(
              invalid, rejected, (req, res) -> fail("Invalid signature reached handler"));
          assertThat(rejected.getStatus()).isEqualTo(401);
        }
        assertThat(metrics.get("merchantpay.api.admission.borrowed").counter().count())
            .isEqualTo(24);
        when(quota.allow(anyString(), anyLong())).thenReturn(false);
        when(quota.admit(anyString(), anyLong(), anyString(), any(Instant.class)))
            .thenReturn(ApiRateLimiter.Decision.RATE_LIMITED);
        var limited = new MockHttpServletResponse();
        filter.doFilter(request(), limited, (req, res) -> fail("Quota rejection reached handler"));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(metrics.get("merchantpay.api.admission.borrowed").counter().count())
            .isEqualTo(25);
        when(quota.allow(anyString(), anyLong())).thenReturn(true);
        when(quota.admit(anyString(), anyLong(), anyString(), any(Instant.class)))
            .thenReturn(ApiRateLimiter.Decision.ALLOWED);
        when(quota.admit(anyString(), anyLong(), anyString(), any(Instant.class)))
            .thenReturn(ApiRateLimiter.Decision.REPLAY);
        if (!coalesced)
          when(database.update(anyString(), any(Object[].class)))
              .thenThrow(new org.springframework.dao.DuplicateKeyException("replay"));
        var replay = new MockHttpServletResponse();
        filter.doFilter(request(), replay, (req, res) -> fail("Replay reached handler"));
        assertThat(replay.getStatus()).isEqualTo(409);
        assertThat(metrics.get("merchantpay.api.admission.borrowed").counter().count())
            .isEqualTo(26);
        doReturn(1).when(database).update(anyString(), any(Object[].class));
        when(quota.admit(anyString(), anyLong(), anyString(), any(Instant.class)))
            .thenReturn(ApiRateLimiter.Decision.ALLOWED);
        for (int i = 0; i < 6; i++)
          holdEntry(filter, executor, pending, release, active, "POST", "/api/v1/payments");
        assertThat(active.get()).isEqualTo(30);
        assertEntryRejected(filter, "POST", "/api/v1/payments");
      } finally {
        release.countDown();
      }
      for (var future : pending) future.get(5, TimeUnit.SECONDS);
    }
    assertThat(metrics.get("merchantpay.api.active").gauge().value()).isZero();
  }

  @Test
  void concurrentBorrowersCannotSpendOtherEntryFloorsOrExceedTotal() throws Exception {
    var filter =
        new SignatureFilter(
            merchant(false),
            jdbc(),
            clock,
            JsonMapper.builder().build(),
            limiter(),
            128,
            availability,
            true,
            false,
            true);
    var release = new CountDownLatch(1);
    var considered = new CountDownLatch(128);
    var active = new AtomicInteger();
    var pending = new java.util.ArrayList<Future<?>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        for (int i = 0; i < 96; i++)
          holdEntry(filter, executor, pending, release, active, "POST", "/api/v1/payments");
        for (int i = 0; i < 128; i++)
          pending.add(
              executor.submit(
                  () -> {
                    var entered = new java.util.concurrent.atomic.AtomicBoolean();
                    var response = new MockHttpServletResponse();
                    filter.doFilter(
                        request(),
                        response,
                        (req, res) -> {
                          entered.set(true);
                          active.incrementAndGet();
                          considered.countDown();
                          try {
                            if (!release.await(15, TimeUnit.SECONDS))
                              throw new ServletException("Test not released");
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new ServletException(e);
                          } finally {
                            active.decrementAndGet();
                          }
                        });
                    if (!entered.get()) {
                      assertThat(response.getStatus()).isEqualTo(503);
                      considered.countDown();
                    }
                    return null;
                  }));
        assertThat(considered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(active.get()).isEqualTo(120);
        for (int i = 0; i < 4; i++) {
          holdEntry(
              filter, executor, pending, release, active, "POST", "/api/v1/payments/p/refunds");
          holdEntry(filter, executor, pending, release, active, "GET", "/api/v1/payments/p");
        }
        assertThat(active.get()).isEqualTo(128);
        assertThat(metrics.get("merchantpay.api.admission.borrowed").counter().count())
            .isEqualTo(24);
        assertEntryRejected(filter, "POST", "/api/v1/payments");
      } finally {
        release.countDown();
      }
      for (var future : pending) future.get(5, TimeUnit.SECONDS);
    }
    assertThat(metrics.get("merchantpay.api.active").gauge().value()).isZero();
  }

  private void holdEntry(
      SignatureFilter filter,
      ExecutorService executor,
      java.util.List<Future<?>> pending,
      CountDownLatch release,
      AtomicInteger active,
      String method,
      String path)
      throws Exception {
    var entered = new CompletableFuture<Boolean>();
    pending.add(
        executor.submit(
            () -> {
              try {
                filter.doFilter(
                    request(method, path),
                    new MockHttpServletResponse(),
                    (req, res) -> {
                      active.incrementAndGet();
                      entered.complete(true);
                      try {
                        if (!release.await(15, TimeUnit.SECONDS))
                          throw new ServletException("Test entry was not released");
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(failure);
                      } finally {
                        active.decrementAndGet();
                      }
                    });
                entered.complete(false);
              } catch (Exception failure) {
                entered.completeExceptionally(failure);
                throw new RuntimeException(failure);
              }
            }));
    assertThat(entered.get(3, TimeUnit.SECONDS)).as("Entry for %s %s", method, path).isTrue();
  }

  private void assertEntryRejected(SignatureFilter filter, String method, String path)
      throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request(method, path),
        response,
        (req, res) -> {
          throw new AssertionError("Saturated entry reached business logic");
        });
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("X-Retry-Reason")).isEqualTo("API_INFLIGHT");
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
