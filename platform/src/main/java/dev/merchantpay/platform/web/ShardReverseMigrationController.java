package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.ShardReverseMigration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/shards/plans")
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled", "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled", "merchantpay.sharding.migration-batch-enabled",
      "merchantpay.sharding.migration-cutover-enabled", "merchantpay.sharding.reverse-enabled"
    },
    havingValue = "true")
public final class ShardReverseMigrationController {
  public record Request(
      @NotNull @Min(1) Long expectedVersion, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final ShardReverseMigration reverse;

  public ShardReverseMigrationController(Access access, ShardReverseMigration reverse) {
    this.access = access;
    this.reverse = reverse;
  }

  @PostMapping("/{id}/reverse/next")
  ShardReverseMigration.Status next(@PathVariable String id, @Valid @RequestBody Request input) {
    var caller = access.caller();
    var actor = new Actor(caller.actor(), caller.merchantId(), caller.roles());
    return reverse.next(id, input.expectedVersion(), input.reason(), input.writersStopped(), actor);
  }

  @GetMapping("/{id}/reverse")
  ShardReverseMigration.Status status(@PathVariable String id) {
    var caller = access.caller();
    return reverse.status(id, new Actor(caller.actor(), caller.merchantId(), caller.roles()));
  }
}
