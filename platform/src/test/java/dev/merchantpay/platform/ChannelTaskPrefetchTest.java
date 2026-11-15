package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ChannelTaskPrefetchTest extends FinancialFixture {
  JdbcTemplate observed;
  ChannelApi api;
  ChannelGateway gateway;

  void service() {
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/migration/V2__security_and_evidence.sql"))
        .execute(jdbc.getDataSource());
    observed = spy(jdbc);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var ledger = new LedgerService(observed);
    var outbox = new OutboxService(observed, Clock.systemUTC());
    payments = spy(new PaymentService(observed, tx, ledger, outbox, Clock.systemUTC(), 16));
    refunds = spy(new RefundService(observed, tx, payments, ledger, outbox, Clock.systemUTC()));
    api = mock(ChannelApi.class);
    var metrics = new SimpleMeterRegistry();
    gateway =
        new ChannelGateway(
            api,
            payments,
            refunds,
            observed,
            JsonMapper.builder().build(),
            "test",
            Clock.systemUTC(),
            tx,
            new ChannelResultBatcher(
                new DataSourceTransactionManager(jdbc.getDataSource()), metrics, 1, Duration.ZERO),
            metrics);
  }

  TaskQueue.Task task(String id, String kind, String aggregate, String merchant) {
    return new TaskQueue.Task(id, "event-" + id, kind, aggregate, merchant, 1, "lease-" + id);
  }

  ChannelApi.Receipt success(String id) {
    return new ChannelApi.Receipt(
        id, "PAYMENT", "MOCK_A", "SUCCEEDED", "ref-" + id, "10000", "CNY", "60");
  }

  @Test
  void mixedTasksLoadAllInitialRecordsWithTwoBoundedQueries() {
    service();
    var one = createPayment("prefetch-one", "10000");
    var two =
        payments.create(
            "merchant-b",
            new PaymentService.CreatePayment("prefetch-two", "10000", "CNY", "MOCK_A", null));
    var root = createPayment("prefetch-root", "10000");
    payments.applyResult(
        root.id(), new ChannelResult.Success("MOCK_A", "source-ref", 10000, "CNY"));
    var refund =
        refunds.create(
            "merchant-a", root.id(), new RefundService.CreateRefund("prefetch-refund", "100"));
    var tasks =
        List.of(
            task("a", "CHANNEL_PAYMENT", one.id(), "merchant-a"),
            task("b", "CHANNEL_CLOSE", two.id(), "merchant-b"),
            task("c", "CHANNEL_REFUND", refund.id(), "merchant-a"));
    clearInvocations(observed, payments, refunds);
    var inputs = gateway.prefetch(tasks);
    assertThat(inputs).hasSize(3);
    assertThat(inputs.get("a").payment().id()).isEqualTo(one.id());
    assertThat(inputs.get("b").payment().merchantId()).isEqualTo("merchant-b");
    assertThat(inputs.get("c").payment().id()).isEqualTo(root.id());
    assertThat(inputs.get("c").refund().id()).isEqualTo(refund.id());
    verify(observed, times(2)).query(any(PreparedStatementCreator.class), any(RowMapper.class));
    verify(payments, never()).get(anyString(), anyString());
    verify(refunds, never()).get(anyString(), anyString());
    assertThatThrownBy(inputs::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void emptyUnsupportedAndOversizedBatchesDoNotQuery() {
    service();
    assertThat(gateway.prefetch(List.of())).isEmpty();
    assertThat(gateway.prefetch(List.of(task("x", "PROCESS_INBOX", "p", "merchant-a")))).isEmpty();
    assertThatThrownBy(
            () ->
                gateway.prefetch(
                    Collections.nCopies(65, task("x", "CHANNEL_PAYMENT", "p", "merchant-a"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> payments.loadForChannelTasks(Collections.nCopies(65, "p")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> refunds.loadForChannelTasks(Collections.nCopies(65, "r")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(observed, never()).query(any(PreparedStatementCreator.class), any(RowMapper.class));
  }

  @Test
  void foreignAndMissingInputsUseTheOriginalAuthorizationPath() {
    service();
    var p = createPayment("owner", "10000");
    var foreign = task("f", "CHANNEL_PAYMENT", p.id(), "merchant-b");
    var missing = task("m", "CHANNEL_PAYMENT", "missing", "merchant-a");
    assertThat(gateway.prefetch(List.of(foreign, missing))).isEmpty();
    assertThatThrownBy(() -> gateway.execute(foreign)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> gateway.execute(missing)).isInstanceOf(ApiException.class);
    verifyNoInteractions(api);
  }

  @Test
  void snapshotsAvoidInitialGetsWhileFinancialApplicationStillLocksLatestRows() {
    service();
    var p = createPayment("execute", "10000");
    var t = task("t", "CHANNEL_PAYMENT", p.id(), "merchant-a");
    var input = gateway.prefetch(List.of(t)).get("t");
    clearInvocations(payments, refunds);
    when(api.pay(eq("MOCK_A"), eq("test"), any())).thenReturn(success(p.id()));
    gateway.execute(t, input);
    verify(payments, never()).get(anyString(), anyString());
    verify(payments, atLeastOnce()).lock(p.id());
    assertThat(count("ledger_journals")).isEqualTo(1);
  }

  @Test
  void snapshotIsBoundToTheExactClaimIncludingLeaseAndMerchant() {
    service();
    var p = createPayment("binding", "10000");
    var t = task("t", "CHANNEL_PAYMENT", p.id(), "merchant-a");
    var input = gateway.prefetch(List.of(t)).get("t");
    var newer =
        new TaskQueue.Task(
            t.id(), t.eventId(), t.kind(), t.aggregateId(), t.merchantId(), 2, "new-lease");
    assertThatThrownBy(() -> gateway.execute(newer, input))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> gateway.execute(task("t", "CHANNEL_PAYMENT", p.id(), "merchant-b"), input))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(api);
  }

  @Test
  void staleProcessingSnapshotCannotResurrectFailedPaymentOrCreateMoney() {
    service();
    var p = createPayment("stale", "10000");
    var t = task("t", "CHANNEL_PAYMENT", p.id(), "merchant-a");
    var input = gateway.prefetch(List.of(t)).get("t");
    payments.applyResult(p.id(), new ChannelResult.Failure("MOCK_A", "declined"));
    when(api.pay(eq("MOCK_A"), eq("test"), any())).thenReturn(success(p.id()));
    assertThatThrownBy(() -> gateway.execute(t, input)).isInstanceOf(ApiException.class);
    assertThat(payments.get("merchant-a", p.id()).status()).isEqualTo("FAILED");
    assertThat(count("ledger_journals")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT response_body FROM channel_attempts WHERE aggregate_id=?",
                String.class,
                p.id()))
        .contains("SUCCEEDED");
  }

  @Test
  void reclaimedClaimStillQueriesBeforeAnyPayment() {
    service();
    var p = createPayment("reclaim", "10000");
    var t = new TaskQueue.Task("t", "e", "CHANNEL_PAYMENT", p.id(), "merchant-a", 2, "lease");
    var input = gateway.prefetch(List.of(t)).get("t");
    when(api.query("MOCK_A", "PAYMENT", p.id(), "test")).thenReturn(success(p.id()));
    gateway.execute(t, input);
    verify(api).query("MOCK_A", "PAYMENT", p.id(), "test");
    verify(api, never()).pay(any(), any(), any());
    assertThat(count("ledger_journals")).isEqualTo(1);
  }
}
