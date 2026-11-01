package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.control.ChannelControl;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.refund.RefundService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

class V2AlipayEvidenceTest extends FinancialFixture {
  AlipaySandbox sandbox;
  Clock clock;
  String privateKey, paymentId;

  @BeforeEach
  void signing() throws Exception {
    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
        schemaConnection,
        new org.springframework.core.io.ClassPathResource(
            "db/migration/V20__v2_channel_controls.sql"));
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    sandbox =
        new AlipaySandbox(
            jdbc,
            JsonMapper.builder().build(),
            clock,
            true,
            "https://openapi.alipaydev.com/gateway.do",
            "2026000000000000",
            privateKey,
            publicKey,
            "2088000000000000",
            "https://example.invalid/api/channel/alipay/callback");
    paymentId = createPayment("alipay", "1000").id();
    jdbc.update("UPDATE payments SET channel='ALIPAY_SANDBOX' WHERE id=?", paymentId);
  }

  @AfterEach
  void release() {
    if (sandbox != null) sandbox.close();
  }

  @Test
  void confirmedPendingOrderRecoversMissingQrWithTheOriginalBusinessKey() throws Exception {
    var client = mock(AlipayClient.class);
    var query = new com.alipay.api.response.AlipayTradeQueryResponse();
    query.setBody(
        envelope(
            "alipay_trade_query_response",
            "{\"code\":\"10000\",\"out_trade_no\":\""
                + paymentId
                + "\",\"trade_status\":\"WAIT_BUYER_PAY\",\"total_amount\":\"10.00\",\"trade_no\":\"sandbox-reference\"}"));
    var precreate = new com.alipay.api.response.AlipayTradePrecreateResponse();
    precreate.setBody(
        envelope(
            "alipay_trade_precreate_response",
            "{\"code\":\"10000\",\"out_trade_no\":\""
                + paymentId
                + "\",\"qr_code\":\"https://qr.alipay.com/sandbox-test\"}"));
    when(client.execute(any(AlipayRequest.class))).thenReturn(query, precreate);
    ReflectionTestUtils.setField(sandbox, "client", client);
    assertThat(sandbox.query("PAYMENT", paymentId).status()).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT qr_code FROM sandbox_checkouts WHERE payment_id=?",
                String.class,
                paymentId))
        .isEqualTo("https://qr.alipay.com/sandbox-test");
    var requests = org.mockito.ArgumentCaptor.forClass(AlipayRequest.class);
    verify(client, times(2)).execute(requests.capture());
    String content =
        ((com.alipay.api.request.AlipayTradePrecreateRequest) requests.getAllValues().get(1))
            .getBizContent();
    assertThat(content).contains(paymentId).contains("10.00");
  }

  private String envelope(String key, String body) throws Exception {
    return "{\""
        + key
        + "\":"
        + body
        + ",\"sign\":\""
        + AlipaySignature.rsaSign(body, privateKey, "UTF-8", "RSA2")
        + "\"}";
  }

  Map<String, String> callback(String amount) throws Exception {
    return callback(amount, "TRADE_SUCCESS");
  }

  Map<String, String> callback(String amount, String status) throws Exception {
    var values = new TreeMap<String, String>();
    values.put("app_id", "2026000000000000");
    values.put("seller_id", "2088000000000000");
    values.put("out_trade_no", paymentId);
    values.put("trade_no", "sandbox-reference");
    values.put("trade_status", status);
    values.put("total_amount", amount);
    String signature =
        AlipaySignature.rsaSign(
            AlipaySignature.getSignContent(values), privateKey, "UTF-8", "RSA2");
    values.put("sign_type", "RSA2");
    values.put("sign", signature);
    return values;
  }

  private RefundService.RefundView pendingRefund(String amount) {
    payments.applyResult(
        paymentId, new ChannelResult.Success("ALIPAY_SANDBOX", "sandbox-reference", 1000, "CNY"));
    return refunds.create(
        "merchant-a", paymentId, new RefundService.CreateRefund("original-refund", amount));
  }

  private AlipayResponse refundQuery(String id, String status, String amount) throws Exception {
    var body =
        new LinkedHashMap<String, String>(
            Map.of(
                "code",
                "10000",
                "out_trade_no",
                paymentId,
                "out_request_no",
                id,
                "trade_no",
                "sandbox-reference"));
    if (status != null) body.put("refund_status", status);
    if (amount != null) body.put("refund_amount", amount);
    var response = new com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse();
    response.setBody(
        envelope(
            "alipay_trade_fastpay_refund_query_response",
            JsonMapper.builder().build().writeValueAsString(body)));
    return response;
  }

  @Test
  void preparedRefundNotReceivedByProviderResubmitsOriginalIdentityAndPostsOnce() throws Exception {
    var refund = pendingRefund("400");
    var client = mock(AlipayClient.class);
    var accepted = new com.alipay.api.response.AlipayTradeRefundResponse();
    accepted.setBody(envelope("alipay_trade_refund_response", "{\"code\":\"10000\"}"));
    when(client.execute(any(AlipayRequest.class)))
        .thenReturn(
            refundQuery(refund.id(), null, null),
            accepted,
            refundQuery(refund.id(), "REFUND_SUCCESS", "4.00"));
    ReflectionTestUtils.setField(sandbox, "client", client);
    jdbc.update(
        "INSERT INTO channel_attempts(id,aggregate_id,payment_id,channel,operation,request_key,status,created_at,updated_at) VALUES (?,?,?,'ALIPAY_SANDBOX','CHANNEL_REFUND',?,'PREPARED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        UUID.randomUUID().toString(),
        refund.id(),
        paymentId,
        "CHANNEL_REFUND:" + refund.id());
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var metrics = new SimpleMeterRegistry();
    var mockApi = mock(ChannelApi.class);
    try (var batches =
        new ChannelResultBatcher(tx.getTransactionManager(), metrics, 1, Duration.ZERO)) {
      var gateway =
          new ChannelGateway(
              mockApi,
              payments,
              refunds,
              jdbc,
              JsonMapper.builder().build(),
              "unused",
              clock,
              tx,
              batches,
              metrics);
      gateway.setTransport(
          new ChannelTransport(mockApi, sandbox, new ChannelControl(jdbc, tx, clock, false, true)));
      var task =
          new TaskQueue.Task(
              "task",
              "channel-refund:" + refund.id(),
              "CHANNEL_REFUND",
              refund.id(),
              "merchant-a",
              2,
              "lease");
      gateway.execute(task);
      assertThat(refunds.get("merchant-a", refund.id()).status()).isEqualTo("SUCCEEDED");
      assertThat(payments.get("merchant-a", paymentId).reservedMinor()).isEqualTo("0");
      assertThat(payments.get("merchant-a", paymentId).refundedMinor()).isEqualTo("400");
      long journals = count("ledger_journals");
      gateway.execute(task);
      assertThat(count("ledger_journals")).isEqualTo(journals);
    }
    var calls = org.mockito.ArgumentCaptor.forClass(AlipayRequest.class);
    verify(client, times(3)).execute(calls.capture());
    var submission = (com.alipay.api.request.AlipayTradeRefundRequest) calls.getAllValues().get(1);
    var body = JsonMapper.builder().build().readTree(submission.getBizContent());
    assertThat(body.get("out_trade_no").asString()).isEqualTo(paymentId);
    assertThat(body.get("out_request_no").asString()).isEqualTo(refund.id());
    assertThat(body.get("refund_amount").asString()).isEqualTo("4.00");
    verifyNoInteractions(mockApi);
  }

  @Test
  void unreceivedRefundWaitsTenSecondsAfterAnAmbiguousSubmission() throws Exception {
    var refund = pendingRefund("400");
    var client = mock(AlipayClient.class);
    when(client.execute(any(AlipayRequest.class)))
        .thenThrow(new AlipayApiException("timeout"))
        .thenReturn(refundQuery(refund.id(), null, "0.00"), refundQuery(refund.id(), null, "0.00"));
    ReflectionTestUtils.setField(sandbox, "client", client);
    var input =
        new ChannelApi.Request(
            refund.id(),
            paymentId,
            "merchant-a",
            refund.merchantRefundNo(),
            "400",
            "CNY",
            refund.createdAt());
    assertThatThrownBy(() -> sandbox.refund(input)).isInstanceOf(RestClientException.class);
    assertThat(sandbox.query("REFUND", refund.id()).status()).isEqualTo("PENDING");
    sandbox.refund(input);
    verify(client, times(2)).execute(any(AlipayRequest.class));
    jdbc.update(
        "UPDATE alipay_evidence SET created_at=? WHERE aggregate_id=? AND operation='REFUND_SUBMIT'",
        Timestamp.from(clock.instant().minusSeconds(11)),
        refund.id());
    assertThat(sandbox.query("REFUND", refund.id()).status()).isEqualTo("NOT_FOUND");
    assertThat(payments.get("merchant-a", paymentId).reservedMinor()).isEqualTo("400");
  }

  @Test
  void explicitProcessingOrUnknownRefundStatusNeverAuthorizesAnotherSubmission() throws Exception {
    var refund = pendingRefund("400");
    var client = mock(AlipayClient.class);
    when(client.execute(any(AlipayRequest.class)))
        .thenReturn(
            refundQuery(refund.id(), "REFUND_PROCESSING", null),
            refundQuery(refund.id(), "UNRECOGNIZED", null),
            refundQuery(refund.id(), "REFUND_SUCCESS", "3.99"));
    ReflectionTestUtils.setField(sandbox, "client", client);
    assertThat(sandbox.query("REFUND", refund.id()).status()).isEqualTo("PENDING");
    assertThat(sandbox.query("REFUND", refund.id()).status()).isEqualTo("UNKNOWN");
    assertThatThrownBy(() -> sandbox.query("REFUND", refund.id())).isInstanceOf(ApiException.class);
    assertThat(payments.get("merchant-a", paymentId).reservedMinor()).isEqualTo("400");
  }

  @Test
  void unreceivedResponseWithAnotherRefundIdentityIsRejected() throws Exception {
    var refund = pendingRefund("400");
    var client = mock(AlipayClient.class);
    when(client.execute(any(AlipayRequest.class)))
        .thenReturn(refundQuery("another-refund", null, null));
    ReflectionTestUtils.setField(sandbox, "client", client);
    assertThatThrownBy(() -> sandbox.query("REFUND", refund.id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("业务标识不一致");
    assertThat(payments.get("merchant-a", paymentId).reservedMinor()).isEqualTo("400");
  }

  @Test
  void fullRefundClosedNotificationIsAnIdempotentChargeObservation() throws Exception {
    var refund = pendingRefund("1000");
    refunds.applyResult(
        refund.id(),
        new ChannelResult.Success(
            "ALIPAY_SANDBOX", "sandbox-reference:" + refund.id(), 1000, "CNY"));
    long journals = count("ledger_journals");
    var receipt = sandbox.callback(callback("10.00", "TRADE_CLOSED"));
    assertThat(receipt.status()).isEqualTo("SUCCEEDED");
    payments.applyObservation(
        paymentId,
        new ChannelResult.Success(receipt.channel(), receipt.reference(), 1000, "CNY"),
        new dev.merchantpay.platform.common.ReceiptEvidence(
            "PAYMENT",
            receipt.status(),
            receipt.channel(),
            receipt.amountMinor(),
            receipt.feeMinor(),
            receipt.currency(),
            receipt.reference()));
    assertThat(payments.get("merchant-a", paymentId).status()).isEqualTo("SUCCEEDED");
    assertThat(payments.get("merchant-a", paymentId).refundedMinor()).isEqualTo("1000");
    assertThat(count("ledger_journals")).isEqualTo(journals);
  }

  @Test
  void closedNotificationBeforeRefundConfirmationDoesNotReleaseItsReservation() throws Exception {
    var refund = pendingRefund("1000");
    var receipt = sandbox.callback(callback("10.00", "TRADE_CLOSED"));
    assertThat(receipt.status()).isEqualTo("SUCCEEDED");
    payments.applyResult(
        paymentId, new ChannelResult.Success(receipt.channel(), receipt.reference(), 1000, "CNY"));
    assertThat(refunds.get("merchant-a", refund.id()).status()).isEqualTo("PROCESSING");
    assertThat(payments.get("merchant-a", paymentId).reservedMinor()).isEqualTo("1000");
  }

  @Test
  void unpaidExpiryStillClosesThePayment() throws Exception {
    assertThat(sandbox.callback(callback("10.00", "TRADE_CLOSED")).status()).isEqualTo("CLOSED");
  }

  @Test
  void closedTradeQueryKeepsTheSuccessfulChargeAndRejectsAnotherProviderReference()
      throws Exception {
    pendingRefund("1000");
    var client = mock(AlipayClient.class);
    var query = new com.alipay.api.response.AlipayTradeQueryResponse();
    query.setBody(
        envelope(
            "alipay_trade_query_response",
            "{\"code\":\"10000\",\"out_trade_no\":\""
                + paymentId
                + "\",\"trade_status\":\"TRADE_CLOSED\",\"total_amount\":\"10.00\",\"trade_no\":\"sandbox-reference\"}"));
    when(client.execute(any(AlipayRequest.class))).thenReturn(query);
    ReflectionTestUtils.setField(sandbox, "client", client);
    assertThat(sandbox.query("PAYMENT", paymentId).status()).isEqualTo("SUCCEEDED");
    var values = callback("10.00", "TRADE_CLOSED");
    values.remove("sign");
    values.remove("sign_type");
    values.put("trade_no", "another-reference");
    String signature =
        AlipaySignature.rsaSign(
            AlipaySignature.getSignContent(values), privateKey, "UTF-8", "RSA2");
    values.put("sign_type", "RSA2");
    values.put("sign", signature);
    assertThatThrownBy(() -> sandbox.callback(values))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("流水与原支付不一致");
    assertThat(payments.get("merchant-a", paymentId).reservedMinor()).isEqualTo("1000");
  }

  @Test
  void authenticCallbackHasExactOriginalIdentityAndAmounts() throws Exception {
    var receipt = sandbox.callback(callback("10.00"));
    assertThat(receipt.aggregateId()).isEqualTo(paymentId);
    assertThat(receipt.status()).isEqualTo("SUCCEEDED");
    assertThat(receipt.amountMinor()).isEqualTo("1000");
    assertThat(payments.get("merchant-a", paymentId).status()).isEqualTo("PROCESSING");
  }

  @Test
  void alteredSignatureAndSignedWrongAmountAreRejected() throws Exception {
    var altered = callback("10.00");
    altered.put("trade_no", "tampered");
    assertThatThrownBy(() -> sandbox.callback(altered)).isInstanceOf(ApiException.class);
    var wrong = callback("9.99");
    assertThatThrownBy(() -> sandbox.callback(wrong)).isInstanceOf(ApiException.class);
  }

  @Test
  void sdkTransportFailureRemainsUnconfirmedAndNeverMarksPaid() throws Exception {
    var client = mock(AlipayClient.class);
    when(client.execute(any(AlipayRequest.class))).thenThrow(new AlipayApiException("timeout"));
    ReflectionTestUtils.setField(sandbox, "client", client);
    assertThatThrownBy(() -> sandbox.query("PAYMENT", paymentId))
        .isInstanceOf(RestClientException.class);
    assertThat(payments.get("merchant-a", paymentId).status()).isEqualTo("PROCESSING");
  }
}
