package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ShardFinanceHandoffChainTest {
  @Test
  void repeatedMovesKeepTheOriginalEntryAndEveryImmutableHop() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:finance_hops_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    try (var connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V41__shard_finance_handoff.sql"));
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V46__shard_finance_handoff_hops.sql"));
    }
    var jdbc = new JdbcTemplate(source);
    var fact =
        Map.<String, Object>of(
            "merchant_id", "merchant", "payment_id", "p4_00000000-0000-0000-0000-000000000000");
    ShardMigrationCutover.recordFinanceHop(jdbc, plan("first", 0, 1), 10, 5, fact);
    ShardMigrationCutover.recordFinanceHop(jdbc, plan("reverse", 1, 0), 5, 20, fact);
    ShardMigrationCutover.recordFinanceHop(jdbc, plan("next", 0, 2), 20, 7, fact);
    assertThat(
            jdbc.queryForMap(
                "SELECT source_shard,source_entry_id,target_shard,"
                    + "target_entry_id FROM v3_shard_finance_handoffs"))
        .containsEntry("source_shard", 0)
        .containsEntry("source_entry_id", 10L)
        .containsEntry("target_shard", 2)
        .containsEntry("target_entry_id", 7L);
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM v3_shard_finance_handoff_hops", Long.class))
        .isEqualTo(3L);
  }

  private static ShardCatalog.Plan plan(String id, int source, int target) {
    return new ShardCatalog.Plan(
        id,
        id,
        4,
        source,
        target,
        1,
        1,
        "PLANNED",
        "ops",
        "test",
        null,
        null,
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
