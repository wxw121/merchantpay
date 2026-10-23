package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.common.ApiException.RetryReason;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.web.ApiAvailability.Stage;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

public class SignatureFilter extends OncePerRequestFilter {
  private final MerchantService merchants;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final JsonMapper json;
  private final ApiRateLimiter rateLimiter;
  private final ApiAvailability availability;
  private final java.util.concurrent.Semaphore admission;
  private final java.util.concurrent.Semaphore[] reserved;

  public SignatureFilter(
      MerchantService merchants,
      JdbcTemplate jdbc,
      Clock clock,
      JsonMapper json,
      ApiRateLimiter rateLimiter,
      int maximumInflight,
      ApiAvailability availability) {
    this.merchants = merchants;
    this.jdbc = jdbc;
    this.clock = clock;
    this.json = json;
    this.rateLimiter = rateLimiter;
    this.availability = availability;
    if (maximumInflight < 1 || maximumInflight > 128)
      throw new IllegalArgumentException("API inflight capacity must be 1..128");
    int unit = maximumInflight < 8 ? 0 : maximumInflight / 8;
    this.reserved =
        new java.util.concurrent.Semaphore[] {
          new java.util.concurrent.Semaphore(unit),
          new java.util.concurrent.Semaphore(unit),
          new java.util.concurrent.Semaphore(2 * unit)
        };
    this.admission = new java.util.concurrent.Semaphore(maximumInflight - 4 * unit);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var permit = admissionFor(request);
    if (!permit.tryAcquire()) {
      permit = admission;
      if (!permit.tryAcquire()) {
        unavailable(response, RetryReason.API_INFLIGHT);
        return;
      }
    }
    try {
      String keyId = request.getHeader("X-Key-Id"),
          timestamp = request.getHeader("X-Timestamp"),
          nonce = request.getHeader("X-Nonce"),
          signature = request.getHeader("X-Signature");
      if (keyId == null
          || timestamp == null
          || !timestamp.matches("[0-9]{10}")
          || nonce == null
          || !nonce.matches("[a-zA-Z0-9_-]{16,100}")) {
        throw new ApiException("INVALID_SIGNATURE", 401, "缺少签名或签名头格式错误");
      }
      long seconds = Long.parseLong(timestamp), now = clock.instant().getEpochSecond();
      if (seconds < now - 300 || seconds > now + 300) {
        throw new ApiException("SIGNATURE_EXPIRED", 401, "签名超过五分钟有效窗口");
      }
      byte[] bytes = request.getInputStream().readNBytes(65_537);
      if (bytes.length > 65_536) {
        throw new ApiException("BODY_TOO_LARGE", 413, "请求体超过 64 KiB");
      }
      String body = new String(bytes, StandardCharsets.UTF_8), path = request.getRequestURI();
      if (request.getQueryString() != null) {
        path += "?" + request.getQueryString();
      }
      var key = availability.time(Stage.AUTH, () -> merchants.key(keyId));
      if (!Signatures.verify(
          key.secret(), signature, timestamp, nonce, request.getMethod(), path, body)) {
        throw new ApiException("INVALID_SIGNATURE", 401, "签名校验失败");
      }
      try {
        availability.time(
            Stage.NONCE,
            () ->
                jdbc.update(
                    "INSERT INTO signature_nonces(key_id,nonce,expires_at) VALUES (?,?,?)",
                    keyId,
                    nonce,
                    Timestamp.from(clock.instant().plusSeconds(601))));
      } catch (DuplicateKeyException replay) {
        throw new ApiException("REPLAY_REJECTED", 409, "请求已使用，请使用新 nonce 和原业务号重试");
      }
      if (!availability.time(Stage.QUOTA, () -> rateLimiter.allow(keyId, now))) {
        throw new ApiException("RATE_LIMITED", 429, "商户接口超过每秒 500 次配额");
      }
      var caller = new Access.Caller("api:" + keyId, key.merchantId(), Set.of("MERCHANT"));
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          new UsernamePasswordAuthenticationToken(
              caller, null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))));
      SecurityContextHolder.setContext(context);
      var wrapped =
          new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
              var input = new ByteArrayInputStream(bytes);
              return new ServletInputStream() {
                public int read() {
                  return input.read();
                }

                public boolean isFinished() {
                  return input.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Synchronous signed request");
                }
              };
            }

            @Override
            public BufferedReader getReader() {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
          };
      long started = System.nanoTime();
      try {
        chain.doFilter(wrapped, response);
      } finally {
        availability.elapsed(Stage.HANDLER, started);
      }
    } catch (ApiException failure) {
      response.setStatus(failure.status());
      if (failure.status() == 503) {
        response.setHeader("Retry-After", "1");
        response.setHeader("X-Retry-Reason", availability.rejected(failure.retryReason()));
      }
      response.setContentType("application/json;charset=UTF-8");
      json.writeValue(
          response.getOutputStream(),
          Map.of("code", failure.code(), "message", failure.getMessage()));
    } catch (org.springframework.jdbc.CannotGetJdbcConnectionException
        | org.springframework.transaction.CannotCreateTransactionException
        | org.springframework.dao.TransientDataAccessException exhausted) {
      unavailable(response, availability.reason(exhausted));
    } finally {
      SecurityContextHolder.clearContext();
      permit.release();
    }
  }

  private java.util.concurrent.Semaphore admissionFor(HttpServletRequest request) {
    String method = request.getMethod(), path = request.getRequestURI();
    if (method.equals("POST") && path.equals("/api/v1/payments")) return reserved[0];
    if (method.equals("POST") && path.matches("/api/v1/payments/[^/]+/refunds")) return reserved[1];
    if (method.equals("GET")
        && (path.matches("/api/v1/payments/[^/]+") || path.matches("/api/v1/refunds/[^/]+")))
      return reserved[2];
    return admission;
  }

  private void unavailable(HttpServletResponse response, RetryReason reason) throws IOException {
    response.setStatus(503);
    response.setHeader("Retry-After", "1");
    response.setHeader("X-Retry-Reason", availability.rejected(reason));
    response.setContentType("application/json;charset=UTF-8");
    json.writeValue(
        response.getOutputStream(),
        Map.of("code", "RETRY_WITH_SAME_BUSINESS_KEY", "message", "系统繁忙，请以原业务号先查单或退避重试"));
  }
}
