package dev.merchantpay.platform.reconciliation;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.outbox.TaskQueue;
import java.io.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final OutboxService outbox;
  private final Clock clock;
  private final ObjectStorage storage;
  private final TaskQueue queue;

  private record BillRow(
      long line,
      String operation,
      String id,
      String status,
      long amount,
      long fee,
      String currency,
      String reference,
      String raw) {}

  private static final class InvalidBill extends RuntimeException {
    InvalidBill(String reason) {
      super(reason);
    }
  }

  public ReconciliationService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      OutboxService outbox,
      Clock clock,
      @Value("${merchantpay.data-directory}") String directory,
      TaskQueue queue) {
    this(jdbc, tx, outbox, clock, new ObjectStorage(directory), queue);
  }

  @Autowired
  public ReconciliationService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      OutboxService outbox,
      Clock clock,
      ObjectStorage storage,
      TaskQueue queue) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.outbox = outbox;
    this.clock = clock;
    this.storage = storage;
    this.queue = queue;
  }

  public String submit(
      String merchant, String channel, LocalDate day, int version, String digest, InputStream input)
      throws IOException {
    return submit(merchant, channel, day, version, digest, input, id -> {});
  }

  public String submit(
      String merchant,
      String channel,
      LocalDate day,
      int version,
      String digest,
      InputStream input,
      java.util.function.Consumer<String> audit)
      throws IOException {
    if (digest == null || !digest.matches("[a-f0-9]{64}")) {
      throw new ApiException("INVALID_BILL", 400, "上传必须声明 SHA-256");
    }
    return store(merchant, channel, day, version, digest, input, audit);
  }

  /**
   * Trusted, authenticated channel transport; the actual digest is still stored and revalidated.
   */
  public String submitFromChannel(
      String merchant, String channel, LocalDate day, int version, InputStream input)
      throws IOException {
    return submitFromChannel(merchant, channel, day, version, input, id -> {});
  }

  public String submitFromChannel(
      String merchant,
      String channel,
      LocalDate day,
      int version,
      InputStream input,
      java.util.function.Consumer<String> audit)
      throws IOException {
    return store(merchant, channel, day, version, null, input, audit);
  }

  private String store(
      String merchant,
      String channel,
      LocalDate day,
      int version,
      String digest,
      InputStream input,
      java.util.function.Consumer<String> audit)
      throws IOException {
    if (merchant == null
        || !("MOCK_A".equals(channel) || "MOCK_B".equals(channel))
        || day == null
        || version < 1) {
      throw new ApiException("INVALID_BILL", 400, "账单商户、渠道、日期、版本或 SHA-256 无效");
    }
    Path temporary = storage.temporary("upload-");
    String actual;
    String storageKey;
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      try (var output = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
          total += read;
          if (total > 128L * 1024 * 1024) {
            throw new ApiException("BILL_TOO_LARGE", 413, "单份账单最大 128 MiB");
          }
          hash.update(buffer, 0, read);
          output.write(buffer, 0, read);
        }
      }
      actual = HexFormat.of().formatHex(hash.digest());
      storageKey = storage.publish(temporary, "bills/" + actual + "-" + UUID.randomUUID() + ".csv");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return tx.execute(
        transaction -> {
          if (jdbc.queryForList("SELECT id FROM merchants WHERE id=? FOR UPDATE", merchant)
              .isEmpty()) {
            throw new ApiException("MERCHANT_NOT_FOUND", 404, "商户不存在");
          }
          var existing =
              jdbc.queryForList(
                  "SELECT id,sha256 FROM recon_batches WHERE merchant_id=? AND channel=? AND business_day=? AND version=?",
                  merchant,
                  channel,
                  java.sql.Date.valueOf(day),
                  version);
          if (!existing.isEmpty()) {
            if (!existing.getFirst().get("sha256").equals(actual)) {
              throw new ApiException("BILL_VERSION_CONFLICT", 409, "同一账单版本已有其他内容，请递增版本");
            }
            String id = (String) existing.getFirst().get("id");
            audit.accept(id);
            return id;
          }
          String id = UUID.randomUUID().toString();
          var now = Timestamp.from(clock.instant());
          boolean valid = digest == null || actual.equals(digest);
          jdbc.update(
              "INSERT INTO recon_batches(id,merchant_id,channel,business_day,version,sha256,storage_key,status,error_detail,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
              id,
              merchant,
              channel,
              java.sql.Date.valueOf(day),
              version,
              actual,
              storageKey,
              valid ? "QUEUED" : "QUARANTINED",
              valid ? null : "SHA-256 与声明不一致",
              now,
              now);
          if (valid) {
            outbox.enqueue("reconcile:" + id, "RECONCILE", id, merchant);
          }
          audit.accept(id);
          return id;
        });
  }

  public void process(String id, TaskQueue.Task task) {
    var batch = batch(id);
    if (Set.of("COMPLETE", "QUARANTINED").contains(batch.get("status"))) {
      return;
    }
    try {
      if (!queue.begin(task)) return;
      parse(id, batch, task);
      snapshot(id, batch, task);
      match(id, batch, task);
      queue.fenced(
          task,
          () -> {
            // Serialize finalized freeze publication with settlement execution, not payment
            // posting.
            jdbc.queryForList(
                "SELECT id FROM merchants WHERE id=? FOR UPDATE", batch.get("merchant_id"));
            jdbc.update(
                "UPDATE recon_batches SET status='COMPLETE',error_detail=NULL,updated_at=? WHERE id=?",
                Timestamp.from(clock.instant()),
                id);
          });
    } catch (TaskQueue.LeaseLost stale) {
      return;
    } catch (InvalidBill invalid) {
      try {
        queue.fenced(
            task,
            () ->
                jdbc.update(
                    "UPDATE recon_batches SET status='QUARANTINED',error_detail=?,updated_at=? WHERE id=? AND status<>'COMPLETE'",
                    invalid.getMessage(),
                    Timestamp.from(clock.instant()),
                    id));
      } catch (TaskQueue.LeaseLost stale) {
        return;
      }
    } catch (IOException failure) {
      fail(id, "读取原始账单失败", task);
      throw new IllegalStateException("Original bill unavailable", failure);
    } catch (RuntimeException failure) {
      fail(id, failure.getClass().getSimpleName(), task);
      throw failure;
    }
  }

  private void fail(String id, String reason, TaskQueue.Task task) {
    try {
      queue.fenced(
          task,
          () ->
              jdbc.update(
                  "UPDATE recon_batches SET status='FAILED',error_detail=?,updated_at=? WHERE id=? AND status<>'COMPLETE'",
                  reason,
                  Timestamp.from(clock.instant()),
                  id));
    } catch (TaskQueue.LeaseLost stale) {
      /* Lost executions cannot publish failure. */
    }
  }

  private Map<String, Object> batch(String id) {
    var rows = jdbc.queryForList("SELECT * FROM recon_batches WHERE id=?", id);
    if (rows.isEmpty()) {
      throw new ApiException("BILL_NOT_FOUND", 404, "账单批次不存在");
    }
    return rows.getFirst();
  }

  private String originalKey(Map<String, Object> batch) {
    String key = (String) batch.get("storage_key");
    if (!key.matches("(s3:)?bills/[a-f0-9]{64}(-[a-f0-9-]{36})?\\.csv")) {
      throw new IllegalStateException("Invalid stored bill key");
    }
    return key;
  }

  public Path original(String id, String merchant) {
    var batch = batch(id);
    if (merchant != null && !merchant.equals(batch.get("merchant_id"))) {
      throw new ApiException("BILL_NOT_FOUND", 404, "账单不存在");
    }
    return storage.localPath(originalKey(batch));
  }

  public InputStreamResource downloadOriginal(String id, String merchant) throws IOException {
    var batch = batch(id);
    if (merchant != null && !merchant.equals(batch.get("merchant_id"))) {
      throw new ApiException("BILL_NOT_FOUND", 404, "账单不存在");
    }
    return storage.resource(originalKey(batch));
  }

  private void parse(String id, Map<String, Object> batch, TaskQueue.Task task) throws IOException {
    queue.fenced(
        task,
        () ->
            jdbc.update(
                "UPDATE recon_batches SET status='VALIDATING',updated_at=? WHERE id=?",
                Timestamp.from(clock.instant()),
                id));
    // Recheck stored evidence, so a corrupted volume cannot silently change an accepted upload.
    try (var input = storage.open(originalKey(batch))) {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      if (!HexFormat.of().formatHex(digest.digest()).equals(batch.get("sha256"))) {
        throw new InvalidBill("原始文件摘要改变，已隔离");
      }
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                storage.open(originalKey(batch)),
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)))) {
      String header = line(reader);
      if (header == null) {
        throw new InvalidBill("空账单");
      }
      var fields = header.split(",", -1);
      if (fields.length != 6
          || !fields[0].equals("#merchantpay-bill-v1")
          || !fields[1].equals(batch.get("channel"))
          || !fields[2].equals(batch.get("merchant_id"))
          || !fields[3].equals(batch.get("business_day").toString())
          || !fields[4].matches("[0-9]{1,7}")
          || !fields[5].matches("[0-9]{1,16}")) {
        throw new InvalidBill("账单元数据不匹配");
      }
      long count = Long.parseLong(fields[4]), total = Long.parseLong(fields[5]);
      if (count > 1_000_000) {
        throw new InvalidBill("单份账单最多一百万行");
      }
      if (!"operation,aggregateId,status,amountMinor,feeMinor,currency,reference"
          .equals(line(reader))) {
        throw new InvalidBill("账单列格式错误");
      }
      queue.fenced(
          task,
          () ->
              jdbc.update(
                  "UPDATE recon_batches SET expected_count=?,expected_amount_minor=? WHERE id=?",
                  count,
                  total,
                  id));
      long checkpoint = ((Number) batch.get("processed_lines")).longValue(), number = 0;
      String raw;
      var chunk = new ArrayList<BillRow>(500);
      while ((raw = line(reader)) != null) {
        number++;
        if (number > 1_000_000) {
          throw new InvalidBill("账单行数超过上限");
        }
        if (number <= checkpoint) {
          continue;
        }
        var row = raw.split(",", -1);
        if (row.length != 7
            || !Set.of("PAYMENT", "REFUND").contains(row[0])
            || !row[1].matches("[a-zA-Z0-9_-]{1,64}")
            || !Set.of("SUCCEEDED", "FAILED", "PENDING", "CLOSED", "UNKNOWN").contains(row[2])
            || !row[3].matches("[1-9][0-9]{0,9}")
            || !row[4].matches("[0-9]{1,10}")
            || !row[5].equals("CNY")
            || !row[6].matches("[a-zA-Z0-9:_-]{0,128}")) {
          throw new InvalidBill("第 " + number + " 行格式错误；原始行已保留");
        }
        long amount = Long.parseLong(row[3]), fee = Long.parseLong(row[4]);
        if (amount > 1_000_000_000L || fee > amount) {
          throw new InvalidBill("第 " + number + " 行金额超出范围");
        }
        chunk.add(new BillRow(number, row[0], row[1], row[2], amount, fee, row[5], row[6], raw));
        if (chunk.size() == 500) {
          persist(id, chunk, task);
          chunk.clear();
        }
      }
      if (!chunk.isEmpty()) {
        persist(id, chunk, task);
      }
      var current = batch(id);
      if (((Number) current.get("actual_count")).longValue() != count
          || ((Number) current.get("actual_amount_minor")).longValue() != total) {
        throw new InvalidBill("账单总笔数或总金额不一致");
      }
    } catch (java.nio.charset.CharacterCodingException invalidEncoding) {
      throw new InvalidBill("账单不是有效的 UTF-8 编码，原始文件已隔离");
    }
  }

  private static String line(Reader reader) throws IOException {
    StringBuilder value = new StringBuilder();
    int character;
    while ((character = reader.read()) != -1) {
      if (character == '\n') {
        return value.toString();
      }
      if (character == '\r') {
        if (reader.read() != '\n') {
          throw new InvalidBill("账单包含孤立回车，原始文件已隔离");
        }
        return value.toString();
      }
      value.append((char) character);
      if (value.length() > 1024) {
        throw new InvalidBill("账单单行超过 1024 字符");
      }
    }
    return value.isEmpty() ? null : value.toString();
  }

  private void persist(String id, List<BillRow> rows, TaskQueue.Task task) {
    tx.executeWithoutResult(
        transaction -> {
          queue.assertOwned(task);
          long checkpoint =
              jdbc.queryForObject(
                  "SELECT processed_lines FROM recon_batches WHERE id=? FOR UPDATE",
                  Long.class,
                  id);
          long count = 0, total = 0;
          for (var row : rows) {
            if (row.line() <= checkpoint) {
              continue;
            }
            jdbc.update(
                "INSERT INTO recon_rows(batch_id,line_no,operation,aggregate_id,status,amount_minor,fee_minor,currency,reference,raw_line) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id,
                row.line(),
                row.operation(),
                row.id(),
                row.status(),
                row.amount(),
                row.fee(),
                row.currency(),
                row.reference(),
                row.raw());
            count++;
            total = Math.addExact(total, row.amount());
          }
          jdbc.update(
              "UPDATE recon_batches SET actual_count=actual_count+?,actual_amount_minor=actual_amount_minor+?,processed_lines=GREATEST(processed_lines,?),updated_at=? WHERE id=?",
              count,
              total,
              rows.getLast().line(),
              Timestamp.from(clock.instant()),
              id);
        });
  }

  private void snapshot(String id, Map<String, Object> batch, TaskQueue.Task task) {
    var snapshotTx = new TransactionTemplate(tx.getTransactionManager());
    snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshotTx.executeWithoutResult(
        transaction -> {
          queue.assertOwned(task);
          var locked =
              jdbc.queryForList("SELECT snapshot_at FROM recon_batches WHERE id=? FOR UPDATE", id)
                  .getFirst();
          if (locked.get("snapshot_at") != null) {
            return;
          }
          var day = LocalDate.parse(batch.get("business_day").toString());
          var zone = ZoneId.of("Asia/Shanghai");
          var from = Timestamp.from(day.atStartOfDay(zone).toInstant());
          var until = Timestamp.from(day.plusDays(1).atStartOfDay(zone).toInstant());
          jdbc.update(
              "INSERT INTO recon_local_rows(batch_id,operation,aggregate_id,status,amount_minor,fee_minor,currency,reference) SELECT ?,'PAYMENT',id,status,amount_minor,fee_minor,currency,channel_reference FROM payments WHERE merchant_id=? AND channel=? AND created_at>=? AND created_at<?",
              id,
              batch.get("merchant_id"),
              batch.get("channel"),
              from,
              until);
          jdbc.update(
              "INSERT INTO recon_local_rows(batch_id,operation,aggregate_id,status,amount_minor,fee_minor,currency,reference) SELECT ?,'REFUND',id,status,amount_minor,refunded_fee_minor,'CNY',channel_reference FROM refunds WHERE merchant_id=? AND channel=? AND created_at>=? AND created_at<?",
              id,
              batch.get("merchant_id"),
              batch.get("channel"),
              from,
              until);
          jdbc.update(
              "UPDATE recon_batches SET snapshot_at=?,status='MATCHING' WHERE id=?",
              Timestamp.from(clock.instant()),
              id);
        });
  }

  private void match(String id, Map<String, Object> batch, TaskQueue.Task task) {
    long cursor = 0;
    while (true) {
      var rows =
          jdbc.queryForList(
              "SELECT r.*,l.status AS local_status,l.amount_minor AS local_amount,l.fee_minor AS local_fee,l.reference AS local_reference,CASE WHEN EXISTS (SELECT /*+ JOIN_INDEX(d ix_recon_match) */ 1 FROM recon_rows d WHERE d.batch_id=r.batch_id AND d.operation=r.operation AND d.aggregate_id=r.aggregate_id AND d.line_no<>r.line_no) THEN 1 ELSE 0 END AS duplicate_record FROM recon_rows r LEFT JOIN recon_local_rows l ON l.batch_id=r.batch_id AND l.operation=r.operation AND l.aggregate_id=r.aggregate_id WHERE r.batch_id=? AND r.line_no>? ORDER BY r.line_no LIMIT 500",
              id,
              cursor);
      if (rows.isEmpty()) {
        break;
      }
      tx.executeWithoutResult(
          transaction -> {
            queue.assertOwned(task);
            for (var row : rows) {
              var issues = new ArrayList<String>();
              if (((Number) row.get("duplicate_record")).longValue() != 0) {
                issues.add("DUPLICATE_CHANNEL_RECORD");
              }
              if (row.get("local_status") == null) {
                issues.add("CHANNEL_ONLY");
              } else {
                if (!row.get("status").equals(row.get("local_status"))) {
                  issues.add(
                      row.get("status").equals("SUCCEEDED")
                              && Set.of("UNKNOWN", "PROCESSING", "CLOSE_PENDING")
                                  .contains(row.get("local_status"))
                          ? "CHANNEL_SUCCESS_LOCAL_UNKNOWN"
                          : "STATUS_MISMATCH");
                }
                if (((Number) row.get("amount_minor")).longValue()
                    != ((Number) row.get("local_amount")).longValue()) {
                  issues.add("AMOUNT_MISMATCH");
                }
                if (((Number) row.get("fee_minor")).longValue()
                    != ((Number) row.get("local_fee")).longValue()) {
                  issues.add("FEE_MISMATCH");
                }
                if (row.get("status").equals("SUCCEEDED")
                    && row.get("local_status").equals("SUCCEEDED")
                    && !Objects.equals(row.get("reference"), row.get("local_reference"))) {
                  issues.add("REFERENCE_MISMATCH");
                }
              }
              for (String type : issues) {
                issue(id, (String) batch.get("merchant_id"), row, type);
              }
            }
          });
      cursor = ((Number) rows.getLast().get("line_no")).longValue();
    }
    String after = "";
    while (true) {
      var rows =
          jdbc.queryForList(
              "SELECT l.*,l.status AS local_status,l.amount_minor AS local_amount,l.fee_minor AS local_fee,0 AS line_no FROM recon_local_rows l WHERE batch_id=? AND status='SUCCEEDED' AND aggregate_id>? AND NOT EXISTS (SELECT 1 FROM recon_rows r WHERE r.batch_id=l.batch_id AND r.operation=l.operation AND r.aggregate_id=l.aggregate_id) ORDER BY aggregate_id,operation LIMIT 500",
              id,
              after);
      if (rows.isEmpty()) {
        break;
      }
      tx.executeWithoutResult(
          transaction -> {
            queue.assertOwned(task);
            for (var row : rows) {
              issue(id, (String) batch.get("merchant_id"), row, "LOCAL_ONLY");
            }
          });
      after = (String) rows.getLast().get("aggregate_id");
    }
  }

  private void issue(String id, String merchant, Map<String, Object> row, String type) {
    boolean local = type.equals("LOCAL_ONLY");
    jdbc.update(
        "INSERT IGNORE INTO recon_issues(id,batch_id,merchant_id,line_no,operation,aggregate_id,issue_type,local_status,channel_status,local_amount_minor,channel_amount_minor,local_fee_minor,channel_fee_minor,raw_line,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        merchant,
        row.get("line_no"),
        row.get("operation"),
        row.get("aggregate_id"),
        type,
        row.get("local_status"),
        local ? null : row.get("status"),
        row.get("local_amount"),
        local ? null : row.get("amount_minor"),
        row.get("local_fee"),
        local ? null : row.get("fee_minor"),
        row.get("raw_line"),
        Timestamp.from(clock.instant()));
  }
}
