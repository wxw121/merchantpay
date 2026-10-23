import com.zaxxer.hikari.HikariDataSource;
import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.ledger.LedgerService;
import dev.merchantpay.platform.merchant.MerchantService;
import dev.merchantpay.platform.outbox.*;
import dev.merchantpay.platform.payment.PaymentService;
import dev.merchantpay.platform.refund.RefundService;
import dev.merchantpay.simulator.ChannelStore;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Offline fixture only: real domain services and a separate durable simulated channel authority. */
class D1Fixture {
  static void guardDatabase(String url) {
    if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:13308/merchantpay_fixture_[a-f0-9]{8}"))
      throw new IllegalArgumentException("Fixture requires its isolated loopback MySQL database; normal databases are forbidden");
  }
  static long amount(long index) {
    if (index % 1000 == 0) return 1;
    if (index % 1000 == 1) return 1_000_000_000L;
    double percentile = new Random(20261019L ^ index).nextDouble();
    return 5 + (long)(Math.pow(percentile, 5) * 9_999_995);
  }
  static LocalDate businessDay(long index, long total, int days, LocalDate first) {
    return first.plusDays(index * days / total);
  }
  static final class HistoricalClock extends Clock {
    Instant now;
    HistoricalClock(Instant now) { this.now = now; }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("Fixture uses UTC"); return this; }
    public Instant instant() { return now; }
  }
  static long scalar(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, Long.class); }
  static ChannelResult.Success success(ChannelStore.Receipt receipt) {
    if (!receipt.status().equals("SUCCEEDED")) throw new IllegalStateException("Fixture authority did not succeed");
    return new ChannelResult.Success(receipt.channel(), receipt.reference(), Long.parseLong(receipt.amountMinor()), receipt.currency());
  }
  static String authorityChargedFee(boolean payment,String status,String requestHash,String fee) {
    return payment&&!"SUCCEEDED".equals(status)&&!"closed".equals(requestHash)?"0":fee;
  }
  static void verifyAuthorityFact(String[] fact,String[] truth,String requestHash,boolean payment) {
    // A close before submit has no merchant payload at the channel; its durable tombstone still proves id/status.
    boolean tombstone=payment&&"closed".equals(requestHash)&&"CLOSED".equals(fact[3])&&"CLOSED".equals(truth[3])
        &&"UNASSIGNED".equals(truth[1])&&"close-before-submit".equals(truth[2])&&"0".equals(truth[4])&&"0".equals(truth[5])&&"CNY".equals(truth[6]);
    for(int column=0;column<8;column++) {
      if(column==7&&!"SUCCEEDED".equals(fact[3]))continue;
      if(tombstone&&(column==1||column==2||column==4))continue;
      if(!Objects.equals(fact[column],truth[column]))throw new IllegalStateException("Fixture authority fact mismatch in column "+(column+1));
    }
  }
  static String verifyAuthority(javax.sql.DataSource platform, javax.sql.DataSource authority) throws Exception {
    var hash = MessageDigest.getInstance("SHA-256");
    try(var left=platform.getConnection();var right=authority.getConnection()) {
      left.setReadOnly(true);left.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);left.setAutoCommit(false);
      right.setReadOnly(true);right.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);right.setAutoCommit(false);
      for(String selectedChannel:List.of("MOCK_A","MOCK_B"))for(String operation:List.of("PAYMENT","REFUND")) {
        boolean payment=operation.equals("PAYMENT");
        String columns="id,merchant_id,"+(payment?"merchant_order_no":"merchant_refund_no")+",status,amount_minor,"+(payment?"CASE WHEN status='SUCCEEDED' THEN fee_minor ELSE 0 END":"refunded_fee_minor")+","+(payment?"currency":"(SELECT p.currency FROM payments p WHERE p.id=refunds.payment_id)")+",channel_reference";
        try(var a=left.prepareStatement("SELECT "+columns+" FROM "+(payment?"payments":"refunds")+" WHERE channel=? ORDER BY id");var b=right.prepareStatement("SELECT aggregate_id,merchant_id,business_no,status,amount_minor,fee_minor,currency,reference,request_hash FROM receipts WHERE channel=? AND operation=? ORDER BY aggregate_id")) {
          a.setString(1,selectedChannel);a.setFetchSize(Integer.MIN_VALUE);b.setString(1,selectedChannel);b.setString(2,operation);
          try(var rows=a.executeQuery();var truth=b.executeQuery()) {
            while(true) {
              boolean hasRow=rows.next(),hasTruth=truth.next();
              if(hasRow!=hasTruth)throw new IllegalStateException("Fixture authority row-count mismatch");
              if(!hasRow)break;
              String[] fact=new String[8],channelFact=new String[8];
              for(int column=0;column<8;column++){fact[column]=rows.getString(column+1);channelFact[column]=truth.getString(column+1);}
              channelFact[5]=authorityChargedFee(payment,channelFact[3],truth.getString(9),channelFact[5]);
              verifyAuthorityFact(fact,channelFact,truth.getString(9),payment);
              hash.update((selectedChannel+"\t"+operation+"\t").getBytes(StandardCharsets.UTF_8));
              for(int column=1;column<=8;column++) {
                // Failed results do not carry a channel reference into the platform domain.
                if(column==8&&!rows.getString(4).equals("SUCCEEDED")){hash.update((byte)0);continue;}
                hash.update(rows.getString(column).getBytes(StandardCharsets.UTF_8));hash.update((byte)0);
              }
            }
          }
        }
      }
      left.commit();right.commit();return HexFormat.of().formatHex(hash.digest());
    }
  }
  public static void main(String[] args) throws Exception {
    String url = System.getenv("FIXTURE_DATABASE_URL"); guardDatabase(url);
    Path folder = Path.of(args[0]).toAbsolutePath().normalize();
    long total = Long.parseLong(args[1]); int merchantCount = Integer.parseInt(args[2]), days = Integer.parseInt(args[3]);
    if (total < 1 || total > 1_000_000 || merchantCount < 1 || merchantCount > 1000 || days < 1 || days > 30 || days > total || merchantCount > total)
      throw new IllegalArgumentException("Invalid fixture bounds");
    Files.createDirectories(folder);
    try (var mysql = new HikariDataSource(); var channelDb = new HikariDataSource()) {
      mysql.setJdbcUrl(url); mysql.setUsername("fixture"); mysql.setPassword(System.getenv("FIXTURE_DATABASE_PASSWORD"));
      mysql.setMaximumPoolSize(4); mysql.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
      mysql.addDataSourceProperty("connectionTimeZone", "UTC"); mysql.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
      var jdbc = new JdbcTemplate(mysql);
      long tables = scalar(jdbc, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()");
      if (tables > 0 && scalar(jdbc, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='fixture_control'") != 1)
        throw new IllegalStateException("Existing non-fixture database is forbidden");
      jdbc.execute("CREATE TABLE IF NOT EXISTS fixture_control(id INT PRIMARY KEY,total BIGINT NOT NULL,merchants INT NOT NULL,days INT NOT NULL,first_day DATE NOT NULL,cursor_value BIGINT NOT NULL)");
      var controls = jdbc.queryForList("SELECT * FROM fixture_control WHERE id=1");
      LocalDate first;
      if (controls.isEmpty()) {
        first = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        jdbc.update("INSERT INTO fixture_control VALUES (1,?,?,?,?,0)", total, merchantCount, days, java.sql.Date.valueOf(first));
      } else {
        var control = controls.getFirst();
        if (((Number)control.get("total")).longValue()!=total || ((Number)control.get("merchants")).intValue()!=merchantCount || ((Number)control.get("days")).intValue()!=days)
          throw new IllegalArgumentException("Resume must use the original total/merchants/days");
        first = ((java.sql.Date)control.get("first_day")).toLocalDate();
      }
      Flyway.configure().dataSource(mysql).baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/migration").load().migrate();
      channelDb.setJdbcUrl("jdbc:h2:file:"+folder.resolve("channel")+";DB_CLOSE_ON_EXIT=FALSE"); channelDb.setUsername("sa"); channelDb.setPassword(""); channelDb.setMaximumPoolSize(1);
      new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(channelDb);
      var channel = new ChannelStore(new JdbcTemplate(channelDb), new TransactionTemplate(new DataSourceTransactionManager(channelDb)));
      var clock = new HistoricalClock(first.atTime(12,0).toInstant(ZoneOffset.UTC));
      var tx = new TransactionTemplate(new DataSourceTransactionManager(mysql));
      var ledger = new LedgerService(jdbc); var outbox = new OutboxService(jdbc, clock);
      var payments = new PaymentService(jdbc, tx, ledger, outbox, clock, 16);
      var refunds = new RefundService(jdbc, tx, payments, ledger, outbox, clock);
      var merchants = new MerchantService(jdbc, new KeyVault(System.getenv("FIXTURE_MASTER_KEY")), new NotificationPolicy(""), tx, clock);
      var credentials = new ArrayList<MerchantService.Key>();
      for (int merchant = 0; merchant < merchantCount; merchant++) {
        String id = String.format(Locale.ROOT,"fixture-m%04d",merchant);
        if (scalar(jdbc,"SELECT COUNT(*) FROM merchants WHERE id='"+id+"'")==0) merchants.configure(id,"D1 模拟商户 "+merchant,true,null,"fixture");
        var existing = jdbc.queryForList("SELECT id FROM merchant_keys WHERE merchant_id=? AND enabled=TRUE AND expires_at IS NULL",String.class,id);
        credentials.add(existing.isEmpty()?merchants.rotate(id,"fixture"):merchants.key(existing.getFirst()));
      }
      Files.writeString(folder.resolve("credentials.json"), JsonMapper.shared().writeValueAsString(credentials));
      // Historical business dates must never freeze worker leases or recovery time.
      var queue = new TaskQueue(jdbc,tx,Clock.systemUTC());
      long start = scalar(jdbc,"SELECT cursor_value FROM fixture_control WHERE id=1");
      long started = System.nanoTime();
      for (long index = start; index < total; index++) {
        clock.now = businessDay(index,total,days,first).atTime(12,0).toInstant(ZoneOffset.UTC);
        String merchant = String.format(Locale.ROOT,"fixture-m%04d",index%merchantCount), business = "d1-20261019-"+index, selectedChannel = index%2==0?"MOCK_A":"MOCK_B";
        long amount = amount(index);
        var payment = tx.execute(ignored -> {
          var created = payments.create(merchant,new PaymentService.CreatePayment(business,Long.toString(amount),"CNY",selectedChannel,null));
          merchants.audit(merchant,"fixture","PAYMENT_CREATE",created.id(),business); return created;
        });
        var receipt = channel.pay(selectedChannel,new ChannelStore.Request(payment.id(),payment.id(),merchant,business,Long.toString(amount),"CNY",clock.instant()));
        if (index%100==1 && !payment.status().equals("SUCCEEDED")) payments.applyResult(payment.id(),new ChannelResult.Unknown(selectedChannel,"fixture-response-lost"));
        payments.applyResult(payment.id(),success(channel.query(selectedChannel,"PAYMENT",payment.id())));
        if (index%20==0) payments.applyResult(payment.id(),success(receipt));
        if (index%5!=0) for (int part=0;part<5;part++) {
          String refundNo = business+"-refund-"+part; long refundAmount = part==4?amount-4*(amount/5):amount/5;
          var refund = tx.execute(ignored -> {
            var created = refunds.create(merchant,payment.id(),new RefundService.CreateRefund(refundNo,Long.toString(refundAmount)));
            merchants.audit(merchant,"fixture","REFUND_CREATE",created.id(),refundNo); return created;
          });
          var refundReceipt = channel.refund(selectedChannel,new ChannelStore.Request(refund.id(),payment.id(),merchant,refundNo,Long.toString(refundAmount),"CNY",clock.instant()));
          if(index%100==2 && part==0 && !refund.status().equals("SUCCEEDED")) refunds.applyResult(refund.id(),new ChannelResult.Unknown(selectedChannel,"fixture-refund-response-lost"));
          refunds.applyResult(refund.id(),success(refundReceipt));
        }
        drain(jdbc,queue,payments,refunds,channel);
        jdbc.update("UPDATE fixture_control SET cursor_value=? WHERE id=1",index+1);
        if ((index+1)%1000==0) System.out.println("Committed fixture payments: "+(index+1)+"/"+total);
      }
      drain(jdbc,queue,payments,refunds,channel);
      long bad = scalar(jdbc,"SELECT COUNT(*) FROM (SELECT j.id FROM ledger_journals j LEFT JOIN ledger_entries e ON e.journal_id=j.id GROUP BY j.id HAVING COUNT(e.id)<2 OR SUM(CASE WHEN e.side='DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)<>0) invalid");
      long amounts = scalar(jdbc,"SELECT COUNT(*) FROM payments WHERE refunded_minor+reserved_minor>amount_minor OR refunded_minor<0 OR reserved_minor<>0 OR refunded_fee_minor>fee_minor");
      long actualPayments=scalar(jdbc,"SELECT COUNT(*) FROM payments"),actualRefunds=scalar(jdbc,"SELECT COUNT(*) FROM refunds"),events=scalar(jdbc,"SELECT COUNT(*) FROM outbox_tasks"),entries=scalar(jdbc,"SELECT COUNT(*) FROM ledger_entries"),actualDays=scalar(jdbc,"SELECT COUNT(DISTINCT DATE(created_at)) FROM payments"),pending=scalar(jdbc,"SELECT COUNT(*) FROM outbox_tasks WHERE status<>'DONE'");
      long authority=scalar(new JdbcTemplate(channelDb),"SELECT COUNT(*) FROM receipts");
      LocalDate firstActual=jdbc.queryForObject("SELECT MIN(DATE(created_at)) FROM payments",java.sql.Date.class).toLocalDate(),lastActual=jdbc.queryForObject("SELECT MAX(DATE(created_at)) FROM payments",java.sql.Date.class).toLocalDate();
      boolean historicalOnly=lastActual.isBefore(LocalDate.now(ZoneOffset.UTC));
      String authorityHash=verifyAuthority(mysql,channelDb);
      var report = new LinkedHashMap<String,Object>();
      report.put("at",Instant.now().toString());report.put("payments",actualPayments);report.put("refunds",actualRefunds);report.put("ledgerEntries",entries);report.put("outboxEvents",events);report.put("merchants",merchantCount);report.put("businessDates",actualDays);report.put("channelReceipts",authority);report.put("unfinishedTasks",pending);report.put("unbalancedJournals",bad);report.put("refundViolations",amounts);report.put("resumeCursor",scalar(jdbc,"SELECT cursor_value FROM fixture_control WHERE id=1"));
      boolean correct=bad==0&&amounts==0&&pending==0&&authority==actualPayments+actualRefunds&&actualPayments==total;
      report.put("moneyAndAuthorityVerified",correct);report.put("firstBusinessDay",firstActual.toString());report.put("lastBusinessDay",lastActual.toString());report.put("historicalDatesOnly",historicalOnly);report.put("d1CountersSatisfied",correct&&historicalOnly&&actualPayments>=1000000&&entries>=5000000&&events>=10000000&&merchantCount>=1000&&actualDays>=30);report.put("referenceR1Certified",false);report.put("runElapsedMillis",(System.nanoTime()-started)/1000000);
      report.put("authorityFactSha256",authorityHash);
      report.put("scope","Offline historical fixture using domain services, independent durable H2 channel authority, fenced completion, five lawful partial refunds on 80% of payments; no HTTP/signature/notification transport benchmark");
      Files.writeString(folder.resolve("manifest.json"),JsonMapper.shared().writeValueAsString(report));
      System.out.println(JsonMapper.shared().writeValueAsString(report));
      if(!correct)throw new IllegalStateException("Fixture failed financial or authority validation");
    }
  }
  static void drain(JdbcTemplate jdbc,TaskQueue queue,PaymentService payments,RefundService refunds,ChannelStore channel) throws InterruptedException {
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(75);
    while(true) {
      var tasks=queue.claimBatch(64);
      if(tasks.isEmpty()) {
        if(scalar(jdbc,"SELECT COUNT(*) FROM outbox_tasks WHERE status<>'DONE'")==0)return;
        if(System.nanoTime()>deadline)throw new IllegalStateException("Fixture tasks did not recover within the lease deadline");
        Thread.sleep(200);continue;
      }
      for(var task:tasks) {
        if(task.kind().equals("CHANNEL_PAYMENT")) {
          var payment=payments.get(task.merchantId(),task.aggregateId());
          payments.applyResult(payment.id(),success(channel.query(payment.channel(),"PAYMENT",payment.id())));
        } else if(task.kind().equals("CHANNEL_REFUND")) {
          var refund=refunds.get(task.merchantId(),task.aggregateId());
          refunds.applyResult(refund.id(),success(channel.query(refund.channel(),"REFUND",refund.id())));
        } else if(!Set.of("PAYMENT_SUCCEEDED","REFUND_SUCCEEDED").contains(task.kind())) throw new IllegalStateException("Unexpected fixture task kind");
        // All fixture notification URLs are absent; NotificationService has the same successful no-op branch.
      }
      if(queue.completeBatch(tasks).stream().anyMatch(done->!done))throw new IllegalStateException("Fixture task lease lost");
    }
  }
}
