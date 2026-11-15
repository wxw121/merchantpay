package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import dev.merchantpay.platform.query.Queries;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Stopped-writer, four-source reconciliation and resumable generation promotion. */
public final class ShardProjectionPromotion {
  private static final String PAYMENT_COLUMNS =
      "id,merchant_id,merchant_order_no,amount_minor,fee_minor,currency,status,refunded_minor,reserved_minor,refunded_fee_minor,channel,channel_reference,notification_url,created_at,updated_at,succeeded_at";

  private record Source(Connection connection, JdbcTemplate jdbc, String active, boolean serving) {}

  private record Located(int physical, Map<String, Object> row) {}

  private final Map<Integer, DataSource> databases;
  private final ClickHouseReadModel clickhouse;
  private final JsonMapper json;
  private final Clock clock;

  public ShardProjectionPromotion(
      Map<Integer, DataSource> databases,
      ClickHouseReadModel clickhouse,
      JsonMapper json,
      Clock clock) {
    if (databases == null || !databases.keySet().equals(Set.of(0, 1, 2, 3)))
      throw new IllegalArgumentException("Four projection sources are required");
    this.databases = Map.copyOf(databases);
    this.clickhouse = clickhouse;
    this.json = json;
    this.clock = clock;
  }

  public Map<String, Object> promote(
      String generation, boolean writersStopped, String reason, Actor actor) {
    EventDelivery.requireOperator(actor);
    if (!writersStopped
        || generation == null
        || !generation.matches("[A-Za-z0-9_-]{1,36}")
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000) throw conflict("先停写、排空消费者与投影worker，并提供有效代和原因");
    var opened = new ArrayList<Connection>();
    var sources = new HashMap<Integer, Source>();
    var finished = Collections.newSetFromMap(new IdentityHashMap<Connection, Boolean>());
    boolean commitStarted = false;
    try {
      for (int id = 0; id < 4; id++) {
        var connection = databases.get(id).getConnection();
        opened.add(connection);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.setQueryTimeout(600);
        sources.put(id, inspect(jdbc, connection, id, generation));
      }
      var proof = reconcile(sources, generation);
      for (int id = 0; id < 4; id++) {
        var source = sources.get(id);
        if (source.serving()) continue;
        var jdbc = source.jdbc();
        if (source.active() != null)
          jdbc.update(
              "UPDATE projection_generations SET state='RETIRED' WHERE id=? AND state='SERVING'",
              source.active());
        jdbc.update(
            "UPDATE projection_generations SET state='SERVING',activated_at=? WHERE id=? AND state='BUILDING'",
            Timestamp.from(clock.instant()),
            generation);
        jdbc.update(
            "UPDATE projection_control SET active_generation=?,building_generation=NULL,version=version+1 WHERE id=1 AND building_generation=?",
            generation,
            generation);
        jdbc.update(
            "INSERT INTO audit_records(id,actor,operation,resource_id,detail,created_at) VALUES (?,?,'PROJECTION_SHARD_PROMOTE',?,?,?)",
            UUID.randomUUID().toString(),
            actor.id(),
            generation,
            "count=" + proof.count() + "; witness=" + proof.witness() + "; " + reason,
            Timestamp.from(clock.instant()));
      }
      for (int id : List.of(1, 2, 3, 0)) {
        commitStarted = true;
        var connection = sources.get(id).connection();
        connection.commit();
        finished.add(connection);
      }
      return Map.of(
          "generation",
          generation,
          "count",
          Long.toString(proof.count()),
          "witness",
          proof.witness(),
          "state",
          "SERVING");
    } catch (SQLException | RuntimeException failure) {
      for (var connection : opened) {
        if (finished.contains(connection)) continue;
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      if (commitStarted)
        throw new IllegalStateException(
            "Projection promotion may be partial; keep writers stopped and retry the same generation",
            failure);
      throw failure instanceof RuntimeException runtime
          ? runtime
          : new IllegalStateException("Projection promotion failed", failure);
    } finally {
      for (var connection : opened) {
        try {
          connection.close();
        } catch (SQLException ignored) {
          /* Original failure is retained. */
        }
      }
    }
  }

  private Source inspect(
      JdbcTemplate jdbc, Connection connection, int physical, String generation) {
    Integer identity =
        jdbc.queryForObject(
            "SELECT physical_shard FROM v3_shard_identity WHERE id=1 FOR UPDATE", Integer.class);
    if (!Objects.equals(identity, physical)) throw conflict("投影来源物理身份不符：" + physical);
    String mode =
        jdbc.queryForObject("SELECT mode FROM event_transport WHERE id=1 FOR UPDATE", String.class);
    if (!"CDC".equals(mode)) throw conflict("事件来源尚非CDC：" + physical);
    var control =
        jdbc.queryForMap(
            "SELECT active_generation,building_generation FROM projection_control WHERE id=1 FOR UPDATE");
    String active = (String) control.get("active_generation");
    String building = (String) control.get("building_generation");
    boolean serving = generation.equals(active) && building == null;
    if (!serving && !generation.equals(building)) throw conflict("构建代不一致：" + physical);
    if (active != null
        && !"SERVING"
            .equals(
                jdbc.queryForObject(
                    "SELECT state FROM projection_generations WHERE id=? FOR UPDATE",
                    String.class,
                    active))) throw conflict("原服务代状态不符：" + physical);
    String state =
        jdbc.queryForObject(
            "SELECT state FROM projection_generations WHERE id=? FOR UPDATE",
            String.class,
            generation);
    if (!Objects.equals(state, serving ? "SERVING" : "BUILDING"))
      throw conflict("目标代状态不符：" + physical);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT scan_complete FROM projection_generations WHERE id=?",
            Boolean.class,
            generation))) throw conflict("全量扫描尚未完成：" + physical);
    if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM projection_batches WHERE generation=? AND state<>'DONE'",
                Long.class,
                generation)
            != 0
        || jdbc.queryForObject(
                "SELECT COUNT(*) FROM projection_errors WHERE state NOT IN ('REPLAYED','DISMISSED')",
                Long.class)
            != 0
        || jdbc.queryForObject(
                "SELECT COUNT(*) FROM domain_events d WHERE NOT EXISTS (SELECT 1 FROM projection_receipts r WHERE r.event_id=d.event_id)",
                Long.class)
            != 0) throw conflict("事件、批次或隔离尚未排空：" + physical);
    return new Source(connection, jdbc, active, serving);
  }

  private record Proof(long count, String witness) {}

  private Proof reconcile(Map<Integer, Source> sources, String generation) {
    var routes =
        sources
            .get(0)
            .jdbc()
            .query(
                "SELECT bucket,physical_shard FROM v3_shard_routes ORDER BY bucket FOR UPDATE",
                (rs, row) -> new int[] {rs.getInt(1), rs.getInt(2)});
    if (routes.size() != 16) throw conflict("分桶目录不完整");
    for (int bucket = 0; bucket < 16; bucket++)
      if (routes.get(bucket)[0] != bucket || routes.get(bucket)[1] < 0 || routes.get(bucket)[1] > 3)
        throw conflict("分桶目录无效");
    long expected = 0, seen = 0;
    for (int id = 0; id < 4; id++)
      expected +=
          sources.get(id).jdbc().queryForObject("SELECT COUNT(*) FROM payments", Long.class);
    String after = "";
    var digest = new StringBuilder();
    long started = System.nanoTime();
    while (true) {
      if (System.nanoTime() - started > TimeUnit.MINUTES.toNanos(9))
        throw conflict("四库快照核对超过九分钟预算");
      var all = new ArrayList<Located>();
      for (int id = 0; id < 4; id++) {
        int physical = id;
        for (var row :
            sources
                .get(id)
                .jdbc()
                .query(
                    "SELECT "
                        + PAYMENT_COLUMNS
                        + ",c.revision AS projection_revision FROM payments p LEFT JOIN projection_counters c ON c.payment_id=p.id AND c.generation=? WHERE p.id>? ORDER BY p.id LIMIT 500",
                    Queries.ROW,
                    generation,
                    after)) all.add(new Located(physical, row));
      }
      all.sort(Comparator.comparing(located -> located.row().get("id").toString()));
      var page = all.subList(0, Math.min(500, all.size()));
      if (all.size() > page.size()
          && all.get(page.size()).row().get("id").equals(page.getLast().row().get("id")))
        throw conflict("支付ID跨来源重复：" + page.getLast().row().get("id"));
      var projected =
          clickhouse.query(
              "SELECT payment_id,snapshot_hash,toString(revision) AS revision FROM merchantpay.payment_projection FINAL WHERE generation={generation:String} AND payment_id>{after:String} ORDER BY payment_id LIMIT 500",
              Map.of("generation", generation, "after", after));
      if (page.size() != projected.size()) throw conflict("四库来源与全局投影数量不一致");
      if (page.isEmpty()) break;
      String previousId = null;
      for (int i = 0; i < page.size(); i++) {
        var row = new LinkedHashMap<>(page.get(i).row());
        Object revision = row.remove("projectionRevision");
        var copy = projected.get(i);
        String id = row.get("id").toString();
        if (id.equals(previousId)) throw conflict("支付ID跨来源重复：" + id);
        previousId = id;
        String hash = Signatures.sha256(json.writeValueAsString(row));
        if (!id.equals(copy.get("payment_id"))
            || !hash.equals(copy.get("snapshot_hash"))
            || revision == null
            || !revision.toString().equals(copy.get("revision")))
          throw conflict("来源快照或版本不一致：" + id);
        int bucket;
        try {
          bucket = PaymentBuckets.forPaymentId(id);
        } catch (ApiException invalid) {
          throw conflict("来源支付号无法分桶：" + id);
        }
        if (routes.get(bucket)[1] != page.get(i).physical()) throw conflict("支付来源与桶归属不一致：" + id);
        digest.append(hash);
        if (digest.length() > 65536) {
          String folded = Signatures.sha256(digest.toString());
          digest.setLength(0);
          digest.append(folded);
        }
        seen++;
      }
      after = page.getLast().row().get("id").toString();
    }
    var count =
        clickhouse.query(
            "SELECT toString(count()) AS count FROM merchantpay.payment_projection FINAL WHERE generation={generation:String}",
            Map.of("generation", generation));
    if (seen != expected
        || count.size() != 1
        || !Long.toString(expected).equals(count.getFirst().get("count")))
      throw conflict("全局总量或孤立投影核对失败");
    return new Proof(seen, Signatures.sha256(digest.toString()));
  }

  private static ApiException conflict(String reason) {
    return new ApiException("PROJECTION_NOT_RECONCILED", 409, reason);
  }
}
