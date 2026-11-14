package dev.merchantpay.platform.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import dev.merchantpay.platform.storage.ObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HistoryExportServiceTest {
  @TempDir Path temp;

  @Test
  void exportsOnlyVerifiedArchiveFactsWithManifest() throws Exception {
    Map<Integer, DataSource> sources = new HashMap<>();
    for (int physical = 0; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:history_export_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      var jdbc = new JdbcTemplate(source);
      jdbc.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      jdbc.update("INSERT INTO v3_shard_identity VALUES(1,?)", physical);
      jdbc.execute(
          "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
      jdbc.execute(
          "CREATE TABLE v3_history_archives(id VARCHAR(36) PRIMARY KEY,merchant_id VARCHAR(64),payment_id VARCHAR(64),logical_bucket INT,source_hash VARCHAR(64),object_hash VARCHAR(64),object_key VARCHAR(180),fact_count BIGINT,object_bytes BIGINT,created_at TIMESTAMP)");
      if (physical == 0) {
        jdbc.execute(
            "CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT,epoch BIGINT)");
        jdbc.execute(
            "CREATE TABLE v3_shard_cutovers(bucket INT PRIMARY KEY,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
        for (int bucket = 0; bucket < 16; bucket++) {
          jdbc.update("INSERT INTO v3_shard_routes VALUES(?,0,1)", bucket);
          jdbc.update("INSERT INTO v3_bucket_fences VALUES(?,1,'ACTIVE')", bucket);
        }
      }
      sources.put(physical, source);
    }
    String payment = "p4_00000000-0000-0000-0000-000000000004";
    String payload = "{\"status\":\"SUCCEEDED\"}";
    String payloadHash = Signatures.sha256(payload);
    var fact =
        new HistoryParquetCodec.Fact(
            "PAYMENT", "merchant", payment, payment, 1, payloadHash, payload);
    var storage = new ObjectStorage(temp.toString());
    Path parquet = temp.resolve("source.parquet");
    var written = HistoryParquetCodec.write(parquet, List.of(fact));
    String key =
        storage.publish(
            parquet, "history/" + written.sha256() + "-" + UUID.randomUUID() + ".parquet");
    new JdbcTemplate(sources.get(0))
        .update(
            "INSERT INTO v3_history_archives VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),
            "merchant",
            payment,
            4,
            HistoryArchiveService.sourceHash(List.of(fact)),
            written.sha256(),
            key,
            1,
            written.bytes(),
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    var gate = new ShardExportReadGate(sources);
    Path output = temp.resolve("result.zip");
    long count =
        new HistoryExportService(gate, storage)
            .write(
                output,
                UUID.randomUUID().toString(),
                "merchant",
                Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                gate.capture(),
                ignored -> {});
    assertThat(count).isEqualTo(1);
    try (var zip = new ZipInputStream(Files.newInputStream(output))) {
      assertThat(zip.getNextEntry().getName()).isEqualTo("history-001.csv");
      assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
          .contains("archive_id,payment_id")
          .contains(payment)
          .contains("SUCCEEDED");
      assertThat(zip.getNextEntry().getName()).isEqualTo("manifest.json");
      assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
          .contains("\"rows\":1");
    }
    var core = new JdbcTemplate(sources.get(0));
    core.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=4");
    core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
    core.update("INSERT INTO v3_shard_cutovers VALUES(4,1,2,1,'ACTIVE')");
    new JdbcTemplate(sources.get(1)).update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
    new JdbcTemplate(sources.get(1))
        .update(
            "INSERT INTO v3_history_archives VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),
            "merchant",
            payment,
            4,
            HistoryArchiveService.sourceHash(List.of(fact)),
            written.sha256(),
            key,
            1,
            written.bytes(),
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    long movedCount =
        new HistoryExportService(gate, storage)
            .write(
                temp.resolve("moved.zip"),
                UUID.randomUUID().toString(),
                "merchant",
                Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                gate.capture(),
                ignored -> {});
    assertThat(movedCount).isEqualTo(1);
    Files.writeString(storage.localPath(key), "tampered");
    assertThatThrownBy(
            () ->
                new HistoryExportService(gate, storage)
                    .write(
                        temp.resolve("tampered.zip"),
                        UUID.randomUUID().toString(),
                        "merchant",
                        Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),
                        Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                        gate.capture(),
                        ignored -> {}))
        .isInstanceOf(java.io.IOException.class);
  }
}
