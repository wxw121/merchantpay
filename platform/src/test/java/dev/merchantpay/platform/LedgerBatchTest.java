package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.ledger.LedgerService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerBatchTest extends FinancialFixture {
  @Test
  void oneBatchWritesOnlyNonzeroEntriesAndKeepsJournalBalanced() {
    var payment = createPayment("batch-ledger", "100");
    var tracked = spy(jdbc);
    var ledger = new LedgerService(tracked);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    tx.executeWithoutResult(
        t -> ledger.postPayment(payment.id(), "merchant-a", 100, 1, Instant.now()));
    assertThat(
            mockingDetails(tracked).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("batchUpdate"))
                .count())
        .isEqualTo(1);
    assertThat(count("ledger_entries")).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries",
                Long.class))
        .isZero();
    tx.executeWithoutResult(
        t -> ledger.postRefund("zero-fee", payment.id(), "merchant-a", 1, 0, Instant.now()));
    assertThat(count("ledger_entries")).isEqualTo(5);
  }

  @Test
  void failedEntryRollsBackJournalAndEntireBatch() {
    var payment = createPayment("batch-failure", "100");
    jdbc.execute(
        "ALTER TABLE ledger_entries ADD CONSTRAINT reject_fee CHECK(account_code<>'FEE_REVENUE')");
    var ledger = new LedgerService(jdbc);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    t -> ledger.postPayment(payment.id(), "merchant-a", 100, 1, Instant.now())))
        .isInstanceOf(RuntimeException.class);
    assertThat(count("ledger_entries")).isZero();
    assertThat(count("ledger_journals")).isZero();
  }
}
