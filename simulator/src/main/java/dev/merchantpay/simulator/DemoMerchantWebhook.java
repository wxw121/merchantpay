package dev.merchantpay.simulator;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local demonstration receiver. The production merchant verifies HMAC and deduplicates by eventId.
 */
@RestController
class DemoMerchantWebhook {
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  DemoMerchantWebhook(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @PostMapping("/merchant-webhook")
  Map<String, String> receive(HttpServletRequest request) throws IOException {
    byte[] bytes = request.getInputStream().readNBytes(65_537);
    if (bytes.length > 65_536) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    }
    String raw = new String(bytes, StandardCharsets.UTF_8),
        event = json.readTree(raw).path("eventId").asString();
    if (event.isBlank() || event.length() > 160) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    jdbc.update(
        "INSERT INTO merchant_webhooks(event_id,raw_body) VALUES (?,?) ON DUPLICATE KEY UPDATE deliveries=deliveries+1",
        event,
        raw);
    return Map.of("status", "RECEIVED", "eventId", event);
  }
}
