package dev.merchantpay.platform.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.ChannelGateway;
import dev.merchantpay.platform.channel.ChannelResultBatcher;
import dev.merchantpay.platform.channel.InboxService;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import dev.merchantpay.platform.sharding.ReceiptDirectory;
import dev.merchantpay.platform.sharding.ShardCatalog;
import dev.merchantpay.platform.sharding.ShardMoney;
import dev.merchantpay.platform.sharding.ShardTransactions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ShardChannelsTest {
  private static final String PAYMENT = "p7_00000000-0000-0000-0000-000000000007";
  private static final String REFUND = "r7_00000000-0000-0000-0000-000000000007";
  private final ShardCatalog.Route route = new ShardCatalog.Route(7, 2, 4);
  private final ShardTransactions transactions = mock(ShardTransactions.class);
  private final ShardMoney money = mock(ShardMoney.class);
  private final TaskQueue localQueue = mock(TaskQueue.class);
  private final ChannelGateway gateway = mock(ChannelGateway.class);
  private final ChannelGateway localGateway = mock(ChannelGateway.class);
  private final InboxService inbox = mock(InboxService.class);
  private final InboxService localInbox = mock(InboxService.class);
  private final NotificationService notifications = mock(NotificationService.class);
  private final NotificationService localNotifications = mock(NotificationService.class);
  private final ChannelResultBatcher batches = mock(ChannelResultBatcher.class);
  private final ChannelResultBatcher localBatch = mock(ChannelResultBatcher.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private ShardChannels channels;

  @BeforeEach
  void fixture() {
    var prototype = mock(TaskQueue.class);
    when(prototype.forShard(any(JdbcTemplate.class))).thenReturn(localQueue);
    var receipts = mock(ReceiptDirectory.class);
    var payments = mock(PaymentService.class);
    var refunds = mock(RefundService.class);
    var outbox = mock(OutboxService.class);
    var local = new ShardMoney.ChannelServices(jdbc, payments, refunds, outbox);
    when(money.channelServices(2)).thenReturn(local);
    when(money.checkpoint(eq(route), any()))
        .thenAnswer(
            call -> {
              Function<ShardMoney.ChannelServices, ?> write = call.getArgument(1);
              return write.apply(local);
            });
    when(transactions.write(eq(route), any()))
        .thenAnswer(
            call -> {
              Function<JdbcTemplate, ?> write = call.getArgument(1);
              return write.apply(jdbc);
            });
    when(transactions.currentRoute(7)).thenReturn(route);
    when(batches.forShard(any())).thenReturn(localBatch);
    when(gateway.forShard(eq(jdbc), eq(payments), eq(refunds), eq(localBatch), any(), eq(receipts)))
        .thenReturn(localGateway);
    when(inbox.forShard(eq(jdbc), eq(outbox), eq(localGateway), any())).thenReturn(localInbox);
    when(notifications.forShard(eq(payments), eq(refunds), eq(jdbc), any(), any()))
        .thenReturn(localNotifications);
    channels =
        new ShardChannels(
            transactions,
            money,
            Map.of(2, mock(DataSource.class)),
            prototype,
            gateway,
            inbox,
            notifications,
            batches,
            receipts,
            Clock.systemUTC());
  }

  @AfterEach
  void close() {
    channels.close();
  }

  @Test
  void claimsOnlyCurrentBucketAndCompletesOnlyThePinnedLease() {
    var task = task("CHANNEL_PAYMENT", PAYMENT);
    when(localQueue.claimBucket(7, 4, "FAST", 2)).thenReturn(List.of(task));
    var claimed = channels.claim(route, "FAST", 2);

    assertThat(claimed).containsExactly(new ShardChannels.Execution(route, task));
    var execution = claimed.getFirst();
    assertThat(channels.execute(execution)).isTrue();
    when(localQueue.complete(task)).thenReturn(true);
    channels.complete(execution);
    var order = inOrder(localQueue, localGateway);
    order.verify(localQueue).assertPinned(task, 7, 4);
    order.verify(localGateway).execute(task);
    order.verify(localQueue).assertPinned(task, 7, 4);
    order.verify(localQueue).complete(task);
  }

  @Test
  void rejectsMismatchedBucketsAndStaleRoutesBeforeChannelExecution() {
    var otherBucket = task("CHANNEL_PAYMENT", PAYMENT.replace("p7_", "p6_"));
    assertThatThrownBy(() -> channels.execute(new ShardChannels.Execution(route, otherBucket)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bucket differ");
    when(transactions.currentRoute(7)).thenReturn(new ShardCatalog.Route(7, 3, 5));
    var execution = new ShardChannels.Execution(route, task("CHANNEL_REFUND", REFUND));
    assertThatThrownBy(() -> channels.execute(execution)).isInstanceOf(ApiException.class);
    verifyNoInteractions(localGateway);
  }

  @Test
  void resolvesInboxNotificationUsingItsOriginalRefundBucket() {
    var task = task("PROCESS_INBOX", "inbox-record");
    when(jdbc.queryForList(anyString(), eq("inbox-record")))
        .thenReturn(List.of(Map.of("aggregate_id", REFUND, "operation", "REFUND")), List.of());
    var execution = new ShardChannels.Execution(route, task);
    assertThat(channels.execute(execution)).isTrue();
    assertThatThrownBy(() -> channels.execute(execution))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no original notification");
    verify(localInbox).process("inbox-record");
  }

  @Test
  void doesNotSendAnExpiredNotificationAndUsesTheLocalDeliveryServiceOnRetry() {
    var task = task("REFUND_SUCCEEDED", REFUND);
    var execution = new ShardChannels.Execution(route, task);
    when(localQueue.notificationMayRun(task)).thenReturn(false, true);
    assertThat(channels.execute(execution)).isFalse();
    verifyNoInteractions(localNotifications);
    assertThat(channels.execute(execution)).isTrue();
    verify(localNotifications).deliver(task);
  }

  @Test
  void renewAndRetryKeepTheLeaseScopedToTheOriginalBucket() {
    var task = task("CHANNEL_PAYMENT", PAYMENT);
    var execution = new ShardChannels.Execution(route, task);
    when(localQueue.renew(task)).thenReturn(true, false);
    channels.renew(execution);
    assertThatThrownBy(() -> channels.renew(execution)).isInstanceOf(TaskQueue.LeaseLost.class);
    assertThatThrownBy(() -> channels.complete(execution)).isInstanceOf(TaskQueue.LeaseLost.class);
    var failure = new IllegalStateException("temporary failure");
    channels.retry(execution, failure);
    verify(localQueue, times(4)).assertPinned(task, 7, 4);
    verify(localQueue).retry(task, failure);
  }

  @Test
  void resultWritersAreReusedWithinTheRouteAndClosedWithTheRuntime() {
    var execution = new ShardChannels.Execution(route, task("CHANNEL_PAYMENT", PAYMENT));
    channels.execute(execution);
    channels.execute(execution);
    verify(batches).forShard(any());

    channels.close();

    verify(localBatch).close();
    assertThat(channels.claim(route, "FAST", 2)).isEmpty();
    assertThatThrownBy(() -> channels.execute(execution))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  private static TaskQueue.Task task(String kind, String aggregate) {
    return new TaskQueue.Task("task", "event", kind, aggregate, "merchant", 1, "lease");
  }
}
