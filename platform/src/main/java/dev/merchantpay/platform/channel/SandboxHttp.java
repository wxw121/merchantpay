package dev.merchantpay.platform.channel;

import com.alipay.api.FileItem;
import com.alipay.api.internal.util.AbstractHttpClient;
import dev.merchantpay.platform.common.BoundedHttpBody;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Official SDK still signs and verifies; this hook supplies bounded, non-redirecting HTTP only. */
final class SandboxHttp extends AbstractHttpClient implements AutoCloseable {
  private final URI gateway;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  SandboxHttp(URI gateway) {
    this.gateway = gateway;
  }

  @Override
  public String doPost(
      String url, Map<String, String> parameters, String charset, Map<String, String> headers)
      throws IOException {
    URI target = URI.create(url);
    if (!gateway.getScheme().equals(target.getScheme())
        || !gateway.getAuthority().equals(target.getAuthority())
        || !gateway.getPath().equals(target.getPath())
        || target.getFragment() != null
        || !"UTF-8".equalsIgnoreCase(charset))
      throw new IOException("Unexpected sandbox transport target");
    var form = new StringJoiner("&");
    parameters.forEach(
        (key, value) -> {
          if (value != null)
            form.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
    String encoded = form.toString();
    if (encoded.length() > 65536 || url.length() > 16384)
      throw new IOException("Sandbox request exceeded budget");
    var request =
        HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(encoded))
            .build();
    var future = http.sendAsync(request, info -> new BoundedHttpBody(65536));
    try {
      var response = future.get(8, TimeUnit.SECONDS);
      if (response.statusCode() / 100 != 2) throw new IOException("Sandbox gateway unconfirmed");
      response.headers().firstValue("trace_id").ifPresent(value -> headers.put("trace_id", value));
      return new String(response.body(), StandardCharsets.UTF_8);
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("Sandbox request interrupted");
    } catch (ExecutionException | TimeoutException failure) {
      future.cancel(true);
      throw new IOException("Sandbox request unconfirmed");
    }
  }

  @Override
  public String doPost(
      String url,
      Map<String, String> parameters,
      Map<String, FileItem> files,
      String charset,
      Map<String, String> headers)
      throws IOException {
    throw new IOException("Sandbox adapter does not upload files");
  }

  @Override
  public void close() {
    http.shutdownNow();
  }
}
