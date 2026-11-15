package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.ShardMigrationIsolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * Disabled-by-default core-origin bucket isolation; this controller cannot copy or switch routes.
 */
@RestController
@RequestMapping("/api/console/shards/plans")
@ConditionalOnProperty(
    name = {"merchantpay.sharding.enabled", "merchantpay.sharding.migration-quiesce-enabled"},
    havingValue = "true")
public final class ShardMigrationIsolationController {
  public record Action(
      @NotNull @Min(1) Long expectedVersion, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final ShardMigrationIsolation isolation;

  public ShardMigrationIsolationController(Access access, ShardMigrationIsolation isolation) {
    this.access = access;
    this.isolation = isolation;
  }

  private Actor actor() {
    var caller = access.caller();
    return new Actor(caller.actor(), caller.merchantId(), caller.roles());
  }

  @GetMapping("/{id}/isolation")
  ShardMigrationIsolation.Isolation status(@PathVariable String id) {
    return isolation.status(id, actor());
  }

  @PostMapping("/{id}/quiesce")
  ShardMigrationIsolation.Isolation quiesce(
      @PathVariable String id, @Valid @RequestBody Action input) {
    return isolation.quiesce(
        id, input.expectedVersion(), input.reason(), input.writersStopped(), actor());
  }

  @PostMapping("/{id}/release")
  ShardMigrationIsolation.Isolation release(
      @PathVariable String id, @Valid @RequestBody Action input) {
    return isolation.release(
        id, input.expectedVersion(), input.reason(), input.writersStopped(), actor());
  }
}
