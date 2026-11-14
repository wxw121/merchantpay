package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.sharding.ShardEventTransportMaintenance;
import java.sql.Connection;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardEventTransportMaintenanceTest {
  private static Map<Integer, DataSource> sources() {
    var sources = new LinkedHashMap<Integer, DataSource>();
    for (int id = 0; id < 4; id++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:cdc_switch_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      var sql = new JdbcTemplate(source);
      sql.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT NOT NULL)");
      sql.update("INSERT INTO v3_shard_identity VALUES(1,?)", id);
      sql.execute(
          "CREATE TABLE event_transport(id INT PRIMARY KEY,mode VARCHAR(16),cutoff_sequence BIGINT,changed_at TIMESTAMP)");
      sql.update("INSERT INTO event_transport VALUES(1,'POLLING',0,CURRENT_TIMESTAMP)");
      sql.execute("CREATE TABLE outbox_tasks(status VARCHAR(16))");
      sql.execute("CREATE TABLE payments(status VARCHAR(16))");
      sql.execute("CREATE TABLE refunds(status VARCHAR(16))");
      sql.execute("CREATE TABLE inbox_notifications(status VARCHAR(16))");
      sql.execute(
          "CREATE TABLE domain_events(sequence_no BIGINT,event_id VARCHAR(160),event_type VARCHAR(64),delivery_mode VARCHAR(16))");
      sql.execute("CREATE TABLE event_receipts(consumer_name VARCHAR(64),event_id VARCHAR(160))");
      sql.execute("CREATE TABLE event_quarantine(status VARCHAR(16))");
      sources.put(id, source);
    }
    return sources;
  }

  private static Map<Integer, Connection> open(Map<Integer, DataSource> sources) throws Exception {
    var opened = new LinkedHashMap<Integer, Connection>();
    for (int id = 0; id < 4; id++) opened.put(id, sources.get(id).getConnection());
    return opened;
  }

  private static void close(Map<Integer, Connection> opened) throws Exception {
    for (var c : opened.values()) c.close();
  }

  @Test
  void partialModeSwitchIsRejectedAtStartupAndResumableAtTheSameCutoff() throws Exception {
    var sources = sources();
    new JdbcTemplate(sources.get(1))
        .update("UPDATE event_transport SET mode='CDC',cutoff_sequence=0 WHERE id=1");
    assertThatThrownBy(() -> ShardEventTransportMaintenance.requireMode(sources, "POLLING"))
        .isInstanceOf(IllegalStateException.class);
    var opened = open(sources);
    try {
      assertThat(ShardEventTransportMaintenance.switchModes(opened, "POLLING", "CDC", true))
          .containsEntry(0, 0L)
          .containsEntry(1, 0L)
          .containsEntry(2, 0L)
          .containsEntry(3, 0L);
    } finally {
      close(opened);
    }
    ShardEventTransportMaintenance.requireMode(sources, "CDC");
    assertThatThrownBy(() -> ShardEventTransportMaintenance.requireMode(sources, "POLLING"))
        .isInstanceOf(IllegalStateException.class);
    new JdbcTemplate(sources.get(1))
        .update("INSERT INTO domain_events VALUES(1,'later','ADJUSTMENT_EXECUTED','CDC')");
    opened = open(sources);
    try {
      var retry = opened;
      assertThatThrownBy(
              () -> ShardEventTransportMaintenance.switchModes(retry, "POLLING", "CDC", true))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      close(opened);
    }
  }

  @Test
  void unreceivedAndQuarantinedSourceBlockEveryModeChange() throws Exception {
    var sources = sources();
    new JdbcTemplate(sources.get(2))
        .update("INSERT INTO domain_events VALUES(1,'missing','ADJUSTMENT_EXECUTED','CDC')");
    var opened = open(sources);
    try {
      var pending = opened;
      assertThatThrownBy(
              () -> ShardEventTransportMaintenance.switchModes(pending, "POLLING", "CDC", true))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      close(opened);
    }
    new JdbcTemplate(sources.get(2)).update("DELETE FROM domain_events");
    new JdbcTemplate(sources.get(3)).update("INSERT INTO event_quarantine VALUES('QUARANTINED')");
    opened = open(sources);
    try {
      var quarantined = opened;
      assertThatThrownBy(
              () -> ShardEventTransportMaintenance.switchModes(quarantined, "POLLING", "CDC", true))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      close(opened);
    }
    for (int id = 0; id < 4; id++)
      assertThat(
              new JdbcTemplate(sources.get(id))
                  .queryForObject("SELECT mode FROM event_transport WHERE id=1", String.class))
          .isEqualTo("POLLING");
  }
}
