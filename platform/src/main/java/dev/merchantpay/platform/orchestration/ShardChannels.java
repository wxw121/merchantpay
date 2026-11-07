package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.sharding.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit per-execution routing; no network call occurs in a shard transaction. */
public final class ShardChannels implements AutoCloseable, ChannelShardRouter {
  public record Execution(ShardCatalog.Route route, TaskQueue.Task task) {}

  private record Batch(ShardCatalog.Route route, ChannelResultBatcher writer) {}

  private final ShardTransactions transactions;
  private final ShardMoney money;
  private final Map<Integer, TaskQueue> queues;
  private final ChannelGateway gateway;
  private final InboxService inbox;
  private final NotificationService notifications;
  private final ChannelResultBatcher batches;
  private final ReceiptDirectory receipts;
  private final Map<Integer, Batch> writers = new ConcurrentHashMap<>();
  private volatile boolean closed;

  public ShardChannels(
      ShardTransactions transactions,
      ShardMoney money,
      Map<Integer, DataSource> sources,
      TaskQueue prototype,
      ChannelGateway gateway,
      InboxService inbox,
      NotificationService notifications,
      ChannelResultBatcher batches,
      ReceiptDirectory receipts,
      Clock clock) {
    this.transactions = transactions;
    this.money = money;
    this.gateway = gateway;
    this.inbox = inbox;
    this.notifications = Objects.requireNonNull(notifications);
    this.batches = batches;
    this.receipts = Objects.requireNonNull(receipts);
    money.setReceiptDirectory(receipts);
    var local = new HashMap<Integer, TaskQueue>();
    sources.forEach((id, source) -> local.put(id, prototype.forShard(new JdbcTemplate(source))));
    queues = Map.copyOf(local);
  }

  public List<Execution> claim(ShardCatalog.Route expected, String lane, int limit) {
    if (closed) return List.of();
    var tasks =
        transactions.write(
            expected,
            jdbc -> queue(expected).claimBucket(expected.bucket(), expected.epoch(), lane, limit));
    return tasks.stream().map(task -> new Execution(expected, task)).toList();
  }

  private TaskQueue queue(ShardCatalog.Route route) {
    var queue = queues.get(route.physicalShard());
    if (queue == null) throw new IllegalArgumentException("Unknown physical shard");
    return queue;
  }

  private ShardChannelContext context(ShardCatalog.Route route, TaskQueue.Task task) {
    return new ShardChannelContext(route, task, queue(route), money);
  }

  private ChannelResultBatcher writer(ShardCatalog.Route route) {
    if (closed) throw new IllegalStateException("Shard channel runtime is closed");
    if (!transactions.currentRoute(route.bucket()).equals(route))
      throw new ApiException("SHARD_ROUTE_UNAVAILABLE", 503, "原分片路由已变化");
    return writers
        .compute(
            route.bucket(),
            (bucket, previous) -> {
              if (previous != null && previous.route().equals(route)) return previous;
              if (previous != null) previous.writer().close();
              return new Batch(
                  route,
                  batches.forShard(
                      action ->
                          transactions.write(
                              route,
                              jdbc -> {
                                action.run();
                                return null;
                              })));
            })
        .writer();
  }

  private ChannelGateway gateway(ShardCatalog.Route route, ShardChannelContext context) {
    var local = money.channelServices(route.physicalShard());
    return gateway.forShard(
        local.jdbc(), local.payments(), local.refunds(), writer(route), context, receipts);
  }

  private InboxService inbox(
      ShardCatalog.Route route, ShardChannelContext context, ChannelGateway channel) {
    var local = money.channelServices(route.physicalShard());
    return inbox.forShard(local.jdbc(), local.outbox(), channel, context);
  }

  private NotificationService notification(ShardCatalog.Route route, ShardChannelContext context) {
    var local = money.channelServices(route.physicalShard());
    return notifications.forShard(
        local.payments(),
        local.refunds(),
        local.jdbc(),
        context::commit,
        () -> context.commit(() -> {}));
  }

  private static int bucket(ChannelApi.Receipt receipt) {
    return switch (Objects.toString(receipt.operation(), "")) {
      case "PAYMENT" -> PaymentBuckets.forPaymentId(receipt.aggregateId());
      case "REFUND" -> PaymentBuckets.forRefundId(receipt.aggregateId());
      default -> throw new ApiException("INVALID_CHANNEL_RECEIPT", 400, "通知操作无效");
    };
  }

