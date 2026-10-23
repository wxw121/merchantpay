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

  public TaskQueue(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.completionTransactions = new TransactionTemplate(transactions.getTransactionManager());
    this.completionTransactions.setTimeout(3);
    this.clock = clock;
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
          return List.copyOf(tasks);
        });
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

  public void retry(Task task, RuntimeException failure) {
    String reason = failure.getMessage();
    String safe = reason == null ? "unknown" : reason.substring(0, Math.min(1000, reason.length()));
    boolean conflict =
        failure instanceof dev.merchantpay.platform.common.ApiException error
            && error.code().equals("CHANNEL_EVIDENCE_CONFLICT");
    // Money outcomes remain UNKNOWN when delivery exhausts; only this delivery enters DEAD.
    jdbc.update(
        "UPDATE outbox_tasks SET status=?,due_at=?,lease_token=NULL,lease_until=NULL,last_error=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=?",
        conflict || task.attempts() >= 12 ? "DEAD" : "PENDING",
        Timestamp.from(
            clock.instant().plusSeconds(Math.min(3600, 1L << Math.min(task.attempts(), 11)))),
        safe,
        Timestamp.from(clock.instant()),
        task.id(),
        task.token());
  }
}
