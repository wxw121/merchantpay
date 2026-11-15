package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import dev.merchantpay.platform.sharding.ShardCatalog;
import dev.merchantpay.platform.sharding.ShardProjectionGenerationMaintenance;
import dev.merchantpay.platform.sharding.ShardProjectionService;
import dev.merchantpay.platform.sharding.ShardTransactions;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ShardProjectionServiceTest {
  private static Map<Integer, DataSource> sources() {
    var result = new LinkedHashMap<Integer, DataSource>();
    for (int id = 0; id < 4; id++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:shard_projection_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      var sql = new JdbcTemplate(source);
      sql.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      sql.update("INSERT INTO v3_shard_identity VALUES(1,?)", id);
      sql.execute("CREATE TABLE event_transport(id INT PRIMARY KEY,mode VARCHAR(16))");
      sql.update("INSERT INTO event_transport VALUES(1,'CDC')");
      sql.execute(
          "CREATE TABLE projection_control(id INT PRIMARY KEY,active_generation VARCHAR(36),building_generation VARCHAR(36),version BIGINT)");
      sql.update("INSERT INTO projection_control VALUES(1,NULL,'v2-initial',1)");
      sql.execute(
          "CREATE TABLE projection_generations(id VARCHAR(36) PRIMARY KEY,state VARCHAR(16),scan_cursor VARCHAR(64) DEFAULT '',scan_complete BOOLEAN DEFAULT FALSE,created_at TIMESTAMP)");
      sql.update(
          "INSERT INTO projection_generations(id,state,created_at) VALUES('v2-initial','BUILDING',CURRENT_TIMESTAMP)");
      sql.execute(
          "CREATE TABLE domain_events(id VARCHAR(36),event_id VARCHAR(160) PRIMARY KEY,payload TEXT,logical_bucket INT)");
      sql.execute(
          "CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),merchant_order_no VARCHAR(64),amount_minor BIGINT,fee_minor BIGINT,currency VARCHAR(8),status VARCHAR(32),refunded_minor BIGINT,reserved_minor BIGINT,refunded_fee_minor BIGINT,channel VARCHAR(32),channel_reference VARCHAR(64),notification_url VARCHAR(255),created_at TIMESTAMP,updated_at TIMESTAMP,succeeded_at TIMESTAMP)");
      sql.execute(
          "CREATE TABLE refunds(id VARCHAR(64),payment_id VARCHAR(64),merchant_id VARCHAR(64))");
      sql.execute(
          "CREATE TABLE finance_adjustments(id VARCHAR(64),payment_id VARCHAR(64),merchant_id VARCHAR(64))");
      sql.execute(
          "CREATE TABLE projection_receipts(event_id VARCHAR(160) PRIMARY KEY,payload_hash VARCHAR(64),topic VARCHAR(160),partition_no INT,record_offset BIGINT,created_at TIMESTAMP)");
      sql.execute(
          "CREATE TABLE projection_counters(generation VARCHAR(36),payment_id VARCHAR(64),revision BIGINT,PRIMARY KEY(generation,payment_id))");
      sql.execute(
          "CREATE TABLE projection_batches(id VARCHAR(36),generation VARCHAR(36),payment_id VARCHAR(64),merchant_id VARCHAR(64),revision BIGINT,snapshot TEXT,snapshot_hash VARCHAR(64),state VARCHAR(16),attempts INT DEFAULT 0,last_error VARCHAR(160),due_at TIMESTAMP,created_at TIMESTAMP)");
      sql.execute(
          "CREATE TABLE projection_errors(id VARCHAR(36),topic VARCHAR(160),partition_no INT,record_offset BIGINT,payload TEXT,payload_hash VARCHAR(64),state VARCHAR(16),reason VARCHAR(1024),created_at TIMESTAMP,replayed_by VARCHAR(160))");
      sql.execute(
          "CREATE TABLE audit_records(id VARCHAR(36),merchant_id VARCHAR(64),actor VARCHAR(160),operation VARCHAR(64),resource_id VARCHAR(64),detail VARCHAR(1000),created_at TIMESTAMP)");
      result.put(id, source);
    }
    return result;
  }

  private static ShardProjectionService service(Map<Integer, DataSource> sources, JsonMapper json) {
    return service(sources, json, false);
  }

  private static ShardProjectionService service(
      Map<Integer, DataSource> sources, JsonMapper json, boolean allowPartialPromotion) {
    var core = new JdbcTemplate(sources.get(0));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(sources.get(0)));
    var events = new EventDelivery(core, tx, Clock.systemUTC(), json);
    return new ShardProjectionService(
        sources,
        Clock.systemUTC(),
        json,
        events,
        mock(ClickHouseReadModel.class),
        allowPartialPromotion);
  }

  @Test
  void sourceTopicCreatesOnlyOneLocalReceiptAndProjectionBatch() {
    var sources = sources();
    var json = JsonMapper.builder().build();
    var target = new JdbcTemplate(sources.get(1));
    target.update(
        "INSERT INTO payments(id,merchant_id,merchant_order_no,amount_minor,fee_minor,currency,status,refunded_minor,reserved_minor,refunded_fee_minor,channel,channel_reference,notification_url,created_at,updated_at) VALUES('p1','merchant-a','order-1',1000,0,'CNY','SUCCEEDED',0,0,0,'MOCK_A',NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    String payload =
        json.writeValueAsString(
            new DomainEvent(
                1,
                "event-1",
                "PAYMENT_SUCCEEDED",
                "p1",
                "merchant-a",
                1,
                Instant.now().toString(),
                ""));
    target.update(
        "INSERT INTO domain_events(id,event_id,payload) VALUES(?,?,?)",
        UUID.randomUUID().toString(),
        "event-1",
        payload);
    var shards = service(sources, json);
    shards.consume("outbox.event.merchantpay.s1", 0, 7, payload);
    shards.consume("outbox.event.merchantpay.s1", 0, 7, payload);
    assertThat(target.queryForObject("SELECT COUNT(*) FROM projection_receipts", Integer.class))
        .isEqualTo(1);
    assertThat(target.queryForObject("SELECT COUNT(*) FROM projection_batches", Integer.class))
        .isEqualTo(1);
    assertThat(target.queryForObject("SELECT payload_hash FROM projection_receipts", String.class))
        .isEqualTo(Signatures.sha256(payload));
    assertThat(
            new JdbcTemplate(sources.get(0))
                .queryForObject("SELECT COUNT(*) FROM projection_receipts", Integer.class))
        .isZero();
    assertThatThrownBy(() -> shards.consume("outbox.event.merchantpay.s0", 0, 7, payload))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
  }

  @Test
  void mismatchedGenerationStopsThePipelineBeforeItCanAcknowledgeEvents() {
    var sources = sources();
    new JdbcTemplate(sources.get(2))
        .update("UPDATE projection_control SET building_generation='different' WHERE id=1");
    assertThatThrownBy(() -> service(sources, JsonMapper.builder().build()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void stoppedPreparationAlignsNewBuildWhilePreservingTheOldCoreServingGeneration()
      throws Exception {
    var sources = sources();
    var core = new JdbcTemplate(sources.get(0));
    core.update(
        "INSERT INTO projection_generations(id,state,created_at) VALUES('v2-serving','SERVING',CURRENT_TIMESTAMP)");
    core.update(
        "UPDATE projection_control SET active_generation='v2-serving',building_generation=NULL");
    var opened = new LinkedHashMap<Integer, Connection>();
    try {
      for (int id = 0; id < 4; id++) opened.put(id, sources.get(id).getConnection());
      ShardProjectionGenerationMaintenance.prepare(opened, "v3-build-1", "four-source build", true);
      ShardProjectionGenerationMaintenance.prepare(opened, "v3-build-1", "four-source build", true);
    } finally {
      for (var connection : opened.values()) connection.close();
    }
    assertThat(
            core.queryForObject(
                "SELECT active_generation FROM projection_control WHERE id=1", String.class))
        .isEqualTo("v2-serving");
    for (int id = 0; id < 4; id++)
      assertThat(
              new JdbcTemplate(sources.get(id))
                  .queryForObject(
                      "SELECT building_generation FROM projection_control WHERE id=1",
                      String.class))
          .isEqualTo("v3-build-1");
    service(sources, JsonMapper.builder().build());
  }

  @Test
  void partialPromotionStartsOnlyInRecoveryModeAndCannotConsume() {
    var sources = sources();
    var target = new JdbcTemplate(sources.get(1));
    target.update("UPDATE projection_generations SET state='SERVING' WHERE id='v2-initial'");
    target.update(
        "UPDATE projection_control SET active_generation='v2-initial',building_generation=NULL");
    var json = JsonMapper.builder().build();
    assertThatThrownBy(() -> service(sources, json)).isInstanceOf(IllegalStateException.class);
    var recovery = service(sources, json, true);
    assertThatThrownBy(() -> recovery.scanNext(0)).hasMessageContaining("提升尚未完成");
  }

  @Test
  void sourceLocalDismissRequiresMatchingTopicAndWritesOnlyLocalAudit() {
    var sources = sources();
    var target = new JdbcTemplate(sources.get(1));
    target.update(
        "INSERT INTO projection_errors VALUES('error-1','outbox.event.merchantpay.s0',0,1,'{}','hash','QUARANTINED','bad',CURRENT_TIMESTAMP,NULL)");
    var shards = service(sources, JsonMapper.builder().build());
    var ops = new Actor("operator", null, Set.of("OPS"));
    assertThatThrownBy(() -> shards.dismiss(1, "error-1", "investigated", ops))
        .hasMessageContaining("来源与所选物理库不一致");
    target.update("UPDATE projection_errors SET topic='outbox.event.merchantpay.s1'");
    assertThat(shards.errors(1, "", ops)).hasSize(1);
    shards.dismiss(1, "error-1", "investigated", ops);
    assertThat(target.queryForObject("SELECT state FROM projection_errors", String.class))
        .isEqualTo("DISMISSED");
    assertThat(target.queryForObject("SELECT COUNT(*) FROM audit_records", Long.class))
        .isEqualTo(1L);
    assertThat(
            new JdbcTemplate(sources.get(0))
                .queryForObject("SELECT COUNT(*) FROM audit_records", Long.class))
        .isZero();
  }

  @Test
  void replayRejectsTamperedEvidenceBeforeCreatingLocalReceipt() {
    var sources = sources();
    var target = new JdbcTemplate(sources.get(2));
    target.update(
        "INSERT INTO projection_errors VALUES('error-2','outbox.event.merchantpay.s2',0,7,'{}','different','QUARANTINED','bad',CURRENT_TIMESTAMP,NULL)");
    var json = JsonMapper.builder().build();
    var core = new JdbcTemplate(sources.get(0));
    var events =
        new EventDelivery(
            core,
            new TransactionTemplate(new DataSourceTransactionManager(sources.get(0))),
            Clock.systemUTC(),
            json);
    var shards =
        new ShardProjectionService(
            sources,
            Clock.systemUTC(),
            json,
            events,
            mock(ClickHouseReadModel.class),
            false,
            mock(ShardTransactions.class));
    assertThatThrownBy(
            () ->
                shards.replay(
                    2, "error-2", "investigated", new Actor("operator", null, Set.of("OPS"))))
        .hasMessageContaining("截断或损坏");
    assertThat(target.queryForObject("SELECT COUNT(*) FROM projection_receipts", Long.class))
        .isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void retryRejectsDamagedSnapshotBeforeRequeueing() {
    var sources = sources();
    var target = new JdbcTemplate(sources.get(1));
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    target.update(
        "INSERT INTO projection_batches(id,generation,payment_id,merchant_id,revision,snapshot,snapshot_hash,state,due_at,created_at) VALUES('batch-1','v2-initial',?,'merchant-a',1,'{}','wrong','DEAD',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        payment);
    var routes = mock(ShardTransactions.class);
    var route = new ShardCatalog.Route(4, 1, 1);
    when(routes.currentRoute(4)).thenReturn(route);
    when(routes.write(eq(route), any()))
        .thenAnswer(call -> ((Function<JdbcTemplate, Object>) call.getArgument(1)).apply(target));
    var json = JsonMapper.builder().build();
    var core = new JdbcTemplate(sources.get(0));
    var events =
        new EventDelivery(
            core,
            new TransactionTemplate(new DataSourceTransactionManager(sources.get(0))),
            Clock.systemUTC(),
            json);
    var shards =
        new ShardProjectionService(
            sources,
            Clock.systemUTC(),
            json,
            events,
            mock(ClickHouseReadModel.class),
            false,
            routes);
    assertThatThrownBy(
            () ->
                shards.retryBatch(
                    1, "batch-1", "investigated", new Actor("operator", null, Set.of("OPS"))))
        .hasMessageContaining("快照校验失败");
    assertThat(target.queryForObject("SELECT state FROM projection_batches", String.class))
        .isEqualTo("DEAD");
  }
}
