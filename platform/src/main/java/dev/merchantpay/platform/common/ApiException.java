package dev.merchantpay.platform.common;

public final class ApiException extends RuntimeException {
  public enum RetryReason {
    API_INFLIGHT,
    QUOTA_CAPACITY,
    QUOTA_TIMEOUT,
    QUOTA_INTERRUPTED,
    QUOTA_UNAVAILABLE,
    QUOTA_DATABASE,
    PAYMENT_CAPACITY,
    DATABASE_UNAVAILABLE,
    DATABASE_TRANSIENT,
    UNCLASSIFIED
  }

  private final String code;
  private final int status;
  private final RetryReason retryReason;

  public ApiException(String code, int status, String message) {
    this(code, status, message, RetryReason.UNCLASSIFIED);
  }

  public ApiException(String code, int status, String message, RetryReason retryReason) {
    super(message);
    this.code = code;
    this.status = status;
    this.retryReason = java.util.Objects.requireNonNull(retryReason);
  }

  public RetryReason retryReason() {
    return retryReason;
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }
}