  /** Called only after the original HMAC or sandbox signature validation succeeds. */
  public void storeVerified(
      ChannelApi.Receipt receipt, String body, String fingerprint, String signedForm) {
    var route = transactions.currentRoute(bucket(receipt));
    var context = context(route, null);
    inbox(route, context, gateway(route, context))
        .storeVerified(receipt, body, fingerprint, signedForm);
  }

  public void applyVerified(ChannelApi.Receipt receipt) {
    var route = transactions.currentRoute(bucket(receipt));
    var context = context(route, null);
    var channel = gateway(route, context);
    try {
      context.commit(() -> channel.applyInCheckpoint(receipt));
    } catch (ApiException conflict) {
      channel.retainRejected(receipt, conflict);
      throw conflict;
    }
  }

  public Map<String, Object> sandboxOriginal(String id) {
    int bucket = PaymentBuckets.forPaymentId(id);
    return transactions.read(
        bucket,
        jdbc -> {
          var rows =
              jdbc.queryForList(
                  "SELECT * FROM payments WHERE id=? AND channel='ALIPAY_SANDBOX'", id);
          if (rows.size() != 1) throw new ApiException("NOT_FOUND", 404, "沙箱原业务不存在");
          return rows.getFirst();
        });
  }

  /** False means the notification retry window was closed and its task was marked DEAD. */
  public boolean execute(Execution execution) {
    var route = execution.route();
    var task = execution.task();
    var context = context(route, task);
    context.commit(() -> {});
    var local = money.channelServices(route.physicalShard());
    int bucket;
    if ("PROCESS_INBOX".equals(task.kind())) {
      var rows =
          local
              .jdbc()
              .queryForList(
                  "SELECT aggregate_id,operation FROM inbox_notifications WHERE id=?",
                  task.aggregateId());
      if (rows.size() != 1)
        throw new IllegalStateException("Inbox task has no original notification");
      bucket =
          "PAYMENT".equals(rows.getFirst().get("operation"))
              ? PaymentBuckets.forPaymentId((String) rows.getFirst().get("aggregate_id"))
              : PaymentBuckets.forRefundId((String) rows.getFirst().get("aggregate_id"));
    } else
      bucket =
          ("CHANNEL_REFUND".equals(task.kind()) || task.kind().startsWith("REFUND_"))
              ? PaymentBuckets.forRefundId(task.aggregateId())
              : PaymentBuckets.forPaymentId(task.aggregateId());
    if (bucket != route.bucket())
      throw new IllegalStateException("Task and original route bucket differ");
    switch (task.kind()) {
      case "CHANNEL_PAYMENT", "CHANNEL_REFUND", "CHANNEL_CLOSE" ->
          gateway(route, context).execute(task);
      case "PROCESS_INBOX" -> {
        var channel = gateway(route, context);
        inbox(route, context, channel).process(task.aggregateId());
      }
      case "PAYMENT_SUCCEEDED",
          "PAYMENT_FAILED",
          "PAYMENT_CLOSED",
          "REFUND_SUCCEEDED",
          "REFUND_FAILED" -> {
        if (context.write(() -> queue(route).notificationMayRun(task) ? 1 : 0) == 0) return false;
        notification(route, context).deliver(task);
      }
      default -> throw new IllegalArgumentException("Not a routed channel or notification task");
    }
    return true;
  }

  public void complete(Execution execution) {
    context(execution.route(), execution.task())
        .commit(
            () -> {
              if (!queue(execution.route()).complete(execution.task()))
                throw new TaskQueue.LeaseLost();
            });
  }

  public void renew(Execution execution) {
    context(execution.route(), execution.task())
        .commit(
            () -> {
              if (!queue(execution.route()).renew(execution.task()))
                throw new TaskQueue.LeaseLost();
            });
  }

  public void retry(Execution execution, RuntimeException failure) {
    context(execution.route(), execution.task())
        .commit(() -> queue(execution.route()).retry(execution.task(), failure));
  }

  @Override
  public void close() {
    closed = true;
    writers.values().forEach(batch -> batch.writer().close());
  }
}
