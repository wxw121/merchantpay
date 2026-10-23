package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.merchantpay.platform.common.ChannelResult;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.refund.RefundService;
import dev.merchantpay.platform.web.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ConsoleDetailTest extends FinancialFixture {
  MockMvc mvc;
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void consoleSchema() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V2__security_and_evidence.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V11__payment_evidence_paging.sql"));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var controller =
        new ConsoleController(
            new Access(),
            new Queries(jdbc, Clock.systemUTC(), "test"),
            payments,
            refunds,
            null,
            null,
            jdbc,
            tx,
            Clock.systemUTC());
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new ApiErrors(
                    new dev.merchantpay.platform.web.ApiAvailability(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry())))
            .build();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> detail(String id, String resource, String cursor) throws Exception {
    var request = get("/api/console/payments/{id}", id);
    if (cursor != null) request.param(resource + "Cursor", cursor);
    return ScopedValue.where(
            Access.CURRENT, new Access.Caller("review-test", "merchant-a", Set.of("MERCHANT")))
        .call(
            () ->
                json.readValue(
                    mvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                    Map.class));
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rows(Map<String, Object> page, String resource) {
    return (List<Map<String, Object>>) page.get(resource);
  }

  @SuppressWarnings("unchecked")
  String next(Map<String, Object> page, String resource) {
    assertThat(page).containsKey("nextCursors");
    return (String) ((Map<String, Object>) page.get("nextCursors")).get(resource);
  }

  @Test
  void everyPageContainsWholeJournalsAndAllEvidenceCanBeReachedWithoutDuplicates()
      throws Exception {
    var payment = paid("many-detail-refunds", "10000");
    for (int index = 0; index < 300; index++) {
      var refund =
          refunds.create(
              "merchant-a", payment.id(), new RefundService.CreateRefund("detail-" + index, "1"));
      refunds.applyResult(
          refund.id(), new ChannelResult.Success("MOCK_A", "detail-ref-" + index, 1, "CNY"));
    }
    var sameTime = java.sql.Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
    jdbc.update(
        "UPDATE ledger_journals SET created_at=? WHERE payment_id=?", sameTime, payment.id());
    jdbc.update("UPDATE refunds SET created_at=? WHERE payment_id=?", sameTime, payment.id());
    for (int index = 0; index < 60; index++) {
      jdbc.update(
          "INSERT INTO channel_attempts(id,aggregate_id,payment_id,channel,operation,request_key,status,created_at,updated_at) VALUES (?,?,?,'MOCK_A','CHANNEL_PAYMENT',?,'PREPARED',?,?)",
          UUID.randomUUID().toString(),
          payment.id(),
          payment.id(),
          "attempt-" + index,
          sameTime,
          sameTime);
      jdbc.update(
          "INSERT INTO evidence_conflicts(id,merchant_id,aggregate_id,channel,detail,created_at) VALUES (?,'merchant-a',?,'MOCK_A','review',?)",
          UUID.randomUUID().toString(),
          payment.id(),
          sameTime);
    }
    var first = detail(payment.id(), "journals", null);
    assertWholeJournals(first);
    var expected =
        Map.of("refunds", 300, "journals", 301, "attempts", 60, "tasks", 602, "conflicts", 60);
    var allEntries = new HashSet<String>();
    for (String resource : expected.keySet()) {
      var ids = new HashSet<String>();
      Map<String, Object> page = first;
      int pages = 0;
      while (true) {
        assertThat(++pages).isLessThan(30);
        assertThat(rows(page, resource)).hasSizeLessThanOrEqualTo(50);
        for (var row : rows(page, resource)) assertThat(ids.add((String) row.get("id"))).isTrue();
        if (resource.equals("journals")) {
          assertWholeJournals(page);
          for (var entry : rows(page, "entries"))
            assertThat(allEntries.add((String) entry.get("id"))).isTrue();
        }
        String cursor = next(page, resource);
        if (cursor == null) break;
        page = detail(payment.id(), resource, cursor);
      }
      assertThat(ids).hasSize(expected.get(resource));
    }
    assertThat(allEntries).hasSize(603);
  }

  void assertWholeJournals(Map<String, Object> page) {
    var journals = rows(page, "journals");
    var entries = rows(page, "entries");
    var journalIds = journals.stream().map(row -> (String) row.get("id")).toList();
    assertThat(entries)
        .allSatisfy(row -> assertThat(journalIds).contains((String) row.get("journalId")));
    for (var journal : journals) {
      var lines =
          entries.stream().filter(row -> row.get("journalId").equals(journal.get("id"))).toList();
      assertThat(lines)
          .hasSize(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ledger_entries WHERE journal_id=?",
                  Integer.class,
                  journal.get("id")));
      long balance =
          lines.stream()
              .mapToLong(
                  row ->
                      Long.parseLong((String) row.get("amountMinor"))
                          * (row.get("side").equals("DEBIT") ? 1 : -1))
              .sum();
      assertThat(balance).isZero();
    }
  }

  @Test
  void aCursorCannotBeReusedForAnotherPaymentOrCollectionOrAltered() throws Exception {
    var payment = paid("cursor-owner", "10000");
    for (int index = 0; index < 51; index++) {
      var refund =
          refunds.create(
              "merchant-a", payment.id(), new RefundService.CreateRefund("cursor-" + index, "1"));
      refunds.applyResult(
          refund.id(), new ChannelResult.Success("MOCK_A", "cursor-ref-" + index, 1, "CNY"));
    }
    String cursor = next(detail(payment.id(), "journals", null), "journals");
    assertThat(cursor).isNotBlank();
    var other = paid("other-payment", "100");
    ScopedValue.where(
            Access.CURRENT, new Access.Caller("review-test", "merchant-a", Set.of("MERCHANT")))
        .run(
            () -> {
              try {
                mvc.perform(
                        get("/api/console/payments/{id}", other.id())
                            .param("journalsCursor", cursor))
                    .andExpect(status().isBadRequest());
                mvc.perform(
                        get("/api/console/payments/{id}", payment.id())
                            .param("refundsCursor", cursor))
                    .andExpect(status().isBadRequest());
                mvc.perform(
                        get("/api/console/payments/{id}", payment.id())
                            .param("journalsCursor", "tampered"))
                    .andExpect(status().isBadRequest());
              } catch (Exception failure) {
                throw new RuntimeException(failure);
              }
            });
    var foreign =
        payments.create(
            "merchant-b",
            new PaymentService.CreatePayment("foreign", "100", "CNY", "MOCK_A", null));
    ScopedValue.where(
            Access.CURRENT, new Access.Caller("review-test", "merchant-a", Set.of("MERCHANT")))
        .run(
            () -> {
              try {
                mvc.perform(get("/api/console/payments/{id}", foreign.id()))
                    .andExpect(status().isNotFound());
              } catch (Exception failure) {
                throw new RuntimeException(failure);
              }
            });
  }
}
