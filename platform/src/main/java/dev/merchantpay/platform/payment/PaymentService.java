package dev.merchantpay.platform.payment;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.common.MoneyRules;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  public record CreatePayment(
      String merchantOrderNo,
      String amountMinor,
      String currency,
      String channel,
      String notificationUrl) {}

  public record PaymentView(
      String id,
      String merchantId,
      String merchantOrderNo,
      String amountMinor,
      String feeMinor,
      String currency,
      String status,
      String refundedMinor,
      String reservedMinor,
      String refundedFeeMinor,
      String channel,
      String channelReference,
      String notificationUrl,
      Instant createdAt,
      Instant updatedAt) {}

  public record Control(
      String id,
      String merchantId,
      String orderNo,
      String requestHash,
      long amount,
      long fee,
      String currency,
      String status,
      long refunded,
      long reserved,
      long refundedFee,
      String channel,
      String reference,
      String notificationUrl,
      Instant createdAt,
      Instant updatedAt) {
    public PaymentView view() {
      return new PaymentView(
          id,
          merchantId,
          orderNo,
          Long.toString(amount),
          Long.toString(fee),
          currency,
          status,
          Long.toString(refunded),
          Long.toString(reserved),
          Long.toString(refundedFee),
          channel,
          reference,
          notificationUrl,
          createdAt,
          updatedAt);
    }
  }

  private static final RowMapper<Control> MAPPER =
      (rs, row) ->
          new Control(
              rs.getString("id"),
              rs.getString("merchant_id"),
              rs.getString("merchant_order_no"),
              rs.getString("request_hash"),
              rs.getLong("amount_minor"),
              rs.getLong("fee_minor"),
              rs.getString("currency"),
              rs.getString("status"),
              rs.getLong("refunded_minor"),
              rs.getLong("reserved_minor"),
              rs.getLong("refunded_fee_minor"),
              rs.getString("channel"),
              rs.getString("channel_reference"),
              rs.getString("notification_url"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final LedgerService ledger;
  private final OutboxService outbox;
  private final Clock clock;
  private final int maximumPerShard;
  private final boolean admissionSlots;
  private final boolean borrowSlots;
  private final boolean paymentOrderedIds;
  private dev.merchantpay.platform.control.ChannelControl channelControl;

  @org.springframework.beans.factory.annotation.Autowired
  public void setChannelControl(dev.merchantpay.platform.control.ChannelControl control) {
    this.channelControl = control;
  }

  public PaymentService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      LedgerService ledger,
      OutboxService outbox,
      Clock clock,
      int maximumPerShard) {
    this(jdbc, transactions, ledger, outbox, clock, maximumPerShard, false);
  }

  public PaymentService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      LedgerService ledger,
      OutboxService outbox,
      Clock clock,
      int maximumPerShard,
      boolean admissionSlots) {
    this(jdbc, transactions, ledger, outbox, clock, maximumPerShard, admissionSlots, false);
  }

  public PaymentService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      LedgerService ledger,
      OutboxService outbox,
      Clock clock,
      @Value("${merchantpay.payment-max-inflight-per-shard:16}") int maximumPerShard,
      @Value("${merchantpay.payment-admission-slots:false}") boolean admissionSlots,
      @Value("${merchantpay.payment-admission-slot-borrow:false}") boolean borrowSlots) {
    this(
        jdbc,
        transactions,
        ledger,
        outbox,
        clock,
        maximumPerShard,
        admissionSlots,
        borrowSlots,
        false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PaymentService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      LedgerService ledger,
      OutboxService outbox,
      Clock clock,
      @Value("${merchantpay.payment-max-inflight-per-shard:16}") int maximumPerShard,
      @Value("${merchantpay.payment-admission-slots:false}") boolean admissionSlots,
      @Value("${merchantpay.payment-admission-slot-borrow:false}") boolean borrowSlots,
      @Value("${merchantpay.payment-ordered-ids:false}") boolean paymentOrderedIds) {
    this.paymentOrderedIds = paymentOrderedIds;
    if (maximumPerShard < 1 || maximumPerShard > 4096) {
      throw new IllegalArgumentException("Payment capacity per shard must be 1..4096");
    }
    if (borrowSlots && !admissionSlots) {
      throw new IllegalArgumentException("Slot borrowing requires payment admission slots");
    }
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.ledger = ledger;
    this.outbox = outbox;
    this.clock = clock;
    this.maximumPerShard = maximumPerShard;
    this.admissionSlots = admissionSlots;
    this.borrowSlots = borrowSlots;
  }

  @jakarta.annotation.PostConstruct
  void initializeSlots() {
    transactions.executeWithoutResult(ignored -> initializeAdmission());
  }

  private void initializeAdmission() {
    // Startup-only lock: stop all old writers before changing modes; never lock it per payment.
    boolean previousSlots =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT slot_mode FROM payment_admission_mode WHERE id=1 FOR UPDATE",
                Boolean.class));
    if (!admissionSlots) {
      if (!previousSlots) return;
      Long active =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM payments WHERE status NOT IN ('SUCCEEDED','FAILED','CLOSED')",
              Long.class);
      if (active != 0)
        throw new IllegalStateException("Admission mode switch requires drained payments");
      jdbc.update("UPDATE payment_admission SET inflight=0");
      jdbc.update(
          "UPDATE payment_admission_slots SET payment_id=NULL WHERE payment_id IS NOT NULL");
      jdbc.update("UPDATE payment_admission_mode SET slot_mode=FALSE WHERE id=1");
      return;
    }
    if (!previousSlots) {
      // A counter-mode deployment may have finalized records backfilled during migration.
      jdbc.update(
          "UPDATE payment_admission_slots SET payment_id=NULL WHERE payment_id IN (SELECT id FROM payments WHERE status IN ('SUCCEEDED','FAILED','CLOSED'))");
    }
    Long missing =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM payments p LEFT JOIN payment_admission_slots s ON s.payment_id=p.id WHERE p.status NOT IN ('SUCCEEDED','FAILED','CLOSED') AND s.payment_id IS NULL",
            Long.class);
    Long stale =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM payment_admission_slots s LEFT JOIN payments p ON p.id=s.payment_id WHERE s.payment_id IS NOT NULL AND (p.id IS NULL OR p.status IN ('SUCCEEDED','FAILED','CLOSED'))",
            Long.class);
    if (missing != 0 || stale != 0)
      throw new IllegalStateException("Admission mode switch requires drained payments and slots");
    if (!borrowSlots
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_admission_slots s JOIN payments p ON p.id=s.payment_id WHERE s.logical_shard<>p.logical_shard",
                Long.class)
            != 0) {
      throw new IllegalStateException("Disable slot borrowing only after borrowed payments drain");
    }
    jdbc.update("UPDATE payment_admission_mode SET slot_mode=TRUE WHERE id=1");
    if (maximumPerShard <= 16) return;
    var rows = new java.util.ArrayList<Object[]>();
    for (int shard = 0; shard < 16; shard++)
      for (int slot = 16; slot < maximumPerShard; slot++) rows.add(new Object[] {shard, slot});
    jdbc.batchUpdate(
        "INSERT INTO payment_admission_slots(logical_shard,slot_no) VALUES (?,?) ON DUPLICATE KEY UPDATE slot_no=slot_no",
        rows);
  }

  public PaymentView create(String merchantId, CreatePayment input) {
    requireBusinessNo(input.merchantOrderNo());
    long amount = MoneyRules.parsePositive(input.amountMinor());
    boolean routedRequest =
        "AUTO".equals(input.channel()) || "ALIPAY_SANDBOX".equals(input.channel());
    if (!"CNY".equals(input.currency())
        || !("MOCK_A".equals(input.channel())
            || "MOCK_B".equals(input.channel())
            || routedRequest)) {
      throw new ApiException("INVALID_PAYMENT", 400, "币种或支付渠道不受支持");
    }
    if (input.notificationUrl() != null
        && (input.notificationUrl().length() > 1024
            || input.notificationUrl().chars().anyMatch(Character::isISOControl))) {
      throw new ApiException("INVALID_NOTIFICATION_URL", 400, "通知地址格式不合法");
    }
    String hash =
        sha256(
            merchantId
                + "\0"
                + input.merchantOrderNo()
                + "\0"
                + amount
                + "\0"
                + input.currency()
                + "\0"
                + input.channel()
                + "\0"
                + Objects.toString(input.notificationUrl(), ""));
    return transactions.execute(
        status -> {
          var merchant =
              jdbc.queryForList(
                  "SELECT enabled, notification_url FROM merchants WHERE id=?", merchantId);
          if (merchant.isEmpty() || !Boolean.TRUE.equals(merchant.getFirst().get("enabled"))) {
            // Previously accepted business keys remain safely retryable after merchant disablement.
            var existing =
                jdbc.query(
                    "SELECT * FROM payments WHERE merchant_id=? AND merchant_order_no=? FOR UPDATE",
                    MAPPER,
                    merchantId,
                    input.merchantOrderNo());
            if (!existing.isEmpty()) return idempotent(existing.getFirst(), hash);
            throw new ApiException("MERCHANT_DISABLED", 403, "商户不存在或已停用");
          }
          // Existing V2 business keys remain retryable even after their capabilities are disabled.
          if (routedRequest || channelControl != null && channelControl.routingEnabled()) {
            var existing =
                jdbc.query(
                    "SELECT * FROM payments WHERE merchant_id=? AND merchant_order_no=? FOR UPDATE",
                    MAPPER,
                    merchantId,
                    input.merchantOrderNo());
            if (!existing.isEmpty()) return idempotent(existing.getFirst(), hash);
          }
          if (channelControl == null && routedRequest)
            throw new ApiException("INVALID_PAYMENT", 400, "币种或支付渠道不受支持");
          String chosenChannel =
              channelControl == null
                  ? input.channel()
                  : channelControl.route(merchantId, input.channel());
          int bucket =
              Integer.parseInt(
                      sha256(merchantId + "\0" + input.merchantOrderNo()).substring(0, 2), 16)
                  & 15;
          Boolean atCapacity =
              borrowSlots
                  ? false
                  : jdbc.queryForObject(
                      admissionSlots
                          ? "SELECT COUNT(*)>=? FROM payment_admission_slots WHERE logical_shard=? AND payment_id IS NOT NULL"
                          : "SELECT inflight>=? FROM payment_admission WHERE logical_shard=?",
                      Boolean.class,
                      maximumPerShard,
                      bucket);
          if (atCapacity == null || atCapacity) {
            var existing =
                jdbc.query(
                    "SELECT * FROM payments WHERE merchant_id=? AND merchant_order_no=? FOR UPDATE",
                    MAPPER,
                    merchantId,
                    input.merchantOrderNo());
            if (!existing.isEmpty()) return idempotent(existing.getFirst(), hash);
            throw new ApiException(
                "RETRY_WITH_SAME_BUSINESS_KEY",
                503,
                "支付处理容量已满，请使用相同业务号退避重试",
                ApiException.RetryReason.PAYMENT_CAPACITY);
          }
          var instant = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
          String id =
              "p"
                  + Integer.toHexString(bucket)
                  + "_"
                  + (paymentOrderedIds
                      ? dev.merchantpay.platform.common.TimeOrderedIds.uuidV7(instant)
                      : UUID.randomUUID().toString());
          var now = Timestamp.from(instant);
          long fee = MoneyRules.fee(amount);
          String notificationUrl =
              input.notificationUrl() != null
                  ? input.notificationUrl()
                  : (String) merchant.getFirst().get("notification_url");
          try {
            jdbc.update(
                "INSERT INTO payments(id,merchant_id,merchant_order_no,request_hash,logical_shard,amount_minor,fee_minor,currency,status,channel,notification_url,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'PROCESSING',?,?,?,?)"
                    + (borrowSlots ? " ON DUPLICATE KEY UPDATE id=id" : ""),
                id,
                merchantId,
                input.merchantOrderNo(),
                hash,
                bucket,
                amount,
                fee,
                "CNY",
                chosenChannel,
                notificationUrl,
                now,
                now);
          } catch (DuplicateKeyException conflict) {
            var winner =
                jdbc.query(
                    "SELECT * FROM payments WHERE merchant_id=? AND merchant_order_no=? FOR UPDATE",
                    MAPPER,
                    merchantId,
                    input.merchantOrderNo());
            if (winner.isEmpty()) {
              throw conflict;
            }
            return idempotent(winner.getFirst(), hash);
          }
          if (borrowSlots) {
            // A no-op upsert owns the winner's exclusive lock without a duplicate S-to-X
            // conversion. Never infer insert ownership from driver-specific affected-row counts.
            var winner =
                jdbc.query(
                    "SELECT * FROM payments WHERE merchant_id=? AND merchant_order_no=? FOR UPDATE",
                    MAPPER,
                    merchantId,
                    input.merchantOrderNo());
            if (winner.size() != 1) {
              throw new IllegalStateException(
                  "Payment creation did not establish business ownership");
            }
            if (!winner.getFirst().id().equals(id)) return idempotent(winner.getFirst(), hash);
          }
          // Acquire only after the unique INSERT: a racing idempotent retry needs no new slot.
          // The increment rolls back with the payment/outbox if admission or creation fails.
          if (!reserveCapacity(bucket, id)) {
            throw new ApiException(
                "RETRY_WITH_SAME_BUSINESS_KEY",
                503,
                "支付处理容量已满，请使用相同业务号退避重试",
                ApiException.RetryReason.PAYMENT_CAPACITY);
          }
          outbox.enqueue("channel-payment:" + id, "CHANNEL_PAYMENT", id, merchantId);
          return new Control(
                  id,
                  merchantId,
                  input.merchantOrderNo(),
                  hash,
                  amount,
                  fee,
                  "CNY",
                  "PROCESSING",
                  0,
                  0,
                  0,
                  chosenChannel,
                  null,
                  notificationUrl,
                  instant,
                  instant)
              .view();
        });
  }

  /** Internal bounded lookup; channel callers still verify each record's merchant. */
  public java.util.Map<String, PaymentView> loadForChannelTasks(java.util.Collection<String> ids) {
    Objects.requireNonNull(ids);
    if (ids.size() > 64) throw new IllegalArgumentException("Channel input batch exceeds64");
    if (ids.isEmpty()) return java.util.Map.of();
    var unique = new java.util.LinkedHashSet<>(ids);
    if (unique.contains(null)) throw new IllegalArgumentException("Channel input ID required");
    String sql =
        "SELECT * FROM payments WHERE id IN ("
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
    var result = new java.util.HashMap<String, PaymentView>();
    for (var row : rows) result.put(row.id(), row.view());
    return java.util.Map.copyOf(result);
  }

  public PaymentView get(String merchantId, String id) {
    var rows =
        jdbc.query("SELECT * FROM payments WHERE id=? AND merchant_id=?", MAPPER, id, merchantId);
    if (rows.isEmpty()) {
      throw new ApiException("PAYMENT_NOT_FOUND", 404, "支付单不存在");
    }
    return rows.getFirst().view();
  }

  public Control lock(String id) {
    var rows = jdbc.query("SELECT * FROM payments WHERE id=? FOR UPDATE", MAPPER, id);
    if (rows.isEmpty()) {
      throw new ApiException("PAYMENT_NOT_FOUND", 404, "支付单不存在");
    }
    return rows.getFirst();
  }

  public PaymentView applyResult(String id, ChannelResult result) {
    return applyResult(id, result, true, null);
  }

  public void applyResultWithoutView(String id, ChannelResult result) {
    applyResult(id, result, false, null);
  }

  public void applyObservation(
      String id, ChannelResult result, dev.merchantpay.platform.common.ReceiptEvidence evidence) {
    applyResult(id, result, false, Objects.requireNonNull(evidence));
  }

  private PaymentView applyResult(
      String id,
      ChannelResult result,
      boolean resultView,
      dev.merchantpay.platform.common.ReceiptEvidence evidence) {
    return transactions.execute(
        transaction -> {
          var current = lock(id);
          if (evidence != null) {
            boolean closeIntent =
                "CLOSED".equals(evidence.status())
                    && !jdbc.queryForList(
                            "SELECT id FROM outbox_events WHERE event_id=?", "channel-close:" + id)
                        .isEmpty();
            evidence.verify(current.amount(), current.channel(), closeIntent);
          }
          if (!current.channel().equals(result.channel())) {
            throw evidenceConflict();
          }
          switch (result) {
            case ChannelResult.Success success -> {
              if (success.amountMinor() != current.amount()
                  || !current.currency().equals(success.currency())
                  || success.reference() == null
                  || success.reference().isBlank()
                  || success.reference().length() > 128) {
                throw evidenceConflict();
              }
              if (current.status().equals("SUCCEEDED")) {
                if (!Objects.equals(current.reference(), success.reference())) {
                  throw evidenceConflict();
                }
                return current.view();
              }
              if (current.status().equals("FAILED") || current.status().equals("CLOSED")) {
                throw evidenceConflict();
              }
              var now = clock.instant();
              try {
                jdbc.update(
                    "UPDATE payments SET status='SUCCEEDED',channel_reference=?,updated_at=? WHERE id=?",
                    success.reference(),
                    Timestamp.from(now),
                    id);
              } catch (DuplicateKeyException reusedReceipt) {
                // This UPDATE changes only one unique key: (channel, channel_reference).
                throw evidenceConflict();
              }
              ledger.postPayment(id, current.merchantId(), current.amount(), current.fee(), now);
              outbox.enqueue(
                  "payment-success:" + id, "PAYMENT_SUCCEEDED", id, current.merchantId());
              releaseCapacity(id);
            }
            case ChannelResult.Failure ignored -> {
              if (terminal(current.status()) && !current.status().equals("FAILED"))
                throw evidenceConflict();
              if (!terminal(current.status())) {
                transition(id, "FAILED");
                outbox.enqueue("payment-failed:" + id, "PAYMENT_FAILED", id, current.merchantId());
                releaseCapacity(id);
              }
            }
            case ChannelResult.Closed ignored -> {
              if (terminal(current.status()) && !current.status().equals("CLOSED"))
                throw evidenceConflict();
              if (!terminal(current.status())) {
                transition(id, "CLOSED");
                outbox.enqueue("payment-closed:" + id, "PAYMENT_CLOSED", id, current.merchantId());
                releaseCapacity(id);
              }
            }
            case ChannelResult.Unknown ignored -> {
              if (current.status().equals("CLOSE_PENDING")) {
                jdbc.update(
                    "UPDATE payments SET unknown_since=COALESCE(unknown_since,?) WHERE id=?",
                    Timestamp.from(clock.instant()),
                    id);
              }
              if (!terminal(current.status())
                  && !current.status().equals("CLOSE_PENDING")
                  && !current.status().equals("UNKNOWN")) {
                transition(id, "UNKNOWN");
                outbox.fact(
                    "payment-unknown:" + id, "PAYMENT_STATE_CHANGED", id, current.merchantId());
              }
            }
            case ChannelResult.Pending ignored -> {
              /* A pending observation cannot undo a stronger observation. */
            }
          }
          return resultView ? lock(id).view() : null;
        });
  }

  public PaymentView requestClose(String merchantId, String id) {
    return transactions.execute(
        transaction -> {
          var current = lock(id);
          if (!current.merchantId().equals(merchantId)) {
            throw new ApiException("PAYMENT_NOT_FOUND", 404, "支付单不存在");
          }
          if (current.status().equals("CLOSED") || current.status().equals("CLOSE_PENDING")) {
            return current.view();
          }
          if (terminal(current.status())) {
            throw new ApiException("STATE_CONFLICT", 409, "该支付状态不能关闭");
          }
          transition(id, "CLOSE_PENDING");
          String eventId = "channel-close:" + id;
          if (jdbc.queryForList("SELECT id FROM outbox_events WHERE event_id=?", eventId)
              .isEmpty()) {
            outbox.enqueue(eventId, "CHANNEL_CLOSE", id, merchantId);
          }
          return lock(id).view();
        });
  }

  private void transition(String id, String status) {
    if (status.equals("UNKNOWN")) {
      var now = Timestamp.from(clock.instant());
      jdbc.update(
          "UPDATE payments SET status='UNKNOWN',updated_at=?,unknown_since=COALESCE(unknown_since,?) WHERE id=?",
          now,
          now,
          id);
      return;
    }
    jdbc.update(
        "UPDATE payments SET status=?,updated_at=? WHERE id=?",
        status,
        Timestamp.from(clock.instant()),
        id);
  }

  private void releaseCapacity(String id) {
    // Called only after locking a nonterminal payment, inside its outcome/ledger transaction.
    if (jdbc.update(
            admissionSlots
                ? "UPDATE payment_admission_slots SET payment_id=NULL WHERE payment_id=?"
                : "UPDATE payment_admission SET inflight=inflight-1 WHERE logical_shard=(SELECT logical_shard FROM payments WHERE id=?) AND inflight>0",
            id)
        != 1) {
      throw new IllegalStateException("Payment capacity accounting is inconsistent");
    }
  }

  private boolean reserveCapacity(int bucket, String id) {
    if (!admissionSlots)
      return jdbc.update(
              "UPDATE payment_admission SET inflight=inflight+1 WHERE logical_shard=? AND inflight<?",
              bucket,
              maximumPerShard)
          == 1;
    // Physical slot ownership remains unique; borrowing changes isolation, not the global bound.
    for (int offset = 0; offset < (borrowSlots ? 16 : 1); offset++) {
      int physicalBucket = (bucket + offset) & 15;
      var free =
          jdbc.queryForList(
              "SELECT slot_no FROM payment_admission_slots WHERE logical_shard=? AND slot_no<? AND payment_id IS NULL ORDER BY slot_no LIMIT 1 FOR UPDATE SKIP LOCKED",
              Integer.class,
              physicalBucket,
              maximumPerShard);
      if (!free.isEmpty()) {
        return jdbc.update(
                "UPDATE payment_admission_slots SET payment_id=? WHERE logical_shard=? AND slot_no=? AND payment_id IS NULL",
                id,
                physicalBucket,
                free.getFirst())
            == 1;
      }
    }
    return false;
  }

  private static boolean terminal(String status) {
    return status.equals("SUCCEEDED") || status.equals("FAILED") || status.equals("CLOSED");
  }

  private static PaymentView idempotent(Control current, String hash) {
    if (!current.requestHash().equals(hash)) {
      throw new ApiException("IDEMPOTENCY_CONFLICT", 409, "幂等业务号已用于不同的支付参数");
    }
    return current.view();
  }

  public static void requireBusinessNo(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 100
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new ApiException("INVALID_BUSINESS_NO", 400, "业务号不能为空、包含控制字符或超过 100 字符");
    }
  }

  private static ApiException evidenceConflict() {
    return new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "渠道证据与原业务不一致，需要查证");
  }

  public static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
