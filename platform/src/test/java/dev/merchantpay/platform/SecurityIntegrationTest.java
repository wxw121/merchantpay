package dev.merchantpay.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.merchant.MerchantService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:security;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
class SecurityIntegrationTest {
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class TestOidc {
    @org.springframework.context.annotation.Bean
    org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
        registrations() {
      var registration =
          org.springframework.security.oauth2.client.registration.ClientRegistration
              .withRegistrationId("keycloak")
              .clientId("merchantpay-console")
              .clientSecret("test")
              .authorizationGrantType(
                  org.springframework.security.oauth2.core.AuthorizationGrantType
                      .AUTHORIZATION_CODE)
              .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
              .scope("openid")
              .authorizationUri("https://issuer.test/auth")
              .tokenUri("https://issuer.test/token")
              .jwkSetUri("https://issuer.test/certs")
              .userInfoUri("https://issuer.test/userinfo")
              .userNameAttributeName("sub")
              .issuerUri("https://issuer.test")
              .build();
      return new org.springframework.security.oauth2.client.registration
          .InMemoryClientRegistrationRepository(registration);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired MerchantService merchants;
  @Autowired dev.merchantpay.platform.reconciliation.ExportService exports;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  MerchantService.Key key;

  @BeforeEach
  void seed() {
    merchants.configure("security-a", "安全测试 A", true, null, "test");
    key = merchants.rotate("security-a", "test");
  }

  @Test
  void exactMerchantLookupFindsAnOptionBeyondTheSearchPageAndStillEnforcesTenantScope()
      throws Exception {
    for (int index = 0; index < 102; index++) {
      jdbc.update(
          "INSERT INTO merchants(id,name,enabled) VALUES (?,?,TRUE)",
          "choice-" + String.format("%03d", index),
          index == 101 ? "目标商户" : "候选商户 choice-101 " + index);
    }
    var admin = oidcLogin().idToken(token -> token.claim("roles", java.util.List.of("ADMIN")));
    mvc.perform(get("/api/console/merchants?q=choice-").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(100));
    mvc.perform(get("/api/console/merchants?q=choice-101").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("choice-101"));
    mvc.perform(get("/api/console/merchants/choice-101").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("choice-101"));
    var merchant =
        oidcLogin()
            .idToken(
                token ->
                    token
                        .claim("roles", java.util.List.of("MERCHANT"))
                        .claim("merchant_id", "security-a"));
    mvc.perform(get("/api/console/merchants/choice-101").with(merchant))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/console/merchants/security-a").with(merchant)).andExpect(status().isOk());
  }

  @Test
  void invalidTimeRangeIsAClientErrorInsteadOfAServerFailure() throws Exception {
    var admin = oidcLogin().idToken(token -> token.claim("roles", java.util.List.of("ADMIN")));
    mvc.perform(get("/api/console/tasks?from=invalid&until=2026-10-18T00:00:00Z").with(admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
  }

  @Test
  void invalidSignatureCannotCreateAnOrder() throws Exception {
    mvc.perform(post("/api/v1/payments").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
  }

  @Test
  void backlogPressureHasRetryHeaderForSignedAndConsoleCreation() throws Exception {
    var saved = jdbc.queryForList("SELECT logical_shard,inflight FROM payment_admission");
    try {
      jdbc.update("UPDATE payment_admission SET inflight=16");
      String path = "/api/v1/payments";
      String body =
          "{\"merchantOrderNo\":\"overloaded-"
              + UUID.randomUUID()
              + "\",\"amountMinor\":\"10000\",\"currency\":\"CNY\",\"channel\":\"MOCK_A\"}";
      String timestamp = Long.toString(Instant.now().getEpochSecond());
      String nonce = UUID.randomUUID().toString();
      mvc.perform(
              post(path)
                  .contentType("application/json")
                  .content(body)
                  .header("X-Key-Id", key.keyId())
                  .header("X-Timestamp", timestamp)
                  .header("X-Nonce", nonce)
                  .header(
                      "X-Signature",
                      Signatures.sign(key.secret(), timestamp, nonce, "POST", path, body)))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string("Retry-After", "1"))
          .andExpect(jsonPath("$.code").value("RETRY_WITH_SAME_BUSINESS_KEY"));
      mvc.perform(
              post("/api/console/payments?merchantId=security-a")
                  .with(csrf())
                  .with(
                      oidcLogin()
                          .idToken(token -> token.claim("roles", java.util.List.of("ADMIN"))))
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string("Retry-After", "1"))
          .andExpect(jsonPath("$.code").value("RETRY_WITH_SAME_BUSINESS_KEY"));
    } finally {
      for (var row : saved)
        jdbc.update(
            "UPDATE payment_admission SET inflight=? WHERE logical_shard=?",
            row.get("inflight"),
            row.get("logical_shard"));
    }
  }

  @Test
  void signedOrderIsIdempotentButNonceReplayIsRejected() throws Exception {
    String path = "/api/v1/payments",
        body =
            "{\"merchantOrderNo\":\"security-"
                + UUID.randomUUID()
                + "\",\"amountMinor\":\"10000\",\"currency\":\"CNY\",\"channel\":\"MOCK_A\"}";
    String timestamp = Long.toString(Instant.now().getEpochSecond()),
        nonce = UUID.randomUUID().toString();
    var request =
        post(path)
            .contentType("application/json")
            .content(body)
            .header("X-Key-Id", key.keyId())
            .header("X-Timestamp", timestamp)
            .header("X-Nonce", nonce)
            .header(
                "X-Signature", Signatures.sign(key.secret(), timestamp, nonce, "POST", path, body));
    mvc.perform(request)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PROCESSING"));
    mvc.perform(request)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REPLAY_REJECTED"));
  }

  @Test
  void exhaustedQuotaReturns429AndTheRejectedNonceCannotBeReplayed() throws Exception {
    long now = Instant.now().getEpochSecond();
    for (long window = now - 1; window <= now + 10; window++) {
      jdbc.update(
          "INSERT INTO api_rate_limits(key_id,window_second,requests) VALUES (?,?,500)",
          key.keyId(),
          window);
    }
    String path = "/api/v1/payments/missing",
        timestamp = Long.toString(now),
        nonce = UUID.randomUUID().toString();
    var request =
        get(path)
            .header("X-Key-Id", key.keyId())
            .header("X-Timestamp", timestamp)
            .header("X-Nonce", nonce)
            .header(
                "X-Signature", Signatures.sign(key.secret(), timestamp, nonce, "GET", path, ""));
    mvc.perform(request)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    mvc.perform(request)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REPLAY_REJECTED"));
  }

  @Test
  void consoleWriteRequiresCsrfAndMerchantScopeIsServerEnforced() throws Exception {
    var merchant =
        oidcLogin()
            .idToken(
                token ->
                    token
                        .claim("roles", java.util.List.of("MERCHANT"))
                        .claim("merchant_id", "security-a"));
    mvc.perform(
            post("/api/console/payments")
                .with(merchant)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/console/payments?merchantId=another-merchant").with(merchant))
        .andExpect(status().isNotFound());
  }

  @Test
  void auditorCannotStartARefund() throws Exception {
    mvc.perform(
            post("/api/console/payments/missing/refunds")
                .with(
                    oidcLogin()
                        .idToken(token -> token.claim("roles", java.util.List.of("AUDITOR"))))
                .with(csrf())
                .contentType("application/json")
                .content("{\"merchantRefundNo\":\"r\",\"amountMinor\":\"100\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void moneyMustBeAJsonStringRatherThanANumber() throws Exception {
    mvc.perform(
            post("/api/console/payments")
                .with(
                    oidcLogin()
                        .idToken(
                            token ->
                                token
                                    .claim("roles", java.util.List.of("MERCHANT"))
                                    .claim("merchant_id", "security-a")))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"merchantOrderNo\":\"numeric-"
                        + UUID.randomUUID()
                        + "\",\"amountMinor\":10000,\"currency\":\"CNY\",\"channel\":\"MOCK_A\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void metricsCredentialCannotAccessBusinessData() throws Exception {
    mvc.perform(get("/actuator/prometheus").with(httpBasic("metrics", "test-metrics-password")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/console/dashboard").with(httpBasic("metrics", "test-metrics-password")))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void exportOwnershipExpiryAndChangedPermissionsAreCheckedByTheServer() throws Exception {
    String id = exports.create("security-a", "owner-a", "payments", null, null);
    var owner =
        oidcLogin()
            .idToken(
                token ->
                    token
                        .subject("owner-a")
                        .claim("roles", java.util.List.of("MERCHANT"))
                        .claim("merchant_id", "security-a"));
    var other =
        oidcLogin()
            .idToken(
                token ->
                    token
                        .subject("owner-b")
                        .claim("roles", java.util.List.of("MERCHANT"))
                        .claim("merchant_id", "security-a"));
    mvc.perform(get("/api/console/exports").with(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
    mvc.perform(get("/api/console/exports").with(other))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(get("/api/console/exports/" + id + "/download").with(other))
        .andExpect(status().isNotFound());
    jdbc.update(
        "UPDATE exports SET expires_at=? WHERE id=?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
        id);
    mvc.perform(get("/api/console/exports/" + id + "/download").with(owner))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("EXPORT_EXPIRED"));
    mvc.perform(
            get("/api/console/exports/" + id + "/download")
                .with(
                    oidcLogin()
                        .idToken(
                            token -> token.subject("owner-a").claim("roles", java.util.List.of()))))
        .andExpect(status().isForbidden());
  }
}
