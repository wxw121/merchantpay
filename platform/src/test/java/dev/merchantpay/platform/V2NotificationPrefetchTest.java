package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.NotificationPolicy;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.orchestration.NotificationService;
import dev.merchantpay.platform.outbox.TaskQueue;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class V2NotificationPrefetchTest extends FinancialFixture {
  NotificationService service() {
    return new NotificationService(
        payments,
        refunds,
        mock(MerchantService.class),
        new NotificationPolicy(""),
        JsonMapper.builder().build(),
        jdbc,
        Clock.systemUTC());
  }

  TaskQueue.Task task(String merchant, String payment) {
    return new TaskQueue.Task(
        "task", "payment-success:" + payment, "PAYMENT_SUCCEEDED", payment, merchant, 1, "lease");
  }

  @Test
  void prefetchSnapshotCannotCrossTenantOrClaimedLease() {
    var p = paid("notify", "1000");
    var task = task("merchant-a", p.id());
    var service = service();
    var snapshot = service.prefetch(List.of(task)).get("task");
    assertThat(snapshot.payment().id()).isEqualTo(p.id());
    assertThatThrownBy(() -> service.deliver(task("merchant-b", p.id()), snapshot))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.prefetch(List.of(task("merchant-b", p.id()))))
        .isInstanceOf(IllegalArgumentException.class);
    service.deliver(task, snapshot);
  }

  @Test
  void duplicateTasksAndOversizedBatchesAreRejected() {
    var p = paid("duplicate", "1000");
    var task = task("merchant-a", p.id());
    assertThatThrownBy(() -> service().prefetch(List.of(task, task)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service().prefetch(Collections.nCopies(65, task)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
