package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Offline four-source event transport cutover. Writers and consumers must be stopped. */
public final class ShardEventTransportMaintenance {
  private ShardEventTransportMaintenance() {}

  /** Fail closed before starting sharded money writers, including during a partial cutover. */
  public static void requireMode(Map<Integer, DataSource> sources, String expected) {
    requireFour(sources);
    if (!Set.of("POLLING", "CDC").contains(expected))
      throw new IllegalArgumentException("Expected transport mode is invalid");
    for (int id = 0; id < 4; id++) {
      try (var c = sources.get(id).getConnection()) {
        if (scalar(c, "SELECT physical_shard FROM v3_shard_identity WHERE id=1") != id)
          throw new IllegalStateException("Shard identity differs at source " + id);
        try (var s = c.prepareStatement("SELECT mode FROM event_transport WHERE id=1")) {
          s.setQueryTimeout(3);
          try (var r = s.executeQuery()) {
            if (!r.next() || !expected.equals(r.getString(1)) || r.next())
              throw new IllegalStateException("Event transport mode differs at source " + id);
          }
        }
      } catch (SQLException failure) {
        throw new IllegalStateException("Cannot verify event transport at source " + id, failure);
      }
    }
  }

  /**
   * Preflight all sources before updating any. A commit failure can leave a partial switch;
   * stopped-writer retry accepts already-switched sources only at their recorded cutoff.
   */
  public static Map<Integer, Long> switchModes(
      Map<Integer, Connection> sources, String expected, String next, boolean stoppedWriters)
      throws SQLException {
    requireFour(sources);
    if (!stoppedWriters
        || !Set.of("POLLING", "CDC").contains(expected)
        || !Set.of("POLLING", "CDC").contains(next)
        || expected.equals(next))
      throw new IllegalArgumentException(
          "Stop all writers and consumers before switching different transport modes");
    for (int id = 0; id < 4; id++)
      if (sources.get(id) == null || !sources.get(id).getAutoCommit())
        throw new IllegalArgumentException("Four dedicated auto-commit connections are required");

    var opened = new ArrayList<Connection>();
    var resettable = Collections.newSetFromMap(new IdentityHashMap<Connection, Boolean>());
    var cutoffs = new LinkedHashMap<Integer, Long>();
    var modes = new HashMap<Integer, String>();
    boolean commitStarted = false;
    try {
      for (int id = 0; id < 4; id++) {
        var c = sources.get(id);
        c.setAutoCommit(false);
        opened.add(c);
      }
      for (int id = 0; id < 4; id++) {
        var c = sources.get(id);
        if (scalar(c, "SELECT physical_shard FROM v3_shard_identity WHERE id=1 FOR UPDATE") != id)
          throw new IllegalStateException("Physical source identity differs at source " + id);
        String mode;
        long recorded;
        try (var s =
                c.prepareStatement(
                    "SELECT mode,cutoff_sequence FROM event_transport WHERE id=1 FOR UPDATE");
            var r = s.executeQuery()) {
          if (!r.next())
            throw new IllegalStateException("Event transport row missing at source " + id);
          mode = r.getString(1);
          recorded = r.getLong(2);
          if (r.next())
            throw new IllegalStateException("Multiple event transport rows at source " + id);
        }
        if (!expected.equals(mode) && !next.equals(mode))
          throw new IllegalStateException("Unexpected event transport mode at source " + id);
        EventTransportMaintenance.requireDrained(c);
        if (unreceived(c) != 0)
          throw new IllegalStateException("Unreceived CDC event at source " + id);
        if (scalar(c, "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'") != 0)
          throw new IllegalStateException("Unresolved quarantine at source " + id);
        long cutoff = scalar(c, "SELECT COALESCE(MAX(sequence_no),0) FROM domain_events");
        if (next.equals(mode) && recorded != cutoff)
          throw new IllegalStateException(
              "Already switched source changed after its cutoff: " + id);
        modes.put(id, mode);
        cutoffs.put(id, cutoff);
      }
      for (int id = 0; id < 4; id++) {
        if (next.equals(modes.get(id))) continue;
        try (var s =
            sources
                .get(id)
                .prepareStatement(
                    "UPDATE event_transport SET mode=?,cutoff_sequence=?,changed_at=CURRENT_TIMESTAMP(6) WHERE id=1")) {
          s.setString(1, next);
          s.setLong(2, cutoffs.get(id));
          if (s.executeUpdate() != 1)
            throw new IllegalStateException("Event transport row changed at source " + id);
        }
      }
      // Keep core as the last committed source. A failed commit is still an unknown outcome.
      for (int id : List.of(1, 2, 3, 0)) {
        commitStarted = true;
        var c = sources.get(id);
        c.commit();
        resettable.add(c);
      }
      return Map.copyOf(cutoffs);
    } catch (SQLException | RuntimeException failure) {
      for (var c : opened) {
        if (resettable.contains(c)) continue;
        try {
          c.rollback();
          resettable.add(c);
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      if (commitStarted)
        throw new IllegalStateException(
            "Four-source cutover may be partial; keep writers stopped and retry after inspection",
            failure);
      throw failure;
    } finally {
      for (var c : opened) {
        if (!resettable.contains(c)) continue;
        try {
          c.setAutoCommit(true);
        } catch (SQLException ignored) {
          /* Caller closes or discards a broken connection. */
        }
      }
    }
  }

  private static void requireFour(Map<Integer, ?> sources) {
    if (sources == null || !sources.keySet().equals(Set.of(0, 1, 2, 3)))
      throw new IllegalArgumentException("Exactly four source databases are required");
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (var s = c.prepareStatement(sql);
        var r = s.executeQuery()) {
      if (!r.next()) throw new IllegalStateException("Source metadata row is missing");
      long value = r.getLong(1);
      if (r.wasNull() || r.next())
        throw new IllegalStateException("Source metadata row is invalid");
      return value;
    }
  }

  private static long unreceived(Connection c) throws SQLException {
    try (var s =
        c.prepareStatement(
            "SELECT COUNT(*) FROM domain_events d WHERE d.delivery_mode='CDC' AND NOT EXISTS (SELECT 1 FROM event_receipts r WHERE r.consumer_name=? AND r.event_id=d.event_id)")) {
      s.setString(1, EventDelivery.CONSUMER);
      try (var r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !"--stopped-writers".equals(args[0]))
      throw new IllegalArgumentException("Usage: --stopped-writers POLLING CDC (or CDC POLLING)");
    try (var core = connect("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD");
        var one =
            connect("SHARD_1_DATABASE_URL", "SHARD_1_DATABASE_USER", "SHARD_1_DATABASE_PASSWORD");
        var two =
            connect("SHARD_2_DATABASE_URL", "SHARD_2_DATABASE_USER", "SHARD_2_DATABASE_PASSWORD");
        var three =
            connect("SHARD_3_DATABASE_URL", "SHARD_3_DATABASE_USER", "SHARD_3_DATABASE_PASSWORD")) {
      var result = switchModes(Map.of(0, core, 1, one, 2, two, 3, three), args[1], args[2], true);
      System.out.println("Stopped-writer source cutoffs: " + new TreeMap<>(result));
    }
  }

  private static Connection connect(String url, String user, String password) throws SQLException {
    return DriverManager.getConnection(env(url), env(user), env(password));
  }

  private static String env(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    return value;
  }
}
