package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.history.HistoryArchiveService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/history/payments")
@ConditionalOnProperty(
    name = {"merchantpay.sharding.enabled", "merchantpay.history.archive-enabled"},
    havingValue = "true")
public final class HistoryArchiveController {
  public record ArchiveRequest(
      @NotBlank String merchantId, @NotBlank String reason, boolean writersStopped) {}

  public record LocatorBackfillRequest(
      @NotBlank String merchantId, @NotBlank String reason, boolean writersStopped) {}

  private final Access access;
  private final HistoryArchiveService history;

  public HistoryArchiveController(Access access, HistoryArchiveService history) {
    this.access = access;
    this.history = history;
  }

  private Actor actor() {
    var caller = access.caller();
    return new Actor(caller.actor(), caller.merchantId(), caller.roles());
  }

  @GetMapping
  HistoryArchiveService.Page list(
      @RequestParam String merchantId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String until,
      @RequestParam(required = false) String cursor) {
    return history.list(merchantId, from, until, cursor, actor());
  }

  @GetMapping("/{id}")
  HistoryArchiveService.Snapshot read(@PathVariable String id, @RequestParam String merchantId) {
    return history.read(merchantId, id, actor());
  }

  @GetMapping("/by-business/{orderNo}")
  HistoryArchiveService.Snapshot byBusiness(
      @PathVariable String orderNo, @RequestParam String merchantId) {
    return history.readByBusinessKey(merchantId, orderNo, actor());
  }

  @GetMapping("/{id}/facts/{kind}/{factId}")
  dev.merchantpay.platform.history.HistoryParquetCodec.Fact fact(
      @PathVariable String id,
      @PathVariable String kind,
      @PathVariable String factId,
      @RequestParam String merchantId) {
    return history.readFact(merchantId, id, kind, factId, actor());
  }

  @PostMapping("/{id}/archive")
  HistoryArchiveService.Archive archive(
      @PathVariable String id, @Valid @RequestBody ArchiveRequest input) {
    return history.archive(input.merchantId(), id, input.reason(), input.writersStopped(), actor());
  }

  @PostMapping("/{id}/archives/{archiveId}/event-locators")
  HistoryArchiveService.Archive backfillEventLocators(
      @PathVariable String id,
      @PathVariable String archiveId,
      @Valid @RequestBody LocatorBackfillRequest input) {
    return history.backfillEventLocators(
        input.merchantId(), id, archiveId, input.reason(), input.writersStopped(), actor());
  }
}
