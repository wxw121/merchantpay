package dev.merchantpay.platform.sharding;

import java.sql.*;
import java.util.*;

/** Stopped-writer preparation of one shared BUILDING generation across four physical sources. */
public final class ShardProjectionGenerationMaintenance {
  private ShardProjectionGenerationMaintenance() {}

  private record Control(String active, String building) {}

  public static void prepare(
      Map<Integer, Connection> sources, String generation, String reason, boolean stoppedWriters)
      throws SQLException {
    if (!stoppedWriters
        || sources == null
        || !sources.keySet().equals(Set.of(0, 1, 2, 3))
        || generation == null
        || !generation.matches("[A-Za-z0-9_-]{1,36}")
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000)
      throw new IllegalArgumentException(
          "Four stopped sources, a new generation and reason are required");
    for (int id = 0; id < 4; id++)
      if (sources.get(id) == null || !sources.get(id).getAutoCommit())
        throw new IllegalArgumentException("Four dedicated auto-commit connections are required");

    var opened = new ArrayList<Connection>();
    var resettable = Collections.newSetFromMap(new IdentityHashMap<Connection, Boolean>());
    var controls = new HashMap<Integer, Control>();
    boolean commitStarted = false;
    try {
      for (int id = 0; id < 4; id++) {
        var c = sources.get(id);
        c.setAutoCommit(false);
        opened.add(c);
      }
      for (int id = 0; id < 4; id++) {
        var c = sources.get(id);
        if (scalar(c, "SELECT physical_shard FROM v3_shard_identity WHERE id=1 FOR UPDATE") != id
            || !"CDC".equals(text(c, "SELECT mode FROM event_transport WHERE id=1 FOR UPDATE")))
          throw new IllegalStateException(
              "Projection source identity or CDC mode differs at shard " + id);
        Control control;
        try (var s =
                c.prepareStatement(
                    "SELECT active_generation,building_generation FROM projection_control WHERE id=1 FOR UPDATE");
            var r = s.executeQuery()) {
          if (!r.next())
            throw new IllegalStateException("Projection control missing at shard " + id);
          control = new Control(r.getString(1), r.getString(2));
          if (r.next())
            throw new IllegalStateException("Projection control duplicated at shard " + id);
        }
        if (control.active() != null && !generationState(c, control.active(), "SERVING"))
          throw new IllegalStateException("Serving projection generation differs at shard " + id);
        if (generation.equals(control.building())) {
          if (!emptyBuilding(c, generation))
            throw new IllegalStateException(
                "Prepared generation has already started scanning at shard " + id);
        } else {
          if (exists(c, "SELECT id FROM projection_generations WHERE id=?", generation))
            throw new IllegalStateException(
                "Projection generation ID was already used at shard " + id);
          if (control.building() != null
              && (!emptyBuilding(c, control.building())
                  || Objects.equals(control.active(), control.building())))
            throw new IllegalStateException(
                "Existing projection build is not empty at shard " + id);
        }
        controls.put(id, control);
      }
      for (int id = 0; id < 4; id++) {
        var control = controls.get(id);
        if (generation.equals(control.building())) continue;
        var c = sources.get(id);
        if (control.building() != null)
          update(
              c,
              "UPDATE projection_generations SET state='RETIRED' WHERE id=? AND state='BUILDING'",
              control.building());
        update(
            c,
            "INSERT INTO projection_generations(id,state,created_at) VALUES (?,'BUILDING',CURRENT_TIMESTAMP(6))",
            generation);
        update(
            c,
            "UPDATE projection_control SET building_generation=?,version=version+1 WHERE id=1",
            generation);
        try (var s =
            c.prepareStatement(
                "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,NULL,'shard-projection-maintenance','PROJECTION_SHARD_PREPARE',?,?,CURRENT_TIMESTAMP(6))")) {
          s.setString(1, UUID.randomUUID().toString());
          s.setString(2, generation);
          s.setString(3, reason);
          if (s.executeUpdate() != 1)
            throw new IllegalStateException("Projection audit failed at shard " + id);
        }
      }
      for (int id : List.of(1, 2, 3, 0)) {
        commitStarted = true;
        var c = sources.get(id);
        c.commit();
        resettable.add(c);
      }
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
            "Projection generation preparation may be partial; keep writers stopped and retry",
            failure);
      throw failure;
    } finally {
      for (var c : opened) {
        if (!resettable.contains(c)) continue;
        try {
          c.setAutoCommit(true);
        } catch (SQLException ignored) {
          /* Caller closes a broken connection. */
        }
      }
    }
  }

  private static boolean emptyBuilding(Connection c, String generation) throws SQLException {
    try (var s =
        c.prepareStatement(
            "SELECT state,scan_cursor,scan_complete FROM projection_generations WHERE id=? FOR UPDATE")) {
      s.setString(1, generation);
      try (var r = s.executeQuery()) {
        if (!r.next()
            || !"BUILDING".equals(r.getString(1))
            || !"".equals(r.getString(2))
            || r.getBoolean(3)
            || r.next()) return false;
      }
    }
    return !exists(c, "SELECT id FROM projection_batches WHERE generation=? LIMIT 1", generation);
  }

  private static boolean generationState(Connection c, String generation, String state)
      throws SQLException {
    try (var s = c.prepareStatement("SELECT state FROM projection_generations WHERE id=?")) {
      s.setString(1, generation);
      try (var r = s.executeQuery()) {
        return r.next() && state.equals(r.getString(1)) && !r.next();
      }
    }
  }

  private static boolean exists(Connection c, String sql, String value) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      s.setString(1, value);
      try (var r = s.executeQuery()) {
        return r.next();
      }
    }
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (var s = c.prepareStatement(sql);
        var r = s.executeQuery()) {
      if (!r.next()) throw new IllegalStateException("Projection source metadata is missing");
      long value = r.getLong(1);
      if (r.wasNull() || r.next())
        throw new IllegalStateException("Projection source metadata is invalid");
      return value;
    }
  }

  private static String text(Connection c, String sql) throws SQLException {
    try (var s = c.prepareStatement(sql);
        var r = s.executeQuery()) {
      if (!r.next()) throw new IllegalStateException("Projection source mode is missing");
      String value = r.getString(1);
      if (r.next()) throw new IllegalStateException("Projection source mode is ambiguous");
      return value;
    }
  }

  private static void update(Connection c, String sql, String value) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      s.setString(1, value);
      if (s.executeUpdate() != 1)
        throw new IllegalStateException("Projection generation write failed");
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !"--stopped-writers".equals(args[0]))
      throw new IllegalArgumentException("Usage: --stopped-writers GENERATION REASON");
    try (var core = connect("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD");
        var one =
            connect("SHARD_1_DATABASE_URL", "SHARD_1_DATABASE_USER", "SHARD_1_DATABASE_PASSWORD");
        var two =
            connect("SHARD_2_DATABASE_URL", "SHARD_2_DATABASE_USER", "SHARD_2_DATABASE_PASSWORD");
        var three =
            connect("SHARD_3_DATABASE_URL", "SHARD_3_DATABASE_USER", "SHARD_3_DATABASE_PASSWORD")) {
      prepare(Map.of(0, core, 1, one, 2, two, 3, three), args[1], args[2], true);
      System.out.println("Shared projection building generation prepared: " + args[1]);
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
