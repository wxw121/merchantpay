package dev.merchantpay.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/security")
public class ConsoleSecurityController {
  public record Confirmation(@NotBlank String method, @NotBlank String path, String body) {}

  private final ConsoleSecurity service;

  public ConsoleSecurityController(ConsoleSecurity service) {
    this.service = service;
  }

  @GetMapping("/status")
  Map<String, Object> status() {
    return service.status();
  }

  @PostMapping("/confirmations")
  Map<String, String> confirm(@Valid @RequestBody Confirmation input, HttpServletRequest request) {
    return service.issue(input.method(), input.path(), input.body(), request);
  }

  @GetMapping("/sessions")
  List<Map<String, Object>> sessions(HttpServletRequest request) {
    return service.sessions(request);
  }

  @PostMapping("/sessions/{id}/revoke")
  void revoke(@PathVariable String id, HttpServletRequest request) {
    service.revoke(id, request);
  }

  @GetMapping("/rotation-reminders")
  List<Map<String, Object>> reminders(@RequestParam String merchantId) {
    return service.rotationReminders(merchantId);
  }
}
