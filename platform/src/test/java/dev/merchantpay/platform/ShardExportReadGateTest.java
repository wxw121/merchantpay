package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.merchantpay.platform.sharding.ShardCatalog;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardExportReadGateTest {
  private static Map<Integer, DataSource> sources() {
    var result = new HashMap<Integer, DataSource>();
    for (int physical = 0; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:shard_export_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      var jdbc = new JdbcTemplate(source);
      jdbc.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      jdbc.update("INSERT INTO v3_shard_identity VALUES(1,?)", physical);
      jdbc.execute(
          "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
      if (physical == 0) {
        jdbc.execute(
            "CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT,epoch BIGINT)");
        jdbc.execute(
            "CREATE TABLE v3_shard_cutovers(plan_id VARCHAR(36) PRIMARY KEY,bucket INT,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
        for (int bucket = 0; bucket < 16; bucket++) {
          jdbc.update("INSERT INTO v3_shard_routes VALUES(?,0,1)", bucket);
          jdbc.update("INSERT INTO v3_bucket_fences VALUES(?,1,'ACTIVE')", bucket);
        }
      }
      result.put(physical, source);
    }
    return result;
  }

  @Test
  void fencedSourceIsVisibleOnlyToItsMatchingQuiescedMigration() {
    var sources = sources();
    var core = new JdbcTemplate(sources.get(0));
    core.execute(
        "CREATE TABLE v3_shard_isolations(plan_id VARCHAR(36),bucket INT,source_epoch BIGINT,plan_version BIGINT,state VARCHAR(16))");
    core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
    core.update("INSERT INTO v3_shard_isolations VALUES('plan',4,1,1,'QUIESCED')");
    var plan =
        new ShardCatalog.Plan(
            "plan",
            "request",
            4,
            0,
            1,
            1,
            1,
            "PLANNED",
            "ops",
            "move",
            null,
            null,
            Instant.EPOCH,
            Instant.EPOCH);
    var gate = new ShardExportReadGate(sources);
    assertThatThrownBy(gate::capture).hasMessageContaining("栅栏不一致");
    var snapshot = gate.captureForMigration(plan);
    gate.validateForMigration(snapshot, plan);
    core.update("UPDATE v3_shard_isolations SET state='RELEASED' WHERE plan_id='plan'");
    assertThatThrownBy(() -> gate.validateForMigration(snapshot, plan))
        .hasMessageContaining("栅栏不一致");
  }

  @Test
  void completedCutoverSkipsOnlyTheRetiredCoreCopy() {
    var sources = sources();
    var core = new JdbcTemplate(sources.get(0));
    var target = new JdbcTemplate(sources.get(1));
    core.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=4");
    core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
    target.update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
    core.update("INSERT INTO v3_shard_cutovers VALUES('forward',4,1,2,1,'ACTIVE')");
    core.execute("CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY)");
    target.execute("CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY)");
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    core.update("INSERT INTO payments VALUES(?)", payment);
    target.update("INSERT INTO payments VALUES(?)", payment);
    var gate = new ShardExportReadGate(sources);
    var snapshot = gate.capture();
    core.query(
        "SELECT id FROM payments",
        (RowCallbackHandler)
            rs -> assertThat(gate.includeOwned(snapshot, 0, "payments", rs)).isFalse());
    target.query(
        "SELECT id FROM payments",
        (RowCallbackHandler)
            rs -> assertThat(gate.includeOwned(snapshot, 1, "payments", rs)).isTrue());
    core.execute("CREATE TABLE v3_history_archives(payment_id VARCHAR(64),logical_bucket INT)");
    target.execute("CREATE TABLE v3_history_archives(payment_id VARCHAR(64),logical_bucket INT)");
    core.update("INSERT INTO v3_history_archives VALUES(?,4)", payment);
    target.update("INSERT INTO v3_history_archives VALUES(?,4)", payment);
    core.query(
        "SELECT * FROM v3_history_archives",
        (RowCallbackHandler)
            rs -> assertThat(gate.includeOwned(snapshot, 0, "history", rs)).isFalse());
    target.query(
        "SELECT * FROM v3_history_archives",
        (RowCallbackHandler)
            rs -> assertThat(gate.includeOwned(snapshot, 1, "history", rs)).isTrue());
    core.update("UPDATE v3_shard_cutovers SET state='ROUTED' WHERE bucket=4");
    assertThatThrownBy(() -> gate.validate(snapshot)).hasMessageContaining("导出来源身份、桶归属或栅栏不一致");
  }

  @Test
  void routedRowsMustComeFromTheirCurrentPhysicalOwner() {
    var sources = sources();
    var core = new JdbcTemplate(sources.get(0));
    var target = new JdbcTemplate(sources.get(1));
    core.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=4");
    core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
    target.update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
    core.update("INSERT INTO v3_shard_cutovers VALUES('forward',4,1,2,1,'ACTIVE')");
    target.execute("CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY)");
    target.update("INSERT INTO payments VALUES('p4_00000000-0000-0000-0000-000000000000')");
    var gate = new ShardExportReadGate(sources);
    var snapshot = gate.capture();
    target.query(
        "SELECT id FROM payments",
        (RowCallbackHandler) rs -> gate.assertOwned(snapshot, 1, "payments", rs));
    assertThatThrownBy(
            () ->
                target.query(
                    "SELECT id FROM payments",
                    (RowCallbackHandler) rs -> gate.assertOwned(snapshot, 0, "payments", rs)))
        .hasMessageContaining("导出来源身份、桶归属或栅栏不一致");
    core.update("UPDATE v3_shard_routes SET epoch=3 WHERE bucket=4");
    assertThatThrownBy(() -> gate.validate(snapshot)).hasMessageContaining("导出来源身份、桶归属或栅栏不一致");
  }

  @Test
  void reverseChainUsesTheNewCoreOwnerAndSkipsTheRetiredTargetCopy() {
    var sources = sources();
    var core = new JdbcTemplate(sources.get(0));
    var oldTarget = new JdbcTemplate(sources.get(1));
    core.update("UPDATE v3_shard_routes SET epoch=3 WHERE bucket=4");
    core.update("UPDATE v3_bucket_fences SET route_epoch=3 WHERE bucket=4");
    oldTarget.update("INSERT INTO v3_bucket_fences VALUES(4,2,'FENCED')");
    core.update("INSERT INTO v3_shard_cutovers VALUES('forward',4,1,2,1,'ACTIVE')");
    core.update("INSERT INTO v3_shard_cutovers VALUES('reverse',4,2,3,0,'ACTIVE')");
    String payment = "p4_00000000-0000-0000-0000-000000000000";
    core.execute("CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY)");
    oldTarget.execute("CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY)");
    core.update("INSERT INTO payments VALUES(?)", payment);
    oldTarget.update("INSERT INTO payments VALUES(?)", payment);
    var gate = new ShardExportReadGate(sources);
    var snapshot = gate.capture();
    assertThat(snapshot.retiredSources().get(4)).containsEntry(1, 2L);
    core.query(
        "SELECT id FROM payments",
        (RowCallbackHandler)
            rs -> assertThat(gate.includeOwned(snapshot, 0, "payments", rs)).isTrue());
    oldTarget.query(
        "SELECT id FROM payments",
        (RowCallbackHandler)
            rs -> assertThat(gate.includeOwned(snapshot, 1, "payments", rs)).isFalse());
    oldTarget.update("UPDATE v3_bucket_fences SET access_state='ACTIVE' WHERE bucket=4");
    assertThatThrownBy(gate::capture).hasMessageContaining("导出来源身份、桶归属或栅栏不一致");
  }
}
