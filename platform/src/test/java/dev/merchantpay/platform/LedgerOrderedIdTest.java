package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerOrderedIdTest extends FinancialFixture {
  final Instant firstTime = Instant.parse("2026-10-29T00:00:00Z");

  void configure(Boolean ordered) {
    var selected = new AtomicReference<LedgerService>();
    var runner =
        new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withUserConfiguration(LedgerService.class);
    if (ordered != null)
      runner = runner.withPropertyValues("merchantpay.ledger-ordered-ids=" + ordered);
    runner.run(context -> selected.set(context.getBean(LedgerService.class)));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var clock = Clock.fixed(firstTime, ZoneOffset.UTC);
    var outbox = new OutboxService(jdbc, clock);
    payments = new PaymentService(jdbc, tx, selected.get(), outbox, clock, 16);
    refunds =
        new RefundService(
            jdbc,
            tx,
            payments,
            selected.get(),
            outbox,
            Clock.fixed(firstTime.plusMillis(1), ZoneOffset.UTC));
  }

  String journal(String event) {
    return jdbc.queryForObject(
        "SELECT id FROM ledger_journals WHERE event_id=?", String.class, event);
  }

  UUID uuid(String id) {
    assertThat(id).startsWith("j_");
    return UUID.fromString(id.substring(2));
  }

  @Test
  void orderedPaymentAndRefundJournalsKeepFactsAndEntriesLinkedAlongsideLegacyRows() {
    var legacy = paid("legacy-journal", "10000");
    var legacyId = journal("payment-success:" + legacy.id());
    assertThat(uuid(legacyId).version()).isEqualTo(4);
    configure(true);
    var payment = paid("ordered-journal", "10000");
    var refund =
        refunds.create(
            "merchant-a", payment.id(), new RefundService.CreateRefund("ordered-refund", "1000"));
    var result = new ChannelResult.Success("MOCK_A", "ordered-refund-receipt", 1000, "CNY");
    refunds.applyResult(refund.id(), result);
    refunds.applyResult(refund.id(), result);
    payments.applyResult(
        payment.id(), new ChannelResult.Success("MOCK_A", "channel-ordered-journal", 10000, "CNY"));

    String paymentJournal = journal("payment-success:" + payment.id());
    String refundJournal = journal("refund-success:" + refund.id());
    assertThat(uuid(paymentJournal).version()).isEqualTo(7);
    assertThat(uuid(refundJournal).version()).isEqualTo(7);
    assertThat(uuid(paymentJournal).getMostSignificantBits() >>> 16)
        .isEqualTo(firstTime.toEpochMilli());
    assertThat(uuid(refundJournal).getMostSignificantBits() >>> 16)
        .isEqualTo(firstTime.plusMillis(1).toEpochMilli());
    assertThat(paymentJournal).isLessThan(refundJournal);
    assertThat(journal("payment-success:" + legacy.id())).isEqualTo(legacyId);
    assertThat(count("ledger_journals")).isEqualTo(3);
    assertThat(count("ledger_entries")).isEqualTo(9);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries e LEFT JOIN ledger_journals j ON j.id=e.journal_id WHERE j.id IS NULL OR e.payment_id<>j.payment_id OR e.merchant_id<>j.merchant_id OR e.currency<>j.currency",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT amount_minor FROM ledger_entries WHERE journal_id=? AND account_code='MERCHANT_PAYABLE'",
                Long.class,
                paymentJournal))
        .isEqualTo(9940);
    assertThat(
            jdbc.queryForObject(
                "SELECT amount_minor FROM ledger_entries WHERE journal_id=? AND account_code='MERCHANT_PAYABLE'",
                Long.class,
                refundJournal))
        .isEqualTo(994);
    assertThat(payments.get("merchant-a", payment.id()).refundedMinor()).isEqualTo("1000");
    assertThat(payments.get("merchant-a", payment.id()).refundedFeeMinor()).isEqualTo("6");
  }

  @Test
  void springDefaultKeepsRandomJournalIds() {
    configure(null);
    var payment = paid("default-journal", "10000");
    assertThat(uuid(journal("payment-success:" + payment.id())).version()).isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void journalAndEntriesRollBackWithTheBusinessEvent(boolean ordered) {
    configure(ordered);
    var payment = createPayment("ordered-rollback", "10000");
    jdbc.execute(
        "ALTER TABLE outbox_tasks ADD CONSTRAINT reject_success CHECK (kind <> 'PAYMENT_SUCCEEDED')");
    assertThatThrownBy(
            () ->
                payments.applyResult(
                    payment.id(),
                    new ChannelResult.Success("MOCK_A", "rollback-result", 10000, "CNY")))
        .isInstanceOf(RuntimeException.class);
    assertThat(count("ledger_journals")).isZero();
    assertThat(count("ledger_entries")).isZero();
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("PROCESSING");
  }
}
