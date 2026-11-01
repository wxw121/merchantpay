package dev.merchantpay.platform.outbox;

import dev.merchantpay.platform.common.Signatures;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable committed business facts for CDC; execution tasks are a separate recoverable concern.
 */
@Component
@DependsOnDatabaseInitialization
public class EventJournal implements InitializingBean {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final JsonMapper json;
  private final String configuredMode;

  public EventJournal(
      JdbcTemplate jdbc,
      Clock clock,
      JsonMapper json,
      @Value("${merchantpay.event-transport:POLLING}") String mode) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.json = json;
    this.configuredMode = Objects.requireNonNull(mode).toUpperCase(java.util.Locale.ROOT);
    if (!Set.of("POLLING", "CDC").contains(configuredMode)) {
      throw new IllegalArgumentException("Event transport must be POLLING or CDC");
    }
  }

  @Override
  public void afterPropertiesSet() {
    verifyTransport();
  }

  public void verifyTransport() {
    String actual =
        jdbc.queryForObject("SELECT mode FROM event_transport WHERE id=1", String.class);
    if (!configuredMode.equals(actual)) {
      throw new IllegalStateException(
          "Event transport differs from persisted mode; stop writers and use the transport switch procedure");
    }
  }

  public String mode() {
    return configuredMode;
  }

  public static boolean taskable(String kind) {
    return EventKinds.taskable(kind);
  }

  public void append(
      String id, String eventId, String kind, String aggregateId, String merchantId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Domain event must share its business transaction");
    }
    verifyTransport();
    jdbc.update(
        "INSERT INTO domain_event_versions(merchant_id,aggregate_id,version) VALUES (?,?,1) ON DUPLICATE KEY UPDATE version=version+1",
        merchantId,
        aggregateId);
    long version =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT version FROM domain_event_versions WHERE merchant_id=? AND aggregate_id=?",
                Long.class,
                merchantId,
                aggregateId));
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    String trace = Objects.toString(MDC.get("requestId"), "");
    if (trace.length() > 128) trace = trace.substring(0, 128);
    var event =
        new DomainEvent(1, eventId, kind, aggregateId, merchantId, version, now.toString(), trace);
    String payload = json.writeValueAsString(event);
    jdbc.update(
        "INSERT INTO domain_events(id,event_id,aggregatetype,aggregateid,merchant_id,event_type,aggregate_version,contract_version,occurred_at,delivery_mode,trace_id,payload,payload_hash) VALUES (?,?,'merchantpay',?,?,?,?,1,?,?,?,?,?)",
        id,
        eventId,
        aggregateId,
        merchantId,
        kind,
        version,
        Timestamp.from(now),
        configuredMode,
        trace,
        payload,
        Signatures.sha256(payload));
  }
}
