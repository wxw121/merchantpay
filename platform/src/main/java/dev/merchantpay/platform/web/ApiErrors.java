package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.ApiException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  private final ApiAvailability availability;

  public ApiErrors(ApiAvailability availability) {
    this.availability = availability;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> domain(ApiException failure) {
    var response = ResponseEntity.status(failure.status());
    if (failure.status() == 503)
      response
          .header("Retry-After", "1")
          .header("X-Retry-Reason", availability.rejected(failure.retryReason()));
    return response.body(Map.of("code", failure.code(), "message", failure.getMessage()));
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
  ResponseEntity<?> badRequest(Exception failure) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_REQUEST", "message", "请求格式无效，请检查必填字段和字段类型"));
  }

  @ExceptionHandler({
    org.springframework.dao.TransientDataAccessException.class,
    org.springframework.jdbc.CannotGetJdbcConnectionException.class,
    org.springframework.transaction.CannotCreateTransactionException.class
  })
  ResponseEntity<?> transientFailure(Exception failure) {
    return ResponseEntity.status(503)
        .header("Retry-After", "1")
        .header("X-Retry-Reason", availability.rejected(availability.reason(failure)))
        .body(Map.of("code", "RETRY_WITH_SAME_BUSINESS_KEY", "message", "数据库事务冲突，请使用相同业务号重试"));
  }
}
