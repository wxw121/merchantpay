import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** V1 protocol driver. JDBC is read-only; every generated financial fact uses the signed API. */
class AcceptanceLoad {
  record Credential(String merchant, String key, String secret) {}
  record Payment(String id, Credential owner, long available) {}
  record Reply(int status, String body, String reason) {
    Reply(int status,String body){this(status,body,null);}
  }
  static Map<String,Credential> credentials(String text) {
    var result = new LinkedHashMap<String,Credential>();
    for (String line : text.split("\\R")) {
      if (line.isBlank()) continue;
      String[] values = line.split("\\t",-1);
      if(values.length!=3 || Arrays.stream(values).anyMatch(String::isBlank)
          || !values[0].matches("[A-Za-z0-9-]{1,64}") || result.containsKey(values[0]))
        throw new IllegalArgumentException("Credentials require unique merchant/key/secret TSV rows");
      result.put(values[0],new Credential(values[0],values[1],values[2]));
    }
    if(result.isEmpty()) throw new IllegalArgumentException("No credentials");
    return result;
  }
  static boolean d1(long payments,long entries,long events,long merchants,long days) {
    return payments>=1_000_000 && entries>=5_000_000 && events>=10_000_000 && merchants>=1000 && days>=30;
  }
  static int queryIndex(Random random,int count) { return random.nextInt(100)<20 ? 0 : random.nextInt(count); }
  static int soakPercent(int second) { return second%3600>=3300 ? 100 : 20; }
  static boolean terminal(String status) { return Set.of("SUCCEEDED","FAILED","CLOSED").contains(status); }
  static boolean factsMatch(String[] authority,String[] platform) { return Arrays.equals(authority,platform); }
  static boolean deliveryConverged(Map<?,?> evidence) {
    for(String counter:List.of("unfinishedTasks","unfinishedNotifications","undeliveredNotifications"))
      if(!(evidence.get(counter) instanceof Number n)||n.longValue()!=0)return false;
    return true;
  }
  static boolean batchesAccepted(Map<?,?> tasks,boolean scaled) {
    for(String kind:List.of("reconciliation","exports")) {
      if(!(tasks.get(kind) instanceof List<?> values)||values.isEmpty())return false;
      for(Object value:values) {
        if(!(value instanceof Map<?,?> row)||!"COMPLETE".equals(row.get("status"))||!(row.get("rows") instanceof Number n)||n.longValue()<(scaled?1:100000))return false;
      }
    }
    return true;
  }
  static boolean responsesAccepted(LoadMeasurements samples,boolean financial) {
    if(Arrays.stream(samples.requests).anyMatch(r->!r.sent||r.response==0||(r.response>=300&&r.response<500)))return false;
    long errors=Arrays.stream(samples.requests).filter(r->r.response<0||r.response>=500).count();
    return financial?errors==0:errors<=samples.requests.length*.001;
  }
  final Map<String,Credential> owners = credentials(LocalLoad.required("LOAD_CREDENTIALS"));
  final List<Credential> orderedOwners = List.copyOf(owners.values());
  final String base=System.getenv().getOrDefault("LOAD_BASE_URL","http://127.0.0.1:8080");
  final String simulator=System.getenv().getOrDefault("LOAD_SIMULATOR_URL","http://127.0.0.1:9091");
  final String notification=System.getenv("LOAD_NOTIFICATION_URL");
  final long seed=Long.parseLong(System.getenv().getOrDefault("LOAD_SEED","20261019"));
  final Path output=Path.of(System.getenv().getOrDefault("LOAD_OUTPUT",".work/acceptance"));
  final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final Semaphore capacity=new Semaphore(2048);
  final AtomicLong observerErrors=new AtomicLong();
  static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
  Connection database() throws Exception {
    var properties=new Properties();
    properties.setProperty("user",System.getenv().getOrDefault("DATABASE_USER","merchantpay"));
    properties.setProperty("password",LocalLoad.required("DATABASE_PASSWORD"));
    properties.setProperty("connectTimeout","3000"); properties.setProperty("socketTimeout","60000");
    var connection=DriverManager.getConnection(System.getenv().getOrDefault("DATABASE_URL","jdbc:mysql://127.0.0.1:3307/merchantpay?connectionTimeZone=UTC"),properties);
    try(var statement=connection.createStatement()){statement.execute("SET SESSION time_zone='+00:00'");}
    connection.setReadOnly(true); connection.setAutoCommit(true); connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    return connection;
  }
  Reply request(Credential credential,String method,String path,String body) throws Exception {
    String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
    var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(credential.secret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    String signature=HexFormat.of().formatHex(mac.doFinal((timestamp+"\n"+nonce+"\n"+method+"\n"+path+"\n"+LocalLoad.hash(body)).getBytes(StandardCharsets.UTF_8)));
    var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15))
        .header("X-Key-Id",credential.key()).header("X-Timestamp",timestamp).header("X-Nonce",nonce).header("X-Signature",signature);
    if(method.equals("POST")) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)); else builder.GET();
    var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(),response.body(),LocalLoad.retryReason(response.statusCode(),response.headers().firstValue("X-Retry-Reason").orElse(null)));
  }
  Reply console(String method,String path,String body) throws Exception {
    var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30))
        .header("Cookie",LocalLoad.required("LOAD_CONSOLE_COOKIE"));
    if(method.equals("POST")) builder.header("X-XSRF-TOKEN",LocalLoad.required("LOAD_CSRF_TOKEN")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    else builder.GET();
    var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(),response.body());
  }
  Reply channel(String path) throws Exception {
    var response=http.send(HttpRequest.newBuilder(URI.create(simulator+path)).timeout(Duration.ofSeconds(30))
        .header("X-Channel-Token",LocalLoad.required("CHANNEL_TOKEN")).GET().build(),HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(),response.body());
  }
  List<Payment> pool() throws Exception {
    var result=new ArrayList<Payment>();
    try(var connection=database();var statement=connection.createStatement()) {
      statement.setFetchSize(Integer.MIN_VALUE);
      try(var rows=statement.executeQuery("SELECT id,merchant_id,amount_minor-refunded_minor-reserved_minor FROM payments ORDER BY merchant_id,id")) {
        while(rows.next()) {
          var owner=owners.get(rows.getString(2));
          if(owner!=null) result.add(new Payment(rows.getString(1),owner,rows.getLong(3)));
        }
      }
    }
    if(result.isEmpty()) throw new IllegalStateException("No online payments in credential scope");
    return result;
  }
  List<Payment> refundPool(int count) throws Exception {
    var result=new ArrayList<Payment>();
    try(var connection=database();var statement=connection.createStatement()) {
      statement.setFetchSize(Integer.MIN_VALUE);
      try(var rows=statement.executeQuery("SELECT id,merchant_id,amount_minor-refunded_minor-reserved_minor FROM payments WHERE status='SUCCEEDED' AND amount_minor-refunded_minor-reserved_minor>0 ORDER BY merchant_id,id")) {
        while(rows.next() && result.size()<count) {
          var owner=owners.get(rows.getString(2));
          if(owner!=null) result.add(new Payment(rows.getString(1),owner,rows.getLong(3)));
        }
      }
    }
    if(result.size()<count) throw new IllegalStateException("Insufficient distinct successful refundable payments: need "+count+", found "+result.size());
    return result;
  }
  static LoadMeasurements schedule(int seconds,int rate,long origin,boolean soak) {
    long units=0;
    for(int second=0;second<seconds;second++) units=Math.addExact(units,(long)rate*(soak?soakPercent(second):100));
    int count=Math.toIntExact((units+99)/100);
    var measurements=new LoadMeasurements(count,Math.max(1,rate),origin);
    int index=0;long previous=0;
    for(int second=0;second<seconds;second++) {
      long current=(long)rate*(soak?soakPercent(second):100);
      while(index*100L<previous+current) {
        measurements.requests[index]=new LoadMeasurements.Request(origin+second*1_000_000_000L+(index*100L-previous)*1_000_000_000L/current);index++;
      }
      previous+=current;
    }
    return measurements;
  }
  static void until(long due) { long delay; while((delay=due-System.nanoTime())>0) LockSupport.parkNanos(Math.min(delay,20_000_000)); }
  void traffic(String kind,String run,LoadMeasurements measurements,List<Payment> source) throws Exception {
    var random=new Random(seed);
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      for(int i=0;i<measurements.requests.length;i++) {
        until(measurements.requests[i].due);
        if(!capacity.tryAcquire()) continue;
        int index=i;
        Payment selected=kind.equals("query")?source.get(queryIndex(random,source.size())):kind.equals("refund")?source.get(i):null;
        executor.submit(()->{
          measurements.sent(index,System.nanoTime()); int status=-1;
          try {
            Credential owner=selected==null?orderedOwners.get(index%orderedOwners.size()):selected.owner();
            Reply response;
            if(kind.equals("query")) response=request(owner,"GET","/api/v1/payments/"+selected.id(),"");
            else if(kind.equals("refund")) response=request(owner,"POST","/api/v1/payments/"+selected.id()+"/refunds",LocalLoad.json(Map.of("merchantRefundNo",run+"-"+index,"amountMinor","1")));
            else response=request(owner,"POST","/api/v1/payments",LocalLoad.json(Map.of("merchantOrderNo",run+"-"+index,"amountMinor","10000","currency","CNY","channel",index%2==0?"MOCK_A":"MOCK_B","notificationUrl",notification)));
            status=response.status();
            measurements.requests[index].retryReason=response.reason();
          } catch(Exception failure) { /* Preserved as transport error and unresolved business key. */ }
          finally { measurements.finished(index,status,System.nanoTime());capacity.release(); }
        });
      }
    }
  }
  static final String POSTED="j.id IS NOT NULL AND o.id IS NOT NULL AND (SELECT COUNT(*) FROM ledger_entries e WHERE e.journal_id=j.id)>=2 AND (SELECT COALESCE(SUM(CASE WHEN e.side='DEBIT' THEN e.amount_minor ELSE -e.amount_minor END),0) FROM ledger_entries e WHERE e.journal_id=j.id)=0";
  void observe(String kind,String run,LoadMeasurements measurements,List<Payment> refunds) {
    long started=System.nanoTime();var facts=new ArrayList<LoadMeasurements.Fact>();
    try(var connection=database()) {
      var indices=new ArrayList<Integer>();
      for(int i=0;i<measurements.requests.length;i++) {
        var r=measurements.requests[i]; if(r.sent&&!r.complete&&!LoadMeasurements.failed(r)) indices.add(i);
      }
      for(int offset=0;offset<indices.size();offset+=500) {
        var batch=indices.subList(offset,Math.min(indices.size(),offset+500));
        String root=kind.equals("refund")?"refunds":"payments",business=kind.equals("refund")?"merchant_refund_no":"merchant_order_no",event=kind.equals("refund")?"refund-success:":"payment-success:";
        String sql="SELECT p.merchant_id,p."+business+",p.status,(p.status='SUCCEEDED' AND "+POSTED+") FROM "+root+" p LEFT JOIN ledger_journals j ON j.event_id=CONCAT('"+event+"',p.id) LEFT JOIN outbox_tasks o ON o.event_id=CONCAT('"+event+"',p.id) WHERE (p.merchant_id,p."+business+") IN ("+String.join(",",Collections.nCopies(batch.size(),"(?,?)"))+")";
        try(var statement=connection.prepareStatement(LocalLoad.taskFactsSql(connection,sql))) {
          statement.setQueryTimeout(30); int parameter=1;
          for(int index:batch) { statement.setString(parameter++,kind.equals("refund")?refunds.get(index).owner().merchant():orderedOwners.get(index%orderedOwners.size()).merchant()); statement.setString(parameter++,run+"-"+index); }
          try(var rows=statement.executeQuery()) {
            while(rows.next()) {
              int index=Integer.parseInt(rows.getString(2).substring(run.length()+1));
              String owner=kind.equals("refund")?refunds.get(index).owner().merchant():orderedOwners.get(index%orderedOwners.size()).merchant();
              if(!owner.equals(rows.getString(1))) throw new IllegalStateException("Observed cross-tenant payment");
              facts.add(new LoadMeasurements.Fact(index,rows.getString(3),rows.getBoolean(4)));
            }
          }
        }
      }
      synchronized(measurements) { measurements.observed(facts,started,System.nanoTime()); }
    } catch(Exception failure) { observerErrors.incrementAndGet(); synchronized(measurements){measurements.failedObservation(started,System.nanoTime());} }
  }
  static Map<String,Object> stats(LoadMeasurements m,long end,int seconds,boolean financial) {
    var result=new LinkedHashMap<String,Object>();
    result.put("scheduled",m.requests.length);result.put("sent",Arrays.stream(m.requests).filter(r->r.sent).count());result.put("acceptedOrRead",m.accepted());
    result.put("httpStatuses",Arrays.stream(m.requests).filter(r->r.sent).collect(java.util.stream.Collectors.groupingBy(r->r.response,TreeMap::new,java.util.stream.Collectors.counting())));
    result.put("droppedIterations",Arrays.stream(m.requests).filter(r->!r.sent).count());
    result.put("systemErrors",Arrays.stream(m.requests).filter(r->r.sent&&(r.response<0||r.response>=500)).count());
    result.put("rejected",Arrays.stream(m.requests).filter(r->r.response>=300&&r.response<500).count());
    result.put("httpP50Micros",LocalLoad.percentile(m.httpMicros(),.50));result.put("httpP95Micros",LocalLoad.percentile(m.httpMicros(),.95));result.put("httpP99Micros",LocalLoad.percentile(m.httpMicros(),.99));
    result.put("scheduledHttpP99Micros",LocalLoad.percentile(Arrays.stream(m.requests).filter(r->r.sent).map(r->TimeUnit.NANOSECONDS.toMicros(r.finished-r.due)).sorted().toList(),.99));
    result.put("sendDelayP99Micros",LocalLoad.percentile(Arrays.stream(m.requests).filter(r->r.sent).map(r->TimeUnit.NANOSECONDS.toMicros(r.started-r.due)).sorted().toList(),.99));
    if(!financial)result.put("successfulPerArrivalSecond",Arrays.stream(m.requests).filter(r->r.sent&&r.response/100==2&&r.finished<end).count()/(double)seconds);
    if(financial) {
      result.put("posted",m.completed());result.put("businessFailed",m.failedAccepted());result.put("unfinished",m.unfinishedAccepted());
      result.put("unacknowledgedCommitted",m.unacknowledgedCommitted());result.put("oldestUnfinishedMicros",m.oldestUnfinishedMicros(System.nanoTime()));
      result.put("completedPerArrivalSecondLower",m.windowCountLower(end)/(double)seconds);result.put("completedPerArrivalSecondUpper",m.windowCountUpper(end)/(double)seconds);
      result.put("successfulCompletionP99LowerMicros",LocalLoad.percentile(Arrays.stream(m.requests).filter(r->r.complete).map(r->TimeUnit.NANOSECONDS.toMicros(r.completionLower-r.due)).sorted().toList(),.99));
      result.put("successfulCompletionP99UpperMicros",LocalLoad.percentile(Arrays.stream(m.requests).filter(r->r.complete).map(r->TimeUnit.NANOSECONDS.toMicros(r.completionUpper-r.due)).sorted().toList(),.99));
      result.put("acceptedCompletionP99UpperMicros",m.acceptedP99UpperMicros());
      result.put("observerTotalQueryMicros",TimeUnit.NANOSECONDS.toMicros(m.snapshotNanos));result.put("observerSnapshots",m.snapshots);result.put("observerMaxQueryMicros",TimeUnit.NANOSECONDS.toMicros(m.maxSnapshotNanos));result.put("maxCommitVisibilityIntervalMicros",TimeUnit.NANOSECONDS.toMicros(m.maxVisibilityIntervalNanos));
    }
    result.put("retryReasons",Arrays.stream(m.requests).filter(r->r.response==503).collect(java.util.stream.Collectors.groupingBy(r->Objects.toString(r.retryReason,"UNCLASSIFIED"),TreeMap::new,java.util.stream.Collectors.counting())));
    return result;
  }
  long scalar(Connection connection,String sql) throws Exception {try(var s=connection.createStatement();var r=s.executeQuery(LocalLoad.taskFactsSql(connection,sql))){r.next();return r.getLong(1);}}
  Map<String,Object> manifest() throws Exception {
    try(var connection=database()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
      var result=new LinkedHashMap<String,Object>();
      long payments=scalar(connection,"SELECT COUNT(*) FROM payments"),entries=scalar(connection,"SELECT COUNT(*) FROM ledger_entries"),events=scalar(connection,"SELECT COUNT(*) FROM outbox_tasks"),merchants=scalar(connection,"SELECT COUNT(DISTINCT merchant_id) FROM payments"),days=scalar(connection,"SELECT COUNT(DISTINCT DATE(created_at)) FROM payments");
      long bad=scalar(connection,"SELECT COUNT(*) FROM (SELECT j.id FROM ledger_journals j LEFT JOIN ledger_entries e ON e.journal_id=j.id GROUP BY j.id HAVING COUNT(e.id)<2 OR SUM(CASE WHEN e.side='DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)<>0) invalid");
      long money=scalar(connection,"SELECT COUNT(*) FROM payments WHERE refunded_minor<0 OR reserved_minor<0 OR refunded_minor+reserved_minor>amount_minor OR refunded_fee_minor>fee_minor");
      long missing=scalar(connection,"SELECT COUNT(*) FROM payments p LEFT JOIN ledger_journals j ON j.event_id=CONCAT('payment-success:',p.id) LEFT JOIN outbox_tasks o ON o.event_id=CONCAT('payment-success:',p.id) WHERE p.status='SUCCEEDED' AND (j.id IS NULL OR o.id IS NULL)")
          +scalar(connection,"SELECT COUNT(*) FROM refunds p LEFT JOIN ledger_journals j ON j.event_id=CONCAT('refund-success:',p.id) LEFT JOIN outbox_tasks o ON o.event_id=CONCAT('refund-success:',p.id) WHERE p.status='SUCCEEDED' AND (j.id IS NULL OR o.id IS NULL)");
      long extraJournals=scalar(connection,"SELECT COUNT(*) FROM ledger_journals j LEFT JOIN payments p ON j.source_type='PAYMENT' AND j.source_id=p.id AND p.status='SUCCEEDED' AND j.event_id=CONCAT('payment-success:',p.id) AND j.merchant_id=p.merchant_id AND j.payment_id=p.id LEFT JOIN refunds r ON j.source_type='REFUND' AND j.source_id=r.id AND r.status='SUCCEEDED' AND j.event_id=CONCAT('refund-success:',r.id) AND j.merchant_id=r.merchant_id AND j.payment_id=r.payment_id WHERE p.id IS NULL AND r.id IS NULL");
      long refundTotals=scalar(connection,"SELECT COUNT(*) FROM payments p WHERE p.refunded_minor<>(SELECT COALESCE(SUM(r.amount_minor),0) FROM refunds r WHERE r.payment_id=p.id AND r.status='SUCCEEDED') OR p.reserved_minor<>(SELECT COALESCE(SUM(r.amount_minor),0) FROM refunds r WHERE r.payment_id=p.id AND r.status IN ('PROCESSING','UNKNOWN'))");
      result.put("generatedAt",Instant.now());result.put("scope","whole authoritative platform DB; counts are actual tables, not synthetic fixture targets");result.put("seed",seed);
      result.put("payments",payments);result.put("ledgerEntries",entries);result.put("outboxEvents",events);result.put("merchantsWithPayments",merchants);result.put("distinctBusinessDatesUtc",days);
      result.put("credentialMerchants",owners.size());result.put("unbalancedJournals",bad);result.put("refundConstraintViolations",money);result.put("missingSuccessfulFacts",missing);result.put("extraOrMisattributedJournals",extraJournals);result.put("refundAggregateDisagreements",refundTotals);
      result.put("moneyInvariantsPassed",bad+money+missing+extraJournals+refundTotals==0);result.put("d1CountersSatisfied",d1(payments,entries,events,merchants,days));result.put("referenceR1Certified",false);
      result.put("databaseBytes",scalar(connection,"SELECT COALESCE(SUM(data_length+index_length),0) FROM information_schema.tables WHERE table_schema=DATABASE()"));
      result.put("maxPaymentIdWatermark",textScalar(connection,"SELECT COALESCE(MAX(id),'') FROM payments"));
      result.put("paymentDateDistribution",dateDistribution(connection));
      result.putAll(deliveryEvidence(connection));
      return result;
    }
  }
  Map<String,Object> deliveryEvidence(Connection c)throws Exception {
    var evidence=new LinkedHashMap<String,Object>();
    String notifications="'PAYMENT_SUCCEEDED','PAYMENT_FAILED','PAYMENT_CLOSED','REFUND_SUCCEEDED','REFUND_FAILED'";
    evidence.put("unfinishedTasks",scalar(c,"SELECT COUNT(*) FROM outbox_tasks WHERE status<>'DONE'"));
    evidence.put("unfinishedNotifications",scalar(c,"SELECT COUNT(*) FROM outbox_tasks WHERE kind IN ("+notifications+") AND status<>'DONE'"));
    evidence.put("undeliveredNotifications",scalar(c,"SELECT COUNT(*) FROM outbox_tasks o LEFT JOIN refunds r ON o.kind LIKE 'REFUND_%' AND r.id=o.aggregate_id JOIN payments p ON p.id=CASE WHEN o.kind LIKE 'REFUND_%' THEN r.payment_id ELSE o.aggregate_id END WHERE o.kind IN ("+notifications+") AND p.notification_url IS NOT NULL AND p.notification_url<>'' AND NOT EXISTS (SELECT 1 FROM notification_deliveries d WHERE d.task_id=o.id AND d.status='DELIVERED' AND d.http_status BETWEEN 200 AND 299)"));
    evidence.put("deliveryConverged",deliveryConverged(evidence));return evidence;
  }
  String textScalar(Connection c,String sql)throws Exception{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
  Map<String,Long> dateDistribution(Connection c)throws Exception {
    var result=new TreeMap<String,Long>();try(var s=c.createStatement();var rows=s.executeQuery("SELECT DATE(created_at),COUNT(*) FROM payments GROUP BY DATE(created_at) ORDER BY DATE(created_at)")){while(rows.next())result.put(rows.getString(1),rows.getLong(2));}return result;
  }
  void write(String name,Object value) throws Exception {Files.createDirectories(output);Files.writeString(output.resolve(name+".json"),LocalLoad.json(value));}
  static String id(Reply response) {
    if(response.status()/100!=2)throw new IllegalStateException("Console task rejected: HTTP "+response.status());
    var matcher=java.util.regex.Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([a-zA-Z0-9-]+)\\\"").matcher(response.body());
    if(!matcher.find())throw new IllegalStateException("Console task response lacks ID");return matcher.group(1);
  }
  final List<String> batchIds=new CopyOnWriteArrayList<>(),exportIds=new CopyOnWriteArrayList<>();
  final AtomicLong consoleCalls=new AtomicLong(),consoleErrors=new AtomicLong();
  final List<Long> listMicros=Collections.synchronizedList(new ArrayList<>());
  void consoleTraffic(long end) throws Exception {
    String merchant=LocalLoad.required("LOAD_BATCH_MERCHANT");
    if(!owners.containsKey(merchant))throw new IllegalArgumentException("Batch merchant is outside credential scope");
    String from=Instant.now().minus(Duration.ofDays(7)).toString(),until=Instant.now().toString();
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      for(int i=0;i<15;i++) {int worker=i;executor.submit(()->{
        while(System.nanoTime()<end) {
          long started=System.nanoTime();
          try {var response=console("GET",worker<10?"/api/console/payments?merchantId="+encode(merchant)+"&from="+encode(from)+"&until="+encode(until):"/api/console/dashboard?merchantId="+encode(merchant),"");if(response.status()!=200)consoleErrors.incrementAndGet();}
          catch(Exception failure){consoleErrors.incrementAndGet();}
          finally{consoleCalls.incrementAndGet();if(worker<10)listMicros.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-started));}
          int interval=Integer.parseInt(System.getenv().getOrDefault("LOAD_CONSOLE_INTERVAL_MILLIS","0"));
          if(interval>0)try{Thread.sleep(interval);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        }
      });}
      batchIds.add(id(console("POST","/api/console/reconciliation/pull",LocalLoad.json(Map.of("merchantId",merchant,"channel",System.getenv().getOrDefault("LOAD_BATCH_CHANNEL","MOCK_A"),"day",LocalLoad.required("LOAD_BATCH_DAY"),"version",Integer.parseInt(System.getenv().getOrDefault("LOAD_BATCH_VERSION","1")))))));
      exportIds.add(id(console("POST","/api/console/exports",LocalLoad.json(Map.of("merchantId",merchant,"resource","payments","from",Instant.now().minus(Duration.ofDays(30)).toString(),"until",until)))));
    }
  }
  Map<String,Object> taskResults() throws Exception {
    var result=new LinkedHashMap<String,Object>();var batches=new ArrayList<Map<String,Object>>();var exports=new ArrayList<Map<String,Object>>();
    try(var c=database()) {
      for(String id:batchIds)try(var s=c.prepareStatement("SELECT status,actual_count,processed_lines FROM recon_batches WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Missing reconciliation task");batches.add(Map.of("id",id,"status",r.getString(1),"rows",r.getLong(2),"processedLines",r.getLong(3)));}}
      for(String id:exportIds)try(var s=c.prepareStatement("SELECT status,row_count FROM exports WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Missing export task");exports.add(Map.of("id",id,"status",r.getString(1),"rows",r.getLong(2)));}}
    }
    result.put("reconciliation",batches);result.put("exports",exports);result.put("consoleCalls",consoleCalls.get());result.put("consoleErrors",consoleErrors.get());
    var sorted=listMicros.stream().sorted().toList();result.put("listP95Micros",LocalLoad.percentile(sorted,.95));
    result.put("batchCoveragePassed",!batches.isEmpty()&&!exports.isEmpty()&&batches.stream().allMatch(m->m.get("status").equals("COMPLETE")&&(long)m.get("rows")>=100000)&&exports.stream().allMatch(m->m.get("status").equals("COMPLETE")&&(long)m.get("rows")>=100000));return result;
  }
  Map<String,Object> truth(String run) throws Exception {
    long checked=0,disagreements=0;
    var digest=java.security.MessageDigest.getInstance("SHA-256");
    try(var c=database()) {
      for(String channel:List.of("MOCK_A","MOCK_B"))for(String operation:List.of("PAYMENT","REFUND")) {
        String after="";int size;
        do {
          var response=channel("/acceptance/results?channel="+channel+"&operation="+operation+"&prefix="+encode(run+"-")+"&after="+encode(after)+"&limit=1000");
          if(response.status()!=200)throw new IllegalStateException("Authoritative simulator results unavailable: HTTP "+response.status());
          size=0;
          for(String line:response.body().split("\\R")) {
            if(line.isBlank())continue;String[] v=line.split("\\t",-1);if(v.length!=6)throw new IllegalStateException("Invalid simulator evidence row");
            String table=operation.equals("PAYMENT")?"payments":"refunds";
            // Payment fee_minor is a quote even after failure; the authority reports charged fee.
            String business=operation.equals("PAYMENT")?"merchant_order_no":"merchant_refund_no",fee=operation.equals("PAYMENT")?"CASE WHEN status='SUCCEEDED' THEN fee_minor ELSE 0 END":"refunded_fee_minor";
            // Each refund in this driver selects a distinct payment. It does not race multiple
            // refunds of one payment, whose fee shares may legally differ by confirmation order.
            try(var s=c.prepareStatement("SELECT id,merchant_id,"+business+",status,amount_minor,"+fee+",channel FROM "+table+" WHERE id=?")) {
              s.setString(1,v[0]);try(var r=s.executeQuery()){
                String[] expected=Arrays.copyOf(v,7);expected[6]=channel;
                String[] actual=new String[7];boolean found=r.next();
                if(found)for(int field=0;field<7;field++)actual[field]=r.getString(field+1);
                if(!found||!factsMatch(expected,actual))disagreements++;
              }
            }
            digest.update((channel+"\t"+operation+"\t"+line+"\n").getBytes(StandardCharsets.UTF_8));after=v[0];size++;checked++;
          }
        }while(size==1000);
      }
      long persisted;
      try(var s=c.prepareStatement("SELECT (SELECT COUNT(*) FROM payments WHERE merchant_order_no LIKE ?)+(SELECT COUNT(*) FROM refunds WHERE merchant_refund_no LIKE ?)")){s.setString(1,run+"-%");s.setString(2,run+"-%");try(var r=s.executeQuery()){r.next();persisted=r.getLong(1);}}
      return Map.of("checked",checked,"persisted",persisted,"disagreements",disagreements,"sha256",HexFormat.of().formatHex(digest.digest()),"matched",checked==persisted&&disagreements==0);
    }
  }
  void verifyProfile() throws Exception {
    var reply=channel("/acceptance/profile");
    if(reply.status()!=200||!reply.body().strip().equals("standard-v1\t"+seed))throw new IllegalStateException("Simulator must explicitly enable standard-v1 with matching fixed seed");
  }
  void run(String kind,String run,int seconds,int payRate,int refundRate,int queryRate,boolean batches,boolean soak) throws Exception {
    run(kind,run,seconds,payRate,refundRate,queryRate,batches,soak,-1);
  }
  void run(String kind,String run,int seconds,int payRate,int refundRate,int queryRate,boolean batches,boolean soak,int exactPayments) throws Exception {
    if(!run.matches("[a-zA-Z0-9-]{1,60}")||seconds<1||seconds>14400||payRate+refundRate+queryRate<=0)throw new IllegalArgumentException("Invalid run ID, duration or empty workload");
    verifyProfile();
    if(payRate>0&&(notification==null||notification.isBlank()))throw new IllegalArgumentException("Payment workload requires LOAD_NOTIFICATION_URL to exercise real merchant HTTP delivery");
    var source=queryRate>0?pool():List.<Payment>of();
    int refundCount=schedule(seconds,refundRate,0,soak).requests.length;
    var refundSource=refundCount>0?refundPool(refundCount):List.<Payment>of();
    if(batches) {
      LocalLoad.required("LOAD_CONSOLE_COOKIE");LocalLoad.required("LOAD_CSRF_TOKEN");LocalLoad.required("LOAD_BATCH_DAY");LocalLoad.required("LOAD_BATCH_MERCHANT");
      if(console("GET","/api/console/session","").status()!=200)throw new IllegalStateException("Authenticated console session required before mixed load");
    }
    Files.createDirectories(output);
    write(run+".started",Map.of("at",Instant.now(),"scenario",kind,"seconds",seconds,"complete",false));
    long origin=System.nanoTime()+TimeUnit.SECONDS.toNanos(1),end=origin+TimeUnit.SECONDS.toNanos(seconds);
    var pay=schedule(seconds,payRate,origin,soak);var refund=schedule(seconds,refundRate,origin,soak);var query=schedule(seconds,queryRate,origin,soak);
    if(exactPayments>=0)pay=new LoadMeasurements(exactPayments,payRate,origin);
    final var payments=pay;
    var stopped=new AtomicBoolean();
    Thread observer=Thread.ofPlatform().name("acceptance-commit-observer").start(()->{
      long nextHourly=origin+TimeUnit.HOURS.toNanos(1);
      while(!stopped.get()) {
        observe("pay",run,payments,List.of());observe("refund",run,refund,refundSource);
        if(soak&&System.nanoTime()>=nextHourly) {try{write(run+".hour-"+TimeUnit.NANOSECONDS.toHours(nextHourly-origin),manifest());}catch(Exception failure){observerErrors.incrementAndGet();}nextHourly+=TimeUnit.HOURS.toNanos(1);}
        try{Thread.sleep(250);}catch(InterruptedException interrupted){return;}
      }
    });
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      var futures=new ArrayList<Future<?>>();
      futures.add(executor.submit(()->{traffic("pay",run,payments,List.of());return null;}));
      futures.add(executor.submit(()->{traffic("refund",run,refund,refundSource);return null;}));
      futures.add(executor.submit(()->{traffic("query",run,query,source);return null;}));
      if(batches)futures.add(executor.submit(()->{consoleTraffic(end);return null;}));
      for(var future:futures)future.get();until(end);
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
      while(System.nanoTime()<deadline) {
        boolean done;
        synchronized(pay){synchronized(refund){done=pay.unfinishedAccepted()+refund.unfinishedAccepted()==0&&Arrays.stream(pay.requests).noneMatch(r->r.sent&&!r.persisted)&&Arrays.stream(refund.requests).noneMatch(r->r.sent&&!r.persisted);}}
        if(done)break;Thread.sleep(250);
      }
    } finally {stopped.set(true);observer.join(65000);if(observer.isAlive())throw new IllegalStateException("Commit observer did not stop");}
    observe("pay",run,pay,List.of());observe("refund",run,refund,refundSource);
    long deliveryDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
    while(System.nanoTime()<deliveryDeadline) {
      try(var c=database()){if(deliveryConverged(deliveryEvidence(c)))break;}
      Thread.sleep(500);
    }
    var report=new LinkedHashMap<String,Object>();
    report.put("runId",run);report.put("scenario",kind);report.put("generatedAt",Instant.now());report.put("durationSeconds",seconds);report.put("seed",seed);report.put("credentialMerchants",owners.size());report.put("queryPoolPayments",source.size());report.put("queryDistribution","80% uniform across all credential-owned online payments; 20% fixed hotspot; credential bound to each selected payment");
    report.put("payment",stats(pay,end,seconds,true));report.put("refund",stats(refund,end,seconds,true));report.put("query",stats(query,end,seconds,false));report.put("tasks",taskResults());report.put("observerErrors",observerErrors.get());report.put("onlineCompletionMeasured",!kind.equals("dataset"));
    if(kind.equals("dataset"))for(String financial:List.of("payment","refund")){@SuppressWarnings("unchecked")var values=(Map<String,Object>)report.get(financial);for(String field:List.of("completedPerArrivalSecondLower","completedPerArrivalSecondUpper","successfulCompletionP99LowerMicros","successfulCompletionP99UpperMicros","acceptedCompletionP99UpperMicros"))values.put(field,null);}
    report.put("truth",truth(run));report.put("manifest",manifest());report.put("complete",true);report.put("referenceR1Certified",false);
    report.put("measurement","V2 independent committed SQL visibility, monotonic lower/upper intervals; observation cost included; no created_at commit timing");
    report.put("limits",List.of("Resource isolation, CPU/memory/disk/queue samples and observer overhead calibration need independent evidence","Cache-hit and cache-disabled comparison require platform configuration evidence","Per-request data and full reports are retained; this driver alone does not certify R1"));
    writeRequests(run+".pay",pay,origin);writeRequests(run+".refund",refund,origin);writeRequests(run+".query",query,origin);
    write(run,report);System.out.println(output.resolve(run+".json").toAbsolutePath());
    boolean correct=observerErrors.get()==0&&Boolean.TRUE.equals(((Map<?,?>)report.get("truth")).get("matched"))&&Boolean.TRUE.equals(((Map<?,?>)report.get("manifest")).get("moneyInvariantsPassed"))&&deliveryConverged((Map<?,?>)report.get("manifest"));
    for(var m:List.of(pay,refund,query))correct&=responsesAccepted(m,m!=query)&&m.unfinishedAccepted()==(m==query?m.accepted():0);
    if(!correct||batches&&!batchesAccepted((Map<?,?>)report.get("tasks"),Boolean.parseBoolean(System.getenv().getOrDefault("LOAD_SCALED","false")))||consoleErrors.get()>0)throw new IllegalStateException("Incomplete or incorrect acceptance run; retained report is not a pass");
  }
  void writeRequests(String name,LoadMeasurements m,long origin)throws Exception {
    try(var writer=Files.newBufferedWriter(output.resolve(name+".jsonl"))) {
      for(int i=0;i<m.requests.length;i++){var r=m.requests[i];var row=new LinkedHashMap<String,Object>();row.put("index",i);row.put("sent",r.sent);row.put("status",r.response);row.put("retryReason",r.retryReason);row.put("dueNanos",r.due-origin);row.put("startedNanos",r.started-origin);row.put("finishedNanos",r.finished-origin);row.put("persisted",r.persisted);row.put("terminalStatus",r.paymentStatus);row.put("posted",r.complete);row.put("commitLowerNanos",r.complete?r.completionLower-origin:null);row.put("commitUpperNanos",r.complete?r.completionUpper-origin:null);writer.write(LocalLoad.json(row));writer.newLine();}
    }
  }
  void dataset(String run,int total,int rate) throws Exception {
    if(!run.matches("[a-zA-Z0-9-]{1,40}")||total<1||rate<1)throw new IllegalArgumentException("Invalid stable dataset ID/count/rate");
    verifyProfile();Files.createDirectories(output);
    Path cursor=output.resolve(run+".cursor");int start=Files.exists(cursor)?Integer.parseInt(Files.readString(cursor).strip()):0;
    for(int offset=start;offset<total;offset+=1000) {
      int count=Math.min(1000,total-offset),seconds=(count+rate-1)/rate;
      String batch=run+"-b"+offset;
      // Stable keys make retry after interruption idempotent; no fake business timestamps or SQL writes.
      run("dataset",batch,seconds,rate,0,0,false,false,count);
      Path pending=output.resolve(run+".cursor.pending");Files.writeString(pending,Integer.toString(offset+count));Files.move(pending,cursor,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    var manifest=manifest();write(run+".manifest",manifest);
    if(!Boolean.TRUE.equals(manifest.get("d1CountersSatisfied")))System.out.println("Dataset generation complete; D1 counters/date distribution are NOT satisfied. No R1 claim.");
  }
  public static void main(String[] args)throws Exception {
    var load=new AcceptanceLoad();
    try {
      switch(args[0]) {
        case "manifest" -> {var m=load.manifest();load.write(args[1],m);System.out.println(LocalLoad.json(m));}
        case "truth" -> {var m=load.truth(args[1]);load.write(args[1]+".truth-recheck",m);System.out.println(LocalLoad.json(m));if(!Boolean.TRUE.equals(m.get("matched")))throw new IllegalStateException("Independent channel truth mismatch");}
        case "dataset" -> load.dataset(args[1],Integer.parseInt(args[2]),Integer.parseInt(args[3]));
        default -> load.run(args[0],args[1],Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]),Boolean.parseBoolean(args[6]),Boolean.parseBoolean(args[7]));
      }
    } catch(Exception failure) {load.write(args.length>1?args[1]+".failure":"failure",Map.of("at",Instant.now(),"complete",false,"qualified",false,"error",failure.getClass().getSimpleName()));throw failure;}
  }
}
