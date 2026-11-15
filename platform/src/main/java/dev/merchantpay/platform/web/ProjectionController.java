package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.projection.*;
import dev.merchantpay.platform.sharding.ShardProjectionPromotion;
import dev.merchantpay.platform.sharding.ShardProjectionReadGate;
import dev.merchantpay.platform.sharding.ShardProjectionReadiness;
import dev.merchantpay.platform.sharding.ShardProjectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/read-model")
public class ProjectionController {
  public record Reason(@NotBlank String reason) {}

  public record Promotion(boolean writersStopped, @NotBlank String reason) {}

  private final Access access;
  private final ProjectionService service;
  private final ProjectionPromotion promotion;
  private ShardProjectionReadiness shards;
  private ShardProjectionService shardedProjection;
  private ShardProjectionPromotion shardedPromotion;
  private ShardProjectionReadGate shardedReads;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShards(ShardProjectionReadiness shards) {
    this.shards = shards;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShardedProjection(ShardProjectionService shardedProjection) {
    this.shardedProjection = shardedProjection;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShardedPromotion(ShardProjectionPromotion shardedPromotion) {
    this.shardedPromotion = shardedPromotion;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShardedReads(ShardProjectionReadGate shardedReads) {
    this.shardedReads = shardedReads;
  }

  public ProjectionController(
      Access access, ProjectionService service, ProjectionPromotion promotion) {
    this.access = access;
    this.service = service;
    this.promotion = promotion;
  }

  private Actor actor() {
    var c = access.caller();
    return new Actor(c.actor(), c.merchantId(), c.roles());
  }

  private void coreOnly(boolean operator) {
    if (shards != null) {
      var caller = actor();
      if (operator) EventDelivery.requireOperator(caller);
      else caller.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
      throw new ApiException("SHARDED_READ_MODEL_PENDING", 503, "跨库查询投影尚未完成，请查询权威支付记录");
    }
  }

  @GetMapping("/status")
  Map<String, Object> status(@RequestParam(defaultValue = "false") boolean deep) {
    if (shards != null) {
      var status = new LinkedHashMap<>(shards.status(actor(), deep));
      status.put("operationsEnabled", shardedProjection != null && shardedProjection.operational());
      if (shardedReads != null) {
        try {
          status.put("servingGeneration", shardedReads.generation());
          status.put("globalReady", true);
        } catch (ApiException pending) {
          if (!"SHARDED_READ_MODEL_PENDING".equals(pending.code())) throw pending;
        }
      }
      return status;
    }
    var status = new LinkedHashMap<>(service.status(actor()));
    status.put("deadBatches", service.deadBatches(actor()));
    return status;
  }

  @GetMapping("/errors")
  List<Map<String, Object>> errors(@RequestParam(defaultValue = "") String after) {
    coreOnly(true);
    return service.errors(after, actor());
  }

  @GetMapping("/sources/{physical}/errors")
  List<Map<String, Object>> sourceErrors(
      @PathVariable int physical, @RequestParam(defaultValue = "") String after) {
    var caller = actor();
    EventDelivery.requireOperator(caller);
    if (shardedProjection == null) throw pending();
    return shardedProjection.errors(physical, after, caller);
  }

  @GetMapping("/sources/{physical}/batches/dead")
  List<Map<String, Object>> sourceDeadBatches(@PathVariable int physical) {
    var caller = actor();
    EventDelivery.requireOperator(caller);
    if (shardedProjection == null) throw pending();
    return shardedProjection.deadBatches(physical, caller);
  }

  @PostMapping("/sources/{physical}/errors/{id}/replay")
  Map<String, Object> replaySourceError(
      @PathVariable int physical, @PathVariable String id, @Valid @RequestBody Reason input) {
    var caller = actor();
    EventDelivery.requireOperator(caller);
    if (shardedProjection == null) throw pending();
    return shardedProjection.replay(physical, id, input.reason(), caller);
  }

  @PostMapping("/sources/{physical}/errors/{id}/dismiss")
  void dismissSourceError(
      @PathVariable int physical, @PathVariable String id, @Valid @RequestBody Reason input) {
    var caller = actor();
    EventDelivery.requireOperator(caller);
    if (shardedProjection == null) throw pending();
    shardedProjection.dismiss(physical, id, input.reason(), caller);
  }

  @PostMapping("/sources/{physical}/batches/{id}/retry")
  void retrySourceBatch(
      @PathVariable int physical, @PathVariable String id, @Valid @RequestBody Reason input) {
    var caller = actor();
    EventDelivery.requireOperator(caller);
    if (shardedProjection == null) throw pending();
    shardedProjection.retryBatch(physical, id, input.reason(), caller);
  }

  private static ApiException pending() {
    return new ApiException("SHARDED_READ_MODEL_PENDING", 503, "四库投影操作尚未启用");
  }

  @PostMapping("/rebuild")
  Map<String, String> rebuild(@Valid @RequestBody Reason input) {
    coreOnly(true);
    return Map.of("generation", service.rebuild(input.reason(), actor()));
  }

  @PostMapping("/recover-source-facts")
  Map<String, Integer> recover(@Valid @RequestBody Reason input) {
    coreOnly(true);
    return Map.of("recovered", service.recoverSourceFacts(input.reason(), actor()));
  }

  @PostMapping("/sources/{physical}/recover")
  Map<String, Integer> recoverSource(@PathVariable int physical, @Valid @RequestBody Reason input) {
    var caller = actor();
    EventDelivery.requireOperator(caller);
    if (shardedProjection == null)
      throw new ApiException("SHARDED_READ_MODEL_PENDING", 503, "跨库投影消费尚未启用");
    return Map.of(
        "recovered", shardedProjection.recoverSourceFacts(physical, input.reason(), caller));
  }

  @PostMapping("/generations/{id}/promote")
  Map<String, Object> promote(@PathVariable String id, @Valid @RequestBody Promotion input) {
    if (shards != null) {
      var caller = actor();
      EventDelivery.requireOperator(caller);
      if (shardedPromotion == null)
        throw new ApiException("SHARDED_READ_MODEL_PENDING", 503, "四库投影提升尚未启用");
      return shardedPromotion.promote(id, input.writersStopped(), input.reason(), caller);
    }
    coreOnly(true);
    return promotion.promote(id, input.writersStopped(), input.reason(), actor());
  }

  @PostMapping("/errors/{id}/replay")
  void replay(@PathVariable String id, @Valid @RequestBody Reason input) {
    coreOnly(true);
    service.replay(id, input.reason(), actor());
  }

  @PostMapping("/errors/{id}/dismiss")
  void dismiss(@PathVariable String id, @Valid @RequestBody Reason input) {
    coreOnly(true);
    service.dismiss(id, input.reason(), actor());
  }

  @PostMapping("/batches/{id}/retry")
  void retry(@PathVariable String id, @Valid @RequestBody Reason input) {
    coreOnly(true);
    service.retryBatch(id, input.reason(), actor());
  }

  @GetMapping("/payments")
  Map<String, Object> payments(
      @RequestParam String merchantId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) String generation,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String until) {
    if (shards != null) {
      var caller = actor();
      caller.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
      caller.scope(merchantId, true);
      if (shardedReads == null)
        throw new ApiException("SHARDED_READ_MODEL_PENDING", 503, "四库投影查询尚未启用");
      return service.paymentsAtGeneration(
          merchantId, after, generation, from, until, shardedReads.generation(), caller);
    }
    return service.payments(merchantId, after, generation, from, until, actor());
  }

  @GetMapping("/payments/{id}")
  Map<String, Object> payment(@PathVariable String id, @RequestParam String merchantId) {
    if (shards != null) {
      var caller = actor();
      caller.require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
      caller.scope(merchantId, true);
      if (shardedReads == null)
        throw new ApiException("SHARDED_READ_MODEL_PENDING", 503, "四库投影查询尚未启用");
      return service.paymentAtGeneration(id, merchantId, shardedReads.generation(), caller);
    }
    return service.payment(id, merchantId, actor());
  }
}
