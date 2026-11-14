package dev.merchantpay.platform.projection;

import dev.merchantpay.platform.common.ApiException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded HTTP transport; never connected during startup and never part of the money transaction.
 */
@Component
public class ClickHouseReadModel {
  private final URI endpoint;
  private final String user, password;
  private final HttpClient http;
  private final JsonMapper json;
  private final boolean enabled;

  public ClickHouseReadModel(
      JsonMapper json,
      @Value("${merchantpay.read-model-enabled:false}") boolean enabled,
      @Value("${merchantpay.clickhouse-url:http://127.0.0.1:18123/}") String url,
      @Value("${merchantpay.clickhouse-user:merchantpay}") String user,
      @Value("${merchantpay.clickhouse-password:}") String password) {
    this.json = json;
    this.enabled = enabled;
    this.user = user;
    this.password = password;
    endpoint = URI.create(url);
    if (!Set.of("http", "https").contains(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null)
      throw new IllegalArgumentException("Explicit ClickHouse HTTP endpoint required");
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public boolean enabled() {
    return enabled;
  }

  public List<Map<String, Object>> query(String sql, Map<String, String> parameters) {
    String raw = request(sql + " FORMAT JSONEachRow", parameters);
    var rows = new ArrayList<Map<String, Object>>();
    for (String line : raw.split("\\R")) {
      if (line.isBlank()) continue;
      if (rows.size() >= 1000) throw unavailable();
      @SuppressWarnings("unchecked")
      var row = (Map<String, Object>) json.readValue(line, Map.class);
      rows.add(row);
    }
    return rows;
  }

  public void write(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return;
    String prefix = "INSERT INTO merchantpay.payment_projection FORMAT JSONEachRow\n";
    var payload = new StringBuilder(prefix);
    int size = prefix.getBytes(StandardCharsets.UTF_8).length;
    for (var row : rows) {
      String line = json.writeValueAsString(row) + "\n";
      int bytes = line.getBytes(StandardCharsets.UTF_8).length;
      if (bytes + prefix.length() > 1048576)
        throw new IllegalArgumentException("Projection row too large");
      if (size + bytes > 1048576) {
        request(payload.toString(), Map.of());
        payload.setLength(0);
        payload.append(prefix);
        size = prefix.length();
      }
      payload.append(line);
      size += bytes;
    }
    request(payload.toString(), Map.of());
  }

  private String request(String sql, Map<String, String> parameters) {
    if (!enabled) throw new ApiException("READ_MODEL_DISABLED", 503, "独立查询投影未启用");
    StringBuilder query = new StringBuilder("?wait_end_of_query=1&max_execution_time=5");
    parameters.forEach(
        (k, v) -> {
          if (!k.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid query parameter");
          query
              .append("&param_")
              .append(k)
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
    if (sql.getBytes(StandardCharsets.UTF_8).length > 1048576)
      throw new IllegalArgumentException("Projection request too large");
    var request =
        HttpRequest.newBuilder(URI.create(endpoint.toString() + query))
            .timeout(Duration.ofSeconds(8))
            .header("X-ClickHouse-User", user)
            .header("X-ClickHouse-Key", password)
            .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
            .build();
    var future =
        http.sendAsync(
            request, info -> new dev.merchantpay.platform.common.BoundedHttpBody(1048576));
    try {
      var response = future.get(8, java.util.concurrent.TimeUnit.SECONDS);
      if (response.statusCode() / 100 != 2) throw unavailable();
      return new String(response.body(), StandardCharsets.UTF_8);
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      future.cancel(true);
      throw unavailable();
    }
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    http.shutdownNow();
  }

  static ApiException unavailable() {
    return new ApiException("READ_MODEL_UNAVAILABLE", 503, "查询投影不可用，请稍后重试或查看权威支付记录");
  }
}
