package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.merchantpay.platform.common.NotificationPolicy;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.reconciliation.ExportService;
import dev.merchantpay.platform.web.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

class ExportTest extends FinancialFixture {
  @TempDir Path directory;
  ExportService exports;
  dev.merchantpay.platform.outbox.TaskQueue queue;
  final Clock exportClock =
      Clock.fixed(
          java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
          java.time.ZoneOffset.UTC);

  @BeforeEach
  void exportSchema() throws Exception {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V5__exports.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V8__export_ownership.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V10__export_execution.sql"));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    queue = new dev.merchantpay.platform.outbox.TaskQueue(jdbc, tx, exportClock);
    exports =
        new ExportService(
            jdbc,
            tx,
            new OutboxService(jdbc, exportClock),
            new Queries(jdbc, Clock.systemUTC(), "test"),
            Clock.systemUTC(),
            directory.toString(),
            queue);
  }

  @Test
  void missingPublishedArchiveFailsBeforeReturningADownload() throws Exception {
    String id = exports.create("merchant-a", "owner-a", "payments", null, null);
    exports.process(id, queue.claimBatch(1, true).getFirst());
    java.nio.file.Files.delete(exports.file(id, "merchant-a", "owner-a"));
    assertThatThrownBy(() -> exports.file(id, "merchant-a", "owner-a"))
        .isInstanceOf(java.io.UncheckedIOException.class);
  }

  @Test
  void backgroundExportContainsOnlyItsTenantAndCannotBeDownloadedByAnother() throws Exception {
    var payment = paid("export-a", "10000");
    var createdAt =
        jdbc.queryForObject(
                "SELECT created_at FROM payments WHERE id=?",
                java.sql.Timestamp.class,
                payment.id())
            .toInstant();
    payments.create(
        "merchant-b",
        new dev.merchantpay.platform.payment.PaymentService.CreatePayment(
            "export-b", "20000", "CNY", "MOCK_B", null));
    String id =
        exports.create(
            "merchant-a",
            "owner-a",
            "payments",
            createdAt.minusSeconds(1).toString(),
            createdAt.plusSeconds(1).toString());
    var task = queue.claimBatch(1, true).getFirst();
    exports.process(id, task);
    try (var zip = new ZipFile(exports.file(id, "merchant-a", "owner-a").toFile())) {
      var data =
          new String(
              zip.getInputStream(zip.getEntry("payments-001.csv")).readAllBytes(),
              StandardCharsets.UTF_8);
      assertThat(data).contains("export-a", "10000").doesNotContain("export-b", "20000");
      assertThat(zip.getEntry("manifest.json")).isNotNull();
    }
    assertThatThrownBy(() -> exports.file(id, "merchant-b", "owner-a"))
        .isInstanceOf(dev.merchantpay.platform.common.ApiException.class);
    exports.process(id, task);
    assertThat(jdbc.queryForObject("SELECT row_count FROM exports WHERE id=?", Long.class, id))
        .isEqualTo(1);
  }

  @Test
  void downloadIsBoundToTheRequesterAndExpiresEvenForTheSameMerchant() {
    String id = exports.create("merchant-a", "owner-a", "payments", null, null);
    exports.process(id, queue.claimBatch(1, true).getFirst());
    assertThatThrownBy(() -> exports.file(id, "merchant-a", "owner-b"))
        .isInstanceOf(dev.merchantpay.platform.common.ApiException.class);
    jdbc.update(
        "UPDATE exports SET expires_at=? WHERE id=?",
        java.sql.Timestamp.from(exportClock.instant().minusSeconds(1)),
        id);
    assertThatThrownBy(() -> exports.file(id, "merchant-a", "owner-a"))
        .isInstanceOf(dev.merchantpay.platform.common.ApiException.class)
        .hasMessageContaining("过期");
  }

