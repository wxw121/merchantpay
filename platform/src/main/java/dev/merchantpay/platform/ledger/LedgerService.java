package dev.merchantpay.platform.ledger;

import dev.merchantpay.platform.common.TimeOrderedIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LedgerService {
  private final JdbcTemplate jdbc;
  private final boolean orderedIds;

  public LedgerService(JdbcTemplate jdbc) {
    this(jdbc, false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public LedgerService(
      JdbcTemplate jdbc, @Value("${merchantpay.ledger-ordered-ids:false}") boolean orderedIds) {
    this.jdbc = jdbc;
    this.orderedIds = orderedIds;
  }

  public record Entry(String account, String side, long amount) {}

  public void postPayment(
      String paymentId, String merchantId, long amount, long fee, Instant time) {
    post(
        "payment-success:" + paymentId,
        paymentId,
        merchantId,
        "PAYMENT",
        paymentId,
        time,
        List.of(
            new Entry("CHANNEL_RECEIVABLE", "DEBIT", amount),
            new Entry("MERCHANT_PAYABLE", "CREDIT", amount - fee),
            new Entry("FEE_REVENUE", "CREDIT", fee)));
  }

  public void postRefund(
      String refundId, String paymentId, String merchantId, long amount, long fee, Instant time) {
    post(
        "refund-success:" + refundId,
        paymentId,
        merchantId,
        "REFUND",
        refundId,
        time,
        List.of(
            new Entry("MERCHANT_PAYABLE", "DEBIT", amount - fee),
            new Entry("FEE_REVENUE", "DEBIT", fee),
            new Entry("CHANNEL_RECEIVABLE", "CREDIT", amount)));
  }

  public String postAdjustment(
      String adjustmentId,
      String paymentId,
      String merchantId,
      boolean increase,
      long amount,
      Instant time) {
    if (amount < 1 || amount > dev.merchantpay.platform.common.MoneyRules.MAX_PAYMENT)
      throw new IllegalArgumentException("Invalid adjustment amount");
    return post(
        "adjustment-executed:" + adjustmentId,
        paymentId,
        merchantId,
        "ADJUSTMENT",
        adjustmentId,
        time,
        List.of(
            new Entry("MERCHANT_PAYABLE", increase ? "CREDIT" : "DEBIT", amount),
            new Entry("ADJUSTMENT_CLEARING", increase ? "DEBIT" : "CREDIT", amount)));
  }

  public String postSettlement(String settlementId, String merchantId, long amount, Instant time) {
    if (amount < 1) throw new IllegalArgumentException("Settlement must be positive");
    return post(
        "settlement-paid:" + settlementId,
        null,
        merchantId,
        "SETTLEMENT",
        settlementId,
        time,
        List.of(
            new Entry("MERCHANT_PAYABLE", "DEBIT", amount),
            new Entry("SIMULATED_SETTLEMENT_CLEARING", "CREDIT", amount)));
  }

  private String post(
      String eventId,
      String paymentId,
      String merchantId,
      String sourceType,
      String sourceId,
      Instant time,
      List<Entry> entries) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Ledger posting must share the business transaction");
    }
    long balance = 0;
    for (var entry : entries) {
      if (entry.amount() < 0 || !(entry.side().equals("DEBIT") || entry.side().equals("CREDIT"))) {
        throw new IllegalArgumentException("Invalid ledger entry");
      }
      balance =
          Math.addExact(balance, entry.side().equals("DEBIT") ? entry.amount() : -entry.amount());
    }
    if (balance != 0) {
      throw new IllegalStateException("Journal is not balanced");
    }
    String journalId = "j_" + (orderedIds ? TimeOrderedIds.uuidV7(time) : UUID.randomUUID());
    jdbc.update(
        "INSERT INTO ledger_journals(id,event_id,payment_id,merchant_id,source_type,source_id,currency,created_at) VALUES (?,?,?,?,?,?,?,?)",
        journalId,
        eventId,
        paymentId,
        merchantId,
        sourceType,
        sourceId,
        "CNY",
        Timestamp.from(time.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    var nonzero = entries.stream().filter(entry -> entry.amount() != 0).toList();
    jdbc.batchUpdate(
        sourceType.equals("SETTLEMENT")
            ? "INSERT INTO ledger_entries(journal_id,payment_id,merchant_id,account_code,side,amount_minor,currency,entry_kind) VALUES (?,?,?,?,?,?,?,'SETTLEMENT')"
            : "INSERT INTO ledger_entries(journal_id,payment_id,merchant_id,account_code,side,amount_minor,currency) VALUES (?,?,?,?,?,?,?)",
        nonzero,
        nonzero.size(),
        (statement, entry) -> {
          statement.setString(1, journalId);
          statement.setString(2, paymentId);
          statement.setString(3, merchantId);
          statement.setString(4, entry.account());
          statement.setString(5, entry.side());
          statement.setLong(6, entry.amount());
          statement.setString(7, "CNY");
        });
    return journalId;
  }
}
