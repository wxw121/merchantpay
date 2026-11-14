package dev.merchantpay.platform.history;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.Csv;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import dev.merchantpay.platform.storage.ObjectStorage;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Verified Parquet facts exported only from each bucket's current owner. */
@Service
@ConditionalOnProperty(
    name = {"merchantpay.sharding.enabled", "merchantpay.history.archive-enabled"},
    havingValue = "true")
public final class HistoryExportService {
  private record Ref(
      String id,
      String paymentId,
      String sourceHash,
      String objectHash,
      String objectKey,
      long factCount,
      long objectBytes,
      Timestamp createdAt) {}

  private static final int PAGE = 500;
  private static final long MAX_FACTS = 10_000_000;
  private static final String HEADER =
      "archive_id,payment_id,kind,fact_id,occurred_micros,payload_hash,payload_json\n";

  private final ShardExportReadGate gate;
  private final ObjectStorage storage;

  public HistoryExportService(ShardExportReadGate gate, ObjectStorage storage) {
    this.gate = gate;
    this.storage = storage;
  }

  public long write(
      Path destination,
      String exportId,
      String merchant,
      Timestamp from,
      Timestamp until,
      ShardExportReadGate.Snapshot snapshot,
      LongConsumer progress)
      throws IOException {
    if (snapshot == null) throw conflict("历史导出缺少四库目录快照");
    long count = 0;
    int parts = 1;
    try (var zip =
        new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(destination)))) {
      var writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("history-001.csv"));
      writer.write(HEADER);
      for (var route : snapshot.routes()) {
        var local = new JdbcTemplate(gate.source(route.physical()));
        local.setQueryTimeout(60);
        Timestamp cursorTime = null;
        String cursorId = null;
        while (true) {
          gate.validate(snapshot);
          String sql =
              "SELECT * FROM v3_history_archives WHERE merchant_id=?"
                  + " AND logical_bucket=? AND created_at>=? AND created_at<?";
          List<Ref> page;
          if (cursorTime == null) {
            page =
                local.query(
                    sql + " ORDER BY created_at,id LIMIT " + PAGE,
                    (rs, ignored) -> ref(snapshot, route.physical(), rs),
                    merchant,
                    route.bucket(),
                    from,
                    until);
          } else {
            page =
                local.query(
                    sql
                        + " AND (created_at>? OR (created_at=? AND id>?))"
                        + " ORDER BY created_at,id LIMIT "
                        + PAGE,
                    (rs, ignored) -> ref(snapshot, route.physical(), rs),
                    merchant,
                    route.bucket(),
                    from,
                    until,
                    cursorTime,
                    cursorTime,
                    cursorId);
          }
          if (page.isEmpty()) break;
          for (var archive : page) {
            cursorTime = archive.createdAt();
            cursorId = archive.id();
            for (var fact : verified(archive, merchant)) {
              if (count == MAX_FACTS) throw conflict("历史导出超过1000万行，请缩小时间范围");
              if (count > 0 && count % 1_000_000 == 0) {
                writer.flush();
                zip.closeEntry();
                parts++;
                zip.putNextEntry(
                    new ZipEntry("history-" + String.format(Locale.ROOT, "%03d", parts) + ".csv"));
                writer.write(HEADER);
              }
              writer.write(Csv.cell(archive.id()));
              writer.write(',');
              writer.write(Csv.cell(fact.paymentId()));
              writer.write(',');
              writer.write(Csv.cell(fact.kind()));
              writer.write(',');
              writer.write(Csv.cell(fact.factId()));
              writer.write(',');
              writer.write(Long.toString(fact.occurredMicros()));
              writer.write(',');
              writer.write(Csv.cell(fact.payloadHash()));
              writer.write(',');
              writer.write(Csv.cell(fact.payloadJson()));
              writer.write('\n');
              count++;
              if (count % 1000 == 0) progress.accept(count);
            }
            gate.validate(snapshot);
          }
          if (page.size() < PAGE) break;
        }
      }
      writer.flush();
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("manifest.json"));
      writer.write(
          "{\"exportId\":\""
              + exportId
              + "\",\"resource\":\"history\",\"rows\":"
              + count
              + ",\"parts\":"
              + parts
              + ",\"sourceMode\":\"PER_SOURCE\",\"verifiedObjects\":true}");
      writer.flush();
      zip.closeEntry();
    }
    gate.validate(snapshot);
    return count;
  }

  private Ref ref(ShardExportReadGate.Snapshot snapshot, int physical, java.sql.ResultSet rs)
      throws java.sql.SQLException {
    gate.assertOwned(snapshot, physical, "history", rs);
    return new Ref(
        rs.getString("id"),
        rs.getString("payment_id"),
        rs.getString("source_hash"),
        rs.getString("object_hash"),
        rs.getString("object_key"),
        rs.getLong("fact_count"),
        rs.getLong("object_bytes"),
        rs.getTimestamp("created_at"));
  }

  private List<HistoryParquetCodec.Fact> verified(Ref archive, String merchant) throws IOException {
    Path file = storage.temporary("history-export-");
    try {
      long bytes = 0;
      try (var input = storage.open(archive.objectKey());
          var output = Files.newOutputStream(file)) {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = input.read(buffer)) != -1) {
          bytes += n;
          if (bytes > 64L * 1024 * 1024) throw new IOException("History object exceeds 64 MiB");
          output.write(buffer, 0, n);
        }
      }
      if (bytes != archive.objectBytes())
        throw new IOException("History object size differs from manifest");
      var facts = HistoryParquetCodec.read(file, archive.objectHash());
      if (facts.size() != archive.factCount()
          || !HistoryArchiveService.sourceHash(facts).equals(archive.sourceHash())
          || facts.stream()
              .anyMatch(
                  f ->
                      !merchant.equals(f.merchantId())
                          || !archive.paymentId().equals(f.paymentId())))
        throw conflict("历史导出对象与清单不一致");
      return facts;
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static ApiException conflict(String message) {
    return new ApiException("HISTORY_EXPORT_CONFLICT", 409, message);
  }
}
