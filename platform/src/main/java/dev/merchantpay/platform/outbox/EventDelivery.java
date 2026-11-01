package dev.merchantpay.platform.outbox;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.Signatures;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Kafka positions are acknowledged by the adapter only after this local transaction commits. */
@Service
public class EventDelivery {
  public static final String CONSUMER = "merchantpay-task-router-v2";

  public static final class InvalidEvent extends RuntimeException {
    public InvalidEvent(String message) {
      super(message);
    }
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final JsonMapper json;

  public EventDelivery(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, JsonMapper json) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(tx.getTransactionManager());
    this.tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.tx.setTimeout(5);
    this.clock = clock;
    this.json = json;
  }

  public void deliver(String topic, int partition, long offset, String payload) {
    tx.executeWithoutResult(t -> deliverCommitted(topic, partition, offset, payload));
  }

  /**
   * Pure validation for independent consumers; it cannot schedule business tasks or record receipt.
   */
  public DomainEvent verifyStored(String payload) {
    var event = decode(payload);
    var originals =
        jdbc.queryForList(
            "SELECT payload FROM domain_events WHERE event_id=?", String.class, event.eventId());
    if (originals.size() != 1 || !event.equals(decode(originals.getFirst())))
      throw new InvalidEvent("Event differs from authoritative business fact");
    return event;
  }

  private void deliverCommitted(String topic, int partition, long offset, String payload) {
    var event = decode(payload);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM domain_events WHERE event_id=? FOR UPDATE", event.eventId());
    if (rows.isEmpty()) throw new InvalidEvent("Unknown authoritative event");
    var row = rows.getFirst();
    var stored = decode((String) row.get("payload"));
    if (!event.equals(stored))
      throw new InvalidEvent("Event identity or content differs from stored business fact");
    String hash = (String) row.get("payload_hash");
    var receipts =
        jdbc.queryForList(
            "SELECT payload_hash FROM event_receipts WHERE consumer_name=? AND event_id=?",
            CONSUMER,
            event.eventId());
    if (!receipts.isEmpty()) {
      if (!hash.equals(receipts.getFirst().get("payload_hash")))
        throw new InvalidEvent("Receipt content changed");
      return;
    }
    if ("CDC".equals(row.get("delivery_mode")) && EventJournal.taskable(stored.eventType())) {
      boolean exists =
          Objects.requireNonNull(
                  jdbc.queryForObject(
                      "SELECT COUNT(*) FROM outbox_task_records WHERE event_id=?",
                      Long.class,
                      event.eventId()))
              > 0;
      if (!exists)
        OutboxService.insertTask(
            jdbc,
            (String) row.get("id"),
            event.eventId(),
            stored.eventType(),
            stored.aggregateId(),
            stored.merchantId(),
            Timestamp.from(clock.instant()),
            Timestamp.from(Instant.parse(stored.occurredAt())));
    }
    jdbc.update(
        "INSERT INTO event_receipts(consumer_name,event_id,payload_hash,topic,partition_no,record_offset,received_at) VALUES (?,?,?,?,?,?,?)",
        CONSUMER,
        event.eventId(),
        hash,
        topic,
        partition,
        offset,
        Timestamp.from(clock.instant()));
  }

  private DomainEvent decode(String payload) {
    if (payload == null || payload.length() > 16384)
      throw new InvalidEvent("Event payload size is invalid");
    try {
      var node = json.readTree(payload);
      var expected =
          Set.of(
              "schemaVersion",
              "eventId",
              "eventType",
              "aggregateId",
              "merchantId",
              "aggregateVersion",
              "occurredAt",
              "traceId");
      var names = new HashSet<String>();
      node.propertyNames().forEach(names::add);
      if (!names.equals(expected)) throw new InvalidEvent("Event contract fields are invalid");
      for (String key : expected) {
        var value = node.get(key);
        if (key.equals("schemaVersion") || key.equals("aggregateVersion")) {
          if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw new InvalidEvent("Event versions must be integral JSON numbers");
        } else if (!value.isString())
          throw new InvalidEvent("Event contract text fields must be JSON strings");
      }
      if (node.get("schemaVersion").asLong() != 1)
        throw new InvalidEvent("Unsupported event contract version");
      DomainEvent event = json.readValue(payload, DomainEvent.class);
      if (event.schemaVersion() != 1
          || event.aggregateVersion() < 1
          || !valid(event.eventId(), 160)
          || !valid(event.aggregateId(), 64)
          || !valid(event.merchantId(), 64)
          || !valid(event.eventType(), 64)
          || event.traceId() == null
          || event.traceId().length() > 128)
        throw new InvalidEvent("Unsupported or invalid event contract");
      Instant.parse(event.occurredAt());
      return event;
    } catch (InvalidEvent invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new InvalidEvent("Malformed event contract");
    }
  }

