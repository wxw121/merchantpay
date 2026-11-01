package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class V2ConsoleSecurityTest extends FinancialFixture {
  Access access;
  ConsoleSecurity security;
  Clock clock;
  MockHttpServletRequest request;

  @BeforeEach
  void schema() {
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V18__reliable_domain_events.sql"));
    ScriptUtils.executeSqlScript(
        schemaConnection, new ClassPathResource("db/migration/V22__console_security.sql"));
    access = mock(Access.class);
    when(access.caller()).thenReturn(new Access.Caller("actor", null, Set.of("ADMIN")));
    clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
    security =
        new ConsoleSecurity(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            clock,
            access,
            true,
            false,
            false);
    request = new MockHttpServletRequest();
    request.getSession();
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void auditorCanConfirmTheirOwnSessionRevocation() {
    var actual =
        new ConsoleSecurity(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            clock,
            new Access(),
            true,
            false,
            false);
    ScopedValue.where(Access.CURRENT, new Access.Caller("auditor", null, Set.of("AUDITOR")))
        .run(
            () ->
                assertThat(
                        actual
                            .issue(
                                "POST",
                                "/api/console/security/sessions/primary/revoke",
                                "",
                                request)
                            .get("token"))
                    .isNotBlank());
  }

  @Test
  void confirmationIsBoundToExactBodySessionActorAndConsumedOnce() {
    String path = "/api/console/merchants", body = "{\"id\":\"merchant-a\"}";
    var token = security.issue("POST", path, body, request).get("token");
    assertThatThrownBy(() -> security.consume(token, "POST", path, body + " ", request))
        .isInstanceOf(ApiException.class);
    var other = new MockHttpServletRequest();
    other.getSession();
    assertThatThrownBy(() -> security.consume(token, "POST", path, body, other))
        .isInstanceOf(ApiException.class);
    security.consume(token, "POST", path, body, request);
    assertThatThrownBy(() -> security.consume(token, "POST", path, body, request))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void expiredConfirmationAndDifferentQueryCannotExecute() {
    var token =
        security
            .issue("POST", "/api/console/payments?merchantId=merchant-a", "{}", request)
            .get("token");
    assertThatThrownBy(
            () ->
                security.consume(
                    token, "POST", "/api/console/payments?merchantId=merchant-b", "{}", request))
        .isInstanceOf(ApiException.class);
    jdbc.update("UPDATE console_confirmations SET expires_at=TIMESTAMP '2020-01-01 00:00:00'");
    assertThatThrownBy(
            () ->
                security.consume(
                    token, "POST", "/api/console/payments?merchantId=merchant-a", "{}", request))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void onlyVerifiedRecentIdTokenAcrTwoSatisfiesMfa() {
    var strict =
        new ConsoleSecurity(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            clock,
            access,
            true,
            true,
            false);
    assertThatThrownBy(strict::requireMfa).isInstanceOf(ApiException.class);
    authenticate("1", clock.instant());
    assertThat(strict.freshMfa()).isFalse();
    authenticate("2", clock.instant().minusSeconds(301));
    assertThat(strict.freshMfa()).isFalse();
    authenticate("2", clock.instant());
    strict.requireMfa();
  }

  private void authenticate(String acr, Instant at) {
    var token =
        new OidcIdToken(
            "signed-token",
            clock.instant(),
            clock.instant().plusSeconds(600),
            Map.of("sub", "actor", "acr", acr, "auth_time", at));
    var user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), token);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user, "", user.getAuthorities()));
  }

  @Test
  void confirmationTargetsRejectOtherRoutesMalformedEscapesAndTheirOwnEndpoint() {
    for (String path :
        List.of(
            "/api/consolex/merchants",
            "/api/%63onsole/security/%63onfirmations",
            "/api/console/security/confirmations?next=pay",
            "/api/console/merchants%ZZ",
            "/api/console/merchants%2")) {
      assertThatThrownBy(() -> security.issue("POST", path, "{}", request))
          .isInstanceOf(ApiException.class);
    }
    assertThat(count("console_confirmations")).isZero();
  }

  @Test
  void encodedConsoleReadsStillCheckRevocationBeforeReachingAHandler() throws Exception {
    var shared =
        new ConsoleSecurity(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            clock,
            access,
            true,
            false,
            true);
    request.setMethod("GET");
    request.setRequestURI("/api/%63onsole/security/status");
    jdbc.update(
        "INSERT INTO revoked_console_sessions(session_hash,expires_at) VALUES (?,?)",
        dev.merchantpay.platform.common.Signatures.sha256(request.getSession().getId()),
        clock.millis() + 60000);
    var response = new MockHttpServletResponse();
    new HighRiskFilter(shared, JsonMapper.builder().build())
        .doFilter(
            request, response, (incoming, outgoing) -> fail("Revoked session reached the handler"));
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("SESSION_REVOKED");
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void currentSharedSessionInvalidatesOnlyAfterSuccessfulCommitAndCannotBeReused()
      throws Exception {
    var manager = new DataSourceTransactionManager(jdbc.getDataSource());
    var sessionTx = new TransactionTemplate(manager);
    sessionTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    sessionTx.setTimeout(2);
    var jdbcSessions = new JdbcIndexedSessionRepository(jdbc, sessionTx);
    jdbcSessions.setTableName("console_sessions");
    SessionRepository<?> sessions = jdbcSessions;
    var filter = new SessionRepositoryFilter<>(sessions);
    filter.setHttpSessionIdResolver(new HeaderHttpSessionIdResolver("X-Test-Session"));
    var login = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest(),
        login,
        (wrapped, response) ->
            ((HttpServletRequest) wrapped)
                .getSession()
                .setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "actor"));
    String sid = login.getHeader("X-Test-Session");
    assertThat(sid).isNotBlank();
    String primary =
        jdbc.queryForObject(
            "SELECT PRIMARY_ID FROM console_sessions WHERE SESSION_ID=?", String.class, sid);
    var shared =
        new ConsoleSecurity(
            jdbc, new TransactionTemplate(manager), clock, access, true, false, true);
    var attempt = new MockHttpServletRequest();
    attempt.addHeader("X-Test-Session", sid);
    filter.doFilter(
        attempt,
        new MockHttpServletResponse(),
        (wrapped, response) -> {
          var current = (HttpServletRequest) wrapped;
          assertThatThrownBy(
                  () ->
                      new TransactionTemplate(manager)
                          .executeWithoutResult(
                              status -> {
                                shared.revoke(primary, current);
                                assertThat(current.getSession(false)).isNotNull();
                                throw new IllegalStateException("force outer rollback");
                              }))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("force outer rollback");
          assertThat(current.getSession(false)).isNotNull();
          assertThat(count("console_sessions")).isEqualTo(1);
          assertThat(count("revoked_console_sessions")).isZero();
          shared.revoke(primary, current);
          assertThat(current.getSession(false)).isNull();
          assertThat(count("console_sessions")).isZero();
          assertThat(count("revoked_console_sessions")).isEqualTo(1);
        });
    assertThat(sessions.findById(sid)).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE operation='SESSION_REVOKE'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void sessionRevocationCannotTargetAnotherUserAndNeverExposesBearer() {
    var shared =
        new ConsoleSecurity(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            clock,
            access,
            true,
            false,
            true);
    jdbc.update(
        "INSERT INTO console_sessions VALUES ('primary','bearer',?,?,1800,?,'actor')",
        clock.millis(),
        clock.millis(),
        clock.millis() + 1800000);
    assertThat(shared.sessions(request).getFirst())
        .doesNotContainKey("SESSION_ID")
        .doesNotContainValue("bearer");
    when(access.caller()).thenReturn(new Access.Caller("other", null, Set.of("ADMIN")));
    assertThatThrownBy(() -> shared.revoke("primary", request)).isInstanceOf(ApiException.class);
  }
}
