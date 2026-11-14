package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.sharding.AnalyticsPublicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/analytics/publications")
@ConditionalOnProperty(
    name = {
      "merchantpay.analytics.flink-enabled",
      "merchantpay.analytics.publication-enabled",
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.projection-enabled",
      "merchantpay.read-model-enabled"
    },
    havingValue = "true")
public final class AnalyticsPublicationController {
  public record Request(@NotBlank String payload, @NotBlank String signature) {}

  private final Access access;
  private final AnalyticsPublicationService publications;

  public AnalyticsPublicationController(Access access, AnalyticsPublicationService publications) {
    this.access = access;
    this.publications = publications;
  }

  @PostMapping
  AnalyticsPublicationService.Publication publish(@Valid @RequestBody Request request) {
    var caller = access.caller();
    return publications.publish(
        request.payload(),
        request.signature(),
        new Actor(caller.actor(), caller.merchantId(), caller.roles()));
  }

  @GetMapping("/{generation}")
  AnalyticsPublicationService.Publication current(@PathVariable String generation) {
    access.require("ADMIN", "OPS", "FINANCE", "AUDITOR");
    return publications.current(generation);
  }
}
