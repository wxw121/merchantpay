package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShardReverseCleanupTest {
  @Test
  void cleanupDeletesOnePageAndPreservesAnotherBucket() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:reverse_clean_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""));
    jdbc.execute("CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY)");
    for (int index = 0; index < 1_101; index++)
      jdbc.update("INSERT INTO payments(id) VALUES(?)", "p4_" + index);
    jdbc.update("INSERT INTO payments(id) VALUES('p5_unrelated')");
    var probe = ShardMigrationPreflight.BUCKET_PROBES.getFirst();
    assertThat(ShardReverseMigration.deletePage(jdbc, probe, 4)).isEqualTo(1_000);
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE LEFT(id,3)='p4_'", Long.class))
        .isEqualTo(101L);
    assertThat(ShardReverseMigration.deletePage(jdbc, probe, 4)).isEqualTo(101);
    assertThat(ShardReverseMigration.deletePage(jdbc, probe, 4)).isZero();
    assertThat(jdbc.queryForObject("SELECT id FROM payments", String.class))
        .isEqualTo("p5_unrelated");
  }
}
