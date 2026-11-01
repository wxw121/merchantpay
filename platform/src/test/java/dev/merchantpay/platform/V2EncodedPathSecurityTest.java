package dev.merchantpay.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.merchant.MerchantService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:encoded-path-security;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "merchantpay.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "merchantpay.channel-token=test",
      "merchantpay.callback-secret=test",
      "merchantpay.workers-enabled=false",
      "merchantpay.console-high-risk-enabled=true",
      "merchantpay.console-mfa-required=true",
      "merchantpay.metrics-password=test-metrics-password",
      "spring.security.oauth2.client.registration.keycloak.client-secret=test"
    })
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(SecurityIntegrationTest.TestOidc.class)
class V2EncodedPathSecurityTest {
  @Autowired MockMvc mvc;
  @Autowired MerchantService merchants;
  @Autowired JsonMapper json;

  private OidcLoginRequestPostProcessor actor(String acr) {
    return oidcLogin()
        .idToken(
            token ->
                token
                    .subject("encoded-path-admin")
                    .claim("roles", List.of("ADMIN"))
                    .claim("acr", acr)
                    .claim("auth_time", Instant.now()));
  }

  @Test
  void encodedConsolePrefixesAndContextPathsCannotBypassMfaOrConfirmation() throws Exception {
    String body = "{\"expectedVersion\":1,\"reason\":\"path boundary\"}";
    for (String path :
        List.of(
            "/api/console/finance/settlements/missing/execute",
            "/api/%63onsole/finance/settlements/missing/execute",
            "/%61pi/console/finance/settlements/missing/execute",
            "/app/api/%63%6Fnsole/finance/settlements/missing/execute")) {
      String context = path.startsWith("/app/") ? "/app" : "";
      mvc.perform(
              post(URI.create(path))
                  .contextPath(context)
                  .with(actor("1"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
      mvc.perform(
              post(URI.create(path))
                  .contextPath(context)
                  .with(actor("2"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().is(428))
          .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));
    }
  }

  @Test
  void encodedUploadStillRequiresMfaBeforeProcessingAnyFile() throws Exception {
    mvc.perform(
            multipart(URI.create("/api/%63onsole/reconciliation/upload"))
                .file("file", new byte[0])
                .with(actor("1"))
                .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
  }

  @Test
  void encodedConfirmationEndpointUsesTheExactOriginalTargetQueryAndBody() throws Exception {
    var session = new MockHttpSession();
    String path = "/api/%63onsole/merchants?marker=a%2Bb";
    String body =
        json.writeValueAsString(
            Map.of("id", "encoded-" + UUID.randomUUID(), "name", "Encoded path", "enabled", true));
    String challenge =
        mvc.perform(
                post(URI.create("/api/console/security/%63onfirmations"))
                    .session(session)
                    .with(actor("2"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("method", "POST", "path", path, "body", body))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = json.readTree(challenge).get("token").asString();
    for (String different : List.of("/api/console/merchants?marker=a%2Bb", path + "x"))
      mvc.perform(
              post(URI.create(different))
                  .session(session)
                  .with(actor("2"))
                  .with(csrf())
                  .header("X-Action-Confirmation", token)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("CONFIRMATION_EXPIRED"));
    mvc.perform(
            post(URI.create(path))
                .session(session)
                .with(actor("2"))
                .with(csrf())
                .header("X-Action-Confirmation", token)
                .contentType("application/json")
                .content(body + " "))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFIRMATION_EXPIRED"));
    mvc.perform(
            post(URI.create(path))
                .session(session)
                .with(actor("2"))
                .with(csrf())
                .header("X-Action-Confirmation", token)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            post(URI.create(path))
                .session(session)
                .with(actor("2"))
                .with(csrf())
                .header("X-Action-Confirmation", token)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFIRMATION_EXPIRED"));
  }

  @Test
  void encodedMerchantApiCannotUseAnOidcSessionToSkipItsSignature() throws Exception {
    for (String path : List.of("/api/%761/payments", "/%61pi/v1/payments", "/api/v1/%70ayments"))
      mvc.perform(
              post(URI.create(path))
                  .with(actor("2"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
  }

  @Test
  void signedEncodedApiStillBindsTheOriginalUrlAndDoesNotRequireBrowserCsrf() throws Exception {
    merchants.configure("encoded-api", "Encoded API", true, null, "test");
    var key = merchants.rotate("encoded-api", "test");
    String path = "/api/%761/payments";
    String body =
        json.writeValueAsString(
            Map.of(
                "merchantOrderNo",
                "encoded-" + UUID.randomUUID(),
                "amountMinor",
                "1000",
                "currency",
                "CNY",
                "channel",
                "MOCK_A"));
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String nonce = UUID.randomUUID().toString();
    mvc.perform(
            post(URI.create(path))
                .contentType("application/json")
                .content(body)
                .header("X-Key-Id", key.keyId())
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header(
                    "X-Signature",
                    Signatures.sign(
                        key.secret(), timestamp, nonce, "POST", "/api/v1/payments", body)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
    mvc.perform(
            post(URI.create(path))
                .contentType("application/json")
                .content(body)
                .header("X-Key-Id", key.keyId())
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header(
                    "X-Signature",
                    Signatures.sign(key.secret(), timestamp, nonce, "POST", path, body)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.channel").value("MOCK_A"));
  }
}
