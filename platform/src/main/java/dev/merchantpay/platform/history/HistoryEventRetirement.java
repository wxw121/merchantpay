package dev.merchantpay.platform.history;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Retires one proven delivered hot event; payment, money, receipts and archive stay hot. */
@Service
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.history.archive-enabled",
      "merchantpay.history.retire-enabled"
    },
    havingValue = "true")
public final class HistoryEventRetirement {
  public record Result(String eventId, String paymentId, String state) {}

  private final HistoryArchiveService archives;
  private final ShardExportReadGate gate;
  private final JsonMapper json;
  private final Clock clock;

  public HistoryEventRetirement(
      HistoryArchiveService archives, ShardExportReadGate gate, JsonMapper json, Clock clock) {
    this.archives = archives;
    this.gate = gate;
    this.json = json;
    this.clock = clock;
  }

  public Result retire(
      String merchant,
      String payment,
      String eventId,
      String reason,
      boolean writersStopped,
      Actor actor) {
    actor.require("ADMIN", "OPS");
    if (actor.merchantId() != null && !actor.merchantId().isBlank())
      throw new ApiException("FORBIDDEN", 403, "仅平台运维可退役热事件");
    if (!writersStopped || reason == null || reason.isBlank() || reason.length() > 1000)
      throw conflict("热事件退役须停写并提供原因");
    int bucket = PaymentBuckets.forPaymentId(payment);
    var routeSnapshot = gate.capture();
    var route = routeSnapshot.routes().get(bucket);
    var source = gate.source(route.physical());
    var jdbc = new JdbcTemplate(source);
    var locator =
        jdbc.queryForList(
            "SELECT state FROM v3_history_event_locators "
                + "WHERE event_id=? AND merchant_id=? AND payment_id=? AND logical_bucket=?",
            eventId,
            merchant,
            payment,
            bucket);
    if (locator.size() != 1) throw conflict("须先写入并核验事件归档定位");
    if ("COLD".equals(locator.getFirst().get("state"))) {
      gate.validate(routeSnapshot);
      return new Result(eventId, payment, "COLD");
    }
    var fact = archives.readEvent(merchant, payment, eventId, actor);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setTimeout(300);
    var result =
        transaction.execute(
            status -> {
              var fence =
                  jdbc.queryForList(
                      "SELECT route_epoch,access_state FROM v3_bucket_fences "
                          + "WHERE bucket=? FOR UPDATE",
                      bucket);
              if (fence.size() != 1
                  || ((Number) fence.getFirst().get("route_epoch")).longValue() != route.epoch()
                  || !"ACTIVE".equals(fence.getFirst().get("access_state")))
                throw conflict("来源栅栏已变化");
              jdbc.execute(
                  (ConnectionCallback<Void>)
                      connection -> {
                        try {
                          EventTransportMaintenance.requireDrained(connection);
                        } catch (IllegalStateException pending) {
                          throw conflict("事件和业务任务尚未排空");
                        }
                        return null;
                      });
              var locked =
                  jdbc.queryForList(
                      "SELECT state,archive_id,payload_hash,event_payload_hash,"
                          + "event_row_id FROM "
                          + "v3_history_event_locators WHERE event_id=? AND merchant_id=? "
                          + "AND payment_id=? AND logical_bucket=? FOR UPDATE",
                      eventId,
                      merchant,
                      payment,
                      bucket);
              if (locked.size() != 1
                  || !"WARM_COPY".equals(locked.getFirst().get("state"))
                  || !fact.payloadHash().equals(locked.getFirst().get("payload_hash")))
                throw conflict("定位记录已变化");
              var hot =
                  jdbc.query(
                      "SELECT * FROM domain_events WHERE event_id=? FOR UPDATE",
                      Queries.ROW,
                      eventId);
              if (hot.size() != 1) throw conflict("热事件已缺失");
              if (!Objects.equals(
                      hot.getFirst().get("payloadHash"),
                      locked.getFirst().get("event_payload_hash"))
                  || !Objects.equals(
                      hot.getFirst().get("id"), locked.getFirst().get("event_row_id")))
                throw conflict("热事件负载摘要与归档定位不一致");
              try {
                if (!json.readTree(fact.payloadJson())
                    .equals(json.readTree(json.writeValueAsString(hot.getFirst()))))
                  throw conflict("热事件与归档事实不一致");
              } catch (RuntimeException invalid) {
                if (invalid instanceof ApiException) throw invalid;
                throw conflict("热事件归档原文无法解析");
              }
              var root =
                  jdbc.queryForList(
                      "SELECT status,updated_at FROM payments "
                          + "WHERE id=? AND merchant_id=? FOR UPDATE",
                      payment,
                      merchant);
              if (root.size() != 1
                  || !List.of("SUCCEEDED", "FAILED", "CLOSED")
                      .contains(root.getFirst().get("status"))
                  || ((Timestamp) root.getFirst().get("updated_at"))
                      .toInstant()
                      .isAfter(clock.instant().minus(Duration.ofDays(30)))
                  || count(
                          jdbc,
                          "SELECT COUNT(*) FROM refunds WHERE payment_id=? AND "
                              + "status NOT IN ('SUCCEEDED','FAILED')",
                          payment)
                      != 0) throw conflict("支付根或退款未达到30天终态");
              if (count(
                          jdbc,
                          "SELECT COUNT(*) FROM event_receipts WHERE consumer_name=? "
                              + "AND event_id=? AND payload_hash=?",
                          EventDelivery.CONSUMER,
                          eventId,
                          hot.getFirst().get("payloadHash"))
                      != 1
                  || count(
                          jdbc,
                          "SELECT COUNT(*) FROM projection_receipts WHERE event_id=? "
                              + "AND payload_hash=?",
                          eventId,
                          hot.getFirst().get("payloadHash"))
                      != 1
                  || count(jdbc, "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'")
                      != 0
                  || count(
                          jdbc,
                          "SELECT COUNT(*) FROM projection_errors WHERE state<>'REPLAYED' "
                              + "AND payload_hash=?",
                          hot.getFirst().get("payloadHash"))
                      != 0) throw conflict("事件消费或投影回执未完整保留");
              if (jdbc.update(
                          "DELETE FROM domain_events WHERE event_id=? AND id=?",
                          eventId,
                          hot.getFirst().get("id"))
                      != 1
                  || jdbc.update(
                          "UPDATE v3_history_event_locators SET state='COLD',"
                              + "retired_at=?,retired_by=?,retire_reason=? WHERE event_id=? "
                              + "AND state='WARM_COPY'",
                          Timestamp.from(clock.instant()),
                          actor.id(),
                          reason,
                          eventId)
                      != 1) throw conflict("热事件删除与冷定位提交失败");
              jdbc.update(
                  "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,"
                      + "detail,created_at) VALUES(?,?,?,'V3_HISTORY_EVENT_RETIRE',?,?,?)",
                  UUID.randomUUID().toString(),
                  merchant,
                  actor.id(),
                  eventId,
                  reason,
                  Timestamp.from(clock.instant()));
              return new Result(eventId, payment, "COLD");
            });
    gate.validate(routeSnapshot);
    return Objects.requireNonNull(result);
  }

  private static long count(JdbcTemplate jdbc, String sql, Object... args) {
    return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
  }

  private static ApiException conflict(String message) {
    return new ApiException("HISTORY_EVENT_RETIRE_CONFLICT", 409, message);
  }
}
