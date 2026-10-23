package dev.merchantpay.platform.channel;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ChannelGateway {
  private static final class PendingOutcome extends RuntimeException {
    PendingOutcome() {
      super("Channel processing; active query scheduled");
    }
  }

  private final ChannelApi api;
  private final PaymentService payments;
  private final RefundService refunds;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final String token;
  private final Clock clock;
  private final TransactionTemplate evidenceTx;
  private final TransactionTemplate resultTx;

  public ChannelGateway(
      ChannelApi api,
      PaymentService payments,
      RefundService refunds,
      JdbcTemplate jdbc,
      JsonMapper json,
      @Value("${merchantpay.channel-token}") String token,
      Clock clock,
      TransactionTemplate tx) {
    this.api = api;
    this.payments = payments;
    this.refunds = refunds;
    this.jdbc = jdbc;
    this.json = json;
    this.token = token;
    this.clock = clock;
    this.evidenceTx = new TransactionTemplate(tx.getTransactionManager());
    this.resultTx = new TransactionTemplate(tx.getTransactionManager());
    this.evidenceTx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void execute(TaskQueue.Task task) {
    boolean isRefund = task.kind().equals("CHANNEL_REFUND");
    String operation = isRefund ? "REFUND" : "PAYMENT";
    var refund = isRefund ? refunds.get(task.merchantId(), task.aggregateId()) : null;
    var payment =
        payments.get(task.merchantId(), isRefund ? refund.paymentId() : task.aggregateId());
    String state = isRefund ? refund.status() : payment.status();
    if (state.equals("SUCCEEDED") || state.equals("FAILED") || state.equals("CLOSED")) {
      return;
    }
    var request =
        new ChannelApi.Request(
            task.aggregateId(),
            payment.id(),
            task.merchantId(),
            isRefund ? refund.merchantRefundNo() : payment.merchantOrderNo(),
            isRefund ? refund.amountMinor() : payment.amountMinor(),
            "CNY",
            isRefund ? refund.createdAt() : payment.createdAt());
    String attemptKey = task.kind() + ":" + task.aggregateId();
    var now = Timestamp.from(clock.instant());
    int prepared =
        jdbc.update(
            "INSERT IGNORE INTO channel_attempts(id,aggregate_id,payment_id,channel,operation,request_key,status,request_body,created_at,updated_at) VALUES (?,?,?,?,?,?,'PREPARED',?,?,?)",
            UUID.randomUUID().toString(),
            task.aggregateId(),
            payment.id(),
            payment.channel(),
            task.kind(),
            attemptKey,
            json.writeValueAsString(request),
            now,
            now);
    ChannelApi.Receipt receipt = null;
    try {
      // Only a newly committed preparation on the first execution may skip the recovery query.
      // Existing preparation, UNKNOWN outcomes and reclaimed tasks always reconcile first.
      if (prepared == 1
          && task.attempts() == 1
          && state.equals("PROCESSING")
          && !task.kind().equals("CHANNEL_CLOSE")) {
        receipt =
            isRefund
                ? api.refund(payment.channel(), token, request)
                : api.pay(payment.channel(), token, request);
      } else {
        receipt = api.query(payment.channel(), operation, task.aggregateId(), token);
        if (task.kind().equals("CHANNEL_CLOSE") || receipt.status().equals("NOT_FOUND")) {
          retainResponse(receipt, payment.channel(), attemptKey);
          validate(receipt, task.aggregateId(), operation, payment.channel());
        }
        if (task.kind().equals("CHANNEL_CLOSE")) {
          receipt = api.close(payment.channel(), task.aggregateId(), token);
        } else if (receipt.status().equals("NOT_FOUND")) {
          receipt =
              isRefund
                  ? api.refund(payment.channel(), token, request)
                  : api.pay(payment.channel(), token, request);
        }
      }
      retainResponse(receipt, payment.channel(), attemptKey);
      var observed = receipt;
      // Network I/O and raw receipt persistence precede this short business transaction.
      resultTx.executeWithoutResult(
          tx -> {
            validate(observed, task.aggregateId(), operation, payment.channel());
            applyBusiness(observed);
            jdbc.update(
                "UPDATE channel_attempts SET status=?,updated_at=? WHERE channel=? AND request_key=?",
                observed.status(),
                Timestamp.from(clock.instant()),
                payment.channel(),
                attemptKey);
          });
      if (!terminal(receipt.status())) {
        throw new PendingOutcome();
      }
    } catch (RuntimeException failure) {
      if (failure instanceof ApiException conflict
          && conflict.code().equals("CHANNEL_EVIDENCE_CONFLICT")) {
        // The result transaction has rolled back; do not retain its connection/locks while
        // acquiring the independent evidence transaction.
        if (receipt != null)
          retainConflict(receipt, task.aggregateId(), operation, payment.channel(), conflict);
        jdbc.update(
            "UPDATE channel_attempts SET status='CONFLICT',updated_at=? WHERE channel=? AND request_key=?",
            Timestamp.from(clock.instant()),
            payment.channel(),
            attemptKey);
      }
      if (!(failure instanceof dev.merchantpay.platform.common.ApiException)
          && !(failure instanceof PendingOutcome)) {
        if (isRefund) {
          refunds.applyResult(
              task.aggregateId(), new ChannelResult.Unknown(payment.channel(), "query required"));
        } else {
          payments.applyResult(
              task.aggregateId(), new ChannelResult.Unknown(payment.channel(), "query required"));
        }
      }
      throw failure;
    }
  }

  public void apply(ChannelApi.Receipt receipt) {
    apply(receipt, receipt.aggregateId(), receipt.operation(), receipt.channel());
  }

  private void retainResponse(ChannelApi.Receipt receipt, String channel, String attemptKey) {
    jdbc.update(
        "UPDATE channel_attempts SET response_body=?,updated_at=? WHERE channel=? AND request_key=?",
        json.writeValueAsString(receipt),
        Timestamp.from(clock.instant()),
        channel,
        attemptKey);
  }

  private void validate(
      ChannelApi.Receipt receipt,
      String expectedId,
      String expectedOperation,
      String expectedChannel) {
    verifyIdentity(receipt, expectedId, expectedOperation, expectedChannel);
    verifyTerminalEvidence(receipt);
  }

  private void apply(
      ChannelApi.Receipt receipt,
      String expectedId,
      String expectedOperation,
      String expectedChannel) {
    try {
      validate(receipt, expectedId, expectedOperation, expectedChannel);
      applyBusiness(receipt);
    } catch (ApiException conflict) {
      retainConflict(receipt, expectedId, expectedOperation, expectedChannel, conflict);
      throw conflict;
    }
  }

  private void applyBusiness(ChannelApi.Receipt receipt) {
    var result = normalize(receipt);
    if (receipt.operation().equals("PAYMENT")) {
      payments.applyResult(receipt.aggregateId(), result);
    } else if (receipt.operation().equals("REFUND")) {
      refunds.applyResult(receipt.aggregateId(), result);
    } else {
      throw new IllegalArgumentException("Unknown channel operation");
    }
  }

  private void retainConflict(
      ChannelApi.Receipt receipt,
      String expectedId,
      String expectedOperation,
      String expectedChannel,
      ApiException conflict) {
    if (!conflict.code().equals("CHANNEL_EVIDENCE_CONFLICT")) return;
    String table = expectedOperation.equals("PAYMENT") ? "payments" : "refunds";
    // Resolve on the outer connection; REQUIRES_NEW INSERT SELECT would self-wait on root locks.
    String merchant =
        jdbc.queryForObject(
            "SELECT merchant_id FROM " + table + " WHERE id=?", String.class, expectedId);
    evidenceTx.executeWithoutResult(
        transaction ->
            jdbc.update(
                "INSERT INTO evidence_conflicts(id,merchant_id,aggregate_id,channel,detail,raw_body,created_at) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                merchant,
                expectedId,
                expectedChannel,
                conflict.getMessage(),
                json.writeValueAsString(receipt),
                Timestamp.from(clock.instant())));
  }

  private void verifyTerminalEvidence(ChannelApi.Receipt receipt) {
    if (!terminal(receipt.status())) return;
    String table = receipt.operation().equals("PAYMENT") ? "payments" : "refunds";
    var rows =
        jdbc.queryForList(
            "SELECT amount_minor,channel FROM " + table + " WHERE id=?", receipt.aggregateId());
    if (rows.isEmpty()) throw new ApiException("UNKNOWN_CHANNEL_RECEIPT", 409, "渠道回执未匹配原业务");
    var expected = rows.getFirst();
    // The mock channel can persist a zero-amount tombstone when close wins before submission.
    boolean closeBeforeSubmit =
        receipt.operation().equals("PAYMENT")
            && receipt.status().equals("CLOSED")
            && "0".equals(receipt.amountMinor())
            && "0".equals(receipt.feeMinor())
            && receipt.reference() != null
            && receipt.reference().startsWith("mock-close-")
            && !jdbc.queryForList(
                    "SELECT id FROM outbox_events WHERE event_id=?",
                    "channel-close:" + receipt.aggregateId())
                .isEmpty();
    if (!expected.get("channel").equals(receipt.channel())
        || !"CNY".equals(receipt.currency())
        || !closeBeforeSubmit
            && !expected.get("amount_minor").toString().equals(receipt.amountMinor())) {
      throw new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "渠道回执金额、币种或渠道与原业务不一致");
    }
  }

  public record ChannelStatus(
      String channel,
      String state,
      Long pendingCallbacks,
      Long pendingPayments,
      String oldestCallbackAt,
      java.time.Instant observedAt) {}

  public ChannelStatus health(String channel, String merchant) {
    try {
      var health = api.health(channel, merchant, token);
      return new ChannelStatus(
          channel,
          "AVAILABLE",
          health.pendingCallbacks(),
          health.pendingPayments(),
          health.oldestCallbackAt(),
          clock.instant());
    } catch (org.springframework.web.client.RestClientException unavailable) {
      return new ChannelStatus(channel, "UNAVAILABLE", null, null, "", clock.instant());
    }
  }

  private static boolean terminal(String state) {
    return state.equals("SUCCEEDED") || state.equals("FAILED") || state.equals("CLOSED");
  }

  private static void verifyIdentity(
      ChannelApi.Receipt receipt, String id, String operation, String channel) {
    if (!id.equals(receipt.aggregateId())
        || !operation.equals(receipt.operation())
        || !channel.equals(receipt.channel())) {
      throw new dev.merchantpay.platform.common.ApiException(
          "CHANNEL_EVIDENCE_CONFLICT", 409, "渠道回执关联不一致");
    }
  }

  private static ChannelResult normalize(ChannelApi.Receipt receipt) {
    return switch (receipt.status()) {
      case "SUCCEEDED" ->
          new ChannelResult.Success(
              receipt.channel(),
              receipt.reference(),
              dev.merchantpay.platform.common.MoneyRules.parsePositive(receipt.amountMinor()),
              receipt.currency());
      case "FAILED" -> new ChannelResult.Failure(receipt.channel(), "DECLINED");
      case "CLOSED" -> new ChannelResult.Closed(receipt.channel());
      case "PENDING" -> new ChannelResult.Pending(receipt.channel());
      default -> new ChannelResult.Unknown(receipt.channel(), "No authoritative result");
    };
  }
}
