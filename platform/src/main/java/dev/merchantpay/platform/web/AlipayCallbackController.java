package dev.merchantpay.platform.web;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class AlipayCallbackController {
  private final AlipaySandbox sandbox;
  private final InboxService inbox;

  public AlipayCallbackController(AlipaySandbox sandbox, InboxService inbox) {
    this.sandbox = sandbox;
    this.inbox = inbox;
  }

  @PostMapping(
      value = "/api/channel/alipay/callback",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.TEXT_PLAIN_VALUE)
  String receive(HttpServletRequest request) throws IOException {
    byte[] bytes = request.getInputStream().readNBytes(65537);
    if (bytes.length > 65536) throw new ApiException("BODY_TOO_LARGE", 413, "沙箱通知超过64KiB");
    String raw = new String(bytes, StandardCharsets.UTF_8);
    var fields = new LinkedHashMap<String, String>();
    for (String item : raw.split("&")) {
      int equals = item.indexOf('=');
      if (equals < 1) throw new ApiException("INVALID_CHANNEL_RECEIPT", 400, "沙箱表单无效");
      String name = URLDecoder.decode(item.substring(0, equals), StandardCharsets.UTF_8),
          value = URLDecoder.decode(item.substring(equals + 1), StandardCharsets.UTF_8);
      if (fields.putIfAbsent(name, value) != null)
        throw new ApiException("INVALID_CHANNEL_RECEIPT", 400, "沙箱表单存在重复字段");
    }
    DatabaseRole.worker(() -> inbox.receiveVerifiedAlipay(sandbox.callback(fields), raw));
    return "success"; // Only after durable inbox + original signed evidence commit.
  }
}
