import java.sql.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Read-only active-event sampling. Enabling/restoring MySQL instrumentation is a separate action. */
class CommitWaitSampler {
  static final String EVENTS_SQL="""
      SELECT t.THREAD_ID threadId,t.NAME threadType,
             e.EVENT_ID statementEventId,e.TIMER_WAIT statementAgePicos,e.DIGEST_TEXT digest,
             e.NESTING_EVENT_ID transactionId,e.NESTING_EVENT_TYPE transactionParentType,
             h.DIGEST_TEXT previousDigest,h.NESTING_EVENT_ID previousTransactionId,
             s.EVENT_ID stageEventId,s.EVENT_NAME stage,s.TIMER_WAIT stageAgePicos,
             w.EVENT_ID waitEventId,w.EVENT_NAME waitEvent,w.OPERATION waitOperation,w.TIMER_WAIT waitAgePicos
      FROM performance_schema.threads t
      LEFT JOIN performance_schema.events_statements_current e ON e.THREAD_ID=t.THREAD_ID AND e.END_EVENT_ID IS NULL
      LEFT JOIN performance_schema.events_statements_history h ON h.THREAD_ID=t.THREAD_ID AND h.EVENT_ID=(
        SELECT MAX(prior.EVENT_ID) FROM performance_schema.events_statements_history prior
        WHERE e.EVENT_NAME='statement/sql/commit' AND e.NESTING_EVENT_TYPE='TRANSACTION'
          AND prior.THREAD_ID=t.THREAD_ID AND prior.EVENT_ID<e.EVENT_ID
          AND prior.NESTING_EVENT_TYPE='TRANSACTION' AND prior.NESTING_EVENT_ID=e.NESTING_EVENT_ID)
      LEFT JOIN performance_schema.events_stages_current s ON s.THREAD_ID=t.THREAD_ID AND s.END_EVENT_ID IS NULL
        AND s.NESTING_EVENT_TYPE='STATEMENT' AND s.NESTING_EVENT_ID=e.EVENT_ID
      LEFT JOIN performance_schema.events_waits_current w ON w.THREAD_ID=t.THREAD_ID AND w.END_EVENT_ID IS NULL
        AND ((w.NESTING_EVENT_TYPE='STAGE' AND w.NESTING_EVENT_ID=s.EVENT_ID)
          OR (w.NESTING_EVENT_TYPE='STATEMENT' AND w.NESTING_EVENT_ID=e.EVENT_ID)
          OR (t.TYPE='BACKGROUND'))
      WHERE (t.PROCESSLIST_DB='merchantpay' AND t.PROCESSLIST_COMMAND<>'Sleep' AND t.PROCESSLIST_ID<>CONNECTION_ID())
         OR t.NAME IN ('thread/innodb/log_writer_thread','thread/innodb/log_flusher_thread')
      ORDER BY t.THREAD_ID
      """;
  static String kind(String digest,String previous){
    if(digest==null)return "OTHER";
    String normalized=digest.replace("`","").strip().toUpperCase(Locale.ROOT);
    if(normalized.equals("COMMIT"))return previous!=null&&previous.replace("`","").toUpperCase(Locale.ROOT).contains("API_RATE_LIMITS")?"QUOTA_COMMIT":"COMMIT_OTHER";
    if(normalized.startsWith("INSERT INTO SIGNATURE_NONCES"))return "NONCE";
    if(normalized.contains("API_RATE_LIMITS"))return "QUOTA_SQL";
    return "OTHER";
  }
  static Map<String,Object> sanitize(Map<String,Object> row){
    var safe=new LinkedHashMap<String,Object>();
    for(var field:List.of("threadId","threadType","statementEventId","statementAgePicos","stageEventId","stage","stageAgePicos","waitEventId","waitEvent","waitOperation","waitAgePicos"))if(row.containsKey(field))safe.put(field,row.get(field));
    boolean sameTransaction="TRANSACTION".equals(row.get("transactionParentType"))&&row.get("transactionId")!=null&&row.get("previousTransactionId")!=null&&row.get("transactionId").toString().equals(row.get("previousTransactionId").toString());
    safe.put("statementKind",kind((String)row.get("digest"),sameTransaction?(String)row.get("previousDigest"):null));
    return safe;
  }
  static void requireConsumers(List<Map<String,Object>> consumers){
    for(var name:List.of("global_instrumentation","thread_instrumentation","events_stages_current","events_waits_current","events_statements_current","events_statements_history","statements_digest")){
      if(consumers.stream().filter(row->name.equals(row.get("NAME"))&&"YES".equals(row.get("ENABLED"))).count()!=1)throw new IllegalStateException("Required consumer is missing/disabled: "+name);
    }
  }
  public static void main(String[] args)throws Exception{
    int seconds=Integer.parseInt(args[0]);if(seconds<1||seconds>600)throw new IllegalArgumentException("Duration must be1..600seconds");
    var p=new Properties();p.setProperty("user","root");p.setProperty("password",LocalLoad.required("MYSQL_ROOT_PASSWORD"));p.setProperty("connectTimeout","3000");p.setProperty("socketTimeout","5000");
    var directory=Path.of(".work/performance");Files.createDirectories(directory);
    String id="commitwait-"+Instant.now().getEpochSecond()+"-"+UUID.randomUUID().toString().substring(0,8);
    String waits="SELECT EVENT_NAME,COUNT_STAR,SUM_TIMER_WAIT FROM performance_schema.events_waits_summary_global_by_event_name WHERE EVENT_NAME LIKE 'wait/synch/%/innodb/%log%' OR EVENT_NAME LIKE 'wait/synch/%/sql/MYSQL_BIN_LOG%' OR EVENT_NAME LIKE 'wait/io/file/innodb/%' OR EVENT_NAME LIKE 'wait/io/file/sql/binlog%' ORDER BY EVENT_NAME";
    try(var c=DriverManager.getConnection(System.getenv().getOrDefault("DATABASE_URL","jdbc:mysql://127.0.0.1:3307/merchantpay?connectionTimeZone=UTC"),p);var writer=Files.newBufferedWriter(directory.resolve(id+".jsonl"))){
      c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      var consumers=LocalDiagnosis.query(c,"SELECT NAME,ENABLED FROM performance_schema.setup_consumers WHERE NAME IN ('global_instrumentation','thread_instrumentation','events_stages_current','events_waits_current','events_statements_current','events_statements_history','statements_digest') ORDER BY NAME");
      requireConsumers(consumers);
      Files.writeString(directory.resolve(id+".io-before.json"),LocalLoad.json(LocalDiagnosis.ioSnapshot(c)));
      Files.writeString(directory.resolve(id+".waits-before.json"),LocalLoad.json(LocalDiagnosis.query(c,waits)));
      Files.writeString(directory.resolve(id+".consumers.json"),LocalLoad.json(consumers));
      long deadline=System.nanoTime()+seconds*1_000_000_000L;
      while(System.nanoTime()<deadline){
        var sample=new LinkedHashMap<String,Object>();sample.put("startedAt",Instant.now());long started=System.nanoTime();
        try{
          sample.put("events",LocalDiagnosis.query(c,EVENTS_SQL).stream().map(CommitWaitSampler::sanitize).toList());
          sample.put("lockWaits",LocalDiagnosis.query(c,"SELECT l.OBJECT_NAME,l.INDEX_NAME,COUNT(*) waiting FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID GROUP BY l.OBJECT_NAME,l.INDEX_NAME"));
        }catch(SQLException failure){sample.put("error",failure.getClass().getSimpleName());}
        sample.put("finishedAt",Instant.now());sample.put("queryMicros",(System.nanoTime()-started)/1000);
        writer.write(LocalLoad.json(sample));writer.newLine();writer.flush();Thread.sleep(100);
      }
      Files.writeString(directory.resolve(id+".waits-after.json"),LocalLoad.json(LocalDiagnosis.query(c,waits)));
      Files.writeString(directory.resolve(id+".io-after.json"),LocalLoad.json(LocalDiagnosis.ioSnapshot(c)));
      System.out.println("Commit wait samples: "+directory.resolve(id+".jsonl"));
    }
  }
}
