package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.ShardMigrationBatchCopy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/shards/plans")
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled",
      "merchantpay.sharding.migration-batch-enabled"
    },
    havingValue = "true")
public final class ShardMigrationBatchCopyController {
  public record Request(
      @NotNull @Min(1) Long expectedVersion, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final ShardMigrationBatchCopy batches;

  public ShardMigrationBatchCopyController(Access access, ShardMigrationBatchCopy batches) {
    this.access = access;
    this.batches = batches;
  }

  private Actor actor() {
    var caller = access.caller();
    return new Actor(caller.actor(), caller.merchantId(), caller.roles());
  }

  @PostMapping("/{id}/copy/batches/next")
  ShardMigrationBatchCopy.Status next(@PathVariable String id, @Valid @RequestBody Request input) {
    return batches.next(
        id, input.expectedVersion(), input.reason(), input.writersStopped(), actor());
  }

  @PostMapping("/{id}/copy/batches/discard-next")
  ShardMigrationBatchCopy.Status discardNext(
      @PathVariable String id, @Valid @RequestBody Request input) {
    return batches.discardNext(
        id, input.expectedVersion(), input.reason(), input.writersStopped(), actor());
  }
}
