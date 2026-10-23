import java.sql.*;
import java.util.*;

/** Read-only diagnostics against this project's configured MySQL. Never prints credentials. */
class DbInspect {
    public static void main(String[] args) throws Exception {
        String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:mysql://127.0.0.1:3307/merchantpay?connectionTimeZone=UTC");
        try(var connection=DriverManager.getConnection(url,System.getenv().getOrDefault("DATABASE_USER","merchantpay"),System.getenv("DATABASE_PASSWORD"))) {
            connection.setReadOnly(true);
            for(String sql:List.of("SELECT id,merchant_order_no,status,channel FROM payments ORDER BY created_at DESC LIMIT 5",
                    "SELECT id,status,actual_count,processed_lines,created_at,updated_at,error_detail FROM recon_batches ORDER BY created_at DESC LIMIT 3",
                    "SELECT kind,status,last_error FROM outbox_tasks ORDER BY created_at DESC LIMIT 10",
                    "SELECT COUNT(*) AS journal_count FROM ledger_journals",
                    "SELECT status,COUNT(*) AS task_count FROM outbox_tasks GROUP BY status",
                    "SELECT ID,TIME,STATE,LEFT(INFO,180) AS active_sql FROM information_schema.PROCESSLIST WHERE DB=DATABASE() AND INFO IS NOT NULL ORDER BY TIME DESC LIMIT 6",
                    "EXPLAIN SELECT id FROM outbox_tasks WHERE (status='PENDING' AND due_at<=NOW(6)) OR (status='RUNNING' AND lease_until<NOW(6)) ORDER BY due_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    "EXPLAIN SELECT id FROM outbox_tasks WHERE status='PENDING' AND due_at<=NOW(6) ORDER BY due_at,id LIMIT 32 FOR UPDATE SKIP LOCKED",
                    "EXPLAIN SELECT id FROM outbox_tasks WHERE status='PENDING' AND due_at<=NOW(6) AND kind IN ('RECONCILE','EXPORT') ORDER BY due_at,id LIMIT 2 FOR UPDATE SKIP LOCKED",
                    "EXPLAIN SELECT id FROM outbox_tasks WHERE status='PENDING' AND due_at<=NOW(6) AND kind NOT IN ('RECONCILE','EXPORT') ORDER BY due_at,id LIMIT 30 FOR UPDATE SKIP LOCKED",
                    "SELECT COALESCE(SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END),0) AS ledger_difference_minor FROM ledger_entries")) {
                try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) {
                    var columns=result.getMetaData();
                    while(result.next()) { var row=new LinkedHashMap<String,String>();for(int i=1;i<=columns.getColumnCount();i++) { row.put(columns.getColumnLabel(i),result.getString(i)); }System.out.println(row); }
                }
            }
        }
    }
}
