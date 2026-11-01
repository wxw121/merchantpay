package dev.merchantpay.platform.channel;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import dev.merchantpay.platform.common.*;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Official RSA2 SDK adapter, restricted to the Alipay sandbox gateway. Never represents real funds.
 */
@Service
public class AlipaySandbox {
  public static final String CHANNEL = "ALIPAY_SANDBOX";
  private final AlipayClient client;
  private final SandboxHttp transport;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final Clock clock;
  private final String appId, seller, publicKey, notifyUrl;

  public AlipaySandbox(
      JdbcTemplate jdbc,
      JsonMapper json,
      Clock clock,
      @Value("${merchantpay.alipay.enabled:false}") boolean enabled,
      @Value("${merchantpay.alipay.gateway:https://openapi.alipaydev.com/gateway.do}")
          String gateway,
      @Value("${merchantpay.alipay.app-id:}") String appId,
      @Value("${merchantpay.alipay.private-key:}") String privateKey,
      @Value("${merchantpay.alipay.public-key:}") String publicKey,
      @Value("${merchantpay.alipay.seller-id:}") String seller,
      @Value("${merchantpay.alipay.notify-url:}") String notifyUrl) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.appId = appId;
    this.publicKey = publicKey;
    this.seller = seller;
    this.notifyUrl = notifyUrl;
    if (!enabled) {
      client = null;
      transport = null;
      return;
    }
    URI target = URI.create(gateway);
    if (!"https".equals(target.getScheme())
        || !Set.of("openapi.alipaydev.com", "openapi-sandbox.dl.alipaydev.com")
            .contains(target.getHost())
        || target.getUserInfo() != null
        || target.getPort() != -1
        || !"/gateway.do".equals(target.getPath())
        || target.getQuery() != null
        || target.getFragment() != null)
      throw new IllegalArgumentException("Only official Alipay sandbox HTTPS gateways are allowed");
    if (!appId.matches("[0-9]{10,32}")
        || !seller.matches("[0-9]{10,32}")
        || privateKey.isBlank()
        || publicKey.isBlank()
        || !notifyUrl.startsWith("https://"))
      throw new IllegalArgumentException(
          "Alipay sandbox app/seller/keys and HTTPS notify URL are required");
    transport = new SandboxHttp(target);
    String certificatePolicy =
        java.security.Security.getProperty("jdk.certpath.disabledAlgorithms");
    try {
      var config = new AlipayConfig();
      config.setServerUrl(gateway);
      config.setAppId(appId);
      config.setPrivateKey(privateKey);
      config.setAlipayPublicKey(publicKey);
      config.setSignType("RSA2");
      config.setCharset("UTF-8");
      config.setFormat("json");
      config.setConnectTimeout(2000);
      config.setReadTimeout(5000);
      config.setCustomizedHttpClient(transport);
      client = new DefaultAlipayClient(config);
    } catch (AlipayApiException invalid) {
      transport.close();
      throw new IllegalArgumentException("Invalid Alipay sandbox signing configuration");
    } finally {
      // SDK class initialization clears this JVM-wide policy. Restore it before accepting traffic.
      if (certificatePolicy != null)
        java.security.Security.setProperty("jdk.certpath.disabledAlgorithms", certificatePolicy);
    }
  }

  public boolean enabled() {
    return client != null;
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    if (transport != null) transport.close();
  }

  private void requireEnabled() {
    if (client == null) throw new ApiException("SANDBOX_NOT_CONFIGURED", 503, "支付宝沙箱未配置，外部接入未验证");
  }

  public static String major(String minor) {
    return BigDecimal.valueOf(MoneyRules.parsePositive(minor), 2).toPlainString();
  }

  public static String minor(String major) {
    if (major == null || !major.matches("[0-9]{1,10}(\\.[0-9]{1,2})?"))
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱金额格式无效");
    long value = new BigDecimal(major).movePointRight(2).longValueExact();
    if (value < 0 || value > MoneyRules.MAX_PAYMENT)
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱金额超出范围");
    return Long.toString(value);
  }

  private JsonNode execute(AlipayRequest<?> request, String id) {
    requireEnabled();
    try {
      AlipayResponse response = client.execute(request);
      String raw = response.getBody();
      if (raw == null || raw.length() > 65536)
        throw new RestClientException("Invalid sandbox response size");
      var outer = json.readTree(raw);
      // The SDK verifies the original signed response bytes. Unsigned errors remain uncertain.
      if (!outer.hasNonNull("sign") || outer.get("sign").asString().isBlank())
        throw new RestClientException("Unsigned sandbox response; query original business key");
      var body = outer.get(request.getApiMethodName().replace('.', '_') + "_response");
      if (body == null) throw new RestClientException("Invalid sandbox response envelope");
      jdbc.update(
          "INSERT INTO alipay_evidence(id,aggregate_id,operation,body,sha256,created_at) VALUES (?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          id,
          request
              .getApiMethodName()
              .substring(0, Math.min(24, request.getApiMethodName().length())),
          raw,
          Signatures.sha256(raw),
          Timestamp.from(clock.instant()));
      return body;
    } catch (AlipayApiException invalid) {
      throw new RestClientException(
          "Sandbox response unconfirmed; query the original business key");
    }
  }

  private static String text(JsonNode row, String key) {
    return row.hasNonNull(key) ? row.get(key).asString() : "";
  }

  private ChannelApi.Receipt receipt(
      String id, String operation, String status, String reference, String amount) {
    return new ChannelApi.Receipt(id, operation, CHANNEL, status, reference, amount, "CNY", "0");
  }

  private static void identity(JsonNode response, String payment, String refund) {
    if (!payment.equals(text(response, "out_trade_no"))
        || refund != null && !refund.equals(text(response, "out_request_no")))
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱回执业务标识不一致");
  }

  public ChannelApi.Receipt pay(ChannelApi.Request input) {
    if (!input.aggregateId().equals(input.paymentId()))
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱支付必须保留原支付标识");
    return precreate(input.paymentId(), input.amountMinor());
  }

  private ChannelApi.Receipt precreate(String id, String amount) {
    var request = new AlipayTradePrecreateRequest();
    request.setBizContent(
        json.writeValueAsString(
            Map.of(
                "out_trade_no",
                id,
                "total_amount",
                major(amount),
                "subject",
                "MerchantPay sandbox payment",
                "timeout_express",
                "15m")));
    request.setNotifyUrl(notifyUrl);
    var row = execute(request, id);
    if (!"10000".equals(text(row, "code"))) return receipt(id, "PAYMENT", "UNKNOWN", "", amount);
    identity(row, id, null);
    String qr = text(row, "qr_code");
    if (qr.isBlank() || qr.length() > 2048 || !qr.startsWith("https://"))
      throw new RestClientException("Invalid sandbox checkout artifact");
    jdbc.update(
        "INSERT INTO sandbox_checkouts(payment_id,qr_code,created_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE qr_code=VALUES(qr_code)",
        id,
        qr,
        Timestamp.from(clock.instant()));
    return receipt(id, "PAYMENT", "PENDING", "", amount);
  }

  private Map<String, Object> original(String id, String operation) {
    if (!Set.of("PAYMENT", "REFUND").contains(operation))
      throw new ApiException("INVALID_OPERATION", 400, "未知沙箱操作");
    var rows =
        jdbc.queryForList(
            "SELECT * FROM "
                + (operation.equals("PAYMENT") ? "payments" : "refunds")
                + " WHERE id=? AND channel=?",
            id,
            CHANNEL);
    if (rows.isEmpty()) throw new ApiException("NOT_FOUND", 404, "沙箱原业务不存在");
    return rows.getFirst();
  }

  public ChannelApi.Receipt query(String operation, String id) {
    var original = original(id, operation);
    String amount = original.get("amount_minor").toString();
    boolean refund = operation.equals("REFUND");
    String payment = refund ? original.get("payment_id").toString() : id;
    AlipayRequest<?> request;
    if (refund) {
      var r = new AlipayTradeFastpayRefundQueryRequest();
      r.setBizContent(
          json.writeValueAsString(Map.of("out_trade_no", payment, "out_request_no", id)));
      request = r;
    } else {
      var r = new AlipayTradeQueryRequest();
      r.setBizContent(json.writeValueAsString(Map.of("out_trade_no", id)));
      request = r;
    }
    var row = execute(request, id);
    if ("ACQ.TRADE_NOT_EXIST".equals(text(row, "sub_code")))
      return receipt(
          id, operation, refund && !refundRetryReady(id) ? "PENDING" : "NOT_FOUND", "", amount);
    if (!"10000".equals(text(row, "code"))) return receipt(id, operation, "UNKNOWN", "", amount);
    if (refund) {
      String status = text(row, "refund_status");
      if (!"REFUND_SUCCESS".equals(status)) {
        optionalIdentity(row, payment, id);
        String reported = text(row, "refund_amount");
        if (!reported.isBlank() && !"0".equals(minor(reported)) && !amount.equals(minor(reported)))
          throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱查询金额与原业务不一致");
        String state =
            status.isBlank()
                ? (refundRetryReady(id) ? "NOT_FOUND" : "PENDING")
                : ("REFUND_PROCESSING".equals(status) ? "PENDING" : "UNKNOWN");
        return receipt(id, operation, state, "", amount);
      }
    }
    identity(row, payment, refund ? id : null);
    String observed = minor(text(row, refund ? "refund_amount" : "total_amount"));
    if (!amount.equals(observed))
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱查询金额与原业务不一致");
    if (refund) {
      if (text(row, "trade_no").isBlank())
        throw new RestClientException("Missing verified refund reference");
      return receipt(id, operation, "SUCCEEDED", text(row, "trade_no") + ":" + id, observed);
    }
    String state = paymentState(text(row, "trade_status"), original, text(row, "trade_no"));
    if ("WAIT_BUYER_PAY".equals(text(row, "trade_status"))
        && jdbc.queryForList("SELECT payment_id FROM sandbox_checkouts WHERE payment_id=?", id)
            .isEmpty()) {
      // Accepted precreate can lose its local QR at any crash/timeout boundary; retry the SAME
      // order.
      precreate(id, amount);
    }
    return receipt(id, operation, state, text(row, "trade_no"), observed);
  }

  public ChannelApi.Receipt refund(ChannelApi.Request input) {
    requireEnabled();
    var original = original(input.aggregateId(), "REFUND");
    if (!input.paymentId().equals(original.get("payment_id"))
        || !input.merchantId().equals(original.get("merchant_id"))
        || !input.amountMinor().equals(original.get("amount_minor").toString())
        || !"CNY".equals(input.currency()))
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "退款必须保留原业务标识和金额");
    if (!refundRetryReady(input.aggregateId()))
      return receipt(input.aggregateId(), "REFUND", "PENDING", "", input.amountMinor());
    var request = new AlipayTradeRefundRequest();
    request.setBizContent(
        json.writeValueAsString(
            Map.of(
                "out_trade_no",
                input.paymentId(),
                "out_request_no",
                input.aggregateId(),
                "refund_amount",
                major(input.amountMinor()))));
    // Persist before I/O: a timeout/crash must not erase the ten-second retry boundary.
    String intent = request.getBizContent();
    jdbc.update(
        "INSERT INTO alipay_evidence(id,aggregate_id,operation,body,sha256,created_at) VALUES (?,?,'REFUND_SUBMIT',?,?,?)",
        UUID.randomUUID().toString(),
        input.aggregateId(),
        intent,
        Signatures.sha256(intent),
        Timestamp.from(clock.instant()));
    var row = execute(request, input.aggregateId());
    if (!"10000".equals(text(row, "code")))
      return receipt(input.aggregateId(), "REFUND", "UNKNOWN", "", input.amountMinor());
    // Query the stable refund identity; do not infer success from fund_change=N alone.
    return query("REFUND", input.aggregateId());
  }

  public ChannelApi.Receipt close(String id) {
    original(id, "PAYMENT");
    var request = new AlipayTradeCloseRequest();
    request.setBizContent(json.writeValueAsString(Map.of("out_trade_no", id)));
    execute(request, id);
    return query("PAYMENT", id);
  }

  public ChannelApi.Receipt callback(Map<String, String> values) {
    requireEnabled();
    try {
      if (!"RSA2".equals(values.get("sign_type"))
          || !appId.equals(values.get("app_id"))
          || !seller.equals(values.get("seller_id"))
          || !AlipaySignature.rsaCheckV1(new HashMap<>(values), publicKey, "UTF-8", "RSA2"))
        throw new ApiException("INVALID_CHANNEL_SIGNATURE", 401, "支付宝沙箱回调验签失败");
    } catch (AlipayApiException invalid) {
      throw new ApiException("INVALID_CHANNEL_SIGNATURE", 401, "支付宝沙箱回调验签失败");
    }
    String id = values.get("out_trade_no");
    var row = original(id, "PAYMENT");
    String amount = minor(values.get("total_amount"));
    if (!row.get("amount_minor").toString().equals(amount))
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱回调金额不一致");
    String tradeStatus = Objects.toString(values.get("trade_status"), "");
    if (!Set.of("TRADE_SUCCESS", "TRADE_FINISHED", "TRADE_CLOSED", "WAIT_BUYER_PAY")
        .contains(tradeStatus)) throw new ApiException("INVALID_CHANNEL_RECEIPT", 400, "未知沙箱回调状态");
    String reference = values.get("trade_no");
    if (reference == null || reference.isBlank() || reference.length() > 128)
      throw new ApiException("INVALID_CHANNEL_RECEIPT", 400, "沙箱流水标识无效");
    return receipt(id, "PAYMENT", paymentState(tradeStatus, row, reference), reference, amount);
  }

  private boolean refundRetryReady(String id) {
    Timestamp last =
        jdbc.queryForObject(
            "SELECT MAX(created_at) FROM alipay_evidence WHERE aggregate_id=? AND operation='REFUND_SUBMIT'",
            Timestamp.class,
            id);
    return last == null || !clock.instant().isBefore(last.toInstant().plusSeconds(10));
  }

  private static void optionalIdentity(JsonNode response, String payment, String refund) {
    if (response.hasNonNull("out_trade_no") && !payment.equals(text(response, "out_trade_no"))
        || response.hasNonNull("out_request_no")
            && !refund.equals(text(response, "out_request_no")))
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱回执业务标识不一致");
  }

  private static String paymentState(
      String providerStatus, Map<String, Object> original, String reference) {
    if ("TRADE_CLOSED".equals(providerStatus) && "SUCCEEDED".equals(original.get("status"))) {
      if (!Objects.equals(original.get("channel_reference"), reference))
        throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "沙箱关闭通知流水与原支付不一致");
      // Full refunds close the provider trade, not the historical successful charge.
      // Each refund still reconciles through its own durable task, original ID and amount.
      return "SUCCEEDED";
    }
    return switch (providerStatus) {
      case "TRADE_SUCCESS", "TRADE_FINISHED" -> "SUCCEEDED";
      case "TRADE_CLOSED" -> "CLOSED";
      default -> "PENDING";
    };
  }
}
