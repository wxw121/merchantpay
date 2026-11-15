package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.payment.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class PaymentAttemptOrderedIdTest extends FinancialFixture {
  final Instant now = Instant.parse("2026-10-30T01:02:03.123456Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  PaymentService ordered() {
    return new PaymentService(
        jdbc,
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
        new LedgerService(jdbc),
        new OutboxService(jdbc, clock),
        clock,
        16,
        false,
        false,
        true);
  }

  UUID uuid(String id) {
    return UUID.fromString(id.substring(id.indexOf('_') + 1));
  }

  @Test
  void defaultIdsStayV4AndCandidateIdsCarryTimeWithoutChangingShard() {
    var old = createPayment("default", "10000");
    var p =
        ordered()
            .create(
                "merchant-a",
                new PaymentService.CreatePayment("ordered", "10000", "CNY", "MOCK_A", null));
    assertThat(uuid(old.id()).version()).isEqualTo(4);
    assertThat(uuid(p.id()).version()).isEqualTo(7);
    assertThat(uuid(p.id()).getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
    int shard =
        jdbc.queryForObject("SELECT logical_shard FROM payments WHERE id=?", Integer.class, p.id());
    assertThat(p.id()).startsWith("p" + Integer.toHexString(shard) + "_");
    assertThat(p.createdAt()).isEqualTo(now);
  }

  @Test
  void candidateReturnsOldIdempotentWinnerAndRejectsChangedPayload() {
    var old = createPayment("old-winner", "10000");
    var service = ordered();
    assertThat(
            service.create(
                "merchant-a",
                new PaymentService.CreatePayment("old-winner", "10000", "CNY", "MOCK_A", null)))
        .isEqualTo(old);
    assertThatThrownBy(
            () ->
                service.create(
                    "merchant-a",
                    new PaymentService.CreatePayment("old-winner", "10001", "CNY", "MOCK_A", null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(409));
    assertThat(count("payments")).isEqualTo(1);
    assertThat(payments.get("merchant-a", old.id())).isEqualTo(old);
  }

  @Test
  void concurrentDifferentPayloadsKeepOneWinnerAndOneConflict() throws Exception {
    var service = ordered();
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var results = new ArrayList<Future<Object>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String amount : List.of("10000", "10001"))
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  try {
                    return service.create(
                        "merchant-a",
                        new PaymentService.CreatePayment("race", amount, "CNY", "MOCK_A", null));
                  } catch (ApiException e) {
                    return e;
                  }
                }));
      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var found = new ArrayList<Object>();
      for (var f : results) found.add(f.get(5, TimeUnit.SECONDS));
      assertThat(found.stream().filter(PaymentService.PaymentView.class::isInstance).count())
          .isEqualTo(1);
      assertThat(
              found.stream()
                  .filter(ApiException.class::isInstance)
                  .map(ApiException.class::cast)
                  .map(ApiException::status)
                  .toList())
          .containsExactly(409);
      var winner =
          found.stream()
              .filter(PaymentService.PaymentView.class::isInstance)
              .map(PaymentService.PaymentView.class::cast)
              .findFirst()
              .orElseThrow();
      assertThat(uuid(winner.id()).version()).isEqualTo(7);
    }
    assertThat(count("payments")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void mixedPaymentIdsWorkThroughReceiptsAndRefundSourceAndAttemptsAreV7() {
    var old = createPayment("old-paid", "10000");
    var fresh =
        ordered()
            .create(
                "merchant-a",
                new PaymentService.CreatePayment("new-paid", "10000", "CNY", "MOCK_A", null));
    var api = mock(ChannelApi.class);
    var metrics = new SimpleMeterRegistry();
    var manager = new DataSourceTransactionManager(jdbc.getDataSource());
    var gateway =
        new ChannelGateway(
            api,
            payments,
            refunds,
            jdbc,
            JsonMapper.builder().build(),
            "test",
            clock,
            new TransactionTemplate(manager),
            new ChannelResultBatcher(manager, metrics, 1, Duration.ZERO),
            metrics,
            null,
            true);
    for (var p : List.of(old, fresh)) {
      when(api.pay(eq("MOCK_A"), eq("test"), any()))
          .thenReturn(
              new ChannelApi.Receipt(
                  p.id(), "PAYMENT", "MOCK_A", "SUCCEEDED", "ref-" + p.id(), "10000", "CNY", "60"));
      gateway.execute(
          new TaskQueue.Task(
              "t-" + p.id(), "e", "CHANNEL_PAYMENT", p.id(), "merchant-a", 1, "lease"));
      assertThat(
              refunds
                  .create(
                      "merchant-a",
                      p.id(),
                      new dev.merchantpay.platform.refund.RefundService.CreateRefund(
                          "refund-" + p.id(), "100"))
                  .paymentId())
          .isEqualTo(p.id());
    }
    var ids = jdbc.queryForList("SELECT id FROM channel_attempts", String.class);
    assertThat(ids).hasSize(2);
    for (var id : ids) {
      assertThat(UUID.fromString(id).version()).isEqualTo(7);
      assertThat(UUID.fromString(id).getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
    }
    assertThat(count("ledger_journals")).isEqualTo(2);
  }

  @Test
  void oldGatewayConstructorKeepsV4AttemptsAndDuplicatePreparationKeepsOriginalId() {
    var p = createPayment("old-attempt", "10000");
    var api = mock(ChannelApi.class);
    var metrics = new SimpleMeterRegistry();
    var manager = new DataSourceTransactionManager(jdbc.getDataSource());
    var receipt =
        new ChannelApi.Receipt(
            p.id(), "PAYMENT", "MOCK_A", "SUCCEEDED", "ref", "10000", "CNY", "60");
    when(api.pay(any(), any(), any())).thenReturn(receipt);
    var gateway =
        new ChannelGateway(
            api,
            payments,
            refunds,
            jdbc,
            JsonMapper.builder().build(),
            "test",
            clock,
            new TransactionTemplate(manager),
            new ChannelResultBatcher(manager, metrics, 1, Duration.ZERO),
            metrics);
    var task = new TaskQueue.Task("t", "e", "CHANNEL_PAYMENT", p.id(), "merchant-a", 1, "lease");
    gateway.execute(task);
    var id =
        jdbc.queryForObject(
            "SELECT id FROM channel_attempts WHERE aggregate_id=?", String.class, p.id());
    assertThat(UUID.fromString(id).version()).isEqualTo(4);
    gateway.execute(task);
    assertThat(count("channel_attempts")).isEqualTo(1);
  }
}
