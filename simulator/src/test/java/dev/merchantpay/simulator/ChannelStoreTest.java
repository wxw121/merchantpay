package dev.merchantpay.simulator;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
