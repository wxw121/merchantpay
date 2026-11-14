package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.finance.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/finance")
public class FinanceController {
  public record OpenCase(@NotBlank String issueId) {}

  public record Investigation(
      @NotNull @Min(1) Long expectedVersion,
      @NotBlank String assignee,
      String paymentId,
      @NotBlank String evidence) {}

  public record Proposal(
      @NotNull @Min(1) Long expectedVersion,
      @NotBlank String direction,
      @NotBlank String amountMinor,
      @NotBlank String reason) {}

  public record Review(
      @NotNull @Min(1) Long expectedVersion, @NotNull Boolean approve, @NotBlank String reason) {}

  public record Action(@NotNull @Min(1) Long expectedVersion, @NotBlank String reason) {}

  public record NewSettlement(
      @NotBlank String merchantId, @NotNull Instant cutoff, @NotBlank String reason) {}

  public record Rebuild(
      @NotNull @Min(1) Long expectedVersion, @NotNull Instant cutoff, @NotBlank String reason) {}

  private final Access access;
  private final AdjustmentService adjustments;
  private final SettlementService settlements;

  public FinanceController(
      Access access, AdjustmentService adjustments, SettlementService settlements) {
    this.access = access;
    this.adjustments = adjustments;
    this.settlements = settlements;
  }

  private Actor actor() {
    var c = access.caller();
    return new Actor(c.actor(), c.merchantId(), c.roles());
  }

  @GetMapping("/cases")
  List<Map<String, Object>> cases(@RequestParam(required = false) String merchantId) {
    return adjustments.cases(merchantId, actor());
  }

  @GetMapping("/cases/{id}")
  Map<String, Object> caseDetail(@PathVariable String id) {
    return adjustments.caseDetail(id, actor());
  }

  @PostMapping("/cases")
  Map<String, Object> open(@Valid @RequestBody OpenCase input) {
    return adjustments.openCase(input.issueId(), actor());
  }

  @PostMapping("/cases/{id}/investigate")
  Map<String, Object> investigate(
      @PathVariable String id, @Valid @RequestBody Investigation input) {
    return adjustments.investigate(
        id,
        input.expectedVersion(),
        input.assignee(),
        input.paymentId(),
        input.evidence(),
        actor());
  }

  @PostMapping("/cases/{id}/proposals")
  Map<String, Object> propose(@PathVariable String id, @Valid @RequestBody Proposal input) {
    return adjustments.propose(
        id,
        input.expectedVersion(),
        input.direction(),
        input.amountMinor(),
        input.reason(),
        actor());
  }

  @GetMapping("/adjustments")
  List<Map<String, Object>> proposals(@RequestParam(required = false) String merchantId) {
    return adjustments.proposals(merchantId, actor());
  }

  @GetMapping("/adjustments/{id}")
  Map<String, Object> adjustment(@PathVariable String id) {
    return adjustments.detail(id, actor());
  }

  @PostMapping("/adjustments/{id}/revise")
  Map<String, Object> revise(@PathVariable String id, @Valid @RequestBody Proposal input) {
    return adjustments.revise(
        id,
        input.expectedVersion(),
        input.direction(),
        input.amountMinor(),
        input.reason(),
        actor());
  }

  @PostMapping("/adjustments/{id}/review")
  Map<String, Object> reviewAdjustment(@PathVariable String id, @Valid @RequestBody Review input) {
    return adjustments.review(
        id, input.expectedVersion(), input.approve(), input.reason(), actor());
  }

  @PostMapping("/adjustments/{id}/execute")
  Map<String, Object> executeAdjustment(@PathVariable String id, @Valid @RequestBody Action input) {
    return adjustments.execute(id, input.expectedVersion(), input.reason(), actor());
  }

  @GetMapping("/settlements")
  List<Map<String, Object>> settlements(@RequestParam(required = false) String merchantId) {
    return settlements.list(merchantId, actor());
  }

  @GetMapping("/settlements/{id}")
  Map<String, Object> settlement(@PathVariable String id) {
    return settlements.detail(id, actor());
  }

  @PostMapping("/settlements")
  Map<String, Object> create(@Valid @RequestBody NewSettlement input) {
    return settlements.create(input.merchantId(), input.cutoff(), input.reason(), actor());
  }

  @PostMapping("/settlements/{id}/rebuild")
  Map<String, Object> rebuild(@PathVariable String id, @Valid @RequestBody Rebuild input) {
    return settlements.rebuild(
        id, input.expectedVersion(), input.cutoff(), input.reason(), actor());
  }

  @PostMapping("/settlements/{id}/review")
  Map<String, Object> reviewSettlement(@PathVariable String id, @Valid @RequestBody Review input) {
    return settlements.review(
        id, input.expectedVersion(), input.approve(), input.reason(), actor());
  }

  @PostMapping("/settlements/{id}/execute")
  Map<String, Object> executeSettlement(@PathVariable String id, @Valid @RequestBody Action input) {
    return settlements.execute(id, input.expectedVersion(), input.reason(), actor());
  }

  @PostMapping("/settlements/{id}/cancel")
  Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody Action input) {
    return settlements.cancel(id, input.expectedVersion(), input.reason(), actor());
  }
}
