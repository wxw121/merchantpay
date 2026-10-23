package dev.merchantpay.platform.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OutboxService {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public OutboxService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void enqueue(String eventId, String kind, String aggregateId, String merchantId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Outbox creation must share the business transaction");
    }
    var now = Timestamp.from(clock.instant());
    jdbc.update(
        "INSERT INTO outbox_tasks(id,event_id,kind,aggregate_id,merchant_id,payload,status,due_at,created_at,updated_at,queue_class) VALUES (?,?,?,?,?,?,'PENDING',?,?,?,?)",
        UUID.randomUUID().toString(),
        eventId,
        kind,
        aggregateId,
        merchantId,
        "{\"aggregateId\":\"" + aggregateId + "\"}",
        now,
        now,
        now,
        switch (kind) {
          case "RECONCILE", "EXPORT" -> "BULK";
          case "PROCESS_INBOX" -> "INBOX";
          case "PAYMENT_SUCCEEDED",
              "PAYMENT_FAILED",
              "PAYMENT_CLOSED",
              "REFUND_SUCCEEDED",
              "REFUND_FAILED" ->
              "NOTIFY";
          default -> "FAST";
        });
  }
}
