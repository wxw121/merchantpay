package dev.merchantpay.simulator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class DemoMerchantWebhookTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void repeatedDeliveryIncrementsTheCountAndPreservesTheOriginalBody() throws IOException {
    try (var source = new HikariDataSource()) {
      source.setJdbcUrl("jdbc:h2:mem:webhook_" + UUID.randomUUID() + ";MODE=MySQL");
      source.setMaximumPoolSize(1);
      new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
      var jdbc = new JdbcTemplate(source);
      var webhook = new DemoMerchantWebhook(jdbc, json);
      var eventId = "event-" + "x".repeat(154);
      var body = "{\"eventId\":\"" + eventId + "\",\"message\":\"支付完成\"}";
      var maxBody = body + " ".repeat(65_536 - body.getBytes(StandardCharsets.UTF_8).length);

      assertThat(webhook.receive(request(maxBody)))
          .isEqualTo(Map.of("status", "RECEIVED", "eventId", eventId));
      assertThat(webhook.receive(request(body)))
          .isEqualTo(Map.of("status", "RECEIVED", "eventId", eventId));
      assertThat(jdbc.queryForList("SELECT event_id, raw_body, deliveries FROM merchant_webhooks"))
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.get("event_id")).isEqualTo(eventId);
                assertThat(row.get("raw_body")).isEqualTo(maxBody);
                assertThat(row.get("deliveries")).isEqualTo(2);
              });
    }
  }

  @Test
  void rejectsAnOversizeBodyBeforeAttemptingToReadJson() {
    var jdbc = mock(JdbcTemplate.class);
    var webhook = new DemoMerchantWebhook(jdbc, json);

    assertThatExceptionOfType(ResponseStatusException.class)
        .isThrownBy(() -> webhook.receive(request("x".repeat(65_537))))
        .satisfies(error -> assertThat(error.getStatusCode().value()).isEqualTo(413));
    verifyNoInteractions(jdbc);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 161})
  void rejectsInvalidEventIdsBeforeInserting(int length) {
    var jdbc = mock(JdbcTemplate.class);
    var webhook = new DemoMerchantWebhook(jdbc, json);
    var eventId = length == 1 ? " " : "x".repeat(length);

    assertThatExceptionOfType(ResponseStatusException.class)
        .isThrownBy(() -> webhook.receive(request("{\"eventId\":\"" + eventId + "\"}")))
        .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    verifyNoInteractions(jdbc);
  }

  private static MockHttpServletRequest request(String body) {
    var request = new MockHttpServletRequest("POST", "/merchant-webhook");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }
}