  MockMvc downloadController() {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var policy = new NotificationPolicy("");
    return MockMvcBuilders.standaloneSetup(
            new ReconciliationController(
                new Access(),
                null,
                exports,
                new Queries(jdbc, exportClock, "test"),
                null,
                new MerchantService(jdbc, null, policy, tx, exportClock),
                new OutboxService(jdbc, exportClock),
                jdbc,
                tx))
        .setControllerAdvice(
            new ApiErrors(
                new dev.merchantpay.platform.web.ApiAvailability(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry())))
        .build();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ADMIN", "FINANCE", "MERCHANT"})
  void downloadAuditBelongsToTheExportMerchantForPlatformAndMerchantUsers(String role)
      throws Exception {
    String id = exports.create("merchant-a", "export-requester", "payments", null, null);
    exports.process(id, queue.claimBatch(1, true).getFirst());
    var mvc = downloadController();
    var caller =
        new Access.Caller(
            "export-requester",
            role.equals("MERCHANT") ? "merchant-a" : null,
            java.util.Set.of(role));
    var response =
        ScopedValue.where(Access.CURRENT, caller)
            .call(
                () ->
                    mvc.perform(get("/api/console/exports/{id}/download", id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse());
    assertThat(response.getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
    var audit =
        jdbc.queryForMap("SELECT merchant_id,actor,operation,resource_id FROM audit_records");
    assertThat(audit)
        .containsEntry("merchant_id", "merchant-a")
        .containsEntry("actor", "export-requester")
        .containsEntry("operation", "EXPORT_DOWNLOAD")
        .containsEntry("resource_id", id);
    var queries = new Queries(jdbc, exportClock, "test");
    String from = exportClock.instant().minusSeconds(1).toString();
    String until = exportClock.instant().plusSeconds(1).toString();
    assertThat(
            queries
                .list("audit", "merchant-a", null, from, until, null, "export-requester")
                .items())
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .containsEntry("resourceId", id)
                    .containsEntry("merchantId", "merchant-a"));
    assertThat(
            queries
                .list("audit", "merchant-b", null, from, until, null, "export-requester")
                .items())
        .isEmpty();
    assertThat(queries.list("audit", null, null, from, until, null, "export-requester").items())
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("resourceId", id));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"other-merchant", "other-requester", "expired"})
  void rejectedDownloadCannotCreateAnAuditForAnotherMerchantOrRequester(String reason)
      throws Exception {
    String id = exports.create("merchant-a", "export-requester", "payments", null, null);
    exports.process(id, queue.claimBatch(1, true).getFirst());
    if (reason.equals("expired")) {
      jdbc.update(
          "UPDATE exports SET expires_at=? WHERE id=?",
          java.sql.Timestamp.from(exportClock.instant().minusSeconds(1)),
          id);
    }
    var caller =
        new Access.Caller(
            reason.equals("other-requester") ? "other-user" : "export-requester",
            reason.equals("other-merchant") ? "merchant-b" : "merchant-a",
            java.util.Set.of("MERCHANT"));
    var mvc = downloadController();
    var response =
        ScopedValue.where(Access.CURRENT, caller)
            .call(
                () ->
                    mvc.perform(get("/api/console/exports/{id}/download", id))
                        .andReturn()
                        .getResponse());
    assertThat(response.getStatus()).isEqualTo(reason.equals("expired") ? 410 : 404);
    assertThat(count("audit_records")).isZero();
  }

  @Test
  void aReclaimedExportCannotBePublishedByTheOldWorker() throws Exception {
    String id = exports.create("merchant-a", "owner-a", "payments", null, null);
    jdbc.update(
        "UPDATE outbox_tasks SET due_at=? WHERE aggregate_id=?",
        java.sql.Timestamp.from(java.time.Instant.EPOCH),
        id);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var queue = new dev.merchantpay.platform.outbox.TaskQueue(jdbc, tx, exportClock);
    var old = queue.claimBatch(1, true).getFirst();
    jdbc.update(
        "UPDATE outbox_tasks SET lease_until=? WHERE id=?",
        java.sql.Timestamp.from(exportClock.instant().minusSeconds(1)),
        old.id());
    var replacement = queue.claimBatch(1, true).getFirst();
    exports.process(id, old);
    assertThat(jdbc.queryForObject("SELECT status FROM exports WHERE id=?", String.class, id))
        .isEqualTo("QUEUED");
    exports.process(id, replacement);
    byte[] original = java.nio.file.Files.readAllBytes(exports.file(id, "merchant-a", "owner-a"));
    exports.process(id, old);
    assertThat(java.nio.file.Files.readAllBytes(exports.file(id, "merchant-a", "owner-a")))
        .isEqualTo(original);
    assertThat(jdbc.queryForObject("SELECT status FROM exports WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void pausedOldExportCannotReplaceANewerCompletedSnapshot(boolean independentRetry)
      throws Exception {
    var payment = paid("snapshot-existing", "10000");
    var createdAt =
        jdbc.queryForObject(
                "SELECT created_at FROM payments WHERE id=?",
                java.sql.Timestamp.class,
                payment.id())
            .toInstant();
    String id =
        exports.create(
            "merchant-a",
            "owner-a",
            "payments",
            createdAt.minusSeconds(1).toString(),
            createdAt.plusSeconds(1).toString());
    jdbc.update(
        "UPDATE outbox_tasks SET due_at=? WHERE aggregate_id=?",
        java.sql.Timestamp.from(java.time.Instant.EPOCH),
        id);
    var old = queue.claimBatch(1, true).getFirst();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var pausing = new PausingQueue(jdbc, tx);
    var oldWorker =
        new ExportService(
            jdbc,
            tx,
            new OutboxService(jdbc, Clock.systemUTC()),
            new Queries(jdbc, Clock.systemUTC(), "test"),
            Clock.systemUTC(),
            directory.toString(),
            pausing);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var execution = executor.submit(() -> oldWorker.process(id, old));
      try {
        assertThat(pausing.paused.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        if (independentRetry) {
          tx.executeWithoutResult(
              transaction ->
                  new OutboxService(jdbc, Clock.systemUTC())
                      .enqueue("retry:" + id, "EXPORT", id, "merchant-a"));
          jdbc.update(
              "UPDATE outbox_tasks SET due_at=? WHERE event_id=?",
              java.sql.Timestamp.from(java.time.Instant.EPOCH),
              "retry:" + id);
        } else {
          jdbc.update(
              "UPDATE outbox_tasks SET lease_until=? WHERE id=?",
              java.sql.Timestamp.from(exportClock.instant().minusSeconds(1)),
              old.id());
        }
        var replacement = queue.claimBatch(1, true).getFirst();
        var refund =
            refunds.create(
                "merchant-a",
                payment.id(),
                new dev.merchantpay.platform.refund.RefundService.CreateRefund(
                    "newer-snapshot", "3000"));
        refunds.applyResult(
            refund.id(),
            new dev.merchantpay.platform.common.ChannelResult.Success(
                "MOCK_A", "newer-refund", 3000, "CNY"));
        exports.process(id, replacement);
        var winningFile = exports.file(id, "merchant-a", "owner-a");
        byte[] winningBytes = java.nio.file.Files.readAllBytes(winningFile);
        pausing.resume.countDown();
        execution.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(exports.file(id, "merchant-a", "owner-a")).isEqualTo(winningFile);
        assertThat(java.nio.file.Files.readAllBytes(winningFile)).isEqualTo(winningBytes);
        assertThat(jdbc.queryForObject("SELECT row_count FROM exports WHERE id=?", Long.class, id))
            .isEqualTo(1);
      } finally {
        pausing.resume.countDown();
      }
    }
  }
}
