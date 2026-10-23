import java.sql.*;
import java.util.*;
import java.time.*;

/** Numeric/configuration-only local baseline probe; no writes or raw receipt data. */
class RuntimeSnapshot {
  public static void main(String[] args) throws Exception {
    var p=new Properties(); p.setProperty("user","root"); p.setProperty("password",LocalLoad.required("MYSQL_ROOT_PASSWORD"));
    p.setProperty("connectTimeout","3000"); p.setProperty("socketTimeout","15000");
    try(var c=DriverManager.getConnection(System.getenv().getOrDefault("DATABASE_URL","jdbc:mysql://127.0.0.1:3307/merchantpay?connectionTimeZone=UTC"),p)) {
      c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
      var out=new LinkedHashMap<String,Object>(); out.put("at",Instant.now());
      out.put("mysql",LocalDiagnosis.ioSnapshot(c));
      out.put("admission",LocalDiagnosis.query(c,"SELECT logical_shard,inflight,(SELECT COUNT(*) FROM payments p WHERE p.logical_shard=a.logical_shard AND p.status NOT IN ('SUCCEEDED','FAILED','CLOSED')) actual FROM payment_admission a ORDER BY logical_shard"));
      out.put("unfinishedTasks",LocalDiagnosis.query(c,"SELECT COUNT(*) unfinished FROM outbox_tasks WHERE status IN ('PENDING','RUNNING')").getFirst().get("unfinished"));
      out.put("dataset",LocalDiagnosis.query(c,LocalLoad.taskFactsSql(c,"SELECT (SELECT COUNT(*) FROM payments) payments,(SELECT COUNT(*) FROM ledger_entries) ledgerEntries,(SELECT COUNT(*) FROM ledger_journals) journals,(SELECT COUNT(*) FROM outbox_tasks) tasks,(SELECT COUNT(*) FROM merchants) merchants")).getFirst());
      c.commit(); System.out.println(LocalLoad.json(out));
    }
  }
}
