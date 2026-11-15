package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.outbox.EventJournal;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import dev.merchantpay.platform.projection.ProjectionService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Four local projection pipelines share one ClickHouse target but never one source transaction. */
public final class ShardProjectionService {
  public static final String GROUP = "merchantpay-read-model-v3-sharded";

  private record Source(JdbcTemplate jdbc, EventDelivery events, ProjectionService projection) {}

  private final Map<Integer, Source> sources;
  private final boolean operational;
  private final ShardTransactions routes;

  public ShardProjectionService(
      Map<Integer, DataSource> databases,
      Clock clock,
      JsonMapper json,
      EventDelivery events,
      ClickHouseReadModel clickhouse) {
    this(databases, clock, json, events, clickhouse, false);
  }

  public ShardProjectionService(
      Map<Integer, DataSource> databases,
      Clock clock,
      JsonMapper json,
      EventDelivery events,
      ClickHouseReadModel clickhouse,
      boolean allowPartialPromotion) {
    this(databases, clock, json, events, clickhouse, allowPartialPromotion, null);
  }

  public ShardProjectionService(
      Map<Integer, DataSource> databases,
      Clock clock,
      JsonMapper json,
      EventDelivery events,
      ClickHouseReadModel clickhouse,
      boolean allowPartialPromotion,
      ShardTransactions routes) {
    this.routes = routes;
    ShardEventTransportMaintenance.requireMode(databases, "CDC");
    var configured = new HashMap<Integer, Source>();
    String candidate = null;
    int buildingCount = 0;
    for (int id = 0; id < 4; id++) {
      var jdbc = new JdbcTemplate(databases.get(id));
      jdbc.setQueryTimeout(10);
      var control =
          jdbc.queryForMap(
              "SELECT active_generation,building_generation FROM projection_control WHERE id=1");
      String localActive = (String) control.get("active_generation");
      String localBuilding = (String) control.get("building_generation");
      String localCandidate = localBuilding == null ? localActive : localBuilding;
      if (localCandidate == null
          || id > 0 && !Objects.equals(candidate, localCandidate)
          || localBuilding != null && localBuilding.equals(localActive))
        throw new IllegalStateException("Projection generation differs across sources");
      if (localBuilding != null) buildingCount++;
      if (localActive != null
          && !jdbc.queryForList(
                  "SELECT id FROM projection_generations WHERE id=? AND state='SERVING'",
                  String.class,
                  localActive)
              .equals(List.of(localActive)))
        throw new IllegalStateException("Serving projection generation is missing at shard " + id);
      if (localBuilding != null
          && !jdbc.queryForList(
                  "SELECT id FROM projection_generations WHERE id=? AND state='BUILDING'",
                  String.class,
                  localBuilding)
              .equals(List.of(localBuilding)))
        throw new IllegalStateException("Building projection generation is missing at shard " + id);
      candidate = localCandidate;
      var tx = new TransactionTemplate(new DataSourceTransactionManager(databases.get(id)));
      var localEvents = events.forShard(jdbc);
      configured.put(
          id,
          new Source(
              jdbc,
              localEvents,
              new ProjectionService(jdbc, tx, clock, json, localEvents, clickhouse)));
    }
    if (buildingCount != 0 && buildingCount != 4 && !allowPartialPromotion)
      throw new IllegalStateException(
          "Projection promotion is partial; stop consumers and workers to retry");
    operational = buildingCount == 0 || buildingCount == 4;
    sources = Map.copyOf(configured);
  }

  private Source source(int physical) {
    if (!operational)
      throw new ApiException(
          "PROJECTION_PARTIAL_PROMOTION", 503, "四库投影代提升尚未完成，请保持消费者和worker关闭后以同一代重试");
    var selected = sources.get(physical);
    if (selected == null) throw new ApiException("INVALID_SHARD", 400, "物理分片编号无效");
    return selected;
  }

  public boolean operational() {
    return operational;
  }

  public List<Map<String, Object>> errors(int physical, String after, Actor actor) {
    if (after == null || after.length() > 64)
      throw new ApiException("INVALID_CURSOR", 400, "分页标识无效");
    return source(physical).projection().errors(after, actor);
  }

  public List<Map<String, Object>> deadBatches(int physical, Actor actor) {
    return source(physical).projection().deadBatches(actor);
  }

