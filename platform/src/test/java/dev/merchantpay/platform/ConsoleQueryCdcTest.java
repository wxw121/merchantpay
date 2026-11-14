package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.web.*;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConsoleQueryCdcTest extends DomainEventTest {
  @Test
  void manualQueryUsesTheAuthoritativeCdcOutbox() throws Exception {
    var payment = paid("manual-cdc", "1000");
    long before = count("outbox_tasks");
    jdbc.update("UPDATE event_transport SET mode='CDC'");
    var cdc =
        new OutboxService(
            jdbc,
            Clock.systemUTC(),
            false,
            new EventJournal(jdbc, Clock.systemUTC(), JsonMapper.builder().build(), "CDC"));
    var controller =
        new ConsoleController(
            new Access(),
            new Queries(jdbc, Clock.systemUTC(), "test"),
            payments,
            refunds,
            mock(MerchantService.class),
            null,
            jdbc,
            tx,
            Clock.systemUTC(),
            cdc);
    var query = ConsoleController.class.getDeclaredMethod("query", String.class, String.class);
    query.setAccessible(true);
    ScopedValue.where(Access.CURRENT, new Access.Caller("finance", null, Set.of("FINANCE")))
        .call(() -> query.invoke(controller, payment.id(), "merchant-a"));
    assertThat(count("outbox_tasks")).isEqualTo(before);
    assertThat(count("domain_events")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT delivery_mode FROM domain_events", String.class))
        .isEqualTo("CDC");
    var delivery = new EventDelivery(jdbc, tx, Clock.systemUTC(), JsonMapper.builder().build());
    delivery.deliver(
        "events", 0, 1, jdbc.queryForObject("SELECT payload FROM domain_events", String.class));
    assertThat(count("outbox_tasks")).isEqualTo(before + 1);
  }
}
