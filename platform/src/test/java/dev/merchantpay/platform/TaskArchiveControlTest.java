package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.ChannelGateway;
import dev.merchantpay.platform.channel.InboxService;
import dev.merchantpay.platform.orchestration.NotificationService;
import dev.merchantpay.platform.orchestration.TaskWorker;
import dev.merchantpay.platform.outbox.TaskQueue;
import dev.merchantpay.platform.reconciliation.ExportService;
import dev.merchantpay.platform.reconciliation.ReconciliationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class TaskArchiveControlTest extends FinancialFixture {
  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackageClasses = TaskWorker.class,
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.Task(Worker|Archiver)"))
  static class Workers {}

  ApplicationContextRunner workers(ChannelGateway gateway) {
    var clock = Clock.fixed(Instant.now().plus(Duration.ofDays(2)), ZoneOffset.UTC);
    var queue =
        new TaskQueue(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            clock);
    return new ApplicationContextRunner()
        .withUserConfiguration(Workers.class)
        .withBean(TaskQueue.class, () -> queue)
        .withBean(JdbcTemplate.class, () -> jdbc)
        .withBean(Clock.class, () -> clock)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(ChannelGateway.class, () -> gateway)
        .withBean(InboxService.class, () -> mock(InboxService.class))
        .withBean(NotificationService.class, () -> mock(NotificationService.class))
        .withBean(ReconciliationService.class, () -> mock(ReconciliationService.class))
        .withBean(ExportService.class, () -> mock(ExportService.class));
  }

  void oldCompletedTasks() {
    paid("archive-control", "100");
    var due =
        jdbc.queryForObject("SELECT MAX(due_at) FROM outbox_tasks", Timestamp.class).toInstant();
    var queue =
        new TaskQueue(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            Clock.fixed(due.plusSeconds(1), ZoneOffset.UTC));
    var tasks = queue.claimBatch(10);
    assertThat(tasks).hasSize(2);
    assertThat(queue.completeBatch(tasks)).allMatch(Boolean::booleanValue);
    jdbc.update("UPDATE outbox_tasks SET updated_at=?", Timestamp.from(Instant.now()));
    assertThat(count("outbox_tasks")).isEqualTo(2);
  }

  void runArchiveCallbacks(org.springframework.context.ApplicationContext context) {
    // Run only declared archive callbacks; unrelated security cleanup is outside this fixture.
    context
        .getBeansWithAnnotation(Component.class)
        .values()
        .forEach(
            bean ->
                Arrays.stream(bean.getClass().getDeclaredMethods())
                    .filter(
                        method ->
                            method.getName().equals("archiveCompletedTasks")
                                && method.isAnnotationPresent(Scheduled.class))
                    .forEach(method -> ReflectionTestUtils.invokeMethod(bean, method.getName())));
  }

  @Test
  void disabledArchiveRetainsQueryableFactsWhileTheLiveWorkerStillDispatches() {
    oldCompletedTasks();
    var gateway = mock(ChannelGateway.class);
    workers(gateway)
        .withPropertyValues("merchantpay.tasks-archive-enabled=false")
        .run(
            context -> {
              runArchiveCallbacks(context);
              assertThat(count("outbox_tasks"))
                  .as("Paused archive leaves completed facts in the hot table")
                  .isEqualTo(2);
              assertThat(count("outbox_task_history")).isZero();
              assertThat(count("outbox_task_records")).isEqualTo(2);
              var payment = createPayment("live-without-archive", "100");
              var worker = context.getBean(TaskWorker.class);
              ReflectionTestUtils.invokeMethod(worker, "poll");
              worker.close();
              verify(gateway).execute(argThat(task -> task.aggregateId().equals(payment.id())));
              assertThat(
                      jdbc.queryForObject(
                          "SELECT status FROM outbox_tasks WHERE aggregate_id=?",
                          String.class,
                          payment.id()))
                  .isEqualTo("DONE");
              assertThat(count("outbox_task_records")).isEqualTo(3);
              assertThat(count("ledger_journals")).isEqualTo(1);
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"default", "true"})
  void defaultAndExplicitEnabledArchiveKeepTheExistingMoveAndQueryableHistory(String mode) {
    oldCompletedTasks();
    var runner = workers(mock(ChannelGateway.class));
    if (mode.equals("true"))
      runner = runner.withPropertyValues("merchantpay.tasks-archive-enabled=true");
    runner.run(
        context -> {
          runArchiveCallbacks(context);
          assertThat(count("outbox_tasks")).isZero();
          assertThat(count("outbox_task_history")).isEqualTo(2);
          assertThat(count("outbox_task_records")).isEqualTo(2);
          assertThat(count("ledger_journals")).isEqualTo(1);
        });
  }

  @Test
  void disablingAllWorkersAlsoStopsArchiveMaintenance() {
    oldCompletedTasks();
    workers(mock(ChannelGateway.class))
        .withPropertyValues("merchantpay.workers-enabled=false")
        .run(
            context -> {
              runArchiveCallbacks(context);
              assertThat(context.getBeansOfType(TaskWorker.class)).isEmpty();
              assertThat(count("outbox_tasks")).isEqualTo(2);
              assertThat(count("outbox_task_history")).isZero();
            });
  }
}
