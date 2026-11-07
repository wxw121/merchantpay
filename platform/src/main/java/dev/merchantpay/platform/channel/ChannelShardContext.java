package dev.merchantpay.platform.channel;

import java.util.function.IntSupplier;

/** Pinned shard checkpoint operations needed by channel code. */
public interface ChannelShardContext {
  void beforeNetwork();

  void assertLease();

  int write(IntSupplier action);

  void commit(Runnable action);
}
