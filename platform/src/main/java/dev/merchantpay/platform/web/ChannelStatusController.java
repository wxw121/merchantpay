package dev.merchantpay.platform.web;

import dev.merchantpay.platform.channel.ChannelGateway;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChannelStatusController {
  private final Access access;
  private final ChannelGateway gateway;

  public ChannelStatusController(Access access, ChannelGateway gateway) {
    this.access = access;
    this.gateway = gateway;
  }

  @GetMapping("/api/console/channels")
  List<ChannelGateway.ChannelStatus> channels(@RequestParam(required = false) String merchantId) {
    String scope = access.merchant(merchantId, false);
    return List.of(gateway.health("MOCK_A", scope), gateway.health("MOCK_B", scope));
  }
}
