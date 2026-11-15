package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardMigrationPreflightTest {
  private static final String PAYMENT = "p4_00000000-0000-0000-0000-000000000004";

  @Test
  void factOnlyRegistryIsCountedAndOrphanRegistryBlocksMigration() {
    var sources = new HashMap<Integer, DataSource>();
    for (int physical = 0; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:migration_preflight_"
                  + UUID.randomUUID()
                  + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      sources.put(physical, source);
      schema(new JdbcTemplate(source), physical);
    }
    var core = new JdbcTemplate(sources.get(0));
    var target = new JdbcTemplate(sources.get(1));
    core.execute(
        "CREATE TABLE v3_shard_cutovers(plan_id VARCHAR(36) PRIMARY KEY,bucket INT,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
    core.update(
        "INSERT INTO domain_events VALUES(1,'fact-id','fact-event',?,4,'PAYMENT_FACT','POLLING')",
        PAYMENT);
    core.update("INSERT INTO outbox_events VALUES('fact-id','fact-event')");
    var catalog = mock(ShardCatalog.class);
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
    var actor = new Actor("ops", null, Set.of("OPS"));
    when(catalog.detail("plan", actor)).thenReturn(plan);
    var preflight = new ShardMigrationPreflight(catalog, new ShardExportReadGate(sources), core);

    var initial = preflight.inspect("plan", actor);
    assertThat(initial.bucketRows().get("domain_events").source()).isEqualTo(1);
    assertThat(initial.bucketRows().get("outbox_events").source()).isEqualTo(1);
    assertThat(initial.blockers()).isEmpty();
    assertThat(initial.executionEnabled()).isFalse();

    target.update("INSERT INTO outbox_events VALUES('lost-id','lost-event')");
    var orphan = preflight.inspect("plan", actor);
    assertThat(orphan.blockers()).contains("SOURCE_1_ORPHAN_EVENT_REGISTRY");
    assertThat(orphan.executionEnabled()).isFalse();

    core.update(
        "INSERT INTO refunds VALUES('r5_00000000-0000-0000-0000-000000000005',?,'SUCCEEDED')",
        PAYMENT);
    var brokenRoot = preflight.inspect("plan", actor);
    assertThat(brokenRoot.blockers()).contains("REFUND_ROOT_MISMATCH");
  }

  @Test
  void onlyOverlappingAutoIdsRequireRemapping() {
    var sources = new HashMap<Integer, DataSource>();
    for (int physical = 0; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:migration_ids_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      sources.put(physical, source);
      schema(new JdbcTemplate(source), physical);
    }
    var core = new JdbcTemplate(sources.get(0));
    var target = new JdbcTemplate(sources.get(1));
    core.execute(
        "CREATE TABLE v3_shard_cutovers(plan_id VARCHAR(36) PRIMARY KEY,bucket INT,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
    core.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=5");
    core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=5");
    core.update("INSERT INTO v3_shard_cutovers VALUES('prior-plan',5,1,2,1,'ACTIVE')");
    target.update("INSERT INTO v3_bucket_fences VALUES(5,2,'ACTIVE')");
    core.update("INSERT INTO payments VALUES(?, 'SUCCEEDED',4)", PAYMENT);
    core.update("INSERT INTO ledger_journals VALUES('journal-4',?)", PAYMENT);
    core.update(
        "INSERT INTO ledger_entries VALUES(7,'journal-4',?,'merchant','MERCHANT_PAYABLE')",
        PAYMENT);
    core.update(
        "INSERT INTO domain_events VALUES(7,'fact-4','event-4',?,4,'PAYMENT_FACT','POLLING')",
        PAYMENT);
    String prior = "p5_00000000-0000-0000-0000-000000000005";
    target.update("INSERT INTO payments VALUES(?,'SUCCEEDED',5)", prior);
    target.update("INSERT INTO ledger_journals VALUES('journal-5',?)", prior);
    target.update(
        "INSERT INTO ledger_entries VALUES(8,'journal-5',?,'merchant','MERCHANT_PAYABLE')", prior);
    target.update(
        "INSERT INTO domain_events VALUES(8,'fact-5','event-5',?,5,'PAYMENT_FACT','POLLING')",
        prior);
    var catalog = mock(ShardCatalog.class);
    var actor = new Actor("ops", null, Set.of("OPS"));
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
    when(catalog.detail("plan", actor)).thenReturn(plan);
    var preflight = new ShardMigrationPreflight(catalog, new ShardExportReadGate(sources), core);
    assertThat(preflight.inspect("plan", actor).idRemapping())
        .containsEntry("ledgerEntries", false)
        .containsEntry("domainEvents", false);

    target.update(
        "INSERT INTO ledger_entries VALUES(7,'journal-5',?,'merchant','MERCHANT_PAYABLE')", prior);
    target.update(
        "INSERT INTO domain_events VALUES(7,'fact-5b','event-5b',?,5,'PAYMENT_FACT','POLLING')",
        prior);
    var inspection = preflight.inspect("plan", actor);
    assertThat(inspection.idRemapping())
        .containsEntry("ledgerEntries", true)
        .containsEntry("domainEvents", true);
    assertThat(inspection.blockers()).isEmpty();
  }

  static void schema(JdbcTemplate jdbc, int physical) {
    for (String sql :
        List.of(
            "CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)",
            "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))",
            "CREATE TABLE payments(id VARCHAR(64),status VARCHAR(24),logical_shard INT)",
            "CREATE TABLE refunds(id VARCHAR(64),payment_id VARCHAR(64),status VARCHAR(24))",
            "CREATE TABLE ledger_journals(id VARCHAR(64),payment_id VARCHAR(64))",
            "CREATE TABLE ledger_entries(id BIGINT AUTO_INCREMENT PRIMARY KEY,journal_id VARCHAR(64),payment_id VARCHAR(64),merchant_id VARCHAR(64),account_code VARCHAR(40))",
            "CREATE TABLE channel_attempts(id VARCHAR(64),payment_id VARCHAR(64),status VARCHAR(24))",
            "CREATE TABLE inbox_notifications(id VARCHAR(64),aggregate_id VARCHAR(64),status VARCHAR(24))",
            "CREATE TABLE outbox_tasks(id VARCHAR(64),event_id VARCHAR(160),aggregate_id VARCHAR(64),logical_bucket INT,status VARCHAR(16),kind VARCHAR(40))",
            "CREATE TABLE outbox_task_history(id VARCHAR(64),event_id VARCHAR(160),aggregate_id VARCHAR(64),logical_bucket INT,kind VARCHAR(40))",
            "CREATE TABLE domain_events(sequence_no BIGINT AUTO_INCREMENT PRIMARY KEY,id VARCHAR(36),event_id VARCHAR(160),aggregateid VARCHAR(64),logical_bucket INT,event_type VARCHAR(64),delivery_mode VARCHAR(16))",
            "CREATE TABLE v3_shard_copy_id_map(plan_id VARCHAR(36),entity_name VARCHAR(32),source_id BIGINT,target_id BIGINT,PRIMARY KEY(plan_id,entity_name,source_id),UNIQUE(entity_name,target_id))",
            "CREATE TABLE domain_event_versions(merchant_id VARCHAR(64),aggregate_id VARCHAR(64))",
            "CREATE TABLE event_receipts(event_id VARCHAR(160),consumer_name VARCHAR(64))",
            "CREATE TABLE projection_receipts(event_id VARCHAR(160))",
            "CREATE TABLE event_quarantine(status VARCHAR(16))",
            "CREATE TABLE payment_admission_slots(logical_shard INT,payment_id VARCHAR(64))",
            "CREATE TABLE payment_admission(logical_shard INT,inflight INT)",
            "CREATE TABLE projection_batches(id VARCHAR(64),payment_id VARCHAR(64),state VARCHAR(16))",
            "CREATE TABLE projection_counters(generation VARCHAR(36),payment_id VARCHAR(64))",
            "CREATE TABLE v3_history_archives(id VARCHAR(36),payment_id VARCHAR(64),logical_bucket INT)",
            "CREATE TABLE v3_history_event_locators(event_id VARCHAR(160),logical_bucket INT,archive_id VARCHAR(36),event_row_id VARCHAR(64),state VARCHAR(12))",
            "CREATE TABLE projection_control(id INT PRIMARY KEY,active_generation VARCHAR(36),building_generation VARCHAR(36))",
            "CREATE TABLE projection_generations(id VARCHAR(36) PRIMARY KEY,state VARCHAR(16),scan_complete BOOLEAN)",
            "CREATE TABLE projection_errors(id VARCHAR(36),state VARCHAR(16))",
            "CREATE TABLE event_transport(id INT PRIMARY KEY,mode VARCHAR(16))",
            "CREATE TABLE sandbox_checkouts(payment_id VARCHAR(64))",
            "CREATE TABLE alipay_evidence(id VARCHAR(64),aggregate_id VARCHAR(64))",
            "CREATE TABLE evidence_conflicts(id VARCHAR(64),aggregate_id VARCHAR(64))",
            "CREATE TABLE audit_records(id VARCHAR(64),merchant_id VARCHAR(64),actor VARCHAR(160),operation VARCHAR(64),resource_id VARCHAR(160),detail VARCHAR(2000),created_at TIMESTAMP)",
            "CREATE TABLE notification_deliveries(id VARCHAR(64),task_id VARCHAR(64))",
            "CREATE TABLE outbox_events(id VARCHAR(64),event_id VARCHAR(160))")) jdbc.execute(sql);
    jdbc.update("INSERT INTO v3_shard_identity VALUES(1,?)", physical);
    jdbc.update("INSERT INTO projection_control VALUES(1,'v2-initial',NULL)");
    jdbc.update("INSERT INTO projection_generations VALUES('v2-initial','SERVING',TRUE)");
    jdbc.update("INSERT INTO event_transport VALUES(1,'CDC')");
    for (int bucket = 0; bucket < 16; bucket++)
      jdbc.update("INSERT INTO payment_admission VALUES(?,0)", bucket);
    if (physical == 0) {
      jdbc.execute(
          "CREATE TABLE v3_shard_routes(bucket INT PRIMARY KEY,physical_shard INT,epoch BIGINT)");
      for (int bucket = 0; bucket < 16; bucket++) {
        jdbc.update("INSERT INTO v3_shard_routes VALUES(?,0,1)", bucket);
        jdbc.update("INSERT INTO v3_bucket_fences VALUES(?,1,'ACTIVE')", bucket);
      }
      for (String sql :
          List.of(
              "CREATE TABLE v3_receipt_claims(aggregate_id VARCHAR(64))",
              "CREATE TABLE finance_cases(payment_id VARCHAR(64))",
              "CREATE TABLE finance_adjustments(payment_id VARCHAR(64))",
              "CREATE TABLE finance_settlements(id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),status VARCHAR(24),version BIGINT)",
              "CREATE TABLE settlement_lines(payment_id VARCHAR(64),settlement_id VARCHAR(64),revision BIGINT,physical_shard INT,entry_id BIGINT)",
              "CREATE TABLE settlement_reservations(settlement_id VARCHAR(64),revision BIGINT,physical_shard INT,entry_id BIGINT)",
              "CREATE TABLE v3_shard_finance_handoffs(plan_id VARCHAR(36),source_shard INT,source_entry_id BIGINT,target_shard INT,target_entry_id BIGINT,merchant_id VARCHAR(64),payment_id VARCHAR(64),PRIMARY KEY(source_shard,source_entry_id),UNIQUE(target_shard,target_entry_id))",
              "CREATE TABLE sharded_settlement_cursors(merchant_id VARCHAR(64),physical_shard INT,last_entry_id BIGINT)",
              "CREATE TABLE sharded_adjustment_intents(payment_id VARCHAR(64),status VARCHAR(16))"))
        jdbc.execute(sql);
    }
  }
}
