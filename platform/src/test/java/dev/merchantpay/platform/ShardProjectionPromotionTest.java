package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.sharding.ShardProjectionPromotion;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

class ShardProjectionPromotionTest {
  private static Map<Integer, DataSource> sources(boolean complete) {
    var result = new LinkedHashMap<Integer, DataSource>();
    for (int id = 0; id < 4; id++) {
      var data =
          new DriverManagerDataSource(
              "jdbc:h2:mem:shard_promote_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      var jdbc = new JdbcTemplate(data);
      jdbc.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      jdbc.update("INSERT INTO v3_shard_identity VALUES(1,?)", id);
      jdbc.execute("CREATE TABLE event_transport(id INT PRIMARY KEY,mode VARCHAR(16))");
      jdbc.update("INSERT INTO event_transport VALUES(1,'CDC')");
      jdbc.execute(
          "CREATE TABLE projection_control(id INT PRIMARY KEY,active_generation VARCHAR(36),building_generation VARCHAR(36),version BIGINT)");
      jdbc.update("INSERT INTO projection_control VALUES(1,NULL,'v3-build',1)");
      jdbc.execute(
          "CREATE TABLE projection_generations(id VARCHAR(36) PRIMARY KEY,state VARCHAR(16),scan_complete BOOLEAN,activated_at TIMESTAMP)");
      jdbc.update(
          "INSERT INTO projection_generations VALUES('v3-build','BUILDING',?,NULL)",
          complete || id != 2);
      jdbc.execute("CREATE TABLE projection_batches(generation VARCHAR(36),state VARCHAR(16))");
      jdbc.execute("CREATE TABLE projection_errors(state VARCHAR(16))");
      jdbc.execute("CREATE TABLE domain_events(event_id VARCHAR(160))");
      jdbc.execute("CREATE TABLE projection_receipts(event_id VARCHAR(160))");
      jdbc.execute(
          "CREATE TABLE payments(id VARCHAR(64),merchant_id VARCHAR(64),merchant_order_no VARCHAR(64),amount_minor BIGINT,fee_minor BIGINT,currency VARCHAR(8),status VARCHAR(32),refunded_minor BIGINT,reserved_minor BIGINT,refunded_fee_minor BIGINT,channel VARCHAR(32),channel_reference VARCHAR(64),notification_url VARCHAR(255),created_at TIMESTAMP,updated_at TIMESTAMP,succeeded_at TIMESTAMP)");
      jdbc.execute(
          "CREATE TABLE projection_counters(generation VARCHAR(36),payment_id VARCHAR(64),revision BIGINT)");
      jdbc.execute(
          "CREATE TABLE audit_records(id VARCHAR(64),actor VARCHAR(160),operation VARCHAR(64),resource_id VARCHAR(160),detail VARCHAR(1000),created_at TIMESTAMP)");
      if (id == 0) {
        jdbc.execute("CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT)");
        for (int bucket = 0; bucket < 16; bucket++)
          jdbc.update("INSERT INTO v3_shard_routes VALUES(?,0)", bucket);
      }
      result.put(id, data);
    }
    return result;
  }

  private static ShardProjectionPromotion promotion(
      Map<Integer, DataSource> sources, ClickHouseReadModel clickhouse) {
    return new ShardProjectionPromotion(
        sources, clickhouse, JsonMapper.builder().build(), Clock.systemUTC());
  }

  private static Actor operator() {
    return new Actor("operator", null, Set.of("OPS"));
  }

  @Test
  void emptyFourSourceGenerationCanBePromotedAndRetried() {
    var sources = sources(true);
    var clickhouse = mock(ClickHouseReadModel.class);
    when(clickhouse.query(anyString(), anyMap()))
        .thenAnswer(
            call ->
                ((String) call.getArgument(0)).contains("count()")
                    ? List.of(Map.of("count", "0"))
                    : List.of());
    var target = promotion(sources, clickhouse);
    target.promote("v3-build", true, "four sources reconciled", operator());
    target.promote("v3-build", true, "retry", operator());
    for (int id = 0; id < 4; id++) {
      var jdbc = new JdbcTemplate(sources.get(id));
      assertThat(
              jdbc.queryForObject("SELECT active_generation FROM projection_control", String.class))
          .isEqualTo("v3-build");
      assertThat(
              jdbc.queryForObject(
                  "SELECT building_generation FROM projection_control", String.class))
          .isNull();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_records", Long.class))
          .isEqualTo(1L);
    }
  }

  @Test
  void incompleteSourcePreventsEveryPromotion() {
    var sources = sources(false);
    var clickhouse = mock(ClickHouseReadModel.class);
    assertThatThrownBy(
            () ->
                promotion(sources, clickhouse).promote("v3-build", true, "not scanned", operator()))
        .hasMessageContaining("全量扫描尚未完成");
    verifyNoInteractions(clickhouse);
    for (int id = 0; id < 4; id++)
      assertThat(
              new JdbcTemplate(sources.get(id))
                  .queryForObject("SELECT active_generation FROM projection_control", String.class))
          .isNull();
  }

  @Test
  void missingGlobalProjectionRowPreventsEveryPromotion() {
    var sources = sources(true);
    new JdbcTemplate(sources.get(1))
        .update(
            "INSERT INTO payments(id,merchant_id,merchant_order_no,amount_minor,fee_minor,currency,status,refunded_minor,reserved_minor,refunded_fee_minor,channel,created_at,updated_at) VALUES('p1','merchant-a','order-1',1000,0,'CNY','SUCCEEDED',0,0,0,'MOCK_A',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    var clickhouse = mock(ClickHouseReadModel.class);
    when(clickhouse.query(anyString(), anyMap())).thenReturn(List.of());
    assertThatThrownBy(
            () ->
                promotion(sources, clickhouse)
                    .promote("v3-build", true, "missing global row", operator()))
        .hasMessageContaining("数量不一致");
    for (int id = 0; id < 4; id++)
      assertThat(
              new JdbcTemplate(sources.get(id))
                  .queryForObject("SELECT active_generation FROM projection_control", String.class))
          .isNull();
  }

  @Test
  void matchingSnapshotStillRejectsPaymentOnTheWrongPhysicalSource() {
    var sources = sources(true);
    String id = "p4_00000000-0000-0000-0000-000000000000";
    var source = new JdbcTemplate(sources.get(1));
    source.update(
        "INSERT INTO payments(id,merchant_id,merchant_order_no,amount_minor,fee_minor,currency,status,refunded_minor,reserved_minor,refunded_fee_minor,channel,created_at,updated_at) VALUES(?,'merchant-a','order-1',1000,0,'CNY','SUCCEEDED',0,0,0,'MOCK_A',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        id);
    source.update("INSERT INTO projection_counters VALUES('v3-build',?,1)", id);
    var row =
        source
            .query(
                "SELECT id,merchant_id,merchant_order_no,amount_minor,fee_minor,currency,status,refunded_minor,reserved_minor,refunded_fee_minor,channel,channel_reference,notification_url,created_at,updated_at,succeeded_at FROM payments",
                Queries.ROW)
            .getFirst();
    String hash = Signatures.sha256(JsonMapper.builder().build().writeValueAsString(row));
    var clickhouse = mock(ClickHouseReadModel.class);
    when(clickhouse.query(anyString(), anyMap()))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              @SuppressWarnings("unchecked")
              Map<String, String> parameters = call.getArgument(1);
              if (sql.contains("count()")) return List.of(Map.of("count", "1"));
              return parameters.get("after").isEmpty()
                  ? List.of(Map.of("payment_id", id, "snapshot_hash", hash, "revision", "1"))
                  : List.of();
            });
    assertThatThrownBy(
            () ->
                promotion(sources, clickhouse).promote("v3-build", true, "wrong owner", operator()))
        .hasMessageContaining("来源与桶归属不一致");
    for (int physical = 0; physical < 4; physical++)
      assertThat(
              new JdbcTemplate(sources.get(physical))
                  .queryForObject("SELECT active_generation FROM projection_control", String.class))
          .isNull();
  }
}
