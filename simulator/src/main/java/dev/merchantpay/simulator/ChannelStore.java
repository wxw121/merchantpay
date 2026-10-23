package dev.merchantpay.simulator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChannelStore {
  public record Request(
      String aggregateId,
      String paymentId,
      String merchantId,
      String businessNo,
      String amountMinor,
      String currency,
      java.time.Instant businessCreatedAt) {}

  public record Receipt(
      String aggregateId,
      String operation,
      String channel,
      String status,
      String reference,
      String amountMinor,
      String currency,
      String feeMinor) {}

  private static final RowMapper<Receipt> MAPPER =
      (rs, row) ->
          new Receipt(
              rs.getString("aggregate_id"),
              rs.getString("operation"),
              rs.getString("channel"),
              rs.getString("status"),
              rs.getString("reference"),
              rs.getString("amount_minor"),
              rs.getString("currency"),
              rs.getString("fee_minor"));
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final StandardProfile profile;

  public ChannelStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this(jdbc, transactions, new StandardProfile("demo", 20261019));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ChannelStore(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      @org.springframework.beans.factory.annotation.Value("${merchantpay.standard-profile:demo}")
          String mode,
      @org.springframework.beans.factory.annotation.Value("${merchantpay.standard-seed:20261019}")
          long seed) {
    this(jdbc, transactions, new StandardProfile(mode, seed));
  }

  public ChannelStore(
      JdbcTemplate jdbc, TransactionTemplate transactions, StandardProfile profile) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.profile = profile;
  }

  public StandardProfile profile() {
    return profile;
  }

  public Receipt pay(String channel, Request request) {
    validate(channel, request);
    return transactions.execute(
        tx -> {
          // Both creation and lookup lock the channel's stable request key, independently of the
          // platform DB.
          jdbc.update(
              "MERGE INTO channel_locks(channel,aggregate_id) KEY(channel,aggregate_id) VALUES (?,?)",
              channel,
              request.aggregateId());
          jdbc.queryForList(
              "SELECT * FROM channel_locks WHERE channel=? AND aggregate_id=? FOR UPDATE",
              channel,
              request.aggregateId());
          var existing = find(channel, "PAYMENT", request.aggregateId());
          if (!existing.isEmpty()) {
            if (!existing.getFirst().status().equals("CLOSED")) {
              requireSame(channel, "PAYMENT", request);
            }
            return existing.getFirst();
          }
          String state = profile.status(request.businessNo());
          insert(
              channel,
              "PAYMENT",
              request,
              state,
              state.equals("SUCCEEDED") ? fee(Long.parseLong(request.amountMinor())) : 0);
          return query(channel, "PAYMENT", request.aggregateId());
        });
  }

  public Receipt refund(String channel, Request request) {
    validate(channel, request);
    return transactions.execute(
        tx -> {
          var roots =
              jdbc.queryForList(
                  "SELECT * FROM receipts WHERE channel=? AND operation='PAYMENT' AND aggregate_id=? FOR UPDATE",
                  channel,
                  request.paymentId());
          var existing = find(channel, "REFUND", request.aggregateId());
          if (!existing.isEmpty()) {
            requireSame(channel, "REFUND", request);
            return existing.getFirst();
          }
          long amount = Long.parseLong(request.amountMinor());
          String state = "FAILED";
          long feeDelta = 0;
          if (!roots.isEmpty()) {
            var root = roots.getFirst();
            long original = ((Number) root.get("amount_minor")).longValue();
            long refunded = ((Number) root.get("refunded_minor")).longValue();
            if (root.get("merchant_id").equals(request.merchantId())
                && root.get("status").equals("SUCCEEDED")
                && amount <= original - refunded
                && !profile.status(request.businessNo()).equals("FAILED")) {
              state = "SUCCEEDED";
              long cumulativeFee =
                  BigDecimal.valueOf(((Number) root.get("fee_minor")).longValue())
                      .multiply(BigDecimal.valueOf(refunded + amount))
                      .divide(BigDecimal.valueOf(original), 0, RoundingMode.HALF_UP)
                      .longValueExact();
              feeDelta = cumulativeFee - ((Number) root.get("refunded_fee_minor")).longValue();
              jdbc.update(
                  "UPDATE receipts SET refunded_minor=?,refunded_fee_minor=? WHERE channel=? AND operation='PAYMENT' AND aggregate_id=?",
                  refunded + amount,
                  cumulativeFee,
                  channel,
                  request.paymentId());
            }
          }
          insert(channel, "REFUND", request, state, feeDelta);
          return query(channel, "REFUND", request.aggregateId());
        });
  }

  public Receipt close(String channel, String id) {
    return transactions.execute(
        tx -> {
          jdbc.update(
              "MERGE INTO channel_locks(channel,aggregate_id) KEY(channel,aggregate_id) VALUES (?,?)",
              channel,
              id);
          jdbc.queryForList(
              "SELECT * FROM channel_locks WHERE channel=? AND aggregate_id=? FOR UPDATE",
              channel,
              id);
          jdbc.queryForList(
              "SELECT * FROM receipts WHERE channel=? AND operation='PAYMENT' AND aggregate_id=? FOR UPDATE",
              channel,
              id);
          if (find(channel, "PAYMENT", id).isEmpty()) {
            jdbc.update(
                "INSERT INTO receipts(channel,operation,aggregate_id,payment_id,merchant_id,business_no,request_hash,amount_minor,currency,status,reference,fee_minor,notified) VALUES (?,'PAYMENT',?,?,'UNASSIGNED','close-before-submit','closed',0,'CNY','CLOSED',?,0,TRUE)",
                channel,
                id,
                id,
                "mock-close-" + UUID.randomUUID());
          }
          jdbc.update(
              "UPDATE receipts SET status='CLOSED',notified=FALSE WHERE channel=? AND operation='PAYMENT' AND aggregate_id=? AND status='PENDING'",
              channel,
              id);
          return query(channel, "PAYMENT", id);
        });
  }

  public Receipt query(String channel, String operation, String id) {
    var rows = find(channel, operation, id);
    return rows.isEmpty()
        ? new Receipt(id, operation, channel, "NOT_FOUND", "", "0", "CNY", "0")
        : rows.getFirst();
  }

  public List<Receipt> pendingNotifications() {
    return jdbc.query(
        "SELECT * FROM receipts WHERE notified=FALSE AND status<>'PENDING' ORDER BY created_at LIMIT "
            + (profile.enabled() ? 2048 : 100),
        MAPPER);
  }

  public int remainingNotifications(Receipt receipt) {
    return jdbc.queryForObject(
        "SELECT expected_callbacks-callback_deliveries FROM receipts WHERE channel=? AND operation=? AND aggregate_id=?",
        Integer.class,
        receipt.channel(),
        receipt.operation(),
        receipt.aggregateId());
  }

  public List<String> evidence(
      String channel, String operation, String prefix, String after, int limit) {
    if (!java.util.Set.of("MOCK_A", "MOCK_B").contains(channel)
        || !java.util.Set.of("PAYMENT", "REFUND").contains(operation)
        || !prefix.matches("[a-zA-Z0-9-]{1,80}")
        || limit < 1
        || limit > 1000) {
      throw new IllegalArgumentException("Invalid evidence page");
    }
    return jdbc.query(
        "SELECT aggregate_id,merchant_id,business_no,status,amount_minor,fee_minor FROM receipts WHERE channel=? AND operation=? AND business_no LIKE ? AND aggregate_id>? ORDER BY aggregate_id LIMIT ?",
        (rows, index) ->
            String.join(
                "\t",
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5),
                rows.getString(6)),
        channel,
        operation,
        prefix + "%",
        after,
        limit);
  }

  public record Health(
      String channel, long pendingCallbacks, long pendingPayments, String oldestCallbackAt) {}

  public Health health(String channel, String merchant) {
    if (!java.util.Set.of("MOCK_A", "MOCK_B").contains(channel)) {
      throw new IllegalArgumentException("Unknown channel");
    }
    var args = new java.util.ArrayList<Object>();
    args.add(channel);
    String filter = "";
    if (merchant != null) {
      filter = " AND merchant_id=?";
      args.add(merchant);
    }
    long pendingCallbacks =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipts WHERE channel=? AND notified=FALSE AND status<>'PENDING'"
                + filter,
            Long.class,
            args.toArray());
    long pendingPayments =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipts WHERE channel=? AND status='PENDING'" + filter,
            Long.class,
            args.toArray());
    var oldest =
        jdbc.queryForObject(
            "SELECT MIN(created_at) FROM receipts WHERE channel=? AND notified=FALSE AND status<>'PENDING'"
                + filter,
            java.sql.Timestamp.class,
            args.toArray());
    return new Health(
        channel,
        pendingCallbacks,
        pendingPayments,
        oldest == null ? "" : oldest.toInstant().toString());
  }

  public void notified(Receipt receipt) {
    jdbc.update(
        "UPDATE receipts SET callback_deliveries=callback_deliveries+1,notified=(callback_deliveries+1>=expected_callbacks) WHERE channel=? AND operation=? AND aggregate_id=? AND notified=FALSE",
        receipt.channel(),
        receipt.operation(),
        receipt.aggregateId());
  }

  public boolean consumeLostResponse(String channel, String operation, String id) {
    return jdbc.update(
            "UPDATE receipts SET lost_response_sent=TRUE WHERE channel=? AND operation=? AND aggregate_id=? AND lost_response_sent=FALSE",
            channel,
            operation,
            id)
        == 1;
  }

  public List<Receipt> bill(
      String channel, String merchant, java.time.Instant from, java.time.Instant until) {
    return jdbc.query(
        "SELECT * FROM receipts WHERE channel=? AND merchant_id=? AND business_created_at>=? AND business_created_at<? ORDER BY business_created_at,operation,aggregate_id",
        MAPPER,
        channel,
        merchant,
        java.sql.Timestamp.from(from),
        java.sql.Timestamp.from(until));
  }

  private List<Receipt> find(String channel, String operation, String id) {
    return jdbc.query(
        "SELECT * FROM receipts WHERE channel=? AND operation=? AND aggregate_id=?",
        MAPPER,
        channel,
        operation,
        id);
  }

  private void requireSame(String channel, String operation, Request request) {
    var hash =
        jdbc.queryForObject(
            "SELECT request_hash FROM receipts WHERE channel=? AND operation=? AND aggregate_id=?",
            String.class,
            channel,
            operation,
            request.aggregateId());
    if (!hash(request).equals(hash)) {
      throw new IllegalArgumentException("Channel idempotency conflict");
    }
  }

  private void insert(String channel, String operation, Request request, String state, long fee) {
    jdbc.update(
        "INSERT INTO receipts(channel,operation,aggregate_id,payment_id,merchant_id,business_no,request_hash,amount_minor,currency,status,reference,fee_minor,business_created_at,expected_callbacks) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        channel,
        operation,
        request.aggregateId(),
        request.paymentId(),
        request.merchantId(),
        request.businessNo(),
        hash(request),
        Long.parseLong(request.amountMinor()),
        "CNY",
        state,
        "mock-" + UUID.randomUUID(),
        fee,
        java.sql.Timestamp.from(request.businessCreatedAt()),
        state.equals("SUCCEEDED") && profile.duplicate(request.businessNo()) ? 2 : 1);
  }

  private static void validate(String channel, Request request) {
    if (!("MOCK_A".equals(channel) || "MOCK_B".equals(channel))
        || request == null
        || !"CNY".equals(request.currency())
        || request.amountMinor() == null
        || !request.amountMinor().matches("[1-9][0-9]{0,9}")
        || Long.parseLong(request.amountMinor()) > 1_000_000_000L
        || request.aggregateId() == null
        || request.aggregateId().length() > 64
        || request.merchantId() == null
        || request.businessNo() == null
        || request.businessCreatedAt() == null) {
      throw new IllegalArgumentException("Invalid mock channel request");
    }
  }

  private static long fee(long amount) {
    return BigDecimal.valueOf(amount)
        .multiply(new BigDecimal("0.006"))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private static String hash(Request request) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (request.aggregateId()
                              + "\0"
                              + request.paymentId()
                              + "\0"
                              + request.merchantId()
                              + "\0"
                              + request.businessNo()
                              + "\0"
                              + request.amountMinor()
                              + "\0"
                              + request.currency())
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
