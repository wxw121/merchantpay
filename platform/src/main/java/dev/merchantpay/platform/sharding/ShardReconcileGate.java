package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.OutboxReconcileGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
public final class ShardReconcileGate implements OutboxReconcileGate {
  private final ShardCoreOnlyGuard coreOnly;
  private final ShardExportReadGate sources;

  public ShardReconcileGate(ShardCoreOnlyGuard coreOnly, ShardExportReadGate sources) {
    this.coreOnly = coreOnly;
    this.sources = sources;
  }

  @Override
  public void requireReadable() {
    try {
      coreOnly.requireCoreOnly();
    } catch (ApiException moved) {
      if (!"SHARDED_CORE_WORKFLOW_PENDING".equals(moved.code())) throw moved;
      sources.capture();
    }
  }
}
