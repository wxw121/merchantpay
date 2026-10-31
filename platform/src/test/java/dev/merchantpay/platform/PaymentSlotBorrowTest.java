package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentSlotBorrowTest extends FinancialFixture {
  TransactionTemplate tx;

  @BeforeEach
  void slots() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V17__payment_admission_slots.sql"));
    tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    configure(true);
  }

  void configure(Boolean borrow) {
    var context =
        new ApplicationContextRunner()
            .withPropertyValues(
                "merchantpay.payment-admission-slots=true",
                "merchantpay.payment-max-inflight-per-shard=2")
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withBean(TransactionTemplate.class, () -> tx)
            .withBean(LedgerService.class, () -> new LedgerService(jdbc))
            .withBean(OutboxService.class, () -> new OutboxService(jdbc, Clock.systemUTC()))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(PaymentService.class);
    if (borrow != null)
      context = context.withPropertyValues("merchantpay.payment-admission-slot-borrow=" + borrow);
    context.run(
        c -> {
          assertThat(c).hasNotFailed();
          payments = c.getBean(PaymentService.class);
        });
  }

  PaymentService service(OutboxService outbox, boolean borrow) {
    return new PaymentService(
        jdbc, tx, new LedgerService(jdbc), outbox, Clock.systemUTC(), 2, true, borrow);
  }

  List<String> orders(int count) {
    return orders(0, count);
  }

  List<String> orders(int bucket, int count) {
    var result = new ArrayList<String>();
    for (int i = 0; result.size() < count; i++) {
      String order = "borrow-" + i;
      if ((Integer.parseInt(PaymentService.sha256("merchant-a\0" + order).substring(0, 2), 16) & 15)
          == bucket) result.add(order);
    }
    return result;
  }

  long occupied() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_admission_slots WHERE payment_id IS NOT NULL", Long.class);
  }

  int physicalBucket(String id) {
    return jdbc.queryForObject(
        "SELECT logical_shard FROM payment_admission_slots WHERE payment_id=?", Integer.class, id);
  }

  void fullHome(List<String> orders) {
    createPayment(orders.get(0), "100");
    createPayment(orders.get(1), "100");
  }

  void assertCapacity(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(503);
              assertThat(e.retryReason()).isEqualTo(ApiException.RetryReason.PAYMENT_CAPACITY);
            });
  }

  @Test
  void usesHomeFirstThenBorrowsWithoutChangingLogicalBusinessBucket() {
    var orders = orders(3);
    var first = createPayment(orders.get(0), "100");
    var second = createPayment(orders.get(1), "100");
    assertThat(physicalBucket(first.id())).isZero();
    assertThat(physicalBucket(second.id())).isZero();
    assertThatCode(() -> createPayment(orders.get(2), "100")).doesNotThrowAnyException();
    var third =
        payments.create(
            "merchant-a",
            new PaymentService.CreatePayment(orders.get(2), "100", "CNY", "MOCK_A", null));
    assertThat(physicalBucket(third.id())).isEqualTo(1);
    assertThat(third.id()).startsWith("p0_");
    assertThat(
            jdbc.queryForObject(
                "SELECT logical_shard FROM payments WHERE id=?", Integer.class, third.id()))
        .isZero();
    assertThat(occupied()).isEqualTo(3);
  }

  @Test
  void fullGlobalPoolRejectsAndRollsBackTheThirtyThirdPayment() {
    var orders = orders(33);
    for (int i = 0; i < 32; i++) createPayment(orders.get(i), "100");
    assertCapacity(() -> createPayment(orders.get(32), "100"));
    assertThat(occupied()).isEqualTo(32);
    assertThat(count("payments")).isEqualTo(32);
    assertThat(count("outbox_tasks")).isEqualTo(32);
    assertThat(count("outbox_events")).isEqualTo(32);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_admission_slots WHERE slot_no>=2 AND payment_id IS NOT NULL",
                Long.class))
        .isZero();
  }

  @Test
  void defaultAndOldConstructorRetainStrictHomeLimit() {
    configure(null);
    var orders = orders(3);
    fullHome(orders);
    assertCapacity(() -> createPayment(orders.get(2), "100"));
    payments =
        new PaymentService(
            jdbc,
            tx,
            new LedgerService(jdbc),
            new OutboxService(jdbc, Clock.systemUTC()),
            Clock.systemUTC(),
            2,
            true);
    assertCapacity(() -> createPayment(orders.get(2), "100"));
    assertThat(occupied()).isEqualTo(2);
  }

  @Test
  void unknownAndClosePendingKeepBorrowedOwnershipAndTerminalReusesItOnce() {
    var orders = orders(33);
    for (int i = 0; i < 32; i++) createPayment(orders.get(i), "100");
    var borrowed =
        payments.create(
            "merchant-a",
            new PaymentService.CreatePayment(orders.get(2), "100", "CNY", "MOCK_A", null));
    assertThat(physicalBucket(borrowed.id())).isEqualTo(1);
    payments.applyResult(borrowed.id(), new ChannelResult.Unknown("MOCK_A", "timeout"));
    payments.requestClose("merchant-a", borrowed.id());
    assertThat(occupied()).isEqualTo(32);
    assertThat(createPayment(orders.get(2), "100").id()).isEqualTo(borrowed.id());
    assertThatThrownBy(() -> createPayment(orders.get(2), "101")).isInstanceOf(ApiException.class);
    assertCapacity(() -> createPayment(orders.get(32), "100"));
    payments.applyResult(borrowed.id(), new ChannelResult.Closed("MOCK_A"));
    payments.applyResult(borrowed.id(), new ChannelResult.Closed("MOCK_A"));
    assertThat(occupied()).isEqualTo(31);
    assertThat(physicalBucket(createPayment(orders.get(32), "100").id())).isEqualTo(1);
    assertThat(occupied()).isEqualTo(32);
  }

  OutboxService failingOutbox() {
    return new OutboxService(jdbc, Clock.systemUTC()) {
      @Override
      public void enqueue(String event, String kind, String aggregate, String merchant) {
        super.enqueue(event, kind, aggregate, merchant);
        throw new IllegalStateException("injected outbox failure");
      }
    };
  }

  @Test
  void creationFailureRollsBackBorrowedSlotPaymentAndOutbox() {
    var orders = orders(3);
    fullHome(orders);
    payments = service(failingOutbox(), true);
    assertThatThrownBy(() -> createPayment(orders.get(2), "100")).hasMessageContaining("injected");
    assertThat(occupied()).isEqualTo(2);
    assertThat(count("payments")).isEqualTo(2);
    assertThat(count("outbox_tasks")).isEqualTo(2);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void outcomeFailureKeepsBorrowedOwnershipAndRollsBackMoney() {
    var orders = orders(3);
    fullHome(orders);
    var borrowed = createPayment(orders.get(2), "100");
    var normal = payments;
    payments = service(failingOutbox(), true);
    var success = new ChannelResult.Success("MOCK_A", "borrow-success", 100, "CNY");
    assertThatThrownBy(() -> payments.applyResult(borrowed.id(), success))
        .hasMessageContaining("injected");
    assertThat(occupied()).isEqualTo(3);
    assertThat(physicalBucket(borrowed.id())).isEqualTo(1);
    assertThat(count("ledger_journals")).isZero();
    assertThat(count("ledger_entries")).isZero();
    payments = normal;
    payments.applyResult(borrowed.id(), success);
    payments.applyResult(borrowed.id(), success);
    assertThat(occupied()).isEqualTo(2);
    assertThat(count("ledger_journals")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries",
                Long.class))
        .isZero();
  }

  @Test
  void concurrentSameHomeNeverOversellsTheGlobalPool() throws Exception {
    var orders = orders(40);
    var accepted = new ArrayList<String>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<String>>();
      for (var order : orders)
        futures.add(
            executor.submit(
                () -> {
                  try {
                    return createPayment(order, "100").id();
                  } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(503);
                    return null;
                  }
                }));
      for (var f : futures) {
        String id = f.get(15, TimeUnit.SECONDS);
        if (id != null) accepted.add(id);
      }
    }
    assertThat(accepted).hasSizeBetween(3, 32).doesNotHaveDuplicates();
    assertThat(occupied()).isEqualTo(accepted.size());
    assertThat(count("payments")).isEqualTo(accepted.size());
    assertThat(count("outbox_tasks")).isEqualTo(accepted.size());
  }

  @Test
  void concurrentIdempotentCreationOwnsOneSlot() throws Exception {
    var ids = new HashSet<String>();
    String order = orders(1).getFirst();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 12; i++)
        futures.add(executor.submit(() -> createPayment(order, "100").id()));
      for (var f : futures) ids.add(f.get(10, TimeUnit.SECONDS));
    }
    assertThat(ids).hasSize(1);
    assertThat(occupied()).isEqualTo(1);
    assertThat(count("payments")).isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(1);
  }

  @Test
  void conflictingRetryPreservesEveryWinnerFieldAndOwnership() {
    String order = orders(1).getFirst();
    var winner =
        payments.create(
            "merchant-a",
            new PaymentService.CreatePayment(
                order, "100", "CNY", "MOCK_A", "http://first.example/hook"));
    var before = jdbc.queryForMap("SELECT * FROM payments WHERE id=?", winner.id());
    var slotsBefore =
        jdbc.queryForList("SELECT * FROM payment_admission_slots WHERE payment_id=?", winner.id());
    assertThatThrownBy(
            () ->
                payments.create(
                    "merchant-a",
                    new PaymentService.CreatePayment(
                        order, "101", "CNY", "MOCK_B", "http://second.example/hook")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(409);
              assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
            });
    assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE id=?", winner.id()))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM payment_admission_slots WHERE payment_id=?", winner.id()))
        .isEqualTo(slotsBefore);
    assertThat(occupied()).isEqualTo(1);
    assertThat(count("payments")).isEqualTo(1);
    assertThat(count("outbox_tasks")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void lockedFreeHomeSlotsCanBorrowWithoutWaitingForTheirOwner() throws Exception {
    try (var holder = jdbc.getDataSource().getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      holder.setAutoCommit(false);
      try (var statement = holder.createStatement()) {
        statement.executeUpdate(
            "UPDATE payment_admission_slots SET slot_no=slot_no WHERE logical_shard=0 AND slot_no<2");
      }
      try {
        var future = executor.submit(() -> createPayment(orders(1).getFirst(), "100"));
        var payment = future.get(3, TimeUnit.SECONDS);
        assertThat(physicalBucket(payment.id())).isEqualTo(1);
      } finally {
        holder.rollback();
      }
    }
  }

  @Test
  void disablingBorrowingRequiresCrossBucketOwnersToFinish() {
    var orders = orders(3);
    fullHome(orders);
    var borrowed = createPayment(orders.get(2), "100");
    assertThatThrownBy(() -> configure(false)).hasStackTraceContaining("borrowed");
    payments.applyResult(borrowed.id(), new ChannelResult.Failure("MOCK_A", "declined"));
    configure(false);
    assertCapacity(() -> createPayment(orders(4).getLast(), "100"));
  }

  @Test
  void borrowingCannotBeEnabledForCounterMode() {
    assertThatThrownBy(
            () ->
                new PaymentService(
                    jdbc,
                    tx,
                    new LedgerService(jdbc),
                    new OutboxService(jdbc, Clock.systemUTC()),
                    Clock.systemUTC(),
                    2,
                    false,
                    true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ringWrapsFromTheLastBucketBackToZero() {
    var orders = orders(15, 3);
    fullHome(orders);
    var borrowed = createPayment(orders.get(2), "100");
    assertThat(physicalBucket(borrowed.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT logical_shard FROM payments WHERE id=?", Integer.class, borrowed.id()))
        .isEqualTo(15);
  }

  @Test
  void fullPoolSearchIsBoundedAndDoesNotUseTheHomeCountPrecheck() {
    var orders = orders(33);
    for (int i = 0; i < 32; i++) createPayment(orders.get(i), "100");
    var buckets = new ArrayList<Integer>();
    var db =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            if (sql.startsWith("SELECT slot_no FROM payment_admission_slots"))
              buckets.add((Integer) args[0]);
            return super.queryForList(sql, type, args);
          }

          @Override
          public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            assertThat(sql).doesNotStartWith("SELECT COUNT(*)>=?");
            return super.queryForObject(sql, type, args);
          }
        };
    payments =
        new PaymentService(
            db,
            tx,
            new LedgerService(jdbc),
            new OutboxService(jdbc, Clock.systemUTC()),
            Clock.systemUTC(),
            2,
            true,
            true);
    assertCapacity(() -> createPayment(orders.get(32), "100"));
    assertThat(buckets).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
  }

  @Test
  void uncommittedBorrowIsInvisibleAndOuterRollbackReturnsOwnership() {
    var orders = orders(3);
    fullHome(orders);
    tx.executeWithoutResult(
        status -> {
          var borrowed = createPayment(orders.get(2), "100");
          try (var reader = jdbc.getDataSource().getConnection()) {
            for (String table : List.of("payments", "payment_admission_slots")) {
              try (var statement =
                  reader.prepareStatement(
                      "SELECT COUNT(*) FROM "
                          + table
                          + " WHERE "
                          + (table.equals("payments") ? "id" : "payment_id")
                          + "=?")) {
                statement.setQueryTimeout(2);
                statement.setString(1, borrowed.id());
                try (var rs = statement.executeQuery()) {
                  assertThat(rs.next()).isTrue();
                  assertThat(rs.getLong(1)).isZero();
                }
              }
            }
          } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
          }
          status.setRollbackOnly();
        });
    assertThat(occupied()).isEqualTo(2);
    assertThat(count("payments")).isEqualTo(2);
    assertThat(count("outbox_tasks")).isEqualTo(2);
  }
}
