package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.sharding.ShardCatalog;
import dev.merchantpay.platform.sharding.ShardTargetHttpAdmission;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardTargetHttpAdmissionTest {
  @Test
  void onlyACompletedCutoverWithItsVerifiedCopyAllowsTargetHttpWrites() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:target_http_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var core = new JdbcTemplate(source);
    core.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
    core.execute(
        "CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT,epoch BIGINT)");
    core.execute(
        "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
    core.execute(
        "CREATE TABLE v3_shard_migrations(id VARCHAR(36) PRIMARY KEY,bucket INT,source_shard INT,target_shard INT,source_epoch BIGINT,version BIGINT,state VARCHAR(16))");
    core.execute(
        "CREATE TABLE v3_shard_isolations(plan_id VARCHAR(36) PRIMARY KEY,bucket INT,source_epoch BIGINT,plan_version BIGINT,state VARCHAR(16))");
    core.execute(
        "CREATE TABLE v3_shard_copy_runs(plan_id VARCHAR(36) PRIMARY KEY,bucket INT,source_epoch BIGINT,plan_version BIGINT,manifest_hash VARCHAR(64),target_hash VARCHAR(64),state VARCHAR(16))");
    core.execute(
        "CREATE TABLE v3_shard_cutovers(plan_id VARCHAR(36) PRIMARY KEY,bucket INT,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,plan_version BIGINT,manifest_hash VARCHAR(64),state VARCHAR(16))");
    core.update("INSERT INTO v3_shard_identity VALUES(1,0)");
    core.update("INSERT INTO v3_shard_routes VALUES(4,1,2)");
    core.update("INSERT INTO v3_bucket_fences VALUES(4,1,'FENCED')");
    core.update("INSERT INTO v3_shard_migrations VALUES('plan',4,0,1,1,2,'MOVED')");
    core.update("INSERT INTO v3_shard_isolations VALUES('plan',4,1,1,'QUIESCED')");
    core.update("INSERT INTO v3_shard_copy_runs VALUES('plan',4,1,1,'hash','hash','VERIFIED')");
    var gate = new ShardTargetHttpAdmission(core);
    var route = new ShardCatalog.Route(4, 1, 2);

    assertThatThrownBy(() -> gate.require(route)).isInstanceOf(ApiException.class);
    core.update("INSERT INTO v3_shard_cutovers VALUES('plan',4,1,2,1,1,'hash','ROUTED')");
    assertThatThrownBy(() -> gate.require(route)).isInstanceOf(ApiException.class);
    core.update("UPDATE v3_shard_cutovers SET state='ACTIVE' WHERE plan_id='plan'");
    gate.require(route);
    core.update("UPDATE v3_shard_copy_runs SET target_hash='drift' WHERE plan_id='plan'");
    assertThatThrownBy(() -> gate.require(route)).isInstanceOf(ApiException.class);
    core.update("UPDATE v3_shard_copy_runs SET target_hash='hash' WHERE plan_id='plan'");
    core.update("UPDATE v3_bucket_fences SET access_state='ACTIVE' WHERE bucket=4");
    assertThatThrownBy(() -> gate.require(route)).isInstanceOf(ApiException.class);
    core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
    assertThatThrownBy(() -> gate.require(new ShardCatalog.Route(4, 1, 3)))
        .isInstanceOf(ApiException.class);
  }
}
