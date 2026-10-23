package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.refund.RefundService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console")
public class ConsoleController {
  record MerchantInput(String id, String name, boolean enabled, String notificationUrl) {}

  private final Access access;
  private final Queries queries;
  private final PaymentService payments;
  private final RefundService refunds;
  private final MerchantService merchants;
  private final NotificationPolicy urls;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public ConsoleController(
      Access access,
      Queries queries,
      PaymentService payments,
      RefundService refunds,
      MerchantService merchants,
      NotificationPolicy urls,
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      Clock clock) {
    this.access = access;
    this.queries = queries;
    this.payments = payments;
    this.refunds = refunds;
    this.merchants = merchants;
    this.urls = urls;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.clock = clock;
  }

  @GetMapping("/session")
  Map<String, Object> session(CsrfToken token) {
    var caller = access.caller();
    return Map.of(
        "actor",
        caller.actor(),
        "merchantId",
        Objects.toString(caller.merchantId(), ""),
        "roles",
        caller.roles(),
        "csrfToken",
        token.getToken(),
        "csrfHeader",
        token.getHeaderName(),
        "loginUrl",
        "/oauth2/authorization/keycloak");
  }

  @GetMapping("/dashboard")
  Map<String, Object> dashboard(@RequestParam(required = false) String merchantId) {
    return queries.dashboard(access.merchant(merchantId, false));
  }

  @GetMapping("/{resource}")
  Queries.Page list(
      @PathVariable String resource,
      @RequestParam(required = false) String merchantId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String until,
      @RequestParam(required = false) String cursor) {
    return queries.list(
        resource,
        access.merchant(merchantId, false),
        status,
        from,
        until,
        cursor,
        access.caller().actor());
  }

  public Map<String, Object> detail(String id, String merchantId) {
    return detail(id, merchantId, Map.of());
  }

  @GetMapping("/payments/{id}")
  public Map<String, Object> detail(
      @PathVariable String id,
      @RequestParam(required = false) String merchantId,
      @RequestParam Map<String, String> cursors) {
    String merchant = queries.paymentMerchant(id, access.merchant(merchantId, false));
    var snapshot = new TransactionTemplate(transactions.getTransactionManager());
    snapshot.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
    return snapshot.execute(
        transaction -> {
          // Establish the repeatable-read snapshot before reading the related collections.
          var payment = payments.get(merchant, id);
          Map<String, Object> result = new LinkedHashMap<>();
          Map<String, String> next = new LinkedHashMap<>();
          result.put("payment", payment);
          for (String resource : List.of("refunds", "journals", "attempts", "tasks", "conflicts")) {
            var page =
                queries.paymentEvidence(id, merchant, resource, cursors.get(resource + "Cursor"));
            result.put(resource, page.items());
            next.put(resource, page.nextCursor());
          }
          @SuppressWarnings("unchecked")
          var journals = (List<Map<String, Object>>) result.get("journals");
          var journalIds = journals.stream().map(row -> row.get("id")).toList();
          var args = new ArrayList<Object>();
          args.add(id);
          args.addAll(journalIds);
          result.put(
              "entries",
              journalIds.isEmpty()
                  ? List.of()
                  : queries.rows(
                      "SELECT * FROM ledger_entries WHERE payment_id=? AND journal_id IN ("
                          + String.join(",", Collections.nCopies(journalIds.size(), "?"))
                          + ") ORDER BY journal_id,id",
                      args.toArray()));
          result.put("nextCursors", next);
          return result;
        });
  }

  @PostMapping("/payments")
  @ResponseStatus(HttpStatus.ACCEPTED)
  PaymentService.PaymentView create(
      @RequestParam(required = false) String merchantId,
      @RequestBody PaymentService.CreatePayment input) {
    access.require("ADMIN", "MERCHANT");
    String merchant = access.merchant(merchantId, true);
    urls.validate(input.notificationUrl());
    return transactions.execute(
        tx -> {
          var result = payments.create(merchant, input);
          merchants.audit(
              merchant,
              access.caller().actor(),
              "PAYMENT_CREATE",
              result.id(),
              input.merchantOrderNo());
          return result;
        });
  }

  @PostMapping("/payments/{id}/refunds")
  @ResponseStatus(HttpStatus.ACCEPTED)
  RefundService.RefundView refund(
      @PathVariable String id,
      @RequestParam(required = false) String merchantId,
      @RequestBody RefundService.CreateRefund input) {
    access.require("ADMIN", "MERCHANT");
    String merchant = queries.paymentMerchant(id, access.merchant(merchantId, false));
    return transactions.execute(
        tx -> {
          var result = refunds.create(merchant, id, input);
          merchants.audit(
              merchant,
              access.caller().actor(),
              "REFUND_CREATE",
              result.id(),
              input.merchantRefundNo());
          return result;
        });
  }

  @PostMapping("/payments/{id}/close")
  @ResponseStatus(HttpStatus.ACCEPTED)
  PaymentService.PaymentView close(
      @PathVariable String id, @RequestParam(required = false) String merchantId) {
    access.require("ADMIN", "MERCHANT");
    String merchant = queries.paymentMerchant(id, access.merchant(merchantId, false));
    return transactions.execute(
        tx -> {
          var result = payments.requestClose(merchant, id);
          merchants.audit(
              merchant, access.caller().actor(), "PAYMENT_CLOSE", id, "Close requested");
          return result;
        });
  }

