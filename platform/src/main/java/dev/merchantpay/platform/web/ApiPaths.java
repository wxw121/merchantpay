package dev.merchantpay.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** MVC segment matching for access decisions; signatures still bind the original encoded URL. */
final class ApiPaths {
  static final PathPattern API = PathPatternParser.defaultInstance.parse("/api/**");
  static final PathPattern MERCHANT = PathPatternParser.defaultInstance.parse("/api/v1/**");
  static final PathPattern CONSOLE = PathPatternParser.defaultInstance.parse("/api/console/**");
  static final PathPattern CONFIRMATIONS =
      PathPatternParser.defaultInstance.parse("/api/console/security/confirmations");
  static final PathPattern UPLOAD =
      PathPatternParser.defaultInstance.parse("/api/console/reconciliation/upload");
  static final PathPattern PAYMENTS = PathPatternParser.defaultInstance.parse("/api/v1/payments");
  static final PathPattern PAYMENT =
      PathPatternParser.defaultInstance.parse("/api/v1/payments/{id}");
  static final PathPattern REFUNDS =
      PathPatternParser.defaultInstance.parse("/api/v1/payments/{id}/refunds");
  static final PathPattern REFUND = PathPatternParser.defaultInstance.parse("/api/v1/refunds/{id}");
  private static final PathPattern CALLBACK =
      PathPatternParser.defaultInstance.parse("/api/channel/callback");
  private static final PathPattern ALIPAY_CALLBACK =
      PathPatternParser.defaultInstance.parse("/api/channel/alipay/callback");

  private ApiPaths() {}

  static PathContainer path(HttpServletRequest request) {
    try {
      return (ServletRequestPathUtils.hasParsedRequestPath(request)
              ? ServletRequestPathUtils.getParsedRequestPath(request)
              : ServletRequestPathUtils.parseAndCache(request))
          .pathWithinApplication();
    } catch (IllegalArgumentException invalid) {
      throw new RequestRejectedException("Invalid request path");
    }
  }

  static PathContainer target(String target, HttpServletRequest request) {
    int query = target.indexOf('?');
    return RequestPath.parse(
            query < 0 ? target : target.substring(0, query), request.getContextPath())
        .pathWithinApplication();
  }

  static boolean callback(PathContainer path) {
    return CALLBACK.matches(path) || ALIPAY_CALLBACK.matches(path);
  }
}
