package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.control.ChannelControl;
import dev.merchantpay.platform.payment.PaymentService.CreatePayment;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class V2ChannelControlTest extends FinancialFixture {
  final Actor ops = new Actor("operator", null, Set.of("OPS"));
  TransactionTemplate tx;
  Clock at;

  @BeforeEach
  void controlsSchema() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V20__v2_channel_controls.sql"));
    tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    at = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
  }

  ChannelControl controls(Clock clock) {
    return new ChannelControl(jdbc, tx, clock, true, false);
  }

  @Test
  void sandboxAvailabilityAndAutomaticRoutingAreIndependentCapabilities() {
    for (boolean routing : new boolean[] {false, true})
      for (boolean sandbox : new boolean[] {false, true}) {
        var control = new ChannelControl(jdbc, tx, at, routing, sandbox);
        assertThat(control.routingEnabled()).isEqualTo(routing);
        assertThat(control.supports("ALIPAY_SANDBOX")).isEqualTo(sandbox);
        assertThat(control.route("merchant-b", "MOCK_A")).isEqualTo("MOCK_A");
        if (sandbox)
          assertThat(control.route("merchant-b", "ALIPAY_SANDBOX")).isEqualTo("ALIPAY_SANDBOX");
        else
          assertThatThrownBy(() -> control.route("merchant-b", "ALIPAY_SANDBOX"))
              .isInstanceOf(ApiException.class);
        if (!routing)
          assertThatThrownBy(() -> control.route("merchant-b", "AUTO"))
              .isInstanceOf(ApiException.class);
      }
  }

  @Test
  void sandboxCanCreateExplicitPaymentsWithAutomaticRoutingDisabled() {
    payments.setChannelControl(new ChannelControl(jdbc, tx, at, false, true));
    assertThat(
            payments
                .create(
                    "merchant-a",
                    new CreatePayment("sandbox-only", "1000", "CNY", "ALIPAY_SANDBOX", null))
                .channel())
        .isEqualTo("ALIPAY_SANDBOX");
    assertThatThrownBy(
            () ->
                payments.create(
                    "merchant-a", new CreatePayment("no-auto", "1000", "CNY", "AUTO", null)))
        .isInstanceOf(ApiException.class);
    assertThat(count("payments")).isEqualTo(1);
  }

  @Test
  void disablingCapabilitiesPreservesOriginalOrdersAndRejectsNewOrChangedRequests() {
    var control = new ChannelControl(jdbc, tx, at, true, true);
    control.route("merchant-a", "MOCK_A", 0, 1, true, "initial", ops);
    payments.setChannelControl(control);
    var automatic = new CreatePayment("auto-disable", "1000", "CNY", "AUTO", null);
    var sandbox = new CreatePayment("sandbox-disable", "1000", "CNY", "ALIPAY_SANDBOX", null);
    var originalAuto = payments.create("merchant-a", automatic);
    var originalSandbox = payments.create("merchant-b", sandbox);
    payments.applyResult(originalAuto.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    for (boolean routing : new boolean[] {true, false}) {
      payments.setChannelControl(new ChannelControl(jdbc, tx, at, routing, false));
      var sameAuto = payments.create("merchant-a", automatic);
      assertThat(sameAuto.id()).isEqualTo(originalAuto.id());
      assertThat(sameAuto.channel()).isEqualTo("MOCK_A");
      assertThat(sameAuto.status()).isEqualTo("UNKNOWN");
      var sameSandbox = payments.create("merchant-b", sandbox);
      assertThat(sameSandbox.id()).isEqualTo(originalSandbox.id());
      assertThat(sameSandbox.channel()).isEqualTo("ALIPAY_SANDBOX");
      assertThatThrownBy(
              () ->
                  payments.create(
                      "merchant-b",
                      new CreatePayment("sandbox-disable", "2000", "CNY", "ALIPAY_SANDBOX", null)))
          .isInstanceOf(ApiException.class)
          .hasMessageContaining("幂等业务号");
      assertThatThrownBy(
              () ->
                  payments.create(
                      "merchant-b",
                      new CreatePayment("new-sandbox", "1000", "CNY", "ALIPAY_SANDBOX", null)))
          .isInstanceOf(ApiException.class);
    }
    assertThatThrownBy(
            () ->
                payments.create(
                    "merchant-a", new CreatePayment("new-auto", "1000", "CNY", "AUTO", null)))
        .isInstanceOf(ApiException.class);
    assertThat(count("payments")).isEqualTo(2);
    assertThat(count("outbox_tasks")).isEqualTo(2);
  }

  @Test
  void allDisabledMerchantRoutesCannotFallBackToAnyExplicitChannel() {
    var control = controls(at);
    control.route("merchant-a", "MOCK_A", 0, 1, false, "pause", ops);
    assertThatThrownBy(() -> control.route("merchant-a", "MOCK_A"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> control.route("merchant-a", "MOCK_B"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> control.route("merchant-a", "AUTO")).isInstanceOf(ApiException.class);
  }

  @Test
  void unknownOutcomeKeepsChosenChannelWhenRoutingChanges() {
    var control = controls(at);
    control.route("merchant-a", "MOCK_A", 0, 1, true, "initial", ops);
    payments.setChannelControl(control);
    var input =
        new dev.merchantpay.platform.payment.PaymentService.CreatePayment(
            "auto", "1000", "CNY", "AUTO", null);
    var payment = payments.create("merchant-a", input);
    payments.applyResult(payment.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    control.route("merchant-a", "MOCK_A", 1, 1, false, "pause", ops);
    control.route("merchant-a", "MOCK_B", 0, 1, true, "new requests", ops);
    assertThat(payments.create("merchant-a", input).channel()).isEqualTo("MOCK_A");
  }

  @Test
  void separateInstancesShareOneInflightLimitAndExpiry() {
    var first = controls(at);
    first.configure("MOCK_A", 1, 1, 10, 3, 30, true, "bound", ops);
    var lease = first.enter("MOCK_A");
    assertThatThrownBy(() -> controls(at).enter("MOCK_A")).isInstanceOf(ApiException.class);
    first.leave(lease, true);
    assertThat(controls(at).enter("MOCK_A").token()).isNotNull();
    assertThat(controls(Clock.offset(at, Duration.ofSeconds(31))).enter("MOCK_A").token())
        .isNotNull();
  }

  @Test
  void lateHealthyCallCannotCloseANewerCircuitAndOnlyOneHalfOpenProbeRuns() {
    var first = controls(at);
    first.configure("MOCK_A", 1, 3, 10, 1, 30, true, "bound", ops);
    var failed = first.enter("MOCK_A");
    var oldHealthy = first.enter("MOCK_A");
    first.leave(failed, false);
    first.leave(oldHealthy, true);
    assertThatThrownBy(() -> first.enter("MOCK_A")).isInstanceOf(ApiException.class);
    var resumed = controls(Clock.offset(at, Duration.ofSeconds(31)));
    var probe = resumed.enter("MOCK_A");
    assertThat(probe.probe()).isTrue();
    assertThatThrownBy(() -> resumed.enter("MOCK_A")).isInstanceOf(ApiException.class);
    resumed.leave(probe, true);
    assertThat(resumed.enter("MOCK_A").probe()).isFalse();
  }

  @Test
  void merchantCannotConfigurePlatformLimits() {
    assertThatThrownBy(
            () ->
                controls(at)
                    .configure(
                        "MOCK_A",
                        1,
                        1,
                        1,
                        1,
                        1,
                        false,
                        "pause",
                        new Actor("merchant", "merchant-a", Set.of("MERCHANT"))))
        .isInstanceOf(ApiException.class);
  }
}
