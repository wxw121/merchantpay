package dev.merchantpay.platform.common;

@org.springframework.stereotype.Component
public class NotificationPolicy {
  private final String localWebhook;

  public NotificationPolicy(
      @org.springframework.beans.factory.annotation.Value("${merchantpay.local-webhook:}")
          String localWebhook) {
    this.localWebhook = localWebhook;
  }

  public boolean isLocalDemo(String value) {
    return !localWebhook.isBlank() && localWebhook.equals(value);
  }

  public String validate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (isLocalDemo(value)) {
      return value;
    }
    try {
      var uri = java.net.URI.create(value);
      if (value.length() > 1024
          || !"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || (uri.getPort() != -1 && uri.getPort() != 443)
          || value.chars().anyMatch(Character::isISOControl)
          || uri.getHost().contains(":")
          || uri.getHost().matches("[0-9.]+")
          || uri.getHost().equalsIgnoreCase("localhost")) {
        throw new IllegalArgumentException();
      }
      return value;
    } catch (IllegalArgumentException invalid) {
      throw new ApiException("INVALID_NOTIFICATION_URL", 400, "通知地址必须使用公网 HTTPS 地址");
    }
  }
}
