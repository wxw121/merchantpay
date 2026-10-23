import java.util.*;

class LoadMeasurementsTest {
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    Object empty = LocalLoad.percentile(List.of(), .99);
    check(empty == null, "Empty latency samples must be null, not a healthy zero");
    check(LocalLoad.onlineObserver("full") && !LocalLoad.onlineObserver("after"), "Calibration must support an explicit no-online-observer control");
    try { LocalLoad.onlineObserver("quiet"); throw new AssertionError("Unknown observer mode accepted"); }
    catch (IllegalArgumentException expected) { }
    check(LocalLoad.retryReason(503, "API_INFLIGHT").equals("API_INFLIGHT"), "Retain a fixed retry category");
    check(LocalLoad.retryReason(503, "secret-or-merchant").equals("UNCLASSIFIED"), "Never keep arbitrary response headers");
    check(LocalLoad.retryReason(503, null).equals("UNCLASSIFIED") && LocalLoad.retryReason(202, "API_INFLIGHT") == null, "Classify every 503 and no successful responses");
    check(LocalLoad.json(List.of(Map.of("counter", 1))).equals("[{\"counter\":1}]"), "Diagnostic rows must remain structured JSON arrays");
    var mixed = new LoadMeasurements(2, 10, 0);
    mixed.sent(0, 1); mixed.finished(0, 202, 8);
    mixed.sent(1, 11); mixed.finished(1, 503, 18);
    mixed.observed(List.of(new LoadMeasurements.Fact(1, "SUCCEEDED", true)), 20, 30);
    check(mixed.unfinishedAccepted() == 1, "An errored committed payment cannot cancel an unfinished 202");
    check(mixed.unacknowledgedCommitted() == 1, "HTTP errors must be reconciled by business key");
    check(mixed.acceptedP99UpperMicros() == null, "Unfinished accepted requests must censor P99");

    var intervals = new LoadMeasurements(1, 1, 0);
    intervals.sent(0, 15_000); intervals.finished(0, 202, 18_000);
    intervals.observed(List.of(), 20_000, 30_000);
    intervals.observed(List.of(new LoadMeasurements.Fact(0, "SUCCEEDED", true)), 40_000, 50_000);
    check(intervals.requests[0].completionLower == 20_000 && intervals.requests[0].completionUpper == 50_000,
        "Commit must be bounded by prior invisible snapshot and first visible query end");
    check(intervals.windowCountLower(35_000) == 0 && intervals.windowCountUpper(35_000) == 1,
        "A boundary-spanning observation must not claim exact window membership");
    check(intervals.acceptedP99UpperMicros() == 50, "Scheduled latency must include generator and async queue delay");
    intervals.observed(List.of(new LoadMeasurements.Fact(0, "SUCCEEDED", true)), 60_000, 70_000);
    check(intervals.requests[0].completionUpper == 50_000, "Repeated polls must not move first completion or duplicate counts");

