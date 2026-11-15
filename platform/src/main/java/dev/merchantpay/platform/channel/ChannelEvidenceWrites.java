package dev.merchantpay.platform.channel;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Commit short evidence writes before permitting channel I/O or financial application. */
@Component
public final class ChannelEvidenceWrites implements AutoCloseable {
  private record Receipt(String channel, String requestKey, String body, Timestamp time) {}

  private record Pending(
      String key, IntSupplier action, Receipt receipt, CompletableFuture<Integer> result) {}

  private final TransactionTemplate transactions;
  private final MeterRegistry metrics;
  private final int maximum;
  private final Duration delay;
  private final JdbcTemplate receiptJdbc;
  private final boolean receiptBulkSql;
  private List<Pending> pending;
  private boolean closed;

  public ChannelEvidenceWrites(
      PlatformTransactionManager manager, MeterRegistry metrics, int maximum, Duration delay) {
    this(manager, metrics, maximum, delay, null, false);
  }

  @Autowired
  public ChannelEvidenceWrites(
      PlatformTransactionManager manager,
      MeterRegistry metrics,
      @Value("${merchantpay.channel-evidence-batch-size:1}") int maximum,
      @Value("${merchantpay.channel-evidence-batch-delay:0ms}") Duration delay,
      JdbcTemplate jdbc,
      @Value("${merchantpay.channel-receipt-bulk-sql:false}") boolean receiptBulkSql) {
    if (maximum < 1
        || maximum > 64
        || delay == null
        || delay.isNegative()
        || delay.compareTo(Duration.ofMillis(50)) > 0)
      throw new IllegalArgumentException(
          "Channel evidence batches require 1..64 writes and 0..50ms");
    transactions = new TransactionTemplate(manager);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(3);
    this.metrics = Objects.requireNonNull(metrics);
    this.maximum = maximum;
    this.delay = delay;
    this.receiptJdbc = jdbc;
    this.receiptBulkSql = receiptBulkSql;
    if (receiptBulkSql) Objects.requireNonNull(jdbc, "Receipt JDBC required for bulk SQL");
  }

  boolean bulkReceiptSqlEnabled() {
    return receiptBulkSql;
  }

  public int execute(String key, IntSupplier action) {
    return submit(
        new Pending(
            Objects.requireNonNull(key),
            Objects.requireNonNull(action),
            null,
            new CompletableFuture<>()));
  }

  public void retainReceipt(String channel, String requestKey, String body, Timestamp time) {
    Objects.requireNonNull(receiptJdbc, "Receipt JDBC required");
    var receipt =
        new Receipt(
            Objects.requireNonNull(channel),
            Objects.requireNonNull(requestKey),
            Objects.requireNonNull(body),
            Timestamp.from(Objects.requireNonNull(time).toInstant()));
    String key = channel + "\0" + requestKey;
    if (!receiptBulkSql) {
      execute(
          key,
          () ->
              receiptJdbc.update(
                  "UPDATE channel_attempts SET response_body=?,updated_at=? WHERE channel=? AND request_key=?",
                  receipt.body(),
                  receipt.time(),
                  channel,
                  requestKey));
      return;
    }
    submit(new Pending(key, null, receipt, new CompletableFuture<>()));
  }

  private int submit(Pending request) {
    if (maximum == 1 && request.receipt() == null) {
      synchronized (this) {
        if (closed) throw new IllegalStateException("Channel evidence writes are closed");
      }
      return request.action().getAsInt();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Channel evidence groups must not span an outer transaction");
    List<Pending> group;
    boolean leader;
    synchronized (this) {
      if (closed) throw new IllegalStateException("Channel evidence writes are closed");
      leader = pending == null || pending.size() == maximum;
      if (leader) pending = new ArrayList<>();
      group = pending;
      group.add(request);
    }
    if (leader) flush(group);
    try {
      return request.result().join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException r) throw r;
      if (e.getCause() instanceof Error f) throw f;
      throw e;
    }
  }

