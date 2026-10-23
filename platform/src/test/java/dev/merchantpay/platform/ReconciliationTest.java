package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.reconciliation.ReconciliationService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ReconciliationTest extends FinancialFixture {
  @TempDir Path directory;
  ReconciliationService reconciliation;
  dev.merchantpay.platform.outbox.TaskQueue queue;
  final java.util.Map<String, dev.merchantpay.platform.outbox.TaskQueue.Task> executions =
      new java.util.HashMap<>();

  @BeforeEach
  void reconciliationSchema() throws Exception {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V4__reconciliation.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V9__reconciliation_execution.sql"));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    queue = new dev.merchantpay.platform.outbox.TaskQueue(jdbc, tx, Clock.systemUTC());
    reconciliation =
        new ReconciliationService(
            jdbc,
            tx,
            new OutboxService(jdbc, Clock.systemUTC()),
            Clock.systemUTC(),
            directory.toString(),
            queue);
  }

  void process(String id) {
    jdbc.update(
        "UPDATE outbox_tasks SET due_at=? WHERE aggregate_id=?",
        java.sql.Timestamp.from(Instant.EPOCH),
        id);
    var task = executions.computeIfAbsent(id, ignored -> queue.claimBatch(1, true).getFirst());
    reconciliation.process(id, task);
  }

  @Test
  void aReclaimedReconciliationCannotBeRegressedByItsOldWorker() throws Exception {
    String id = submit("PAYMENT,remote,SUCCEEDED,100,1,CNY,x\n", 1, 100, 1);
    jdbc.update(
        "UPDATE outbox_tasks SET due_at=? WHERE aggregate_id=?",
        java.sql.Timestamp.from(Instant.EPOCH),
        id);
    var old = queue.claimBatch(1, true).getFirst();
    jdbc.update(
        "UPDATE outbox_tasks SET lease_until=? WHERE id=?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
        old.id());
    var replacement = queue.claimBatch(1, true).getFirst();
    reconciliation.process(id, old);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUEUED");
    reconciliation.process(id, replacement);
    long issues = count("recon_issues");
    java.nio.file.Files.delete(reconciliation.original(id, "merchant-a"));
    reconciliation.process(id, old);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
    assertThat(count("recon_issues")).isEqualTo(issues);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void pausedOldParserCannotRegressANewerCompletedBatch(boolean independentRetry) throws Exception {
    String id = submit("PAYMENT,remote,SUCCEEDED,100,1,CNY,x\n", 1, 100, 1);
    jdbc.update(
        "UPDATE outbox_tasks SET due_at=? WHERE aggregate_id=?",
        java.sql.Timestamp.from(Instant.EPOCH),
        id);
    var old = queue.claimBatch(1, true).getFirst();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var pausing = new PausingQueue(jdbc, tx);
    var oldWorker =
        new ReconciliationService(
            jdbc,
            tx,
            new OutboxService(jdbc, Clock.systemUTC()),
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
                      .enqueue("retry:" + id, "RECONCILE", id, "merchant-a"));
          jdbc.update(
              "UPDATE outbox_tasks SET due_at=? WHERE event_id=?",
              java.sql.Timestamp.from(Instant.EPOCH),
              "retry:" + id);
        } else {
          jdbc.update(
              "UPDATE outbox_tasks SET lease_until=? WHERE id=?",
              java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
              old.id());
        }
        var replacement = queue.claimBatch(1, true).getFirst();
        reconciliation.process(id, replacement);
        pausing.resume.countDown();
        execution.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM recon_batches WHERE id=?", String.class, id))
            .isEqualTo("COMPLETE");
        assertThat(count("recon_issues")).isEqualTo(1);
      } finally {
        pausing.resume.countDown();
      }
    }
  }

  String submit(String rows, int count, long total, int version) throws Exception {
    var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    String body =
        "#merchantpay-bill-v1,MOCK_A,merchant-a,"
            + day
            + ","
            + count
            + ","
            + total
            + "\noperation,aggregateId,status,amountMinor,feeMinor,currency,reference\n"
            + rows;
    return reconciliation.submit(
        "merchant-a",
        "MOCK_A",
        day,
        version,
        PaymentService.sha256(body),
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void matchDetectsBothSingleSidesAndFieldDifferencesWithoutChangingLedger() throws Exception {
    var payment = paid("bill-match", "10000");
    paid("bill-local-only", "100");
    long journals = count("ledger_journals");
    String id =
        submit(
            "PAYMENT,"
                + payment.id()
                + ",SUCCEEDED,9999,59,CNY,"
                + payment.channelReference()
                + "\nPAYMENT,unknown,SUCCEEDED,200,1,CNY,remote\n",
            2,
            10199,
            1);
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
    assertThat(
            jdbc.queryForList(
                "SELECT issue_type FROM recon_issues WHERE batch_id=?", String.class, id))
        .contains("AMOUNT_MISMATCH", "FEE_MISMATCH", "CHANNEL_ONLY", "LOCAL_ONLY");
    assertThat(count("ledger_journals")).isEqualTo(journals);
    long issues = count("recon_issues");
    process(id);
    assertThat(count("recon_issues")).isEqualTo(issues);
  }

  @Test
  void wrongTotalsAreQuarantinedAndNeverPublishPartialResults() throws Exception {
    String id = submit("PAYMENT,remote,SUCCEEDED,100,1,CNY,x\n", 2, 200, 1);
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUARANTINED");
    assertThat(count("recon_issues")).isZero();
  }

  @Test
  void duplicatesAreScopedByBatchAndOperationAndIncludeEveryRepeatedLine() throws Exception {
    String id =
        submit(
            "PAYMENT,duplicate,SUCCEEDED,100,1,CNY,a\n"
                + "PAYMENT,duplicate,SUCCEEDED,100,1,CNY,b\n"
                + "PAYMENT,duplicate,SUCCEEDED,100,1,CNY,c\n"
                + "REFUND,duplicate,SUCCEEDED,100,1,CNY,d\n"
                + "REFUND,duplicate,SUCCEEDED,100,1,CNY,e\n"
                + "PAYMENT,cross_operation,SUCCEEDED,100,1,CNY,f\n"
                + "REFUND,cross_operation,SUCCEEDED,100,1,CNY,g\n"
                + "PAYMENT,single,SUCCEEDED,100,1,CNY,h\n",
            8,
            800,
            1);
    process(id);
    assertThat(
            jdbc.queryForList(
                "SELECT line_no FROM recon_issues WHERE batch_id=? AND issue_type='DUPLICATE_CHANNEL_RECORD' ORDER BY line_no",
                Long.class,
                id))
        .containsExactly(1L, 2L, 3L, 4L, 5L);
    String other = submit("PAYMENT,duplicate,SUCCEEDED,100,1,CNY,a\n", 1, 100, 2);
    process(other);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_issues WHERE batch_id=? AND issue_type='DUPLICATE_CHANNEL_RECORD'",
                Long.class,
                other))
        .isZero();
    long issues = count("recon_issues");
    process(id);
    assertThat(count("recon_issues")).isEqualTo(issues);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ff", "e4b8"})
  void malformedUtf8IsQuarantinedWithoutRetryingOrPublishingPartialResults(String invalidHex)
      throws Exception {
    var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    byte[] prefix =
        ("#merchantpay-bill-v1,MOCK_A,merchant-a,"
                + day
                + ",1,100\noperation,aggregateId,status,amountMinor,feeMinor,currency,reference\n"
                + "PAYMENT,remote,SUCCEEDED,100,1,CNY,ref")
            .getBytes(StandardCharsets.UTF_8);
    byte[] invalid = java.util.HexFormat.of().parseHex(invalidHex);
    byte[] body = java.util.Arrays.copyOf(prefix, prefix.length + invalid.length);
    System.arraycopy(invalid, 0, body, prefix.length, invalid.length);
    String digest =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
    String id =
        reconciliation.submit(
            "merchant-a", "MOCK_A", day, 1, digest, new ByteArrayInputStream(body));
    assertThatCode(() -> process(id)).doesNotThrowAnyException();
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUARANTINED");
    assertThat(
            jdbc.queryForObject(
                "SELECT error_detail FROM recon_batches WHERE id=?", String.class, id))
        .contains("UTF-8");
    assertThat(java.nio.file.Files.readAllBytes(reconciliation.original(id, "merchant-a")))
        .isEqualTo(body);
    assertThat(count("recon_issues")).isZero();
    process(id);
    assertThat(count("recon_issues")).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "aggregateId",
        "reference",
        "amountMinor",
        "feeMinor",
        "metadata",
        "columns",
        "standaloneCr",
        "finalCr"
      })
  void carriageReturnsCannotBeRemovedToMakeAnInvalidBillMatch(String location) throws Exception {
    var payment = paid("carriage-return", "10000");
    String row =
        "PAYMENT," + payment.id() + ",SUCCEEDED,10000,60,CNY," + payment.channelReference();
    var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    String valid =
        "#merchantpay-bill-v1,MOCK_A,merchant-a,"
            + day
            + ",1,10000\r\n"
            + "operation,aggregateId,status,amountMinor,feeMinor,currency,reference\r\n"
            + row
            + "\r\n";
    String body =
        switch (location) {
          case "aggregateId" ->
              valid.replace(
                  payment.id(), payment.id().substring(0, 8) + "\r" + payment.id().substring(8));
          case "reference" ->
              valid.replace(payment.channelReference(), "channel-\rcarriage-return");
          case "amountMinor" -> valid.replace(",SUCCEEDED,10000,", ",SUCCEEDED,10\r000,");
          case "feeMinor" -> valid.replace(",60,CNY,", ",6\r0,CNY,");
          case "metadata" -> valid.replace("merchant-a", "merchant-\ra");
          case "columns" -> valid.replace("aggregateId", "aggregate\rId");
          case "standaloneCr" -> valid.replace(row, row + "\r");
          case "finalCr" -> valid.substring(0, valid.length() - 1);
          default -> throw new IllegalArgumentException(location);
        };
    byte[] original = body.getBytes(StandardCharsets.UTF_8);
    long journals = count("ledger_journals"), entries = count("ledger_entries");
    String id =
        reconciliation.submit(
            "merchant-a",
            "MOCK_A",
            day,
            1,
            PaymentService.sha256(body),
            new ByteArrayInputStream(original));
    assertThatCode(() -> process(id)).doesNotThrowAnyException();
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUARANTINED");
    assertThat(java.nio.file.Files.readAllBytes(reconciliation.original(id, "merchant-a")))
        .isEqualTo(original);
    assertThat(count("recon_rows")).isZero();
    assertThat(count("recon_local_rows")).isZero();
    assertThat(count("recon_issues")).isZero();
    assertThat(count("ledger_journals")).isEqualTo(journals);
    assertThat(count("ledger_entries")).isEqualTo(entries);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"LF,true", "LF,false", "CRLF,true", "CRLF,false"})
  void supportedLineEndingsPreserveRowsAndAllowAnUnterminatedFinalLine(
      String endingName, boolean finalTerminator) throws Exception {
    var payment = paid("line-endings", "10000");
    String row =
        "PAYMENT," + payment.id() + ",SUCCEEDED,10000,60,CNY," + payment.channelReference();
    String ending = endingName.equals("LF") ? "\n" : "\r\n";
    var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    String body =
        "#merchantpay-bill-v1,MOCK_A,merchant-a,"
            + day
            + ",1,10000"
            + ending
            + "operation,aggregateId,status,amountMinor,feeMinor,currency,reference"
            + ending
            + row
            + (finalTerminator ? ending : "");
    byte[] original = body.getBytes(StandardCharsets.UTF_8);
    long journals = count("ledger_journals"), entries = count("ledger_entries");
    String id =
        reconciliation.submit(
            "merchant-a",
            "MOCK_A",
            day,
            1,
            PaymentService.sha256(body),
            new ByteArrayInputStream(original));
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
    assertThat(
            jdbc.queryForObject(
                "SELECT raw_line FROM recon_rows WHERE batch_id=?", String.class, id))
        .isEqualTo(row);
    assertThat(java.nio.file.Files.readAllBytes(reconciliation.original(id, "merchant-a")))
        .isEqualTo(original);
    assertThat(count("recon_issues")).isZero();
    assertThat(count("ledger_journals")).isEqualTo(journals);
    assertThat(count("ledger_entries")).isEqualTo(entries);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"1024,账单元数据不匹配", "1025,账单单行超过 1024 字符"})
  void lineLengthLimitCountsContentWithoutTheCrLfTerminator(int length, String error)
      throws Exception {
    String body = "x".repeat(length) + "\r\n";
    byte[] original = body.getBytes(StandardCharsets.UTF_8);
    String id =
        reconciliation.submit(
            "merchant-a",
            "MOCK_A",
            LocalDate.now(ZoneId.of("Asia/Shanghai")),
            1,
            PaymentService.sha256(body),
            new ByteArrayInputStream(original));
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUARANTINED");
    assertThat(
            jdbc.queryForObject(
                "SELECT error_detail FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo(error);
    assertThat(java.nio.file.Files.readAllBytes(reconciliation.original(id, "merchant-a")))
        .isEqualTo(original);
    assertThat(count("recon_rows")).isZero();
    assertThat(count("recon_issues")).isZero();
    assertThat(count("ledger_journals")).isZero();
    assertThat(count("ledger_entries")).isZero();
  }

  @Test
  void missingOriginalRemainsARetryableStorageFailure() throws Exception {
    String id = submit("PAYMENT,remote,SUCCEEDED,100,1,CNY,x\n", 1, 100, 1);
    java.nio.file.Files.delete(reconciliation.original(id, "merchant-a"));
    assertThatThrownBy(() -> process(id)).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("FAILED");
    assertThat(count("recon_issues")).isZero();
  }

  @Test
  void wellEncodedChineseIsClassifiedByTheBillFieldRules() throws Exception {
    String id = submit("PAYMENT,中文,SUCCEEDED,100,1,CNY,x\n", 1, 100, 1);
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUARANTINED");
    assertThat(
            jdbc.queryForObject(
                "SELECT error_detail FROM recon_batches WHERE id=?", String.class, id))
        .contains("行格式错误")
        .doesNotContain("UTF-8");
  }

  @Test
  void sameBillVersionIsIdempotentAndDifferentContentNeedsANewVersion() throws Exception {
    String rows = "PAYMENT,remote,SUCCEEDED,100,1,CNY,x\n";
    String id = submit(rows, 1, 100, 1);
    assertThat(submit(rows, 1, 100, 1)).isEqualTo(id);
    assertThatThrownBy(() -> submit(rows.replace(",100,", ",200,"), 1, 200, 1))
        .isInstanceOf(dev.merchantpay.platform.common.ApiException.class);
    assertThat(submit(rows, 1, 100, 2)).isNotEqualTo(id);
  }

  @Test
  void interruptedSecondChunkResumesWithoutDuplicatingTheFirstChunk() throws Exception {
    var rows = new StringBuilder();
    for (int i = 1; i <= 1001; i++) {
      rows.append("PAYMENT,remote-")
          .append(i)
          .append(",SUCCEEDED,100,1,CNY,ref-")
          .append(i)
          .append('\n');
    }
    String id = submit(rows.toString(), 1001, 100100, 1);
    jdbc.execute(
        "ALTER TABLE recon_rows ADD CONSTRAINT simulate_storage_failure CHECK (line_no<=500)");
    assertThatThrownBy(() -> process(id)).isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT processed_lines FROM recon_batches WHERE id=?", Long.class, id))
        .isEqualTo(500);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("FAILED");
    jdbc.execute("ALTER TABLE recon_rows DROP CONSTRAINT simulate_storage_failure");
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM recon_rows WHERE batch_id=?", Long.class, id))
        .isEqualTo(1001);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_issues WHERE batch_id=?", Long.class, id))
        .isEqualTo(1001);
  }
}
