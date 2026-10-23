import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** D0 generator: real signed HTTP requests and independent SQL checks, never fabricated success counts. */
class LocalLoad {
  static final String OBSERVATION_SQL = """
      SELECT p.merchant_order_no,p.status,
             (p.status='SUCCEEDED' AND j.id IS NOT NULL AND o.id IS NOT NULL
              AND (SELECT COUNT(*) FROM ledger_entries e WHERE e.journal_id=j.id)>=2
              AND (SELECT COALESCE(SUM(CASE WHEN e.side='DEBIT' THEN e.amount_minor ELSE -e.amount_minor END),0)
                   FROM ledger_entries e WHERE e.journal_id=j.id)=0) posted
      FROM payments p
      LEFT JOIN ledger_journals j ON j.event_id=CONCAT('payment-success:',p.id)
      LEFT JOIN outbox_tasks o ON o.event_id=CONCAT('payment-success:',p.id)
      WHERE p.merchant_id=? AND p.merchant_order_no LIKE ?
      """;
  static List<LoadMeasurements.Fact> readFacts(Connection connection, String sql, String merchant, String run) throws SQLException {
    var facts = new ArrayList<LoadMeasurements.Fact>();
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      statement.setString(1, merchant); statement.setString(2, run + "-%");
      try (var rows = statement.executeQuery()) {
        while (rows.next()) facts.add(new LoadMeasurements.Fact(
            Integer.parseInt(rows.getString(1).substring(run.length() + 1)), rows.getString(2), rows.getBoolean(3)));
      }
    }
    return facts;
  }
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final String key = required("LOAD_KEY_ID"), secret = required("LOAD_KEY_SECRET");
  final String merchant = System.getenv().getOrDefault("LOAD_MERCHANT", "merchant-a");
  final String base = System.getenv().getOrDefault("LOAD_BASE_URL", "http://127.0.0.1:8080");
  final String run = "load-" + Instant.now().getEpochSecond() + "-" + UUID.randomUUID().toString().substring(0, 8);
  final Semaphore capacity = new Semaphore(512);
  final Path output = Path.of(".work", "performance");
  final Map<Integer, Long> refundStatuses = new TreeMap<>();

  static String required(String name) {
    var value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable " + name);
    return value;
  }
  Connection database() throws SQLException {
    var properties = new Properties();
    properties.setProperty("user", System.getenv().getOrDefault("DATABASE_USER", "merchantpay"));
    properties.setProperty("password", required("DATABASE_PASSWORD"));
    properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "10000");
    return DriverManager.getConnection(System.getenv().getOrDefault("DATABASE_URL", "jdbc:mysql://127.0.0.1:3307/merchantpay?connectionTimeZone=UTC"), properties);
  }
  static String hash(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
  String sign(String value) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }
  record Response(int status, String reason) {}
  static boolean onlineObserver(String mode) {
    if (!Set.of("full", "after").contains(mode)) throw new IllegalArgumentException("Observer mode must be full or after");
    return mode.equals("full");
  }
  static String retryReason(int status, String reason) {
    if (status != 503) return null;
    return reason != null && Set.of("API_INFLIGHT", "QUOTA_CAPACITY", "QUOTA_TIMEOUT", "QUOTA_INTERRUPTED", "QUOTA_UNAVAILABLE", "QUOTA_DATABASE", "PAYMENT_CAPACITY", "DATABASE_UNAVAILABLE", "DATABASE_TRANSIENT").contains(reason) ? reason : "UNCLASSIFIED";
  }
  int send(String method, String path, String body) throws Exception { return sendResponse(method, path, body).status(); }
  Response sendResponse(String method, String path, String body) throws Exception {
    String timestamp = Long.toString(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString();
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
        .header("X-Key-Id", key).header("X-Timestamp", timestamp).header("X-Nonce", nonce)
        .header("X-Signature", sign(timestamp + "\n" + nonce + "\n" + method + "\n" + path + "\n" + hash(body)));
    if (method.equals("POST")) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    else builder.GET();
    var response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    return new Response(response.statusCode(), retryReason(response.statusCode(), response.headers().firstValue("X-Retry-Reason").orElse(null)));
  }
  List<String> queryIds() throws Exception {
    var ids = new ArrayList<String>();
    try (var connection = database(); var statement = connection.prepareStatement("SELECT id FROM payments WHERE merchant_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1000")) {
      statement.setString(1, merchant);
      try (var rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
    }
    if (ids.isEmpty()) throw new IllegalStateException("Run a payment scenario before the query scenario");
    return ids;
  }
  void phase(String scenario, LoadMeasurements measurements, List<String> ids) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < measurements.requests.length; index++) {
        long due = measurements.requests[index].due;
        long delay;
        while ((delay = due - System.nanoTime()) > 0) LockSupport.parkNanos(Math.min(delay, 20_000_000));
        if (!capacity.tryAcquire()) continue;
        int number = index;
        executor.submit(() -> {
          measurements.sent(number, System.nanoTime());
          int status = -1;
          try {
            Response response;
            if (scenario.equals("query")) response = sendResponse("GET", "/api/v1/payments/" + ids.get(number % ids.size()), "");
            else {
              long amount = number % 100 == 0 ? 1 : number % 100 == 1 ? 1_000_000_000L : 10_000;
              String body = "{\"merchantOrderNo\":\"" + run + "-" + number + "\",\"amountMinor\":\"" + amount + "\",\"currency\":\"CNY\",\"channel\":\"" + (number % 2 == 0 ? "MOCK_A" : "MOCK_B") + "\"}";
              response = sendResponse("POST", "/api/v1/payments", body);
            }
            status = response.status(); measurements.requests[number].retryReason = response.reason();
          } catch (Exception failure) { /* Retained as response=-1, with elapsed time. */ }
          finally { measurements.finished(number, status, System.nanoTime()); capacity.release(); }
        });
      }
    }
  }
  long scalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, merchant); statement.setString(2, run + "%");
      try (var row = statement.executeQuery()) { row.next(); return row.getLong(1); }
    }
  }
  int refunds(int percent, int rate) throws Exception {
    if (percent == 0) return 0;
    var rows = new ArrayList<String[]>();
    try (var connection = database(); var statement = connection.prepareStatement("SELECT id,amount_minor FROM payments WHERE merchant_id=? AND merchant_order_no LIKE ? AND status='SUCCEEDED' ORDER BY id")) {
      statement.setString(1, merchant); statement.setString(2, run + "%");
      try (var result = statement.executeQuery()) {
        int index = 0;
        while (result.next()) {
          long amount = result.getLong(2);
          if (refundSelected(index++, percent)) rows.add(new String[]{result.getString(1), Long.toString(Math.max(1, amount / 3))});
        }
      }
    }
    int index = 0; long started = System.nanoTime();
    for (var row : rows) {
      long due = started + (long) index++ * 1_000_000_000L / rate;
      long delay; while ((delay = due - System.nanoTime()) > 0) LockSupport.parkNanos(Math.min(delay, 20_000_000));
      String body = "{\"merchantRefundNo\":\"seed-refund-" + row[0] + "\",\"amountMinor\":\"" + row[1] + "\"}";
      int status;
      try { status = send("POST", "/api/v1/payments/" + row[0] + "/refunds", body); }
      catch (Exception failure) { status = -1; }
      refundStatuses.merge(status, 1L, Long::sum);
    }
    return rows.size();
  }
  static Long percentile(List<Long> values, double quantile) {
    if (values.isEmpty()) return null;
    return values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * quantile) - 1));
  }
  static boolean refundSelected(int index, int percent) {
    return (long)(index + 1) * percent / 100 > (long)index * percent / 100;
  }
  static boolean moneyInvariants(long balance, long unbalancedJournals, long journals, long successfulPayments, long refunds) {
    return balance == 0 && unbalancedJournals == 0 && journals == successfulPayments + refunds;
  }
  final class Observer implements AutoCloseable {
    final LoadMeasurements measurements;
    volatile boolean stopped;
    long errors;
    Thread thread;
    Observer(LoadMeasurements measurements) { this.measurements = measurements; }
    void poll() {
      long started = System.nanoTime();
      try {
        List<LoadMeasurements.Fact> facts;
        try (var connection = database()) {
          connection.setReadOnly(true);
          connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          connection.setAutoCommit(true);
          facts = readFacts(connection, OBSERVATION_SQL, merchant, run);
        }
        // Closing a failed connection must not turn one attempt into both success and failure.
        measurements.observed(facts, started, System.nanoTime());
      } catch (SQLException | RuntimeException failure) {
        errors++; measurements.failedObservation(started, System.nanoTime());
      }
    }
    void start() {
      thread = Thread.ofPlatform().name("committed-payment-observer").start(() -> {
        while (!stopped) {
          poll();
          try { Thread.sleep(250); } catch (InterruptedException interrupted) { return; }
        }
      });
    }
    public void close() throws InterruptedException {
      stopped = true;
      if (thread != null) { thread.join(15000); if (thread.isAlive()) throw new IllegalStateException("Observer did not stop"); }
    }
  }
  static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map<?, ?> map) return map.entrySet().stream().map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    if (value instanceof Collection<?> values) return values.stream().map(LocalLoad::json).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    String text = value.toString(); var result = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      if (c == '"' || c == '\\') result.append('\\').append(c);
      else if (c < 32) result.append(String.format("\\u%04x", (int)c));
      else result.append(c);
    }
    return result.append('"').toString();
  }
  void run(String scenario, int rate, int value, int refundPercent, String observerMode) throws Exception {
    boolean online = onlineObserver(observerMode);
    if (!Set.of("pay", "query", "dataset").contains(scenario) || rate < 1 || rate > 10_000 || value < 1 || value > 1_000_000 || refundPercent < 0 || refundPercent > 100) throw new IllegalArgumentException("Invalid load parameters");
    Files.createDirectories(output);
    int requests = scenario.equals("dataset") ? value : Math.multiplyExact(rate, value);
    var ids = scenario.equals("query") ? queryIds() : List.<String>of();
    boolean payment = !scenario.equals("query");
    System.out.println("D0 run=" + run + " scenario=" + scenario + " targetRequests=" + requests + " rate=" + rate);
    Instant phaseStarted = Instant.now(); long origin = System.nanoTime();
    long windowNanos = (long) requests * 1_000_000_000L / rate;
    var measurements = new LoadMeasurements(requests, rate, origin);
    var observer = new Observer(measurements);
    if (payment && online) observer.start();
    long phaseNanos; Instant phaseEnded;
    try { phase(scenario, measurements, ids); phaseNanos = System.nanoTime() - origin; phaseEnded = Instant.now(); }
    finally { observer.close(); }
    long onlineObserverNanos = measurements.snapshotNanos, onlineObserverSnapshots = measurements.snapshots;
    long errors = Arrays.stream(measurements.requests).filter(r -> r.sent && r.response == -1).count();
    long rejected = Arrays.stream(measurements.requests).filter(r -> r.response >= 300).count();
    long notSent = Arrays.stream(measurements.requests).filter(r -> !r.sent).count();
    long accepted = measurements.accepted();
    var statuses = new TreeMap<Integer, Long>();
    for (var request : measurements.requests) if (request.response > 0) statuses.merge(request.response, 1L, Long::sum);
    long balance = 0, uniquePayments = 0, successfulPayments = 0, journals = 0, events = 0, refundCount = 0, ledgerRows = 0, unbalancedJournals = 0;
    int requestedRefunds = 0;
    if (payment) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      // A failed response is not proof of rejection; allow late commits and disclose unresolved errors.
      long grace = System.nanoTime() + TimeUnit.SECONDS.toNanos(errors + rejected > 0 ? 5 : 0);
      do {
        observer.poll();
        boolean unresolved = Arrays.stream(measurements.requests).anyMatch(r ->
            (r.accepted() || r.persisted) && !r.complete && !Set.of("FAILED", "CLOSED").contains(Objects.toString(r.paymentStatus, "")));
        if (!unresolved && System.nanoTime() >= grace) break;
        Thread.sleep(250);
      } while (System.nanoTime() < deadline);
      if (scenario.equals("dataset")) requestedRefunds = refunds(refundPercent, rate);
      try (var connection = database()) {
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (scalar(connection, "SELECT COUNT(*) FROM refunds r JOIN payments p ON p.id=r.payment_id WHERE p.merchant_id=? AND p.merchant_order_no LIKE ? AND r.status IN ('PROCESSING','UNKNOWN')") > 0 && System.nanoTime() < deadline) Thread.sleep(250);
        observer.poll();
        connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
        uniquePayments = scalar(connection, "SELECT COUNT(*) FROM payments WHERE merchant_id=? AND merchant_order_no LIKE ?");
        successfulPayments = scalar(connection, "SELECT COUNT(*) FROM payments WHERE merchant_id=? AND merchant_order_no LIKE ? AND status='SUCCEEDED'");
        balance = scalar(connection, "SELECT COALESCE(SUM(CASE WHEN e.side='DEBIT' THEN e.amount_minor ELSE -e.amount_minor END),0) FROM ledger_entries e JOIN payments p ON p.id=e.payment_id WHERE p.merchant_id=? AND p.merchant_order_no LIKE ?");
        journals = scalar(connection, "SELECT COUNT(*) FROM ledger_journals j JOIN payments p ON p.id=j.payment_id WHERE p.merchant_id=? AND p.merchant_order_no LIKE ?");
        ledgerRows = scalar(connection, "SELECT COUNT(*) FROM ledger_entries e JOIN payments p ON p.id=e.payment_id WHERE p.merchant_id=? AND p.merchant_order_no LIKE ?");
        unbalancedJournals = scalar(connection, "SELECT COUNT(*) FROM (SELECT j.id FROM ledger_journals j JOIN payments p ON p.id=j.payment_id LEFT JOIN ledger_entries e ON e.journal_id=j.id WHERE p.merchant_id=? AND p.merchant_order_no LIKE ? GROUP BY j.id HAVING COUNT(e.id)<2 OR SUM(CASE WHEN e.side='DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)<>0) bad");
        refundCount = scalar(connection, "SELECT COUNT(*) FROM refunds r JOIN payments p ON p.id=r.payment_id WHERE p.merchant_id=? AND p.merchant_order_no LIKE ? AND r.status='SUCCEEDED'");
        events = scalar(connection, "SELECT COUNT(*) FROM outbox_tasks t JOIN payments p ON p.id=t.aggregate_id WHERE p.merchant_id=? AND p.merchant_order_no LIKE ?")
            + scalar(connection, "SELECT COUNT(*) FROM outbox_tasks t JOIN refunds r ON r.id=t.aggregate_id JOIN payments p ON p.id=r.payment_id WHERE p.merchant_id=? AND p.merchant_order_no LIKE ?");
      }
    }
    long finished = System.nanoTime();
    var times = measurements.httpMicros();
    boolean invariants = !payment || moneyInvariants(balance, unbalancedJournals, journals, successfulPayments, refundCount);
    boolean correct = invariants && rejected == 0 && errors == 0 && notSent == 0 && observer.errors == 0
        && (!payment || measurements.completed() == accepted && uniquePayments == accepted && refundCount == requestedRefunds
            && refundStatuses.entrySet().stream().noneMatch(e -> e.getKey() / 100 != 2));
    var report = new LinkedHashMap<String, Object>();
    report.put("measurementVersion", 2); report.put("runId", run); report.put("environment", "D0 shared developer host");
    report.put("generator",Map.of("pid",ProcessHandle.current().pid(),"availableProcessors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory()));
    report.put("scenario", scenario); report.put("generatedAt", Instant.now());
    report.put("observerMode", observerMode); report.put("onlineCompletionMeasured", payment && online);
    report.put("onlineObserverTotalQueryMicros", TimeUnit.NANOSECONDS.toMicros(onlineObserverNanos));
    report.put("onlineObserverSnapshots", onlineObserverSnapshots);
    report.put("retryReasons", Arrays.stream(measurements.requests).filter(r -> r.response == 503).collect(java.util.stream.Collectors.groupingBy(r -> Objects.toString(r.retryReason,"UNCLASSIFIED"), TreeMap::new, java.util.stream.Collectors.counting())));
    report.put("requestedRate", rate); report.put("requests", requests); report.put("acceptedOrRead", accepted);
    report.put("rejected", rejected); report.put("transportErrors", errors); report.put("notSentDueCapacity", notSent);
    report.put("phaseSeconds", phaseNanos / 1_000_000_000d); report.put("arrivalWindowSeconds", windowNanos / 1_000_000_000d);
    report.put("acceptedOrReadPerSecond", accepted / (phaseNanos / 1_000_000_000d));
    report.put("httpStatuses", statuses); report.put("httpLatencySamples", times.size());
    report.put("httpP95Micros", percentile(times, .95)); report.put("httpP99Micros", percentile(times, .99));
    report.put("scheduledHttpP99Micros", percentile(Arrays.stream(measurements.requests).filter(r -> r.sent).map(r -> TimeUnit.NANOSECONDS.toMicros(r.finished-r.due)).sorted().toList(), .99));
    report.put("sendDelayP99Micros", percentile(Arrays.stream(measurements.requests).filter(r -> r.sent).map(r -> TimeUnit.NANOSECONDS.toMicros(r.started-r.due)).sorted().toList(), .99));
    report.put("postedPayments", payment ? measurements.completed() : null); report.put("uniquePayments", payment ? uniquePayments : null);
    report.put("successfulPayments", payment ? successfulPayments : null);
    report.put("unfinishedAcceptedPayments", payment ? measurements.unfinishedAccepted() : null);
    report.put("failedAcceptedPayments", payment ? measurements.failedAccepted() : null);
    report.put("oldestUnfinishedAcceptedMicros", payment ? measurements.oldestUnfinishedMicros(finished) : null);
    report.put("unacknowledgedCommittedPayments", payment ? measurements.unacknowledgedCommitted() : null);
    report.put("failedResponseWithoutVisiblePayment", payment ? Arrays.stream(measurements.requests).filter(r -> r.sent && !r.accepted() && !r.persisted).count() : null);
    report.put("paymentStatuses", Arrays.stream(measurements.requests).filter(r -> r.persisted).collect(java.util.stream.Collectors.groupingBy(r -> r.paymentStatus, TreeMap::new, java.util.stream.Collectors.counting())));
    report.put("acceptedCompletionP99UpperMicros", payment ? measurements.acceptedP99UpperMicros() : null);
    report.put("successfulCompletionP99LowerMicros", percentile(Arrays.stream(measurements.requests).filter(r -> r.complete).map(r -> TimeUnit.NANOSECONDS.toMicros(r.completionLower-r.due)).sorted().toList(), .99));
    report.put("successfulCompletionP99UpperMicros", percentile(Arrays.stream(measurements.requests).filter(r -> r.complete).map(r -> TimeUnit.NANOSECONDS.toMicros(r.completionUpper-r.due)).sorted().toList(), .99));
    report.put("completedInArrivalWindowLower", payment ? measurements.windowCountLower(origin+windowNanos) : null);
    report.put("completedInArrivalWindowUpper", payment ? measurements.windowCountUpper(origin+windowNanos) : null);
    report.put("completedPerArrivalSecondLower", payment ? measurements.windowCountLower(origin+windowNanos)/(windowNanos/1_000_000_000d) : null);
    report.put("completedPerArrivalSecondUpper", payment ? measurements.windowCountUpper(origin+windowNanos)/(windowNanos/1_000_000_000d) : null);
    report.put("completionTimeSource", "independent committed SQL visibility; monotonic-clock interval, not created_at");
    report.put("observerPollMillis", 250); report.put("observerSnapshots", measurements.snapshots); report.put("observerErrors", observer.errors);
    report.put("observerAttempts", measurements.snapshots + observer.errors);
    report.put("observerTotalQueryMicros", TimeUnit.NANOSECONDS.toMicros(measurements.snapshotNanos));
    report.put("observerMaxQueryMicros", TimeUnit.NANOSECONDS.toMicros(measurements.maxSnapshotNanos));
    report.put("maxCommitVisibilityIntervalMicros", TimeUnit.NANOSECONDS.toMicros(measurements.maxVisibilityIntervalNanos));
    report.put("requestedRefunds", requestedRefunds); report.put("refundHttpStatuses", refundStatuses); report.put("refundedPayments", refundCount);
    report.put("journals", payment ? journals : null); report.put("ledgerRows", payment ? ledgerRows : null); report.put("outboxBusinessTasks", payment ? events : null);
    report.put("ledgerDifferenceMinor", payment ? Long.toString(balance) : null); report.put("unbalancedJournals", payment ? unbalancedJournals : null);
    report.put("moneyInvariantsPassed", payment ? invariants : null); report.put("correctnessPassed", correct); report.put("referenceR1Certified", false);
    report.put("primaryPhaseStarted", phaseStarted); report.put("primaryPhaseEnded", phaseEnded);
    report.put("arrivalWindowEnded", phaseStarted.plusNanos(windowNanos));
    if (payment && !online) {
      // An offline control proves final correctness, never completion TPS or end-to-end latency.
      for (var field : List.of("acceptedCompletionP99UpperMicros", "successfulCompletionP99LowerMicros", "successfulCompletionP99UpperMicros", "completedInArrivalWindowLower", "completedInArrivalWindowUpper", "completedPerArrivalSecondLower", "completedPerArrivalSecondUpper", "maxCommitVisibilityIntervalMicros", "observerPollMillis")) report.put(field, null);
      report.put("completionTimeSource", "post-phase committed SQL verification only; no online completion timing");
    }
    try (var writer = Files.newBufferedWriter(output.resolve(run+".requests.jsonl"))) {
      for (int i=0; i<requests; i++) {
        var r=measurements.requests[i]; var row=new LinkedHashMap<String,Object>();
        row.put("index",i); row.put("businessNo",payment ? run+"-"+i : null); row.put("sent",r.sent); row.put("httpStatus",r.response);
        row.put("retryReason",r.retryReason);
        row.put("scheduledOffsetMicros",TimeUnit.NANOSECONDS.toMicros(r.due-origin));
        row.put("sentOffsetMicros",r.sent ? TimeUnit.NANOSECONDS.toMicros(r.started-origin) : null);
        row.put("httpFinishedOffsetMicros",r.sent ? TimeUnit.NANOSECONDS.toMicros(r.finished-origin) : null);
        row.put("persisted",r.persisted); row.put("paymentStatus",r.paymentStatus); row.put("posted",r.complete);
        row.put("commitLowerOffsetMicros",r.complete && online ? TimeUnit.NANOSECONDS.toMicros(r.completionLower-origin) : null);
        row.put("commitUpperOffsetMicros",r.complete && online ? TimeUnit.NANOSECONDS.toMicros(r.completionUpper-origin) : null);
        writer.write(json(row)); writer.newLine();
      }
    }
    Path reportPath = output.resolve(run+".json"); Files.writeString(reportPath,json(report)); System.out.println(json(report));
    if (!correct) throw new IllegalStateException("Load has rejected, unfinished, unobserved or incorrect results; see " + reportPath);
  }
  public static void main(String[] args) throws Exception {
    if (args.length < 3) throw new IllegalArgumentException("LocalLoad pay|query RATE SECONDS | dataset RATE PAYMENT_COUNT [REFUND_PERCENT]");
    new LocalLoad().run(args[0],Integer.parseInt(args[1]),Integer.parseInt(args[2]),args.length > 3 ? Integer.parseInt(args[3]) : 0,args.length > 4 ? args[4] : "full");
  }
}
