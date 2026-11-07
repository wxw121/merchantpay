package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.channel.ChannelApi;
import dev.merchantpay.platform.common.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable conservative reservation; a failed local money transaction never releases it. */
public final class ReceiptDirectory
    implements AutoCloseable, dev.merchantpay.platform.channel.ChannelReceiptClaims {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private com.zaxxer.hikari.HikariDataSource owned, parent;
  private int previousMaximum, previousMinimum;

  public ReceiptDirectory(DataSource core, Clock clock) {
    this(core, clock, 2);
  }

  public ReceiptDirectory(DataSource core, Clock clock, int connections) {
    if (connections < 1 || connections > 8)
      throw new IllegalArgumentException("Receipt connections must be 1..8");
    DataSource directorySource = core;
    DataSource effective = core;
    if (core instanceof org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource routed)
      effective =
          routed.getResolvedDataSources().getOrDefault(true, routed.getResolvedDefaultDataSource());
    if (effective instanceof com.zaxxer.hikari.HikariDataSource pool) {
      previousMaximum = pool.getMaximumPoolSize();
      previousMinimum = pool.getMinimumIdle();
      if (previousMinimum < 0) previousMinimum = previousMaximum;
      if (previousMaximum - connections < 3)
        throw new IllegalArgumentException(
            "Receipt pool reservation must leave at least three core worker connections");
      parent = pool;
      owned = new com.zaxxer.hikari.HikariDataSource();
      owned.setPoolName("merchantpay-receipt-directory");
      owned.setJdbcUrl(pool.getJdbcUrl());
      owned.setUsername(pool.getUsername());
      owned.setPassword(pool.getPassword());
      var properties = new Properties();
      properties.putAll(pool.getDataSourceProperties());
      owned.setDataSourceProperties(properties);
      owned.setConnectionInitSql(pool.getConnectionInitSql());
      owned.setCatalog(pool.getCatalog());
      owned.setSchema(pool.getSchema());
      owned.setMinimumIdle(0);
      owned.setMaximumPoolSize(connections);
      owned.setConnectionTimeout(3000);
      owned.setValidationTimeout(1000);
      owned.setInitializationFailTimeout(-1);
      owned.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
      pool.setMinimumIdle(Math.min(previousMinimum, previousMaximum - connections));
      pool.setMaximumPoolSize(previousMaximum - connections);
      directorySource = owned;
    }
    // A distinct resource key always obtains an independent connection, even on shard zero.
    var independent = new DelegatingDataSource(directorySource);
    jdbc = new JdbcTemplate(independent);
    jdbc.setQueryTimeout(3);
    tx = new TransactionTemplate(new DataSourceTransactionManager(independent));
    tx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(3);
    this.clock = clock;
  }

  public void claim(ChannelApi.Receipt receipt, String merchant) {
    if (!"SUCCEEDED".equals(receipt.status())) return;
    if (!Set.of("PAYMENT", "REFUND").contains(Objects.toString(receipt.operation(), ""))
        || receipt.reference() == null
        || receipt.reference().isBlank()
        || receipt.reference().length() > 128
        || receipt.channel() == null
        || receipt.channel().isBlank()
        || receipt.channel().length() > 32
        || merchant == null
        || merchant.isBlank()
        || merchant.length() > 64
        || !"CNY".equals(receipt.currency())) throw conflict();
    long amount = MoneyRules.parsePositive(receipt.amountMinor());
    if ("PAYMENT".equals(receipt.operation())) PaymentBuckets.forPaymentId(receipt.aggregateId());
    else PaymentBuckets.forRefundId(receipt.aggregateId());
    tx.executeWithoutResult(
        status -> {
          String table = "PAYMENT".equals(receipt.operation()) ? "payments" : "refunds";
          var legacy =
              jdbc.queryForList(
                  "SELECT id FROM "
                      + table
                      + " WHERE channel=? AND channel_reference=? AND status='SUCCEEDED'",
                  String.class,
                  receipt.channel(),
                  receipt.reference());
          if (legacy.stream().anyMatch(id -> !receipt.aggregateId().equals(id))) throw conflict();
          jdbc.update(
              "INSERT INTO v3_receipt_claims(channel,operation,receipt_reference,aggregate_id,merchant_id,amount_minor,currency,claimed_at) VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE receipt_reference=receipt_reference",
              receipt.channel(),
              receipt.operation(),
              receipt.reference(),
              receipt.aggregateId(),
              merchant,
              amount,
              receipt.currency(),
              Timestamp.from(clock.instant()));
          var owner =
              jdbc.queryForMap(
                  "SELECT aggregate_id,merchant_id,amount_minor,currency FROM v3_receipt_claims WHERE channel=? AND operation=? AND receipt_reference=? FOR UPDATE",
                  receipt.channel(),
                  receipt.operation(),
                  receipt.reference());
          if (!receipt.aggregateId().equals(owner.get("aggregate_id"))
              || !merchant.equals(owner.get("merchant_id"))
              || amount != ((Number) owner.get("amount_minor")).longValue()
              || !receipt.currency().equals(owner.get("currency"))) throw conflict();
        });
  }

  private static ApiException conflict() {
    return new ApiException("CHANNEL_EVIDENCE_CONFLICT", 409, "渠道回执已绑定其他业务或内容不一致");
  }

  @Override
  public void close() {
    if (owned != null) owned.close();
    if (parent != null && !parent.isClosed()) {
      parent.setMaximumPoolSize(previousMaximum);
      parent.setMinimumIdle(previousMinimum);
    }
  }
}