  public Map<String, Object> replay(int physical, String id, String reason, Actor actor) {
    EventDelivery.requireOperator(actor);
    if (routes == null) throw pending();
    var selected = source(physical);
    var rows = selected.jdbc().queryForList("SELECT * FROM projection_errors WHERE id=?", id);
    if (rows.size() != 1) throw new ApiException("NOT_FOUND", 404, "投影隔离记录不存在");
    var original = rows.getFirst();
    String topic = Objects.toString(original.get("topic"), "");
    requireTopic(topic, physical);
    if ("REPLAYED".equals(original.get("state"))) return Map.of("id", id, "state", "REPLAYED");
    if (!"QUARANTINED".equals(original.get("state")))
      throw new ApiException("NOT_REPLAYABLE", 409, "隔离记录不是待处理状态");
    String payload = Objects.toString(original.get("payload"), "");
    if (!Signatures.sha256(payload).equals(original.get("payload_hash")))
      throw new ApiException("TRUNCATED_EVIDENCE", 409, "截断或损坏的原文不能重放");
    var event = stored(selected, payload);
    var buckets =
        selected
            .jdbc()
            .queryForList(
                "SELECT logical_bucket FROM domain_events WHERE event_id=?",
                Integer.class,
                event.eventId());
    if (buckets.size() != 1) throw conflict("来源事件桶缺失");
    Integer bucket = buckets.getFirst();
    if (bucket == null
        && (physical != 0
            || EventJournal.taskable(event.eventType())
                && !Set.of("RECONCILE", "EXPORT").contains(event.eventType())))
      throw conflict("来源事件无法确定归属桶");
    ShardCatalog.Route expected = null;
    if (bucket != null) {
      expected = routes.currentRoute(bucket);
      if (expected.physicalShard() != physical) throw conflict("来源与当前桶归属不一致");
    }
    var validator =
        (java.util.function.Consumer<Map<String, Object>>)
            locked -> {
              for (String key :
                  List.of(
                      "topic", "partition_no", "record_offset", "payload", "payload_hash", "state"))
                if (!Objects.equals(original.get(key), locked.get(key)))
                  throw conflict("隔离记录已变化，请重新核对");
              requireTopic(Objects.toString(locked.get("topic"), ""), physical);
              var lockedBuckets =
                  selected
                      .jdbc()
                      .queryForList(
                          "SELECT logical_bucket FROM domain_events WHERE event_id=? FOR UPDATE",
                          Integer.class,
                          event.eventId());
              if (lockedBuckets.size() != 1 || !Objects.equals(bucket, lockedBuckets.getFirst()))
                throw conflict("来源事件归属桶已变化");
            };
    if (expected == null) return selected.projection().replay(id, reason, actor, validator);
    var route = expected;
    return routes.write(route, jdbc -> selected.projection().replay(id, reason, actor, validator));
  }

  public void dismiss(int physical, String id, String reason, Actor actor) {
    EventDelivery.requireOperator(actor);
    var selected = source(physical);
    selected
        .projection()
        .dismiss(
            id,
            reason,
            actor,
            locked -> requireTopic(Objects.toString(locked.get("topic"), ""), physical));
  }

  public void retryBatch(int physical, String id, String reason, Actor actor) {
    EventDelivery.requireOperator(actor);
    if (routes == null) throw pending();
    var selected = source(physical);
    var rows =
        selected.jdbc().queryForList("SELECT payment_id FROM projection_batches WHERE id=?", id);
    if (rows.size() != 1) throw new ApiException("NOT_RETRYABLE", 409, "投影批次不存在");
    String payment = rows.getFirst().get("payment_id").toString();
    int bucket = PaymentBuckets.forPaymentId(payment);
    var expected = routes.currentRoute(bucket);
    if (expected.physicalShard() != physical) throw conflict("批次来源与当前桶归属不一致");
    routes.write(
        expected,
        jdbc -> {
          var locked =
              jdbc.queryForList(
                  "SELECT generation,payment_id,snapshot,snapshot_hash,state FROM projection_batches WHERE id=? FOR UPDATE",
                  id);
          if (locked.size() != 1
              || !"DEAD".equals(locked.getFirst().get("state"))
              || !payment.equals(locked.getFirst().get("payment_id")))
            throw new ApiException("NOT_RETRYABLE", 409, "批次已变化或不在失败状态");
          var row = locked.getFirst();
          if (!Signatures.sha256(row.get("snapshot").toString()).equals(row.get("snapshot_hash")))
            throw conflict("投影批次快照校验失败");
          if (jdbc.queryForList(
                      "SELECT id FROM projection_generations WHERE id=? AND state IN ('BUILDING','SERVING')",
                      String.class,
                      row.get("generation"))
                  .isEmpty()
              || jdbc.queryForList("SELECT id FROM payments WHERE id=?", String.class, payment)
                  .isEmpty()) throw conflict("投影代或来源支付已失效");
          selected.projection().retryBatch(id, reason, actor);
          return null;
        });
  }

  private static dev.merchantpay.platform.outbox.DomainEvent stored(Source source, String payload) {
    try {
      return source.events().verifyStored(payload);
    } catch (EventDelivery.InvalidEvent invalid) {
      throw conflict("原文与本库权威事件不一致");
    }
  }

  private static void requireTopic(String topic, int physical) {
    try {
      if (ShardEventDelivery.physical(topic) != physical) throw conflict("隔离记录来源与所选物理库不一致");
    } catch (EventDelivery.InvalidEvent invalid) {
      throw conflict("隔离记录来源主题无效");
    }
  }

  private static ApiException conflict(String message) {
    return new ApiException("PROJECTION_SOURCE_CONFLICT", 409, message);
  }

  private static ApiException pending() {
    return new ApiException("SHARDED_READ_MODEL_PENDING", 503, "分片路由事务尚未启用");
  }

  private Source source(String topic) {
    return source(ShardEventDelivery.physical(topic));
  }

  public void consume(String topic, int partition, long offset, String payload) {
    source(topic).projection().consume(topic, partition, offset, payload);
  }

  public void quarantine(String topic, int partition, long offset, String payload, String reason) {
    source(topic).projection().quarantine(topic, partition, offset, payload, reason);
  }

  public void scanNext(int physical) {
    source(physical).projection().scanNext();
  }

  public void recoverPollingFacts(int physical) {
    source(physical).projection().recoverPollingFacts();
  }

  public int recoverSourceFacts(int physical, String reason, Actor actor) {
    return source(physical).projection().recoverSourceFacts(reason, actor);
  }

  public List<ProjectionService.Delivery> claim(int physical, int limit) {
    return source(physical).projection().claim(limit);
  }

  public void deliver(int physical, List<ProjectionService.Delivery> deliveries) {
    source(physical).projection().deliver(deliveries);
  }
}
