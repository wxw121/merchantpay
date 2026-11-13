package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.sharding.ShardTransactions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/** Remote smoke fixture: four physical MySQL servers, not four schemas on one server. */
class MySqlFourPhysicalSourcesSmokeTest {
  @Test
  void routesKeepWritesOnTheirPhysicalSourceAndRejectStaleOwnership() {
    try (MySQLContainer coreDb = MySqlFinanceDatabase.container();
        MySQLContainer firstDb = MySqlFinanceDatabase.container();
        MySQLContainer secondDb = MySqlFinanceDatabase.container();
        MySQLContainer thirdDb = MySqlFinanceDatabase.container()) {
      List<MySQLContainer> servers = List.of(coreDb, firstDb, secondDb, thirdDb);
      Map<Integer, DataSource> sources = new LinkedHashMap<>();
      for (int physical = 0; physical < servers.size(); physical++) {
        var server = servers.get(physical);
        server.start();
        var source =
            new DriverManagerDataSource(
                server.getJdbcUrl(), server.getUsername(), server.getPassword());
        sources.put(physical, source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var sql = new JdbcTemplate(source);
        assertThat(
                sql.queryForObject(
                    "SELECT version FROM flyway_schema_history WHERE success=1 "
                        + "ORDER BY installed_rank DESC LIMIT 1",
                    String.class))
            .isEqualTo("50");
        sql.execute("CREATE TABLE tx_probe(id INT PRIMARY KEY, physical_shard INT NOT NULL)");
        if (physical != 0) {
          assertThat(sql.queryForObject("SELECT COUNT(*) FROM payments", Long.class)).isZero();
          sql.update("DELETE FROM v3_bucket_fences");
          sql.update("UPDATE v3_shard_identity SET physical_shard=? WHERE id=1", physical);
        }
      }
      assertThat(servers.stream().map(server -> server.getMappedPort(3306)).distinct().toList())
          .hasSize(4);

      var control = new JdbcTemplate(sources.get(0));
      for (int physical = 1; physical < 4; physical++) {
        control.update(
            "UPDATE v3_shard_routes SET physical_shard=?,epoch=2 WHERE bucket=?",
            physical,
            physical);
        control.update(
            "UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=?", physical);
        new JdbcTemplate(sources.get(physical))
            .update(
                "INSERT INTO v3_bucket_fences(bucket,route_epoch,access_state) "
                    + "VALUES (?,2,'ACTIVE')",
                physical);
      }
      var routes = new ShardTransactions(control, sources);
      for (int physical = 0; physical < 4; physical++) {
        int selected = physical;
        long epoch = physical == 0 ? 1 : 2;
        routes.write(
            selected,
            epoch,
            sql -> sql.update("INSERT INTO tx_probe VALUES (1,?)", selected));
        assertThat(
                routes.read(
                    selected,
                    sql ->
                        sql.queryForObject(
                            "SELECT physical_shard FROM tx_probe WHERE id=1", Integer.class)))
            .isEqualTo(selected);
        assertThat(
                new JdbcTemplate(sources.get(selected))
                    .queryForObject("SELECT COUNT(*) FROM tx_probe", Long.class))
            .isEqualTo(1);
      }

      var first = new JdbcTemplate(sources.get(1));
      first.update("UPDATE v3_shard_identity SET physical_shard=2 WHERE id=1");
      assertThatThrownBy(() -> routes.read(1, sql -> 1)).isInstanceOf(ApiException.class);
      first.update("UPDATE v3_shard_identity SET physical_shard=1 WHERE id=1");

      var second = new JdbcTemplate(sources.get(2));
      second.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=2");
      assertThatThrownBy(() -> routes.read(2, sql -> 1)).isInstanceOf(ApiException.class);
      second.update("UPDATE v3_bucket_fences SET access_state='ACTIVE' WHERE bucket=2");

      assertThatThrownBy(() -> routes.write(3, 1, sql -> 1)).isInstanceOf(ApiException.class);
      assertThatThrownBy(
              () ->
                  routes.write(
                      3,
                      2,
                      sql -> {
                        sql.update("INSERT INTO tx_probe VALUES (2,3)");
                        throw new IllegalStateException("rollback probe");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              new JdbcTemplate(sources.get(3))
                  .queryForObject("SELECT COUNT(*) FROM tx_probe", Long.class))
          .isEqualTo(1);
    }
  }
}
