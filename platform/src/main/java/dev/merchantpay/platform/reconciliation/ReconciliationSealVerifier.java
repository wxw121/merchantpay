package dev.merchantpay.platform.reconciliation;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import dev.merchantpay.platform.storage.ObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Slow, read-only evidence comparison for a stopped-writer reconciliation seal. */
final class ReconciliationSealVerifier {
  private final JdbcTemplate core;
  private final ShardExportReadGate gate;
  private final ObjectStorage storage;

  ReconciliationSealVerifier(JdbcTemplate core, ShardExportReadGate gate, ObjectStorage storage) {
    this.core = core;
    this.gate = gate;
    this.storage = storage;
  }

  String verify(Map<String, Object> batch, ShardExportReadGate.Snapshot source) throws IOException {
    gate.validate(source);
    verifyBill(batch);
    var staged = cells();
    var live = cells();
    readStaged((String) batch.get("id"), source, staged);
    var day = LocalDate.parse(batch.get("business_day").toString());
    var zone = ZoneId.of("Asia/Shanghai");
    var from = Timestamp.from(day.atStartOfDay(zone).toInstant());
    var until = Timestamp.from(day.plusDays(1).atStartOfDay(zone).toInstant());
    for (int physical = 0; physical < 4; physical++) {
      try (var connection = gate.source(physical).getConnection()) {
        try {
          EventTransportMaintenance.requireDrained(connection);
        } catch (IllegalStateException pending) {
          throw conflict("来源仍有未排空的业务任务或事件");
        }
        if (count(connection, "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'")
            != 0) throw conflict("来源仍有未处理的隔离事件");
        readLive(connection, batch, source, physical, from, until, live[physical]);
      } catch (SQLException failure) {
        throw new IllegalStateException(
            "Reconciliation seal source unavailable: " + physical, failure);
      }
    }
    var proof = new StringBuilder();
    for (int physical = 0; physical < 4; physical++) {
      for (int operation = 0; operation < 2; operation++) {
        var observed = live[physical][operation].finish();
        var expected = staged[physical][operation].finish();
        if (observed.count() != expected.count() || !observed.hash().equals(expected.hash()))
          throw conflict("权威资金行与预对账暂存不一致");
        proof
            .append(physical)
            .append(':')
            .append(operation)
            .append(':')
            .append(observed.count())
            .append(':')
            .append(observed.hash())
            .append('\n');
      }
    }
    gate.validate(source);
    return Signatures.sha256(proof.toString());
  }

  private void verifyBill(Map<String, Object> batch) throws IOException {
    if (!"SEGMENTED".equals(batch.get("bill_format"))) {
      hashObject((String) batch.get("storage_key"), (String) batch.get("sha256"));
      return;
    }
    var segments =
        core.queryForList(
            "SELECT segment_index,sha256,storage_key,expected_count,expected_amount_minor FROM recon_segments WHERE batch_id=? ORDER BY segment_index",
            batch.get("id"));
    if (segments.size() != ((Number) batch.get("segment_count")).intValue())
      throw conflict("分段账单不完整");
    long rows = 0, amount = 0;
    var manifest = new StringBuilder();
    for (int index = 0; index < segments.size(); index++) {
      var part = segments.get(index);
      if (((Number) part.get("segment_index")).intValue() != index) throw conflict("分段账单序号不连续");
      long count = ((Number) part.get("expected_count")).longValue();
      long value = ((Number) part.get("expected_amount_minor")).longValue();
      rows = Math.addExact(rows, count);
      amount = Math.addExact(amount, value);
      manifest
          .append(index)
          .append(':')
          .append(part.get("sha256"))
          .append(':')
          .append(count)
          .append(':')
          .append(value)
          .append('\n');
      hashObject((String) part.get("storage_key"), (String) part.get("sha256"));
    }
    if (rows != ((Number) batch.get("expected_count")).longValue()
        || amount != ((Number) batch.get("expected_amount_minor")).longValue()
        || !Signatures.sha256(manifest.toString()).equals(batch.get("sha256")))
      throw conflict("分段账单清单与批次不一致");
  }

  private void hashObject(String key, String expected) throws IOException {
    var hash = digest();
    try (var input = storage.open(key)) {
      byte[] buffer = new byte[65536];
      int read;
      while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
    }
    if (!HexFormat.of().formatHex(hash.digest()).equals(expected)) throw conflict("账单原文摘要已变化");
  }

