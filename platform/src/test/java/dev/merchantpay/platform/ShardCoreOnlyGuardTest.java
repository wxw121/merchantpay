package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.merchantpay.platform.sharding.ShardCoreOnlyGuard;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardCoreOnlyGuardTest {
  private static JdbcTemplate core() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:shard_bulk_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute(
        "CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT,epoch BIGINT)");
    jdbc.execute(
        "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
    for (int bucket = 0; bucket < 16; bucket++) {
      jdbc.update("INSERT INTO v3_shard_routes VALUES(?,0,1)", bucket);
      jdbc.update("INSERT INTO v3_bucket_fences VALUES(?,1,'ACTIVE')", bucket);
    }
    return jdbc;
  }

  @Test
  void refusesCoreBulkWhenAnyBucketMovesOrItsFenceIsMissing() {
    var core = core();
    var guard = new ShardCoreOnlyGuard(core);
    guard.requireCoreOnly();

    core.update("UPDATE v3_shard_routes SET physical_shard=2 WHERE bucket=7");
    assertThatThrownBy(guard::requireCoreOnly).hasMessageContaining("单库对账、导出和财务写入已停用");

    core.update("UPDATE v3_shard_routes SET physical_shard=0 WHERE bucket=7");
    core.update("DELETE FROM v3_bucket_fences WHERE bucket=7");
    assertThatThrownBy(guard::requireCoreOnly).hasMessageContaining("单库对账、导出和财务写入已停用");
  }
}
