import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Read-only D0 sampler. Digest text is normalized SQL, never raw SQL or lock data. */
class LocalDiagnosis {
  static final List<String> IO_COUNTERS = List.of("Innodb_buffer_pool_reads", "Innodb_buffer_pool_read_requests", "Innodb_buffer_pool_wait_free", "Innodb_data_reads", "Innodb_data_writes", "Innodb_data_read", "Innodb_data_written", "Innodb_data_fsyncs", "Innodb_os_log_fsyncs", "Innodb_os_log_written", "Innodb_log_waits", "Binlog_cache_use", "Binlog_cache_disk_use", "Binlog_stmt_cache_use");
  static final List<String> FILE_COUNTERS = List.of("COUNT_READ", "SUM_TIMER_READ", "SUM_NUMBER_OF_BYTES_READ", "COUNT_WRITE", "SUM_TIMER_WRITE", "SUM_NUMBER_OF_BYTES_WRITE", "COUNT_MISC", "SUM_TIMER_MISC");
  static final String FILE_FILTER = "NAME LIKE 'wait/io/file/innodb/%' OR NAME LIKE 'wait/io/file/sql/binlog%'";
  static Map<String,Object> ioSnapshot(Connection connection) throws SQLException {
    var snapshot = new LinkedHashMap<String,Object>();
    snapshot.put("startedAt", Instant.now());
    snapshot.put("config",query(connection,"SELECT @@server_uuid serverUuid,@@innodb_flush_log_at_trx_commit flushOnCommit,@@sync_binlog syncBinlog,@@log_bin logBin,@@innodb_buffer_pool_size bufferPoolBytes,@@performance_schema performanceSchema,@@binlog_group_commit_sync_delay binlogSyncDelayMicros,@@binlog_group_commit_sync_no_delay_count binlogNoDelayCount,@@innodb_log_writer_threads logWriterThreads").getFirst());
    var status = new LinkedHashMap<String,Object>();
    for (var row : query(connection,"SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Uptime','Innodb_buffer_pool_pages_total','Innodb_buffer_pool_pages_free','Innodb_buffer_pool_pages_dirty','"+String.join("','",IO_COUNTERS)+"') ORDER BY VARIABLE_NAME")) status.put(row.get("VARIABLE_NAME").toString(),row.get("VARIABLE_VALUE"));
    snapshot.put("status",status);
    snapshot.put("instruments",query(connection,"SELECT NAME,ENABLED,TIMED FROM performance_schema.setup_instruments WHERE "+FILE_FILTER+" OR NAME LIKE 'wait/synch/%/innodb/%log%' OR NAME LIKE 'stage/sql/%commit%' ORDER BY NAME"));
    snapshot.put("consumers",query(connection,"SELECT NAME,ENABLED FROM performance_schema.setup_consumers ORDER BY NAME"));
    snapshot.put("files",query(connection,"SELECT EVENT_NAME,"+String.join(",",FILE_COUNTERS)+" FROM performance_schema.file_summary_by_event_name WHERE "+FILE_FILTER.replace("NAME LIKE", "EVENT_NAME LIKE")+" ORDER BY EVENT_NAME"));
    snapshot.put("stages",query(connection,"SELECT EVENT_NAME,COUNT_STAR,SUM_TIMER_WAIT FROM performance_schema.events_stages_summary_global_by_event_name WHERE EVENT_NAME LIKE 'stage/sql/%commit%' ORDER BY EVENT_NAME"));
    snapshot.put("finishedAt",Instant.now());
    return snapshot;
  }
  static List<Map<String,Object>> eventDeltas(List<Map<String,Object>> before,List<Map<String,Object>> after,Map<Object,Map<String,Object>> instruments,List<String> columns,String category,boolean available,List<String> problems) {
    var oldEvents=new HashMap<Object,Map<String,Object>>();
    for(var row:before) oldEvents.put(row.get("EVENT_NAME"),row);
    var events=new ArrayList<Map<String,Object>>(); var newNames=new HashSet<Object>();
    for(var row:after) {
      var name=row.get("EVENT_NAME"); newNames.add(name);
      var old=oldEvents.get(name); var instrument=instruments.get(name);
      if(old==null) { problems.add(category+" event appeared: "+name); old=Map.of(); }
      boolean enabled=available && instrument!=null && "YES".equals(instrument.get("ENABLED"));
      boolean timed=enabled && "YES".equals(instrument.get("TIMED"));
      var event=new LinkedHashMap<String,Object>(); event.put("EVENT_NAME",name); event.put("enabled",enabled); event.put("timed",timed);
      for(var column:columns) {
        var delta=difference(old.get(column),row.get(column),name+"/"+column,problems);
        event.put(column,enabled && (!column.startsWith("SUM_TIMER_") || timed) ? delta : null);
      }
      events.add(event);
    }
    if(!newNames.equals(oldEvents.keySet())) problems.add(category+" event set changed");
    return events;
  }
  static java.math.BigInteger difference(Object before,Object after,String name,List<String> problems) {
    if(before==null || after==null) {
      if(before!=after) problems.add("Counter availability changed: "+name);
      return null;
    }
    var old=new java.math.BigInteger(before.toString()); var current=new java.math.BigInteger(after.toString());
    if(current.compareTo(old)<0 || old.signum()<0) { problems.add("Counter decreased or negative: "+name); return null; }
    return current.subtract(old);
  }
  @SuppressWarnings("unchecked")
  static Map<String,Object> ioDelta(Map<String,Object> before,Map<String,Object> after) {
    var problems=new ArrayList<String>();
    for(var key:List.of("config","instruments","consumers")) if(!Objects.equals(before.get(key),after.get(key))) problems.add("Changed: "+key);
    var consumers=(List<Map<String,Object>>)after.get("consumers");
    boolean available=consumers.stream().anyMatch(row -> "global_instrumentation".equals(row.get("NAME")) && "YES".equals(row.get("ENABLED")));
    if(!available) problems.add("Global instrumentation unavailable");
    var oldStatus=(Map<String,Object>)before.get("status"); var newStatus=(Map<String,Object>)after.get("status");
    if(difference(oldStatus.get("Uptime"),newStatus.get("Uptime"),"Uptime",problems)==null) problems.add("Server uptime unavailable");
    var deltas=new LinkedHashMap<String,Object>();
    for(var name:IO_COUNTERS) deltas.put(name,difference(oldStatus.get(name),newStatus.get(name),name,problems));
    var instruments=new HashMap<Object,Map<String,Object>>();
    for(var row:(List<Map<String,Object>>)after.get("instruments")) instruments.put(row.get("NAME"),row);
    var files=eventDeltas((List<Map<String,Object>>)before.get("files"),(List<Map<String,Object>>)after.get("files"),instruments,FILE_COUNTERS,"File",available,problems);
    var stages=eventDeltas((List<Map<String,Object>>)before.getOrDefault("stages",List.of()),(List<Map<String,Object>>)after.getOrDefault("stages",List.of()),instruments,List.of("COUNT_STAR","SUM_TIMER_WAIT"),"Stage",available,problems);
    var result=new LinkedHashMap<String,Object>();
    result.put("valid",problems.isEmpty()); result.put("problems",problems);
    result.put("statusDeltas",deltas); result.put("fileDeltas",files); result.put("stageDeltas",stages);
    result.put("units","Timers are cumulative picoseconds; MISC includes sync/open/close/etc, not a dedicated fsync count. Missing/untimed values are null. Invalid intervals must not be used for comparisons.");
    return result;
  }
  static List<Map<String,Object>> query(Connection connection, String sql) throws SQLException {
    var result = new ArrayList<Map<String,Object>>();
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(3);
      try (var rows = statement.executeQuery(sql)) {
        var metadata = rows.getMetaData();
        while (rows.next()) {
          var row = new LinkedHashMap<String,Object>();
          for (int i=1;i<=metadata.getColumnCount();i++) row.put(metadata.getColumnLabel(i),rows.getObject(i));
          result.add(row);
        }
      }
    }
    return result;
  }
  static String listJson(List<Map<String,Object>> rows) {
    return rows.stream().map(LocalLoad::json).collect(java.util.stream.Collectors.joining(",","[","]"));
  }
  public static void main(String[] args) throws Exception {
    int seconds = Integer.parseInt(args[0]);
    if (seconds<1 || seconds>600) throw new IllegalArgumentException("Diagnostic duration must be 1..600 seconds");
    var properties = new Properties(); properties.setProperty("user","root");
    properties.setProperty("password",LocalLoad.required("MYSQL_ROOT_PASSWORD"));
    properties.setProperty("connectTimeout","3000"); properties.setProperty("socketTimeout","5000");
    var directory = Path.of(".work","performance"); Files.createDirectories(directory);
    String run = "diagnosis-"+Instant.now().getEpochSecond()+"-"+UUID.randomUUID().toString().substring(0,8);
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    String authorization = "Basic "+Base64.getEncoder().encodeToString(("metrics:"+LocalLoad.required("METRICS_PASSWORD")).getBytes(StandardCharsets.UTF_8));
    try (var connection=DriverManager.getConnection(System.getenv().getOrDefault("DATABASE_URL","jdbc:mysql://127.0.0.1:3307/merchantpay?connectionTimeZone=UTC"),properties);
         var writer=Files.newBufferedWriter(directory.resolve(run+".jsonl"))) {
      connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      boolean admissionAvailable = ((Number)query(connection,"SELECT COUNT(*) tablesPresent FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='payment_admission'").getFirst().get("tablesPresent")).intValue() == 1;
      Files.writeString(directory.resolve(run+".config.json"),listJson(query(connection,"SELECT @@innodb_flush_log_at_trx_commit flushOnCommit,@@sync_binlog syncBinlog,@@innodb_buffer_pool_size bufferPoolBytes,@@max_connections maxConnections,@@performance_schema performanceSchema")));
      String digestSql="SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_LOCK_TIME,SUM_ROWS_EXAMINED,SUM_ROWS_SENT,SUM_NO_INDEX_USED,SUM_ERRORS FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='merchantpay' AND DIGEST IS NOT NULL";
      Files.writeString(directory.resolve(run+".digests-before.json"),listJson(query(connection,digestSql)));
      var ioBefore=ioSnapshot(connection);
      Files.writeString(directory.resolve(run+".io-before.json"),LocalLoad.json(ioBefore));
      long started=System.nanoTime(), deadline=started+seconds*1_000_000_000L;
      while (System.nanoTime()<deadline) {
        var sample=new LinkedHashMap<String,Object>(); sample.put("at",Instant.now()); sample.put("elapsedSeconds",(System.nanoTime()-started)/1_000_000_000d);
        try {
          var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/actuator/prometheus")).timeout(Duration.ofSeconds(3)).header("Authorization",authorization).GET().build(),HttpResponse.BodyHandlers.ofString());
          sample.put("metricsStatus",response.statusCode());
          // Keep only numeric business/runtime metrics, never request headers or credential values.
          if(response.statusCode()==200) sample.put("metrics",response.body().lines().filter(line -> !line.startsWith("#") && line.matches("^(hikaricp_|merchantpay_|process_cpu_|system_cpu_|jvm_gc_pause_|http_server_requests_|jvm_threads_).*" )).collect(java.util.stream.Collectors.joining("\n")));
          sample.put("mysqlStatus",query(connection,"SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Threads_running','Threads_connected','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Innodb_log_waits','Innodb_os_log_written','Innodb_data_fsyncs','Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_rows_read','Innodb_rows_inserted','Innodb_rows_updated')"));
          sample.put("lockWaits",query(connection,"SELECT COUNT(*) waiting FROM performance_schema.data_lock_waits").getFirst().get("waiting"));
          sample.put("lockWaitIndexes",query(connection,"SELECT l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,COUNT(*) waiting FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID GROUP BY l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE"));
          sample.put("sessions",query(connection,"SELECT COMMAND,STATE,COUNT(*) sessions FROM information_schema.PROCESSLIST WHERE DB='merchantpay' GROUP BY COMMAND,STATE"));
          sample.put("queue",query(connection,"SELECT kind,status,COUNT(*) tasks,MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP())) oldestSeconds FROM outbox_tasks WHERE status IN ('PENDING','RUNNING') GROUP BY kind,status"));
          if (admissionAvailable) sample.put("paymentAdmission",query(connection,"SELECT logical_shard,inflight FROM payment_admission ORDER BY logical_shard"));
        } catch (Exception failure) { sample.put("sampleError",failure.getClass().getSimpleName()); }
        sample.put("sampleMicros",(System.nanoTime()-started-(long)((Double)sample.get("elapsedSeconds")*1_000_000_000L))/1000);
        writer.write(LocalLoad.json(sample)); writer.newLine(); writer.flush();
        Thread.sleep(1000);
      }
      Files.writeString(directory.resolve(run+".digests-after.json"),listJson(query(connection,digestSql)));
      var ioAfter=ioSnapshot(connection);
      Files.writeString(directory.resolve(run+".io-after.json"),LocalLoad.json(ioAfter));
      Files.writeString(directory.resolve(run+".io-delta.json"),LocalLoad.json(ioDelta(ioBefore,ioAfter)));
      System.out.println("Read-only diagnostic evidence: "+directory.resolve(run+".jsonl"));
    }
  }
}
