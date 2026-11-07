package dev.merchantpay.platform.history;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import dev.merchantpay.platform.storage.ObjectStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Offline payment-root archive; a retired event's verified object is its authority. */
@Service
@ConditionalOnProperty(
    name = {"merchantpay.sharding.enabled", "merchantpay.history.archive-enabled"},
    havingValue = "true")
public final class HistoryArchiveService {
  public record Archive(
      String id,
      String merchantId,
      String paymentId,
      int logicalBucket,
      int physicalShard,
      long routeEpoch,
      String sourceHash,
      String objectHash,
      String objectKey,
      long factCount,
      long objectBytes,
      String archivedBy,
      String reason,
      Instant createdAt,
      String tier) {}

  public record Snapshot(
      Archive archive, List<HistoryParquetCodec.Fact> facts, String consistency) {}

  public record Page(List<Archive> items, String nextCursor, Instant from, Instant until) {}

  private record Captured(List<HistoryParquetCodec.Fact> facts, String sourceHash) {}

  private record LocatedEvent(Archive archive, String payloadHash) {}

  static final String EVENTS_SQL =
      """
      SELECT e.* FROM domain_events e WHERE e.merchant_id=? AND (
        e.aggregateid=?
        OR e.aggregateid IN (SELECT r.id FROM refunds r
            WHERE r.payment_id=? AND r.merchant_id=?)
        OR (e.event_type='PROCESS_INBOX' AND EXISTS (
            SELECT 1 FROM inbox_notifications i WHERE i.id=e.aggregateid
              AND (i.aggregate_id=? OR i.aggregate_id IN (
                  SELECT r.id FROM refunds r WHERE r.payment_id=? AND r.merchant_id=?))
        ))
      ) ORDER BY e.sequence_no LIMIT 20001
      """;

  private final ShardExportReadGate gate;
  private final ObjectStorage storage;
  private final JsonMapper json;
  private final Queries queries;
  private final Clock clock;

  public HistoryArchiveService(
      ShardExportReadGate gate,
      ObjectStorage storage,
      JsonMapper json,
      Queries queries,
      Clock clock) {
    this.gate = gate;
    this.storage = storage;
    this.json = json;
    this.queries = queries;
    this.clock = clock;
  }

