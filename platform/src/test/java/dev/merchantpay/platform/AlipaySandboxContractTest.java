package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.channel.AlipaySandbox;
import dev.merchantpay.platform.common.ApiException;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AlipaySandboxContractTest {
  @Test
  void moneyNeverUsesFloatingPointOrAcceptsExcessPrecision() {
    assertThat(AlipaySandbox.major("10001")).isEqualTo("100.01");
    assertThat(AlipaySandbox.minor("100.01")).isEqualTo("10001");
    assertThatThrownBy(() -> AlipaySandbox.minor("1.001")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> AlipaySandbox.minor("NaN")).isInstanceOf(ApiException.class);
  }

  @Test
  void productionGatewayAndMissingCredentialsFailClosed() {
    assertThatThrownBy(
            () ->
                new AlipaySandbox(
                    null,
                    JsonMapper.builder().build(),
                    Clock.systemUTC(),
                    true,
                    "https://openapi.alipay.com/gateway.do",
                    "",
                    "",
                    "",
                    "",
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disabledSandboxNeverCreatesAnExternalClient() {
    var sandbox =
        new AlipaySandbox(
            null, JsonMapper.builder().build(), Clock.systemUTC(), false, "", "", "", "", "", "");
    assertThat(sandbox.enabled()).isFalse();
    assertThatThrownBy(() -> sandbox.callback(java.util.Map.of())).isInstanceOf(ApiException.class);
  }
}
