package dev.merchantpay.platform.projection;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.query.Queries;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit offline cutover: writers, consumer and projection worker must already be
 * stopped/drained.
 */
@Service
public class ProjectionPromotion {
  private final JdbcTemplate jdbc;
  private final ClickHouseReadModel clickhouse;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate tx;

  public ProjectionPromotion(
      JdbcTemplate jdbc,
      ClickHouseReadModel clickhouse,
      JsonMapper json,
      Clock clock,
      TransactionTemplate template) {
    this.jdbc = jdbc;
    this.clickhouse = clickhouse;
    this.json = json;
    this.clock = clock;
    tx = new TransactionTemplate(template.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(600);
  }

  public Map<String, Object> promote(
      String generation, boolean writersStopped, String reason, Actor actor) {
    EventDelivery.requireOperator(actor);
    if (!writersStopped || reason == null || reason.isBlank() || reason.length() > 1000)
      throw new ApiException("STOP_WRITERS_REQUIRED", 409, "先停止并排空所有写入、消费者和投影工作进程，再明确确认并填写原因");
    return tx.execute(
        t -> {
          var control = jdbc.queryForMap("SELECT * FROM projection_control WHERE id=1 FOR UPDATE");
          if (!generation.equals(control.get("building_generation"))) throw conflict("不是当前构建代");
          var metadata =
              jdbc.queryForMap(
                  "SELECT * FROM projection_generations WHERE id=? FOR UPDATE", generation);
          if (!Boolean.TRUE.equals(metadata.get("scan_complete"))) throw conflict("全量扫描尚未完成");
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
                  != 0) throw conflict("事件、投递或隔离尚未排空");
          long expected = jdbc.queryForObject("SELECT COUNT(*) FROM payments", Long.class),
              seen = 0;
          var digest = new StringBuilder();
          String afterMerchant = "";
          long started = System.nanoTime();
          while (true) {
            if (System.nanoTime() - started > java.util.concurrent.TimeUnit.MINUTES.toNanos(9))
              throw conflict("核对超过九分钟预算，未切换服务代");
            var merchants =
                jdbc.queryForList(
                    "SELECT id FROM merchants WHERE id>? ORDER BY id LIMIT 50",
                    String.class,
                    afterMerchant);
            if (merchants.isEmpty()) break;
            for (String merchant : merchants) {
              String after = "";
              while (true) {
                if (System.nanoTime() - started > java.util.concurrent.TimeUnit.MINUTES.toNanos(9))
                  throw conflict("核对超过九分钟预算，未切换服务代");
                var source =
                    jdbc.query(
                        "SELECT "
                            + ProjectionService.COLUMNS
                            + ",c.revision AS projection_revision FROM payments p LEFT JOIN projection_counters c ON c.payment_id=p.id AND c.generation=? WHERE p.merchant_id=? AND p.id>? ORDER BY p.id LIMIT 500",
                        Queries.ROW,
                        generation,
                        merchant,
                        after);
                var projected =
                    clickhouse.query(
                        "SELECT payment_id,snapshot_hash,toString(revision) AS revision FROM merchantpay.payment_projection FINAL WHERE generation={generation:String} AND merchant_id={merchant:String} AND payment_id>{after:String} ORDER BY payment_id LIMIT 500",
                        Map.of("generation", generation, "merchant", merchant, "after", after));
                if (source.size() != projected.size()) throw conflict("来源数量不一致：" + merchant);
                if (source.isEmpty()) break;
                for (int i = 0; i < source.size(); i++) {
                  var row = new LinkedHashMap<>(source.get(i));
                  Object revision = row.remove("projectionRevision");
                  var copy = projected.get(i);
                  String id = row.get("id").toString();
                  String hash = Signatures.sha256(json.writeValueAsString(row));
                  if (!id.equals(copy.get("payment_id")) || !hash.equals(copy.get("snapshot_hash")))
                    throw conflict("来源快照不一致：" + id);
                  if (revision == null || !revision.toString().equals(copy.get("revision")))
                    throw conflict("来源版本不一致：" + id);
                  digest.append(hash);
                  if (digest.length() > 65536) {
                    String folded = Signatures.sha256(digest.toString());
                    digest.setLength(0);
                    digest.append(folded);
                  }
                  seen++;
                }
                after = source.getLast().get("id").toString();
              }
            }
            afterMerchant = merchants.getLast();
          }
          var count =
              clickhouse.query(
                  "SELECT toString(count()) AS count FROM merchantpay.payment_projection FINAL WHERE generation={generation:String}",
                  Map.of("generation", generation));
          if (seen != expected
              || count.size() != 1
              || !Long.toString(expected).equals(count.getFirst().get("count")))
            throw conflict("全量数量或孤立投影核对失败");
          if (control.get("active_generation") != null)
            jdbc.update(
                "UPDATE projection_generations SET state='RETIRED' WHERE id=?",
                control.get("active_generation"));
          jdbc.update(
              "UPDATE projection_generations SET state='SERVING',activated_at=? WHERE id=?",
              Timestamp.from(clock.instant()),
              generation);
          jdbc.update(
              "UPDATE projection_control SET active_generation=?,building_generation=NULL,version=version+1 WHERE id=1",
              generation);
          String witness = Signatures.sha256(digest.toString());
          jdbc.update(
              "INSERT INTO audit_records(id,actor,operation,resource_id,detail,created_at) VALUES (?,?,'PROJECTION_PROMOTE',?,?,?)",
              UUID.randomUUID().toString(),
              actor.id(),
              generation,
              "count=" + seen + "; witness=" + witness + "; " + reason,
              Timestamp.from(clock.instant()));
          return Map.of(
              "generation",
              generation,
              "count",
              Long.toString(seen),
              "witness",
              witness,
              "state",
              "SERVING");
        });
  }

  private static ApiException conflict(String reason) {
    return new ApiException("PROJECTION_NOT_RECONCILED", 409, reason);
  }
}
