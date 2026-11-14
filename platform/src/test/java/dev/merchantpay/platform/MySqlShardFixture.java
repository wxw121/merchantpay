package dev.merchantpay.platform;

import dev.merchantpay.platform.sharding.ShardTransactions;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/** Defined for an independent MySQL acceptance environment; never run by native static checks. */
abstract class MySqlShardFixture {
  static final MySQLContainer MYSQL = MySqlFinanceDatabase.container();
  static final Map<Integer, DataSource> SOURCES = new LinkedHashMap<>();
  ShardTransactions routes;
  JdbcTemplate core;

  @BeforeAll
  static void databases() {
    MYSQL.start();
    SOURCES.clear();
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
    for (int id = 0; id < 4; id++) {
      String name = "v3_shard_" + id;
      admin.execute("CREATE DATABASE " + name);
      String url = MYSQL.getJdbcUrl().replace("/v2_finance", "/" + name);
      var source = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
      SOURCES.put(id, source);
      Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
      var sql = new JdbcTemplate(source);
      sql.execute("CREATE TABLE tx_probe(id INT PRIMARY KEY, value_no INT NOT NULL)");
      sql.update("UPDATE v3_shard_identity SET physical_shard=? WHERE id=1", id);
    }
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void routing() {
    core = new JdbcTemplate(SOURCES.get(0));
    for (int id = 0; id < 4; id++) {
      var sql = new JdbcTemplate(SOURCES.get(id));
      for (String table :
          List.of(
              "v3_analytics_generation_publications",
              "v3_shard_reverse_runs",
              "v3_history_event_locators",
              "v3_history_archives",
              "v3_shard_copy_id_map",
              "v3_shard_finance_handoff_hops",
              "v3_shard_finance_handoffs",
              "tx_probe",
              "sandbox_checkouts",
              "alipay_evidence",
              "notification_deliveries",
              "refunds",
              "payments",
              "ledger_entries",
              "ledger_journals",
              "outbox_tasks",
              "outbox_task_history",
              "outbox_events",
              "event_receipts",
              "event_quarantine",
              "domain_events",
              "domain_event_versions",
              "audit_records",
              "merchants",
              "v3_receipt_claims",
              "inbox_notifications",
              "channel_attempts",
              "evidence_conflicts")) sql.update("DELETE FROM " + table);
      sql.update("UPDATE payment_admission SET inflight=0");
      sql.update("UPDATE payment_admission_slots SET payment_id=NULL");
      sql.update("UPDATE payment_admission_mode SET slot_mode=FALSE WHERE id=1");
      sql.update("UPDATE v3_shard_identity SET physical_shard=? WHERE id=1", id);
      sql.update("UPDATE v3_bucket_fences SET access_state='FENCED',route_epoch=2");
      for (int bucket = id; bucket < 16; bucket += 4)
        sql.update("UPDATE v3_bucket_fences SET access_state='ACTIVE' WHERE bucket=?", bucket);
    }
    for (int bucket = 0; bucket < 16; bucket++)
      core.update(
          "UPDATE v3_shard_routes SET physical_shard=?,epoch=2 WHERE bucket=?", bucket % 4, bucket);
    routes = new ShardTransactions(core, SOURCES);
  }
}
