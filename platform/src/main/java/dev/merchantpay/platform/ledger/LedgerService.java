package dev.merchantpay.platform.ledger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LedgerService {
  private final JdbcTemplate jdbc;

  public LedgerService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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

  private void post(
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
    String journalId = "j_" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ledger_journals(id,event_id,payment_id,merchant_id,source_type,source_id,currency,created_at) VALUES (?,?,?,?,?,?,?,?)",
        journalId,
        eventId,
        paymentId,
        merchantId,
        sourceType,
        sourceId,
        "CNY",
        Timestamp.from(time));
    var nonzero = entries.stream().filter(entry -> entry.amount() != 0).toList();
    jdbc.batchUpdate(
        "INSERT INTO ledger_entries(journal_id,payment_id,merchant_id,account_code,side,amount_minor,currency) VALUES (?,?,?,?,?,?,?)",
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
  }
}
