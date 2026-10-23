package dev.merchantpay.platform.reconciliation;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.query.Queries;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExportService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final OutboxService outbox;
  private final Queries queries;
  private final Clock clock;
  private final ObjectStorage storage;
  private final TaskQueue queue;
  private static final Map<String, String> TABLES =
      Map.of("payments", "payments", "refunds", "refunds", "journals", "ledger_journals");
  private static final Map<String, String> COLUMNS =
      Map.of(
          "payments",
          "id,merchant_id,merchant_order_no,amount_minor,fee_minor,currency,status,refunded_minor,channel,channel_reference,created_at,updated_at",
          "refunds",
          "id,payment_id,merchant_id,merchant_refund_no,amount_minor,refunded_fee_minor,status,channel,channel_reference,created_at,updated_at",
          "journals",
          "id,event_id,payment_id,merchant_id,source_type,source_id,currency,created_at");

  public ExportService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      OutboxService outbox,
      Queries queries,
      Clock clock,
      @Value("${merchantpay.data-directory}") String directory,
      TaskQueue queue) {
    this(jdbc, tx, outbox, queries, clock, new ObjectStorage(directory), queue);
  }

  @Autowired
  public ExportService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      OutboxService outbox,
      Queries queries,
      Clock clock,
      ObjectStorage storage,
      TaskQueue queue) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.outbox = outbox;
    this.queries = queries;
    this.clock = clock;
    this.storage = storage;
    this.queue = queue;
  }

  public String create(
      String merchant, String requester, String resource, String from, String until) {
    if (merchant == null
        || requester == null
        || requester.isBlank()
        || requester.length() > 160
        || !TABLES.containsKey(resource)) {
      throw new ApiException("INVALID_EXPORT", 400, "导出必须选择商户和支付、退款或凭证资源");
    }
    var range = queries.range(from, until, 365);
    return tx.execute(
        transaction -> {
          if (jdbc.queryForList("SELECT id FROM merchants WHERE id=?", merchant).isEmpty()) {
            throw new ApiException("MERCHANT_NOT_FOUND", 404, "商户不存在");
          }
          String id = UUID.randomUUID().toString();
          var now = Timestamp.from(clock.instant());
          jdbc.update(
              "INSERT INTO exports(id,merchant_id,resource,from_time,until_time,status,created_at,updated_at,requested_by,expires_at) VALUES (?,?,?,?,?,'QUEUED',?,?,?,?)",
              id,
              merchant,
              resource,
              Timestamp.from(range.from()),
              Timestamp.from(range.until()),
              now,
              now,
              requester,
              Timestamp.from(clock.instant().plus(Duration.ofHours(24))));
          outbox.enqueue("export:" + id, "EXPORT", id, merchant);
          return id;
        });
  }

  public void process(String id, TaskQueue.Task task) {
    var job = job(id, null);
    if (job.get("status").equals("COMPLETE")) {
      return;
    }
    Path temporary = null;
    try {
      if (!queue.begin(task)) return;
      temporary = storage.temporary(id + "-");
      queue.fenced(
          task,
          () ->
              jdbc.update(
                  "UPDATE exports SET status='RUNNING',row_count=0,error_detail=NULL,updated_at=? WHERE id=?",
                  Timestamp.from(clock.instant()),
                  id));
      long count = 0;
      int parts = 1;
      Instant snapshotAt = clock.instant();
      String resource = (String) job.get("resource");
      try (var zip =
              new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)));
          var connection = jdbc.getDataSource().getConnection()) {
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        String sql =
            "SELECT "
                + COLUMNS.get(resource)
                + " FROM "
                + TABLES.get(resource)
                + " WHERE merchant_id=? AND created_at>=? AND created_at<? ORDER BY created_at,id";
        try (var statement =
            connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
          statement.setString(1, (String) job.get("merchant_id"));
          statement.setTimestamp(2, (Timestamp) job.get("from_time"));
          statement.setTimestamp(3, (Timestamp) job.get("until_time"));
          statement.setFetchSize(
              connection.getMetaData().getURL().startsWith("jdbc:mysql:")
                  ? Integer.MIN_VALUE
                  : 1000);
          try (var rows = statement.executeQuery()) {
            var writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            String header = COLUMNS.get(resource) + "\n";
            zip.putNextEntry(new ZipEntry(resource + "-001.csv"));
            writer.write(header);
            while (rows.next()) {
              if (count > 0 && count % 1_000_000 == 0) {
                writer.flush();
                zip.closeEntry();
                parts++;
                zip.putNextEntry(
                    new ZipEntry(
                        resource + "-" + String.format(Locale.ROOT, "%03d", parts) + ".csv"));
                writer.write(header);
              }
              for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                if (column > 1) {
                  writer.write(',');
                }
                writer.write(Csv.cell(rows.getString(column)));
              }
              writer.write('\n');
              count++;
              if (count % 1000 == 0) {
                long progress = count;
                queue.fenced(
                    task,
                    () ->
                        jdbc.update(
                            "UPDATE exports SET row_count=?,updated_at=? WHERE id=?",
                            progress,
                            Timestamp.from(clock.instant()),
                            id));
              }
            }
            writer.flush();
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            writer.write(
                "{\"exportId\":\""
                    + id
                    + "\",\"resource\":\""
                    + resource
                    + "\",\"rows\":"
                    + count
                    + ",\"parts\":"
                    + parts
                    + ",\"readSnapshotAt\":\""
                    + snapshotAt
                    + "\",\"moneyUnit\":\"minor\",\"currency\":\"CNY\"}");
            writer.flush();
            zip.closeEntry();
          }
        }
        connection.commit();
      }
      String storageKey = storage.publish(temporary, "exports/" + id + "-" + task.token() + ".zip");
      long finalCount = count;
      queue.fenced(
          task,
          () ->
              jdbc.update(
                  "UPDATE exports SET status='COMPLETE',storage_key=?,row_count=?,updated_at=? WHERE id=?",
                  storageKey,
                  finalCount,
                  Timestamp.from(clock.instant()),
                  id));
    } catch (TaskQueue.LeaseLost stale) {
      // A private attempt file may remain, but it is never the published pointer.
      return;
    } catch (Exception failure) {
      try {
        queue.fenced(
            task,
            () ->
                jdbc.update(
                    "UPDATE exports SET status='FAILED',error_detail=?,updated_at=? WHERE id=? AND status<>'COMPLETE'",
                    failure.getClass().getSimpleName(),
                    Timestamp.from(clock.instant()),
                    id));
      } catch (TaskQueue.LeaseLost stale) {
        return;
      }
      throw new IllegalStateException(
          "Export failed; a retry regenerates the complete archive", failure);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
      }
    }
  }

  private Map<String, Object> job(String id, String merchant) {
    var rows =
        merchant == null
            ? jdbc.queryForList("SELECT * FROM exports WHERE id=?", id)
            : jdbc.queryForList("SELECT * FROM exports WHERE id=? AND merchant_id=?", id, merchant);
    if (rows.isEmpty()) {
      throw new ApiException("EXPORT_NOT_FOUND", 404, "导出不存在");
    }
    return rows.getFirst();
  }

  private String authorizedKey(String id, String merchant, String requester) {
    var job = job(id, merchant);
    if (requester == null || !requester.equals(job.get("requested_by"))) {
      throw new ApiException("EXPORT_NOT_FOUND", 404, "导出不存在");
    }
    if (job.get("expires_at") == null
        || !((Timestamp) job.get("expires_at")).toInstant().isAfter(clock.instant())) {
      throw new ApiException("EXPORT_EXPIRED", 410, "导出链接已过期，请重新创建导出");
    }
    if (!job.get("status").equals("COMPLETE")) {
      throw new ApiException("EXPORT_NOT_READY", 409, "导出尚未完成");
    }
    String key = (String) job.get("storage_key");
    if (key == null || !key.matches("(s3:)?exports/[a-f0-9-]{36}-[a-f0-9-]{36}\\.zip")) {
      throw new IllegalStateException("Invalid stored export path");
    }
    return key;
  }

  public Path file(String id, String merchant, String requester) {
    return storage.localPath(authorizedKey(id, merchant, requester));
  }

  public InputStreamResource download(String id, String merchant, String requester)
      throws IOException {
    return storage.resource(authorizedKey(id, merchant, requester));
  }
}
