package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.ShardMigrationManifest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/shards/plans")
@ConditionalOnProperty(
    name = {"merchantpay.sharding.enabled", "merchantpay.sharding.migration-quiesce-enabled"},
    havingValue = "true")
public final class ShardMigrationManifestController {
  public record Capture(
      @NotNull @Min(1) Long expectedVersion, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final ShardMigrationManifest manifests;

  public ShardMigrationManifestController(Access access, ShardMigrationManifest manifests) {
    this.access = access;
    this.manifests = manifests;
  }

  private Actor actor() {
    var caller = access.caller();
    return new Actor(caller.actor(), caller.merchantId(), caller.roles());
  }

  @GetMapping("/{id}/manifest")
  ShardMigrationManifest.Manifest read(@PathVariable String id) {
    return manifests.read(id, actor());
  }

  @PostMapping("/{id}/manifest")
  ShardMigrationManifest.Manifest capture(
      @PathVariable String id, @Valid @RequestBody Capture input) {
    return manifests.capture(
        id, input.expectedVersion(), input.reason(), input.writersStopped(), actor());
  }
}
