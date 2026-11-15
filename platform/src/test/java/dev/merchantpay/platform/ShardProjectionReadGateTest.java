package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.sharding.ShardProjectionReadGate;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardProjectionReadGateTest {
  private static Map<Integer, DataSource> sources() {
    var sources = new LinkedHashMap<Integer, DataSource>();
    for (int id = 0; id < 4; id++) {
      var data =
          new DriverManagerDataSource(
              "jdbc:h2:mem:projection_gate_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      var sql = new JdbcTemplate(data);
      sql.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      sql.update("INSERT INTO v3_shard_identity VALUES(1,?)", id);
      sql.execute("CREATE TABLE event_transport(id INT PRIMARY KEY,mode VARCHAR(16))");
      sql.update("INSERT INTO event_transport VALUES(1,'CDC')");
      sql.execute(
          "CREATE TABLE projection_control(id INT PRIMARY KEY,active_generation VARCHAR(36))");
      sql.update("INSERT INTO projection_control VALUES(1,'v3-serving')");
      sql.execute(
          "CREATE TABLE projection_generations(id VARCHAR(36),state VARCHAR(16),scan_complete BOOLEAN)");
      sql.update("INSERT INTO projection_generations VALUES('v3-serving','SERVING',TRUE)");
      sql.execute(
          "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
      if (id == 0) {
        sql.execute(
            "CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT,epoch BIGINT)");
        sql.execute("CREATE TABLE v3_shard_cutovers(plan_id VARCHAR(36),state VARCHAR(16))");
        for (int bucket = 0; bucket < 16; bucket++) {
          sql.update("INSERT INTO v3_shard_routes VALUES(?,0,1)", bucket);
          sql.update("INSERT INTO v3_bucket_fences VALUES(?,1,'ACTIVE')", bucket);
        }
      }
      sources.put(id, data);
    }
    return sources;
  }

  @Test
  void commonServingGenerationAndSingleActiveFencePermitGlobalReads() {
    var sources = sources();
    assertThat(new ShardProjectionReadGate(sources).generation()).isEqualTo("v3-serving");
  }

  @Test
  void divergedGenerationOrSplitActiveBucketRefusesGlobalReads() {
    var sources = sources();
    var gate = new ShardProjectionReadGate(sources);
    var target = new JdbcTemplate(sources.get(2));
    target.update("UPDATE projection_control SET active_generation='other'");
    assertThatThrownBy(gate::generation).isInstanceOf(ApiException.class);
    target.update("UPDATE projection_control SET active_generation='v3-serving'");
    target.update("INSERT INTO v3_bucket_fences VALUES(4,1,'ACTIVE')");
    assertThatThrownBy(gate::generation).isInstanceOf(ApiException.class);
  }

  @Test
  void routedCutoverRemainsUnavailableEvenAfterTargetFenceIsActive() {
    var sources = sources();
    var core = new JdbcTemplate(sources.get(0));
    var target = new JdbcTemplate(sources.get(1));
    core.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=4");
    core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
    target.update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
    core.update("INSERT INTO v3_shard_cutovers VALUES('plan','ROUTED')");
    var gate = new ShardProjectionReadGate(sources);
    assertThatThrownBy(gate::generation).isInstanceOf(ApiException.class);
    core.update("UPDATE v3_shard_cutovers SET state='ACTIVE' WHERE plan_id='plan'");
    assertThat(gate.generation()).isEqualTo("v3-serving");
  }
}
