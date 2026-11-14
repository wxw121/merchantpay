package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ShardMigrationIsolationTest {
  @Test
  void coreFenceAndIsolationRecordMoveTogetherAndReleaseAllowsCancellation() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:shard_isolation_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var core = new JdbcTemplate(source);
    for (String sql :
        List.of(
            "CREATE TABLE audit_records(id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),actor VARCHAR(160),operation VARCHAR(64),resource_id VARCHAR(160),detail VARCHAR(1000),created_at TIMESTAMP)",
            "CREATE TABLE outbox_tasks(status VARCHAR(16))",
            "CREATE TABLE payments(status VARCHAR(24))",
            "CREATE TABLE refunds(status VARCHAR(24))",
            "CREATE TABLE inbox_notifications(status VARCHAR(24))",
            "CREATE TABLE domain_events(event_id VARCHAR(160),event_type VARCHAR(64),delivery_mode VARCHAR(16))",
            "CREATE TABLE event_receipts(consumer_name VARCHAR(64),event_id VARCHAR(160))",
            "CREATE TABLE event_quarantine(status VARCHAR(16))",
            "CREATE TABLE v3_shard_copy_runs(plan_id VARCHAR(64),state VARCHAR(24))"))
      core.execute(sql);
    try (var connection = source.getConnection()) {
      for (String migration :
          List.of(
              "V24__shard_catalog.sql", "V25__shard_ownership.sql", "V34__shard_quiescence.sql"))
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("db/migration/" + migration));
      var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
      var catalog = new ShardCatalog(core, tx, Clock.systemUTC());
      var operator = new Actor("ops", null, Set.of("OPS"));
      var plan = catalog.plan("isolate-four", 4, 1, 1, "move", operator);
      var preflight = mock(ShardMigrationPreflight.class);
      when(preflight.inspect(plan.id(), operator))
          .thenReturn(
              new ShardMigrationPreflight.Inspection(
                  plan.id(), 4, 0, 1, 1, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), false));
      when(preflight.targetEmpty(plan)).thenReturn(true);
      var isolation = new ShardMigrationIsolation(catalog, preflight, core, tx, Clock.systemUTC());

      assertThatThrownBy(() -> isolation.quiesce(plan.id(), 1, "stop", false, operator))
          .isInstanceOf(ApiException.class);
      assertThat(fence(core)).isEqualTo("ACTIVE");

      var stopped = isolation.quiesce(plan.id(), 1, "stop", true, operator);
      assertThat(stopped.state()).isEqualTo("QUIESCED");
      assertThat(fence(core)).isEqualTo("FENCED");
      assertThat(isolation.quiesce(plan.id(), 1, "stop", true, operator)).isEqualTo(stopped);
      assertThatThrownBy(() -> catalog.cancel(plan.id(), 1, "cancel", operator))
          .isInstanceOf(ApiException.class);

      when(preflight.targetEmpty(plan)).thenReturn(false);
      assertThatThrownBy(() -> isolation.release(plan.id(), 1, "resume", true, operator))
          .isInstanceOf(ApiException.class);
      assertThat(fence(core)).isEqualTo("FENCED");
      when(preflight.targetEmpty(plan)).thenReturn(true);
      var released = isolation.release(plan.id(), 1, "resume", true, operator);
      assertThat(released.state()).isEqualTo("RELEASED");
      assertThat(fence(core)).isEqualTo("ACTIVE");
      assertThat(catalog.cancel(plan.id(), 1, "cancel", operator).state()).isEqualTo("CANCELLED");
    }
  }

  private static String fence(JdbcTemplate core) {
    return core.queryForObject(
        "SELECT access_state FROM v3_bucket_fences WHERE bucket=4", String.class);
  }
}
