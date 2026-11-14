package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.ShardMigrationCutover;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/shards/plans")
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled",
      "merchantpay.sharding.migration-cutover-enabled"
    },
    havingValue = "true")
public final class ShardMigrationCutoverController {
  public record Request(
      @NotNull @Min(1) Long expectedVersion, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final ShardMigrationCutover cutover;

  public ShardMigrationCutoverController(Access access, ShardMigrationCutover cutover) {
    this.access = access;
    this.cutover = cutover;
  }

  private Actor actor() {
    var caller = access.caller();
    return new Actor(caller.actor(), caller.merchantId(), caller.roles());
  }

  @GetMapping("/{id}/cutover")
  ShardMigrationCutover.Cutover read(@PathVariable String id) {
    return cutover.read(id, actor());
  }

  @GetMapping("/{id}/finance-handoff")
  ShardMigrationCutover.FinanceHandoff financeHandoff(@PathVariable String id) {
    return cutover.financeHandoff(id, actor());
  }

  @PostMapping("/{id}/cutover")
  ShardMigrationCutover.Cutover execute(
      @PathVariable String id, @Valid @RequestBody Request input) {
    return cutover.execute(
        id, input.expectedVersion(), input.reason(), input.writersStopped(), actor());
  }
}
