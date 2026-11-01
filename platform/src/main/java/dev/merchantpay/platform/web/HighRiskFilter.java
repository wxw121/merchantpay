package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

public class HighRiskFilter extends OncePerRequestFilter {
  private final ConsoleSecurity security;
  private final JsonMapper json;

  public HighRiskFilter(ConsoleSecurity security, JsonMapper json) {
    this.security = security;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var path = ApiPaths.path(request);
    if (!ApiPaths.CONSOLE.matches(path)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      security.checkRevocation(request);
      if (!security.protectedAction(request.getMethod(), path)) {
        chain.doFilter(request, response);
        return;
      }
      // Uploads carry streaming evidence, not a money action. Their separate execution is
      // protected.
      if ("POST".equals(request.getMethod())
          && ApiPaths.UPLOAD.matches(path)
          && request.getContentType() != null
          && request.getContentType().startsWith("multipart/form-data")) {
        security.requireMfa();
        chain.doFilter(request, response);
        return;
      }
      byte[] bytes = request.getInputStream().readNBytes(65537);
      if (bytes.length > 65536) throw new ApiException("PAYLOAD_TOO_LARGE", 413, "操作内容超过64KiB");
      String target =
          request.getRequestURI()
              + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
      security.consume(
          request.getHeader("X-Action-Confirmation"),
          request.getMethod(),
          target,
          new String(bytes, StandardCharsets.UTF_8),
          request);
      chain.doFilter(
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
                  throw new UnsupportedOperationException();
                }
              };
            }

            @Override
            public BufferedReader getReader() {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
          },
          response);
    } catch (ApiException rejected) {
      response.setStatus(rejected.status());
      response.setContentType("application/json;charset=UTF-8");
      response
          .getWriter()
          .write(
              json.writeValueAsString(
                  Map.of("code", rejected.code(), "message", rejected.getMessage())));
    }
  }
}
