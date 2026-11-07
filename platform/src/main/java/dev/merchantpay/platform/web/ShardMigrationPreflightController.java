package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.ShardMigrationPreflight;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/shards/plans")
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
public final class ShardMigrationPreflightController {
  private final Access access;
  private final ShardMigrationPreflight preflight;

  public ShardMigrationPreflightController(Access access, ShardMigrationPreflight preflight) {
    this.access = access;
    this.preflight = preflight;
  }

  @GetMapping("/{id}/preflight")
  ShardMigrationPreflight.Inspection preflight(@PathVariable String id) {
    var caller = access.caller();
    return preflight.inspect(id, new Actor(caller.actor(), caller.merchantId(), caller.roles()));
  }
}
