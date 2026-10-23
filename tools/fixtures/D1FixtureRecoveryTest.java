import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mutates only the guarded, offline fixture. Every deliberate corruption is restored in finally. */
class D1FixtureRecoveryTest {
  public static void main(String[] args) throws Exception {
    String url=System.getenv("FIXTURE_DATABASE_URL");D1Fixture.guardDatabase(url);
    try(var mysql=new HikariDataSource();var authority=new HikariDataSource()) {
      mysql.setJdbcUrl(url);mysql.setUsername("fixture");mysql.setPassword(System.getenv("FIXTURE_DATABASE_PASSWORD"));
      mysql.addDataSourceProperty("connectionTimeZone","UTC");mysql.addDataSourceProperty("forceConnectionTimeZoneToSession","true");
      authority.setJdbcUrl("jdbc:h2:file:"+Path.of(args[0]).toAbsolutePath().resolve("channel")+";DB_CLOSE_ON_EXIT=FALSE");authority.setUsername("sa");authority.setPassword("");
      var jdbc=new JdbcTemplate(mysql);
      long payments=D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM payments"),entries=D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM ledger_entries");
      String task=jdbc.queryForObject("SELECT id FROM outbox_tasks WHERE status='DONE' ORDER BY id LIMIT 1",String.class);
      var original=jdbc.queryForMap("SELECT status,lease_token,lease_until,updated_at FROM outbox_tasks WHERE id=?",task);
      try {
        jdbc.update("UPDATE outbox_tasks SET status='RUNNING',lease_token=?,lease_until=? WHERE id=?",UUID.randomUUID().toString(),Timestamp.from(Instant.now().plusSeconds(3)),task);
        D1Fixture.main(args);
        if(D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM outbox_tasks WHERE status<>'DONE'")!=0)throw new AssertionError("Expired fixture lease was not recovered after cursor reached total");
      } finally {
        jdbc.update("UPDATE outbox_tasks SET status=?,lease_token=?,lease_until=?,updated_at=? WHERE id=?",original.get("status"),original.get("lease_token"),original.get("lease_until"),original.get("updated_at"),task);
      }
      if(payments!=D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM payments")||entries!=D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM ledger_entries"))throw new AssertionError("Resume duplicated financial facts");
      String payment=jdbc.queryForObject("SELECT id FROM payments ORDER BY id LIMIT 1",String.class);
      long originalFee=jdbc.queryForObject("SELECT fee_minor FROM payments WHERE id=?",Long.class,payment);
      try {
        jdbc.update("UPDATE payments SET fee_minor=? WHERE id=?",originalFee+1,payment);
        try {D1Fixture.verifyAuthority(mysql,authority);throw new AssertionError("Channel fee mismatch was accepted");}
        catch(IllegalStateException expected) {if(!expected.getMessage().contains("fact mismatch"))throw expected;}
      } finally {jdbc.update("UPDATE payments SET fee_minor=? WHERE id=?",originalFee,payment);}
      D1Fixture.verifyAuthority(mysql,authority);
      System.out.println("Isolated expired-lease recovery, cursor-complete resume, financial deduplication and fee tamper regression passed.");
    }
  }
}
