package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.ShardMigrationCopy;
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
      "merchantpay.sharding.migration-copy-enabled"
    },
    havingValue = "true")
public final class ShardMigrationCopyController {
  public record CopyRequest(
      @NotNull @Min(1) Long expectedVersion, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final ShardMigrationCopy copies;

  public ShardMigrationCopyController(Access access, ShardMigrationCopy copies) {
    this.access = access;
    this.copies = copies;
  }

  private Actor actor() {
    var caller = access.caller();
    return new Actor(caller.actor(), caller.merchantId(), caller.roles());
  }

  @GetMapping("/{id}/copy")
  ShardMigrationCopy.Copy read(@PathVariable String id) {
    return copies.read(id, actor());
  }

  @PostMapping("/{id}/copy")
  ShardMigrationCopy.Copy copy(@PathVariable String id, @Valid @RequestBody CopyRequest input) {
    return copies.copy(
        id, input.expectedVersion(), input.reason(), input.writersStopped(), actor());
  }

  @PostMapping("/{id}/copy/discard")
  ShardMigrationCopy.Copy discard(@PathVariable String id, @Valid @RequestBody CopyRequest input) {
    return copies.discard(
        id, input.expectedVersion(), input.reason(), input.writersStopped(), actor());
  }
}