  private void readStaged(String batchId, ShardExportReadGate.Snapshot source, Cell[][] target) {
    try (var connection = gate.source(0).getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT operation,aggregate_id,status,amount_minor,fee_minor,currency,reference FROM recon_local_rows WHERE batch_id=? ORDER BY operation,aggregate_id",
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
      statement.setString(1, batchId);
      fetch(statement, connection);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          String operation = rows.getString("operation");
          String id = rows.getString("aggregate_id");
          int bucket =
              "PAYMENT".equals(operation)
                  ? PaymentBuckets.forPaymentId(id)
                  : "REFUND".equals(operation) ? PaymentBuckets.forRefundId(id) : -1;
          if (bucket < 0) throw conflict("暂存行操作类型无效");
          int physical = source.routes().get(bucket).physical();
          target[physical]["PAYMENT".equals(operation) ? 0 : 1].add(
              id,
              rows.getString("status"),
              rows.getLong("amount_minor"),
              rows.getLong("fee_minor"),
              rows.getString("currency"),
              rows.getString("reference"));
        }
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Reconciliation staged rows unavailable", failure);
    }
  }

  private void readLive(
      Connection connection,
      Map<String, Object> batch,
      ShardExportReadGate.Snapshot source,
      int physical,
      Timestamp from,
      Timestamp until,
      Cell[] target)
      throws SQLException {
    String paymentSql =
        "SELECT id,status,amount_minor,fee_minor,currency,channel_reference FROM payments WHERE merchant_id=? AND channel=? AND created_at>=? AND created_at<? ORDER BY id";
    String refundSql =
        "SELECT id,payment_id,status,amount_minor,refunded_fee_minor,channel_reference FROM refunds WHERE merchant_id=? AND channel=? AND created_at>=? AND created_at<? ORDER BY id";
    for (int operation = 0; operation < 2; operation++) {
      try (var statement =
          connection.prepareStatement(
              operation == 0 ? paymentSql : refundSql,
              ResultSet.TYPE_FORWARD_ONLY,
              ResultSet.CONCUR_READ_ONLY)) {
        statement.setString(1, (String) batch.get("merchant_id"));
        statement.setString(2, (String) batch.get("channel"));
        statement.setTimestamp(3, from);
        statement.setTimestamp(4, until);
        fetch(statement, connection);
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            if (!gate.includeOwned(source, physical, operation == 0 ? "payments" : "refunds", rows))
              continue;
            target[operation].add(
                rows.getString("id"),
                rows.getString("status"),
                rows.getLong("amount_minor"),
                rows.getLong(operation == 0 ? "fee_minor" : "refunded_fee_minor"),
                operation == 0 ? rows.getString("currency") : "CNY",
                rows.getString("channel_reference"));
          }
        }
      }
    }
  }

  private static void fetch(java.sql.PreparedStatement statement, Connection connection)
      throws SQLException {
    statement.setFetchSize(
        connection.getMetaData().getURL().startsWith("jdbc:mysql:") ? Integer.MIN_VALUE : 1000);
  }

  private static long count(Connection connection, String sql) throws SQLException {
    try (var statement = connection.prepareStatement(sql);
        var rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static Cell[][] cells() {
    var result = new Cell[4][2];
    for (int physical = 0; physical < 4; physical++)
      for (int operation = 0; operation < 2; operation++) result[physical][operation] = new Cell();
    return result;
  }

  private record Fingerprint(long count, String hash) {}

  private static final class Cell {
    private final MessageDigest hash = digest();
    private final byte[] number = new byte[8];
    private long count;

    void add(String id, String status, long amount, long fee, String currency, String reference) {
      put(id);
      put(status);
      put(amount);
      put(fee);
      put(currency);
      put(reference);
      count++;
    }

    private void put(String value) {
      if (value == null) {
        put(-1L);
        return;
      }
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      put(bytes.length);
      hash.update(bytes);
    }

    private void put(long value) {
      for (int index = 7; index >= 0; index--) {
        number[index] = (byte) value;
        value >>>= 8;
      }
      hash.update(number);
    }

    Fingerprint finish() {
      return new Fingerprint(count, HexFormat.of().formatHex(hash.digest()));
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static ApiException conflict(String detail) {
    return new ApiException("RECON_SEAL_CONFLICT", 409, detail);
  }
}
