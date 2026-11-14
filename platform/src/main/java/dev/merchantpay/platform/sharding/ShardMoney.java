package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.refund.RefundService;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

/**
 * Money and its audit share a local transaction. Pinned methods are internal, not HTTP endpoints.
 */
public class ShardMoney {
  private record Local(
      JdbcTemplate jdbc,
      PaymentService payments,
      RefundService refunds,
      OutboxService outbox,
      AtomicBoolean ready) {
    void prepare() {
      if (ready.get()) return;
      payments.initializeAdmissionState();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              ready.set(true);
            }
          });
    }
  }

  private final ShardTransactions transactions;
  private final Map<Integer, Local> locals;
  private final Clock clock;
  private ReceiptDirectory receipts;
  private volatile ShardTargetHttpAdmission targetHttpAdmission;

  public void setReceiptDirectory(ReceiptDirectory value) {
    receipts = value;
  }

  public void setTargetHttpAdmission(ShardTargetHttpAdmission value) {
    targetHttpAdmission = Objects.requireNonNull(value);
  }

  public ShardMoney(
      ShardTransactions transactions,
      Map<Integer, DataSource> sources,
      PaymentService payment,
      RefundService refund,
      LedgerService ledger,
      OutboxService outbox,
      Clock clock) {
    this.transactions = transactions;
    this.clock = clock;
    if (sources == null || !sources.keySet().equals(Set.of(0, 1, 2, 3)))
      throw new IllegalArgumentException("Four shard sources are required");
    var configured = new HashMap<Integer, Local>();
    sources.forEach(
        (id, source) -> {
          var jdbc = new JdbcTemplate(source);
          jdbc.setQueryTimeout(3);
          var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
          var localLedger = ledger.forShard(jdbc);
          var localOutbox = outbox.forShard(jdbc, () -> transactions.boundRoute().bucket());
          var localPayment = payment.forShard(jdbc, tx, localLedger, localOutbox);
          var localRefund = refund.forShard(jdbc, tx, localPayment, localLedger, localOutbox);
          configured.put(
              id, new Local(jdbc, localPayment, localRefund, localOutbox, new AtomicBoolean()));
        });
    locals = Map.copyOf(configured);
  }

  private <T> T write(ShardCatalog.Route expected, Function<Local, T> action) {
    return transactions.write(
        expected,
        selected -> {
          var local = locals.get(expected.physicalShard());
          if (local == null || local.jdbc().getDataSource() != selected.getDataSource())
            throw new IllegalStateException("Money services must share the selected DataSource");
          local.prepare();
          return action.apply(local);
        });
  }

  public record ChannelServices(
      JdbcTemplate jdbc, PaymentService payments, RefundService refunds, OutboxService outbox) {}

  public ChannelServices channelServices(int physical) {
    var local = locals.get(physical);
    if (local == null) throw new IllegalArgumentException("Unknown physical shard");
    // Outbox shares the same configured journal and route binding as these services.
    return new ChannelServices(local.jdbc(), local.payments(), local.refunds(), local.outbox());
  }

  public <T> T checkpoint(ShardCatalog.Route expected, Function<ChannelServices, T> action) {
    return write(expected, local -> action.apply(channelServices(expected.physicalShard())));
  }

  private ShardCatalog.Route requestRoute(int bucket) {
    var expected = transactions.currentRoute(bucket);
    if (expected.physicalShard() != 0) {
      var admission = targetHttpAdmission;
      if (admission == null)
        throw new ApiException(
            "SHARD_WORKER_NOT_READY",
            503,
            "目标分片交付条件尚未齐备，暂不开放受理",
            ApiException.RetryReason.DATABASE_UNAVAILABLE);
      admission.require(expected);
    }
    return expected;
  }

  private static void bucket(ShardCatalog.Route expected, int bucket) {
    if (expected == null || expected.bucket() != bucket)
      throw new IllegalArgumentException("Aggregate and expected route must have the same bucket");
  }

  public PaymentService.PaymentView create(
      String merchant, PaymentService.CreatePayment input, String actor) {
    return create(
        requestRoute(PaymentBuckets.forBusinessKey(merchant, input.merchantOrderNo())),
        merchant,
        input,
        actor);
  }

  public PaymentService.PaymentView create(
      ShardCatalog.Route expected,
      String merchant,
      PaymentService.CreatePayment input,
      String actor) {
    return write(
        expected,
        local -> {
          bucket(expected, PaymentBuckets.forBusinessKey(merchant, input.merchantOrderNo()));
          var result = local.payments().create(merchant, input);
          MerchantService.audit(
              local.jdbc(),
              clock,
              merchant,
              actor,
              "PAYMENT_CREATE",
              result.id(),
              input.merchantOrderNo());
          return result;
        });
  }

  public PaymentService.PaymentView close(String scope, String id, String actor) {
    var expected = requestRoute(PaymentBuckets.forPaymentId(id));
    return write(
        expected,
        local -> {
          String merchant = Queries.paymentMerchant(local.jdbc(), id, scope);
          var result = local.payments().requestClose(merchant, id);
          MerchantService.audit(
              local.jdbc(), clock, merchant, actor, "PAYMENT_CLOSE", id, "Close requested");
          return result;
        });
  }

  public RefundService.RefundView refund(
      String scope, String payment, RefundService.CreateRefund input, String actor) {
    return refund(requestRoute(PaymentBuckets.forPaymentId(payment)), scope, payment, input, actor);
  }

  public RefundService.RefundView refund(
      ShardCatalog.Route expected,
      String scope,
      String payment,
      RefundService.CreateRefund input,
      String actor) {
    return write(
        expected,
        local -> {
          bucket(expected, PaymentBuckets.forPaymentId(payment));
          String merchant = Queries.paymentMerchant(local.jdbc(), payment, scope);
          var result = local.refunds().create(merchant, payment, input);
          MerchantService.audit(
              local.jdbc(),
              clock,
              merchant,
              actor,
              "REFUND_CREATE",
              result.id(),
              input.merchantRefundNo());
          return result;
        });
  }

  public void paymentResult(
      ShardCatalog.Route expected, String id, ChannelResult result, ReceiptEvidence evidence) {
    write(
        expected,
        local -> {
          bucket(expected, PaymentBuckets.forPaymentId(id));
          if (evidence == null) local.payments().applyResultWithoutView(id, result);
          else local.payments().applyObservation(id, result, evidence);
          claimResult(local, id, "PAYMENT", result);
          return null;
        });
  }

  public void refundResult(
      ShardCatalog.Route expected, String id, ChannelResult result, ReceiptEvidence evidence) {
    write(
        expected,
        local -> {
          bucket(expected, PaymentBuckets.forRefundId(id));
          if (evidence == null) local.refunds().applyResultWithoutView(id, result);
          else local.refunds().applyObservation(id, result, evidence);
          claimResult(local, id, "REFUND", result);
          return null;
        });
  }

  private void claimResult(Local local, String id, String operation, ChannelResult result) {
    if (receipts == null || !(result instanceof ChannelResult.Success success)) return;
    String table = "PAYMENT".equals(operation) ? "payments" : "refunds";
    String merchant =
        local
            .jdbc()
            .queryForObject("SELECT merchant_id FROM " + table + " WHERE id=?", String.class, id);
    receipts.claim(
        new dev.merchantpay.platform.channel.ChannelApi.Receipt(
            id,
            operation,
            success.channel(),
            "SUCCEEDED",
            success.reference(),
            Long.toString(success.amountMinor()),
            success.currency(),
            "0"),
        merchant);
  }
}
