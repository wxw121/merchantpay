import java.nio.file.*;
import java.sql.*;
import java.time.Instant;

/** Read-only oracle for a payment intentionally accepted before an application restart. */
class RecoveryCheck {
  static long number(Connection db, String sql, String id) throws Exception {
    try(var statement=db.prepareStatement(sql)) {
      statement.setString(1,id);
      try(var rows=statement.executeQuery()) { rows.next();return rows.getLong(1); }
    }
  }
  public static void main(String[] args) throws Exception {
    String id=args[0];
    if(!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid project payment id");
    try(var db=DriverManager.getConnection(System.getenv().getOrDefault("DATABASE_URL","jdbc:mysql://127.0.0.1:3307/merchantpay?connectionTimeZone=UTC"),System.getenv().getOrDefault("DATABASE_USER","merchantpay"),System.getenv("DATABASE_PASSWORD"))) {
      db.setReadOnly(true);
      long deadline=System.nanoTime()+60_000_000_000L;
      while(number(db,"SELECT COUNT(*) FROM payments WHERE id=? AND status='SUCCEEDED'",id)!=1 && System.nanoTime()<deadline) Thread.sleep(500);
      db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);db.setAutoCommit(false);
      long success=number(db,"SELECT COUNT(*) FROM payments WHERE id=? AND status='SUCCEEDED'",id);
      long journals=number(db,"SELECT COUNT(*) FROM ledger_journals WHERE payment_id=?",id);
      long entries=number(db,"SELECT COUNT(*) FROM ledger_entries WHERE payment_id=?",id);
      long balance=number(db,"SELECT COALESCE(SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END),0) FROM ledger_entries WHERE payment_id=?",id);
      boolean archived;
      try(var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.views WHERE table_schema=DATABASE() AND table_name='outbox_task_records'")){r.next();archived=r.getLong(1)==1;}
      long events=number(db,"SELECT COUNT(*) FROM "+(archived?"outbox_task_records":"outbox_tasks")+" WHERE aggregate_id=? AND kind='PAYMENT_SUCCEEDED'",id);
      long attempts=number(db,"SELECT COUNT(*) FROM channel_attempts WHERE payment_id=?",id);
      boolean correct=success==1 && journals==1 && entries==3 && balance==0 && events==1 && attempts==1;
      String report="{\"scenario\":\"application-restart-after-durable-acceptance\",\"paymentId\":\""+id+"\",\"generatedAt\":\""+Instant.now()+"\",\"success\":"+success+",\"journals\":"+journals+",\"entries\":"+entries+",\"ledgerDifferenceMinor\":\""+balance+"\",\"successEvents\":"+events+",\"channelAttempts\":"+attempts+",\"correctnessPassed\":"+correct+"}";
      Files.createDirectories(Path.of(".work/performance"));Files.writeString(Path.of(".work/performance/restart-"+id+".json"),report);System.out.println(report);
      if(!correct) throw new AssertionError("Restart recovery failed invariants");
    }
  }
}
