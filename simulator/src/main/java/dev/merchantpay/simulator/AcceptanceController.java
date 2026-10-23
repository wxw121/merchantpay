package dev.merchantpay.simulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Token protected, bounded pages of independent durable channel truth. */
@RestController
@RequestMapping("/acceptance")
public class AcceptanceController {
  private final ChannelStore store;
  private final byte[] token;

  public AcceptanceController(
      ChannelStore store, @Value("${merchantpay.channel-token}") String token) {
    this.store = store;
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  private void authenticate(String supplied) {
    if (!MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }

  @GetMapping(value = "/profile", produces = "text/plain")
  String profile(@RequestHeader("X-Channel-Token") String token) {
    authenticate(token);
    return store.profile().descriptor();
  }

  @GetMapping(value = "/results", produces = "text/plain")
  String results(
      @RequestHeader("X-Channel-Token") String token,
      @RequestParam String channel,
      @RequestParam String operation,
      @RequestParam String prefix,
      @RequestParam(defaultValue = "") String after,
      @RequestParam(defaultValue = "1000") int limit) {
    authenticate(token);
    return String.join("\n", store.evidence(channel, operation, prefix, after, limit));
  }
}
