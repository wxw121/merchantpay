package dev.merchantpay.simulator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class ChannelStoreTest {
  @TempDir Path directory;

  ChannelStore open() {
    return open(new StandardProfile("demo", 20261019));
  }

  ChannelStore open(StandardProfile profile) {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:file:" + directory.resolve("channel") + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    return new ChannelStore(
        new JdbcTemplate(source),
        new TransactionTemplate(new DataSourceTransactionManager(source)),
        profile);
  }

  @Test
  void standardTruthDuplicateDeliveryAndBoundedEvidenceSurviveRestart() {
    var profile = new StandardProfile("standard-v1", 20261019);
    var store = open(profile);
    String business =
        java.util.stream.IntStream.range(0, 1000)
            .mapToObj(i -> "acceptance-standard-" + i)
            .filter(key -> profile.status(key).equals("SUCCEEDED") && profile.duplicate(key))
            .findFirst()
            .orElseThrow();
    var input = request("standard-payment", business, "10000");
    var receipt = store.pay("MOCK_A", input);
    assertThat(receipt.status()).isEqualTo("SUCCEEDED");
    assertThat(store.remainingNotifications(receipt)).isEqualTo(2);
    store.notified(receipt);
    var restored = open(profile);
    assertThat(restored.pay("MOCK_A", input)).isEqualTo(receipt);
    assertThat(restored.remainingNotifications(receipt)).isEqualTo(1);
    restored.notified(receipt);
    assertThat(restored.pendingNotifications()).isEmpty();
    assertThat(restored.evidence("MOCK_A", "PAYMENT", "acceptance-", "", 1))
        .singleElement()
        .asString()
        .contains("standard-payment\tmerchant-a\t" + business + "\tSUCCEEDED\t10000\t60");
    assertThat(restored.evidence("MOCK_B", "PAYMENT", "acceptance-", "", 1)).isEmpty();
    assertThat(restored.evidence("MOCK_A", "PAYMENT", "acceptance-", "standard-payment", 1))
        .isEmpty();
    assertThatThrownBy(() -> restored.evidence("MOCK_A", "PAYMENT", "%", "", 1000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> restored.evidence("MOCK_A", "PAYMENT", "acceptance-", "", 1001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  ChannelStore.Request request(String id, String order, String amount) {
    return new ChannelStore.Request(
        id,
        id,
        "merchant-a",
        order,
        amount,
        "CNY",
        java.time.Instant.parse("2026-10-18T00:00:00Z"));
  }

  @Test
  void receiptSurvivesReopeningAndBusinessKeyDoesNotChargeTwice() {
    var input = request("p1", "order-1", "10000");
    var first = open().pay("MOCK_A", input);
    var restored = open();
    assertThat(restored.pay("MOCK_A", input)).isEqualTo(first);
    assertThat(restored.query("MOCK_A", "PAYMENT", "p1").status()).isEqualTo("SUCCEEDED");
    assertThatThrownBy(() -> restored.pay("MOCK_A", request("p1", "order-1", "10001")))
        .isInstanceOf(RuntimeException.class);
    assertThat(restored.query("MOCK_B", "PAYMENT", "p1").status()).isEqualTo("NOT_FOUND");
  }

  @Test
  void closeAndRefundObserveAuthoritativeChannelState() {
    var store = open();
    store.pay("MOCK_A", request("p1", "order", "10000"));
    assertThat(store.close("MOCK_A", "p1").status()).isEqualTo("SUCCEEDED");
    var refund =
        new ChannelStore.Request(
            "r1",
            "p1",
            "merchant-a",
            "r1",
            "8000",
            "CNY",
            java.time.Instant.parse("2026-10-18T00:00:00Z"));
    assertThat(store.refund("MOCK_A", refund).status()).isEqualTo("SUCCEEDED");
    assertThat(store.refund("MOCK_A", refund).status()).isEqualTo("SUCCEEDED");
    assertThat(
            store
                .refund(
                    "MOCK_A",
                    new ChannelStore.Request(
                        "r2",
                        "p1",
                        "merchant-a",
                        "r2",
                        "3000",
                        "CNY",
                        java.time.Instant.parse("2026-10-18T00:00:00Z")))
                .status())
        .isEqualTo("FAILED");
    store.pay("MOCK_B", request("p2", "pending:order", "100"));
    assertThat(store.close("MOCK_B", "p2").status()).isEqualTo("CLOSED");
  }

  @Test
  void closingAnUnsentPaymentPreventsALateSubmissionFromCharging() {
    var store = open();
    assertThat(store.close("MOCK_A", "unsent").status()).isEqualTo("CLOSED");
    assertThat(store.pay("MOCK_A", request("unsent", "late-submit", "10000")).status())
        .isEqualTo("CLOSED");
  }

  @Test
  void delayedPaymentAndRefundRetainTheirOriginalBusinessDayAcrossMidnight() {
    var store = open();
    var accepted = java.time.Instant.parse("2026-10-17T15:59:59Z");
    store.pay(
        "MOCK_A",
        new ChannelStore.Request(
            "midnight-p", "midnight-p", "merchant-a", "midnight", "10000", "CNY", accepted));
    store.refund(
        "MOCK_A",
        new ChannelStore.Request(
            "midnight-r", "midnight-p", "merchant-a", "midnight-r", "3000", "CNY", accepted));
    var from = java.time.Instant.parse("2026-10-16T16:00:00Z");
    var until = java.time.Instant.parse("2026-10-17T16:00:00Z");
    assertThat(store.bill("MOCK_A", "merchant-a", from, until))
        .extracting(ChannelStore.Receipt::aggregateId)
        .containsExactlyInAnyOrder("midnight-p", "midnight-r");
    assertThat(store.bill("MOCK_A", "merchant-a", until, until.plusSeconds(86400))).isEmpty();
  }

  @Test
  void lostResponseCanOnlyBeConsumedOnceAcrossReopening() {
    var input = request("p1", "lost:order", "100");
    var store = open();
    assertThat(store.consumeLostResponse("MOCK_A", "PAYMENT", "p1")).isFalse();
    var receipt = store.pay("MOCK_A", input);
    assertThat(store.consumeLostResponse("MOCK_A", "PAYMENT", "p1")).isTrue();

    var restored = open();
    assertThat(restored.consumeLostResponse("MOCK_A", "PAYMENT", "p1")).isFalse();
    assertThat(restored.pay("MOCK_A", input)).isEqualTo(receipt);
  }

  @Test
  void rejectedRefundsDoNotConsumeTheAvailableRefundBalance() {
    var store = open();
    store.pay("MOCK_A", request("paid", "order-1", "100"));
    store.pay("MOCK_A", request("pending", "pending:order-2", "100"));
    var created = Instant.parse("2026-10-18T00:00:00Z");
    var missingPayment =
        new ChannelStore.Request("r1", "missing", "merchant-a", "refund-1", "100", "CNY", created);
    var wrongMerchant =
        new ChannelStore.Request("r2", "paid", "merchant-b", "refund-2", "100", "CNY", created);
    var pendingPayment =
        new ChannelStore.Request("r3", "pending", "merchant-a", "refund-3", "100", "CNY", created);
    var declined =
        new ChannelStore.Request(
            "r4", "paid", "merchant-a", "fail:refund-4", "100", "CNY", created);

    for (var refund : java.util.List.of(missingPayment, wrongMerchant, pendingPayment, declined)) {
      assertThat(store.refund("MOCK_A", refund))
          .satisfies(
              receipt -> {
                assertThat(receipt.status()).isEqualTo("FAILED");
                assertThat(receipt.feeMinor()).isEqualTo("0");
              });
    }
    var valid =
        new ChannelStore.Request("r5", "paid", "merchant-a", "refund-5", "100", "CNY", created);
    assertThat(store.refund("MOCK_A", valid).status()).isEqualTo("SUCCEEDED");
  }

  @ParameterizedTest(name = "invalid channel request: {0}")
  @MethodSource("invalidRequests")
  void rejectsInvalidPaymentRequestsBeforeTheDatabase(
      String reason, String channel, ChannelStore.Request request) {
    var jdbc = mock(JdbcTemplate.class);
    var transactions = mock(TransactionTemplate.class);
    var store = new ChannelStore(jdbc, transactions);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.pay(channel, request))
        .withMessage("Invalid mock channel request");
    verifyNoInteractions(jdbc, transactions);
  }

  static Stream<Arguments> invalidRequests() {
    var created = Instant.parse("2026-10-18T00:00:00Z");
    return Stream.of(
        Arguments.of(
            "unknown channel",
            "OTHER",
            new ChannelStore.Request("p1", "p1", "m1", "order", "100", "CNY", created)),
        Arguments.of("missing request", "MOCK_A", null),
        invalid(
            "foreign currency",
            new ChannelStore.Request("p1", "p1", "m1", "order", "100", "USD", created)),
        invalid(
            "missing amount",
            new ChannelStore.Request("p1", "p1", "m1", "order", null, "CNY", created)),
        invalid(
            "nonpositive amount",
            new ChannelStore.Request("p1", "p1", "m1", "order", "0", "CNY", created)),
        invalid(
            "amount above the maximum",
            new ChannelStore.Request("p1", "p1", "m1", "order", "1000000001", "CNY", created)),
        invalid(
            "missing aggregate id",
            new ChannelStore.Request(null, "p1", "m1", "order", "100", "CNY", created)),
        invalid(
            "aggregate id above the maximum length",
            new ChannelStore.Request("p".repeat(65), "p1", "m1", "order", "100", "CNY", created)),
        invalid(
            "missing merchant id",
            new ChannelStore.Request("p1", "p1", null, "order", "100", "CNY", created)),
        invalid(
            "missing business number",
            new ChannelStore.Request("p1", "p1", "m1", null, "100", "CNY", created)),
        invalid(
            "missing business date",
            new ChannelStore.Request("p1", "p1", "m1", "order", "100", "CNY", null)));
  }

  private static Arguments invalid(String reason, ChannelStore.Request request) {
    return Arguments.of(reason, "MOCK_A", request);
  }

  @Test
  void rejectsUnknownHealthChannelAndInvalidEvidenceBoundsBeforeTheDatabase() {
    var jdbc = mock(JdbcTemplate.class);
    var store = new ChannelStore(jdbc, mock(TransactionTemplate.class), "standard-v1", 37);

    assertThat(store.profile().descriptor()).isEqualTo("standard-v1\t37");
    assertThatIllegalArgumentException().isThrownBy(() -> store.health("OTHER", null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.evidence("OTHER", "PAYMENT", "run", "", 10));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.evidence("MOCK_A", "OTHER", "run", "", 10));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.evidence("MOCK_A", "PAYMENT", "run", "", 0));
    verifyNoInteractions(jdbc);
  }
}
