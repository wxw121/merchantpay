package dev.merchantpay.platform.outbox;

import dev.merchantpay.platform.common.TimeOrderedIds;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OutboxService {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final boolean orderedIds;
  private final EventJournal journal;

  public OutboxService(JdbcTemplate jdbc, Clock clock) {
    this(jdbc, clock, false);
  }

  public OutboxService(
      JdbcTemplate jdbc,
      Clock clock,
      @Value("${merchantpay.outbox-ordered-ids:false}") boolean orderedIds) {
    this(jdbc, clock, orderedIds, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public OutboxService(
      JdbcTemplate jdbc,
      Clock clock,
      @Value("${merchantpay.outbox-ordered-ids:false}") boolean orderedIds,
      EventJournal journal) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.orderedIds = orderedIds;
    this.journal = journal;
  }

  public void enqueue(String eventId, String kind, String aggregateId, String merchantId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Outbox creation must share the business transaction");
    }
    var instant = clock.instant();
    var now = Timestamp.from(instant);
    String id = orderedIds ? TimeOrderedIds.uuidV7(instant) : UUID.randomUUID().toString();
    jdbc.update("INSERT INTO outbox_events(id,event_id) VALUES (?,?)", id, eventId);
    if (journal != null) {
      journal.append(id, eventId, kind, aggregateId, merchantId);
      if (!journal.mode().equals("POLLING") || !EventJournal.taskable(kind)) return;
    }
    insertTask(jdbc, id, eventId, kind, aggregateId, merchantId, now);
  }

  /** State observations are replayable facts, never executable channel/notification tasks. */
  public void fact(String eventId, String kind, String aggregateId, String merchantId) {
    if (EventJournal.taskable(kind))
      throw new IllegalArgumentException("Fact-only event cannot create a task");
    if (journal != null) enqueue(eventId, kind, aggregateId, merchantId);
  }

  static void insertTask(
      JdbcTemplate jdbc,
      String id,
      String eventId,
      String kind,
      String aggregateId,
      String merchantId,
      Timestamp now) {
    insertTask(jdbc, id, eventId, kind, aggregateId, merchantId, now, now);
  }

  static void insertTask(
      JdbcTemplate jdbc,
      String id,
      String eventId,
      String kind,
      String aggregateId,
      String merchantId,
      Timestamp now,
      Timestamp retryStartedAt) {
    jdbc.update(
        "INSERT INTO outbox_tasks(id,event_id,kind,aggregate_id,merchant_id,payload,status,due_at,created_at,updated_at,retry_started_at,queue_class) VALUES (?,?,?,?,?,?,'PENDING',?,?,?,?,?)",
        id,
        eventId,
        kind,
        aggregateId,
        merchantId,
        "{\"aggregateId\":\"" + aggregateId + "\"}",
        now,
        now,
        now,
        retryStartedAt,
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
