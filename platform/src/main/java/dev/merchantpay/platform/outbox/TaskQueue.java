package dev.merchantpay.platform.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskQueue {
  public record Task(
      String id,
      String eventId,
      String kind,
      String aggregateId,
      String merchantId,
      int attempts,
      String token) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final TransactionTemplate completionTransactions;
  private final Clock clock;
  private final boolean bulkSql;
  private final boolean claimBulkSql;

  public TaskQueue(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
    this(jdbc, transactions, clock, false);
  }

  public TaskQueue(
      JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, boolean bulkSql) {
    this(jdbc, transactions, clock, bulkSql, false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public TaskQueue(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${merchantpay.task-completion-bulk-sql:false}")
          boolean bulkSql,
      @org.springframework.beans.factory.annotation.Value(
              "${merchantpay.task-claim-bulk-sql:false}")
          boolean claimBulkSql) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.completionTransactions = new TransactionTemplate(transactions.getTransactionManager());
    this.completionTransactions.setTimeout(3);
    this.clock = clock;
    this.bulkSql = bulkSql;
    this.claimBulkSql = claimBulkSql;
  }

  public Optional<Task> claim() {
    return claimBatch(1).stream().findFirst();
  }

  private static final RowMapper<Task> MAPPER =
      (rs, row) ->
          new Task(
              rs.getString("id"),
              rs.getString("event_id"),
              rs.getString("kind"),
              rs.getString("aggregate_id"),
              rs.getString("merchant_id"),
              rs.getInt("attempts") + 1,
              UUID.randomUUID().toString());

  public List<Task> claimBatch(int limit) {
    return claimBatch(limit, null);
  }

  public List<Task> claimBatch(int limit, boolean bulk) {
    return claimBatch(limit, bulk ? "BULK" : "FAST");
  }

  public List<Task> claimBatch(int limit, String queueClass) {
    if (limit < 1 || limit > 64) {
      throw new IllegalArgumentException("Claim batch must contain 1..64 tasks");
    }
    if (queueClass != null
        && !java.util.Set.of("FAST", "BULK", "INBOX", "NOTIFY").contains(queueClass)) {
      throw new IllegalArgumentException("Unknown task queue class");
    }
    if (claimBulkSql
        && org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()) {
      throw new IllegalStateException(
          "Claim tasks outside a business transaction so leases commit before dispatch");
    }
    return transactions.execute(
        tx -> {
          var now = Timestamp.from(clock.instant());
          String filter = queueClass == null ? "" : " AND queue_class='" + queueClass + "'";
          // Separate ordered index ranges avoid the OR/filesort that locked/scanned the whole
          // backlog.
          var tasks =
              new ArrayList<>(
                  jdbc.query(
                      "SELECT * FROM outbox_tasks WHERE status='RUNNING' AND lease_until<?"
                          + filter
                          + " ORDER BY lease_until,id LIMIT ? FOR UPDATE SKIP LOCKED",
                      MAPPER,
                      now,
                      limit));
          if (tasks.size() < limit) {
            tasks.addAll(
                jdbc.query(
                    "SELECT * FROM outbox_tasks WHERE status='PENDING' AND due_at<=?"
                        + filter
                        + " ORDER BY due_at,id LIMIT ? FOR UPDATE SKIP LOCKED",
                    MAPPER,
                    now,
                    limit - tasks.size()));
          }
          if (!tasks.isEmpty()) {
            var lease = Timestamp.from(clock.instant().plusSeconds(60));
            if (claimBulkSql && tasks.size() > 1) {
              claimBulk(tasks, lease, now);
            } else {
              jdbc.batchUpdate(
                  "UPDATE outbox_tasks SET status='RUNNING',attempts=?,lease_token=?,lease_until=?,updated_at=? WHERE id=?",
                  tasks,
                  tasks.size(),
                  (statement, task) -> {
                    statement.setInt(1, task.attempts());
                    statement.setString(2, task.token());
                    statement.setTimestamp(3, lease);
                    statement.setTimestamp(4, now);
                    statement.setString(5, task.id());
                  });
            }
          }
          return List.copyOf(tasks);
        });
  }

  private void claimBulk(List<Task> tasks, Timestamp lease, Timestamp now) {
    // The preceding SKIP LOCKED selects already own every affected row until commit.
    // CASE preserves independently generated tokens; no worker sees this list before commit.
    var ordered = tasks.stream().sorted(java.util.Comparator.comparing(Task::id)).toList();
    var bindings = new ArrayList<Object>(ordered.size() * 3 + 2);
    for (var task : ordered) {
      bindings.add(task.id());
      bindings.add(task.token());
    }
    bindings.add(lease);
    bindings.add(now);
    for (var task : ordered) bindings.add(task.id());
    int changed =
        jdbc.update(
            "UPDATE outbox_tasks SET status='RUNNING',attempts=attempts+1,lease_token=CASE id "
                + String.join(" ", java.util.Collections.nCopies(ordered.size(), "WHEN ? THEN ?"))
                + " END,lease_until=?,updated_at=? WHERE id IN ("
                + String.join(",", java.util.Collections.nCopies(ordered.size(), "?"))
                + ")",
            bindings.toArray());
    if (changed != ordered.size())
      throw new IllegalStateException("Task claim affected row count does not match locked rows");
  }

  public boolean complete(Task task) {
    return jdbc.update(
            "UPDATE outbox_tasks SET status='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=?",
            Timestamp.from(clock.instant()),
            task.id(),
            task.token())
        == 1;
  }

  /** A bounded commit group, with the same per-task lease fencing as individual completion. */
  public List<Boolean> completeBatch(List<Task> tasks) {
    var batch = List.copyOf(tasks);
    if (batch.isEmpty() || batch.size() > 64) {
      throw new IllegalArgumentException("Completion batch must contain 1..64 tasks");
    }
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      throw new IllegalStateException("Acknowledge tasks after the business transaction commits");
    }
    if (bulkSql && batch.size() > 1) return completeBulk(batch);
    return completionTransactions.execute(
        tx -> {
          var results = new ArrayList<Boolean>(java.util.Collections.nCopies(batch.size(), false));
          // Consistent PK order also covers overlapping stale/current completions across instances.
          var indices =
              java.util.stream.IntStream.range(0, batch.size())
                  .boxed()
                  .sorted(java.util.Comparator.comparing(index -> batch.get(index).id()))
                  .toList();
          for (int index : indices) results.set(index, complete(batch.get(index)));
          return List.copyOf(results);
        });
  }

  private record Owner(String id, String status, String token) {}

  private List<Boolean> completeBulk(List<Task> batch) {
    return completionTransactions.execute(
        tx -> {
          var ids = batch.stream().map(Task::id).distinct().sorted().toList();
          var owners = new java.util.HashMap<String, String>();
          // PK-only predicates keep the locking read bounded to this group's rows. Locks survive
          // until commit, preventing reclaim between ownership checks and the shared UPDATE.
          var rows =
              jdbc.query(
                  "SELECT id,status,lease_token FROM outbox_tasks WHERE id IN ("
                      + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
                      + ") ORDER BY id FOR UPDATE",
                  (rs, index) ->
                      new Owner(
                          rs.getString("id"), rs.getString("status"), rs.getString("lease_token")),
                  ids.toArray());
          for (var row : rows)
            if (row.status().equals("RUNNING") && row.token() != null)
              owners.put(row.id(), row.token());
          var results = new ArrayList<Boolean>(java.util.Collections.nCopies(batch.size(), false));
          var accepted = new ArrayList<Task>();
          for (int index = 0; index < batch.size(); index++) {
            var task = batch.get(index);
            var owner = owners.get(task.id());
            if (owner != null && owner.equals(task.token())) {
              results.set(index, true);
              accepted.add(task);
              owners.remove(task.id()); // The same lease can win only its first input occurrence.
            }
          }
          if (!accepted.isEmpty()) {
            var bindings = new ArrayList<Object>();
            bindings.add(Timestamp.from(clock.instant()));
            for (var task : accepted) {
              bindings.add(task.id());
              bindings.add(task.token());
            }
            int changed =
                jdbc.update(
                    "UPDATE outbox_tasks SET status='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=? WHERE status='RUNNING' AND ("
                        + String.join(
                            " OR ",
                            java.util.Collections.nCopies(
                                accepted.size(), "(id=? AND lease_token=?)"))
                        + ")",
                    bindings.toArray());
            if (changed != accepted.size())
              throw new IllegalStateException("Task completion ownership changed while locked");
          }
          return List.copyOf(results);
        });
  }

  public static final class LeaseLost extends RuntimeException {
    public LeaseLost() {
      super("Task lease is no longer owned by this execution");
    }
  }

  /** Hold the lease row until the business checkpoint commits, so reclaim cannot race it. */
  public void assertOwned(Task task) {
    assertTaskLease(task);
    String table = batchTable(task);
    var rows =
        jdbc.queryForList(
            "SELECT run_token FROM " + table + " WHERE id=? FOR UPDATE", task.aggregateId());
    if (rows.isEmpty() || !task.token().equals(rows.getFirst().get("run_token")))
      throw new LeaseLost();
  }

  private void assertTaskLease(Task task) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      throw new IllegalStateException("Lease check requires the business transaction");
    }
    var owners =
        jdbc.queryForList(
            "SELECT id FROM outbox_tasks WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>? FOR UPDATE",
            String.class,
            task.id(),
            task.token(),
            Timestamp.from(clock.instant()));
    if (owners.isEmpty()) throw new LeaseLost();
  }

  private String batchTable(Task task) {
    return switch (task.kind()) {
      case "RECONCILE" -> "recon_batches";
      case "EXPORT" -> "exports";
      default -> throw new IllegalArgumentException("Not a batch task");
    };
  }

  public boolean begin(Task task) {
    return transactions.execute(
        transaction -> {
          assertTaskLease(task);
          String table = batchTable(task);
          var rows =
              jdbc.queryForList(
                  "SELECT status FROM " + table + " WHERE id=? FOR UPDATE", task.aggregateId());
          if (rows.isEmpty()) throw new LeaseLost();
          if (java.util.Set.of("COMPLETE", "QUARANTINED").contains(rows.getFirst().get("status")))
            return false;
          jdbc.update(
              "UPDATE " + table + " SET run_token=? WHERE id=?", task.token(), task.aggregateId());
          return true;
        });
  }

  public void fenced(Task task, Runnable checkpoint) {
    transactions.executeWithoutResult(
        transaction -> {
          assertOwned(task);
          checkpoint.run();
        });
  }

  public boolean renew(Task task) {
    return jdbc.update(
            "UPDATE outbox_tasks SET lease_until=? WHERE id=? AND status='RUNNING' AND lease_token=?",
            Timestamp.from(clock.instant().plusSeconds(60)),
            task.id(),
            task.token())
        == 1;
  }

  /** Move only completed, settled deliveries; both copies change in one bounded transaction. */
  public int archiveDone(int limit) {
    if (limit < 1 || limit > 500)
      throw new IllegalArgumentException("Archive batch must contain 1..500 tasks");
    return transactions.execute(
        tx -> {
          var ids =
              jdbc.queryForList(
                  "SELECT id FROM outbox_tasks WHERE status='DONE' AND updated_at<? ORDER BY updated_at,id LIMIT ? FOR UPDATE SKIP LOCKED",
                  String.class,
                  Timestamp.from(clock.instant().minusSeconds(86400)),
                  limit);
          if (ids.isEmpty()) return 0;
          String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
          jdbc.update(
              "INSERT INTO outbox_task_history SELECT * FROM outbox_tasks WHERE id IN ("
                  + placeholders
                  + ")",
              ids.toArray());
          int removed =
              jdbc.update(
                  "DELETE FROM outbox_tasks WHERE id IN (" + placeholders + ")", ids.toArray());
          if (removed != ids.size()) throw new IllegalStateException("Archive task set changed");
          return removed;
        });
  }

  public void retry(Task task, RuntimeException failure) {
    String reason = failure.getMessage();
    String safe = reason == null ? "unknown" : reason.substring(0, Math.min(1000, reason.length()));
    boolean conflict =
        failure instanceof dev.merchantpay.platform.common.ApiException error
            && error.code().equals("CHANNEL_EVIDENCE_CONFLICT");
    transactions.executeWithoutResult(
        tx -> {
          var now = clock.instant();
          var windows =
              jdbc.queryForList(
                  "SELECT retry_started_at FROM outbox_tasks WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>? FOR UPDATE",
                  Timestamp.class,
                  task.id(),
                  task.token(),
                  Timestamp.from(now));
          if (windows.isEmpty()) return;
          boolean channel = TaskRecovery.channel(task.kind());
          boolean notification = TaskRecovery.notification(task.kind());
          var deadline = windows.getFirst().toInstant().plus(TaskRecovery.NOTIFICATION_WINDOW);
          String stage = "STANDARD";
          if (channel) {
            String table = task.kind().equals("CHANNEL_REFUND") ? "refunds" : "payments";
            var unknown =
                jdbc.queryForList(
                    "SELECT unknown_since FROM "
                        + table
                        + " WHERE id=? AND merchant_id=? AND status IN ('UNKNOWN','CLOSE_PENDING')",
                    Timestamp.class,
                    task.aggregateId(),
                    task.merchantId());
            if (!unknown.isEmpty() && unknown.getFirst() != null)
              stage = TaskRecovery.stage(unknown.getFirst().toInstant(), now);
          }
          boolean dead =
              conflict
                  || (notification ? !now.isBefore(deadline) : !channel && task.attempts() >= 12);
          var due =
              now.plus(
                  channel || notification
                      ? TaskRecovery.queryDelay(task.attempts())
                      : java.time.Duration.ofSeconds(
                          Math.min(3600, 1L << Math.min(task.attempts(), 11))));
          if (notification && due.isAfter(deadline)) due = deadline;
          // Exhaustion changes only delivery state. Unknown money and refund reservations survive.
          jdbc.update(
              "UPDATE outbox_tasks SET status=?,due_at=?,recovery_stage=?,lease_token=NULL,lease_until=NULL,last_error=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=?",
              dead ? "DEAD" : "PENDING",
              Timestamp.from(due),
              stage,
              safe,
              Timestamp.from(now),
              task.id(),
              task.token());
        });
  }

  /** Check immediately before a notification send, including after a long outage or reclaim. */
  public boolean notificationMayRun(Task task) {
    if (!TaskRecovery.notification(task.kind()))
      throw new IllegalArgumentException("Not a notification task");
    return transactions.execute(
        tx -> {
          var now = clock.instant();
          var windows =
              jdbc.queryForList(
                  "SELECT retry_started_at FROM outbox_tasks WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>? FOR UPDATE",
                  Timestamp.class,
                  task.id(),
                  task.token(),
                  Timestamp.from(now));
          if (windows.isEmpty()) return false;
          if (now.isBefore(windows.getFirst().toInstant().plus(TaskRecovery.NOTIFICATION_WINDOW)))
            return true;
          jdbc.update(
              "UPDATE outbox_tasks SET status='DEAD',lease_token=NULL,lease_until=NULL,last_error='Notification automatic retry window expired',updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=?",
              Timestamp.from(now),
              task.id(),
              task.token());
          return false;
        });
  }
}