  private void flush(List<Pending> group) {
    Throwable interrupted = null;
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      interrupted = e;
    }
    synchronized (this) {
      if (pending == group) pending = null;
      group = group.stream().sorted(Comparator.comparing(Pending::key)).toList();
    }
    try {
      if (interrupted != null)
        throw new IllegalStateException(
            "Channel evidence group interrupted before commit", interrupted);
      var writes = group;
      var results =
          Objects.requireNonNull(
              transactions.execute(
                  status -> {
                    var values = new ArrayList<Integer>();
                    for (int i = 0; i < writes.size(); ) {
                      var item = writes.get(i);
                      if (item.receipt() == null) {
                        values.add(item.action().getAsInt());
                        i++;
                        continue;
                      }
                      var receipts = new ArrayList<Receipt>();
                      var keys = new HashSet<String>();
                      // Preserve generic-action and repeated-key order; never combine across either
                      // boundary.
                      int end = i;
                      while (end < writes.size()
                          && writes.get(end).receipt() != null
                          && keys.add(writes.get(end).key())) {
                        receipts.add(writes.get(end).receipt());
                        end++;
                      }
                      updateReceipts(receipts);
                      for (int n = i; n < end; n++) values.add(1);
                      i = end;
                    }
                    return values;
                  }));
      metrics.summary("merchantpay.channel.evidence.batch.size").record(group.size());
      metrics.counter("merchantpay.channel.evidence.batches", "outcome", "committed").increment();
      for (int i = 0; i < group.size(); i++) group.get(i).result().complete(results.get(i));
    } catch (RuntimeException | Error failure) {
      metrics.counter("merchantpay.channel.evidence.batches", "outcome", "failed").increment();
      group.forEach(item -> item.result().completeExceptionally(failure));
    }
  }

  private void updateReceipts(List<Receipt> receipts) {
    int count;
    metrics
        .counter(
            "merchantpay.channel.receipt.sql.attempts",
            "mode",
            receipts.size() > 1 ? "bulk" : "single")
        .increment();
    metrics.summary("merchantpay.channel.receipt.sql.batch.size").record(receipts.size());
    if (receipts.size() == 1) {
      var r = receipts.getFirst();
      count =
          receiptJdbc.update(
              "UPDATE channel_attempts SET response_body=?,updated_at=? WHERE channel=? AND request_key=?",
              r.body(),
              r.time(),
              r.channel(),
              r.requestKey());
    } else {
      var sql = new StringBuilder("UPDATE channel_attempts SET response_body=CASE ");
      var args = new ArrayList<Object>();
      for (var r : receipts) {
        sql.append("WHEN channel=? AND request_key=? THEN ? ");
        Collections.addAll(args, r.channel(), r.requestKey(), r.body());
      }
      sql.append("ELSE response_body END,updated_at=CASE ");
      for (var r : receipts) {
        sql.append("WHEN channel=? AND request_key=? THEN ? ");
        Collections.addAll(args, r.channel(), r.requestKey(), r.time());
      }
      sql.append("ELSE updated_at END WHERE (channel,request_key) IN (");
      sql.append(String.join(",", Collections.nCopies(receipts.size(), "(?,?)"))).append(")");
      for (var r : receipts) Collections.addAll(args, r.channel(), r.requestKey());
      count = receiptJdbc.update(sql.toString(), args.toArray());
    }
    if (count != receipts.size()) {
      // Connector/J useAffectedRows=true reports changed rows. A zero/short update is valid
      // only when every prepared key still exists; lock the keys until this group commits.
      var args = new ArrayList<Object>();
      for (var r : receipts) Collections.addAll(args, r.channel(), r.requestKey());
      var keys =
          receiptJdbc.query(
              "SELECT channel,request_key FROM channel_attempts WHERE (channel,request_key) IN ("
                  + String.join(",", Collections.nCopies(receipts.size(), "(?,?)"))
                  + ") FOR UPDATE",
              (rs, row) -> rs.getString(1) + "\0" + rs.getString(2),
              args.toArray());
      var expected =
          receipts.stream()
              .map(r -> r.channel() + "\0" + r.requestKey())
              .collect(java.util.stream.Collectors.toSet());
      if (count < 0 || count > receipts.size() || !new HashSet<>(keys).equals(expected))
        throw new IllegalStateException("Receipt batch requires every prepared evidence row");
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
  }
}