  @GetMapping("/merchants")
  List<Map<String, Object>> merchants(@RequestParam(required = false) String q) {
    String merchant = access.merchant(null, false);
    if (merchant != null) {
      return queries.rows(
          "SELECT id,name,enabled,notification_url,created_at FROM merchants WHERE id=?", merchant);
    }
    return queries.rows(
        "SELECT id,name,enabled,notification_url,created_at FROM merchants WHERE name LIKE ? OR id LIKE ? ORDER BY CASE WHEN id=? THEN 0 WHEN name=? THEN 1 ELSE 2 END,id LIMIT 100",
        "%" + Objects.toString(q, "") + "%",
        "%" + Objects.toString(q, "") + "%",
        Objects.toString(q, ""),
        Objects.toString(q, ""));
  }

  @PostMapping("/payments/{id}/query")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void query(@PathVariable String id, @RequestParam(required = false) String merchantId) {
    access.require("ADMIN", "OPS", "FINANCE", "MERCHANT");
    String merchant = queries.paymentMerchant(id, access.merchant(merchantId, false));
    transactions.executeWithoutResult(
        tx -> {
          new OutboxService(jdbc, clock)
              .enqueue("manual-query:" + UUID.randomUUID(), "CHANNEL_PAYMENT", id, merchant);
          merchants.audit(
              merchant,
              access.caller().actor(),
              "PAYMENT_QUERY",
              id,
              "Same authoritative channel and stable request key");
        });
  }

  @GetMapping("/merchants/{id}")
  Map<String, Object> merchant(@PathVariable String id) {
    String merchant = access.merchant(id, true);
    var rows =
        queries.rows(
            "SELECT id,name,enabled,notification_url,created_at FROM merchants WHERE id=?",
            merchant);
    if (rows.isEmpty()) throw new ApiException("MERCHANT_NOT_FOUND", 404, "商户不存在");
    return rows.getFirst();
  }

  @PostMapping("/merchants")
  void configure(@RequestBody MerchantInput input) {
    access.require("ADMIN");
    merchants.configure(
        input.id(),
        input.name(),
        input.enabled(),
        input.notificationUrl(),
        access.caller().actor());
  }

  @GetMapping("/merchants/{id}/keys")
  List<Map<String, Object>> keys(@PathVariable String id) {
    access.require("ADMIN", "MERCHANT");
    String merchant = access.merchant(id, true);
    return queries.rows(
        "SELECT id,enabled,expires_at,created_at FROM merchant_keys WHERE merchant_id=? ORDER BY created_at DESC LIMIT 20",
        merchant);
  }

  @PostMapping("/merchants/{id}/keys")
  MerchantService.Key rotate(@PathVariable String id) {
    access.require("ADMIN", "MERCHANT");
    return merchants.rotate(access.merchant(id, true), access.caller().actor());
  }

  @PostMapping("/merchants/{id}/keys/{keyId}/revoke")
  void revoke(@PathVariable String id, @PathVariable String keyId) {
    access.require("ADMIN", "MERCHANT");
    String merchant = access.merchant(id, true);
    transactions.executeWithoutResult(
        tx -> {
          jdbc.update(
              "UPDATE merchant_keys SET enabled=FALSE WHERE id=? AND merchant_id=?",
              keyId,
              merchant);
          merchants.audit(merchant, access.caller().actor(), "KEY_REVOKE", keyId, "revoked");
        });
  }

  @PostMapping("/tasks/{id}/replay")
  void replay(@PathVariable String id, @RequestParam(required = false) String merchantId) {
    access.require("ADMIN", "OPS", "MERCHANT");
    String scope = access.merchant(merchantId, false);
    transactions.executeWithoutResult(
        tx -> {
          var rows =
              scope == null
                  ? queries.rows("SELECT * FROM outbox_tasks WHERE id=? FOR UPDATE", id)
                  : queries.rows(
                      "SELECT * FROM outbox_tasks WHERE id=? AND merchant_id=? FOR UPDATE",
                      id,
                      scope);
          if (rows.isEmpty()) {
            rows =
                scope == null
                    ? queries.rows("SELECT * FROM outbox_task_history WHERE id=? FOR UPDATE", id)
                    : queries.rows(
                        "SELECT * FROM outbox_task_history WHERE id=? AND merchant_id=? FOR UPDATE",
                        id,
                        scope);
            if (rows.isEmpty()) throw new ApiException("TASK_NOT_FOUND", 404, "任务不存在");
            jdbc.update(
                "INSERT INTO outbox_tasks SELECT * FROM outbox_task_history WHERE id=?", id);
            jdbc.update("DELETE FROM outbox_task_history WHERE id=?", id);
          }
          var task = rows.getFirst();
          if (access.caller().roles().contains("MERCHANT")
              && !Set.of(
                      "PAYMENT_SUCCEEDED",
                      "PAYMENT_FAILED",
                      "PAYMENT_CLOSED",
                      "REFUND_SUCCEEDED",
                      "REFUND_FAILED")
                  .contains(task.get("kind"))) {
            throw new ApiException("FORBIDDEN", 403, "商户仅可重发业务通知");
          }
          if (!Set.of("DEAD", "DONE").contains(task.get("status"))) {
            throw new ApiException("TASK_BUSY", 409, "仅已结束或死信任务可以人工重放");
          }
          jdbc.update(
              "UPDATE outbox_tasks SET status='PENDING',attempts=0,due_at=?,last_error=NULL,updated_at=? WHERE id=?",
              Timestamp.from(clock.instant()),
              Timestamp.from(clock.instant()),
              id);
          merchants.audit(
              (String) task.get("merchantId"),
              access.caller().actor(),
              "TASK_REPLAY",
              id,
              "Manual replay with original business identity");
        });
  }
}
