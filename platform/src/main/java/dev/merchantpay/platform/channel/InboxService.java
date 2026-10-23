package dev.merchantpay.platform.channel;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.outbox.OutboxService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class InboxService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final OutboxService outbox;
  private final ChannelGateway gateway;
  private final JsonMapper json;
  private final Clock clock;
  private final String secret;

  public InboxService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      OutboxService outbox,
      ChannelGateway gateway,
      JsonMapper json,
      Clock clock,
      @Value("${merchantpay.callback-secret}") String secret) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.outbox = outbox;
    this.gateway = gateway;
    this.json = json;
    this.clock = clock;
    this.secret = secret;
  }

  public void receive(String timestamp, String signature, String body) {
    if (timestamp == null
        || !timestamp.matches("[0-9]{10}")
        || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) > 300
        || !Signatures.constantEquals(
            Signatures.hmac(secret, timestamp + "\n" + body), signature)) {
      throw new ApiException("INVALID_CHANNEL_SIGNATURE", 401, "渠道通知验签失败");
    }
    var receipt = json.readValue(body, ChannelApi.Receipt.class);
    if (receipt.aggregateId() == null
        || receipt.aggregateId().length() > 64
        || !("MOCK_A".equals(receipt.channel()) || "MOCK_B".equals(receipt.channel()))
        || !("PAYMENT".equals(receipt.operation()) || "REFUND".equals(receipt.operation()))) {
      throw new ApiException("INVALID_CHANNEL_RECEIPT", 400, "渠道通知关联字段无效");
    }
    String table = receipt.operation().equals("PAYMENT") ? "payments" : "refunds";
    var merchants =
        jdbc.queryForList(
            "SELECT merchant_id FROM " + table + " WHERE id=? AND channel=?",
            String.class,
            receipt.aggregateId(),
            receipt.channel());
    if (merchants.isEmpty()) {
      throw new ApiException("UNKNOWN_CHANNEL_RECEIPT", 409, "渠道通知未匹配原业务");
    }
    transactions.executeWithoutResult(
        tx -> {
          String fingerprint = dev.merchantpay.platform.common.Signatures.sha256(body),
              id = UUID.randomUUID().toString();
          var now = Timestamp.from(clock.instant());
          try {
            jdbc.update(
                "INSERT INTO inbox_notifications(id,channel,fingerprint,aggregate_id,operation,raw_body,status,created_at,updated_at) VALUES (?,?,?,?,?,?,'RECEIVED',?,?)",
                id,
                receipt.channel(),
                fingerprint,
                receipt.aggregateId(),
                receipt.operation(),
                body,
                now,
                now);
          } catch (DuplicateKeyException duplicate) {
            return;
          }
          outbox.enqueue("inbox:" + id, "PROCESS_INBOX", id, merchants.getFirst());
        });
  }

  public void process(String id) {
    var rows = jdbc.queryForList("SELECT raw_body,status FROM inbox_notifications WHERE id=?", id);
    if (rows.isEmpty()
        || rows.getFirst().get("status").equals("PROCESSED")
        || rows.getFirst().get("status").equals("CONFLICT")) {
      return;
    }
    String raw = (String) rows.getFirst().get("raw_body");
    var receipt = json.readValue(raw, ChannelApi.Receipt.class);
    try {
      transactions.executeWithoutResult(
          tx -> {
            gateway.apply(receipt);
            jdbc.update(
                "UPDATE inbox_notifications SET status='PROCESSED',updated_at=? WHERE id=?",
                Timestamp.from(clock.instant()),
                id);
          });
    } catch (ApiException conflict) {
      if (!conflict.code().equals("CHANNEL_EVIDENCE_CONFLICT")) {
        throw conflict;
      }
      transactions.executeWithoutResult(
          tx -> {
            jdbc.update(
                "UPDATE inbox_notifications SET status='CONFLICT',error_detail=?,updated_at=? WHERE id=?",
                conflict.getMessage(),
                Timestamp.from(clock.instant()),
                id);
          });
    }
  }
}
