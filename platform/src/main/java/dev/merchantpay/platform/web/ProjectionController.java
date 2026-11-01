package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.projection.*;
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

  @GetMapping("/status")
  Map<String, Object> status() {
    var status = new LinkedHashMap<>(service.status(actor()));
    status.put("deadBatches", service.deadBatches(actor()));
    return status;
  }

  @GetMapping("/errors")
  List<Map<String, Object>> errors(@RequestParam(defaultValue = "") String after) {
    return service.errors(after, actor());
  }

  @PostMapping("/rebuild")
  Map<String, String> rebuild(@Valid @RequestBody Reason input) {
    return Map.of("generation", service.rebuild(input.reason(), actor()));
  }

  @PostMapping("/recover-source-facts")
  Map<String, Integer> recover(@Valid @RequestBody Reason input) {
    return Map.of("recovered", service.recoverSourceFacts(input.reason(), actor()));
  }

  @PostMapping("/generations/{id}/promote")
  Map<String, Object> promote(@PathVariable String id, @Valid @RequestBody Promotion input) {
    return promotion.promote(id, input.writersStopped(), input.reason(), actor());
  }

  @PostMapping("/errors/{id}/replay")
  void replay(@PathVariable String id, @Valid @RequestBody Reason input) {
    service.replay(id, input.reason(), actor());
  }

  @PostMapping("/errors/{id}/dismiss")
  void dismiss(@PathVariable String id, @Valid @RequestBody Reason input) {
    service.dismiss(id, input.reason(), actor());
  }

  @PostMapping("/batches/{id}/retry")
  void retry(@PathVariable String id, @Valid @RequestBody Reason input) {
    service.retryBatch(id, input.reason(), actor());
  }

  @GetMapping("/payments")
  Map<String, Object> payments(
      @RequestParam String merchantId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) String generation,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String until) {
    return service.payments(merchantId, after, generation, from, until, actor());
  }

  @GetMapping("/payments/{id}")
  Map<String, Object> payment(@PathVariable String id, @RequestParam String merchantId) {
    return service.payment(id, merchantId, actor());
  }
}
