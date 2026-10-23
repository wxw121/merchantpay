package dev.merchantpay.platform.query;

import dev.merchantpay.platform.common.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class Queries {
  public record Page(
      List<Map<String, Object>> items, String nextCursor, Instant from, Instant until) {}

  public record Range(Instant from, Instant until) {}

  public record EvidencePage(List<Map<String, Object>> items, String nextCursor) {}

  private record Table(String name, String columns) {}

  private static final Map<String, Table> TABLES =
      Map.ofEntries(
          Map.entry(
              "payments",
              new Table(
                  "payments",
                  "id,merchant_id,merchant_order_no,amount_minor,fee_minor,currency,status,refunded_minor,reserved_minor,channel,channel_reference,created_at,updated_at")),
          Map.entry(
              "refunds",
              new Table(
                  "refunds",
                  "id,payment_id,merchant_id,merchant_refund_no,amount_minor,refunded_fee_minor,status,channel,channel_reference,created_at,updated_at")),
          Map.entry(
              "journals",
              new Table(
                  "ledger_journals",
                  "id,event_id,payment_id,merchant_id,source_type,source_id,currency,created_at")),
          Map.entry(
              "tasks",
              new Table(
                  "outbox_tasks",
                  "id,event_id,kind,aggregate_id,merchant_id,status,attempts,due_at,lease_until,last_error,created_at,updated_at")),
          Map.entry(
              "audit",
              new Table(
                  "audit_records", "id,merchant_id,actor,operation,resource_id,detail,created_at")),
          Map.entry(
              "notifications",
              new Table(
                  "notification_deliveries",
                  "id,task_id,event_id,merchant_id,status,http_status,error_detail,created_at")),
          Map.entry(
              "conflicts",
              new Table(
                  "evidence_conflicts", "id,merchant_id,aggregate_id,channel,detail,created_at")),
          Map.entry(
              "batches",
              new Table(
                  "recon_batches",
                  "id,merchant_id,channel,business_day,version,status,expected_count,actual_count,expected_amount_minor,actual_amount_minor,processed_lines,error_detail,created_at,updated_at")),
          Map.entry(
              "exports",
              new Table(
                  "exports",
                  "id,merchant_id,resource,status,row_count,error_detail,created_at,updated_at,expires_at")));
  public static final RowMapper<Map<String, Object>> ROW =
      (rs, n) -> {
        Map<String, Object> values = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          String column = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
          StringBuilder camel = new StringBuilder();
          boolean upper = false;
          for (char character : column.toCharArray()) {
            if (character == '_') {
              upper = true;
            } else {
              camel.append(upper ? Character.toUpperCase(character) : character);
              upper = false;
            }
          }
          Object value = rs.getObject(i);
          if (column.endsWith("_minor") || column.equals("id") && value instanceof Number) {
            value = rs.getString(i);
          } else if (value instanceof Timestamp timestamp) {
            value = timestamp.toInstant().toString();
          }
          values.put(camel.toString(), value);
        }
        return values;
      };
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final String cursorKey;

  public Queries(
      JdbcTemplate jdbc, Clock clock, @Value("${merchantpay.master-key}") String cursorKey) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.cursorKey = cursorKey;
  }

  public Range range(String from, String until, int maximumDays) {
    Instant end, start;
    try {
      end = until == null ? clock.instant() : Instant.parse(until);
      start = from == null ? end.minus(Duration.ofDays(7)) : Instant.parse(from);
    } catch (DateTimeException invalid) {
      throw new ApiException("INVALID_RANGE", 400, "日期范围必须使用有效的 UTC 时间戳");
    }
    if (!start.isBefore(end)
        || Duration.between(start, end).compareTo(Duration.ofDays(maximumDays)) > 0) {
      throw new ApiException("RANGE_TOO_LARGE", 400, "在线查询最多 31 天；更长时间请创建导出任务");
    }
    return new Range(start, end);
  }

  public Page list(
      String resource,
      String merchant,
      String status,
      String from,
      String until,
      String cursor,
      String actor) {
    var table = TABLES.get(resource);
    if (table == null) {
      throw new ApiException("NOT_FOUND", 404, "资源不存在");
    }
    var range = range(from, until, 31);
    String cursorScope =
        scope(merchant, status)
            + (resource.equals("exports")
                ? ":" + Signatures.sha256(Objects.requireNonNull(actor))
                : "");
    // A cursor freezes the original time range, rather than shifting the default 'now' between
    // pages.
    String afterTime = null, afterId = null;
    if (cursor != null) {
      String raw;
      try {
        raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException invalid) {
        throw new ApiException("INVALID_CURSOR", 400, "分页游标无效");
      }
      var fields = raw.split("\\|", -1);
      if (fields.length != 8
          || !Signatures.constantEquals(
              Signatures.hmac(cursorKey, String.join("|", Arrays.copyOf(fields, 7))), fields[7])) {
        throw new ApiException("INVALID_CURSOR", 400, "分页游标无效");
      }
      if (Long.parseLong(fields[6]) < clock.instant().getEpochSecond()) {
        throw new ApiException("CURSOR_EXPIRED", 400, "分页游标已过期");
      }
      range = new Range(Instant.parse(fields[0]), Instant.parse(fields[1]));
      if (!fields[4].equals(resource) || !fields[5].equals(cursorScope)) {
        throw new ApiException("INVALID_CURSOR", 400, "分页游标与筛选条件不一致");
      }
      if (from != null && !range.from().equals(Instant.parse(from))
          || until != null && !range.until().equals(Instant.parse(until))) {
        throw new ApiException("INVALID_CURSOR", 400, "分页时间条件已变化");
      }
      afterTime = fields[2];
      afterId = fields[3];
    }
    var args = new ArrayList<Object>();
    var sql =
        new StringBuilder(
            "SELECT "
                + table.columns()
                + " FROM "
                + table.name()
                + " WHERE created_at>=? AND created_at<?");
    args.add(Timestamp.from(range.from()));
    args.add(Timestamp.from(range.until()));
    if (merchant != null) {
      sql.append(" AND merchant_id=?");
      args.add(merchant);
    }
    if (resource.equals("exports")) {
      sql.append(" AND requested_by=?");
      args.add(actor);
    }
    if (status != null && !status.isBlank()) {
      if (!Set.of("payments", "refunds", "tasks", "batches", "exports").contains(resource)
          || !status.matches("[A-Z_]{1,24}")) {
        throw new ApiException("INVALID_FILTER", 400, "状态筛选无效");
      }
      sql.append(" AND status=?");
      args.add(status);
    }
    if (afterTime != null) {
      sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
      var timestamp = Timestamp.from(Instant.parse(afterTime));
      args.add(timestamp);
      args.add(timestamp);
      args.add(afterId);
    }
    sql.append(" ORDER BY created_at DESC,id DESC LIMIT 51");
    var rows = jdbc.query(sql.toString(), ROW, args.toArray());
    String next = null;
    if (rows.size() > 50) {
      rows = new ArrayList<>(rows.subList(0, 50));
      var last = rows.getLast();
      String value =
          range.from()
              + "|"
              + range.until()
              + "|"
              + last.get("createdAt")
              + "|"
              + last.get("id")
              + "|"
              + resource
              + "|"
              + cursorScope
              + "|"
              + clock.instant().plusSeconds(3600).getEpochSecond();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (value + "|" + Signatures.hmac(cursorKey, value))
                      .getBytes(StandardCharsets.UTF_8));
    }
    return new Page(rows, next, range.from(), range.until());
  }

  private String scope(String merchant, String status) {
    return dev.merchantpay.platform.common.Signatures.sha256(
        Objects.toString(merchant, "") + "\0" + Objects.toString(status, ""));
  }

  public String encodeCursor(String scope, String value) {
    String payload = scope + "|" + value + "|" + clock.instant().plusSeconds(3600).getEpochSecond();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            (payload + "|" + Signatures.hmac(cursorKey, payload)).getBytes(StandardCharsets.UTF_8));
  }

  public String decodeCursor(String scope, String cursor) {
    try {
      var fields =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (fields.length != 4
          || !fields[0].equals(scope)
          || Long.parseLong(fields[2]) < clock.instant().getEpochSecond()
          || !Signatures.constantEquals(
              Signatures.hmac(cursorKey, String.join("|", Arrays.copyOf(fields, 3))), fields[3])) {
        throw new IllegalArgumentException();
      }
      return fields[1];
    } catch (IllegalArgumentException invalid) {
      throw new ApiException("INVALID_CURSOR", 400, "分页游标无效或已过期");
    }
  }

  public String paymentMerchant(String id, String scope) {
    var rows =
        scope == null
            ? jdbc.queryForList("SELECT merchant_id FROM payments WHERE id=?", String.class, id)
            : jdbc.queryForList(
                "SELECT merchant_id FROM payments WHERE id=? AND merchant_id=?",
                String.class,
                id,
                scope);
    if (rows.isEmpty()) {
      throw new ApiException("PAYMENT_NOT_FOUND", 404, "支付单不存在");
    }
    return rows.getFirst();
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(sql, ROW, args);
  }

  /** The caller resolves and authorizes the payment before reading its evidence snapshot. */
  public EvidencePage paymentEvidence(
      String payment, String merchant, String resource, String cursor) {
    if (!Set.of("refunds", "journals", "attempts", "tasks", "conflicts").contains(resource))
      throw new ApiException("NOT_FOUND", 404, "资源不存在");
    var table =
        resource.equals("attempts")
            ? new Table(
                "channel_attempts",
                "id,aggregate_id,payment_id,operation,channel,request_key,status,created_at,updated_at")
            : TABLES.get(resource);
    boolean indirect = resource.equals("tasks") || resource.equals("conflicts");
    var args = new ArrayList<Object>();
    String filter;
    if (indirect) {
      filter =
          "merchant_id=? AND (aggregate_id=? OR aggregate_id IN (SELECT id FROM refunds WHERE payment_id=?))";
      args.addAll(List.of(merchant, payment, payment));
    } else {
      filter = "payment_id=?";
      args.add(payment);
    }
    var sql =
        new StringBuilder(
            "SELECT " + table.columns() + " FROM " + table.name() + " WHERE " + filter);
    String scope =
        Signatures.sha256("payment-detail\0" + merchant + "\0" + payment + "\0" + resource);
    if (cursor != null) {
      var fields = decodeCursor(scope, cursor).split("~", -1);
      try {
        if (fields.length != 2 || !fields[1].matches("[a-zA-Z0-9_-]{1,64}"))
          throw new IllegalArgumentException();
        var time = Timestamp.from(Instant.parse(fields[0]));
        sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
        args.addAll(List.of(time, time, fields[1]));
      } catch (IllegalArgumentException | DateTimeException invalid) {
        throw new ApiException("INVALID_CURSOR", 400, "分页游标无效");
      }
    }
    sql.append(" ORDER BY created_at DESC,id DESC LIMIT 51");
    var items = rows(sql.toString(), args.toArray());
    String next = null;
    if (items.size() > 50) {
      items = new ArrayList<>(items.subList(0, 50));
      var last = items.getLast();
      next = encodeCursor(scope, last.get("createdAt") + "~" + last.get("id"));
    }
    return new EvidencePage(items, next);
  }

  public Map<String, Object> dashboard(String merchant) {
    Instant until = clock.instant(), from = until.minus(Duration.ofDays(7));
    String filter = merchant == null ? "" : " AND merchant_id=?";
    var args = new ArrayList<Object>(List.of(Timestamp.from(from), Timestamp.from(until)));
    if (merchant != null) {
      args.add(merchant);
    }
    var totals =
        rows(
                "SELECT COUNT(*) AS total_count,COALESCE(SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END),0) AS succeeded_count,COALESCE(SUM(CASE WHEN status='SUCCEEDED' THEN amount_minor ELSE 0 END),0) AS paid_minor,COALESCE(SUM(refunded_minor),0) AS refunded_minor,COALESCE(SUM(CASE WHEN status='UNKNOWN' THEN 1 ELSE 0 END),0) AS unknown_count FROM payments WHERE created_at>=? AND created_at<?"
                    + filter,
                args.toArray())
            .getFirst();
    var trend =
        rows(
            "SELECT DATE(created_at) AS day,COUNT(*) AS total_count,COALESCE(SUM(CASE WHEN status='SUCCEEDED' THEN amount_minor ELSE 0 END),0) AS paid_minor FROM payments WHERE created_at>=? AND created_at<?"
                + filter
                + " GROUP BY DATE(created_at) ORDER BY day",
            args.toArray());
    return Map.of(
        "totals",
        totals,
        "trend",
        trend,
        "from",
        from,
        "until",
        until,
        "source",
        "MOCK_CHANNELS",
        "businessZone",
        "Asia/Shanghai",
        "aggregationZone",
        "UTC");
  }
}
