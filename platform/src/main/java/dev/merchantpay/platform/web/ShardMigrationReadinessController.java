package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.ShardMigrationReadiness;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/shards/plans")
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled"
    },
    havingValue = "true")
public final class ShardMigrationReadinessController {
  public record Request(@NotNull @Min(1) Long expectedVersion, boolean writersStopped) {}

  private final Access access;
  private final ShardMigrationReadiness readiness;

  public ShardMigrationReadinessController(Access access, ShardMigrationReadiness readiness) {
    this.access = access;
    this.readiness = readiness;
  }

  @PostMapping("/{id}/readiness")
  ShardMigrationReadiness.Report inspect(
      @PathVariable String id, @Valid @RequestBody Request input) {
    var caller = access.caller();
    var actor = new Actor(caller.actor(), caller.merchantId(), caller.roles());
    return readiness.inspect(id, input.expectedVersion(), input.writersStopped(), actor);
  }
}
