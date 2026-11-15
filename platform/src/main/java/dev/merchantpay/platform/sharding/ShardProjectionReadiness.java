package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.outbox.EventDelivery;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-source projection evidence. No cross-database snapshot or global completeness claim. */
public final class ShardProjectionReadiness {
  private record Source(JdbcTemplate jdbc, TransactionTemplate tx) {}

  private final Map<Integer, Source> sources;
  private final Clock clock;

  public ShardProjectionReadiness(Map<Integer, DataSource> databases, Clock clock) {
    if (databases == null || !databases.keySet().equals(Set.of(0, 1, 2, 3)))
      throw new IllegalArgumentException("Four projection sources are required");
    this.clock = Objects.requireNonNull(clock);
    var configured = new HashMap<Integer, Source>();
    databases.forEach(
        (id, database) -> {
          var jdbc = new JdbcTemplate(Objects.requireNonNull(database));
          jdbc.setQueryTimeout(10);
          var tx = new TransactionTemplate(new DataSourceTransactionManager(database));
          tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
          tx.setReadOnly(true);
          tx.setTimeout(15);
          configured.put(id, new Source(jdbc, tx));
        });
    this.sources = Map.copyOf(configured);
  }

  public Map<String, Object> status(Actor actor, boolean deep) {
    EventDelivery.requireOperator(actor);
    var rows = new ArrayList<Map<String, Object>>();
    for (int id = 0; id < 4; id++) {
      int physical = id;
      var source = sources.get(id);
      rows.add(source.tx().execute(t -> inspect(source.jdbc(), physical, deep)));
    }
    return Map.of(
        "sharded",
        true,
        "globalReady",
        false,
        "deep",
        deep,
        "observedAt",
        clock.instant().toString(),
        "sources",
        rows);
  }

  private Map<String, Object> inspect(JdbcTemplate jdbc, int physical, boolean deep) {
    Integer identity =
        jdbc.queryForObject(
            "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
    if (!Objects.equals(identity, physical))
      throw new IllegalStateException(
          "Projection source identity differs at physical shard " + physical);
    var transport = jdbc.queryForMap("SELECT mode,cutoff_sequence FROM event_transport WHERE id=1");
    var control =
        jdbc.queryForMap(
            "SELECT active_generation,building_generation FROM projection_control WHERE id=1");
    String generation =
        Objects.toString(
            control.get("building_generation"),
            Objects.toString(control.get("active_generation"), ""));
    Boolean scanned =
        generation.isEmpty()
            ? false
            : jdbc.queryForObject(
                "SELECT scan_complete FROM projection_generations WHERE id=?",
                Boolean.class,
                generation);
    var result = new LinkedHashMap<String, Object>();
    result.put("physicalShard", physical);
    result.put("mode", transport.get("mode"));
    result.put("cutoffSequence", transport.get("cutoff_sequence"));
    result.put(
        "allocatedSequence",
        jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM domain_events", Long.class));
    result.put("activeGeneration", control.get("active_generation"));
    result.put("buildingGeneration", control.get("building_generation"));
    result.put("scanComplete", scanned);
    result.put(
        "hasPendingBatch",
        !jdbc.queryForList(
                "SELECT id FROM projection_batches WHERE state IN ('PENDING','LEASED','DEAD') LIMIT 1",
                String.class)
            .isEmpty());
    result.put(
        "hasQuarantine",
        !jdbc.queryForList(
                "SELECT id FROM projection_errors WHERE state='QUARANTINED' LIMIT 1", String.class)
            .isEmpty());
    if (deep) {
      var missing =
          jdbc.queryForList(
              "SELECT d.sequence_no FROM domain_events d WHERE NOT EXISTS (SELECT 1 FROM projection_receipts r WHERE r.event_id=d.event_id) ORDER BY d.sequence_no LIMIT 1",
              Long.class);
      result.put("firstUnprojectedSequence", missing.isEmpty() ? null : missing.getFirst());
      result.put("noMissingAtRead", missing.isEmpty());
    }
    result.put("observedAt", clock.instant().toString());
    return result;
  }
}
