package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.sharding.ShardProjectionReadiness;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardProjectionReadinessTest {
  private static Map<Integer, DataSource> sources() {
    var sources = new LinkedHashMap<Integer, DataSource>();
    for (int id = 0; id < 4; id++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:projection_sources_"
                  + UUID.randomUUID()
                  + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      var sql = new JdbcTemplate(source);
      sql.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      sql.update("INSERT INTO v3_shard_identity VALUES(1,?)", id);
      sql.execute(
          "CREATE TABLE event_transport(id INT PRIMARY KEY,mode VARCHAR(16),cutoff_sequence BIGINT)");
      sql.update("INSERT INTO event_transport VALUES(1,'CDC',0)");
      sql.execute(
          "CREATE TABLE projection_control(id INT PRIMARY KEY,active_generation VARCHAR(36),building_generation VARCHAR(36))");
      sql.update("INSERT INTO projection_control VALUES(1,NULL,'v2-initial')");
      sql.execute(
          "CREATE TABLE projection_generations(id VARCHAR(36) PRIMARY KEY,scan_complete BOOLEAN)");
      sql.update("INSERT INTO projection_generations VALUES('v2-initial',FALSE)");
      sql.execute("CREATE TABLE projection_batches(id VARCHAR(36),state VARCHAR(16))");
      sql.execute("CREATE TABLE projection_errors(id VARCHAR(36),state VARCHAR(16))");
      sql.execute(
          "CREATE TABLE domain_events(sequence_no BIGINT PRIMARY KEY,event_id VARCHAR(160))");
      sql.execute("CREATE TABLE projection_receipts(event_id VARCHAR(160) PRIMARY KEY)");
      sources.put(id, source);
    }
    return sources;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> status) {
    return (List<Map<String, Object>>) status.get("sources");
  }

  @Test
  void reportsEachSourceAndOnlyClaimsAnObservedMissingFactAfterDeepScan() {
    var sources = sources();
    var target = new JdbcTemplate(sources.get(2));
    target.update("INSERT INTO domain_events VALUES(7,'event-seven')");
    var readiness = new ShardProjectionReadiness(sources, Clock.systemUTC());
    var ops = new Actor("ops", null, Set.of("OPS"));
    var shallow = rows(readiness.status(ops, false));
    assertThat(shallow).hasSize(4);
    assertThat(shallow.get(2))
        .containsEntry("physicalShard", 2)
        .containsEntry("allocatedSequence", 7L)
        .doesNotContainKey("firstUnprojectedSequence");
    var deep = rows(readiness.status(ops, true));
    assertThat(deep.get(2))
        .containsEntry("firstUnprojectedSequence", 7L)
        .containsEntry("noMissingAtRead", false);
    target.update("INSERT INTO projection_receipts VALUES('event-seven')");
    assertThat(rows(readiness.status(ops, true)).get(2)).containsEntry("noMissingAtRead", true);
  }

  @Test
  void refusesMerchantBoundOrMismatchedPhysicalSource() {
    var sources = sources();
    var readiness = new ShardProjectionReadiness(sources, Clock.systemUTC());
    assertThatThrownBy(() -> readiness.status(new Actor("ops", "merchant-a", Set.of("OPS")), false))
        .isInstanceOf(ApiException.class);
    new JdbcTemplate(sources.get(3)).update("UPDATE v3_shard_identity SET physical_shard=0");
    assertThatThrownBy(() -> readiness.status(new Actor("ops", null, Set.of("OPS")), false))
        .isInstanceOf(IllegalStateException.class);
  }
}
