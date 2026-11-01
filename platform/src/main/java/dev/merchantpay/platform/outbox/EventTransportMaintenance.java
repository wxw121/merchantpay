package dev.merchantpay.platform.outbox;

import java.sql.*;
import java.util.Set;

/** Offline operation: stop ingress AND every business writer before running this utility. */
public final class EventTransportMaintenance {
  private EventTransportMaintenance() {}

  public static long switchMode(Connection c, String expected, String next, boolean stoppedWriters)
      throws SQLException {
    if (!stoppedWriters
        || !Set.of("POLLING", "CDC").contains(expected)
        || !Set.of("POLLING", "CDC").contains(next)
        || expected.equals(next))
      throw new IllegalArgumentException(
          "Stop all writers, drain with workers/consumer, stop them, then switch different POLLING/CDC modes");
    if (!c.getAutoCommit())
      throw new IllegalArgumentException("A dedicated auto-commit connection is required");
    c.setAutoCommit(false);
    try {
      try (var s = c.prepareStatement("SELECT mode FROM event_transport WHERE id=1 FOR UPDATE");
          var r = s.executeQuery()) {
        if (!r.next() || !expected.equals(r.getString(1)))
          throw new IllegalStateException("Transport changed; expected mode differs");
      }
      for (String sql :
          new String[] {
            "SELECT COUNT(*) FROM outbox_tasks WHERE status<>'DONE'",
            "SELECT COUNT(*) FROM payments WHERE status NOT IN ('SUCCEEDED','FAILED','CLOSED')",
            "SELECT COUNT(*) FROM refunds WHERE status NOT IN ('SUCCEEDED','FAILED')",
            "SELECT COUNT(*) FROM inbox_notifications WHERE status='RECEIVED'"
          }) {
        if (scalar(c, sql) != 0)
          throw new IllegalStateException(
              "Business work is not drained; resume old mode workers before switching");
      }
      try (var s =
          c.prepareStatement(
              "SELECT d.event_type FROM domain_events d WHERE d.delivery_mode='CDC' AND NOT EXISTS (SELECT 1 FROM event_receipts r WHERE r.consumer_name=? AND r.event_id=d.event_id)")) {
        s.setString(1, EventDelivery.CONSUMER);
        try (var r = s.executeQuery()) {
          while (r.next())
            if (EventKinds.taskable(r.getString(1)))
              throw new IllegalStateException(
                  "Undelivered CDC business event; inspect lag/quarantine and drain first");
        }
      }
      // This is an allocation watermark only. Stopped writers are the prerequisite, not MAX's
      // ordering.
      long cutoff = scalar(c, "SELECT COALESCE(MAX(sequence_no),0) FROM domain_events");
      try (var s =
          c.prepareStatement(
              "UPDATE event_transport SET mode=?,cutoff_sequence=?,changed_at=CURRENT_TIMESTAMP(6) WHERE id=1")) {
        s.setString(1, next);
        s.setLong(2, cutoff);
        s.executeUpdate();
      }
      c.commit();
      return cutoff;
    } catch (SQLException | RuntimeException failure) {
      c.rollback();
      throw failure;
    } finally {
      c.setAutoCommit(true);
    }
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (var s = c.prepareStatement(sql);
        var r = s.executeQuery()) {
      r.next();
      return r.getLong(1);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !"--stopped-writers".equals(args[0]))
      throw new IllegalArgumentException("Usage: --stopped-writers POLLING CDC (or CDC POLLING)");
    try (var c =
        DriverManager.getConnection(
            env("DATABASE_URL"), env("DATABASE_USER"), env("DATABASE_PASSWORD"))) {
      System.out.println(
          "Transport changed; stopped-writer cutoff=" + switchMode(c, args[1], args[2], true));
    }
  }

  private static String env(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    return value;
  }
}
