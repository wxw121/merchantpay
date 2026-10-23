package dev.merchantpay.platform.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.ChannelGateway;
import dev.merchantpay.platform.channel.InboxService;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.reconciliation.ExportService;
import dev.merchantpay.platform.reconciliation.ReconciliationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class TaskWorkerConcurrencyTest {
  @Test
  void emptyPollsDoNotResetFairnessWhileTheOnlyWorkerIsBlocked() throws Exception {
    var queue = mock(TaskQueue.class);
    var gateway = mock(ChannelGateway.class);
    var inbox = mock(InboxService.class);
    var notifications = mock(NotificationService.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var seen = new CopyOnWriteArrayList<String>();
    when(queue.claimBatch(anyInt(), anyString()))
        .thenAnswer(
            call -> {
              String lane = call.getArgument(1);
              if (lane.equals("BULK")) return List.of();
              String kind =
                  lane.equals("INBOX")
                      ? "PROCESS_INBOX"
                      : lane.equals("NOTIFY") ? "PAYMENT_SUCCEEDED" : "CHANNEL_PAYMENT";
              return List.of(new TaskQueue.Task(lane, lane, kind, lane, "merchant", 1, lane));
            });
    when(queue.completeBatch(anyList()))
        .thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(item -> true).toList());
    doAnswer(
            call -> {
              seen.add("INBOX");
              entered.countDown();
              release.await();
              return null;
            })
        .when(inbox)
        .process(anyString());
    doAnswer(
            call -> {
              seen.add("FAST");
              return null;
            })
        .when(gateway)
        .execute(any());
    doAnswer(
            call -> {
              seen.add("NOTIFY");
              return null;
            })
        .when(notifications)
        .deliver(any());
    try (var worker =
        new TaskWorker(
            queue,
            gateway,
            inbox,
            notifications,
            null,
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            null,
            null,
            1)) {
      try {
        worker.poll();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        worker.poll();
        worker.poll();
        release.countDown();
        var field = TaskWorker.class.getDeclaredField("capacity");
        field.setAccessible(true);
        var capacity = (Semaphore) field.get(worker);
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(3))
            .until(() -> capacity.availablePermits() == 1);
        worker.poll();
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(3))
            .until(() -> seen.size() >= 2);
        assertThat(seen).startsWith("INBOX", "FAST");
      } finally {
        release.countDown();
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
  void smallGlobalBudgetsStillMakeProgressOnEveryContinuouslyBackloggedLane(int budget) {
    var queue = mock(TaskQueue.class);
    var gateway = mock(ChannelGateway.class);
    var inbox = mock(InboxService.class);
    var notifications = mock(NotificationService.class);
    var sequence = new AtomicInteger();
    var seen = ConcurrentHashMap.<String>newKeySet();
    when(queue.claimBatch(anyInt(), anyString()))
        .thenAnswer(
            call -> {
              String lane = call.getArgument(1);
              if (lane.equals("BULK")) return List.of();
              String id = "task" + sequence.incrementAndGet();
              String kind =
                  lane.equals("INBOX")
                      ? "PROCESS_INBOX"
                      : lane.equals("NOTIFY") ? "PAYMENT_SUCCEEDED" : "CHANNEL_PAYMENT";
              return List.of(new TaskQueue.Task(id, id, kind, id, "merchant", 1, id));
            });
    when(queue.completeBatch(anyList()))
        .thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(item -> true).toList());
    doAnswer(
            call -> {
              seen.add("FAST");
              return null;
            })
        .when(gateway)
        .execute(any());
    doAnswer(
            call -> {
              seen.add("INBOX");
              return null;
            })
        .when(inbox)
        .process(anyString());
    doAnswer(
            call -> {
              seen.add("NOTIFY");
              return null;
            })
        .when(notifications)
        .deliver(any());
    try (var worker =
        new TaskWorker(
            queue,
            gateway,
            inbox,
            notifications,
            null,
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            null,
            null,
            budget)) {
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                worker.poll();
                assertThat(seen).containsExactlyInAnyOrder("FAST", "INBOX", "NOTIFY");
              });
    }
  }

  @Test
  void configuredBudgetUsesAllSlotsWithoutExceedingClaimBoundsOrLosingPermits() {
    var queue = mock(TaskQueue.class);
    var gateway = mock(ChannelGateway.class);
    var inbox = mock(InboxService.class);
    var notifications = mock(NotificationService.class);
    var waiting = new ConcurrentLinkedQueue<TaskQueue.Task>();
    for (int index = 0; index < 200; index++)
      waiting.add(
          new TaskQueue.Task(
              "id" + index,
              "event" + index,
              "CHANNEL_PAYMENT",
              "payment" + index,
              "merchant",
              1,
              "token" + index));
    var claims = new CopyOnWriteArrayList<Integer>();
    when(queue.claimBatch(anyInt(), eq("FAST")))
        .thenAnswer(
            call -> {
              int limit = call.getArgument(0);
              assertThat(limit).isBetween(1, 64);
              claims.add(limit);
              var result = new ArrayList<TaskQueue.Task>();
              for (int index = 0; index < limit; index++) {
                var item = waiting.poll();
                if (item == null) break;
                result.add(item);
              }
              return result;
            });
    for (String lane : List.of("INBOX", "NOTIFY")) {
      when(queue.claimBatch(anyInt(), eq(lane)))
          .thenAnswer(
              call -> {
                int limit = call.getArgument(0);
                var tasks = new ArrayList<TaskQueue.Task>();
                for (int index = 0; index < limit; index++) {
                  String id = lane + index;
                  tasks.add(
                      new TaskQueue.Task(
                          id,
                          id,
                          lane.equals("INBOX") ? "PROCESS_INBOX" : "PAYMENT_SUCCEEDED",
                          id,
                          "merchant",
                          1,
                          id));
                }
                return tasks;
              });
    }
    when(queue.claimBatch(2, "BULK")).thenReturn(List.of());
    when(queue.completeBatch(anyList()))
        .thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(item -> true).toList());
    var entered = new CountDownLatch(96);
    var release = new CountDownLatch(1);
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    org.mockito.stubbing.Answer<Object> blocked =
        call -> {
          maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
          entered.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("worker gate timed out");
          } finally {
            active.decrementAndGet();
          }
          return null;
        };
    doAnswer(blocked).when(gateway).execute(any());
    doAnswer(blocked).when(inbox).process(anyString());
    doAnswer(blocked).when(notifications).deliver(any());
    new ApplicationContextRunner()
        .withPropertyValues(
            "merchantpay.tasks-fast-concurrency=96", "merchantpay.workers-enabled=true")
        .withBean(TaskQueue.class, () -> queue)
        .withBean(ChannelGateway.class, () -> gateway)
        .withBean(InboxService.class, () -> inbox)
        .withBean(NotificationService.class, () -> notifications)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(ReconciliationService.class, () -> mock(ReconciliationService.class))
        .withBean(ExportService.class, () -> mock(ExportService.class))
        .withUserConfiguration(TaskWorker.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var worker = context.getBean(TaskWorker.class);
              try {
                worker.poll();
                worker.poll();
                worker.poll();
                assertThat(entered.await(3, TimeUnit.SECONDS))
                    .as("all configured worker slots must be usable")
                    .isTrue();
                assertThat(maximum.get()).isEqualTo(96);
                assertThat(claims).containsExactly(64);
                verify(queue).claimBatch(16, "INBOX");
                verify(queue).claimBatch(16, "NOTIFY");
              } finally {
                release.countDown();
              }
            });
    assertThat(active.get()).isZero();
  }
}
