package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.finance.ShardAdjustmentService;
import dev.merchantpay.platform.finance.ShardBalanceService;
import dev.merchantpay.platform.finance.ShardSettlementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Explicit four-source finance API; the legacy finance endpoints keep their core-only gate. */
@RestController
@ConditionalOnProperty(name = "merchantpay.sharding.enabled", havingValue = "true")
@RequestMapping("/api/console/finance/sharded")
public final class ShardFinanceController {
  public record Prepare(
      @NotBlank String merchantId,
      @NotNull Instant cutoff,
      @NotBlank String reason,
      boolean writersStopped) {}

  public record Rebuild(
      @NotNull @Min(1) Long expectedVersion,
      @NotNull Instant cutoff,
      @NotBlank String reason,
      boolean writersStopped) {}

  public record Review(
      @NotNull @Min(1) Long expectedVersion, @NotNull Boolean approve, @NotBlank String reason) {}

  public record Action(@NotNull @Min(1) Long expectedVersion, @NotBlank String reason) {}

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

  public record BalanceCheck(
      @NotBlank String merchantId, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final ShardSettlementService settlements;
  private final ShardAdjustmentService adjustments;
  private final ShardBalanceService balances;

  public ShardFinanceController(
      Access access,
      ShardSettlementService settlements,
      ShardAdjustmentService adjustments,
      ShardBalanceService balances) {
    this.access = access;
    this.settlements = settlements;
    this.adjustments = adjustments;
    this.balances = balances;
  }

  private Actor actor() {
    var caller = access.caller();
    return new Actor(caller.actor(), caller.merchantId(), caller.roles());
  }

  @GetMapping("/balances")
  List<Map<String, Object>> balances(@RequestParam String merchantId) {
    return balances.list(merchantId, actor());
  }

  @GetMapping("/balances/holds")
  List<Map<String, Object>> balanceHolds(@RequestParam String merchantId) {
    return balances.holds(merchantId, actor());
  }

  @PostMapping("/balances/checkpoints")
  Map<String, Object> balanceCheckpoint(@Valid @RequestBody BalanceCheck input) {
    return balances.checkpoint(input.merchantId(), input.reason(), input.writersStopped(), actor());
  }

  @GetMapping("/settlements")
  List<Map<String, Object>> list(@RequestParam(required = false) String merchantId) {
    return settlements.list(merchantId, actor());
  }

  @GetMapping("/settlements/{id}")
  Map<String, Object> detail(@PathVariable String id) {
    return settlements.detail(id, actor());
  }

  @PostMapping("/settlements")
  Map<String, Object> create(@Valid @RequestBody Prepare input) {
    return settlements.create(
        input.merchantId(), input.cutoff(), input.reason(), input.writersStopped(), actor());
  }

  @PostMapping("/settlements/{id}/rebuild")
  Map<String, Object> rebuild(@PathVariable String id, @Valid @RequestBody Rebuild input) {
    return settlements.rebuild(
        id,
        input.expectedVersion(),
        input.cutoff(),
        input.reason(),
        input.writersStopped(),
        actor());
  }

  @PostMapping("/settlements/{id}/review")
  Map<String, Object> review(@PathVariable String id, @Valid @RequestBody Review input) {
    return settlements.review(
        id, input.expectedVersion(), input.approve(), input.reason(), actor());
  }

  @PostMapping("/settlements/{id}/cancel")
  Map<String, Object> cancel(@PathVariable String id, @Valid @RequestBody Action input) {
    return settlements.cancel(id, input.expectedVersion(), input.reason(), actor());
  }

  @PostMapping("/settlements/{id}/execute")
  Map<String, Object> execute(@PathVariable String id, @Valid @RequestBody Action input) {
    return settlements.execute(id, input.expectedVersion(), input.reason(), actor());
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
  Map<String, Object> openCase(@Valid @RequestBody OpenCase input) {
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

  @GetMapping("/adjustments")
  List<Map<String, Object>> adjustments(@RequestParam(required = false) String merchantId) {
    return adjustments.proposals(merchantId, actor());
  }

  @GetMapping("/adjustments/{id}")
  Map<String, Object> adjustment(@PathVariable String id) {
    return adjustments.detail(id, actor());
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
}
