package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "merchantpay.sharding.money-writes-enabled", havingValue = "true")
public class ShardMoneyConfiguration {
  @Bean
  ShardMoney shardMoney(
      ShardTransactions transactions,
      ShardConnections connections,
      PaymentService payments,
      RefundService refunds,
      LedgerService ledger,
      OutboxService outbox,
      Clock clock,
      @Value("${merchantpay.event-transport:POLLING}") String transport,
      @Value("${merchantpay.read-model-consumer-enabled:false}") boolean coreProjectionConsumer,
      @Value("${merchantpay.read-model-worker-enabled:false}") boolean coreProjectionWorker) {
    if (coreProjectionConsumer || coreProjectionWorker)
      throw new IllegalArgumentException(
          "Core-only projection consumer and worker cannot run with sharded money writes");
    ShardEventTransportMaintenance.requireMode(
        connections.targets(), transport.toUpperCase(java.util.Locale.ROOT));
    return new ShardMoney(
        transactions, connections.targets(), payments, refunds, ledger, outbox, clock);
  }

  @Bean
  ShardProjectionReadiness shardProjectionReadiness(ShardConnections connections, Clock clock) {
    return new ShardProjectionReadiness(connections.targets(), clock);
  }
}
