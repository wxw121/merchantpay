package dev.merchantpay.platform.web;

import dev.merchantpay.platform.channel.InboxService;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.DatabaseRole;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChannelCallbackController {
  private final InboxService inbox;

  public ChannelCallbackController(InboxService inbox) {
    this.inbox = inbox;
  }

  @PostMapping("/api/channel/callback")
  Map<String, String> receive(HttpServletRequest request) throws IOException {
    byte[] bytes = request.getInputStream().readNBytes(65_537);
    if (bytes.length > 65_536) {
      throw new ApiException("BODY_TOO_LARGE", 413, "渠道通知超过 64 KiB");
    }
    DatabaseRole.worker(
        () ->
            inbox.receive(
                request.getHeader("X-Channel-Timestamp"),
                request.getHeader("X-Channel-Signature"),
                new String(bytes, StandardCharsets.UTF_8)));
    return Map.of("status", "RECEIVED");
  }
}
