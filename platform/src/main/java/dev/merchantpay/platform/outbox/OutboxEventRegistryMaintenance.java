package dev.merchantpay.platform.outbox;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** Repairs the permanent event registry after a stopped-writer rollback to a pre-V14 binary. */
public final class OutboxEventRegistryMaintenance {
  private OutboxEventRegistryMaintenance() {}

  public record Result(long scanned, long inserted) {}

  public static Result reconcile(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection()) {
      return reconcile(connection);
    }
  }

  public static Result reconcile(Connection connection) throws SQLException {
    // Pre-V18 installations have only task facts; V2 also has CDC and descriptive event facts.
    boolean v2;
    try (ResultSet tables =
        connection
            .getMetaData()
            .getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
      v2 = false;
      while (tables.next())
        if ("domain_events".equalsIgnoreCase(tables.getString("TABLE_NAME"))) v2 = true;
    }
    String factsSql =
        v2
            ? "(SELECT id,event_id FROM outbox_task_records UNION SELECT id,event_id FROM domain_events)"
            : "outbox_task_records";
    if (exists(
            connection,
            "SELECT 1 FROM "
                + factsSql
                + " t JOIN outbox_events e ON e.id=t.id WHERE e.event_id<>t.event_id LIMIT 1")
        || exists(
            connection,
            "SELECT 1 FROM "
                + factsSql
                + " t JOIN outbox_events e ON e.event_id=t.event_id WHERE e.id<>t.id LIMIT 1")
        || exists(
            connection,
            "SELECT 1 FROM "
                + factsSql
                + " a JOIN "
                + factsSql
                + " b ON a.id=b.id WHERE a.event_id<>b.event_id LIMIT 1")
        || exists(
            connection,
            "SELECT 1 FROM "
                + factsSql
                + " a JOIN "
                + factsSql
                + " b ON a.event_id=b.event_id WHERE a.id<>b.id LIMIT 1")) {
      throw new IllegalStateException(
          "Conflicting outbox event identity; registry was not changed");
    }
    boolean originalAutoCommit = connection.getAutoCommit();
    long scanned = 0;
    long inserted = 0;
    String afterId = "";
    try {
      connection.setAutoCommit(false);
      while (true) {
        List<Event> page = new ArrayList<>();
        try (PreparedStatement select =
            connection.prepareStatement(
                "SELECT t.id,t.event_id,e.id FROM "
                    + factsSql
                    + " t LEFT JOIN outbox_events e ON e.id=t.id WHERE t.id>? ORDER BY t.id LIMIT 500")) {
          select.setString(1, afterId);
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
              page.add(new Event(rows.getString(1), rows.getString(2), rows.getString(3) == null));
            }
          }
        }
        if (page.isEmpty()) {
          connection.commit();
          break;
        }
        try (PreparedStatement insert =
            connection.prepareStatement("INSERT INTO outbox_events(id,event_id) VALUES (?,?)")) {
          for (Event event : page) {
            if (!event.missing()) continue;
            insert.setString(1, event.id());
            insert.setString(2, event.eventId());
            insert.addBatch();
            inserted++;
          }
          insert.executeBatch();
        }
        scanned += page.size();
        afterId = page.getLast().id();
        connection.commit();
      }
      long facts = count(connection, "SELECT COUNT(*) FROM " + factsSql + " t");
      long registered = count(connection, "SELECT COUNT(*) FROM outbox_events");
      long matching =
          count(
              connection,
              "SELECT COUNT(*) FROM "
                  + factsSql
                  + " t JOIN outbox_events e ON e.id=t.id AND e.event_id=t.event_id");
      if (facts != registered || facts != matching) {
        throw new IllegalStateException(
            "Outbox registry verification failed: facts="
                + facts
                + ", registered="
                + registered
                + ", matching="
                + matching);
      }
      return new Result(scanned, inserted);
    } catch (SQLException | RuntimeException failure) {
      connection.rollback();
      throw failure;
    } finally {
      connection.setAutoCommit(originalAutoCommit);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !"--stopped-writers".equals(args[0])) {
      throw new IllegalArgumentException(
          "Stop every old platform writer, then pass --stopped-writers");
    }
    String url = require("DATABASE_URL");
    String user = require("DATABASE_USER");
    String password = require("DATABASE_PASSWORD");
    try (Connection connection = DriverManager.getConnection(url, user, password)) {
      Result result = reconcile(connection);
      System.out.println(
          "Outbox event identities verified; scanned="
              + result.scanned()
              + ", inserted="
              + result.inserted());
    }
  }

  private static String require(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private static boolean exists(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next();
    }
  }

  private static long count(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private record Event(String id, String eventId, boolean missing) {}
}
