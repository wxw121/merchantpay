package dev.merchantpay.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.payment.PaymentService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:finance_api;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "merchantpay.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "merchantpay.channel-token=test",
      "merchantpay.callback-secret=test",
      "merchantpay.workers-enabled=false",
      "merchantpay.metrics-password=test-metrics-password",
      "spring.security.oauth2.client.registration.keycloak.client-secret=test"
    })
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(SecurityIntegrationTest.TestOidc.class)
class FinanceApiTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PaymentService payments;
  @Autowired EventDelivery delivery;
  @Autowired JsonMapper json;
  String merchant, root, issue;

  OidcLoginRequestPostProcessor login(String user, String role, String scope) {
    return oidcLogin()
        .idToken(
            t -> {
              t.subject(user).claim("roles", List.of(role));
              if (scope != null) t.claim("merchant_id", scope);
            });
  }

  @BeforeEach
  void seed() {
    merchant = "api-" + UUID.randomUUID();
    issue = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO merchants(id,name,enabled) VALUES (?, 'API finance',true)", merchant);
    var payment =
        payments.create(
            merchant, new PaymentService.CreatePayment("order", "1000", "CNY", "MOCK_A", null));
    root = payment.id();
    payments.applyResult(
        root, new ChannelResult.Success("MOCK_A", UUID.randomUUID().toString(), 1000, "CNY"));
    jdbc.update(
        "INSERT INTO recon_batches(id,merchant_id,channel,business_day,version,sha256,storage_key,status,created_at,updated_at) VALUES (?,?,'MOCK_A',CURRENT_DATE,1,'hash','key','COMPLETE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        issue,
        merchant);
    jdbc.update(
        "INSERT INTO recon_issues(id,batch_id,merchant_id,line_no,operation,aggregate_id,issue_type,created_at) VALUES (?,?,?,1,'PAYMENT',?,'AMOUNT_MISMATCH',CURRENT_TIMESTAMP)",
        issue,
        issue,
        merchant,
        root);
  }

  String write(String path, Object body, String user) throws Exception {
    String content =
        mvc.perform(
                post("/api/console/finance" + path)
                    .with(login(user, "FINANCE", null))
                    .with(csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(content).get("id").asString();
  }

  @Test
  void requiresCsrfFinanceRoleTenantScopeAndCurrentVersion() throws Exception {
    String body = json.writeValueAsString(Map.of("issueId", issue));
    mvc.perform(
            post("/api/console/finance/cases")
                .with(login("f", "FINANCE", null))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/console/finance/cases")
                .with(login("a", "ADMIN", null))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    String id = write("/cases", Map.of("issueId", issue), "one");
    mvc.perform(get("/api/console/finance/cases/" + id).with(login("foreign", "MERCHANT", "other")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/console/finance/cases/" + id + "/investigate")
                .with(login("one", "FINANCE", null))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("assignee", "one", "paymentId", root, "evidence", "checked"))))
        .andExpect(status().isBadRequest());
    write(
        "/cases/" + id + "/investigate",
        Map.of("expectedVersion", 1, "assignee", "one", "paymentId", root, "evidence", "checked"),
        "one");
    String adjustment =
        write(
            "/cases/" + id + "/proposals",
            Map.of(
                "expectedVersion",
                2,
                "direction",
                "NONE",
                "amountMinor",
                "0",
                "reason",
                "No adjustment required"),
            "one");
    String review =
        json.writeValueAsString(
            Map.of("expectedVersion", 1, "approve", true, "reason", "reviewed"));
    mvc.perform(
            post("/api/console/finance/adjustments/" + adjustment + "/review")
                .with(login("one", "FINANCE", null))
                .with(csrf())
                .contentType("application/json")
                .content(review))
        .andExpect(status().isConflict());
    write(
        "/adjustments/" + adjustment + "/review",
        Map.of("expectedVersion", 1, "approve", true, "reason", "reviewed"),
        "two");
    write(
        "/adjustments/" + adjustment + "/execute",
        Map.of("expectedVersion", 1, "reason", "close case"),
        "one");
    mvc.perform(
            get("/api/console/finance/adjustments/" + adjustment)
                .with(login("auditor", "AUDITOR", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXECUTED"));
  }

  @Test
  void quarantineIsOnlyAvailableToUnscopedOperators() throws Exception {
    String id = delivery.quarantine("api-fixture", 0, System.nanoTime(), "invalid", "poison");
    mvc.perform(get("/api/console/events/quarantine").with(login("f", "FINANCE", null)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/console/events/quarantine/" + id + "/replay")
                .with(login("ops", "OPS", "other"))
                .with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/console/events/quarantine/" + id + "/replay")
                .with(login("ops", "OPS", null))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
  }

  @Test
  void rootlessNoAdjustmentInvestigationIsAvailableThroughTheRealApi() throws Exception {
    jdbc.update(
        "UPDATE recon_issues SET aggregate_id='external-channel-id',issue_type='CHANNEL_ONLY' WHERE id=?",
        issue);
    String id = write("/cases", Map.of("issueId", issue), "one");
    write(
        "/cases/" + id + "/investigate",
        Map.of("expectedVersion", 1, "assignee", "one", "evidence", "已核对，无本地支付"),
        "one");
    String adjustment =
        write(
            "/cases/" + id + "/proposals",
            Map.of("expectedVersion", 2, "direction", "NONE", "amountMinor", "0", "reason", "无需调账"),
            "one");
    write(
        "/adjustments/" + adjustment + "/review",
        Map.of("expectedVersion", 1, "approve", true, "reason", "独立核对渠道重复行"),
        "two");
    write(
        "/adjustments/" + adjustment + "/execute",
        Map.of("expectedVersion", 1, "reason", "关闭差异"),
        "one");
    mvc.perform(get("/api/console/finance/cases/" + id).with(login("audit", "AUDITOR", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.paymentId").isEmpty());
  }
}
