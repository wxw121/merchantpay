package dev.merchantpay.platform.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.PaymentBuckets;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import dev.merchantpay.platform.storage.ObjectStorage;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

class HistoryArchiveServiceTest {
  @TempDir Path directory;

  @Test
  void copiedArchiveRemainsReadableAfterBucketMoves() throws Exception {
    Map<Integer, DataSource> sources = new HashMap<>();
    for (int physical = 0; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:history_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      var jdbc = new JdbcTemplate(source);
      jdbc.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      jdbc.update("INSERT INTO v3_shard_identity VALUES(1,?)", physical);
      jdbc.execute(
          "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
      jdbc.execute(
          "CREATE TABLE v3_history_archives(id VARCHAR(36) PRIMARY KEY,merchant_id VARCHAR(64),payment_id VARCHAR(64),logical_bucket INT,physical_shard INT,route_epoch BIGINT,source_hash VARCHAR(64),object_hash VARCHAR(64),object_key VARCHAR(180),fact_count BIGINT,object_bytes BIGINT,archived_by VARCHAR(160),reason VARCHAR(1000),created_at TIMESTAMP,UNIQUE(payment_id,source_hash),UNIQUE(object_key))");
      jdbc.execute(
          "CREATE TABLE v3_history_event_locators(event_id VARCHAR(160) PRIMARY KEY,merchant_id VARCHAR(64),payment_id VARCHAR(64),logical_bucket INT,archive_id VARCHAR(36),payload_hash VARCHAR(64),event_payload_hash VARCHAR(64),event_row_id VARCHAR(64),state VARCHAR(12) DEFAULT 'WARM_COPY',created_at TIMESTAMP,FOREIGN KEY(archive_id) REFERENCES v3_history_archives(id))");
      jdbc.execute(
          "CREATE TABLE audit_records(id VARCHAR(36) PRIMARY KEY,merchant_id VARCHAR(64),actor VARCHAR(160),operation VARCHAR(64),resource_id VARCHAR(160),detail VARCHAR(1000),created_at TIMESTAMP)");
      jdbc.execute("CREATE TABLE outbox_tasks(status VARCHAR(16))");
      jdbc.execute("CREATE TABLE payments(status VARCHAR(16))");
      jdbc.execute("CREATE TABLE refunds(status VARCHAR(16))");
      jdbc.execute("CREATE TABLE inbox_notifications(status VARCHAR(16))");
      jdbc.execute(
          "CREATE TABLE domain_events(id VARCHAR(64),event_id VARCHAR(160),event_type VARCHAR(64),delivery_mode VARCHAR(16),merchant_id VARCHAR(64),logical_bucket INT,payload VARCHAR(1000),payload_hash VARCHAR(64))");
      jdbc.execute("CREATE TABLE event_receipts(event_id VARCHAR(160),consumer_name VARCHAR(160))");
      if (physical == 0) {
        jdbc.execute(
            "CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT,epoch BIGINT)");
        jdbc.execute(
            "CREATE TABLE v3_shard_cutovers(bucket INT PRIMARY KEY,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
        for (int bucket = 0; bucket < 16; bucket++) {
          jdbc.update(
              "INSERT INTO v3_shard_routes VALUES(?,?,?)",
              bucket,
              bucket == 4 ? 1 : 0,
              bucket == 4 ? 2 : 1);
          jdbc.update(
              "INSERT INTO v3_bucket_fences VALUES(?,1,?)",
              bucket,
              bucket == 4 ? "FENCED" : "ACTIVE");
        }
        jdbc.update("INSERT INTO v3_shard_cutovers VALUES(4,1,2,1,'ACTIVE')");
      } else if (physical == 1) {
        jdbc.update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
      }
      sources.put(physical, source);
    }
    var target = new JdbcTemplate(sources.get(1));
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    String orderNo = "archive-order-0";
    for (int suffix = 1; PaymentBuckets.forBusinessKey("merchant", orderNo) != 4; suffix++)
      orderNo = "archive-order-" + suffix;
    final String archiveOrderNo = orderNo;
    String payload = "{\"id\":\"" + payment + "\",\"merchantOrderNo\":\"" + orderNo + "\"}";
    var fact =
        new HistoryParquetCodec.Fact(
            "PAYMENT",
            "merchant",
            payment,
            payment,
            1_700_000_000_000_000L,
            Signatures.sha256(payload),
            payload);
    String eventPayload =
        "{\"eventId\":\"event-1\",\"merchantId\":\"merchant\","
            + "\"logicalBucket\":4,\"payload\":\"{}\",\"payloadHash\":\""
            + Signatures.sha256("{}")
            + "\"}";
    var event =
        new HistoryParquetCodec.Fact(
            "EVENT",
            "merchant",
            payment,
            "event-1",
            fact.occurredMicros(),
            Signatures.sha256(eventPayload),
            eventPayload);
    target.update(
        "INSERT INTO domain_events VALUES(?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        "event-1",
        "PAYMENT_SUCCEEDED",
        "POLLING",
        "merchant",
        4,
        "{}",
        Signatures.sha256("{}"));
    var storage = new ObjectStorage(directory.toString());
    var file = directory.resolve("archive.parquet");
    var written = HistoryParquetCodec.write(file, List.of(fact, event));
    String key = "history/" + written.sha256() + "-" + UUID.randomUUID() + ".parquet";
    storage.publish(file, key);
    String sourceHash = HistoryArchiveService.sourceHash(List.of(fact, event));
    String archiveId = UUID.randomUUID().toString();
    target.update(
        "INSERT INTO v3_history_archives VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        archiveId,
        "merchant",
        payment,
        4,
        0,
        1,
        sourceHash,
        written.sha256(),
        key,
        2,
        written.bytes(),
        "ops",
        "initial copy",
        Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    new JdbcTemplate(sources.get(0))
        .update(
            "INSERT INTO v3_history_archives VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),
            "merchant",
            payment,
            4,
            0,
            1,
            sourceHash,
            written.sha256(),
            key,
            2,
            written.bytes(),
            "ops",
            "retired source",
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

    var service =
        new HistoryArchiveService(
            new ShardExportReadGate(sources),
            storage,
            JsonMapper.builder().build(),
            new Queries(new JdbcTemplate(sources.get(0)), Clock.systemUTC(), "cursor-key"),
            Clock.systemUTC());
    var result =
        service.read("merchant", payment, new Actor("user", "merchant", Set.of("MERCHANT")));
    assertThat(result.consistency()).isEqualTo("HISTORICAL_COPY");
    assertThat(result.archive().physicalShard()).isZero();
    assertThat(result.archive().logicalBucket()).isEqualTo(4);
    assertThat(result.facts()).containsExactly(fact, event);
    assertThat(
            service.readFact(
                "merchant",
                payment,
                "PAYMENT",
                payment,
                new Actor("user", "merchant", Set.of("MERCHANT"))))
        .isEqualTo(fact);
    assertThatThrownBy(
            () ->
                service.readFact(
                    "other",
                    payment,
                    "PAYMENT",
                    payment,
                    new Actor("user", "merchant", Set.of("MERCHANT"))))
        .hasMessageContaining("资源不存在");
    var operator = new Actor("ops", null, Set.of("OPS"));
    assertThatThrownBy(
            () ->
                service.backfillEventLocators(
                    "merchant", payment, archiveId, "without stop", false, operator))
        .hasMessageContaining("回填前须停写");
    target.update("INSERT INTO outbox_tasks VALUES('READY')");
    assertThatThrownBy(
            () ->
                service.backfillEventLocators(
                    "merchant", payment, archiveId, "pending task", true, operator))
        .hasMessageContaining("来源任务或事件尚未排空");
    assertThat(target.queryForObject("SELECT COUNT(*) FROM v3_history_event_locators", Long.class))
        .isZero();
    target.update("DELETE FROM outbox_tasks");
    assertThat(
            service
                .backfillEventLocators(
                    "merchant", payment, archiveId, "verified old archive", true, operator)
                .id())
        .isEqualTo(archiveId);
    assertThat(
            service
                .backfillEventLocators(
                    "merchant", payment, archiveId, "verified old archive again", true, operator)
                .id())
        .isEqualTo(archiveId);
    assertThat(target.queryForObject("SELECT COUNT(*) FROM v3_history_event_locators", Long.class))
        .isEqualTo(1L);
    var merchantActor = new Actor("user", "merchant", Set.of("MERCHANT"));
    assertThat(service.readEvent("merchant", payment, event.factId(), merchantActor))
        .isEqualTo(event);
    assertThatThrownBy(() -> service.readEvent("merchant", payment, "missing", merchantActor))
        .hasMessageContaining("没有可核对的归档定位记录");
    target.update(
        "UPDATE v3_history_event_locators SET payload_hash=? WHERE event_id=?",
        Signatures.sha256("altered"),
        event.factId());
    assertThatThrownBy(() -> service.readEvent("merchant", payment, event.factId(), merchantActor))
        .hasMessageContaining("归档事件定位与对象事实不一致");
    var page =
        service.list(
            "merchant",
            "2025-12-31T00:00:00Z",
            "2026-01-02T00:00:00Z",
            null,
            new Actor("user", "merchant", Set.of("MERCHANT")));
    assertThat(page.items())
        .extracting(HistoryArchiveService.Archive::id)
        .containsExactly(archiveId);
    assertThat(page.nextCursor()).isNull();

    for (int second = 1; second <= 55; second++) {
      String listedPayment = "p4_" + UUID.randomUUID();
      target.update(
          "INSERT INTO v3_history_archives VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          "merchant",
          listedPayment,
          4,
          0,
          1,
          Signatures.sha256(listedPayment),
          written.sha256(),
          "history/" + written.sha256() + "-" + UUID.randomUUID() + ".parquet",
          1,
          written.bytes(),
          "ops",
          "page sample",
          Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(second)));
    }
    String corePayment = "p0_" + UUID.randomUUID();
    String coreArchive = UUID.randomUUID().toString();
    new JdbcTemplate(sources.get(0))
        .update(
            "INSERT INTO v3_history_archives VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            coreArchive,
            "merchant",
            corePayment,
            0,
            0,
            1,
            Signatures.sha256(corePayment),
            written.sha256(),
            "history/" + written.sha256() + "-" + UUID.randomUUID() + ".parquet",
            1,
            written.bytes(),
            "ops",
            "core sample",
            Timestamp.from(Instant.parse("2026-01-01T00:00:30Z")));
    var firstPage =
        service.list(
            "merchant",
            "2025-12-31T00:00:00Z",
            "2026-01-02T00:00:00Z",
            null,
            new Actor("user", "merchant", Set.of("MERCHANT")));
    var secondPage =
        service.list(
            "merchant",
            firstPage.from().toString(),
            firstPage.until().toString(),
            firstPage.nextCursor(),
            new Actor("user", "merchant", Set.of("MERCHANT")));
    assertThat(firstPage.items()).hasSize(50);
    assertThat(firstPage.items())
        .extracting(HistoryArchiveService.Archive::id)
        .contains(coreArchive);
    assertThat(secondPage.items()).hasSize(7);
    assertThat(secondPage.items())
        .extracting(HistoryArchiveService.Archive::id)
        .contains(archiveId);
    assertThat(secondPage.nextCursor()).isNull();
    target.execute("ALTER TABLE payments ADD COLUMN id VARCHAR(64)");
    target.execute("ALTER TABLE payments ADD COLUMN merchant_id VARCHAR(64)");
    target.execute("ALTER TABLE payments ADD COLUMN merchant_order_no VARCHAR(100)");
    target.update(
        "INSERT INTO payments(status,id,merchant_id,merchant_order_no) VALUES(?,?,?,?)",
        "SUCCEEDED",
        payment,
        "merchant",
        orderNo);
    assertThat(service.readByBusinessKey("merchant", orderNo, merchantActor).facts())
        .containsExactly(fact, event);
    target.update(
        "UPDATE payments SET id=? WHERE id=?", "p5_00000000-0000-0000-0000-000000000000", payment);
    assertThatThrownBy(() -> service.readByBusinessKey("merchant", archiveOrderNo, merchantActor))
        .hasMessageContaining("桶归属不一致");
  }
}
