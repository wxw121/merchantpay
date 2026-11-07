package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.sharding.ShardReads;
import dev.merchantpay.platform.web.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.DefaultApiVersionStrategy;
import org.springframework.web.accept.SemanticApiVersionParser;

class ShardReadApiTest extends FinancialFixture {
  private MockMvc api(OpenApiController controller) {
    var versions =
        new DefaultApiVersionStrategy(
            java.util.List.of(request -> "1.0"),
            new SemanticApiVersionParser(),
            false,
            "1.0",
            false,
            null,
            null);
    versions.addSupportedVersion("1.0");
    return MockMvcBuilders.standaloneSetup(controller)
        .setApiVersionStrategy(versions)
        .setControllerAdvice(new ApiErrors(null))
        .build();
  }

  @Test
  void existingEndpointsUseTheOptionalShardReaderWithTheAuthenticatedMerchant() throws Exception {
    var reader = mock(ShardReads.class);
    var payment = createPayment("reader-transport", "100");
    var controller = new OpenApiController(new Access(), payments, refunds, null, null, null);
    controller.setShardReads(reader);
    var mvc = api(controller);
    when(reader.payment("merchant-a", payment.id())).thenReturn(payment);
    String refund = "r1_552ea710-7879-4b42-876a-f145cdcf51d3";
    when(reader.refund("merchant-a", refund))
        .thenThrow(new ApiException("REFUND_NOT_FOUND", 404, "退款单不存在"));
    ScopedValue.where(
            Access.CURRENT, new Access.Caller("merchant", "merchant-a", Set.of("MERCHANT")))
        .call(
            () -> {
              mvc.perform(get("/api/v1/payments/" + payment.id()))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.id").value(payment.id()));
              mvc.perform(get("/api/v1/refunds/" + refund)).andExpect(status().isNotFound());
              return null;
            });
    verify(reader).payment("merchant-a", payment.id());
    verify(reader).refund("merchant-a", refund);
  }

  @Test
  void defaultControllerContinuesToUseTheExistingPaymentPath() throws Exception {
    var payment = createPayment("legacy-reader", "100");
    var controller = new OpenApiController(new Access(), payments, refunds, null, null, null);
    var mvc = api(controller);
    ScopedValue.where(
            Access.CURRENT, new Access.Caller("merchant", "merchant-a", Set.of("MERCHANT")))
        .call(
            () -> {
              mvc.perform(get("/api/v1/payments/" + payment.id()))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.amountMinor").value("100"));
              return null;
            });
    assertThat(payments.get("merchant-a", payment.id()).id()).isEqualTo(payment.id());
  }
}