  public Archive archive(
      String merchant, String payment, String reason, boolean writersStopped, Actor actor) {
    actor.require("ADMIN", "OPS");
    if (actor.merchantId() != null && !actor.merchantId().isBlank())
      throw new ApiException("FORBIDDEN", 403, "仅平台运维可生成归档对象");
    if (!writersStopped) throw conflict("须停写并排空来源后才能制作历史归档");
    if (reason == null || reason.isBlank() || reason.length() > 1000)
      throw new ApiException("INVALID_REASON", 400, "归档原因必填且不能超过1000字");
    int bucket = PaymentBuckets.forPaymentId(payment);
    var routes = gate.capture();
    var route = routes.routes().get(bucket);
    var captured = capture(merchant, payment, route);
    var existing = find(merchant, payment, route.physical(), captured.sourceHash());
    if (existing != null) {
      if (existing.logicalBucket() != bucket) throw conflict("既有归档清单桶号不一致");
      gate.validate(routes);
      return existing;
    }
    Path file = null;
    try {
      file = storage.temporary("history-");
      Files.delete(file); // Parquet's LocalOutputFile uses CREATE_NEW.
      var written = HistoryParquetCodec.write(file, captured.facts());
      String key = "history/" + written.sha256() + "-" + UUID.randomUUID() + ".parquet";
      String pointer = storage.publish(file, key);
      Files.deleteIfExists(file);
      file = null;
      if (!captured.sourceHash().equals(capture(merchant, payment, route).sourceHash()))
        throw conflict("归档期间来源事实变化，已上传对象不会进入可见清单");
      gate.validate(routes);
      var archived =
          record(
              merchant,
              payment,
              route,
              captured.sourceHash(),
              written,
              captured.facts(),
              pointer,
              reason,
              actor);
      if (archived.logicalBucket() != bucket) throw conflict("归档清单桶号不一致");
      gate.validate(routes);
      return archived;
    } catch (IOException unavailable) {
      throw new ApiException("HISTORY_STORAGE_UNAVAILABLE", 503, "无法写入或校验历史归档对象");
    } finally {
      if (file != null)
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
          /* Private staging cleanup is retried by operations. */
        }
    }
  }

  public Snapshot read(String merchant, String payment, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    actor.checkMerchant(merchant);
    int bucket = PaymentBuckets.forPaymentId(payment);
    var routes = gate.capture();
    var route = routes.routes().get(bucket);
    var jdbc = new JdbcTemplate(gate.source(route.physical()));
    var rows =
        jdbc.query(
            "SELECT * FROM v3_history_archives WHERE merchant_id=? AND payment_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
            (rs, ignored) -> mapped(rs),
            merchant,
            payment);
    if (rows.isEmpty()) throw new ApiException("HISTORY_NOT_FOUND", 404, "该支付尚无可核对的历史归档副本");
    var archive = rows.getFirst();
    if (archive.logicalBucket() != bucket) throw conflict("归档清单与支付桶不一致");
    return loadSnapshot(archive, merchant, payment, routes);
  }

  /**
   * Resolves an archived event through the current bucket owner, then verifies the entire object.
   */
  public HistoryParquetCodec.Fact readEvent(
      String merchant, String payment, String eventId, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    actor.checkMerchant(merchant);
    if (eventId == null || eventId.isBlank() || eventId.length() > 160)
      throw new ApiException("INVALID_EVENT_ID", 400, "事件号无效");
    int bucket = PaymentBuckets.forPaymentId(payment);
    var routes = gate.capture();
    var route = routes.routes().get(bucket);
    var jdbc = new JdbcTemplate(gate.source(route.physical()));
    var found =
        jdbc.query(
            "SELECT a.*,l.payload_hash AS locator_payload_hash FROM v3_history_event_locators l "
                + "JOIN v3_history_archives a ON a.id=l.archive_id "
                + "WHERE l.event_id=? AND l.merchant_id=? AND l.payment_id=? AND l.logical_bucket=?",
            (rs, ignored) -> new LocatedEvent(mapped(rs), rs.getString("locator_payload_hash")),
            eventId,
            merchant,
            payment,
            bucket);
    if (found.size() != 1)
      throw new ApiException("HISTORY_EVENT_NOT_FOUND", 404, "该事件没有可核对的归档定位记录");
    var located = found.getFirst();
    if (located.archive().logicalBucket() != bucket
        || !merchant.equals(located.archive().merchantId())
        || !payment.equals(located.archive().paymentId())) throw conflict("归档事件定位与清单不一致");
    var facts = loadSnapshot(located.archive(), merchant, payment, routes).facts();
    var matches =
        facts.stream().filter(f -> "EVENT".equals(f.kind()) && eventId.equals(f.factId())).toList();
    if (matches.size() != 1 || !located.payloadHash().equals(matches.getFirst().payloadHash()))
      throw conflict("归档事件定位与对象事实不一致");
    var fact = matches.getFirst();
    try {
      var row = json.readTree(fact.payloadJson());
      if (!row.hasNonNull("eventId")
          || !eventId.equals(row.get("eventId").asString())
          || !row.hasNonNull("merchantId")
          || !merchant.equals(row.get("merchantId").asString())
          || !row.hasNonNull("logicalBucket")
          || !row.get("logicalBucket").isIntegralNumber()
          || row.get("logicalBucket").asInt() != bucket
          || !row.hasNonNull("payload")
          || !row.get("payload").isString()
          || !row.hasNonNull("payloadHash")
          || !Signatures.sha256(row.get("payload").asString())
              .equals(row.get("payloadHash").asString())) throw conflict("归档事件原文与事件身份不一致");
    } catch (RuntimeException invalid) {
      if (invalid instanceof ApiException) throw invalid;
      throw conflict("归档事件原文无法解析");
    }
    gate.validate(routes);
    return fact;
  }

  /** Verifies a pre-V45 warm object before installing any missing event locators. */
  public Archive backfillEventLocators(
      String merchant,
      String payment,
      String archiveId,
      String reason,
      boolean writersStopped,
      Actor actor) {
    actor.require("ADMIN", "OPS");
    if (actor.merchantId() != null && !actor.merchantId().isBlank())
      throw new ApiException("FORBIDDEN", 403, "仅平台运维可回填事件定位记录");
    if (!writersStopped) throw conflict("回填前须停写并排空搬迁和归档来源");
    if (reason == null || reason.isBlank() || reason.length() > 1000)
      throw new ApiException("INVALID_REASON", 400, "回填原因必填且不能超过1000字");
    if (archiveId == null || !archiveId.matches("[a-f0-9-]{36}"))
      throw new ApiException("INVALID_ARCHIVE_ID", 400, "归档编号无效");
    int bucket = PaymentBuckets.forPaymentId(payment);
    var routes = gate.capture();
    var route = routes.routes().get(bucket);
    var source = gate.source(route.physical());
    var jdbc = new JdbcTemplate(source);
    var rows =
        jdbc.query(
            "SELECT * FROM v3_history_archives WHERE id=? AND merchant_id=?"
                + " AND payment_id=? AND logical_bucket=?",
            (rs, ignored) -> mapped(rs),
            archiveId,
            merchant,
            payment,
            bucket);
    if (rows.size() != 1) throw new ApiException("HISTORY_NOT_FOUND", 404, "指定归档清单不存在");
    var archive = rows.getFirst();
    var facts = loadSnapshot(archive, merchant, payment, routes).facts();
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(300);
    transaction.executeWithoutResult(
        status -> {
          var fence =
              jdbc.queryForList(
                  "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=? FOR UPDATE",
                  bucket);
          if (fence.size() != 1
              || ((Number) fence.getFirst().get("route_epoch")).longValue() != route.epoch()
              || !"ACTIVE".equals(fence.getFirst().get("access_state")))
            throw conflict("回填期间来源栅栏已变化");
          try {
            jdbc.execute(
                (ConnectionCallback<Void>)
                    connection -> {
                      EventTransportMaintenance.requireDrained(connection);
                      return null;
                    });
          } catch (IllegalStateException pending) {
            throw conflict("回填期间来源任务或事件尚未排空");
          }
          var current =
              jdbc.query(
                  "SELECT * FROM v3_history_archives WHERE id=? FOR UPDATE",
                  (rs, ignored) -> mapped(rs),
                  archiveId);
          if (current.size() != 1 || !archive.equals(current.getFirst()))
            throw conflict("回填期间归档清单已变化");
          recordEventLocators(
              jdbc,
              facts,
              merchant,
              payment,
              bucket,
              archiveId,
              Timestamp.from(clock.instant()),
              json,
              false);
          jdbc.update(
              "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at)"
                  + " VALUES(?,?,?,'V3_HISTORY_EVENT_LOCATOR_BACKFILL',?,?,?)",
              UUID.randomUUID().toString(),
              merchant,
              actor.id(),
              archiveId,
              reason,
              Timestamp.from(clock.instant()));
        });
    gate.validate(routes);
    return archive;
  }

  private Snapshot loadSnapshot(
      Archive archive, String merchant, String payment, ShardExportReadGate.Snapshot routes) {
    Path file = null;
    try {
      file = storage.temporary("history-read-");
      try (var input = storage.open(archive.objectKey())) {
        try (var output = Files.newOutputStream(file)) {
          byte[] buffer = new byte[64 * 1024];
          long bytes = 0;
          int n;
          while ((n = input.read(buffer)) != -1) {
            bytes += n;
            if (bytes > 64L * 1024 * 1024)
              throw new IOException("History object exceeds stored size limit");
            output.write(buffer, 0, n);
          }
          if (bytes != archive.objectBytes())
            throw new IOException("History object size does not match the manifest");
        }
      }
      var facts = HistoryParquetCodec.read(file, archive.objectHash());
      if (facts.size() != archive.factCount()
          || !sourceHash(facts).equals(archive.sourceHash())
          || facts.stream()
              .anyMatch(f -> !merchant.equals(f.merchantId()) || !payment.equals(f.paymentId())))
        throw conflict("历史归档事实与持久清单不一致");
      gate.validate(routes);
      return new Snapshot(archive, facts, "HISTORICAL_COPY");
    } catch (IOException unavailable) {
      throw new ApiException("HISTORY_STORAGE_UNAVAILABLE", 503, "历史归档对象不可读取或摘要不一致");
    } finally {
      if (file != null)
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
          /* Private staging cleanup is retried by operations. */
        }
    }
  }

  public Page list(String merchant, String from, String until, String cursor, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    actor.checkMerchant(merchant);
    var range = queries.range(from, until, 31);
    String scope =
        Signatures.sha256("history-list\0" + merchant + "\0" + range.from() + "\0" + range.until());
    Timestamp before = null;
    String beforeId = null;
    if (cursor != null && !cursor.isBlank()) {
      try {
        var fields = queries.decodeCursor(scope, cursor).split("~", -1);
        if (fields.length != 2 || !fields[1].matches("[a-f0-9-]{36}"))
          throw new IllegalArgumentException();
        before = Timestamp.from(Instant.parse(fields[0]));
        beforeId = fields[1];
      } catch (IllegalArgumentException | DateTimeException invalid) {
        throw new ApiException("INVALID_CURSOR", 400, "历史分页游标无效");
      }
    }
    var routes = gate.capture();
    var merged = new ArrayList<Archive>();
    for (var route : routes.routes()) {
      var args = new ArrayList<Object>();
      args.add(merchant);
      args.add(route.bucket());
      args.add(Timestamp.from(range.from()));
      args.add(Timestamp.from(range.until()));
      String sql =
          "SELECT * FROM v3_history_archives WHERE merchant_id=?"
              + " AND logical_bucket=? AND created_at>=? AND created_at<?";
      if (before != null) {
        sql += " AND (created_at<? OR (created_at=? AND id<?))";
        args.add(before);
        args.add(before);
        args.add(beforeId);
      }
      sql += " ORDER BY created_at DESC,id DESC LIMIT 51";
      var local = new JdbcTemplate(gate.source(route.physical()));
      local.setQueryTimeout(5);
      for (var archive : local.query(sql, (rs, ignored) -> mapped(rs), args.toArray())) {
        int bucket;
        try {
          bucket = PaymentBuckets.forPaymentId(archive.paymentId());
        } catch (ApiException invalid) {
          throw conflict("归档清单支付号无效");
        }
        if (bucket != route.bucket() || bucket != archive.logicalBucket())
          throw conflict("归档清单与当前桶归属不符");
        merged.add(archive);
      }
    }
    merged.sort(Comparator.comparing(Archive::createdAt).thenComparing(Archive::id).reversed());
    boolean more = merged.size() > 50;
    var items = List.copyOf(merged.subList(0, Math.min(50, merged.size())));
    String next =
        more
            ? queries.encodeCursor(scope, items.getLast().createdAt() + "~" + items.getLast().id())
            : null;
    gate.validate(routes);
    return new Page(items, next, range.from(), range.until());
  }

  public Snapshot readByBusinessKey(String merchant, String orderNo, Actor actor) {
    actor.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
    actor.checkMerchant(merchant);
    if (orderNo == null || orderNo.isBlank() || orderNo.length() > 100)
      throw new ApiException("INVALID_BUSINESS_KEY", 400, "商户订单号无效");
    int bucket = PaymentBuckets.forBusinessKey(merchant, orderNo);
    var routes = gate.capture();
    var route = routes.routes().get(bucket);
    var payments =
        new JdbcTemplate(gate.source(route.physical()))
            .queryForList(
                "SELECT id FROM payments WHERE merchant_id=? AND merchant_order_no=?",
                String.class,
                merchant,
                orderNo);
    if (payments.isEmpty()) throw new ApiException("HISTORY_NOT_FOUND", 404, "该商户订单暂无历史归档副本");
    if (payments.size() != 1) throw conflict("商户业务号对应多个支付根");
    if (PaymentBuckets.forPaymentId(payments.getFirst()) != bucket)
      throw conflict("商户业务号与支付根桶归属不一致");
    gate.validate(routes);
    var snapshot = read(merchant, payments.getFirst(), actor);
    var roots =
        snapshot.facts().stream()
            .filter(
                fact -> "PAYMENT".equals(fact.kind()) && payments.getFirst().equals(fact.factId()))
            .limit(2)
            .toList();
    if (roots.size() != 1) throw conflict("归档支付根缺失或重复");
    try {
      var root = json.readTree(roots.getFirst().payloadJson());
      if (!root.hasNonNull("merchantOrderNo")
          || !orderNo.equals(root.get("merchantOrderNo").asString()))
        throw conflict("归档业务号索引与支付事实不一致");
    } catch (RuntimeException invalid) {
      if (invalid instanceof ApiException) throw invalid;
      throw conflict("归档支付事实无法解析");
    }
    return snapshot;
  }

  /** Bounded exact lookup of one verified archived payment, refund or ledger fact. */
  public HistoryParquetCodec.Fact readFact(
      String merchant, String payment, String kind, String factId, Actor actor) {
    if (!List.of("PAYMENT", "REFUND", "JOURNAL", "ENTRY", "EVENT").contains(kind)
        || factId == null
        || factId.isBlank()
        || factId.length() > 160)
      throw new ApiException("INVALID_HISTORY_FACT", 400, "历史事实类型或编号无效");
    var matches =
        read(merchant, payment, actor).facts().stream()
            .filter(fact -> kind.equals(fact.kind()) && factId.equals(fact.factId()))
            .limit(2)
            .toList();
    if (matches.isEmpty()) throw new ApiException("HISTORY_FACT_NOT_FOUND", 404, "指定归档事实不存在");
    if (matches.size() != 1) throw conflict("归档对象含重复事实编号");
    return matches.getFirst();
  }

  private Captured capture(String merchant, String payment, ShardExportReadGate.Route route) {
    try (var connection = gate.source(route.physical()).getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      jdbc.setQueryTimeout(60);
      EventTransportMaintenance.requireDrained(connection);
      var identity =
          jdbc.queryForList(
              "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
      var fence =
          jdbc.queryForList(
              "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=?",
              route.bucket());
      if (identity.size() != 1
          || identity.getFirst() != route.physical()
          || fence.size() != 1
          || ((Number) fence.getFirst().get("route_epoch")).longValue() != route.epoch()
          || !"ACTIVE".equals(fence.getFirst().get("access_state")))
        throw conflict("来源物理身份或桶栅栏已变化");
      var roots =
          jdbc.query(
              "SELECT * FROM payments WHERE id=? AND merchant_id=?",
              Queries.ROW,
              payment,
              merchant);
      if (roots.size() != 1) throw new ApiException("NOT_FOUND", 404, "支付根不存在");
      var root = roots.getFirst();
      Instant updated = Instant.parse(root.get("updatedAt").toString());
      if (updated.isAfter(clock.instant().minus(Duration.ofDays(30))))
        throw conflict("仅归档超过30天的支付根");
      var facts = new ArrayList<HistoryParquetCodec.Fact>();
      add(facts, "PAYMENT", merchant, payment, payment, updated, root);
      for (var row :
          jdbc.query(
              "SELECT * FROM refunds WHERE payment_id=? AND merchant_id=? ORDER BY id LIMIT 20001",
              Queries.ROW,
              payment,
              merchant))
        add(
            facts,
            "REFUND",
            merchant,
            payment,
            row.get("id").toString(),
            Instant.parse(row.get("updatedAt").toString()),
            row);
      for (var row :
          jdbc.query(
              "SELECT * FROM ledger_journals WHERE payment_id=? AND merchant_id=? ORDER BY id LIMIT 20001",
              Queries.ROW,
              payment,
              merchant))
        add(
            facts,
            "JOURNAL",
            merchant,
            payment,
            row.get("id").toString(),
            Instant.parse(row.get("createdAt").toString()),
            row);
      for (var row :
          jdbc.query(
              "SELECT e.*,j.created_at AS archive_occurred FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE e.payment_id=? AND e.merchant_id=? AND j.merchant_id=? ORDER BY e.id LIMIT 20001",
              Queries.ROW,
              payment,
              merchant,
              merchant))
        add(
            facts,
            "ENTRY",
            merchant,
            payment,
            row.get("id").toString(),
            Instant.parse(row.get("archiveOccurred").toString()),
            row);
      for (var row :
          jdbc.query(
              EVENTS_SQL,
              Queries.ROW,
              merchant,
              payment,
              payment,
              merchant,
              payment,
              payment,
              merchant))
        add(
            facts,
            "EVENT",
            merchant,
            payment,
            row.get("eventId").toString(),
            Instant.parse(row.get("occurredAt").toString()),
            row);
      for (var cold : coldFacts(jdbc, merchant, payment, route)) {
        if (facts.size() == 20_000) throw conflict("单个支付根的历史事实超过归档上限");
        facts.add(cold);
      }
      connection.rollback();
      return new Captured(List.copyOf(facts), sourceHash(facts));
    } catch (SQLException unavailable) {
      throw new ApiException("HISTORY_SOURCE_UNAVAILABLE", 503, "无法从稳定的来源快照生成历史归档");
    } catch (IllegalStateException pending) {
      throw conflict("来源业务任务或事件尚未排空");
    }
  }

  private List<HistoryParquetCodec.Fact> coldFacts(
      JdbcTemplate jdbc, String merchant, String payment, ShardExportReadGate.Route route) {
    var locators =
        jdbc.queryForList(
            "SELECT event_id,archive_id,payload_hash,"
                + "event_payload_hash FROM v3_history_event_locators WHERE payment_id=? "
                + "AND merchant_id=? AND logical_bucket=? AND state='COLD' "
                + "ORDER BY event_id LIMIT 20001",
            payment,
            merchant,
            route.bucket());
    if (locators.isEmpty()) return List.of();
    if (locators.size() > 20_000) throw conflict("单个支付根的冷事件超过归档上限");
    var routes = gate.capture();
    if (!route.equals(routes.routes().get(route.bucket()))) throw conflict("冷事件归档时目录已变化");
    var byArchive = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
    for (var locator : locators)
      byArchive
          .computeIfAbsent((String) locator.get("archive_id"), ignored -> new ArrayList<>())
          .add(locator);
    var found = new java.util.HashMap<String, HistoryParquetCodec.Fact>();
    for (var group : byArchive.entrySet()) {
      var archives =
          jdbc.query(
              "SELECT * FROM v3_history_archives WHERE id=? "
                  + "AND merchant_id=? AND payment_id=? AND logical_bucket=?",
              (rs, ignored) -> mapped(rs),
              group.getKey(),
              merchant,
              payment,
              route.bucket());
      if (archives.size() != 1) throw conflict("冷事件归档清单缺失");
      var objectFacts = loadSnapshot(archives.getFirst(), merchant, payment, routes).facts();
      var events = new java.util.HashMap<String, HistoryParquetCodec.Fact>();
      for (var fact : objectFacts)
        if ("EVENT".equals(fact.kind()) && events.put(fact.factId(), fact) != null)
          throw conflict("归档对象含重复事件号");
      for (var locator : group.getValue()) {
        String eventId = (String) locator.get("event_id");
        var fact = events.get(eventId);
        if (fact == null || !fact.payloadHash().equals(locator.get("payload_hash")))
          throw conflict("冷事件定位与归档对象不一致");
        try {
          var row = json.readTree(fact.payloadJson());
          if (!row.hasNonNull("eventId")
              || !eventId.equals(row.get("eventId").asString())
              || !row.hasNonNull("merchantId")
              || !merchant.equals(row.get("merchantId").asString())
              || !row.hasNonNull("logicalBucket")
              || !row.get("logicalBucket").isIntegralNumber()
              || row.get("logicalBucket").asInt() != route.bucket()
              || !row.hasNonNull("payload")
              || !row.get("payload").isString()
              || !row.hasNonNull("payloadHash")
              || !Signatures.sha256(row.get("payload").asString())
                  .equals(row.get("payloadHash").asString())
              || !row.get("payloadHash").asString().equals(locator.get("event_payload_hash")))
            throw conflict("冷事件原文与归档定位不一致");
        } catch (RuntimeException invalid) {
          if (invalid instanceof ApiException) throw invalid;
          throw conflict("冷事件归档原文无法解析");
        }
        if (found.put(eventId, fact) != null) throw conflict("冷事件定位重复");
      }
    }
    var ordered = new ArrayList<HistoryParquetCodec.Fact>();
    for (var locator : locators) ordered.add(found.get((String) locator.get("event_id")));
    return List.copyOf(ordered);
  }

  private void add(
      List<HistoryParquetCodec.Fact> facts,
      String kind,
      String merchant,
      String payment,
      String id,
      Instant occurred,
      Map<String, Object> row) {
    if (facts.size() == 20_000) throw conflict("单个支付根的历史事实超过归档上限");
    String payload = json.writeValueAsString(row);
    facts.add(
        new HistoryParquetCodec.Fact(
            kind,
            merchant,
            payment,
            id,
            Math.multiplyExact(occurred.getEpochSecond(), 1_000_000L) + occurred.getNano() / 1_000,
            Signatures.sha256(payload),
            payload));
  }

  private Archive record(
      String merchant,
      String payment,
      ShardExportReadGate.Route route,
      String sourceHash,
      HistoryParquetCodec.Written written,
      List<HistoryParquetCodec.Fact> facts,
      String pointer,
      String reason,
      Actor actor) {
    var jdbc = new JdbcTemplate(gate.source(route.physical()));
    var existing = find(merchant, payment, route.physical(), sourceHash);
    if (existing != null) return existing;
    String id = UUID.randomUUID().toString();
    var now = Timestamp.from(clock.instant());
    var transaction =
        new TransactionTemplate(new DataSourceTransactionManager(gate.source(route.physical())));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(300);
    try {
      return transaction.execute(
          status -> {
            var fence =
                jdbc.queryForList(
                    "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=? FOR UPDATE",
                    route.bucket());
            if (fence.size() != 1
                || ((Number) fence.getFirst().get("route_epoch")).longValue() != route.epoch()
                || !"ACTIVE".equals(fence.getFirst().get("access_state")))
              throw conflict("归档发布前来源桶栅栏已变化");
            if (jdbc.update(
                        "INSERT INTO v3_history_archives(id,merchant_id,payment_id,logical_bucket,physical_shard,route_epoch,source_hash,object_hash,object_key,fact_count,object_bytes,archived_by,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id,
                        merchant,
                        payment,
                        route.bucket(),
                        route.physical(),
                        route.epoch(),
                        sourceHash,
                        written.sha256(),
                        pointer,
                        written.rows(),
                        written.bytes(),
                        actor.id(),
                        reason,
                        now)
                    != 1
                || jdbc.update(
                        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES(?,?,?,'V3_HISTORY_ARCHIVE',?,?,?)",
                        UUID.randomUUID().toString(),
                        merchant,
                        actor.id(),
                        payment,
                        "hash=" + written.sha256() + "; " + reason,
                        now)
                    != 1) throw conflict("历史归档清单或审计未写入");
            recordEventLocators(
                jdbc, facts, merchant, payment, route.bucket(), id, now, json, true);
            return new Archive(
                id,
                merchant,
                payment,
                route.bucket(),
                route.physical(),
                route.epoch(),
                sourceHash,
                written.sha256(),
                pointer,
                written.rows(),
                written.bytes(),
                actor.id(),
                reason,
                now.toInstant(),
                "WARM_COPY");
          });
    } catch (DuplicateKeyException raced) {
      var winner = find(merchant, payment, route.physical(), sourceHash);
      if (winner != null) return winner;
      throw conflict("归档清单唯一键冲突，须核对目标对象和来源");
    }
  }

  static void recordEventLocators(
      JdbcTemplate jdbc,
      List<HistoryParquetCodec.Fact> facts,
      String merchant,
      String payment,
      int bucket,
      String archiveId,
      Timestamp now,
      JsonMapper json,
      boolean allowRepoint) {
    var unique = new java.util.LinkedHashMap<String, HistoryParquetCodec.Fact>();
    for (var fact : facts)
      if ("EVENT".equals(fact.kind())) {
        var prior = unique.putIfAbsent(fact.factId(), fact);
        if (prior != null && !prior.equals(fact)) throw conflict("同一事件号对应多个归档事实");
      }
    var events = new ArrayList<>(unique.values());
    for (int start = 0; start < events.size(); start += 500) {
      var page = events.subList(start, Math.min(start + 500, events.size()));
      String placeholders = String.join(",", java.util.Collections.nCopies(page.size(), "?"));
      var existing =
          jdbc.queryForList(
              "SELECT event_id,merchant_id,payment_id,logical_bucket,payload_hash,"
                  + "event_payload_hash,event_row_id,state "
                  + "FROM v3_history_event_locators WHERE event_id IN ("
                  + placeholders
                  + ") FOR UPDATE",
              page.stream().map(HistoryParquetCodec.Fact::factId).toArray());
      var found = new java.util.HashMap<String, Map<String, Object>>();
      for (var row : existing)
        if (found.put((String) row.get("event_id"), row) != null) throw conflict("归档事件定位索引重复");
      var missing = new ArrayList<HistoryParquetCodec.Fact>();
      var rowIds = new java.util.HashMap<String, String>();
      for (var fact : page) {
        String payload;
        String payloadHash;
        try {
          var archived = json.readTree(fact.payloadJson());
          if (!archived.hasNonNull("eventId")
              || !fact.factId().equals(archived.get("eventId").asString())
              || !archived.hasNonNull("payload")
              || !archived.get("payload").isString()
              || !archived.hasNonNull("payloadHash")) throw conflict("归档事件原文缺少身份或负载");
          payload = archived.get("payload").asString();
          payloadHash = archived.get("payloadHash").asString();
          if (!Signatures.sha256(payload).equals(payloadHash)) throw conflict("归档事件负载摘要无效");
        } catch (RuntimeException invalid) {
          if (invalid instanceof ApiException) throw invalid;
          throw conflict("归档事件原文无法解析");
        }
        var prior = found.get(fact.factId());
        if (prior != null && "COLD".equals(prior.get("state"))) {
          if (!merchant.equals(prior.get("merchant_id"))
              || !payment.equals(prior.get("payment_id"))
              || ((Number) prior.get("logical_bucket")).intValue() != bucket
              || !fact.payloadHash().equals(prior.get("payload_hash"))
              || !payloadHash.equals(prior.get("event_payload_hash")))
            throw conflict("冷事件定位与归档事实不一致");
          continue;
        }
        var hot =
            jdbc.queryForList(
                "SELECT id,merchant_id,logical_bucket,payload,payload_hash "
                    + "FROM domain_events WHERE event_id=?",
                fact.factId());
        if (hot.size() != 1
            || !merchant.equals(hot.getFirst().get("merchant_id"))
            || ((Number) hot.getFirst().get("logical_bucket")).intValue() != bucket
            || !payload.equals(hot.getFirst().get("payload"))
            || !payloadHash.equals(hot.getFirst().get("payload_hash")))
          throw conflict("归档事件与当前权威原文不一致");
        rowIds.put(fact.factId(), (String) hot.getFirst().get("id"));
        if (prior == null) {
          missing.add(fact);
          continue;
        }
        if (!merchant.equals(prior.get("merchant_id"))
            || !payment.equals(prior.get("payment_id"))
            || ((Number) prior.get("logical_bucket")).intValue() != bucket
            || prior.get("event_payload_hash") != null
                && !payloadHash.equals(prior.get("event_payload_hash"))
            || prior.get("event_row_id") != null
                && !hot.getFirst().get("id").equals(prior.get("event_row_id"))
            || !"WARM_COPY".equals(prior.get("state"))) throw conflict("事件号已绑定到不同归档事实");
        if (!fact.payloadHash().equals(prior.get("payload_hash"))) {
          if (!allowRepoint
              || prior.get("event_payload_hash") == null
              || jdbc.update(
                      "UPDATE v3_history_event_locators SET archive_id=?,"
                          + "payload_hash=? WHERE event_id=? AND state='WARM_COPY' "
                          + "AND payload_hash=? AND event_payload_hash=?",
                      archiveId,
                      fact.payloadHash(),
                      fact.factId(),
                      prior.get("payload_hash"),
                      payloadHash)
                  != 1) throw conflict("事件号已绑定到不同归档事实");
        } else if (prior.get("event_payload_hash") == null || prior.get("event_row_id") == null) {
          if (jdbc.update(
                  "UPDATE v3_history_event_locators SET event_payload_hash=?,"
                      + "event_row_id=? WHERE event_id=? AND state='WARM_COPY'",
                  payloadHash,
                  hot.getFirst().get("id"),
                  fact.factId())
              != 1) throw conflict("事件号负载摘要回填失败");
        }
      }
      if (!missing.isEmpty())
        jdbc.batchUpdate(
            "INSERT INTO v3_history_event_locators(event_id,merchant_id,payment_id,"
                + "logical_bucket,archive_id,payload_hash,event_payload_hash,event_row_id,"
                + "created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            missing,
            500,
            (statement, fact) -> {
              statement.setString(1, fact.factId());
              statement.setString(2, merchant);
              statement.setString(3, payment);
              statement.setInt(4, bucket);
              statement.setString(5, archiveId);
              statement.setString(6, fact.payloadHash());
              var archived = json.readTree(fact.payloadJson());
              statement.setString(7, archived.get("payloadHash").asString());
              statement.setString(8, rowIds.get(fact.factId()));
              statement.setTimestamp(9, now);
            });
    }
  }

  private Archive find(String merchant, String payment, int physical, String sourceHash) {
    var jdbc = new JdbcTemplate(gate.source(physical));
    var rows =
        jdbc.query(
            "SELECT * FROM v3_history_archives WHERE merchant_id=? AND payment_id=? AND source_hash=?",
            (rs, ignored) -> mapped(rs),
            merchant,
            payment,
            sourceHash);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static Archive mapped(java.sql.ResultSet row) throws SQLException {
    return new Archive(
        row.getString("id"),
        row.getString("merchant_id"),
        row.getString("payment_id"),
        row.getInt("logical_bucket"),
        row.getInt("physical_shard"),
        row.getLong("route_epoch"),
        row.getString("source_hash"),
        row.getString("object_hash"),
        row.getString("object_key"),
        row.getLong("fact_count"),
        row.getLong("object_bytes"),
        row.getString("archived_by"),
        row.getString("reason"),
        row.getTimestamp("created_at").toInstant(),
        "WARM_COPY");
  }

  static String sourceHash(List<HistoryParquetCodec.Fact> facts) {
    var witness = new StringBuilder();
    for (var fact : facts)
      witness
          .append(fact.kind())
          .append('\0')
          .append(fact.factId())
          .append('\0')
          .append(fact.payloadHash())
          .append('\n');
    return Signatures.sha256(witness.toString());
  }

  private static ApiException conflict(String message) {
    return new ApiException("HISTORY_ARCHIVE_CONFLICT", 409, message);
  }
}
