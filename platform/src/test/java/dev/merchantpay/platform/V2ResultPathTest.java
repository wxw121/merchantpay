package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class V2ResultPathTest extends FinancialFixture {
  @Test
  void workerSuccessLocksRootOnceAndKeepsFullEvidenceValidation() {
    var payment = createPayment("worker-result", "1000");
    var queries = new ArrayList<String>();
    var counted =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            queries.add(sql);
            return super.query(sql, mapper, args);
          }
        };
    var service =
        new PaymentService(
            counted,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            new LedgerService(jdbc),
            new OutboxService(jdbc, Clock.systemUTC()),
            Clock.systemUTC(),
            16);
    service.applyObservation(
        payment.id(),
        new ChannelResult.Success("MOCK_A", "receipt", 1000, "CNY"),
        new ReceiptEvidence("PAYMENT", "SUCCEEDED", "MOCK_A", "1000", "6", "CNY", "receipt"));
    assertThat(
            queries.stream()
                .filter(s -> s.equals("SELECT * FROM payments WHERE id=? FOR UPDATE"))
                .count())
        .isEqualTo(1);
    assertThat(payments.get("merchant-a", payment.id()).status()).isEqualTo("SUCCEEDED");
    assertThatThrownBy(
            () ->
                service.applyObservation(
                    payment.id(),
                    new ChannelResult.Failure("MOCK_A", "declined"),
                    new ReceiptEvidence("PAYMENT", "FAILED", "MOCK_A", "999", "0", "CNY", "x")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void legacyViewApiStillReturnsTheCommittedState() {
    var p = createPayment("view-result", "1000");
    assertThat(
            payments
                .applyResult(
                    p.id(), new ChannelResult.Success("MOCK_A", "view-receipt", 1000, "CNY"))
                .status())
        .isEqualTo("SUCCEEDED");
  }

  @Test
  void zeroAmountCloseRequiresRecordedMockIntent() {
    assertThatThrownBy(
            () ->
                new ReceiptEvidence("PAYMENT", "CLOSED", "MOCK_A", "0", "0", "CNY", "mock-close-x")
                    .verify(1000, "MOCK_A", false))
        .isInstanceOf(ApiException.class);
    assertThatCode(
            () ->
                new ReceiptEvidence("PAYMENT", "CLOSED", "MOCK_A", "0", "0", "CNY", "mock-close-x")
                    .verify(1000, "MOCK_A", true))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new ReceiptEvidence(
                        "PAYMENT", "CLOSED", "ALIPAY_SANDBOX", "0", "0", "CNY", "mock-close-x")
                    .verify(1000, "ALIPAY_SANDBOX", true))
        .isInstanceOf(ApiException.class);
  }
}
