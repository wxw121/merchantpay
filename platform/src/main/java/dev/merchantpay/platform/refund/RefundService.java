package dev.merchantpay.platform.refund;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.common.MoneyRules;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundService {
  public record CreateRefund(String merchantRefundNo, String amountMinor) {}

  public record RefundView(
      String id,
      String paymentId,
      String merchantId,
      String merchantRefundNo,
      String amountMinor,
      String refundedFeeMinor,
      String status,
      String channel,
      String channelReference,
      Instant createdAt,
      Instant updatedAt) {}

  private static final RowMapper<RefundView> MAPPER =
      (rs, row) ->
          new RefundView(
              rs.getString("id"),
              rs.getString("payment_id"),
              rs.getString("merchant_id"),
              rs.getString("merchant_refund_no"),
              rs.getString("amount_minor"),
              rs.getString("refunded_fee_minor"),
              rs.getString("status"),
              rs.getString("channel"),
              rs.getString("channel_reference"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final PaymentService payments;
  private final LedgerService ledger;
  private final OutboxService outbox;
  private final Clock clock;

  public RefundService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      PaymentService payments,
      LedgerService ledger,
      OutboxService outbox,
      Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.payments = payments;
    this.ledger = ledger;
    this.outbox = outbox;
    this.clock = clock;
  }

  public RefundView create(String merchantId, String paymentId, CreateRefund input) {
    PaymentService.requireBusinessNo(input.merchantRefundNo());
    long amount = MoneyRules.parsePositive(input.amountMinor());
    return transactions.execute(
        transaction -> {
          var payment = payments.lock(paymentId);
          if (!payment.merchantId().equals(merchantId)) {
            throw new ApiException("PAYMENT_NOT_FOUND", 404, "支付单不存在");
          }
          var existing =
              jdbc.query(
                  "SELECT * FROM refunds WHERE merchant_id=? AND payment_id=? AND merchant_refund_no=? FOR UPDATE",
                  MAPPER,
                  merchantId,
                  paymentId,
                  input.merchantRefundNo());
          if (!existing.isEmpty()) {
            if (!existing.getFirst().amountMinor().equals(input.amountMinor())) {
              throw new ApiException("IDEMPOTENCY_CONFLICT", 409, "幂等业务号已用于不同的退款金额");
            }
            return existing.getFirst();
          }
          var enabled =
              jdbc.query(
                  "SELECT enabled FROM merchants WHERE id=?",
                  (rs, row) -> rs.getBoolean(1),
                  merchantId);
          if (enabled.isEmpty() || !enabled.getFirst()) {
            throw new ApiException("MERCHANT_DISABLED", 403, "商户不存在或已停用");
          }
          if (!payment.status().equals("SUCCEEDED")) {
            throw new ApiException("STATE_CONFLICT", 409, "仅成功支付可以退款");
          }
          if (amount > payment.amount() - payment.refunded() - payment.reserved()) {
            throw new ApiException("REFUND_LIMIT_EXCEEDED", 409, "退款金额超过可退额度");
          }
          var now = Timestamp.from(clock.instant());
          String id = "r" + payment.id().charAt(1) + "_" + UUID.randomUUID();
          jdbc.update(
              "UPDATE payments SET reserved_minor=reserved_minor+?,updated_at=? WHERE id=?",
              amount,
              now,
              paymentId);
          jdbc.update(
              "INSERT INTO refunds(id,payment_id,merchant_id,merchant_refund_no,amount_minor,status,channel,created_at,updated_at) VALUES (?,?,?,?,?,'PROCESSING',?,?,?)",
              id,
              paymentId,
              merchantId,
              input.merchantRefundNo(),
              amount,
              payment.channel(),
              now,
              now);
          outbox.enqueue("channel-refund:" + id, "CHANNEL_REFUND", id, merchantId);
          return get(merchantId, id);
        });
  }

  /** Internal bounded lookup; channel callers still verify each record's merchant. */
  public java.util.Map<String, RefundView> loadForChannelTasks(java.util.Collection<String> ids) {
    Objects.requireNonNull(ids);
    if (ids.size() > 64) throw new IllegalArgumentException("Channel input batch exceeds64");
    if (ids.isEmpty()) return java.util.Map.of();
    var unique = new java.util.LinkedHashSet<>(ids);
    if (unique.contains(null)) throw new IllegalArgumentException("Channel input ID required");
    String sql =
        "SELECT * FROM refunds WHERE id IN ("
            + String.join(",", java.util.Collections.nCopies(unique.size(), "?"))
            + ")";
    var rows =
        jdbc.query(
            connection -> {
              var statement = connection.prepareStatement(sql);
              statement.setQueryTimeout(1);
              int index = 1;
              for (String id : unique) statement.setString(index++, id);
              return statement;
            },
            MAPPER);
    var result = new java.util.HashMap<String, RefundView>();
    for (var row : rows) result.put(row.id(), row);
    return java.util.Map.copyOf(result);
  }

  public RefundView get(String merchantId, String id) {
    var rows =
        jdbc.query("SELECT * FROM refunds WHERE id=? AND merchant_id=?", MAPPER, id, merchantId);
    if (rows.isEmpty()) {
      throw new ApiException("REFUND_NOT_FOUND", 404, "退款单不存在");
    }
    return rows.getFirst();
  }

  public RefundView applyResult(String id, ChannelResult result) {
    return transactions.execute(
        transaction -> {
          var initial = jdbc.query("SELECT * FROM refunds WHERE id=?", MAPPER, id);
          if (initial.isEmpty()) {
            throw new ApiException("REFUND_NOT_FOUND", 404, "退款单不存在");
          }
          // All refund operations lock the payment root before the refund, avoiding inverted lock
          // order.
          var payment = payments.lock(initial.getFirst().paymentId());
          var refund =
              jdbc.query("SELECT * FROM refunds WHERE id=? FOR UPDATE", MAPPER, id).getFirst();
          if (!refund.channel().equals(result.channel())) {
            throw conflict();
          }
          long amount = Long.parseLong(refund.amountMinor());
          switch (result) {
            case ChannelResult.Success success -> {
              if (success.amountMinor() != amount
                  || !success.currency().equals("CNY")
                  || success.reference() == null
                  || success.reference().isBlank()
                  || success.reference().length() > 128) {
                throw conflict();
              }
              if (refund.status().equals("SUCCEEDED")) {
                if (!Objects.equals(refund.channelReference(), success.reference())) {
                  throw conflict();
                }
                return refund;
              }
              if (refund.status().equals("FAILED") || payment.reserved() < amount) {
                throw conflict();
              }
              long cumulative = Math.addExact(payment.refunded(), amount);
              long cumulativeFee =
                  MoneyRules.refundedFee(payment.fee(), payment.amount(), cumulative);
              long thisFee = cumulativeFee - payment.refundedFee();
              var now = clock.instant();
              jdbc.update(
                  "UPDATE payments SET reserved_minor=reserved_minor-?,refunded_minor=?,refunded_fee_minor=?,updated_at=? WHERE id=?",
                  amount,
                  cumulative,
                  cumulativeFee,
                  Timestamp.from(now),
                  payment.id());
              try {
                jdbc.update(
                    "UPDATE refunds SET status='SUCCEEDED',channel_reference=?,refunded_fee_minor=?,updated_at=? WHERE id=?",
                    success.reference(),
                    thisFee,
                    Timestamp.from(now),
                    id);
              } catch (DuplicateKeyException reusedReceipt) {
                // Keep the root quota change in the same transaction; this conflict rolls it back.
                throw conflict();
              }
              ledger.postRefund(id, payment.id(), refund.merchantId(), amount, thisFee, now);
              outbox.enqueue("refund-success:" + id, "REFUND_SUCCEEDED", id, refund.merchantId());
            }
            case ChannelResult.Failure ignored -> {
              if (refund.status().equals("SUCCEEDED")) throw conflict();
              if (!terminal(refund.status())) {
                if (payment.reserved() < amount) {
                  throw conflict();
                }
                var now = Timestamp.from(clock.instant());
                jdbc.update(
                    "UPDATE payments SET reserved_minor=reserved_minor-?,updated_at=? WHERE id=?",
                    amount,
                    now,
                    payment.id());
                jdbc.update("UPDATE refunds SET status='FAILED',updated_at=? WHERE id=?", now, id);
                outbox.enqueue("refund-failed:" + id, "REFUND_FAILED", id, payment.merchantId());
              }
            }
            case ChannelResult.Unknown ignored -> {
              if (!terminal(refund.status())) {
                jdbc.update(
                    "UPDATE refunds SET status='UNKNOWN',updated_at=? WHERE id=?",
                    Timestamp.from(clock.instant()),
                    id);
              }
            }
            case ChannelResult.Pending ignored -> {}
            case ChannelResult.Closed ignored -> {
              throw conflict();
            }
          }
          return get(refund.merchantId(), id);
        });
  }

  private static boolean terminal(String status) {
    return status.equals("SUCCEEDED") || status.equals("FAILED");
  }

  private static ApiException conflict() {
    return new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "退款渠道证据冲突，需要查证");
  }
}
