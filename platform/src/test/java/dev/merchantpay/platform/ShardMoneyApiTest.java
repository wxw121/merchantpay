package dev.merchantpay.platform;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.merchantpay.platform.common.NotificationPolicy;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.refund.RefundService;
import dev.merchantpay.platform.sharding.*;
import dev.merchantpay.platform.web.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.*;

class ShardMoneyApiTest extends FinancialFixture {
  @Test
  void bothControllersDispatchBeforeTheLegacyTransactionAndPreserveActorAndTenant()
      throws Exception {
    var money = mock(ShardMoney.class);
    var access = new Access();
    var urls = new NotificationPolicy("");
    var open = new OpenApiController(access, payments, refunds, null, urls, null);
    open.setShardMoney(money);
    var console =
        new ConsoleController(
            access,
            mock(Queries.class),
            payments,
            refunds,
            null,
            urls,
            jdbc,
            null,
            Clock.systemUTC(),
            null);
    console.setShardMoney(money);
    var versions =
        new DefaultApiVersionStrategy(
            List.of(request -> "1.0"),
            new SemanticApiVersionParser(),
            false,
            "1.0",
            false,
            null,
            null);
    versions.addSupportedVersion("1.0");
    var mvc =
        MockMvcBuilders.standaloneSetup(open, console)
            .setApiVersionStrategy(versions)
            .setControllerAdvice(new ApiErrors(null))
            .build();
    var payment = createPayment("dispatch-money", "100");
    when(money.create(eq("merchant-a"), any(PaymentService.CreatePayment.class), eq("merchant")))
        .thenReturn(payment);
    when(money.close("merchant-a", payment.id(), "merchant")).thenReturn(payment);
    var refund =
        new RefundService.RefundView(
            "refund",
            payment.id(),
            "merchant-a",
            "refund",
            "10",
            "0",
            "PROCESSING",
            "MOCK_A",
            null,
            payment.createdAt(),
            payment.updatedAt());
    when(money.refund(
            eq("merchant-a"),
            eq(payment.id()),
            any(RefundService.CreateRefund.class),
            eq("merchant")))
        .thenReturn(refund);
    ScopedValue.where(
            Access.CURRENT, new Access.Caller("merchant", "merchant-a", Set.of("MERCHANT")))
        .call(
            () -> {
              for (String prefix : List.of("/api/v1", "/api/console")) {
                mvc.perform(
                        post(prefix + "/payments")
                            .contentType("application/json")
                            .content(
                                "{\"merchantOrderNo\":\"order\",\"amountMinor\":\"100\",\"currency\":\"CNY\",\"channel\":\"MOCK_A\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value(payment.id()));
                mvc.perform(post(prefix + "/payments/" + payment.id() + "/close"))
                    .andExpect(status().isAccepted());
                mvc.perform(
                        post(prefix + "/payments/" + payment.id() + "/refunds")
                            .contentType("application/json")
                            .content("{\"merchantRefundNo\":\"refund\",\"amountMinor\":\"10\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.paymentId").value(payment.id()));
              }
              mvc.perform(
                      post("/api/console/payments")
                          .param("merchantId", "merchant-b")
                          .contentType("application/json")
                          .content("{}"))
                  .andExpect(status().isNotFound());
              return null;
            });
    verify(money, times(2))
        .create(eq("merchant-a"), any(PaymentService.CreatePayment.class), eq("merchant"));
    verify(money, times(2)).close("merchant-a", payment.id(), "merchant");
    verify(money, times(2))
        .refund(
            eq("merchant-a"),
            eq(payment.id()),
            any(RefundService.CreateRefund.class),
            eq("merchant"));
  }

  @Test
  void writesRemainOffByDefaultAndAnEnabledWriteFlagRequiresShardDependencies() {
    new ApplicationContextRunner()
        .withUserConfiguration(ShardMoneyConfiguration.class)
        .run(
            context ->
                org.assertj.core.api.Assertions.assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(ShardMoney.class));
    new ApplicationContextRunner()
        .withUserConfiguration(ShardMoneyConfiguration.class)
        .withPropertyValues("merchantpay.sharding.money-writes-enabled=true")
        .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasFailed());
  }

  @Test
  void targetHttpFlagCannotStartWithoutShardedMoneyService() {
    new ApplicationContextRunner()
        .withUserConfiguration(ShardTargetHttpConfiguration.class)
        .withPropertyValues("merchantpay.sharding.target-http-writes-enabled=true")
        .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasFailed());
  }
}
