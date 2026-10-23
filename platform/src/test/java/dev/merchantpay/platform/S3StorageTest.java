package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.merchantpay.platform.common.NotificationPolicy;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.reconciliation.*;
import dev.merchantpay.platform.web.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Real HTTP against an explicitly supplied isolated S3-compatible server, never production. */
@EnabledIfSystemProperty(
    named = "merchantpay.test.s3-endpoint",
    matches = "http://(127\\.0\\.0\\.1|localhost):[0-9]+")
class S3StorageTest extends FinancialFixture {
  @TempDir Path directory;
  S3Client client;
  ObjectStorage storage;
  String bucket;
  ExportService exports;
  ReconciliationService reconciliation;
  TaskQueue queue;
  TransactionTemplate tx;
  final Clock clock = Clock.systemUTC();

  @BeforeEach
  void storageSchema() throws Exception {
    for (String migration :
        List.of(
            "V4__reconciliation.sql",
            "V5__exports.sql",
            "V8__export_ownership.sql",
            "V9__reconciliation_execution.sql",
            "V10__export_execution.sql")) {
      ScriptUtils.executeSqlScript(
          schemaConnection, new ClassPathResource("db/migration/" + migration));
    }
    client =
        S3Client.builder()
            .endpointOverride(URI.create(System.getProperty("merchantpay.test.s3-endpoint")))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        System.getenv().getOrDefault("S3_TEST_ACCESS_KEY", "integration-test"),
                        System.getenv()
                            .getOrDefault("S3_TEST_SECRET_KEY", "integration-test-secret"))))
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(2))
                    .socketTimeout(Duration.ofSeconds(10)))
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(20)))
            .build();
    bucket = "merchantpay-test-" + UUID.randomUUID();
    client.createBucket(request -> request.bucket(bucket));
    storage = new ObjectStorage(directory, client, bucket);
    tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    queue = new TaskQueue(jdbc, tx, clock);
    exports = exportService(storage);
    reconciliation =
        new ReconciliationService(jdbc, tx, new OutboxService(jdbc, clock), clock, storage, queue);
  }

  @AfterEach
  void closeStorage() {
    if (client == null) return;
    // This test alone created the randomized bucket; delete only its isolated fixture objects.
    if (bucket != null) {
      for (var item : client.listObjectsV2(request -> request.bucket(bucket)).contents())
        client.deleteObject(request -> request.bucket(bucket).key(item.key()));
      client.deleteBucket(request -> request.bucket(bucket));
    }
    client.close();
  }

  ExportService exportService(ObjectStorage selected) {
    return new ExportService(
        jdbc,
        tx,
        new OutboxService(jdbc, clock),
        new Queries(jdbc, clock, "test"),
        clock,
        selected,
        queue);
  }

  String bill(String claimedDigest) throws Exception {
    String body =
        "#merchantpay-bill-v1,MOCK_A,merchant-a,2026-10-19,1,100\noperation,aggregateId,status,amountMinor,feeMinor,currency,reference\nPAYMENT,remote,SUCCEEDED,100,1,CNY,remote\n";
    return reconciliation.submit(
        "merchant-a",
        "MOCK_A",
        LocalDate.of(2026, 10, 19),
        1,
        claimedDigest == null ? PaymentService.sha256(body) : claimedDigest,
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  String pointer(String table, String id) {
    return jdbc.queryForObject(
        "SELECT storage_key FROM " + table + " WHERE id=?", String.class, id);
  }

  TaskQueue.Task claim(String id) {
    jdbc.update(
        "UPDATE outbox_tasks SET due_at=? WHERE aggregate_id=?", Timestamp.from(Instant.EPOCH), id);
    return queue.claimBatch(1, true).getFirst();
  }

  @Test
  void billEvidenceAndExportAreStoredAndStreamedThroughRealS3() throws Exception {
    String bill = bill(null);
    assertThat(pointer("recon_batches", bill)).startsWith("s3:bills/");
    assertThat(Files.walk(directory).filter(Files::isRegularFile).count()).isZero();
    reconciliation.process(bill, claim(bill));
    assertThat(
            jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, bill))
        .isEqualTo("COMPLETE");
    try (var input = reconciliation.downloadOriginal(bill, "merchant-a").getInputStream()) {
      assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .contains("PAYMENT,remote,SUCCEEDED,100");
    }
    paid("s3-tenant", "100");
    String id = exports.create("merchant-a", "owner", "payments", null, null);
    exports.process(id, claim(id));
    assertThat(pointer("exports", id)).startsWith("s3:exports/");
    try (var zip =
        new ZipInputStream(exports.download(id, "merchant-a", "owner").getInputStream())) {
      assertThat(zip.getNextEntry().getName()).isEqualTo("payments-001.csv");
      assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).contains("s3-tenant");
    }
  }

  @Test
  void digestMismatchAndChangedEvidenceAreQuarantined() throws Exception {
    String id = bill("0".repeat(64));
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("QUARANTINED");
    assertThat(count("outbox_tasks")).isZero();
    tx.executeWithoutResult(
        ignored -> {
          jdbc.update("UPDATE recon_batches SET status='QUEUED' WHERE id=?", id);
          new OutboxService(jdbc, clock).enqueue("corrupt-test", "RECONCILE", id, "merchant-a");
        });
    client.putObject(
        request -> request.bucket(bucket).key(pointer("recon_batches", id).substring(3)),
        RequestBody.fromString("corrupted evidence"));
    reconciliation.process(id, claim(id));
    assertThat(
            jdbc.queryForObject(
                "SELECT error_detail FROM recon_batches WHERE id=?", String.class, id))
        .contains("摘要改变");
    assertThat(count("recon_issues")).isZero();
  }

  @Test
  void missingS3ObjectDoesNotFallbackToLocalOrPublishResults() throws Exception {
    String id = bill(null);
    String key = pointer("recon_batches", id).substring(3);
    client.deleteObject(request -> request.bucket(bucket).key(key));
    Files.createDirectories(directory.resolve("bills"));
    Files.writeString(directory.resolve(key), "local file must never hide remote loss");
    assertThatThrownBy(() -> reconciliation.downloadOriginal(id, "merchant-a"))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> reconciliation.process(id, claim(id)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM recon_batches WHERE id=?", String.class, id))
        .isEqualTo("FAILED");
    assertThat(count("recon_issues")).isZero();
  }

  @Test
  void publicationFailureLeavesNoBillOrCompleteExportPointer() throws Exception {
    var absent = new ObjectStorage(directory, client, bucket + "-absent");
    reconciliation =
        new ReconciliationService(jdbc, tx, new OutboxService(jdbc, clock), clock, absent, queue);
    assertThatThrownBy(() -> bill(null)).isInstanceOf(IOException.class);
    assertThat(count("recon_batches")).isZero();
    assertThat(count("outbox_tasks")).isZero();
    exports = exportService(absent);
    String id = exports.create("merchant-a", "owner", "payments", null, null);
    assertThatThrownBy(() -> exports.process(id, claim(id)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForMap("SELECT status,storage_key FROM exports WHERE id=?", id))
        .containsEntry("status", "FAILED")
        .containsEntry("storage_key", null);
  }

  @Test
  void completedAttemptObjectCannotPublishAfterLosingItsFence() throws Exception {
    String id = exports.create("merchant-a", "owner", "payments", null, null);
    var old = claim(id);
    ObjectStorage losing =
        new ObjectStorage(directory, client, bucket) {
          @Override
          public String publish(Path source, String key) throws IOException {
            String pointer = super.publish(source, key);
            jdbc.update(
                "UPDATE outbox_tasks SET lease_until=? WHERE id=?",
                Timestamp.from(Instant.EPOCH),
                old.id());
            return pointer;
          }
        };
    exportService(losing).process(id, old);
    assertThat(pointer("exports", id)).isNull();
    var replacement = claim(id);
    exports.process(id, replacement);
    String winner = pointer("exports", id);
    assertThat(winner).contains(replacement.token()).doesNotContain(old.token());
    assertThat(client.listObjectsV2(request -> request.bucket(bucket)).keyCount()).isEqualTo(2);
    exportService(losing).process(id, old);
    assertThat(pointer("exports", id)).isEqualTo(winner);
  }

  @Test
  void s3DownloadsKeepRequesterTenantExpiryAndAuditChecks() throws Exception {
    String id = exports.create("merchant-a", "owner", "payments", null, null);
    exports.process(id, claim(id));
    assertThatThrownBy(() -> exports.download(id, "merchant-b", "owner"))
        .isInstanceOf(dev.merchantpay.platform.common.ApiException.class);
    assertThatThrownBy(() -> exports.download(id, "merchant-a", "other"))
        .isInstanceOf(dev.merchantpay.platform.common.ApiException.class);
    var merchants = new MerchantService(jdbc, null, new NotificationPolicy(""), tx, clock);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new ReconciliationController(
                    new Access(),
                    reconciliation,
                    exports,
                    new Queries(jdbc, clock, "test"),
                    null,
                    merchants,
                    new OutboxService(jdbc, clock),
                    jdbc,
                    tx))
            .build();
    ScopedValue.where(Access.CURRENT, new Access.Caller("owner", null, Set.of("ADMIN")))
        .run(
            () -> {
              try {
                mvc.perform(get("/api/console/exports/{id}/download", id))
                    .andExpect(status().isOk());
              } catch (Exception failure) {
                throw new RuntimeException(failure);
              }
            });
    assertThat(jdbc.queryForMap("SELECT merchant_id,operation FROM audit_records"))
        .containsEntry("merchant_id", "merchant-a")
        .containsEntry("operation", "EXPORT_DOWNLOAD");
    jdbc.update("UPDATE exports SET expires_at=? WHERE id=?", Timestamp.from(Instant.EPOCH), id);
    assertThatThrownBy(() -> exports.download(id, "merchant-a", "owner"))
        .hasMessageContaining("过期");
    assertThatThrownBy(() -> reconciliation.downloadOriginal(bill(null), "merchant-b"))
        .isInstanceOf(dev.merchantpay.platform.common.ApiException.class);
  }

  @Test
  void streamsLargerThanTheBufferRoundTripWithoutMaterializingAnArchive() throws Exception {
    Path source = storage.temporary("bounded-");
    byte[] block = new byte[65536];
    new Random(7).nextBytes(block);
    try (var output = Files.newOutputStream(source)) {
      for (int index = 0; index < 256; index++) output.write(block);
    }
    String key = "exports/" + UUID.randomUUID() + "-" + UUID.randomUUID() + ".zip";
    String pointer = storage.publish(source, key);
    long total = 0;
    try (var input = storage.open(pointer)) {
      assertThat(input).isInstanceOf(software.amazon.awssdk.core.ResponseInputStream.class);
      byte[] buffer = new byte[65536];
      int read;
      while ((read = input.read(buffer)) != -1) total += read;
    }
    assertThat(total).isEqualTo(16L * 1024 * 1024);
    assertThatThrownBy(() -> storage.open("s3:../escape"))
        .isInstanceOf(IllegalStateException.class);
  }
}
