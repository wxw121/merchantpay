package dev.merchantpay.platform.orchestration;

import dev.merchantpay.platform.common.DatabaseRole;
import dev.merchantpay.platform.outbox.TaskQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Archive maintenance can pause independently of live transaction workers. */
@Component
@ConditionalOnProperty(
    name = {"merchantpay.workers-enabled", "merchantpay.tasks-archive-enabled"},
    havingValue = "true",
    matchIfMissing = true)
public class TaskArchiver {
  private final TaskQueue queue;

  public TaskArchiver(TaskQueue queue) {
    this.queue = queue;
  }

  @Scheduled(fixedDelay = 250, initialDelay = 1000)
  void archiveCompletedTasks() {
    DatabaseRole.worker(() -> queue.archiveDone(500));
  }
}
