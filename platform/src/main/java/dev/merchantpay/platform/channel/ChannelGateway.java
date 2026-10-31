package dev.merchantpay.platform.channel;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
  private final ChannelResultBatcher resultBatches;
  private final MeterRegistry metrics;
  private final ChannelEvidenceWrites evidenceWrites;
  private final boolean channelAttemptOrderedIds;

  public ChannelGateway(
      ChannelApi api,
      PaymentService payments,
      RefundService refunds,
      JdbcTemplate jdbc,
      JsonMapper json,
      @Value("${merchantpay.channel-token}") String token,
      Clock clock,
      TransactionTemplate tx,
      ChannelResultBatcher resultBatches,
      MeterRegistry metrics) {
    this(api, payments, refunds, jdbc, json, token, clock, tx, resultBatches, metrics, null);
  }

  public ChannelGateway(
      ChannelApi api,
      PaymentService payments,
      RefundService refunds,
      JdbcTemplate jdbc,
      JsonMapper json,
      @Value("${merchantpay.channel-token}") String token,
      Clock clock,
      TransactionTemplate tx,
      ChannelResultBatcher resultBatches,
      MeterRegistry metrics,
      ChannelEvidenceWrites evidenceWrites) {
    this(
        api,
        payments,
        refunds,
        jdbc,
        json,
        token,
        clock,
        tx,
        resultBatches,
        metrics,
        evidenceWrites,
        false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ChannelGateway(
      ChannelApi api,
      PaymentService payments,
      RefundService refunds,
      JdbcTemplate jdbc,
      JsonMapper json,
      @Value("${merchantpay.channel-token}") String token,
      Clock clock,
      TransactionTemplate tx,
      ChannelResultBatcher resultBatches,
      MeterRegistry metrics,
      ChannelEvidenceWrites evidenceWrites,
      @Value("${merchantpay.channel-attempt-ordered-ids:false}") boolean channelAttemptOrderedIds) {
    this.channelAttemptOrderedIds = channelAttemptOrderedIds;
    this.api = api;
    this.payments = payments;
    this.refunds = refunds;
    this.jdbc = jdbc;
    this.json = json;
    this.token = token;
    this.clock = clock;
    this.evidenceTx = new TransactionTemplate(tx.getTransactionManager());
    this.resultBatches = resultBatches;
    this.metrics = metrics;
    this.evidenceWrites = evidenceWrites;
    this.evidenceTx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public record TaskInput(
      TaskQueue.Task task, PaymentService.PaymentView payment, RefundService.RefundView refund) {}

  public Map<String, TaskInput> prefetch(List<TaskQueue.Task> tasks) {
    Objects.requireNonNull(tasks);
    if (tasks.size() > 64) throw new IllegalArgumentException("Channel task batch exceeds64");
    var eligible = new ArrayList<TaskQueue.Task>();
    var taskIds = new HashSet<String>();
    var refundIds = new LinkedHashSet<String>();
    for (var task : tasks) {
      Objects.requireNonNull(task);
      if (!taskIds.add(Objects.requireNonNull(task.id())))
        throw new IllegalArgumentException("Duplicate claimed task ID");
      if (!Set.of("CHANNEL_PAYMENT", "CHANNEL_REFUND", "CHANNEL_CLOSE").contains(task.kind()))
        continue;
      Objects.requireNonNull(task.aggregateId());
      Objects.requireNonNull(task.merchantId());
      eligible.add(task);
      if (task.kind().equals("CHANNEL_REFUND")) refundIds.add(task.aggregateId());
    }
    if (eligible.isEmpty()) return Map.of();
    var foundRefunds = refunds.loadForChannelTasks(refundIds);
    var paymentIds = new LinkedHashSet<String>();
    for (var task : eligible) {
      if (task.kind().equals("CHANNEL_REFUND")) {
        var refund = foundRefunds.get(task.aggregateId());
        if (refund != null && refund.merchantId().equals(task.merchantId()))
          paymentIds.add(refund.paymentId());
      } else paymentIds.add(task.aggregateId());
    }
    var foundPayments = payments.loadForChannelTasks(paymentIds);
    var result = new HashMap<String, TaskInput>();
    for (var task : eligible) {
      var refund =
          task.kind().equals("CHANNEL_REFUND") ? foundRefunds.get(task.aggregateId()) : null;
      if (task.kind().equals("CHANNEL_REFUND")
          && (refund == null || !refund.merchantId().equals(task.merchantId()))) continue;
      var payment = foundPayments.get(refund == null ? task.aggregateId() : refund.paymentId());
      if (payment == null || !payment.merchantId().equals(task.merchantId())) continue;
      var input = new TaskInput(task, payment, refund);
      verifyTaskInput(task, input);
      result.put(task.id(), input);
    }
    metrics.summary("merchantpay.channel.prefetch.batch.size").record(eligible.size());
    metrics.counter("merchantpay.channel.prefetch.hits").increment(result.size());
    metrics
        .counter("merchantpay.channel.prefetch.misses")
        .increment(eligible.size() - result.size());
    return Map.copyOf(result);
  }

  private void verifyTaskInput(TaskQueue.Task task, TaskInput input) {
    var payment = input.payment();
    var refund = input.refund();
    boolean valid =
        task.equals(input.task())
            && payment != null
            && task.merchantId().equals(payment.merchantId());
    if (task.kind().equals("CHANNEL_REFUND"))
      valid =
          valid
              && refund != null
              && task.aggregateId().equals(refund.id())
              && task.merchantId().equals(refund.merchantId())
              && refund.paymentId().equals(payment.id())
              && refund.channel().equals(payment.channel());
    else
      valid =
          valid
              && refund == null
              && task.aggregateId().equals(payment.id())
              && Set.of("CHANNEL_PAYMENT", "CHANNEL_CLOSE").contains(task.kind());
    if (!valid)
      throw new IllegalArgumentException("Channel input does not belong to this claimed task");
  }

  public void execute(TaskQueue.Task task) {
    execute(task, null);
  }

  public void execute(TaskQueue.Task task, TaskInput input) {
    if (input != null) verifyTaskInput(task, input);
    boolean isRefund = task.kind().equals("CHANNEL_REFUND");
    String operation = isRefund ? "REFUND" : "PAYMENT";
    var refund =
        input != null
            ? input.refund()
            : (isRefund ? refunds.get(task.merchantId(), task.aggregateId()) : null);
    var payment =
        input != null
            ? input.payment()
            : payments.get(task.merchantId(), isRefund ? refund.paymentId() : task.aggregateId());
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
    long prepareStarted = System.nanoTime();
    int prepared;
    try {
      prepared =
          writeEvidence(
              payment.channel() + "\0" + attemptKey,
              () ->
                  jdbc.update(
                      "INSERT IGNORE INTO channel_attempts(id,aggregate_id,payment_id,channel,operation,request_key,status,request_body,created_at,updated_at) VALUES (?,?,?,?,?,?,'PREPARED',?,?,?)",
                      channelAttemptOrderedIds
                          ? dev.merchantpay.platform.common.TimeOrderedIds.uuidV7(now.toInstant())
                          : UUID.randomUUID().toString(),
                      task.aggregateId(),
                      payment.id(),
                      payment.channel(),
                      task.kind(),
                      attemptKey,
                      json.writeValueAsString(request),
                      now,
                      now));
    } finally {
      recordStage(operation, "prepare", prepareStarted);
    }
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
          retainResponse(receipt, payment.channel(), attemptKey, operation);
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
      retainResponse(receipt, payment.channel(), attemptKey, operation);
      var observed = receipt;
      // Network I/O and raw receipt persistence precede this short business transaction.
      long resultStarted = System.nanoTime();
      try {
        resultBatches.execute(
            payment.id(),
            () -> {
              validate(observed, task.aggregateId(), operation, payment.channel());
              applyBusiness(observed);
              jdbc.update(
                  "UPDATE channel_attempts SET status=?,updated_at=? WHERE channel=? AND request_key=?",
                  observed.status(),
                  Timestamp.from(clock.instant()),
                  payment.channel(),
                  attemptKey);
            });
      } finally {
        recordStage(operation, "apply_result", resultStarted);
      }
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

  private void retainResponse(
      ChannelApi.Receipt receipt, String channel, String attemptKey, String operation) {
    long started = System.nanoTime();
    try {
      if (evidenceWrites != null && evidenceWrites.bulkReceiptSqlEnabled()) {
        evidenceWrites.retainReceipt(
            channel, attemptKey, json.writeValueAsString(receipt), Timestamp.from(clock.instant()));
        return;
      }
      writeEvidence(
          channel + "\0" + attemptKey,
          () ->
              jdbc.update(
                  "UPDATE channel_attempts SET response_body=?,updated_at=? WHERE channel=? AND request_key=?",
                  json.writeValueAsString(receipt),
                  Timestamp.from(clock.instant()),
                  channel,
                  attemptKey));
    } finally {
      recordStage(operation, "retain_receipt", started);
    }
  }

  private int writeEvidence(String key, java.util.function.IntSupplier action) {
    return evidenceWrites == null ? action.getAsInt() : evidenceWrites.execute(key, action);
  }

  private void recordStage(String operation, String stage, long started) {
    metrics
        .timer("merchantpay.channel.stage", "operation", operation, "stage", stage)
        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
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
