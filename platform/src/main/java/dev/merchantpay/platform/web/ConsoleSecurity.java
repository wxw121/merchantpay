package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.PathContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConsoleSecurity {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Access access;
  private final boolean enabled, mfa, shared;

  public ConsoleSecurity(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      Clock clock,
      Access access,
      @Value("${merchantpay.console-high-risk-enabled:false}") boolean enabled,
      @Value("${merchantpay.console-mfa-required:false}") boolean mfa,
      @Value("${merchantpay.shared-sessions-enabled:false}") boolean shared) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
    this.access = access;
    this.enabled = enabled;
    this.mfa = mfa;
    this.shared = shared;
  }

  public boolean protectedAction(String method, PathContainer path) {
    return enabled
        && !Set.of("GET", "HEAD", "OPTIONS").contains(method)
        && ApiPaths.CONSOLE.matches(path)
        && !ApiPaths.CONFIRMATIONS.matches(path);
  }

  private static String sessionHash(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session == null) throw new ApiException("UNAUTHENTICATED", 401, "会话已失效");
    return Signatures.sha256(session.getId());
  }

  public void checkRevocation(HttpServletRequest request) {
    if (!shared || request.getSession(false) == null) return;
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM revoked_console_sessions WHERE session_hash=? AND expires_at>?",
            Long.class,
            sessionHash(request),
            clock.millis())
        > 0) {
      request.getSession(false).invalidate();
      throw new ApiException("SESSION_REVOKED", 401, "此会话已撤销，请重新登录");
    }
  }

  public boolean freshMfa() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user))
      return false;
    var claims = user.getIdToken().getClaims();
    if (!"2".equals(Objects.toString(claims.get("acr"), ""))) return false;
    try {
      Object raw = claims.get("auth_time");
      Instant time =
          raw instanceof Instant value
              ? value
              : Instant.ofEpochSecond(Long.parseLong(raw.toString()));
      long age = Duration.between(time, clock.instant()).getSeconds();
      return age >= -10 && age <= 300;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public void requireMfa() {
    var caller = access.caller();
    if (mfa
        && !Collections.disjoint(caller.roles(), Set.of("ADMIN", "OPS", "FINANCE"))
        && !freshMfa())
      throw new ApiException("MFA_REQUIRED", 403, "此操作需要最近五分钟内完成双因素认证，请前往安全中心重新认证");
  }

  public Map<String, Object> status() {
    return Map.of(
        "confirmationsEnabled",
        enabled,
        "mfaRequired",
        mfa,
        "freshMfa",
        freshMfa(),
        "sharedSessions",
        shared,
        "stepUpUrl",
        "/oauth2/authorization/keycloak?step_up=1");
  }

  public Map<String, String> issue(
      String method, String path, String body, HttpServletRequest request) {
    access.require("ADMIN", "OPS", "FINANCE", "MERCHANT", "AUDITOR");
    requireMfa();
    checkRevocation(request);
    if (path == null
        || path.length() > 512
        || !path.matches("/[A-Za-z0-9_./%?=&+:-]+")
        || !Set.of("POST", "PUT", "PATCH", "DELETE").contains(Objects.toString(method, ""))
        || body == null
        || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
      throw new ApiException("INVALID_CONFIRMATION", 400, "确认请求无效或内容过大");
    try {
      if (!protectedAction(method, ApiPaths.target(path, request)))
        throw new ApiException("INVALID_CONFIRMATION", 400, "确认请求不属于受保护的操作");
    } catch (IllegalArgumentException invalid) {
      throw new ApiException("INVALID_CONFIRMATION", 400, "确认目标路径无效");
    }
    String id = UUID.randomUUID().toString(), hash = Signatures.sha256(body);
    Instant expires = clock.instant().plusSeconds(120);
    jdbc.update(
        "INSERT INTO console_confirmations(id,actor,session_hash,method,path,body_hash,expires_at) VALUES (?,?,?,?,?,?,?)",
        id,
        access.caller().actor(),
        sessionHash(request),
        method,
        path,
        hash,
        Timestamp.from(expires));
    return Map.of(
        "token",
        id,
        "method",
        method,
        "path",
        path,
        "bodyHash",
        hash,
        "expiresAt",
        expires.toString());
  }

  public void consume(
      String token, String method, String path, String body, HttpServletRequest request) {
    requireMfa();
    if (token == null || token.length() != 36)
      throw new ApiException("CONFIRMATION_REQUIRED", 428, "请核对并确认本次操作");
    int changed =
        jdbc.update(
            "UPDATE console_confirmations SET consumed_at=? WHERE id=? AND actor=? AND session_hash=? AND method=? AND path=? AND body_hash=? AND expires_at>? AND consumed_at IS NULL",
            Timestamp.from(clock.instant()),
            token,
            access.caller().actor(),
            sessionHash(request),
            method,
            path,
            Signatures.sha256(body),
            Timestamp.from(clock.instant()));
    if (changed != 1) throw new ApiException("CONFIRMATION_EXPIRED", 409, "操作内容、会话已变化或确认已使用，请重新核对");
  }

  public List<Map<String, Object>> sessions(HttpServletRequest request) {
    if (!shared) return List.of();
    String actor = access.caller().actor();
    String current = request.getSession(false) == null ? "" : request.getSession(false).getId();
    var rows =
        jdbc.queryForList(
            "SELECT PRIMARY_ID,SESSION_ID,CREATION_TIME,LAST_ACCESS_TIME,EXPIRY_TIME FROM console_sessions WHERE PRINCIPAL_NAME=? AND EXPIRY_TIME>? ORDER BY LAST_ACCESS_TIME DESC LIMIT 50",
            actor,
            clock.millis());
    var result = new ArrayList<Map<String, Object>>();
    for (var row : rows)
      result.add(
          Map.of(
              "id",
              row.get("PRIMARY_ID"),
              "createdAt",
              Instant.ofEpochMilli(((Number) row.get("CREATION_TIME")).longValue()).toString(),
              "lastAccessAt",
              Instant.ofEpochMilli(((Number) row.get("LAST_ACCESS_TIME")).longValue()).toString(),
              "expiresAt",
              Instant.ofEpochMilli(((Number) row.get("EXPIRY_TIME")).longValue()).toString(),
              "current",
              current.equals(row.get("SESSION_ID"))));
    return result;
  }

  public void revoke(String primaryId, HttpServletRequest request) {
    if (!shared) throw new ApiException("SHARED_SESSION_DISABLED", 409, "未启用共享会话");
    String actor = access.caller().actor();
    var current = request.getSession(false);
    tx.executeWithoutResult(
        t -> {
          var rows =
              jdbc.queryForList(
                  "SELECT SESSION_ID,EXPIRY_TIME FROM console_sessions WHERE PRIMARY_ID=? AND PRINCIPAL_NAME=? FOR UPDATE",
                  primaryId,
                  actor);
          if (rows.isEmpty()) throw new ApiException("NOT_FOUND", 404, "会话不存在");
          var row = rows.getFirst();
          String sid = row.get("SESSION_ID").toString();
          jdbc.update(
              "INSERT INTO revoked_console_sessions(session_hash,expires_at) VALUES (?,?) ON DUPLICATE KEY UPDATE expires_at=GREATEST(expires_at,VALUES(expires_at))",
              Signatures.sha256(sid),
              Math.max(((Number) row.get("EXPIRY_TIME")).longValue(), clock.millis() + 3600000));
          jdbc.update(
              "DELETE FROM console_sessions_ATTRIBUTES WHERE SESSION_PRIMARY_ID=?", primaryId);
          jdbc.update("DELETE FROM console_sessions WHERE PRIMARY_ID=?", primaryId);
          jdbc.update(
              "INSERT INTO audit_records(id,actor,operation,resource_id,detail,created_at) VALUES (?,?,'SESSION_REVOKE',?,'Own session revoked',?)",
              UUID.randomUUID().toString(),
              actor,
              primaryId,
              Timestamp.from(clock.instant()));
          if (current != null && sid.equals(current.getId()))
            // Spring Session deletes in REQUIRES_NEW; release our row lock before invalidating.
            // A joined outer transaction may still roll back, so defer to its successful commit.
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void afterCommit() {
                    current.invalidate();
                  }
                });
        });
  }

  public List<Map<String, Object>> rotationReminders(String merchant) {
    access.require("ADMIN", "OPS", "MERCHANT");
    String scope = access.merchant(merchant, true);
    return jdbc.query(
        "SELECT id,merchant_id,created_at,expires_at FROM merchant_keys WHERE merchant_id=? AND enabled=true AND (expires_at IS NULL OR expires_at>?) AND created_at<=? ORDER BY created_at LIMIT 100",
        dev.merchantpay.platform.query.Queries.ROW,
        scope,
        Timestamp.from(clock.instant()),
        Timestamp.from(clock.instant().minus(Duration.ofDays(90))));
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
  void cleanup() {
    if (!enabled && !shared) return;
    dev.merchantpay.platform.common.DatabaseRole.worker(
        () -> {
          jdbc.update(
              "DELETE FROM console_confirmations WHERE expires_at<? LIMIT 1000",
              Timestamp.from(clock.instant()));
          jdbc.update(
              "DELETE FROM revoked_console_sessions WHERE expires_at<? LIMIT 1000", clock.millis());
        });
  }
}
