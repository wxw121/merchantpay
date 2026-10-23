package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.orchestration.*;
import dev.merchantpay.platform.outbox.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkerIsolationTest extends FinancialFixture {
  @Test
  void blockedMerchantWebhooksCannotExhaustChannelAndInboxExecution() throws Exception {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var outbox = new OutboxService(jdbc, Clock.systemUTC());
    tx.executeWithoutResult(
        ignored -> {
          for (int index = 0; index < 40; index++)
            outbox.enqueue("notification-" + index, "PAYMENT_SUCCEEDED", "p" + index, "merchant-a");
        });
    var queue = new TaskQueue(jdbc, tx, Clock.offset(Clock.systemUTC(), Duration.ofSeconds(1)));
    var gateway = mock(ChannelGateway.class);
    var inbox = mock(InboxService.class);
    var notifications = mock(NotificationService.class);
    var entered = new CountDownLatch(5);
    var release = new CountDownLatch(1);
    var progressed = new CountDownLatch(2);
    doAnswer(
            call -> {
              entered.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("test notification timeout");
              return null;
            })
        .when(notifications)
        .deliver(any());
    doAnswer(
            call -> {
              progressed.countDown();
              return null;
            })
        .when(gateway)
        .execute(any());
    doAnswer(
            call -> {
              progressed.countDown();
              return null;
            })
        .when(inbox)
        .process("inbox-1");
    try (var worker =
        new TaskWorker(
            queue,
            gateway,
            inbox,
            notifications,
            jdbc,
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            null,
            null,
            30)) {
      var poll = TaskWorker.class.getDeclaredMethod("poll");
      poll.setAccessible(true);
      try {
        poll.invoke(worker);
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        tx.executeWithoutResult(
            ignored -> {
              outbox.enqueue("new-payment", "CHANNEL_PAYMENT", "new-payment", "merchant-a");
              outbox.enqueue("new-inbox", "PROCESS_INBOX", "inbox-1", "merchant-a");
            });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (progressed.getCount() > 0 && System.nanoTime() < until) {
          poll.invoke(worker);
          Thread.sleep(10);
        }
        assertThat(progressed.getCount() == 0)
            .as("channel and inbox progress while webhook requests remain blocked")
            .isTrue();
        verify(notifications, times(5)).deliver(any());
      } finally {
        release.countDown();
      }
    }
  }
}