  private static boolean valid(String text, int maximum) {
    return text != null && !text.isBlank() && text.length() <= maximum;
  }

  public String quarantine(
      String topic, int partition, long offset, String payload, String reason) {
    if (!valid(topic, 160) || partition < 0 || offset < 0)
      throw new IllegalArgumentException("Kafka position required");
    String raw = Objects.toString(payload, "");
    String rawHash = Signatures.sha256(raw);
    String retained = raw.length() > 16000 ? raw.substring(0, 16000) : raw;
    String detail = Objects.toString(reason, "Event delivery failed");
    if (detail.length() > 1024) detail = detail.substring(0, 1024);
    String finalDetail = detail;
    return tx.execute(
        t -> {
          var now = Timestamp.from(clock.instant());
          String id = UUID.randomUUID().toString();
          jdbc.update(
              "INSERT INTO event_quarantine(id,topic,partition_no,record_offset,payload,payload_hash,reason,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'QUARANTINED',?,?) ON DUPLICATE KEY UPDATE id=id",
              id,
              topic,
              partition,
              offset,
              retained,
              rawHash,
              finalDetail,
              now,
              now);
          var row =
              jdbc.queryForMap(
                  "SELECT id,payload_hash FROM event_quarantine WHERE topic=? AND partition_no=? AND record_offset=? FOR UPDATE",
                  topic,
                  partition,
                  offset);
          if (!rawHash.equals(row.get("payload_hash")))
            throw new InvalidEvent("Kafka position content changed");
          return (String) row.get("id");
        });
  }

  public Map<String, Object> replay(String id, Actor actor) {
    requireOperator(actor);
    return tx.execute(
        t -> {
          var rows = jdbc.queryForList("SELECT * FROM event_quarantine WHERE id=? FOR UPDATE", id);
          if (rows.isEmpty()) throw new ApiException("NOT_FOUND", 404, "隔离事件不存在");
          var row = rows.getFirst();
          if ("REPLAYED".equals(row.get("status"))) return Map.of("id", id, "status", "REPLAYED");
          String status = "REPLAYED";
          String reason = Objects.toString(row.get("reason"), "");
          try {
            String payload = (String) row.get("payload");
            if (!Signatures.sha256(payload).equals(row.get("payload_hash")))
              throw new InvalidEvent(
                  "Oversized original requires external inspection; truncated evidence cannot be replayed");
            deliverCommitted(
                (String) row.get("topic"),
                ((Number) row.get("partition_no")).intValue(),
                ((Number) row.get("record_offset")).longValue(),
                payload);
          } catch (InvalidEvent invalid) {
            status = "FAILED";
            reason = invalid.getMessage();
          }
          jdbc.update(
              "UPDATE event_quarantine SET status=?,reason=?,attempts=attempts+1,replayed_by=?,updated_at=? WHERE id=?",
              status,
              reason,
              actor.id(),
              Timestamp.from(clock.instant()),
              id);
          jdbc.update(
              "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,NULL,?,'EVENT_REPLAY',?,?,?)",
              UUID.randomUUID().toString(),
              actor.id(),
              id,
              status,
              Timestamp.from(clock.instant()));
          return Map.of("id", id, "status", status);
        });
  }

  public static void requireOperator(Actor actor) {
    actor.require("OPS", "ADMIN");
    if (actor.merchantId() != null && !actor.merchantId().isBlank())
      throw new ApiException("FORBIDDEN", 403, "事件隔离需平台范围的运维权限");
  }
}
