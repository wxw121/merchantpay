package dev.merchantpay.platform.outbox;

import org.springframework.jdbc.core.JdbcTemplate;

/** Archive-backed authority used only after a hot event was safely retired. */
public interface ColdEventResolver {
  record Verified(String payloadHash, int bucket) {}

  Verified verify(JdbcTemplate source, DomainEvent event, String payload);
}
