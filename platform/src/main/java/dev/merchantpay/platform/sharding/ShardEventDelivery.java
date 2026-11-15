package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.DomainEvent;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.outbox.EventJournal;
import dev.merchantpay.platform.query.Queries;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The topic selects a physical source; the stored bucket and control route fence its task creation.
 */
public final class ShardEventDelivery {
  private record Source(JdbcTemplate jdbc, EventDelivery delivery, TransactionTemplate tx) {}

  private record Verified(
      Source source, String eventId, Integer bucket, ShardCatalog.Route route) {}

  private final Map<Integer, Source> sources;
  private final ShardTransactions routes;

  public ShardEventDelivery(
      ShardTransactions routes, Map<Integer, DataSource> targets, EventDelivery prototype) {
    this.routes = Objects.requireNonNull(routes);
    if (targets == null || !targets.keySet().equals(Set.of(0, 1, 2, 3)))
      throw new IllegalArgumentException("Four CDC source databases are required");
    var configured = new HashMap<Integer, Source>();
    targets.forEach(
        (id, target) -> {
          var jdbc = new JdbcTemplate(target);
          jdbc.setQueryTimeout(3);
          var tx = new TransactionTemplate(new DataSourceTransactionManager(target));
          tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
          tx.setTimeout(5);
          configured.put(id, new Source(jdbc, prototype.forShard(jdbc), tx));
        });
    sources = Map.copyOf(configured);
  }

  static int physical(String topic) {
    if ("outbox.event.merchantpay".equals(topic)) return 0;
    if (topic != null && topic.matches("outbox\\.event\\.merchantpay\\.s[0-3]"))
      return topic.charAt(topic.length() - 1) - '0';
    throw new EventDelivery.InvalidEvent("Unknown CDC source topic");
  }

  private Source source(String topic) {
    return sources.get(physical(topic));
  }

  private Source source(int physical) {
    var selected = sources.get(physical);
    if (selected == null) throw new ApiException("INVALID_SHARD", 400, "物理分片编号无效");
    return selected;
  }

  private Verified verifySource(String topic, String payload) {
    int physical = physical(topic);
    var source = sources.get(physical);
    DomainEvent event = source.delivery().verifyStored(payload);
    if (physical != 0 && Set.of("RECONCILE", "EXPORT").contains(event.eventType()))
      throw new EventDelivery.InvalidEvent("Target shard has no BULK executor");
    var buckets =
        source
            .jdbc()
            .queryForList(
                "SELECT logical_bucket FROM domain_events WHERE event_id=?",
                Integer.class,
                event.eventId());
    if (buckets.isEmpty())
      buckets =
          source
              .jdbc()
              .queryForList(
                  "SELECT logical_bucket FROM v3_history_event_locators WHERE event_id=? "
                      + "AND state='COLD'",
                  Integer.class,
                  event.eventId());
    if (buckets.size() != 1) throw new EventDelivery.InvalidEvent("Unknown source event");
    Integer bucket = buckets.getFirst();
    if (bucket == null) {
      if (physical != 0
          || !Set.of("RECONCILE", "EXPORT").contains(event.eventType())
              && EventJournal.taskable(event.eventType()))
        throw new EventDelivery.InvalidEvent("Source event has no routable bucket");
      return new Verified(source, event.eventId(), null, null);
    }
    var expected = routes.currentRoute(bucket);
    if (expected.physicalShard() != physical)
      throw new EventDelivery.InvalidEvent("CDC source differs from authoritative route");
    return new Verified(source, event.eventId(), bucket, expected);
  }

  public void deliver(String topic, int partition, long offset, String payload) {
    var verified = verifySource(topic, payload);
    if (verified.route() == null) {
      verified.source().delivery().deliver(topic, partition, offset, payload);
      return;
    }
    var source = verified.source();
    var expected = verified.route();
    routes.write(
        expected,
        jdbc -> {
          source.delivery().deliver(topic, partition, offset, payload);
          return null;
        });
  }

  public String quarantine(
      String topic, int partition, long offset, String payload, String reason) {
    return source(topic).delivery().quarantine(topic, partition, offset, payload, reason);
  }

