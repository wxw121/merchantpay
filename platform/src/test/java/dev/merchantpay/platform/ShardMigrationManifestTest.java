package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.sharding.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ShardMigrationManifestTest {
  @Test
  void paidSettlementReservationMovesWithRouteAndRepeatedRequestIsIdempotent() throws Exception {
    var sources = new HashMap<Integer, DataSource>();
    for (int physical = 0; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:shard_cutover_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      sources.put(physical, source);
      ShardMigrationPreflightTest.schema(new JdbcTemplate(source), physical);
    }
    var core = new JdbcTemplate(sources.get(0));
    core.execute(
        "CREATE TABLE v3_shard_migrations(id VARCHAR(36) PRIMARY KEY,bucket INT,open_bucket INT,source_shard INT,target_shard INT,source_epoch BIGINT,version BIGINT,state VARCHAR(16),updated_at TIMESTAMP)");
    try (var connection = sources.get(0).getConnection()) {
      for (String file :
          List.of(
              "V34__shard_quiescence.sql",
              "V35__shard_source_manifests.sql",
              "V36__shard_copy_runs.sql",
              "V37__shard_copy_discard.sql",
              "V46__shard_finance_handoff_hops.sql"))
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + file));
      core.update(
          "INSERT INTO v3_shard_migrations VALUES('plan',4,4,0,1,1,1,'PLANNED',CURRENT_TIMESTAMP)");
      core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
      core.update(
          "INSERT INTO v3_shard_isolations(plan_id,bucket,source_epoch,plan_version,state,isolated_by,isolation_reason,created_at,updated_at) VALUES('plan',4,1,1,'QUIESCED','ops','stop',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
    core.execute(
        "CREATE TABLE v3_shard_cutovers(plan_id VARCHAR(36) PRIMARY KEY,bucket INT,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,plan_version BIGINT,manifest_hash VARCHAR(64),state VARCHAR(16),initiated_by VARCHAR(160),reason VARCHAR(1000),created_at TIMESTAMP,updated_at TIMESTAMP,activated_at TIMESTAMP)");
    String payment = "p4_00000000-0000-0000-0000-000000000004";
    core.update("INSERT INTO payments VALUES(?,'SUCCEEDED',4)", payment);
    core.update("INSERT INTO ledger_journals VALUES('journal',?)", payment);
    core.update(
        "INSERT INTO ledger_entries VALUES(1,'journal',?,'merchant','MERCHANT_PAYABLE')", payment);
    core.update("INSERT INTO finance_settlements VALUES('settlement','merchant','MOCK_PAID',1)");
    core.update("INSERT INTO settlement_lines VALUES(?,'settlement',1,0,1)", payment);
    core.update("INSERT INTO settlement_reservations VALUES('settlement',1,0,1)");
    String priorPayment = "p5_00000000-0000-0000-0000-000000000005";
    core.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=5");
    core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=5");
    core.update(
        "INSERT INTO v3_shard_cutovers(plan_id,bucket,source_epoch,target_epoch,target_shard,plan_version,manifest_hash,state,initiated_by,reason,created_at,updated_at,activated_at) VALUES('prior-plan',5,1,2,1,1,'prior','ACTIVE','ops','prior',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    var target = new JdbcTemplate(sources.get(1));
    target.update("INSERT INTO v3_bucket_fences VALUES(5,2,'ACTIVE')");
    target.update("INSERT INTO payments VALUES(?,'SUCCEEDED',5)", priorPayment);
    target.update("INSERT INTO ledger_journals VALUES('prior-journal',?)", priorPayment);
    target.update(
        "INSERT INTO ledger_entries VALUES(1,'prior-journal',?,'other','MERCHANT_PAYABLE')",
        priorPayment);
    var operator = new Actor("ops", null, Set.of("OPS"));
    var catalog = mock(ShardCatalog.class);
    when(catalog.detail("plan", operator))
        .thenAnswer(
            ignored -> {
              var row =
                  core.queryForMap("SELECT state,version FROM v3_shard_migrations WHERE id='plan'");
              return new ShardCatalog.Plan(
                  "plan",
                  "request",
                  4,
                  0,
                  1,
                  1,
                  ((Number) row.get("version")).longValue(),
                  row.get("state").toString(),
                  "ops",
                  "move",
                  null,
                  null,
                  Instant.EPOCH,
                  Instant.EPOCH);
            });
    var gate = new ShardExportReadGate(sources);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(sources.get(0)));
    var preflight = new ShardMigrationPreflight(catalog, gate, core);
    var manifests =
        new ShardMigrationManifest(catalog, preflight, gate, core, tx, Clock.systemUTC());
    var copies =
        new ShardMigrationCopy(catalog, manifests, preflight, gate, core, tx, Clock.systemUTC());
    var readiness = new ShardMigrationReadiness(catalog, manifests, gate, core, tx);
    var cutover =
        new ShardMigrationCutover(catalog, readiness, manifests, gate, core, tx, Clock.systemUTC());
    manifests.capture("plan", 1, "source witness", true, operator);
    copies.copy("plan", 1, "offline copy", true, operator);
    long movedEntry =
        target.queryForObject(
            "SELECT target_id FROM v3_shard_copy_id_map WHERE plan_id='plan' AND entity_name='ledger_entries' AND source_id=1",
            Long.class);
    assertThat(movedEntry).isNotEqualTo(1);
    core.update("DELETE FROM settlement_reservations WHERE entry_id=1");
    assertThat(readiness.inspect("plan", 1, true, operator).blockers())
        .contains("FINANCE_UNRESERVEDCOMPLETEDLINES");
    core.update("INSERT INTO settlement_reservations VALUES('settlement',1,0,1)");
    assertThat(readiness.inspect("plan", 1, true, operator).checksPassed()).isTrue();
    core.update(
        "INSERT INTO v3_shard_cutovers(plan_id,bucket,source_epoch,target_epoch,target_shard,plan_version,manifest_hash,state,initiated_by,reason,created_at,updated_at) VALUES('plan',4,1,2,1,1,?,'PREPARING','ops','activate',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        manifests.read("plan", operator).hash());
    assertThatThrownBy(() -> copies.discard("plan", 1, "discard", true, operator))
        .hasMessageContaining("切流已开始");
    core.update("DELETE FROM v3_shard_cutovers WHERE plan_id='plan'");
    assertThatThrownBy(() -> cutover.execute("plan", 1, "activate", false, operator))
        .isInstanceOf(ApiException.class);
    var active = cutover.execute("plan", 1, "activate", true, operator);
    assertThat(active.state()).isEqualTo("ACTIVE");
    assertThat(cutover.execute("plan", 1, "activate", true, operator)).isEqualTo(active);
    assertThat(
            core.queryForMap(
                "SELECT state,version,open_bucket FROM v3_shard_migrations WHERE id='plan'"))
        .containsEntry("state", "MOVED")
        .containsEntry("version", 2L)
        .containsEntry("open_bucket", null);
    assertThat(
            core.queryForObject(
                "SELECT physical_shard FROM v3_shard_routes WHERE bucket=4", Integer.class))
        .isEqualTo(1);
    assertThat(
            core.queryForMap(
                "SELECT physical_shard,entry_id FROM settlement_reservations WHERE settlement_id='settlement'"))
        .containsEntry("physical_shard", 1)
        .containsEntry("entry_id", movedEntry);
    assertThat(
            core.queryForObject(
                "SELECT target_entry_id FROM v3_shard_finance_handoffs WHERE source_shard=0 AND source_entry_id=1",
                Long.class))
        .isEqualTo(movedEntry);
    assertThat(
            core.queryForObject(
                "SELECT physical_shard FROM settlement_lines WHERE settlement_id='settlement'",
                Integer.class))
        .isZero();
    assertThat(cutover.financeHandoff("plan", operator))
        .satisfies(
            report -> {
              assertThat(report.routeCompleted()).isTrue();
              assertThat(report.sourcePayableEntries()).isEqualTo(1);
              assertThat(report.mappedEntries()).isEqualTo(1);
              assertThat(report.mappedReservations()).isEqualTo(1);
              assertThat(report.sourceReservations()).isZero();
            });
    assertThat(
            core.queryForObject(
                "SELECT access_state FROM v3_bucket_fences WHERE bucket=4", String.class))
        .isEqualTo("FENCED");
    assertThat(
            new JdbcTemplate(sources.get(1))
                .queryForObject(
                    "SELECT access_state FROM v3_bucket_fences WHERE bucket=4", String.class))
        .isEqualTo("ACTIVE");
  }

  @Test
  void factOnlyEventsAreWitnessedAndRogueSourceChangesBreakRetry() throws Exception {
    var sources = new HashMap<Integer, DataSource>();
    for (int physical = 0; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:shard_manifest_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      sources.put(physical, source);
      ShardMigrationPreflightTest.schema(new JdbcTemplate(source), physical);
    }
    var core = new JdbcTemplate(sources.get(0));
    core.execute(
        "CREATE TABLE v3_shard_migrations(id VARCHAR(36) PRIMARY KEY,bucket INT,source_shard INT,target_shard INT,source_epoch BIGINT,version BIGINT,state VARCHAR(16))");
    core.execute(
        "CREATE TABLE v3_shard_cutovers(plan_id VARCHAR(36) PRIMARY KEY,bucket INT,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
    try (var connection = sources.get(0).getConnection()) {
      for (String file :
          List.of(
              "V34__shard_quiescence.sql",
              "V35__shard_source_manifests.sql",
              "V36__shard_copy_runs.sql",
              "V37__shard_copy_discard.sql",
              "V46__shard_finance_handoff_hops.sql"))
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + file));
      core.update("INSERT INTO v3_shard_migrations VALUES('plan',4,0,1,1,1,'PLANNED')");
      core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
      core.update(
          "INSERT INTO v3_shard_isolations(plan_id,bucket,source_epoch,plan_version,state,isolated_by,isolation_reason,created_at,updated_at) VALUES('plan',4,1,1,'QUIESCED','ops','stop',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
      core.update(
          "INSERT INTO domain_events VALUES(1,'fact-id','fact-event','p4_00000000-0000-0000-0000-000000000004',4,'PAYMENT_FACT','POLLING')");
      core.update("INSERT INTO outbox_events VALUES('fact-id','fact-event')");
      core.update("INSERT INTO projection_receipts VALUES('fact-event')");
      core.update(
          "INSERT INTO payments VALUES('p4_00000000-0000-0000-0000-000000000004','SUCCEEDED',4)");
      core.update(
          "INSERT INTO ledger_journals VALUES('journal','p4_00000000-0000-0000-0000-000000000004')");
      core.update(
          "INSERT INTO ledger_entries VALUES(1,'journal','p4_00000000-0000-0000-0000-000000000004','merchant','MERCHANT_PAYABLE')");
      core.update("INSERT INTO sharded_settlement_cursors VALUES('merchant',0,1)");
      // Bucket 5 was already copied to this target. Its global IDs must survive
      // both the next copy and a discard of bucket 4.
      core.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=5");
      core.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=5");
      core.update("INSERT INTO v3_shard_cutovers VALUES('prior-plan',5,1,2,1,'ACTIVE')");
      var target = new JdbcTemplate(sources.get(1));
      target.update("INSERT INTO v3_bucket_fences VALUES(5,2,'ACTIVE')");
      target.update(
          "INSERT INTO payments VALUES('p5_00000000-0000-0000-0000-000000000005','SUCCEEDED',5)");
      target.update(
          "INSERT INTO ledger_journals VALUES('prior-journal','p5_00000000-0000-0000-0000-000000000005')");
      target.update(
          "INSERT INTO ledger_entries VALUES(99,'prior-journal','p5_00000000-0000-0000-0000-000000000005','other','MERCHANT_PAYABLE')");
      target.update(
          "INSERT INTO domain_events VALUES(99,'prior-fact','prior-event','p5_00000000-0000-0000-0000-000000000005',5,'PAYMENT_FACT','POLLING')");
      var operator = new Actor("ops", null, Set.of("OPS"));
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
      var catalog = mock(ShardCatalog.class);
      when(catalog.detail("plan", operator)).thenReturn(plan);
      var gate = new ShardExportReadGate(sources);
      var preflight = new ShardMigrationPreflight(catalog, gate, core);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(sources.get(0)));
      var manifests =
          new ShardMigrationManifest(catalog, preflight, gate, core, tx, Clock.systemUTC());

      var first = manifests.capture("plan", 1, "source witness", true, operator);
      assertThat(first.tables()).hasSize(22);
      assertThat(
              first.tables().stream()
                  .filter(table -> table.name().equals("domain_events"))
                  .findFirst()
                  .orElseThrow()
                  .count())
          .isEqualTo(1);
      assertThat(
              first.tables().stream()
                  .filter(table -> table.name().equals("outbox_events"))
                  .findFirst()
                  .orElseThrow()
                  .count())
          .isEqualTo(1);
      assertThat(manifests.capture("plan", 1, "source witness", true, operator)).isEqualTo(first);

      var copies =
          new ShardMigrationCopy(catalog, manifests, preflight, gate, core, tx, Clock.systemUTC());
      var readiness = new ShardMigrationReadiness(catalog, manifests, gate, core, tx);
      assertThat(readiness.inspect("plan", 1, true, operator).blockers())
          .contains("VERIFIED_COPY_MISSING");
      target.update(
          "INSERT INTO ledger_entries VALUES(1,'prior-journal','p5_00000000-0000-0000-0000-000000000005','other','MERCHANT_PAYABLE')");
      target.update(
          "INSERT INTO domain_events VALUES(1,'prior-fact-1','prior-event-1','p5_00000000-0000-0000-0000-000000000005',5,'PAYMENT_FACT','POLLING')");
      assertThat(preflight.inspect("plan", operator).idRemapping())
          .containsEntry("ledgerEntries", true)
          .containsEntry("domainEvents", true);
      var copied = copies.copy("plan", 1, "offline copy", true, operator);
      assertThat(copied.manifestHash()).isEqualTo(first.hash());
      assertThat(copied.targetHash()).isEqualTo(first.hash());
      assertThat(copied.totalRows()).isEqualTo(6);
      assertThat(target.queryForObject("SELECT COUNT(*) FROM domain_events", Long.class))
          .isEqualTo(3);
      assertThat(
              target.queryForObject(
                  "SELECT COUNT(*) FROM v3_shard_copy_id_map WHERE plan_id='plan'", Long.class))
          .isEqualTo(2);
      assertThat(
              target.queryForObject(
                  "SELECT id FROM ledger_entries WHERE payment_id='p4_00000000-0000-0000-0000-000000000004'",
                  Long.class))
          .isNotEqualTo(1L);
      assertThat(
              target.queryForObject(
                  "SELECT sequence_no FROM domain_events WHERE event_id='fact-event'", Long.class))
          .isNotEqualTo(1L);
      long mappedEntry =
          target.queryForObject(
              "SELECT target_id FROM v3_shard_copy_id_map WHERE plan_id='plan' AND entity_name='ledger_entries'",
              Long.class);
      target.update(
          "DELETE FROM v3_shard_copy_id_map WHERE plan_id='plan' AND entity_name='ledger_entries'");
      assertThat(readiness.inspect("plan", 1, true, operator).blockers())
          .contains("TARGET_MANIFEST_DRIFT");
      target.update(
          "INSERT INTO v3_shard_copy_id_map VALUES('plan','ledger_entries',1,?)", mappedEntry);
      target.update("INSERT INTO v3_shard_copy_id_map VALUES('plan','ledger_entries',123,99)");
      assertThatThrownBy(() -> readiness.inspect("plan", 1, true, operator))
          .isInstanceOf(ApiException.class)
          .hasMessageContaining("错误桶");
      target.update("DELETE FROM v3_shard_copy_id_map WHERE plan_id='plan' AND source_id=123");
      assertThat(
              core.queryForObject(
                  "SELECT physical_shard FROM v3_shard_routes WHERE bucket=4", Integer.class))
          .isZero();
      assertThat(readiness.inspect("plan", 1, true, operator).blockers())
          .contains("FINANCE_CURSORUNRESERVEDENTRIES");
      core.update("DELETE FROM sharded_settlement_cursors WHERE merchant_id='merchant'");
      var ready = readiness.inspect("plan", 1, true, operator);
      assertThat(ready.checksPassed()).isTrue();
      assertThat(ready.executionEnabled()).isFalse();
      core.update(
          "INSERT INTO settlement_lines(payment_id,settlement_id,revision,physical_shard,entry_id) VALUES('p4_00000000-0000-0000-0000-000000000004','settlement',1,0,9)");
      assertThat(readiness.inspect("plan", 1, true, operator).blockers())
          .contains("FINANCE_PENDINGSETTLEMENTLINES");
      core.update("DELETE FROM settlement_lines WHERE settlement_id='settlement'");
      target.update(
          "INSERT INTO payment_admission_slots VALUES(4,'p4_00000000-0000-0000-0000-000000000004')");
      assertThat(readiness.inspect("plan", 1, true, operator).blockers())
          .contains("TARGET_ADMISSION_SLOTS");
      target.update("DELETE FROM payment_admission_slots WHERE logical_shard=4");
      target.update("INSERT INTO projection_generations VALUES('v2-next','SERVING',TRUE)");
      target.update("UPDATE projection_control SET active_generation='v2-next' WHERE id=1");
      assertThat(readiness.inspect("plan", 1, true, operator).blockers())
          .contains("PROJECTION_GENERATION_MISMATCH");
      target.update("UPDATE projection_control SET active_generation='v2-initial' WHERE id=1");
      core.update("DELETE FROM v3_shard_copy_runs WHERE plan_id='plan'");
      assertThat(copies.copy("plan", 1, "recover copy record", true, operator).targetHash())
          .isEqualTo(first.hash());
      target.update("UPDATE domain_events SET event_type='DRIFTED' WHERE id='fact-id'");
      assertThat(readiness.inspect("plan", 1, true, operator).blockers())
          .contains("TARGET_MANIFEST_DRIFT");
      assertThatThrownBy(() -> copies.copy("plan", 1, "retry", true, operator))
          .isInstanceOf(ApiException.class)
          .satisfies(
              failure ->
                  assertThat(((ApiException) failure).code()).isEqualTo("SHARD_COPY_CONFLICT"));
      assertThatThrownBy(() -> copies.discard("plan", 1, "discard", true, operator))
          .isInstanceOf(ApiException.class)
          .satisfies(
              failure ->
                  assertThat(((ApiException) failure).code()).isEqualTo("SHARD_COPY_CONFLICT"));
      target.update("UPDATE domain_events SET event_type='PAYMENT_FACT' WHERE id='fact-id'");
      var discarded = copies.discard("plan", 1, "discard", true, operator);
      assertThat(discarded.state()).isEqualTo("DISCARDED");
      assertThat(target.queryForObject("SELECT COUNT(*) FROM domain_events", Long.class))
          .isEqualTo(2);
      assertThat(target.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isZero();
      assertThat(target.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class))
          .isEqualTo(2);
      assertThat(
              target.queryForObject(
                  "SELECT COUNT(*) FROM v3_shard_copy_id_map WHERE plan_id='plan'", Long.class))
          .isZero();
      assertThat(copies.discard("plan", 1, "retry discard", true, operator)).isEqualTo(discarded);
      core.update(
          "UPDATE v3_shard_copy_runs SET state='VERIFIED',discarded_by=NULL,discard_reason=NULL,discarded_at=NULL WHERE plan_id='plan'");
      assertThat(copies.discard("plan", 1, "recover discard record", true, operator).state())
          .isEqualTo("DISCARDED");
      assertThatThrownBy(() -> copies.copy("plan", 1, "copy again", true, operator))
          .isInstanceOf(ApiException.class)
          .satisfies(
              failure ->
                  assertThat(((ApiException) failure).code()).isEqualTo("SHARD_COPY_CONFLICT"));
      assertThat(
              core.queryForObject(
                  "SELECT access_state FROM v3_bucket_fences WHERE bucket=4", String.class))
          .isEqualTo("FENCED");

      core.update("UPDATE domain_events SET event_type='CHANGED' WHERE id='fact-id'");
      assertThatThrownBy(() -> manifests.capture("plan", 1, "source witness", true, operator))
          .isInstanceOf(ApiException.class)
          .satisfies(
              failure ->
                  assertThat(((ApiException) failure).code()).isEqualTo("SHARD_MANIFEST_CONFLICT"));
      assertThat(manifests.read("plan", operator)).isEqualTo(first);
      core.update("UPDATE domain_events SET event_type='PAYMENT_FACT' WHERE id='fact-id'");
      var isolation = new ShardMigrationIsolation(catalog, preflight, core, tx, Clock.systemUTC());
      assertThat(isolation.release("plan", 1, "resume source", true, operator).state())
          .isEqualTo("RELEASED");
      assertThat(
              core.queryForObject(
                  "SELECT access_state FROM v3_bucket_fences WHERE bucket=4", String.class))
          .isEqualTo("ACTIVE");
    }
  }
}
