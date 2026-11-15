package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.channel.ChannelShardContext;
import dev.merchantpay.platform.outbox.TaskQueue;
import java.util.function.IntSupplier;

/** Immutable explicit envelope, including when another task's thread leads a result batch. */
public final class ShardChannelContext implements ChannelShardContext {
  private final ShardCatalog.Route route;
  private final TaskQueue.Task task;
  private final TaskQueue queue;
  private final ShardMoney money;

  public ShardChannelContext(
      ShardCatalog.Route route, TaskQueue.Task task, TaskQueue queue, ShardMoney money) {
    this.route = route;
    this.task = task;
    this.queue = queue;
    this.money = money;
  }

  public void assertLease() {
    if (task != null) queue.assertPinned(task, route.bucket(), route.epoch());
  }

  public int write(IntSupplier action) {
    return money.checkpoint(
        route,
        services -> {
          assertLease();
          return action.getAsInt();
        });
  }

  public void commit(Runnable action) {
    write(
        () -> {
          action.run();
          return 0;
        });
  }

  public void beforeNetwork() {
    commit(() -> {});
  }
}
