package dev.merchantpay.platform.reconciliation;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.sharding.ShardCoreOnlyGuard;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import dev.merchantpay.platform.storage.ObjectStorage;
import java.io.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.ResultSet;
import java.sql.SQLException;
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
  private ShardCoreOnlyGuard shardBulkGuard;
  private ShardExportReadGate shardSourceGate;

  @Autowired(required = false)
  public void setShardBulkGuard(ShardCoreOnlyGuard guard) {
    shardBulkGuard = guard;
  }

  @Autowired(required = false)
  public void setShardSourceGate(ShardExportReadGate gate) {
    shardSourceGate = gate;
  }

  private ShardExportReadGate.Snapshot sourceSnapshot() {
    if (shardBulkGuard == null) return null;
    try {
      shardBulkGuard.requireCoreOnly();
      return null;
    } catch (ApiException moved) {
      if (!"SHARDED_CORE_WORKFLOW_PENDING".equals(moved.code()) || shardSourceGate == null)
        throw moved;
      return shardSourceGate.capture();
    }
  }

  private void validate(ShardExportReadGate.Snapshot snapshot) {
    if (snapshot == null) {
      if (shardBulkGuard != null) shardBulkGuard.requireCoreOnly();
    } else shardSourceGate.validate(snapshot);
  }

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

  private record LocalRow(
      String operation,
      String id,
      String status,
      long amount,
      long fee,
      String currency,
      String reference) {}

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
    var source = sourceSnapshot();
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
          validate(source);
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
            validate(source);
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
          validate(source);
          return id;
        });
  }

  /** Each accepted segment is immutable; only the complete manifest creates a worker task. */
  public String submitSegment(
      String merchant,
      String channel,
      LocalDate day,
      int version,
      int segmentIndex,
      int segmentCount,
      long segmentRows,
      long segmentAmount,
      long totalRows,
      long totalAmount,
      String manifestSha256,
      String segmentSha256,
      InputStream input,
      java.util.function.Consumer<String> audit)
      throws IOException {
    if (merchant == null
        || !("MOCK_A".equals(channel) || "MOCK_B".equals(channel))
        || day == null
        || version < 1
        || segmentCount < 1
        || segmentCount > 10
        || segmentIndex < 0
        || segmentIndex >= segmentCount
        || segmentRows < 0
        || segmentRows > 1_000_000
        || segmentAmount < 0
        || segmentAmount > 1_000_000_000_000_000L
        || (segmentRows == 0 && segmentAmount != 0)
        || totalRows < 0
        || totalRows > 10_000_000
        || totalAmount < 0
        || totalAmount > 10_000_000_000_000_000L
        || (totalRows == 0 && totalAmount != 0)
        || segmentRows > totalRows
        || segmentAmount > totalAmount
        || !digestShape(manifestSha256)
        || !digestShape(segmentSha256))
      throw new ApiException("INVALID_BILL_SEGMENT", 400, "分段账单清单或范围无效");
    var source = sourceSnapshot();
    Path temporary = storage.temporary("segment-");
    String storageKey;
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      try (var output = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
          size += read;
          if (size > 128L * 1024 * 1024)
            throw new ApiException("BILL_TOO_LARGE", 413, "单段账单最大 128 MiB");
          hash.update(buffer, 0, read);
          output.write(buffer, 0, read);
        }
      }
      if (!segmentSha256.equals(HexFormat.of().formatHex(hash.digest())))
        throw new ApiException("BILL_SEGMENT_DIGEST", 400, "分段原文与 SHA-256 不符");
      storageKey =
          storage.publish(temporary, "bills/" + segmentSha256 + "-" + UUID.randomUUID() + ".csv");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return tx.execute(
        transaction -> {
          validate(source);
          if (jdbc.queryForList("SELECT id FROM merchants WHERE id=? FOR UPDATE", merchant)
              .isEmpty()) throw new ApiException("MERCHANT_NOT_FOUND", 404, "商户不存在");
          var existing =
              jdbc.queryForList(
                  "SELECT id,sha256,bill_format,segment_count,expected_count,expected_amount_minor,status FROM recon_batches WHERE merchant_id=? AND channel=? AND business_day=? AND version=? FOR UPDATE",
                  merchant,
                  channel,
                  java.sql.Date.valueOf(day),
                  version);
          String id;
          if (existing.isEmpty()) {
            if (segmentIndex != 0) throw new ApiException("BILL_SEGMENT_ORDER", 409, "请先上传第 0 段");
            id = UUID.randomUUID().toString();
            var now = Timestamp.from(clock.instant());
            jdbc.update(
                "INSERT INTO recon_batches(id,merchant_id,channel,business_day,version,sha256,storage_key,status,expected_count,expected_amount_minor,bill_format,segment_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                merchant,
                channel,
                java.sql.Date.valueOf(day),
                version,
                manifestSha256,
                storageKey,
                "ASSEMBLING",
                totalRows,
                totalAmount,
                "SEGMENTED",
                segmentCount,
                now,
                now);
          } else {
            var row = existing.getFirst();
            if (!"SEGMENTED".equals(row.get("bill_format"))
                || !manifestSha256.equals(row.get("sha256"))
                || ((Number) row.get("segment_count")).intValue() != segmentCount
                || ((Number) row.get("expected_count")).longValue() != totalRows
                || ((Number) row.get("expected_amount_minor")).longValue() != totalAmount)
              throw new ApiException("BILL_VERSION_CONFLICT", 409, "账单版本已绑定另一份清单");
            id = (String) row.get("id");
          }
          var prior =
              jdbc.queryForList(
                  "SELECT sha256,expected_count,expected_amount_minor FROM recon_segments WHERE batch_id=? AND segment_index=?",
                  id,
                  segmentIndex);
          if (!prior.isEmpty()) {
            var row = prior.getFirst();
            if (!segmentSha256.equals(row.get("sha256"))
                || ((Number) row.get("expected_count")).longValue() != segmentRows
                || ((Number) row.get("expected_amount_minor")).longValue() != segmentAmount)
              throw new ApiException("BILL_SEGMENT_CONFLICT", 409, "该段已有不同内容");
            audit.accept(id);
            validate(source);
            return id;
          }
          if (!existing.isEmpty() && !"ASSEMBLING".equals(existing.getFirst().get("status")))
            throw new ApiException("BILL_SEGMENT_CLOSED", 409, "账单已提交，不能追加分段");
          jdbc.update(
              "INSERT INTO recon_segments(batch_id,segment_index,sha256,storage_key,expected_count,expected_amount_minor) VALUES(?,?,?,?,?,?)",
              id,
              segmentIndex,
              segmentSha256,
              storageKey,
              segmentRows,
              segmentAmount);
          var segments =
              jdbc.queryForList(
                  "SELECT segment_index,sha256,expected_count,expected_amount_minor FROM recon_segments WHERE batch_id=? ORDER BY segment_index",
                  id);
          if (segments.size() == segmentCount) {
            long rows = 0, amount = 0;
            var manifest = new StringBuilder();
            for (int index = 0; index < segments.size(); index++) {
              var segment = segments.get(index);
              if (((Number) segment.get("segment_index")).intValue() != index)
                throw new ApiException("BILL_SEGMENT_GAP", 409, "分段序号不连续");
              long count = ((Number) segment.get("expected_count")).longValue();
              long value = ((Number) segment.get("expected_amount_minor")).longValue();
              rows = Math.addExact(rows, count);
              amount = Math.addExact(amount, value);
              manifest
                  .append(index)
                  .append(':')
                  .append(segment.get("sha256"))
                  .append(':')
                  .append(count)
                  .append(':')
                  .append(value)
                  .append('\n');
            }
            if (rows != totalRows
                || amount != totalAmount
                || !manifestSha256.equals(Signatures.sha256(manifest.toString())))
              throw new ApiException("BILL_MANIFEST_CONFLICT", 409, "分段清单笔数、金额或摘要不一致");
            jdbc.update(
                "UPDATE recon_batches SET status='QUEUED',updated_at=? WHERE id=?",
                Timestamp.from(clock.instant()),
                id);
            outbox.enqueue("reconcile:" + id, "RECONCILE", id, merchant);
          }
          audit.accept(id);
          validate(source);
          return id;
        });
  }

  private static boolean digestShape(String digest) {
    return digest != null && digest.matches("[a-f0-9]{64}");
  }

  public void process(String id, TaskQueue.Task task) {
    var source = sourceSnapshot();
    var batch = batch(id);
    if (Set.of("COMPLETE", "PRELIMINARY", "QUARANTINED").contains(batch.get("status"))) {
      return;
    }
    try {
      if (!queue.begin(task)) return;
      parse(id, batch, task);
      snapshot(id, batch, task, source);
      match(id, batch, task);
      queue.fenced(
          task,
          () -> {
            if (source == null) validate(null);
            else {
              shardSourceGate.validateForPublish(source);
              requireWatermarks(
                  source,
                  (String)
                      jdbc.queryForObject(
                          "SELECT source_event_watermarks FROM recon_batches WHERE id=?",
                          String.class,
                          id));
            }
            // Core-only COMPLETE freezes settlement. Sharded PRELIMINARY remains outside
            // settlement until an explicit stopped-writer seal is implemented.
            jdbc.queryForList(
                "SELECT id FROM merchants WHERE id=? FOR UPDATE", batch.get("merchant_id"));
            jdbc.update(
                "UPDATE recon_batches SET status=?,error_detail=NULL,updated_at=? WHERE id=?",
                source == null ? "COMPLETE" : "PRELIMINARY",
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

  /** Promote a four-source preliminary result only during an operator-enforced writer stop. */
  public void seal(
      String id,
      String channelSha256,
      boolean writersStopped,
      String reason,
      String actor,
      java.util.function.Consumer<String> audit)
      throws IOException {
    if (!writersStopped
        || !digestShape(channelSha256)
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000
        || actor == null
        || actor.isBlank()
        || actor.length() > 160)
      throw new ApiException("INVALID_RECON_SEAL", 400, "须确认所有写入已停止，提供独立核对的渠道摘要、原因和操作人");
    var row = batch(id);
    if ("COMPLETE".equals(row.get("status")) && row.get("sealed_at") != null) {
      if (!channelSha256.equals(row.get("sha256")))
        throw new ApiException("RECON_SEAL_CONFLICT", 409, "渠道摘要与已封版账单不符");
      return;
    }
    var source = sourceSnapshot();
    requireSealable(row, source, channelSha256);
    requireWatermarks(source, (String) row.get("source_event_watermarks"));
    var proof = new ReconciliationSealVerifier(jdbc, shardSourceGate, storage).verify(row, source);
    requireWatermarks(source, (String) row.get("source_event_watermarks"));
    tx.executeWithoutResult(
        transaction -> {
          var locked = jdbc.queryForMap("SELECT * FROM recon_batches WHERE id=? FOR UPDATE", id);
          requireSealable(locked, source, channelSha256);
          if (!Objects.equals(row.get("snapshot_at"), locked.get("snapshot_at"))
              || !Objects.equals(
                  row.get("source_event_watermarks"), locked.get("source_event_watermarks")))
            throw new ApiException("RECON_SEAL_CONFLICT", 409, "对账快照在封版期间发生变化");
          shardSourceGate.validateForPublish(source);
          requireWatermarks(source, (String) locked.get("source_event_watermarks"));
          jdbc.queryForList(
              "SELECT id FROM merchants WHERE id=? FOR UPDATE", locked.get("merchant_id"));
          int updated =
              jdbc.update(
                  "UPDATE recon_batches SET status='COMPLETE',sealed_at=?,sealed_by=?,seal_reason=?,seal_source_hash=?,updated_at=? WHERE id=? AND status='PRELIMINARY'",
                  Timestamp.from(clock.instant()),
                  actor,
                  reason.trim(),
                  proof,
                  Timestamp.from(clock.instant()),
                  id);
          if (updated != 1) throw new ApiException("RECON_SEAL_CONFLICT", 409, "对账状态已变化");
          audit.accept((String) locked.get("merchant_id"));
        });
  }

  private static void requireSealable(
      Map<String, Object> row, ShardExportReadGate.Snapshot source, String channelSha256) {
    if (source == null
        || !"PRELIMINARY".equals(row.get("status"))
        || row.get("snapshot_at") == null
        || !channelSha256.equals(row.get("sha256"))
        || !Signatures.sha256(source.routes().toString()).equals(row.get("source_routes_hash"))
        || row.get("source_event_watermarks") == null
        || ((Number) row.get("expected_count")).longValue()
            != ((Number) row.get("actual_count")).longValue()
        || ((Number) row.get("expected_amount_minor")).longValue()
            != ((Number) row.get("actual_amount_minor")).longValue())
      throw new ApiException("RECON_SEAL_CONFLICT", 409, "批次未满足预对账、渠道摘要、完整笔数金额和固定来源要求");
  }

  private String originalKey(Map<String, Object> batch) {
    if ("SEGMENTED".equals(batch.get("bill_format")))
      throw new ApiException("BILL_SEGMENTED", 409, "分段账单请按段下载原文");
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

  public InputStreamResource downloadSegment(String id, String merchant, int index)
      throws IOException {
    var batch = batch(id);
    if (merchant != null && !merchant.equals(batch.get("merchant_id")))
      throw new ApiException("BILL_NOT_FOUND", 404, "账单不存在");
    if (!"SEGMENTED".equals(batch.get("bill_format")) || index < 0)
      throw new ApiException("BILL_SEGMENT_NOT_FOUND", 404, "分段不存在");
    var rows =
        jdbc.queryForList(
            "SELECT storage_key FROM recon_segments WHERE batch_id=? AND segment_index=?",
            id,
            index);
    if (rows.isEmpty()) throw new ApiException("BILL_SEGMENT_NOT_FOUND", 404, "分段不存在");
    String key = (String) rows.getFirst().get("storage_key");
    if (!key.matches("(s3:)?bills/[a-f0-9]{64}-[a-f0-9-]{36}\\.csv"))
      throw new IllegalStateException("Invalid stored segment key");
    return storage.resource(key);
  }

  private void parse(String id, Map<String, Object> batch, TaskQueue.Task task) throws IOException {
    queue.fenced(
        task,
        () ->
            jdbc.update(
                "UPDATE recon_batches SET status='VALIDATING',updated_at=? WHERE id=?",
                Timestamp.from(clock.instant()),
                id));
    if ("SEGMENTED".equals(batch.get("bill_format"))) {
      parseSegmented(id, batch, task);
      return;
    }
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
        chunk.add(billRow(raw, number));
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

  private void parseSegmented(String id, Map<String, Object> batch, TaskQueue.Task task)
      throws IOException {
    var segments =
        jdbc.queryForList(
            "SELECT segment_index,sha256,storage_key,expected_count,expected_amount_minor FROM recon_segments WHERE batch_id=? ORDER BY segment_index",
            id);
    int segmentCount = ((Number) batch.get("segment_count")).intValue();
    if (segments.size() != segmentCount || segmentCount < 1 || segmentCount > 10)
      throw new InvalidBill("分段清单不完整");
    var manifest = new StringBuilder();
    long declaredCount = 0, declaredAmount = 0;
    for (int index = 0; index < segments.size(); index++) {
      var segment = segments.get(index);
      if (((Number) segment.get("segment_index")).intValue() != index)
        throw new InvalidBill("分段序号不连续");
      long rows = ((Number) segment.get("expected_count")).longValue();
      long amount = ((Number) segment.get("expected_amount_minor")).longValue();
      declaredCount = Math.addExact(declaredCount, rows);
      declaredAmount = Math.addExact(declaredAmount, amount);
      manifest
          .append(index)
          .append(':')
          .append(segment.get("sha256"))
          .append(':')
          .append(rows)
          .append(':')
          .append(amount)
          .append('\n');
    }
    if (!batch.get("sha256").equals(Signatures.sha256(manifest.toString()))
        || declaredCount != ((Number) batch.get("expected_count")).longValue()
        || declaredAmount != ((Number) batch.get("expected_amount_minor")).longValue())
      throw new InvalidBill("分段清单摘要或总计不符");
    long checkpoint = ((Number) batch.get("processed_lines")).longValue();
    long globalLine = 0, globalAmount = 0;
    for (var segment : segments) {
      String key = originalKey(segment);
      String expectedDigest = (String) segment.get("sha256");
      try (var input = storage.open(key)) {
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        if (!expectedDigest.equals(HexFormat.of().formatHex(digest.digest())))
          throw new InvalidBill("分段原文摘要改变，已隔离");
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
      try (var reader =
          new BufferedReader(
              new InputStreamReader(
                  storage.open(key),
                  StandardCharsets.UTF_8
                      .newDecoder()
                      .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)))) {
        String header = line(reader);
        if (header == null) throw new InvalidBill("分段账单为空");
        var fields = header.split(",", -1);
        if (fields.length != 6
            || !"#merchantpay-bill-v1".equals(fields[0])
            || !fields[1].equals(batch.get("channel"))
            || !fields[2].equals(batch.get("merchant_id"))
            || !fields[3].equals(batch.get("business_day").toString())
            || !fields[4].matches("[0-9]{1,7}")
            || !fields[5].matches("[0-9]{1,16}")
            || Long.parseLong(fields[4]) != ((Number) segment.get("expected_count")).longValue()
            || Long.parseLong(fields[5])
                != ((Number) segment.get("expected_amount_minor")).longValue()
            || !"operation,aggregateId,status,amountMinor,feeMinor,currency,reference"
                .equals(line(reader))) throw new InvalidBill("分段账单元数据不匹配");
        long rows = 0, amount = 0;
        String raw;
        var chunk = new ArrayList<BillRow>(500);
        while ((raw = line(reader)) != null) {
          rows++;
          globalLine++;
          if (rows > 1_000_000 || globalLine > 10_000_000) throw new InvalidBill("分段账单行数超过上限");
          BillRow row = billRow(raw, globalLine);
          amount = Math.addExact(amount, row.amount());
          if (globalLine > checkpoint) {
            chunk.add(row);
            if (chunk.size() == 500) {
              persist(id, chunk, task);
              chunk.clear();
            }
          }
        }
        if (!chunk.isEmpty()) persist(id, chunk, task);
        if (rows != ((Number) segment.get("expected_count")).longValue()
            || amount != ((Number) segment.get("expected_amount_minor")).longValue())
          throw new InvalidBill("分段账单行数或金额不符");
        globalAmount = Math.addExact(globalAmount, amount);
      } catch (java.nio.charset.CharacterCodingException invalidEncoding) {
        throw new InvalidBill("分段账单不是有效的 UTF-8 编码，原始文件已隔离");
      }
    }
    var current = batch(id);
    if (globalLine != declaredCount
        || globalAmount != declaredAmount
        || ((Number) current.get("actual_count")).longValue() != declaredCount
        || ((Number) current.get("actual_amount_minor")).longValue() != declaredAmount)
      throw new InvalidBill("分段账单总笔数或总金额不一致");
  }

  private static BillRow billRow(String raw, long number) {
    var row = raw.split(",", -1);
    if (row.length != 7
        || !Set.of("PAYMENT", "REFUND").contains(row[0])
        || !row[1].matches("[a-zA-Z0-9_-]{1,64}")
        || !Set.of("SUCCEEDED", "FAILED", "PENDING", "CLOSED", "UNKNOWN").contains(row[2])
        || !row[3].matches("[1-9][0-9]{0,9}")
        || !row[4].matches("[0-9]{1,10}")
        || !row[5].equals("CNY")
        || !row[6].matches("[a-zA-Z0-9:_-]{0,128}"))
      throw new InvalidBill("第 " + number + " 行格式错误；原始行已保留");
    long amount = Long.parseLong(row[3]), fee = Long.parseLong(row[4]);
    if (amount > 1_000_000_000L || fee > amount) throw new InvalidBill("第 " + number + " 行金额超出范围");
    return new BillRow(number, row[0], row[1], row[2], amount, fee, row[5], row[6], raw);
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

  private void snapshot(
      String id,
      Map<String, Object> batch,
      TaskQueue.Task task,
      ShardExportReadGate.Snapshot source) {
    if (source != null) {
      snapshotSharded(id, batch, task, source);
      return;
    }
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

  private void snapshotSharded(
      String id,
      Map<String, Object> batch,
      TaskQueue.Task task,
      ShardExportReadGate.Snapshot source) {
    String routeHash = Signatures.sha256(source.routes().toString());
    boolean alreadySnapshotted =
        tx.execute(
            transaction -> {
              queue.assertOwned(task);
              var row =
                  jdbc.queryForMap(
                      "SELECT snapshot_at,source_routes_hash,source_event_watermarks FROM recon_batches WHERE id=? FOR UPDATE",
                      id);
              if (row.get("snapshot_at") != null) {
                if (!routeHash.equals(row.get("source_routes_hash")))
                  throw new ApiException("RECON_SOURCE_CHANGED", 409, "账单快照对应的桶路由已变化，请重新上传新版本账单");
                requireWatermarks(source, (String) row.get("source_event_watermarks"));
                return true;
              }
              jdbc.update("DELETE FROM recon_local_rows WHERE batch_id=?", id);
              return false;
            });
    if (alreadySnapshotted) {
      shardSourceGate.validate(source);
      return;
    }
    var day = LocalDate.parse(batch.get("business_day").toString());
    var zone = ZoneId.of("Asia/Shanghai");
    var from = Timestamp.from(day.atStartOfDay(zone).toInstant());
    var until = Timestamp.from(day.plusDays(1).atStartOfDay(zone).toInstant());
    String startingWatermarks = watermarks(source);
    for (int physical = 0; physical < 4; physical++) {
      shardSourceGate.validate(source);
      stageSource(id, batch, task, source, physical, "PAYMENT", from, until);
      stageSource(id, batch, task, source, physical, "REFUND", from, until);
      shardSourceGate.validate(source);
    }
    requireWatermarks(source, startingWatermarks);
    tx.executeWithoutResult(
        transaction -> {
          queue.assertOwned(task);
          var row =
              jdbc.queryForMap("SELECT snapshot_at FROM recon_batches WHERE id=? FOR UPDATE", id);
          if (row.get("snapshot_at") != null)
            throw new ApiException("RECON_SOURCE_CHANGED", 409, "账单快照已被其他任务完成");
          shardSourceGate.validateForPublish(source);
          requireWatermarks(source, startingWatermarks);
          jdbc.update(
              "UPDATE recon_batches SET snapshot_at=?,source_routes_hash=?,source_event_watermarks=?,status='MATCHING' WHERE id=?",
              Timestamp.from(clock.instant()),
              routeHash,
              startingWatermarks,
              id);
        });
  }

  private String watermarks(ShardExportReadGate.Snapshot source) {
    shardSourceGate.validate(source);
    var values = new StringJoiner(",");
    for (int physical = 0; physical < 4; physical++) {
      Long latest =
          new JdbcTemplate(shardSourceGate.source(physical))
              .queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM domain_events", Long.class);
      values.add(Long.toString(latest));
    }
    shardSourceGate.validate(source);
    return values.toString();
  }

  private void requireWatermarks(ShardExportReadGate.Snapshot source, String expected) {
    if (expected == null || !expected.equals(watermarks(source)))
      throw new ApiException("RECON_WATERMARK_MOVED", 409, "对账期间来源事件水位已变化，请重新上传新版本账单");
  }

  private void stageSource(
      String id,
      Map<String, Object> batch,
      TaskQueue.Task task,
      ShardExportReadGate.Snapshot source,
      int physical,
      String operation,
      Timestamp from,
      Timestamp until) {
    String resource = operation.equals("PAYMENT") ? "payments" : "refunds";
    String sql =
        operation.equals("PAYMENT")
            ? "SELECT id,status,amount_minor,fee_minor,currency,channel_reference FROM payments WHERE merchant_id=? AND channel=? AND created_at>=? AND created_at<? ORDER BY created_at,id"
            : "SELECT id,payment_id,status,amount_minor,refunded_fee_minor,channel_reference FROM refunds WHERE merchant_id=? AND channel=? AND created_at>=? AND created_at<? ORDER BY created_at,id";
    var pending = new ArrayList<LocalRow>(500);
    try (var connection = shardSourceGate.source(physical).getConnection()) {
      connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement(
              sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
        statement.setString(1, batch.get("merchant_id").toString());
        statement.setString(2, batch.get("channel").toString());
        statement.setTimestamp(3, from);
        statement.setTimestamp(4, until);
        statement.setFetchSize(
            connection.getMetaData().getURL().startsWith("jdbc:mysql:") ? Integer.MIN_VALUE : 1000);
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            if (!shardSourceGate.includeOwned(source, physical, resource, rows)) continue;
            pending.add(
                new LocalRow(
                    operation,
                    rows.getString("id"),
                    rows.getString("status"),
                    rows.getLong("amount_minor"),
                    rows.getLong(operation.equals("PAYMENT") ? "fee_minor" : "refunded_fee_minor"),
                    operation.equals("PAYMENT") ? rows.getString("currency") : "CNY",
                    rows.getString("channel_reference")));
            if (pending.size() == 500) {
              stageRows(id, task, pending);
              pending.clear();
            }
          }
        }
      }
      connection.commit();
    } catch (SQLException failure) {
      throw new IllegalStateException("Shard reconciliation source unavailable", failure);
    }
    if (!pending.isEmpty()) stageRows(id, task, pending);
  }

  private void stageRows(String id, TaskQueue.Task task, List<LocalRow> rows) {
    tx.executeWithoutResult(
        transaction -> {
          queue.assertOwned(task);
          var batch =
              jdbc.queryForMap("SELECT snapshot_at FROM recon_batches WHERE id=? FOR UPDATE", id);
          if (batch.get("snapshot_at") != null)
            throw new ApiException("RECON_SOURCE_CHANGED", 409, "账单快照已完成，不能继续写入");
          jdbc.batchUpdate(
              "INSERT INTO recon_local_rows(batch_id,operation,aggregate_id,status,amount_minor,fee_minor,currency,reference) VALUES (?,?,?,?,?,?,?,?)",
              rows,
              500,
              (statement, row) -> {
                statement.setString(1, id);
                statement.setString(2, row.operation());
                statement.setString(3, row.id());
                statement.setString(4, row.status());
                statement.setLong(5, row.amount());
                statement.setLong(6, row.fee());
                statement.setString(7, row.currency());
                statement.setString(8, row.reference());
              });
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
    String afterId = "";
    String afterOperation = "";
    while (true) {
      var rows =
          jdbc.queryForList(
              "SELECT l.*,l.status AS local_status,l.amount_minor AS local_amount,l.fee_minor AS local_fee,0 AS line_no FROM recon_local_rows l WHERE batch_id=? AND status='SUCCEEDED' AND (aggregate_id>? OR (aggregate_id=? AND operation>?)) AND NOT EXISTS (SELECT 1 FROM recon_rows r WHERE r.batch_id=l.batch_id AND r.operation=l.operation AND r.aggregate_id=l.aggregate_id) ORDER BY aggregate_id,operation LIMIT 500",
              id,
              afterId,
              afterId,
              afterOperation);
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
      afterId = (String) rows.getLast().get("aggregate_id");
      afterOperation = (String) rows.getLast().get("operation");
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
