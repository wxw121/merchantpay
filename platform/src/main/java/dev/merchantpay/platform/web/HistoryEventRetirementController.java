package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.history.HistoryEventRetirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/history/payments")
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.history.archive-enabled",
      "merchantpay.history.retire-enabled"
    },
    havingValue = "true")
public final class HistoryEventRetirementController {
  public record Request(
      @NotBlank String merchantId, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final HistoryEventRetirement retirement;

  public HistoryEventRetirementController(Access access, HistoryEventRetirement retirement) {
    this.access = access;
    this.retirement = retirement;
  }

  @PostMapping("/{id}/events/{eventId}/retire")
  HistoryEventRetirement.Result retire(
      @PathVariable String id, @PathVariable String eventId, @Valid @RequestBody Request request) {
    var caller = access.caller();
    return retirement.retire(
        request.merchantId(),
        id,
        eventId,
        request.reason(),
        request.writersStopped(),
        new Actor(caller.actor(), caller.merchantId(), caller.roles()));
  }
}