    var emptyWindow = new LoadMeasurements(1, 1, 0);
    emptyWindow.sent(0, 1); emptyWindow.finished(0, -1, 15_000_001);
    check(emptyWindow.windowCountUpper(10_000_000) == 1, "Missing observer coverage cannot imply no commits");
    emptyWindow.observed(List.of(), 20_000_000, 21_000_000);
    check(emptyWindow.windowCountUpper(10_000_000) == 0, "A later invisible snapshot rules out an earlier commit");
    check(emptyWindow.httpMicros().equals(List.of(15000L)), "Timeouts must remain in HTTP latency samples");
    check(intervals.windowCountLower(60_000) == 1 && intervals.windowCountUpper(60_000) == 1,
        "Fully observed windows must converge");
    var failed = new LoadMeasurements(1, 1, 0);
    failed.sent(0, 1); failed.finished(0, 202, 2);
    failed.observed(List.of(new LoadMeasurements.Fact(0, "FAILED", false)), 3, 4);
    check(failed.unfinishedAccepted() == 0 && failed.failedAccepted() == 1, "Terminal business failure must be separate from unfinished work");
    check(LocalLoad.moneyInvariants(0, 0, 0, 0, 0), "A failed payment with no success journal must preserve money invariants");
    check(!LocalLoad.moneyInvariants(0, 0, 1, 0, 0), "A failed payment must not have an extra successful journal");
    check(!LocalLoad.moneyInvariants(0, 0, 0, 1, 0), "A successful payment must have its journal");
    check(LocalLoad.moneyInvariants(0, 0, 2, 1, 1), "Successful payment and refund require two journals");
    check(java.util.stream.IntStream.range(0, 30).filter(i -> LocalLoad.refundSelected(i, 20)).count() == 6,
        "Twenty percent refunds for 30 payments must be six, not the first twenty rows");
    check(java.util.stream.IntStream.range(0, 1000).filter(i -> LocalLoad.refundSelected(i, 20)).count() == 200,
        "Refund selection must preserve the requested ratio across complete and partial blocks");
    check(java.util.stream.IntStream.range(0, 30).noneMatch(i -> LocalLoad.refundSelected(i, 0))
        && java.util.stream.IntStream.range(0, 30).allMatch(i -> LocalLoad.refundSelected(i, 100)), "Zero and full refund selection boundaries");
    var observationFailure = new LoadMeasurements(1, 1, 0);
    observationFailure.sent(0, 1); observationFailure.finished(0, 202, 2);
    observationFailure.observed(List.of(), 10_000, 20_000);
    observationFailure.failedObservation(30_000, 1_030_000);
    check(observationFailure.previousSnapshotStart == 10_000, "Failed sampling must not advance the invisible snapshot boundary");
    check(observationFailure.snapshots == 1, "Failed attempts must not count as successful snapshots");
    check(observationFailure.snapshotNanos == 1_010_000 && observationFailure.maxSnapshotNanos == 1_000_000,
        "Connection and query failure elapsed time must remain in observer overhead");
    observationFailure.observed(List.of(new LoadMeasurements.Fact(0, "SUCCEEDED", true)), 1_040_000, 1_050_000);
    check(observationFailure.requests[0].completionLower == 10_000, "An observation gap must widen the completion interval");
    if (Arrays.asList(args).contains("--mysql")) mysqlVisibility();
    System.out.println("Load measurement tests passed");
  }

  static void mysqlVisibility() throws Exception {
    // Four private tables only; never write synthetic probe money to the application's tables.
    String prefix = "load_probe_" + UUID.randomUUID().toString().replace("-", "") + "_";
    String url = System.getenv().getOrDefault("DATABASE_URL", "jdbc:mysql://127.0.0.1:3307/merchantpay?connectionTimeZone=UTC");
    try (var writer = java.sql.DriverManager.getConnection(url, System.getenv().getOrDefault("DATABASE_USER", "merchantpay"), LocalLoad.required("DATABASE_PASSWORD"));
         var reader = java.sql.DriverManager.getConnection(url, System.getenv().getOrDefault("DATABASE_USER", "merchantpay"), LocalLoad.required("DATABASE_PASSWORD"))) {
      reader.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
      reader.setReadOnly(true);
      var ddl = writer.createStatement();
      try {
        ddl.execute("CREATE TABLE " + prefix + "payments(id VARCHAR(64) PRIMARY KEY,merchant_id VARCHAR(64),merchant_order_no VARCHAR(100),status VARCHAR(24)) ENGINE=InnoDB");
        ddl.execute("CREATE TABLE " + prefix + "ledger_journals(id VARCHAR(64) PRIMARY KEY,event_id VARCHAR(160),created_at TIMESTAMP(6)) ENGINE=InnoDB");
        ddl.execute("CREATE TABLE " + prefix + "ledger_entries(journal_id VARCHAR(64),side VARCHAR(8),amount_minor BIGINT) ENGINE=InnoDB");
        ddl.execute("CREATE TABLE " + prefix + "outbox_tasks(id VARCHAR(64),event_id VARCHAR(160)) ENGINE=InnoDB");
        String sql = LocalLoad.OBSERVATION_SQL.replaceAll("\\b(payments|ledger_journals|ledger_entries|outbox_tasks)\\b", prefix + "$1");
        writer.setAutoCommit(false);
        ddl.execute("INSERT INTO " + prefix + "payments VALUES('p','m','run-0','SUCCEEDED')");
        ddl.execute("INSERT INTO " + prefix + "ledger_journals VALUES('j','payment-success:p','2000-01-01 00:00:00')");
        ddl.execute("INSERT INTO " + prefix + "outbox_tasks VALUES('o','payment-success:p')");
        ddl.execute("INSERT INTO " + prefix + "ledger_entries VALUES('j','DEBIT',10),('j','CREDIT',10)");
        check(LocalLoad.readFacts(reader, sql, "m", "run").isEmpty(), "Uncommitted success and old created_at must be invisible");
        writer.commit();
        var facts = LocalLoad.readFacts(reader, sql, "m", "run");
        check(facts.size() == 1 && facts.getFirst().posted(), "A fresh autocommit read must see committed balanced success");
        ddl.execute("UPDATE " + prefix + "ledger_entries SET amount_minor=11 WHERE side='CREDIT'");
        writer.commit();
        check(!LocalLoad.readFacts(reader, sql, "m", "run").getFirst().posted(), "Unbalanced committed journals cannot count as complete");
        ddl.execute("INSERT INTO " + prefix + "payments VALUES('rollback','m','run-1','SUCCEEDED')");
        writer.rollback();
        check(LocalLoad.readFacts(reader, sql, "m", "run").size() == 1, "Rolled-back payment must never count");
        System.out.println("MySQL commit visibility, rollback, timestamp skew and journal balance passed");
      } finally {
        writer.rollback(); writer.setAutoCommit(true);
        for (String table : List.of("ledger_entries", "outbox_tasks", "ledger_journals", "payments"))
          ddl.execute("DROP TABLE IF EXISTS " + prefix + table);
        ddl.close();
      }
    }
  }
}
