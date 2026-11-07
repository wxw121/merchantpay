package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.sharding.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "merchantpay.sharding.channel-workers-enabled", havingValue = "true")
public class ShardChannelConfiguration {
  @Bean(destroyMethod = "close")
  ReceiptDirectory receiptDirectory(
      DataSource core,
      Clock clock,
      @Value("${merchantpay.sharding.receipt-connections:2}") int connections) {
    return new ReceiptDirectory(core, clock, connections);
  }

  @Bean(destroyMethod = "close")
  ShardChannels shardChannels(
      ShardTransactions routes,
      ShardMoney money,
      ShardConnections sources,
      TaskQueue queue,
      ChannelGateway gateway,
      InboxService inbox,
      NotificationService notifications,
      ChannelResultBatcher batches,
      ReceiptDirectory receipts,
      Clock clock,
      ObjectProvider<AlipaySandbox> sandbox,
      @Value("${merchantpay.event-transport:POLLING}") String transport,
      @Value("${merchantpay.sharding.cdc-enabled:false}") boolean cdc,
      @Value("${merchantpay.events-consumer-enabled:false}") boolean consumer) {
    if (!"POLLING".equalsIgnoreCase(transport)
        && !("CDC".equalsIgnoreCase(transport) && cdc && consumer))
      throw new IllegalArgumentException(
          "Sharded workers require POLLING or a configured shard CDC consumer");
    var channels =
        new ShardChannels(
            routes,
            money,
            sources.targets(),
            queue,
            gateway,
            inbox,
            notifications,
            batches,
            receipts,
            clock);
    inbox.setShards(channels);
    gateway.setShards(channels);
    var adapter = sandbox.getIfAvailable();
    if (adapter != null) adapter.setCallbackOriginal(channels::sandboxOriginal);
    return channels;
  }
}
