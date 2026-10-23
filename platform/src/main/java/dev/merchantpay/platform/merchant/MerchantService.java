package dev.merchantpay.platform.merchant;

import dev.merchantpay.platform.common.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MerchantService {
  public record Key(String keyId, String merchantId, String secret) {}

  private final JdbcTemplate jdbc;
  private final KeyVault vault;
  private final NotificationPolicy urls;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public MerchantService(
      JdbcTemplate jdbc,
      KeyVault vault,
      NotificationPolicy urls,
      TransactionTemplate transactions,
      Clock clock) {
    this.jdbc = jdbc;
    this.vault = vault;
    this.urls = urls;
    this.transactions = transactions;
    this.clock = clock;
  }

  public void configure(
      String id, String name, boolean enabled, String notificationUrl, String actor) {
    if (id == null
        || !id.matches("[a-zA-Z0-9_-]{1,64}")
        || name == null
        || name.isBlank()
        || name.length() > 100) {
      throw new ApiException("INVALID_MERCHANT", 400, "商户编号或名称无效");
    }
    String url = urls.validate(notificationUrl);
    transactions.executeWithoutResult(
        tx -> {
          jdbc.update(
              "INSERT INTO merchants(id,name,enabled,notification_url) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),enabled=VALUES(enabled),notification_url=VALUES(notification_url)",
              id,
              name,
              enabled,
              url);
          audit(id, actor, "MERCHANT_CONFIGURE", id, "enabled=" + enabled);
        });
  }

  public Key rotate(String merchantId, String actor) {
    return transactions.execute(
        tx -> {
          if (jdbc.queryForList("SELECT id FROM merchants WHERE id=? FOR UPDATE", merchantId)
              .isEmpty()) {
            throw new ApiException("MERCHANT_NOT_FOUND", 404, "商户不存在");
          }
          var now = clock.instant();
          // The previous key remains valid for one hour; explicit revocation is available
          // immediately.
          jdbc.update(
              "UPDATE merchant_keys SET expires_at=? WHERE merchant_id=? AND enabled=TRUE AND expires_at IS NULL",
              Timestamp.from(now.plusSeconds(3600)),
              merchantId);
          byte[] bytes = new byte[32];
          new java.security.SecureRandom().nextBytes(bytes);
          String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
              id = "key_" + UUID.randomUUID();
          jdbc.update(
              "INSERT INTO merchant_keys(id,merchant_id,secret_cipher,enabled,created_at) VALUES (?,?,?,TRUE,?)",
              id,
              merchantId,
              vault.encrypt(secret),
              Timestamp.from(now));
          audit(merchantId, actor, "KEY_ROTATE", id, "Previous key expires after one hour");
          return new Key(id, merchantId, secret);
        });
  }

  public Key key(String id) {
    var rows =
        jdbc.query(
            "SELECT * FROM merchant_keys WHERE id=? AND enabled=TRUE AND (expires_at IS NULL OR expires_at>?)",
            (rs, n) ->
                new Key(
                    rs.getString("id"),
                    rs.getString("merchant_id"),
                    vault.decrypt(rs.getString("secret_cipher"))),
            id,
            Timestamp.from(clock.instant()));
    if (rows.isEmpty()) {
      throw new ApiException("INVALID_API_KEY", 401, "接口凭证无效或已过期");
    }
    return rows.getFirst();
  }

  public Key notificationKey(String merchant) {
    var ids =
        jdbc.queryForList(
            "SELECT id FROM merchant_keys WHERE merchant_id=? AND enabled=TRUE AND (expires_at IS NULL OR expires_at>?) ORDER BY created_at DESC LIMIT 1",
            String.class,
            merchant,
            Timestamp.from(clock.instant()));
    if (ids.isEmpty()) {
      throw new ApiException("NO_NOTIFICATION_KEY", 409, "商户没有可用通知密钥");
    }
    return key(ids.getFirst());
  }

  public void audit(
      String merchant, String actor, String operation, String resource, String detail) {
    String requestId = org.slf4j.MDC.get("requestId");
    if (requestId != null) {
      detail = java.util.Objects.toString(detail, "") + " [requestId=" + requestId + "]";
    }
    jdbc.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        merchant,
        actor,
        operation,
        resource,
        detail,
        Timestamp.from(clock.instant()));
  }
}
