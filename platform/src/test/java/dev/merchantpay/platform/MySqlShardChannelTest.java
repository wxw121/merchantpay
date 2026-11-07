package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.channel.*;
import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.orchestration.NotificationService;
import dev.merchantpay.platform.orchestration.ShardChannels;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import dev.merchantpay.platform.sharding.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** SQL/adapter definitions for an independent MySQL environment; never executed locally. */
class MySqlShardChannelTest extends MySqlShardFixture {
  ShardChannels channels;
  ShardMoney money;
  ChannelApi api;
  InboxService inbox;
  ReceiptDirectory directory;
  Clock clock = Clock.systemUTC();

  @BeforeEach
  void channelRuntime() {
    core.update("INSERT INTO merchants(id,name,enabled) VALUES ('merchant-a','A',TRUE)");
    var tx = new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(0)));
    var json = JsonMapper.builder().build();
    var ledger = new LedgerService(core);
    var outbox =
        new OutboxService(core, clock, false, new EventJournal(core, clock, json, "POLLING"));
    var payments = new PaymentService(core, tx, ledger, outbox, clock, 16);
    var refunds = new RefundService(core, tx, payments, ledger, outbox, clock);
    money = new ShardMoney(routes, SOURCES, payments, refunds, ledger, outbox, clock);
    api = mock(ChannelApi.class);
    when(api.pay(anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              var request = (ChannelApi.Request) call.getArgument(2);
              return new ChannelApi.Receipt(
                  request.aggregateId(),
                  "PAYMENT",
                  "MOCK_A",
                  "SUCCEEDED",
                  "ref-" + request.aggregateId(),
                  request.amountMinor(),
                  "CNY",
                  "60");
            });
    var batches =
        new ChannelResultBatcher(
            tx.getTransactionManager(), new SimpleMeterRegistry(), 4, Duration.ofMillis(1));
    var gateway =
        new ChannelGateway(
            api,
            payments,
            refunds,
            core,
            json,
            "token",
            clock,
            tx,
            batches,
            new SimpleMeterRegistry());
    inbox = new InboxService(core, tx, outbox, gateway, json, clock, "secret", false);
    directory = new ReceiptDirectory(SOURCES.get(0), clock);
    var notifications =
        new NotificationService(
            payments,
            refunds,
            mock(MerchantService.class),
            new NotificationPolicy(""),
            json,
            core,
            clock);
    channels =
        new ShardChannels(
            routes,
            money,
            SOURCES,
            new TaskQueue(core, tx, clock),
            gateway,
            inbox,
            notifications,
            batches,
            directory,
            clock);
    inbox.setShards(channels);
  }

  PaymentService.PaymentView create(int bucket) {
    for (int n = 0; n < 10000; n++) {
      String order = "channel-" + n;
      if (PaymentBuckets.forBusinessKey("merchant-a", order) == bucket)
        return money.create(
            routes.currentRoute(bucket),
            "merchant-a",
            new PaymentService.CreatePayment(order, "10000", "CNY", "MOCK_A", null),
            "merchant");
    }
    throw new AssertionError();
  }

  @AfterEach
  void closeBatches() {
    if (channels != null) channels.close();
  }

  @Test
  void selectedWorkerAppliesMoneyAndAcknowledgesOnlyItsOriginalLease() {
    var payment = create(1);
    var tasks = channels.claim(routes.currentRoute(1), "FAST", 2);
    assertThat(tasks).hasSize(1);
    channels.execute(tasks.getFirst());
    channels.complete(tasks.getFirst());
    assertThat(new ShardReads(routes).payment("merchant-a", payment.id()).status())
        .isEqualTo("SUCCEEDED");
    assertThat(
            new JdbcTemplate(SOURCES.get(1))
                .queryForObject(
                    "SELECT status FROM outbox_tasks WHERE id=?",
                    String.class,
                    tasks.getFirst().task().id()))
        .isEqualTo("DONE");
    assertThat(core.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isZero();
    verify(api, times(1)).pay(eq("MOCK_A"), eq("token"), any());
    var target = new JdbcTemplate(SOURCES.get(1));
    target.update(
        "UPDATE outbox_tasks SET updated_at=? WHERE id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(172800)),
        tasks.getFirst().task().id());
    var queue =
        new TaskQueue(
            target,
            new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(1))),
            clock);
    assertThat(queue.archiveDone(10)).isEqualTo(1);
    assertThat(
            target.queryForObject(
                "SELECT logical_bucket FROM outbox_task_history WHERE id=?",
                Integer.class,
                tasks.getFirst().task().id()))
        .isEqualTo(1);
    assertThat(
            target.queryForObject(
                "SELECT lease_epoch FROM outbox_task_history WHERE id=?",
                Long.class,
                tasks.getFirst().task().id()))
        .isEqualTo(2L);
  }

  @Test
  void notificationUsesItsOriginalBucketAndAcknowledgesAfterTheLocalDeliveryPath() {
    create(1);
    var fast = channels.claim(routes.currentRoute(1), "FAST", 1).getFirst();
    channels.execute(fast);
    channels.complete(fast);
    var notice = channels.claim(routes.currentRoute(1), "NOTIFY", 1).getFirst();
    assertThat(notice.task().kind()).isEqualTo("PAYMENT_SUCCEEDED");
    assertThat(channels.execute(notice)).isTrue();
    channels.complete(notice);
    var target = new JdbcTemplate(SOURCES.get(1));
    assertThat(
            target.queryForObject(
                "SELECT status FROM outbox_tasks WHERE id=?", String.class, notice.task().id()))
        .isEqualTo("DONE");
    assertThat(
            target.queryForObject(
                "SELECT logical_bucket FROM outbox_tasks WHERE id=?",
                Integer.class,
                notice.task().id()))
        .isEqualTo(1);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM notification_deliveries", Integer.class))
        .isZero();
    assertThat(core.queryForObject("SELECT COUNT(*) FROM outbox_tasks", Integer.class)).isZero();
  }

  @Test
  void expiredNotificationWindowDiesOnItsOwnShardWithoutSending() {
    create(1);
    var fast = channels.claim(routes.currentRoute(1), "FAST", 1).getFirst();
    channels.execute(fast);
    channels.complete(fast);
    var target = new JdbcTemplate(SOURCES.get(1));
    target.update(
        "UPDATE outbox_tasks SET retry_started_at=? WHERE queue_class='NOTIFY'",
        java.sql.Timestamp.from(clock.instant().minusSeconds(86401)));
    var notice = channels.claim(routes.currentRoute(1), "NOTIFY", 1).getFirst();
    assertThat(channels.execute(notice)).isFalse();
    assertThat(
            target.queryForObject(
                "SELECT status FROM outbox_tasks WHERE id=?", String.class, notice.task().id()))
        .isEqualTo("DEAD");
    assertThat(target.queryForObject("SELECT COUNT(*) FROM notification_deliveries", Integer.class))
        .isZero();
    assertThat(core.queryForObject("SELECT COUNT(*) FROM outbox_tasks", Integer.class)).isZero();
  }

  @Test
  void sandboxShardCopyLooksUpItsOriginalBusinessOnTheSelectedSource() {
    var payment = create(1);
    var target = new JdbcTemplate(SOURCES.get(1));
    target.update("UPDATE payments SET channel='ALIPAY_SANDBOX' WHERE id=?", payment.id());
    var sandbox =
        new AlipaySandbox(core, JsonMapper.builder().build(), clock, false, "", "", "", "", "", "");
    var local =
        sandbox.forShard(
            target,
            action ->
                routes.write(
                    routes.currentRoute(1),
                    sql -> {
                      action.run();
                      return null;
                    }),
            () -> {});
    assertThatThrownBy(() -> local.query("PAYMENT", payment.id()))
        .isInstanceOfSatisfying(
            ApiException.class,
            failure -> assertThat(failure.code()).isEqualTo("SANDBOX_NOT_CONFIGURED"));
    assertThat(core.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isZero();
  }

  @Test
  void sandboxCheckoutReadsTheSelectedShardWithTheMerchantPredicate() {
    var payment = create(1);
    var target = new JdbcTemplate(SOURCES.get(1));
    target.update("UPDATE payments SET channel='ALIPAY_SANDBOX' WHERE id=?", payment.id());
    target.update(
        "INSERT INTO sandbox_checkouts(payment_id,qr_code,created_at) VALUES (?,?,?)",
        payment.id(),
        "https://example.test/checkout",
        java.sql.Timestamp.from(clock.instant()));
    var checkout = new ShardReads(routes).sandboxCheckout("merchant-a", payment.id());
    assertThat(checkout.qrCode()).isEqualTo("https://example.test/checkout");
    assertThatThrownBy(
            () -> new ShardReads(routes).sandboxCheckout("another-merchant", payment.id()))
        .isInstanceOf(ApiException.class);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM sandbox_checkouts", Integer.class))
        .isZero();
  }

  @Test
  void cdcSourceTopicCreatesOneBucketedTaskOnlyOnItsAuthoritativeShard() {
    var payment = create(1);
    var target = new JdbcTemplate(SOURCES.get(1));
    var event =
        target.queryForMap(
            "SELECT event_id,payload,logical_bucket FROM domain_events WHERE aggregateid=? AND event_type='CHANNEL_PAYMENT'",
            payment.id());
    assertThat(event.get("logical_bucket")).isEqualTo(1);
    target.update(
        "UPDATE domain_events SET delivery_mode='CDC' WHERE event_id=?", event.get("event_id"));
    target.update("DELETE FROM outbox_tasks WHERE event_id=?", event.get("event_id"));
    var prototype =
        new EventDelivery(
            core,
            new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(0))),
            clock,
            JsonMapper.builder().build());
    var routed = new ShardEventDelivery(routes, SOURCES, prototype);
    assertThatThrownBy(
            () ->
                routed.deliver("outbox.event.merchantpay.s0", 0, 1, (String) event.get("payload")))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
    routed.deliver("outbox.event.merchantpay.s1", 0, 1, (String) event.get("payload"));
    routed.deliver("outbox.event.merchantpay.s1", 0, 1, (String) event.get("payload"));
    assertThat(
            target.queryForObject(
                "SELECT logical_bucket FROM outbox_tasks WHERE event_id=?",
                Integer.class,
                event.get("event_id")))
        .isEqualTo(1);
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE event_id=?",
                Integer.class,
                event.get("event_id")))
        .isEqualTo(1);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM outbox_tasks", Integer.class)).isZero();
  }

  @Test
  void targetCdcDoesNotCreateBulkWorkWithoutATargetBulkExecutor() {
    routes.write(
        routes.currentRoute(1),
        sql -> {
          money
              .channelServices(1)
              .outbox()
              .enqueue("target-bulk", "RECONCILE", "batch-1", "merchant-a");
          return null;
        });
    var target = new JdbcTemplate(SOURCES.get(1));
    String payload =
        target.queryForObject(
            "SELECT payload FROM domain_events WHERE event_id='target-bulk'", String.class);
    target.update("UPDATE domain_events SET delivery_mode='CDC' WHERE event_id='target-bulk'");
    target.update("DELETE FROM outbox_tasks WHERE event_id='target-bulk'");
    var prototype =
        new EventDelivery(
            core,
            new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(0))),
            clock,
            JsonMapper.builder().build());
    var routed = new ShardEventDelivery(routes, SOURCES, prototype);
    assertThatThrownBy(() -> routed.deliver("outbox.event.merchantpay.s1", 0, 2, payload))
        .isInstanceOf(EventDelivery.InvalidEvent.class);
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE event_id='target-bulk'", Integer.class))
        .isZero();
  }

  @Test
  void targetQuarantineReplayKeepsTaskReceiptAndAuditOnTheOriginalShard() {
    var payment = create(1);
    var target = new JdbcTemplate(SOURCES.get(1));
    var event =
        target.queryForMap(
            "SELECT event_id,payload FROM domain_events WHERE aggregateid=? AND event_type='CHANNEL_PAYMENT'",
            payment.id());
    target.update(
        "UPDATE domain_events SET delivery_mode='CDC' WHERE event_id=?", event.get("event_id"));
    target.update("DELETE FROM outbox_tasks WHERE event_id=?", event.get("event_id"));
    var prototype =
        new EventDelivery(
            core,
            new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(0))),
            clock,
            JsonMapper.builder().build());
    var routed = new ShardEventDelivery(routes, SOURCES, prototype);
    String id =
        routed.quarantine(
            "outbox.event.merchantpay.s1",
            0,
            3,
            (String) event.get("payload"),
            "operator inspection");
    var ops = new Actor("ops", null, Set.of("OPS"));
    assertThatThrownBy(() -> routed.replay(1, id, new Actor("finance", null, Set.of("FINANCE"))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> routed.replay(0, id, ops)).isInstanceOf(ApiException.class);
    assertThat(routed.replay(1, id, ops)).containsEntry("status", "REPLAYED");
    assertThat(routed.replay(1, id, ops)).containsEntry("status", "REPLAYED");
    assertThat(
            target.queryForObject(
                "SELECT logical_bucket FROM outbox_tasks WHERE event_id=?",
                Integer.class,
                event.get("event_id")))
        .isEqualTo(1);
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM event_receipts WHERE event_id=?",
                Integer.class,
                event.get("event_id")))
        .isEqualTo(1);
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE operation='EVENT_REPLAY'", Integer.class))
        .isEqualTo(1);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM outbox_tasks", Integer.class)).isZero();
    assertThat(core.queryForObject("SELECT COUNT(*) FROM event_quarantine", Integer.class))
        .isZero();
  }

  @Test
  void staleRouteOrDamagedQuarantineEvidenceCannotReplayOnTarget() {
    var payment = create(1);
    var target = new JdbcTemplate(SOURCES.get(1));
    var event =
        target.queryForMap(
            "SELECT event_id,payload FROM domain_events WHERE aggregateid=? AND event_type='CHANNEL_PAYMENT'",
            payment.id());
    target.update(
        "UPDATE domain_events SET delivery_mode='CDC' WHERE event_id=?", event.get("event_id"));
    target.update("DELETE FROM outbox_tasks WHERE event_id=?", event.get("event_id"));
    var prototype =
        new EventDelivery(
            core,
            new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(0))),
            clock,
            JsonMapper.builder().build());
    var routed = new ShardEventDelivery(routes, SOURCES, prototype);
    String id =
        routed.quarantine(
            "outbox.event.merchantpay.s1",
            0,
            4,
            (String) event.get("payload"),
            "operator inspection");
    var ops = new Actor("ops", null, Set.of("OPS"));
    target.update("UPDATE event_quarantine SET payload='damaged' WHERE id=?", id);
    assertThatThrownBy(() -> routed.replay(1, id, ops)).isInstanceOf(ApiException.class);
    target.update("UPDATE event_quarantine SET payload=? WHERE id=?", event.get("payload"), id);
    target.update(
        "UPDATE domain_events SET logical_bucket=2 WHERE event_id=?", event.get("event_id"));
    assertThatThrownBy(() -> routed.replay(1, id, ops)).isInstanceOf(ApiException.class);
    target.update(
        "UPDATE domain_events SET logical_bucket=1 WHERE event_id=?", event.get("event_id"));
    core.update("UPDATE v3_shard_routes SET epoch=3 WHERE bucket=1");
    assertThatThrownBy(() -> routed.replay(1, id, ops)).isInstanceOf(ApiException.class);
    assertThat(
            target.queryForObject(
                "SELECT status FROM event_quarantine WHERE id=?", String.class, id))
        .isEqualTo("QUARANTINED");
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM outbox_tasks WHERE event_id=?",
                Integer.class,
                event.get("event_id")))
        .isZero();
  }

  @Test
  void expiredReclaimedAndStaleEpochTasksCannotCallTheChannelOrAcknowledge() {
    create(1);
    var execution = channels.claim(routes.currentRoute(1), "FAST", 1).getFirst();
    var target = new JdbcTemplate(SOURCES.get(1));
    target.update(
        "UPDATE outbox_tasks SET lease_until=? WHERE id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
        execution.task().id());
    assertThatThrownBy(() -> channels.renew(execution)).isInstanceOf(TaskQueue.LeaseLost.class);
    var replacement = channels.claim(routes.currentRoute(1), "FAST", 1).getFirst();
    assertThat(replacement.task().token()).isNotEqualTo(execution.task().token());
    assertThatThrownBy(() -> channels.execute(execution)).isInstanceOf(TaskQueue.LeaseLost.class);
    assertThatThrownBy(() -> channels.complete(execution)).isInstanceOf(TaskQueue.LeaseLost.class);
    core.update("UPDATE v3_shard_routes SET epoch=3 WHERE bucket=1");
    assertThatThrownBy(() -> channels.execute(replacement)).isInstanceOf(ApiException.class);
    verifyNoInteractions(api);
  }

  @Test
  void signedCallbackIsStoredOnItsOriginalShardAndBadSignaturesNeverChooseAStore() {
    var payment = create(1);
    String body =
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                new ChannelApi.Receipt(
                    payment.id(),
                    "PAYMENT",
                    "MOCK_A",
                    "SUCCEEDED",
                    "callback-ref",
                    "10000",
                    "CNY",
                    "60"));
    String timestamp = Long.toString(clock.instant().getEpochSecond());
    assertThatThrownBy(() -> inbox.receive(timestamp, "bad", body))
        .isInstanceOf(ApiException.class);
    var target = new JdbcTemplate(SOURCES.get(1));
    assertThat(target.queryForObject("SELECT COUNT(*) FROM inbox_notifications", Integer.class))
        .isZero();
    inbox.receive(timestamp, Signatures.hmac("secret", timestamp + "\n" + body), body);
    var work = channels.claim(routes.currentRoute(1), "INBOX", 1);
    assertThat(work).hasSize(1);
    channels.execute(work.getFirst());
    channels.complete(work.getFirst());
    assertThat(target.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("PROCESSED");
    assertThat(new ShardReads(routes).payment("merchant-a", payment.id()).status())
        .isEqualTo("SUCCEEDED");
    assertThat(core.queryForObject("SELECT COUNT(*) FROM inbox_notifications", Integer.class))
        .isZero();
  }

  @Test
  void globalClaimsSurviveLocalRollbackAndCannotBeReassignedToAnotherBusiness() {
    var first = create(1);
    var second = create(2);
    var receipt =
        new ChannelApi.Receipt(
            first.id(), "PAYMENT", "MOCK_A", "SUCCEEDED", "global-ref", "10000", "CNY", "60");
    assertThatThrownBy(
            () ->
                routes.write(
                    routes.currentRoute(1),
                    sql -> {
                      directory.claim(receipt, "merchant-a");
                      throw new IllegalStateException("local rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    directory.claim(receipt, "merchant-a");
    assertThatThrownBy(
            () ->
                directory.claim(
                    new ChannelApi.Receipt(
                        second.id(),
                        "PAYMENT",
                        "MOCK_A",
                        "SUCCEEDED",
                        "global-ref",
                        "10000",
                        "CNY",
                        "60"),
                    "merchant-a"))
        .isInstanceOf(ApiException.class);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM v3_receipt_claims", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void coreLocalTransactionDoesNotSelfWaitOnTheIndependentReceiptClaim() {
    var payment = create(0);
    var receipt =
        new ChannelApi.Receipt(
            payment.id(), "PAYMENT", "MOCK_A", "SUCCEEDED", "core-ref", "10000", "CNY", "60");
    assertThatThrownBy(
            () ->
                routes.write(
                    routes.currentRoute(0),
                    sql -> {
                      sql.update(
                          "UPDATE payments SET status='SUCCEEDED',channel_reference='core-ref' WHERE id=?",
                          payment.id());
                      directory.claim(receipt, "merchant-a");
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(new ShardReads(routes).payment("merchant-a", payment.id()).status())
        .isEqualTo("PROCESSING");
    assertThat(core.queryForObject("SELECT COUNT(*) FROM v3_receipt_claims", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void separateBucketsOnTheSamePhysicalShardKeepTheirResultTransactionsSeparate() throws Exception {
    var first = create(1);
    var second = create(5);
    var a = channels.claim(routes.currentRoute(1), "FAST", 1).getFirst();
    var b = channels.claim(routes.currentRoute(5), "FAST", 1).getFirst();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var one = executor.submit(() -> channels.execute(a));
      var two = executor.submit(() -> channels.execute(b));
      one.get(10, java.util.concurrent.TimeUnit.SECONDS);
      two.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(new ShardReads(routes).payment("merchant-a", first.id()).status())
        .isEqualTo("SUCCEEDED");
    assertThat(new ShardReads(routes).payment("merchant-a", second.id()).status())
        .isEqualTo("SUCCEEDED");
    assertThat(core.queryForObject("SELECT COUNT(*) FROM v3_receipt_claims", Integer.class))
        .isEqualTo(2);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void leaseOrEpochLostWhileTheNetworkRunsCannotApplyItsResponse(boolean epochChanges) {
    var payment = create(1);
    var expected = routes.currentRoute(1);
    var execution = channels.claim(expected, "FAST", 1).getFirst();
    var target = new JdbcTemplate(SOURCES.get(1));
    when(api.pay(anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              if (epochChanges) {
                core.update("UPDATE v3_shard_routes SET epoch=3 WHERE bucket=1");
                target.update("UPDATE v3_bucket_fences SET route_epoch=3 WHERE bucket=1");
              } else {
                target.update(
                    "UPDATE outbox_tasks SET lease_until=? WHERE id=?",
                    java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
                    execution.task().id());
                channels.claim(expected, "FAST", 1);
              }
              return new ChannelApi.Receipt(
                  payment.id(), "PAYMENT", "MOCK_A", "SUCCEEDED", "late-ref", "10000", "CNY", "60");
            });
    var failure = assertThatThrownBy(() -> channels.execute(execution));
    if (epochChanges) failure.isInstanceOf(ApiException.class);
    else failure.isInstanceOf(TaskQueue.LeaseLost.class);
    assertThat(
            target.queryForObject(
                "SELECT status FROM payments WHERE id=?", String.class, payment.id()))
        .isEqualTo("PROCESSING");
    assertThat(target.queryForObject("SELECT COUNT(*) FROM ledger_journals", Integer.class))
        .isZero();
    assertThat(core.queryForObject("SELECT COUNT(*) FROM v3_receipt_claims", Integer.class))
        .isZero();
  }

  @Test
  void heartbeatFromAnotherThreadKeepsTheCapturedRouteInsteadOfTheParentRoute() throws Exception {
    create(1);
    var execution = channels.claim(routes.currentRoute(1), "FAST", 1).getFirst();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      routes.write(
          routes.currentRoute(0),
          sql -> {
            try {
              executor
                  .submit(() -> channels.renew(execution))
                  .get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
            return null;
          });
    }
    assertThat(
            new JdbcTemplate(SOURCES.get(1))
                .queryForObject(
                    "SELECT lease_token FROM outbox_tasks WHERE id=?",
                    String.class,
                    execution.task().id()))
        .isEqualTo(execution.task().token());
    assertThat(core.queryForObject("SELECT COUNT(*) FROM outbox_tasks", Integer.class)).isZero();
  }

  @Test
  void conflictingCallbackKeepsRawConflictEvidenceAfterTheMoneyTransactionRollsBack() {
    var payment = create(1);
    String body =
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                new ChannelApi.Receipt(
                    payment.id(),
                    "PAYMENT",
                    "MOCK_A",
                    "SUCCEEDED",
                    "bad-amount",
                    "9999",
                    "CNY",
                    "60"));
    String timestamp = Long.toString(clock.instant().getEpochSecond());
    inbox.receive(timestamp, Signatures.hmac("secret", timestamp + "\n" + body), body);
    var execution = channels.claim(routes.currentRoute(1), "INBOX", 1).getFirst();
    channels.execute(execution);
    channels.complete(execution);
    var target = new JdbcTemplate(SOURCES.get(1));
    assertThat(target.queryForObject("SELECT status FROM inbox_notifications", String.class))
        .isEqualTo("CONFLICT");
    assertThat(target.queryForObject("SELECT COUNT(*) FROM evidence_conflicts", Integer.class))
        .isEqualTo(1);
    assertThat(new ShardReads(routes).payment("merchant-a", payment.id()).status())
        .isEqualTo("PROCESSING");
    assertThat(target.queryForObject("SELECT COUNT(*) FROM ledger_journals", Integer.class))
        .isZero();
    assertThat(core.queryForObject("SELECT COUNT(*) FROM v3_receipt_claims", Integer.class))
        .isZero();
  }

  @Test
  void aLiveTaskCanCommitWhenAPeerWithTheSameRouteHasExpired() throws Exception {
    var payment = create(1);
    var expected = routes.currentRoute(1);
    routes.write(
        expected,
        sql -> {
          money
              .channelServices(1)
              .outbox()
              .enqueue("extra-close", "CHANNEL_CLOSE", payment.id(), "merchant-a");
          return null;
        });
    var tasks = channels.claim(expected, "FAST", 2);
    var live = tasks.get(0).task();
    var stale = tasks.get(1).task();
    var target = new JdbcTemplate(SOURCES.get(1));
    target.update(
        "UPDATE outbox_tasks SET lease_until=? WHERE id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
        stale.id());
    var queue =
        new TaskQueue(
            target,
            new TransactionTemplate(new DataSourceTransactionManager(SOURCES.get(1))),
            clock);
    var original =
        new ChannelResultBatcher(
            new DataSourceTransactionManager(SOURCES.get(0)),
            new SimpleMeterRegistry(),
            2,
            Duration.ofMillis(50));
    try (var writer =
            original.forShard(
                action ->
                    routes.write(
                        expected,
                        jdbc -> {
                          action.run();
                          return null;
                        }));
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var ready = new java.util.concurrent.CountDownLatch(2);
      var start = new java.util.concurrent.CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                writer.execute(
                    "live",
                    () -> {
                      queue.assertPinned(live, expected.bucket(), expected.epoch());
                      target.update("INSERT INTO tx_probe VALUES (1,1)");
                    });
                return null;
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                writer.execute(
                    "stale", () -> queue.assertPinned(stale, expected.bucket(), expected.epoch()));
                return null;
              });
      assertThat(ready.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertThatThrownBy(() -> second.get(5, java.util.concurrent.TimeUnit.SECONDS))
          .hasCauseInstanceOf(TaskQueue.LeaseLost.class);
    } finally {
      original.close();
    }
    assertThat(target.queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class)).isEqualTo(1);
  }
}
