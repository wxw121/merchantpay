package dev.merchantpay.platform.web;

import dev.merchantpay.platform.channel.ChannelBillReader;
import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.OutboxService;
import dev.merchantpay.platform.query.Queries;
import dev.merchantpay.platform.reconciliation.*;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/console")
public class ReconciliationController {
  record PullBill(String merchantId, String channel, LocalDate day, int version) {}

  record ExportRequest(String merchantId, String resource, String from, String until) {}

  private final Access access;
  private final ReconciliationService reconciliation;
  private final ExportService exports;
  private final Queries queries;
  private final ChannelBillReader billReader;
  private final MerchantService merchants;
  private final OutboxService outbox;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public ReconciliationController(
      Access access,
      ReconciliationService reconciliation,
      ExportService exports,
      Queries queries,
      ChannelBillReader billReader,
      MerchantService merchants,
      OutboxService outbox,
      JdbcTemplate jdbc,
      TransactionTemplate tx) {
    this.access = access;
    this.reconciliation = reconciliation;
    this.exports = exports;
    this.queries = queries;
    this.billReader = billReader;
    this.merchants = merchants;
    this.outbox = outbox;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @PostMapping(value = "/reconciliation/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, String> upload(
      @RequestParam(required = false) String merchantId,
      @RequestParam String channel,
      @RequestParam LocalDate day,
      @RequestParam int version,
      @RequestParam String sha256,
      @RequestPart MultipartFile file)
      throws IOException {
    access.require("ADMIN", "FINANCE", "MERCHANT");
    String merchant = access.merchant(merchantId, true);
    String id;
    try (var input = file.getInputStream()) {
      id =
          reconciliation.submit(
              merchant,
              channel,
              day,
              version,
              sha256,
              input,
              batchId ->
                  merchants.audit(
                      merchant,
                      access.caller().actor(),
                      "BILL_UPLOAD",
                      batchId,
                      "SHA-256=" + sha256));
    }
    return Map.of("id", id);
  }

  @PostMapping("/reconciliation/pull")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, String> pull(@RequestBody PullBill input) throws IOException {
    access.require("ADMIN", "FINANCE", "MERCHANT");
    String merchant = access.merchant(input.merchantId(), true);
    if (input.day() == null) throw new ApiException("INVALID_BILL", 400, "账单日期必填");
    String id =
        billReader.read(
            input.channel(),
            merchant,
            input.day().toString(),
            stream ->
                reconciliation.submitFromChannel(
                    merchant,
                    input.channel(),
                    input.day(),
                    input.version(),
                    stream,
                    batchId ->
                        merchants.audit(
                            merchant,
                            access.caller().actor(),
                            "BILL_PULL",
                            batchId,
                            input.channel() + ":" + input.day())));
    return Map.of("id", id);
  }

  private Map<String, Object> batch(String id) {
    String merchant = access.merchant(null, false);
    var rows =
        merchant == null
            ? queries.rows("SELECT * FROM recon_batches WHERE id=?", id)
            : queries.rows(
                "SELECT * FROM recon_batches WHERE id=? AND merchant_id=?", id, merchant);
    if (rows.isEmpty()) {
      throw new ApiException("BILL_NOT_FOUND", 404, "账单不存在");
    }
    return rows.getFirst();
  }

  @GetMapping("/batches/{id}")
  Map<String, Object> detail(
      @PathVariable String id, @RequestParam(required = false) String cursor) {
    var batch = batch(id);
    var issues = List.<Map<String, Object>>of();
    String next = null;
    if (batch.get("status").equals("COMPLETE")) {
      String after =
          cursor == null
              ? ""
              : queries.decodeCursor("issues:" + id + ":" + batch.get("merchantId"), cursor);
      var rows =
          queries.rows(
              "SELECT * FROM recon_issues WHERE batch_id=? AND id>? ORDER BY id LIMIT 101",
              id,
              after);
      if (rows.size() > 100) {
        issues = new ArrayList<>(rows.subList(0, 100));
        next =
            queries.encodeCursor(
                "issues:" + id + ":" + batch.get("merchantId"),
                (String) issues.getLast().get("id"));
      } else {
        issues = rows;
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("batch", batch);
    result.put("issues", issues);
    result.put("nextCursor", next);
    return result;
  }

  @PostMapping("/batches/{id}/retry")
  void retry(@PathVariable String id) {
    access.require("ADMIN", "FINANCE", "OPS");
    var batch = batch(id);
    tx.executeWithoutResult(
        transaction -> {
          int updated =
              jdbc.update(
                  "UPDATE recon_batches SET status='QUEUED',error_detail=NULL WHERE id=? AND status='FAILED'",
                  id);
          if (updated != 1) {
            throw new ApiException("BILL_NOT_RETRYABLE", 409, "只有运行失败的账单可续跑；隔离文件请修正后递增版本重新上传");
          }
          outbox.enqueue(
              "reconcile-retry:" + UUID.randomUUID(),
              "RECONCILE",
              id,
              (String) batch.get("merchantId"));
          merchants.audit(
              (String) batch.get("merchantId"),
              access.caller().actor(),
              "BILL_RETRY",
              id,
              "Resume existing staged rows and immutable evidence");
        });
  }

  @GetMapping("/batches/{id}/original")
  ResponseEntity<InputStreamResource> original(@PathVariable String id) throws IOException {
    var batch = batch(id);
    String merchant = (String) batch.get("merchantId");
    var resource = reconciliation.downloadOriginal(id, merchant);
    try {
      merchants.audit(merchant, access.caller().actor(), "BILL_DOWNLOAD", id, "Original evidence");
    } catch (RuntimeException failure) {
      resource.getInputStream().close();
      throw failure;
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bill-" + id + ".csv\"")
        .body(resource);
  }

  @PostMapping("/exports")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, String> export(@RequestBody ExportRequest input) {
    String merchant = access.merchant(input.merchantId(), true);
    return tx.execute(
        transaction -> {
          String id =
              exports.create(
                  merchant, access.caller().actor(), input.resource(), input.from(), input.until());
          merchants.audit(merchant, access.caller().actor(), "EXPORT_CREATE", id, input.resource());
          return Map.of("id", id);
        });
  }

  @GetMapping("/exports/{id}/download")
  ResponseEntity<InputStreamResource> download(@PathVariable String id) throws IOException {
    String scope = access.merchant(null, false);
    var resource = exports.download(id, scope, access.caller().actor());
    try {
      String merchant =
          jdbc.queryForObject("SELECT merchant_id FROM exports WHERE id=?", String.class, id);
      merchants.audit(
          merchant, access.caller().actor(), "EXPORT_DOWNLOAD", id, "Authorized export download");
    } catch (RuntimeException failure) {
      resource.getInputStream().close();
      throw failure;
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export-" + id + ".zip\"")
        .body(resource);
  }
}
