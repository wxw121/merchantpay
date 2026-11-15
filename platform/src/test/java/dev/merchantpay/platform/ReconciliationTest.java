package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.reconciliation.ReconciliationService;
import dev.merchantpay.platform.sharding.ShardCoreOnlyGuard;
import dev.merchantpay.platform.sharding.ShardExportReadGate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V30__recon_segments.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V31__recon_final_seal.sql"));
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
        java.sql.Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
        id);
    var task = executions.computeIfAbsent(id, ignored -> queue.claimBatch(1, true).getFirst());
    reconciliation.process(id, task);
  }

  private String segmentBody(String rows, int count, long amount) {
    var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    return "#merchantpay-bill-v1,MOCK_A,merchant-a,"
        + day
        + ","
        + count
        + ","
        + amount
        + "\noperation,aggregateId,status,amountMinor,feeMinor,currency,reference\n"
        + rows;
  }

  private String uploadSegment(
      int version,
      int index,
      int segments,
      long rows,
      long amount,
      long totalRows,
      long totalAmount,
      String manifest,
      String body)
      throws Exception {
    return reconciliation.submitSegment(
        "merchant-a",
        "MOCK_A",
        LocalDate.now(ZoneId.of("Asia/Shanghai")),
        version,
        index,
        segments,
        rows,
        amount,
        totalRows,
        totalAmount,
        manifest,
        Signatures.sha256(body),
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
        ignored -> {});
  }

  @Test
  void segmentedBillQueuesOnlyAfterItsCompleteManifestAndParsesBothSegments() throws Exception {
    String first = segmentBody("PAYMENT,remote-a,SUCCEEDED,100,1,CNY,a\n", 1, 100);
    String second = segmentBody("PAYMENT,remote-b,SUCCEEDED,200,2,CNY,b\n", 1, 200);
    String manifest =
        Signatures.sha256(
            "0:"
                + Signatures.sha256(first)
                + ":1:100\n"
                + "1:"
                + Signatures.sha256(second)
                + ":1:200\n");
    String id = uploadSegment(1, 0, 2, 1, 100, 2, 300, manifest, first);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("ASSEMBLING");
    assertThat(count("outbox_tasks")).isZero();
    assertThat(uploadSegment(1, 0, 2, 1, 100, 2, 300, manifest, first)).isEqualTo(id);
    assertThat(uploadSegment(1, 1, 2, 1, 200, 2, 300, manifest, second)).isEqualTo(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUEUED");
    assertThat(count("outbox_tasks")).isEqualTo(1L);
    try (var evidence = reconciliation.downloadSegment(id, "merchant-a", 1).getInputStream()) {
      assertThat(new String(evidence.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(second);
    }
    assertThatThrownBy(() -> reconciliation.downloadOriginal(id, "merchant-a"))
        .hasMessageContaining("按段下载");
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_issues WHERE batch_id=?", Long.class, id))
        .isEqualTo(2L);
  }

  @Test
  void segmentedBillRejectsAMismatchedManifestWithoutQueuing() throws Exception {
    String first = segmentBody("PAYMENT,remote-a,SUCCEEDED,100,1,CNY,a\n", 1, 100);
    String second = segmentBody("PAYMENT,remote-b,SUCCEEDED,200,2,CNY,b\n", 1, 200);
    String wrong = Signatures.sha256("wrong manifest");
    String id = uploadSegment(1, 0, 2, 1, 100, 2, 300, wrong, first);
    assertThatThrownBy(() -> uploadSegment(1, 1, 2, 1, 200, 2, 300, wrong, second))
        .hasMessageContaining("清单笔数、金额或摘要不一致");
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("ASSEMBLING");
    assertThat(count("recon_segments")).isEqualTo(1L);
    assertThat(count("outbox_tasks")).isZero();
  }

  @Test
  void segmentedBillRejectsAChangedAcceptedSegment() throws Exception {
    String first = segmentBody("PAYMENT,remote-a,SUCCEEDED,100,1,CNY,a\n", 1, 100);
    String changed = segmentBody("PAYMENT,remote-b,SUCCEEDED,100,1,CNY,b\n", 1, 100);
    String manifest =
        Signatures.sha256(
            "0:"
                + Signatures.sha256(first)
                + ":1:100\n"
                + "1:"
                + Signatures.sha256(first)
                + ":1:100\n");
    String id = uploadSegment(1, 0, 2, 1, 100, 2, 200, manifest, first);
    assertThatThrownBy(() -> uploadSegment(1, 0, 2, 1, 100, 2, 200, manifest, changed))
        .hasMessageContaining("该段已有不同内容");
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("ASSEMBLING");
    assertThat(count("recon_segments")).isEqualTo(1L);
  }

  @Test
  void segmentedBillQuarantinesAFalseSegmentTotal() throws Exception {
    String body = segmentBody("PAYMENT,remote-a,SUCCEEDED,100,1,CNY,a\n", 1, 999);
    String manifest = Signatures.sha256("0:" + Signatures.sha256(body) + ":1:999\n");
    String id = uploadSegment(1, 0, 1, 1, 999, 1, 999, manifest, body);
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUARANTINED");
    assertThat(count("recon_issues")).isZero();
  }

  @Test
  void segmentedBillResumesAfterACompletedFirstSegment() throws Exception {
    String first = segmentBody("PAYMENT,remote-a,SUCCEEDED,100,1,CNY,a\n", 1, 100);
    String second = segmentBody("PAYMENT,remote-b,SUCCEEDED,200,2,CNY,b\n", 1, 200);
    String manifest =
        Signatures.sha256(
            "0:"
                + Signatures.sha256(first)
                + ":1:100\n"
                + "1:"
                + Signatures.sha256(second)
                + ":1:200\n");
    String id = uploadSegment(1, 0, 2, 1, 100, 2, 300, manifest, first);
    uploadSegment(1, 1, 2, 1, 200, 2, 300, manifest, second);
    jdbc.update(
        "INSERT INTO recon_rows VALUES(?,1,'PAYMENT','remote-a','SUCCEEDED',100,1,'CNY','a',?)",
        id,
        "PAYMENT,remote-a,SUCCEEDED,100,1,CNY,a");
    jdbc.update(
        "UPDATE recon_batches SET processed_lines=1,actual_count=1,actual_amount_minor=100 WHERE id=?",
        id);
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM recon_rows WHERE batch_id=?", Long.class, id))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "SELECT actual_amount_minor FROM recon_batches WHERE id=?", Long.class, id))
        .isEqualTo(300L);
  }

  private record ShardedFixture(JdbcTemplate target, ShardExportReadGate gate) {}

  private ShardedFixture shardedReconciliation() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V24__shard_catalog.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V25__shard_ownership.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V28__recon_source_routes.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V29__recon_source_watermarks.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V18__reliable_domain_events.sql"));
    jdbc.execute(
        "CREATE TABLE v3_shard_cutovers(bucket INT PRIMARY KEY,source_epoch BIGINT,target_epoch BIGINT,target_shard INT,state VARCHAR(16))");
    jdbc.update("UPDATE v3_shard_routes SET physical_shard=1,epoch=2 WHERE bucket=4");
    jdbc.update("UPDATE v3_bucket_fences SET access_state='FENCED' WHERE bucket=4");
    jdbc.update("INSERT INTO v3_shard_cutovers VALUES(4,1,2,1,'ACTIVE')");
    var sources = new java.util.HashMap<Integer, javax.sql.DataSource>();
    sources.put(0, jdbc.getDataSource());
    JdbcTemplate target = null;
    for (int physical = 1; physical < 4; physical++) {
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:recon_source_"
                  + java.util.UUID.randomUUID()
                  + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      sources.put(physical, source);
      var local = new JdbcTemplate(source);
      local.execute("CREATE TABLE v3_shard_identity(id INT PRIMARY KEY,physical_shard INT)");
      local.update("INSERT INTO v3_shard_identity VALUES(1,?)", physical);
      local.execute(
          "CREATE TABLE v3_bucket_fences(bucket INT PRIMARY KEY,route_epoch BIGINT,access_state VARCHAR(16))");
      local.execute(
          "CREATE TABLE payments(id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),channel VARCHAR(32),created_at TIMESTAMP,status VARCHAR(24),amount_minor BIGINT,fee_minor BIGINT,currency VARCHAR(3),channel_reference VARCHAR(128))");
      local.execute(
          "CREATE TABLE refunds(id VARCHAR(64) PRIMARY KEY,payment_id VARCHAR(64),merchant_id VARCHAR(64),channel VARCHAR(32),created_at TIMESTAMP,status VARCHAR(24),amount_minor BIGINT,refunded_fee_minor BIGINT,channel_reference VARCHAR(128))");
      local.execute(
          "CREATE TABLE domain_events(sequence_no BIGINT PRIMARY KEY,delivery_mode VARCHAR(16),event_type VARCHAR(64),event_id VARCHAR(160))");
      local.execute("CREATE TABLE event_receipts(consumer_name VARCHAR(64),event_id VARCHAR(160))");
      local.execute("CREATE TABLE event_quarantine(status VARCHAR(16))");
      local.execute("CREATE TABLE outbox_tasks(status VARCHAR(24))");
      local.execute("CREATE TABLE inbox_notifications(status VARCHAR(24))");
      if (physical == 1) {
        local.update("INSERT INTO v3_bucket_fences VALUES(4,2,'ACTIVE')");
        target = local;
      }
    }
    var gate = new ShardExportReadGate(sources);
    var outbox = new OutboxService(jdbc, Clock.systemUTC());
    outbox.setReconcileGate(
        new dev.merchantpay.platform.sharding.ShardReconcileGate(
            new ShardCoreOnlyGuard(jdbc), gate));
    reconciliation =
        new ReconciliationService(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            outbox,
            Clock.systemUTC(),
            directory.toString(),
            queue);
    reconciliation.setShardBulkGuard(new ShardCoreOnlyGuard(jdbc));
    reconciliation.setShardSourceGate(gate);
    return new ShardedFixture(target, gate);
  }

  @Test
  void shardedReconciliationStagesTheAuthoritativeTargetPayment() throws Exception {
    var target = shardedReconciliation().target();
    String paymentId = "p4_00000000-0000-0000-0000-000000000000";
    target.update(
        "INSERT INTO payments VALUES(?,?,?,?,'SUCCEEDED',100,1,'CNY','target-ref')",
        paymentId,
        "merchant-a",
        "MOCK_A",
        java.sql.Timestamp.from(Instant.now()));
    String id = submit("PAYMENT," + paymentId + ",SUCCEEDED,100,1,CNY,target-ref\n", 1, 100, 1);
    process(id);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("PRELIMINARY");
    assertThat(
            jdbc.queryForObject(
                "SELECT source_routes_hash FROM recon_batches WHERE id=?", String.class, id))
        .hasSize(64);
    assertThat(
            jdbc.queryForObject(
                "SELECT source_event_watermarks FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("0,0,0,0");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_local_rows WHERE batch_id=?", Long.class, id))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_issues WHERE batch_id=?", Long.class, id))
        .isZero();
  }

  @Test
  void shardedPreliminaryResultSealsOnlyAfterWriterStopAndDrain() throws Exception {
    var target = shardedReconciliation().target();
    String paymentId = "p4_00000000-0000-0000-0000-000000000000";
    target.update(
        "INSERT INTO payments VALUES(?,?,?,?,'SUCCEEDED',100,1,'CNY','target-ref')",
        paymentId,
        "merchant-a",
        "MOCK_A",
        java.sql.Timestamp.from(Instant.now()));
    String id = submit("PAYMENT," + paymentId + ",SUCCEEDED,100,1,CNY,target-ref\n", 1, 100, 1);
    process(id);
    String digest =
        jdbc.queryForObject("SELECT sha256 FROM recon_batches WHERE id=?", String.class, id);
    assertThatThrownBy(
            () -> reconciliation.seal(id, digest, false, "cutover", "operator", ignored -> {}))
        .hasMessageContaining("须确认所有写入已停止");
    assertThatThrownBy(
            () -> reconciliation.seal(id, digest, true, "cutover", "operator", ignored -> {}))
        .hasMessageContaining("未排空");
    assertThat(queue.complete(executions.get(id))).isTrue();
    assertThatThrownBy(
            () ->
                reconciliation.seal(
                    id, Signatures.sha256("wrong"), true, "cutover", "operator", ignored -> {}))
        .hasMessageContaining("渠道摘要");
    reconciliation.seal(id, digest, true, "cutover", "operator", ignored -> {});
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
    assertThat(
            jdbc.queryForObject(
                "SELECT seal_source_hash FROM recon_batches WHERE id=?", String.class, id))
        .hasSize(64);
    assertThat(
            jdbc.queryForObject("SELECT sealed_by FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("operator");
  }

  @Test
  void shardedSealRejectsChangedMoneyRowWithUnchangedEventWatermark() throws Exception {
    var target = shardedReconciliation().target();
    String paymentId = "p4_00000000-0000-0000-0000-000000000000";
    target.update(
        "INSERT INTO payments VALUES(?,?,?,?,'SUCCEEDED',100,1,'CNY','target-ref')",
        paymentId,
        "merchant-a",
        "MOCK_A",
        java.sql.Timestamp.from(Instant.now()));
    String id = submit("PAYMENT," + paymentId + ",SUCCEEDED,100,1,CNY,target-ref\n", 1, 100, 1);
    process(id);
    assertThat(queue.complete(executions.get(id))).isTrue();
    target.update("UPDATE payments SET status='FAILED' WHERE id=?", paymentId);
    String digest =
        jdbc.queryForObject("SELECT sha256 FROM recon_batches WHERE id=?", String.class, id);
    assertThatThrownBy(
            () -> reconciliation.seal(id, digest, true, "cutover", "operator", ignored -> {}))
        .hasMessageContaining("权威资金行与预对账暂存不一致");
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("PRELIMINARY");
  }

  @Test
  void shardedSegmentedBillSealsAgainstItsCompleteManifest() throws Exception {
    shardedReconciliation();
    String first = segmentBody("", 0, 0);
    String second = segmentBody("", 0, 0);
    String manifest =
        Signatures.sha256(
            "0:"
                + Signatures.sha256(first)
                + ":0:0\n"
                + "1:"
                + Signatures.sha256(second)
                + ":0:0\n");
    String id = uploadSegment(1, 0, 2, 0, 0, 0, 0, manifest, first);
    uploadSegment(1, 1, 2, 0, 0, 0, 0, manifest, second);
    process(id);
    assertThat(queue.complete(executions.get(id))).isTrue();
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("PRELIMINARY");
    reconciliation.seal(id, manifest, true, "manifest verified", "operator", ignored -> {});
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("COMPLETE");
  }

  @Test
  void shardedReconciliationRejectsAnOldRouteSnapshot() throws Exception {
    shardedReconciliation();
    String id = submit("", 0, 0, 1);
    jdbc.update(
        "UPDATE recon_batches SET snapshot_at=?,source_routes_hash=? WHERE id=?",
        java.sql.Timestamp.from(Instant.now()),
        "old-route",
        id);
    assertThatThrownBy(() -> process(id)).hasMessageContaining("账单快照对应的桶路由已变化");
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("FAILED");
  }

  @Test
  void shardedReconciliationRejectsAChangedEventWatermark() throws Exception {
    var fixture = shardedReconciliation();
    String id = submit("", 0, 0, 1);
    String routeHash = Signatures.sha256(fixture.gate().capture().routes().toString());
    jdbc.update(
        "UPDATE recon_batches SET snapshot_at=?,source_routes_hash=?,source_event_watermarks=? WHERE id=?",
        java.sql.Timestamp.from(Instant.now()),
        routeHash,
        "0,0,0,0",
        id);
    fixture.target().update("INSERT INTO domain_events(sequence_no) VALUES(1)");
    assertThatThrownBy(() -> process(id)).hasMessageContaining("来源事件水位已变化");
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("FAILED");
  }

  @Test
  void localOnlyPaginationDoesNotSkipTheSecondOperationOfAnAggregate() throws Exception {
    String id = submit("", 0, 0, 1);
    for (int index = 0; index < 499; index++) {
      jdbc.update(
          "INSERT INTO recon_local_rows VALUES(?,'PAYMENT',?,'SUCCEEDED',100,1,'CNY','ref')",
          id,
          "a" + String.format("%03d", index));
    }
    jdbc.update(
        "INSERT INTO recon_local_rows VALUES(?,'PAYMENT','z','SUCCEEDED',100,1,'CNY','ref')", id);
    jdbc.update(
        "INSERT INTO recon_local_rows VALUES(?,'REFUND','z','SUCCEEDED',100,1,'CNY','ref')", id);
    jdbc.update(
        "UPDATE recon_batches SET snapshot_at=? WHERE id=?",
        java.sql.Timestamp.from(Instant.now()),
        id);
    process(id);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_issues WHERE batch_id=? AND issue_type='LOCAL_ONLY'",
                Long.class,
                id))
        .isEqualTo(501L);
  }

  @Test
  void aReclaimedReconciliationCannotBeRegressedByItsOldWorker() throws Exception {
    String id = submit("PAYMENT,remote,SUCCEEDED,100,1,CNY,x\n", 1, 100, 1);
    jdbc.update(
        "UPDATE outbox_tasks SET due_at=? WHERE aggregate_id=?",
        java.sql.Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
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
        java.sql.Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
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
              java.sql.Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
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
