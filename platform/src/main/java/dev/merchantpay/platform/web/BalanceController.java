package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.finance.BalanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/balances")
public class BalanceController {
  public record Input(@NotBlank String merchantId, @NotBlank String reason) {}

  private final Access access;
  private final BalanceService service;

  public BalanceController(Access access, BalanceService service) {
    this.access = access;
    this.service = service;
  }

  private Actor actor() {
    var c = access.caller();
    return new Actor(c.actor(), c.merchantId(), c.roles());
  }

  @GetMapping
  List<Map<String, Object>> list(@RequestParam String merchantId) {
    return service.list(merchantId, actor());
  }

  @GetMapping("/holds")
  List<Map<String, Object>> holds(@RequestParam String merchantId) {
    return service.holds(merchantId, actor());
  }

  @PostMapping("/checkpoints")
  Map<String, Object> checkpoint(@Valid @RequestBody Input input) {
    return service.checkpoint(input.merchantId(), input.reason(), actor());
  }
}
