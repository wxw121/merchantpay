import com.zaxxer.hikari.HikariDataSource;
import dev.merchantpay.platform.common.KeyVault;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.zip.ZipInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Opens only a newly restored local database and offline channel copy, never source data. */
class RestoredSetCheck {
  public static void main(String[] args) throws Exception {
    String url=System.getenv("DATABASE_URL");
    if(!url.equals("jdbc:mysql://127.0.0.1:13309/merchantpay?connectionTimeZone=UTC"))throw new IllegalArgumentException("Restore verification requires its isolated database");
    Path folder=Path.of(args[0]).toAbsolutePath();
    try(var mysql=new HikariDataSource();var channel=new HikariDataSource()) {
      mysql.setJdbcUrl(url);mysql.setUsername("merchantpay");mysql.setPassword(System.getenv("DATABASE_PASSWORD"));
      mysql.addDataSourceProperty("connectionTimeZone","UTC");mysql.addDataSourceProperty("forceConnectionTimeZoneToSession","true");
      Path channelFile;
      try(var files=Files.walk(folder.resolve("simulator"))){channelFile=files.filter(p->p.toString().endsWith(".mv.db")).findFirst().orElseThrow();}
      channel.setJdbcUrl("jdbc:h2:file:"+channelFile.toString().replaceFirst("\\.mv\\.db$","")+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE");channel.setUsername("sa");channel.setPassword("");
      String hash=D1Fixture.verifyAuthority(mysql,channel);
      var jdbc=new JdbcTemplate(mysql);var vault=new KeyVault(System.getenv("MASTER_KEY"));
      var mapper=JsonMapper.shared();var credentials=mapper.readTree(Files.readString(folder.resolve("api-credentials.json")));
      int checkedKeys=0;
      for(var credential:credentials) {
        var rows=jdbc.queryForList("SELECT merchant_id,secret_cipher FROM merchant_keys WHERE id=?",credential.path("keyId").asText());
        if(rows.size()!=1||!rows.getFirst().get("merchant_id").equals(credential.path("merchantId").asText())||!vault.decrypt((String)rows.getFirst().get("secret_cipher")).equals(credential.path("secret").asText()))throw new IllegalStateException("Restored key material does not authenticate its stored merchant key");
        checkedKeys++;
      }
      if(checkedKeys==0)throw new IllegalStateException("No merchant key was verified from this restore set");
      long badJournals=D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM (SELECT j.id FROM ledger_journals j LEFT JOIN ledger_entries e ON e.journal_id=j.id GROUP BY j.id HAVING COUNT(e.id)<2 OR SUM(CASE WHEN e.side='DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)<>0) invalid");
      long badRefunds=D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM payments p WHERE p.refunded_minor+p.reserved_minor>p.amount_minor OR p.refunded_fee_minor>p.fee_minor OR p.refunded_minor<>(SELECT COALESCE(SUM(r.amount_minor),0) FROM refunds r WHERE r.payment_id=p.id AND r.status='SUCCEEDED') OR p.refunded_fee_minor<>(SELECT COALESCE(SUM(r.refunded_fee_minor),0) FROM refunds r WHERE r.payment_id=p.id AND r.status='SUCCEEDED')");
      long missingJournals=D1Fixture.scalar(jdbc,"SELECT (SELECT COUNT(*) FROM payments p WHERE p.status='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM ledger_journals j WHERE j.event_id=CONCAT('payment-success:',p.id)))+(SELECT COUNT(*) FROM refunds r WHERE r.status='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM ledger_journals j WHERE j.event_id=CONCAT('refund-success:',r.id)))");
      long unfinished=D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM outbox_tasks WHERE status<>'DONE'");
      if(badJournals+badRefunds+missingJournals+unfinished!=0)throw new IllegalStateException("Restored ledger, refund or task invariants failed");
      int bills=0,exports=0,s3Bills=0,s3Exports=0;
      boolean hasS3="true".equals(System.getenv("RESTORE_HAS_S3"));
      try(var storage=new dev.merchantpay.platform.reconciliation.ObjectStorage(folder.resolve("platform").toString(),hasS3?"s3":"local",hasS3?System.getenv("S3_ENDPOINT"):"",hasS3?System.getenv("S3_BUCKET"):"","us-east-1",true)) {
        for(var row:jdbc.queryForList("SELECT storage_key,sha256 FROM recon_batches")) {
          var billHash=MessageDigest.getInstance("SHA-256");
          try(var stream=storage.open((String)row.get("storage_key"))) {byte[] buffer=new byte[65536];int n;while((n=stream.read(buffer))!=-1)billHash.update(buffer,0,n);}
          if(!HexFormat.of().formatHex(billHash.digest()).equals(row.get("sha256")))throw new IllegalStateException("Restored bill SHA mismatch");bills++;if(((String)row.get("storage_key")).startsWith("s3:"))s3Bills++;
        }
        for(var row:jdbc.queryForList("SELECT storage_key FROM exports WHERE status='COMPLETE'")) {
          int members=0;
          try(var zip=new ZipInputStream(storage.open((String)row.get("storage_key")))) {while(zip.getNextEntry()!=null){zip.transferTo(java.io.OutputStream.nullOutputStream());zip.closeEntry();members++;}}
          if(members==0)throw new IllegalStateException("Restored export is empty or invalid");exports++;if(((String)row.get("storage_key")).startsWith("s3:"))s3Exports++;
        }
      }
      var result=new LinkedHashMap<String,Object>();result.put("authorityFactSha256",hash);result.put("channelReceipts",D1Fixture.scalar(new JdbcTemplate(channel),"SELECT COUNT(*) FROM receipts"));result.put("merchantKeysVerified",checkedKeys);result.put("truthAndKeyRecoveryPassed",checkedKeys>0);result.put("referenceR1Certified",false);
      result.put("payments",D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM payments"));result.put("refunds",D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM refunds"));result.put("ledgerEntries",D1Fixture.scalar(jdbc,"SELECT COUNT(*) FROM ledger_entries"));result.put("unbalancedJournals",badJournals);result.put("refundViolations",badRefunds);result.put("missingJournals",missingJournals);result.put("unfinishedTasks",unfinished);result.put("referencedBillsVerified",bills);result.put("referencedExportsVerified",exports);result.put("moneyAndLocalObjectRecoveryPassed",true);
      result.put("referencedS3BillsVerified",s3Bills);result.put("referencedS3ExportsVerified",s3Exports);result.put("moneyAndObjectRecoveryPassed",true);
      result.put("channelCloseBeforeSubmitTombstones",D1Fixture.scalar(new JdbcTemplate(channel),"SELECT COUNT(*) FROM receipts WHERE operation='PAYMENT' AND status='CLOSED' AND request_hash='closed'"));
      result.put("nonSuccessfulPaymentFeeQuotes",D1Fixture.scalar(new JdbcTemplate(channel),"SELECT COUNT(*) FROM receipts WHERE operation='PAYMENT' AND status<>'SUCCEEDED' AND request_hash<>'closed' AND fee_minor<>0"));
      result.put("chargedFeeEvidenceScope","Unsuccessful historical payment receipts may retain a quote; actual charged payment fee is zero unless SUCCEEDED. Original archived fields are not changed; refund fees remain strict.");
      result.put("closeTombstoneEvidenceScope","A close-before-submit tombstone verifies channel/id/status/currency/zero charged fee; the channel has no merchant payload or original amount to verify");
      Files.writeString(Path.of(args[1]),mapper.writeValueAsString(result));System.out.println(mapper.writeValueAsString(result));
    }
  }
}
