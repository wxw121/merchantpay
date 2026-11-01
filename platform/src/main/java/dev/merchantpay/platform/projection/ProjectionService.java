package dev.merchantpay.platform.projection;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.query.Queries;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProjectionService {
  public static final String GROUP = "merchantpay-read-model-v2";
  static final String COLUMNS =
      "id,merchant_id,merchant_order_no,amount_minor,fee_minor,currency,status,refunded_minor,reserved_minor,refunded_fee_minor,channel,channel_reference,notification_url,created_at,updated_at";
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final JsonMapper json;
  private final EventDelivery events;
  private final ClickHouseReadModel clickhouse;

  public ProjectionService(
      JdbcTemplate jdbc,
      TransactionTemplate template,
      Clock clock,
      JsonMapper json,
      EventDelivery events,
      ClickHouseReadModel clickhouse) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.json = json;
    this.events = events;
    this.clickhouse = clickhouse;
    tx = new TransactionTemplate(template.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  /**
   * Historical polling facts are absent from the CDC topic; permanent anti-join avoids commit-order
   * assumptions.
   */
  public void recoverPollingFacts() {
    for (var row :
        jdbc.queryForList(
            "SELECT sequence_no,payload FROM domain_events d WHERE delivery_mode='POLLING' AND NOT EXISTS(SELECT 1 FROM projection_receipts r WHERE r.event_id=d.event_id) ORDER BY sequence_no LIMIT 32"))
      consume(
          "mysql-polling-facts",
          0,
          ((Number) row.get("sequence_no")).longValue(),
          row.get("payload").toString());
  }

  /**
   * Explicit recovery when a new projection consumer starts after Kafka retention. No task routing.
   */
  public int recoverSourceFacts(String reason, Actor actor) {
    operator(actor);
    requireReason(reason);
    return tx.execute(
        t -> {
          var rows =
              jdbc.queryForList(
                  "SELECT sequence_no,payload FROM domain_events d WHERE NOT EXISTS(SELECT 1 FROM projection_receipts r WHERE r.event_id=d.event_id) ORDER BY sequence_no LIMIT 32");
          for (var row : rows)
            consume(
                "mysql-authoritative-recovery",
                0,
                ((Number) row.get("sequence_no")).longValue(),
                row.get("payload").toString());
          audit(actor, "PROJECTION_SOURCE_RECOVER", "1", reason + "; count=" + rows.size());
          return rows.size();
        });
  }

  public void consume(String topic, int partition, long offset, String payload) {
    var event = events.verifyStored(payload);
    tx.executeWithoutResult(
        t -> {
          jdbc.queryForList(
              "SELECT id FROM domain_events WHERE event_id=? FOR UPDATE", event.eventId());
          String hash = Signatures.sha256(payload);
          var previous =
              jdbc.queryForList(
                  "SELECT payload_hash FROM projection_receipts WHERE event_id=?",
                  String.class,
                  event.eventId());
          if (!previous.isEmpty()) return;
          String root = root(event);
          if (root != null)
            for (String generation :
                jdbc.queryForList(
                    "SELECT id FROM projection_generations WHERE state IN ('BUILDING','SERVING') ORDER BY id",
                    String.class)) capture(generation, root);
          jdbc.update(
              "INSERT INTO projection_receipts(event_id,payload_hash,topic,partition_no,record_offset,created_at) VALUES (?,?,?,?,?,?)",
              event.eventId(),
              hash,
              topic,
              partition,
              offset,
              now());
        });
  }

  private String root(DomainEvent event) {
    var payment =
        jdbc.queryForList(
            "SELECT id FROM payments WHERE id=? AND merchant_id=?",
            String.class,
            event.aggregateId(),
            event.merchantId());
    if (!payment.isEmpty()) return payment.getFirst();
    var refund =
        jdbc.queryForList(
            "SELECT payment_id FROM refunds WHERE id=? AND merchant_id=?",
            String.class,
            event.aggregateId(),
            event.merchantId());
    if (!refund.isEmpty()) return refund.getFirst();
    var adjustment =
        jdbc.queryForList(
            "SELECT payment_id FROM finance_adjustments WHERE id=? AND merchant_id=?",
            String.class,
            event.aggregateId(),
            event.merchantId());
    return adjustment.isEmpty() ? null : adjustment.getFirst();
  }

  private void capture(String generation, String paymentId) {
    var rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM payments WHERE id=? FOR UPDATE", Queries.ROW, paymentId);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    jdbc.update(
        "INSERT INTO projection_counters(generation,payment_id,revision) VALUES (?,?,1) ON DUPLICATE KEY UPDATE revision=revision+1",
        generation,
        paymentId);
    long revision =
        jdbc.queryForObject(
            "SELECT revision FROM projection_counters WHERE generation=? AND payment_id=?",
            Long.class,
            generation,
            paymentId);
    String snapshot = json.writeValueAsString(row);
    jdbc.update(
        "INSERT INTO projection_batches(id,generation,payment_id,merchant_id,revision,snapshot,snapshot_hash,state,due_at,created_at) VALUES (?,?,?,?,?,?,?,'PENDING',?,?)",
        UUID.randomUUID().toString(),
        generation,
        paymentId,
        row.get("merchantId"),
        revision,
        snapshot,
        Signatures.sha256(snapshot),
        now(),
        now());
  }

  public void scanNext() {
    tx.executeWithoutResult(
        t -> {
          var generations =
              jdbc.queryForList(
                  "SELECT id,scan_cursor FROM projection_generations WHERE state IN ('BUILDING','SERVING') AND scan_complete=false ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
          if (generations.isEmpty()) return;
          var generation = generations.getFirst();
          String id = generation.get("id").toString();
          var paymentIds =
              jdbc.queryForList(
                  "SELECT id FROM payments WHERE id>? ORDER BY id LIMIT 50",
                  String.class,
                  generation.get("scan_cursor"));
          for (String payment : paymentIds) capture(id, payment);
          if (paymentIds.isEmpty())
            jdbc.update("UPDATE projection_generations SET scan_complete=true WHERE id=?", id);
          else
            jdbc.update(
                "UPDATE projection_generations SET scan_cursor=? WHERE id=?",
                paymentIds.getLast(),
                id);
        });
  }

  public record Delivery(
      String id,
      String token,
      String generation,
      String paymentId,
      String merchantId,
      long revision,
      String snapshot,
      String hash) {}

  public List<Delivery> claim() {
    return claim(20);
  }

  public List<Delivery> claim(int limit) {
    if (limit < 1 || limit > 256)
      throw new IllegalArgumentException("Projection claim must contain 1..256 rows");
    return tx.execute(
        t -> {
          var rows =
              jdbc.queryForList(
                  "SELECT b.* FROM projection_batches b JOIN projection_generations g ON g.id=b.generation WHERE g.state IN ('BUILDING','SERVING') AND ((b.state='PENDING' AND b.due_at<=?) OR (b.state='LEASED' AND b.lease_until<=?)) ORDER BY b.due_at,b.id LIMIT ? FOR UPDATE SKIP LOCKED",
                  now(),
                  now(),
                  limit);
          var result = new ArrayList<Delivery>();
          for (var row : rows) {
            String token = UUID.randomUUID().toString();
            jdbc.update(
                "UPDATE projection_batches SET state='LEASED',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?",
                token,
                Timestamp.from(clock.instant().plusSeconds(30)),
                row.get("id"));
            result.add(
                new Delivery(
                    row.get("id").toString(),
                    token,
                    row.get("generation").toString(),
                    row.get("payment_id").toString(),
                    row.get("merchant_id").toString(),
                    ((Number) row.get("revision")).longValue(),
                    row.get("snapshot").toString(),
                    row.get("snapshot_hash").toString()));
          }
          return result;
        });
  }

  public void deliver(List<Delivery> deliveries) {
    var rows = new ArrayList<Map<String, Object>>();
    try {
      for (var delivery : deliveries) {
        if (!Signatures.sha256(delivery.snapshot()).equals(delivery.hash()))
          throw new IllegalStateException("Projection snapshot hash mismatch");
        var snapshot = json.readTree(delivery.snapshot());
        var row = new LinkedHashMap<String, Object>();
        row.put("generation", delivery.generation());
        row.put("merchant_id", delivery.merchantId());
        row.put("payment_id", delivery.paymentId());
        row.put("revision", delivery.revision());
        row.put(
            "created_at", snapshot.get("createdAt").asString().replace('T', ' ').replace("Z", ""));
        row.put(
            "source_updated_at",
            snapshot.get("updatedAt").asString().replace('T', ' ').replace("Z", ""));
        row.put("status", snapshot.get("status").asString());
        row.put("amount_minor", snapshot.get("amountMinor").asString());
        row.put("refunded_minor", snapshot.get("refundedMinor").asString());
        row.put("snapshot", delivery.snapshot());
        row.put("snapshot_hash", delivery.hash());
        rows.add(row);
      }
      clickhouse.write(rows);
      tx.executeWithoutResult(
          t -> {
            for (var delivery : deliveries)
              jdbc.update(
                  "UPDATE projection_batches SET state='DONE',delivered_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND lease_token=? AND state='LEASED'",
                  now(),
                  delivery.id(),
                  delivery.token());
          });
    } catch (RuntimeException failure) {
      tx.executeWithoutResult(
          t -> {
            for (var delivery : deliveries)
              jdbc.update(
                  "UPDATE projection_batches SET state=CASE WHEN attempts>=8 THEN 'DEAD' ELSE 'PENDING' END,due_at=?,lease_token=NULL,lease_until=NULL,last_error=? WHERE id=? AND lease_token=?",
                  Timestamp.from(clock.instant().plusSeconds(30)),
                  failure.getClass().getSimpleName(),
                  delivery.id(),
                  delivery.token());
          });
    }
  }

  public void quarantine(String topic, int partition, long offset, String payload, String reason) {
    String raw = Objects.toString(payload, "");
    String saved = raw.length() > 16000 ? raw.substring(0, 16000) : raw;
    jdbc.update(
        "INSERT INTO projection_errors(id,topic,partition_no,record_offset,payload,payload_hash,state,reason,created_at) VALUES (?,?,?,?,?,?,'QUARANTINED',?,?) ON DUPLICATE KEY UPDATE id=id",
        UUID.randomUUID().toString(),
        topic,
        partition,
        offset,
        saved,
        Signatures.sha256(raw),
        reason.substring(0, Math.min(reason.length(), 1024)),
        now());
  }

  private static void operator(Actor actor) {
    EventDelivery.requireOperator(actor);
  }

  public List<Map<String, Object>> deadBatches(Actor actor) {
    operator(actor);
    return jdbc.query(
        "SELECT id,generation,payment_id,attempts,last_error,created_at FROM projection_batches WHERE state='DEAD' ORDER BY created_at,id LIMIT 100",
        Queries.ROW);
  }

  public Map<String, Object> status(Actor actor) {
    operator(actor);
    var result = new LinkedHashMap<String, Object>();
    result.put("enabled", clickhouse.enabled());
    result.put(
        "control",
        jdbc.query("SELECT * FROM projection_control WHERE id=1", Queries.ROW).getFirst());
    result.put(
        "generations",
        jdbc.query(
            "SELECT * FROM projection_generations ORDER BY created_at DESC LIMIT 20", Queries.ROW));
    result.put(
        "pending",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM projection_batches WHERE state<>'DONE'", Long.class));
    result.put(
        "quarantined",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM projection_errors WHERE state NOT IN ('REPLAYED','DISMISSED')",
            Long.class));
    return result;
  }

  public String rebuild(String reason, Actor actor) {
    operator(actor);
    requireReason(reason);
    return tx.execute(
        t -> {
          var control = jdbc.queryForMap("SELECT * FROM projection_control WHERE id=1 FOR UPDATE");
          if (control.get("building_generation") != null)
            throw new ApiException("REBUILD_ACTIVE", 409, "已有构建中的投影代");
          String id = UUID.randomUUID().toString();
          jdbc.update(
              "INSERT INTO projection_generations(id,state,created_at) VALUES (?,'BUILDING',?)",
              id,
              now());
          jdbc.update(
              "UPDATE projection_control SET building_generation=?,version=version+1 WHERE id=1",
              id);
          audit(actor, "PROJECTION_REBUILD", id, reason);
          return id;
        });
  }

  public Map<String, Object> replay(String id, String reason, Actor actor) {
    operator(actor);
    requireReason(reason);
    return tx.execute(
        t -> {
          var rows = jdbc.queryForList("SELECT * FROM projection_errors WHERE id=? FOR UPDATE", id);
          if (rows.isEmpty()) throw new ApiException("NOT_FOUND", 404, "投影隔离记录不存在");
          var row = rows.getFirst();
          if (!"REPLAYED".equals(row.get("state"))) {
            String payload = row.get("payload").toString();
            if (!Signatures.sha256(payload).equals(row.get("payload_hash")))
              throw new ApiException("TRUNCATED_EVIDENCE", 409, "截断证据不能重放");
            consume(
                row.get("topic").toString(),
                ((Number) row.get("partition_no")).intValue(),
                ((Number) row.get("record_offset")).longValue(),
                payload);
            jdbc.update(
                "UPDATE projection_errors SET state='REPLAYED',replayed_by=? WHERE id=?",
                actor.id(),
                id);
            audit(actor, "PROJECTION_REPLAY", id, reason);
          }
          return Map.of("id", id, "state", "REPLAYED");
        });
  }

  public void retryBatch(String id, String reason, Actor actor) {
    operator(actor);
    requireReason(reason);
    tx.executeWithoutResult(
        t -> {
          int changed =
              jdbc.update(
                  "UPDATE projection_batches SET state='PENDING',attempts=0,due_at=?,last_error=NULL WHERE id=? AND state='DEAD'",
                  now(),
                  id);
          if (changed != 1) throw new ApiException("NOT_RETRYABLE", 409, "只允许重试失败的投递");
          audit(actor, "PROJECTION_RETRY", id, reason);
        });
  }

  public List<Map<String, Object>> errors(String after, Actor actor) {
    operator(actor);
    return jdbc.query(
        "SELECT id,topic,partition_no,record_offset,state,reason,created_at FROM projection_errors WHERE id>? ORDER BY id LIMIT 100",
        Queries.ROW,
        after);
  }

  public Map<String, Object> payments(
      String merchant, String after, String generation, String from, String until, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    String scope = actor.scope(merchant, true);
    String current = active();
    if (after != null && !after.isBlank() && !current.equals(generation))
      throw new ApiException("GENERATION_CHANGED", 409, "投影代已切换，请重新查询");
    Instant end, start;
    try {
      end = until == null ? clock.instant() : Instant.parse(until);
      start = from == null ? end.minus(Duration.ofDays(7)) : Instant.parse(from);
    } catch (DateTimeException invalid) {
      throw new ApiException("INVALID_RANGE", 400, "时间范围无效");
    }
    if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0)
      throw new ApiException("INVALID_RANGE", 400, "在线查询最多31天");
    if (after != null && after.length() > 64)
      throw new ApiException("INVALID_CURSOR", 400, "分页标识无效");
    var rows =
        clickhouse.query(
            "SELECT snapshot,revision FROM merchantpay.payment_projection FINAL WHERE generation={generation:String} AND merchant_id={merchant:String} AND created_at>={from:DateTime64(6)} AND created_at<{until:DateTime64(6)} AND payment_id>{after:String} ORDER BY payment_id LIMIT 101",
            Map.of(
                "generation",
                current,
                "merchant",
                scope,
                "after",
                Objects.toString(after, ""),
                "from",
                start.toString().replace('T', ' ').replace("Z", ""),
                "until",
                end.toString().replace('T', ' ').replace("Z", "")));
    boolean more = rows.size() > 100;
    var items = new ArrayList<Object>();
    for (var row : rows.subList(0, Math.min(100, rows.size())))
      items.add(json.readValue(row.get("snapshot").toString(), Map.class));
    String next =
        more ? json.readTree(rows.get(99).get("snapshot").toString()).get("id").asString() : "";
    return Map.of(
        "items",
        items,
        "nextCursor",
        next,
        "generation",
        current,
        "from",
        start.toString(),
        "until",
        end.toString(),
        "consistency",
        "EVENTUAL",
        "observedAt",
        clock.instant().toString());
  }

  public void dismiss(String id, String reason, Actor actor) {
    operator(actor);
    requireReason(reason);
    tx.executeWithoutResult(
        t -> {
          if (jdbc.update(
                  "UPDATE projection_errors SET state='DISMISSED',replayed_by=? WHERE id=? AND state='QUARANTINED'",
                  actor.id(),
                  id)
              != 1) throw new ApiException("NOT_DISMISSIBLE", 409, "隔离状态已变化");
          audit(actor, "PROJECTION_DISMISS", id, reason);
        });
  }

  public Map<String, Object> payment(String id, String merchant, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    String scope = actor.scope(merchant, true);
    String generation = active();
    var rows =
        clickhouse.query(
            "SELECT snapshot FROM merchantpay.payment_projection FINAL WHERE generation={generation:String} AND merchant_id={merchant:String} AND payment_id={payment:String} LIMIT 1",
            Map.of("generation", generation, "merchant", scope, "payment", id));
    if (rows.isEmpty()) throw new ApiException("NOT_FOUND", 404, "投影中尚无该记录，请查权威支付详情");
    return Map.of(
        "payment",
        json.readValue(rows.getFirst().get("snapshot").toString(), Map.class),
        "generation",
        generation,
        "consistency",
        "EVENTUAL",
        "observedAt",
        clock.instant().toString());
  }

  private String active() {
    String value =
        jdbc.queryForObject(
            "SELECT active_generation FROM projection_control WHERE id=1", String.class);
    if (value == null) throw new ApiException("READ_MODEL_BUILDING", 503, "投影尚未核对并激活");
    return value;
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 1000)
      throw new ApiException("INVALID_REASON", 400, "原因必填且不能超过1000字");
  }

  private void audit(Actor actor, String action, String id, String reason) {
    jdbc.update(
        "INSERT INTO audit_records(id,actor,operation,resource_id,detail,created_at) VALUES (?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor.id(),
        action,
        id,
        reason,
        now());
  }
}
