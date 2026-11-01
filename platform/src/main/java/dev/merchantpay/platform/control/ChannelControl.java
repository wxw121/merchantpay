package dev.merchantpay.platform.control;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.query.Queries;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable cross-instance admission; leases/circuit decisions never change financial state. */
@Service
public class ChannelControl {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean enabled, sandbox;

  public ChannelControl(
      JdbcTemplate jdbc,
      TransactionTemplate template,
      Clock clock,
      @Value("${merchantpay.channel-controls-enabled:false}") boolean enabled,
      @Value("${merchantpay.alipay.enabled:false}") boolean sandbox) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.enabled = enabled;
    this.sandbox = sandbox;
    tx = new TransactionTemplate(template.getTransactionManager());
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(3);
  }

  public boolean routingEnabled() {
    return enabled;
  }

  public boolean supports(String name) {
    return Set.of("MOCK_A", "MOCK_B").contains(Objects.toString(name, ""))
        || (sandbox && "ALIPAY_SANDBOX".equals(name));
  }

  public String route(String merchant, String requested) {
    if (!enabled) {
      if (!supports(requested)) throw new ApiException("INVALID_CHANNEL", 400, "渠道未启用");
      return requested;
    }
    var routes =
        jdbc.queryForList(
            "SELECT r.channel,r.enabled AS route_enabled,c.enabled,c.open_until FROM merchant_channel_routes r JOIN channel_controls c ON c.channel=r.channel WHERE r.merchant_id=? ORDER BY r.priority,r.channel",
            merchant);
    if (!"AUTO".equals(requested)) {
      if (!supports(requested)
          || !routes.isEmpty()
              && routes.stream()
                  .noneMatch(
                      r ->
                          requested.equals(r.get("channel"))
                              && Boolean.TRUE.equals(r.get("route_enabled"))))
        throw new ApiException("CHANNEL_NOT_ALLOWED", 403, "该商户未配置此渠道");
      var state =
          jdbc.queryForMap(
              "SELECT enabled,open_until FROM channel_controls WHERE channel=?", requested);
      if (!Boolean.TRUE.equals(state.get("enabled"))
          || state.get("open_until") != null
              && ((Timestamp) state.get("open_until")).toInstant().isAfter(clock.instant()))
        throw unavailable("所选渠道暂不可受理新支付");
      return requested;
    }
    for (var row : routes)
      if (supports((String) row.get("channel"))
          && Boolean.TRUE.equals(row.get("route_enabled"))
          && Boolean.TRUE.equals(row.get("enabled"))
          && (row.get("open_until") == null
              || !((Timestamp) row.get("open_until")).toInstant().isAfter(clock.instant())))
        return (String) row.get("channel");
    throw unavailable("没有可受理的新支付渠道，请使用相同业务号退避重试");
  }

  public record Lease(String token, String channel, boolean probe, long circuitVersion) {}

  public Lease enter(String channel) {
    if (!enabled) return new Lease(null, channel, false, 0);
    return tx.execute(
        t -> {
          var row =
              jdbc.queryForMap(
                  "SELECT * FROM channel_controls WHERE channel=? FOR UPDATE", channel);
          var now = clock.instant();
          if (!Boolean.TRUE.equals(row.get("enabled"))) throw unavailable("渠道已暂停");
          boolean probe = row.get("open_until") != null;
          if (probe && ((Timestamp) row.get("open_until")).toInstant().isAfter(now))
            throw unavailable("渠道熔断，保留原渠道等待恢复");
          if (probe
              && row.get("probe_until") != null
              && ((Timestamp) row.get("probe_until")).toInstant().isAfter(now))
            throw unavailable("渠道正在半开探测");
          jdbc.update(
              "DELETE FROM channel_leases WHERE channel=? AND expires_at<=?",
              channel,
              Timestamp.from(now));
          long active =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM channel_leases WHERE channel=?", Long.class, channel);
          if (active >= ((Number) row.get("max_inflight")).intValue())
            throw unavailable("渠道并发额度已满");
          long second = now.getEpochSecond();
          int used =
              ((Number) row.get("window_second")).longValue() == second
                  ? ((Number) row.get("window_used")).intValue()
                  : 0;
          if (used >= ((Number) row.get("max_per_second")).intValue())
            throw unavailable("渠道速率额度已满");
          String token = UUID.randomUUID().toString();
          Timestamp expiry = Timestamp.from(now.plusSeconds(30));
          jdbc.update(
              "UPDATE channel_controls SET window_second=?,window_used=?,probe_token=?,probe_until=?,updated_at=? WHERE channel=?",
              second,
              used + 1,
              probe ? token : row.get("probe_token"),
              probe ? expiry : row.get("probe_until"),
              Timestamp.from(now),
              channel);
          jdbc.update(
              "INSERT INTO channel_leases(token,channel,expires_at,probe) VALUES (?,?,?,?)",
              token,
              channel,
              expiry,
              probe);
          return new Lease(token, channel, probe, ((Number) row.get("version")).longValue());
        });
  }

  public void leave(Lease lease, boolean healthy) {
    if (lease.token() == null) return;
    tx.executeWithoutResult(
        t -> {
          var row =
              jdbc.queryForMap(
                  "SELECT * FROM channel_controls WHERE channel=? FOR UPDATE", lease.channel());
          int removed =
              jdbc.update(
                  "DELETE FROM channel_leases WHERE token=? AND channel=?",
                  lease.token(),
                  lease.channel());
          if (removed != 1 || ((Number) row.get("version")).longValue() != lease.circuitVersion())
            return;
          if (lease.probe() && !lease.token().equals(row.get("probe_token"))) return;
          // A late healthy call from before opening cannot close a newer circuit.
          if (healthy) {
            if (lease.probe())
              jdbc.update(
                  "UPDATE channel_controls SET failures=0,open_until=NULL,probe_token=NULL,probe_until=NULL,version=version+1,updated_at=? WHERE channel=?",
                  Timestamp.from(clock.instant()),
                  lease.channel());
            else if (row.get("open_until") == null)
              jdbc.update(
                  "UPDATE channel_controls SET failures=0,updated_at=? WHERE channel=?",
                  Timestamp.from(clock.instant()),
                  lease.channel());
          } else {
            int failures = ((Number) row.get("failures")).intValue() + 1;
            if (lease.probe() || failures >= ((Number) row.get("failure_threshold")).intValue())
              jdbc.update(
                  "UPDATE channel_controls SET failures=?,open_until=?,probe_token=NULL,probe_until=NULL,version=version+1,updated_at=? WHERE channel=?",
                  failures,
                  Timestamp.from(
                      clock.instant().plusSeconds(((Number) row.get("open_seconds")).longValue())),
                  Timestamp.from(clock.instant()),
                  lease.channel());
            else
              jdbc.update(
                  "UPDATE channel_controls SET failures=?,updated_at=? WHERE channel=?",
                  failures,
                  Timestamp.from(clock.instant()),
                  lease.channel());
          }
        });
  }

  public List<Map<String, Object>> status(Actor actor) {
    actor.require("ADMIN", "OPS");
    if (actor.merchantId() != null && !actor.merchantId().isBlank())
      throw new ApiException("FORBIDDEN", 403, "需要平台运维范围");
    return jdbc.query(
        "SELECT c.*,(SELECT COUNT(*) FROM channel_leases l WHERE l.channel=c.channel AND l.expires_at>CURRENT_TIMESTAMP(6)) AS active FROM channel_controls c ORDER BY channel",
        Queries.ROW);
  }

  public void configure(
      String channel,
      long version,
      int inflight,
      int perSecond,
      int threshold,
      int openSeconds,
      boolean active,
      String reason,
      Actor actor) {
    actor.require("ADMIN", "OPS");
    if (actor.merchantId() != null && !actor.merchantId().isBlank())
      throw new ApiException("FORBIDDEN", 403, "需要平台运维范围");
    if (reason == null
        || reason.isBlank()
        || reason.length() > 1000
        || inflight < 1
        || inflight > 256
        || perSecond < 1
        || perSecond > 10000
        || threshold < 1
        || threshold > 100
        || openSeconds < 1
        || openSeconds > 600) throw new ApiException("INVALID_CONTROL", 400, "渠道控制参数或原因无效");
    tx.executeWithoutResult(
        t -> {
          int changed =
              jdbc.update(
                  "UPDATE channel_controls SET max_inflight=?,max_per_second=?,failure_threshold=?,open_seconds=?,enabled=?,version=version+1,updated_at=? WHERE channel=? AND version=?",
                  inflight,
                  perSecond,
                  threshold,
                  openSeconds,
                  active,
                  Timestamp.from(clock.instant()),
                  channel,
                  version);
          if (changed != 1) throw new ApiException("CONTROL_CONFLICT", 409, "渠道配置版本已变化");
          audit(null, actor, "CHANNEL_CONFIGURE", channel, reason);
        });
  }

  public List<Map<String, Object>> routes(String merchant, Actor actor) {
    actor.require("ADMIN", "OPS", "MERCHANT");
    String scope = actor.scope(merchant, true);
    return jdbc.query(
        "SELECT * FROM merchant_channel_routes WHERE merchant_id=? ORDER BY priority,channel",
        Queries.ROW,
        scope);
  }

  public void route(
      String merchant,
      String channel,
      long expected,
      int priority,
      boolean active,
      String reason,
      Actor actor) {
    actor.require("ADMIN", "OPS");
    String scope = actor.scope(merchant, true);
    if (!supports(channel)
        || priority < 0
        || priority > 1000
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000) throw new ApiException("INVALID_ROUTE", 400, "路由参数无效");
    tx.executeWithoutResult(
        t -> {
          if (jdbc.queryForList("SELECT id FROM merchants WHERE id=? FOR UPDATE", scope).isEmpty())
            throw new ApiException("NOT_FOUND", 404, "商户不存在");
          var rows =
              jdbc.queryForList(
                  "SELECT version FROM merchant_channel_routes WHERE merchant_id=? AND channel=? FOR UPDATE",
                  scope,
                  channel);
          if (rows.isEmpty()) {
            if (expected != 0) throw new ApiException("CONTROL_CONFLICT", 409, "路由版本已变化");
            jdbc.update(
                "INSERT INTO merchant_channel_routes(merchant_id,channel,priority,enabled,version,updated_at) VALUES (?,?,?,?,1,?)",
                scope,
                channel,
                priority,
                active,
                Timestamp.from(clock.instant()));
          } else {
            if (((Number) rows.getFirst().get("version")).longValue() != expected)
              throw new ApiException("CONTROL_CONFLICT", 409, "路由版本已变化");
            jdbc.update(
                "UPDATE merchant_channel_routes SET priority=?,enabled=?,version=version+1,updated_at=? WHERE merchant_id=? AND channel=?",
                priority,
                active,
                Timestamp.from(clock.instant()),
                scope,
                channel);
          }
          audit(scope, actor, "CHANNEL_ROUTE", channel, reason);
        });
  }

  private void audit(
      String merchant, Actor actor, String operation, String resource, String reason) {
    jdbc.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        merchant,
        actor.id(),
        operation,
        resource,
        reason,
        Timestamp.from(clock.instant()));
  }

  private static ApiException unavailable(String why) {
    return new ApiException("CHANNEL_BACKPRESSURE", 503, why);
  }
}