  public List<Map<String, Object>> quarantined(int physical, String after) {
    if (after == null || after.length() > 64)
      throw new ApiException("INVALID_CURSOR", 400, "分页标识无效");
    return source(physical)
        .jdbc()
        .query(
            "SELECT id,topic,partition_no,record_offset,payload_hash,reason,status,attempts,created_at,updated_at,replayed_by FROM event_quarantine WHERE id>? ORDER BY id LIMIT 100",
            Queries.ROW,
            after);
  }

  public Map<String, Object> status(int physical) {
    var jdbc = source(physical).jdbc();
    var result =
        new LinkedHashMap<>(
            jdbc.query("SELECT * FROM event_transport WHERE id=1", Queries.ROW).getFirst());
    result.put("physicalShard", physical);
    result.put("shardedCdcEnabled", true);
    result.put(
        "unreceived",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM domain_events d WHERE delivery_mode='CDC' AND NOT EXISTS(SELECT 1 FROM event_receipts r WHERE r.event_id=d.event_id AND r.consumer_name=?)",
            Long.class,
            EventDelivery.CONSUMER));
    result.put(
        "quarantined",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'", Long.class));
    return result;
  }

  /**
   * Replays under the same route fence as live delivery; no unchecked target task can be created.
   */
  public Map<String, Object> replay(int physical, String id, Actor actor) {
    EventDelivery.requireOperator(actor);
    var selected = source(physical);
    var rows =
        selected
            .jdbc()
            .queryForList(
                "SELECT topic,partition_no,record_offset,payload,payload_hash,status FROM event_quarantine WHERE id=?",
                id);
    if (rows.size() != 1) throw new ApiException("NOT_FOUND", 404, "隔离事件不存在");
    var snapshot = rows.getFirst();
    String topic = (String) snapshot.get("topic");
    int recordedPhysical;
    try {
      recordedPhysical = physical(topic);
    } catch (EventDelivery.InvalidEvent invalid) {
      throw new ApiException("EVENT_SOURCE_CONFLICT", 409, "隔离事件来源无效");
    }
    if (recordedPhysical != physical)
      throw new ApiException("EVENT_SOURCE_CONFLICT", 409, "隔离事件来源与所选分片不一致");
    if ("REPLAYED".equals(snapshot.get("status"))) return Map.of("id", id, "status", "REPLAYED");
    String payload = (String) snapshot.get("payload");
    if (!Signatures.sha256(payload).equals(snapshot.get("payload_hash")))
      throw new ApiException("TRUNCATED_EVIDENCE", 409, "截断或损坏的原文不能重放");
    Verified verified;
    try {
      verified = verifySource(topic, payload);
    } catch (EventDelivery.InvalidEvent invalid) {
      throw new ApiException("EVENT_NOT_REPLAYABLE", 409, "原始事件与来源事实不一致");
    }
    Function<JdbcTemplate, Map<String, Object>> action =
        jdbc -> {
          var locked =
              jdbc.queryForList(
                  "SELECT topic,partition_no,record_offset,payload,payload_hash FROM event_quarantine WHERE id=? FOR UPDATE",
                  id);
          if (locked.size() != 1 || !sameSnapshot(snapshot, locked.getFirst()))
            throw new ApiException("EVENT_SOURCE_CONFLICT", 409, "隔离事件已变化，请重新核对");
          var buckets =
              jdbc.queryForList(
                  "SELECT logical_bucket FROM domain_events WHERE event_id=? FOR UPDATE",
                  Integer.class,
                  verified.eventId());
          if (buckets.size() != 1 || !Objects.equals(buckets.getFirst(), verified.bucket()))
            throw new ApiException("EVENT_SOURCE_CONFLICT", 409, "来源事件归属桶已变化，请重新核对");
          return selected.delivery().replay(id, actor);
        };
    if (verified.route() == null)
      return selected.tx().execute(status -> action.apply(selected.jdbc()));
    return routes.write(verified.route(), action);
  }

  private static boolean sameSnapshot(Map<String, Object> before, Map<String, Object> locked) {
    for (String key : List.of("topic", "partition_no", "record_offset", "payload", "payload_hash"))
      if (!Objects.equals(before.get(key), locked.get(key))) return false;
    return true;
  }
}
