package dev.merchantpay.platform.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String incoming = request.getHeader("X-Request-Id");
    String requestId =
        incoming != null && incoming.matches("[a-zA-Z0-9_-]{1,64}")
            ? incoming
            : UUID.randomUUID().toString();
    String previous = MDC.get("requestId");
    MDC.put("requestId", requestId);
    response.setHeader("X-Request-Id", requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove("requestId");
      } else {
        MDC.put("requestId", previous);
      }
    }
  }
}
