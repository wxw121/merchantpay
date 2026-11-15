package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.merchantpay.platform.channel.ChannelBillReader;
import dev.merchantpay.platform.common.NotificationPolicy;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.reconciliation.*;
import dev.merchantpay.platform.web.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.accept.DefaultApiVersionStrategy;
import org.springframework.web.accept.SemanticApiVersionParser;
import org.springframework.web.client.RestClient;

class AuditTransactionTest extends FinancialFixture {
  enum Operation {
    CONSOLE_PAYMENT,
    CONSOLE_REFUND,
    CONSOLE_CLOSE,
    API_PAYMENT,
    API_REFUND,
    API_CLOSE,
    BILL_UPLOAD,
    BILL_PULL,
    EXPORT_CREATE
  }

  @TempDir Path directory;
  MockMvc mvc;
  MockRestServiceServer channel;
  final Clock clock = Clock.systemUTC();

  @BeforeEach
  void controllers() {
    for (String migration :
        List.of("V4__reconciliation.sql", "V5__exports.sql", "V8__export_ownership.sql")) {
      ScriptUtils.executeSqlScript(
          schemaConnection, new ClassPathResource("db/migration/" + migration));
    }
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var urls = new NotificationPolicy("");
    var merchants = new MerchantService(jdbc, null, urls, tx, clock);
    var access = new Access();
    var queries = new Queries(jdbc, clock, "audit-test");
    var outbox = new OutboxService(jdbc, clock);
    var queue = new TaskQueue(jdbc, tx, clock);
    var reconciliation =
        new ReconciliationService(jdbc, tx, outbox, clock, directory.toString(), queue);
    var exports = new ExportService(jdbc, tx, outbox, queries, clock, directory.toString(), queue);
    var builder = RestClient.builder().baseUrl("http://channel.test");
    channel = MockRestServiceServer.bindTo(builder).build();
    var reader = new ChannelBillReader(builder.build(), "audit-test");
    var versions =
        new DefaultApiVersionStrategy(
            List.of(request -> "1.0"),
            new SemanticApiVersionParser(),
            false,
            "1.0",
            false,
            null,
            null);
    versions.addSupportedVersion("1.0");
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleController(
                    access, queries, payments, refunds, merchants, urls, jdbc, tx, clock, outbox),
                new OpenApiController(access, payments, refunds, merchants, urls, tx),
                new ReconciliationController(
                    access, reconciliation, exports, queries, reader, merchants, outbox, jdbc, tx))
            .setApiVersionStrategy(versions)
            .setControllerAdvice(
                new ApiErrors(
                    new dev.merchantpay.platform.web.ApiAvailability(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry())))
            .build();
  }

  InputStream outsideTransaction(byte[] bytes) {
    return new ByteArrayInputStream(bytes) {
      @Override
      public synchronized int read(byte[] buffer, int offset, int length) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
            .as("bill transfer must remain outside the database transaction")
            .isFalse();
        return super.read(buffer, offset, length);
      }
    };
  }

  RequestBuilder request(Operation operation, String parent) {
    String prefix = operation.name().startsWith("API_") ? "/api/v1" : "/api/console";
    var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    String bill =
        "#merchantpay-bill-v1,MOCK_A,merchant-a,"
            + day
            + ",0,0\noperation,aggregateId,status,amountMinor,feeMinor,currency,reference\n";
    byte[] bytes = bill.getBytes(StandardCharsets.UTF_8);
    if (operation == Operation.BILL_UPLOAD) {
      var file =
          new MockMultipartFile("file", "audit.csv", "text/csv", bytes) {
            @Override
            public InputStream getInputStream() {
              return outsideTransaction(bytes);
            }
          };
      return multipart("/api/console/reconciliation/upload")
          .file(file)
          .param("merchantId", "merchant-a")
          .param("channel", "MOCK_A")
          .param("day", day.toString())
          .param("version", "1")
          .param("sha256", dev.merchantpay.platform.payment.PaymentService.sha256(bill));
    }
    if (operation == Operation.BILL_PULL) {
      return post("/api/console/reconciliation/pull")
          .contentType("application/json")
          .content(
              "{\"merchantId\":\"merchant-a\",\"channel\":\"MOCK_A\",\"day\":\""
                  + day
                  + "\",\"version\":1}");
    }
    if (operation == Operation.EXPORT_CREATE) {
      return post("/api/console/exports")
          .contentType("application/json")
          .content("{\"merchantId\":\"merchant-a\",\"resource\":\"payments\"}");
    }
    if (operation.name().endsWith("REFUND")) {
      return post(prefix + "/payments/" + parent + "/refunds")
          .param("merchantId", "merchant-a")
          .contentType("application/json")
          .content("{\"merchantRefundNo\":\"audit-refund\",\"amountMinor\":\"1000\"}");
    }
    if (operation.name().endsWith("CLOSE")) {
      return post(prefix + "/payments/" + parent + "/close").param("merchantId", "merchant-a");
    }
    return post(prefix + "/payments")
        .param("merchantId", "merchant-a")
        .contentType("application/json")
        .content(
            "{\"merchantOrderNo\":\"audit-payment\",\"amountMinor\":\"10000\",\"currency\":\"CNY\",\"channel\":\"MOCK_A\"}");
  }

  String operationName(Operation operation) {
    if (operation.name().endsWith("PAYMENT")) return "PAYMENT_CREATE";
    if (operation.name().endsWith("REFUND")) return "REFUND_CREATE";
    if (operation.name().endsWith("CLOSE")) return "PAYMENT_CLOSE";
    return operation.name();
  }

  @ParameterizedTest
  @EnumSource(Operation.class)
  void auditFailureRollsBackTheOperationAndTheSameRequestCanBeRetried(Operation operation)
      throws Exception {
    String parent =
        operation.name().endsWith("REFUND")
            ? paid("audit-parent", "10000").id()
            : operation.name().endsWith("CLOSE")
                ? createPayment("audit-parent", "10000").id()
                : null;
    Map<String, Long> before = new HashMap<>();
    for (String table :
        List.of(
            "payments",
            "refunds",
            "outbox_tasks",
            "recon_batches",
            "exports",
            "ledger_journals",
            "ledger_entries")) {
      before.put(table, count(table));
    }
    if (operation == Operation.BILL_PULL) {
      byte[] bill = "isolated-channel-file".getBytes(StandardCharsets.UTF_8);
      channel
          .expect(ExpectedCount.times(2), anything())
          .andRespond(
              request -> new MockClientHttpResponse(outsideTransaction(bill), HttpStatus.OK));
    }
    jdbc.execute(
        "ALTER TABLE audit_records ADD CONSTRAINT audit_unavailable CHECK (operation='UNAVAILABLE')");
    MDC.put("requestId", "audit-regression");
    try {
      ScopedValue.where(
              Access.CURRENT,
              new Access.Caller("audit-actor", "merchant-a", Set.of("ADMIN", "MERCHANT")))
          .call(
              () -> {
                assertThatThrownBy(() -> mvc.perform(request(operation, parent)))
                    .hasCauseInstanceOf(DataAccessException.class)
                    .satisfies(
                        failure ->
                            assertThat(failure.getCause().getMessage())
                                .containsIgnoringCase("audit_unavailable"));
                for (var entry : before.entrySet())
                  assertThat(count(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                assertThat(count("audit_records")).isZero();
                if (parent != null) {
                  var payment = payments.get("merchant-a", parent);
                  assertThat(payment.reservedMinor()).isEqualTo("0");
                  assertThat(payment.status())
                      .isEqualTo(operation.name().endsWith("REFUND") ? "SUCCEEDED" : "PROCESSING");
                }
                jdbc.execute("ALTER TABLE audit_records DROP CONSTRAINT audit_unavailable");
                mvc.perform(request(operation, parent)).andExpect(status().isAccepted());
                var audit = jdbc.queryForMap("SELECT * FROM audit_records");
                assertThat(audit.get("actor")).isEqualTo("audit-actor");
                assertThat(audit.get("merchant_id")).isEqualTo("merchant-a");
                assertThat(audit.get("operation")).isEqualTo(operationName(operation));
                assertThat(audit.get("detail").toString()).contains("requestId=audit-regression");
                assertThat(audit.get("resource_id").toString()).isNotBlank();
                String kind =
                    operation.name().endsWith("PAYMENT")
                        ? "CHANNEL_PAYMENT"
                        : operation.name().endsWith("REFUND")
                            ? "CHANNEL_REFUND"
                            : operation.name().endsWith("CLOSE")
                                ? "CHANNEL_CLOSE"
                                : operation == Operation.EXPORT_CREATE ? "EXPORT" : "RECONCILE";
                assertThat(
                        jdbc.queryForObject(
                            "SELECT COUNT(*) FROM outbox_tasks WHERE kind=? AND aggregate_id=?",
                            Long.class,
                            kind,
                            audit.get("resource_id")))
                    .isEqualTo(1);
                return null;
              });
      if (operation == Operation.BILL_PULL) channel.verify();
    } finally {
      MDC.remove("requestId");
    }
  }
}
