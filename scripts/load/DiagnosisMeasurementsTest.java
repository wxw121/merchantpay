import java.util.*;

class DiagnosisMeasurementsTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static Map<String,Object> snapshot(long uptime, long reads, long wait) {
    return new LinkedHashMap<>(Map.of(
        "config",Map.of("serverUuid","same", "bufferPoolBytes",134217728L),
        "status",Map.of("Uptime",Long.toString(uptime),"Innodb_buffer_pool_reads",Long.toString(reads),"Innodb_buffer_pool_read_requests","1000"),
        "instruments",List.of(Map.of("NAME","wait/io/file/innodb/innodb_data_file","ENABLED","YES","TIMED","YES")),
        "consumers",List.of(Map.of("NAME","global_instrumentation","ENABLED","YES")),
        "files",List.of(Map.of("EVENT_NAME","wait/io/file/innodb/innodb_data_file","COUNT_READ",10L,"SUM_TIMER_READ",wait))));
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] args) {
    var before=snapshot(10,100,9007199254740993L); var after=snapshot(20,150,9007199254741010L);
    var delta=LocalDiagnosis.ioDelta(before,after);
    check(Boolean.TRUE.equals(delta.get("valid")),"Stable snapshots must be comparable");
    var counters=(Map<String,Object>)delta.get("statusDeltas");
    check(counters.get("Innodb_buffer_pool_reads").toString().equals("50"),"Use interval physical reads");
    check(counters.get("Innodb_os_log_fsyncs")==null,"Missing counter must remain unavailable");
    var files=(List<Map<String,Object>>)delta.get("fileDeltas");
    check(files.getFirst().get("SUM_TIMER_READ").toString().equals("17"),"Subtract integer picoseconds before floating conversion");
    check(files.getFirst().get("COUNT_MISC")==null,"Unavailable misc is not zero or fsync");
    var unsignedBefore=snapshot(10,100,0); var unsignedAfter=snapshot(20,150,0);
    unsignedBefore.put("files",List.of(Map.of("EVENT_NAME","wait/io/file/innodb/innodb_data_file","COUNT_READ","10","SUM_TIMER_READ","9223372036854775808")));
    unsignedAfter.put("files",List.of(Map.of("EVENT_NAME","wait/io/file/innodb/innodb_data_file","COUNT_READ","12","SUM_TIMER_READ","9223372036854775825")));
    var unsignedDelta=LocalDiagnosis.ioDelta(unsignedBefore,unsignedAfter);
    files=(List<Map<String,Object>>)unsignedDelta.get("fileDeltas");
    check(Boolean.TRUE.equals(unsignedDelta.get("valid")) && files.getFirst().get("SUM_TIMER_READ").toString().equals("17"),"Unsigned BIGINT timer totals must retain exact interval differences");
    check(LocalDiagnosis.ioDelta(before,snapshot(1,150,9007199254741010L)).get("valid").equals(false),"Restart must invalidate interval");
    check(LocalDiagnosis.ioDelta(before,snapshot(20,99,9007199254741010L)).get("valid").equals(false),"Counter reset must invalidate interval");
    var changed=snapshot(20,150,9007199254741010L); changed.put("config",Map.of("serverUuid","other"));
    check(LocalDiagnosis.ioDelta(before,changed).get("valid").equals(false),"Configuration/identity change invalidates interval");
    changed=snapshot(20,150,9007199254741010L); changed.put("instruments",List.of());
    check(LocalDiagnosis.ioDelta(before,changed).get("valid").equals(false),"Instrumentation change invalidates interval");
    before=snapshot(10,100,100); after=snapshot(20,150,120);
    var untimed=List.of(Map.of("NAME","wait/io/file/innodb/innodb_data_file","ENABLED","YES","TIMED","NO"));
    before.put("instruments",untimed); after.put("instruments",untimed);
    files=(List<Map<String,Object>>)LocalDiagnosis.ioDelta(before,after).get("fileDeltas");
    check(files.getFirst().get("SUM_TIMER_READ")==null,"Untimed I/O must not produce a measured zero");
    var disabled=List.of(Map.of("NAME","global_instrumentation","ENABLED","NO"));
    before.put("consumers",disabled); after.put("consumers",disabled);
    check(LocalDiagnosis.ioDelta(before,after).get("valid").equals(false),"Disabled global collection is unavailable");
    before=snapshot(10,100,100); after=snapshot(20,150,120);
    var stageInstrument=List.of(Map.of("NAME","stage/sql/waiting for handler commit","ENABLED","YES","TIMED","YES"));
    before.put("instruments",stageInstrument); after.put("instruments",stageInstrument);
    before.put("stages",List.of(Map.of("EVENT_NAME","stage/sql/waiting for handler commit","COUNT_STAR",10L,"SUM_TIMER_WAIT","9223372036854775808")));
    after.put("stages",List.of(Map.of("EVENT_NAME","stage/sql/waiting for handler commit","COUNT_STAR",13L,"SUM_TIMER_WAIT","9223372036854775825")));
    var stageDelta=(List<Map<String,Object>>)LocalDiagnosis.ioDelta(before,after).get("stageDeltas");
    check(stageDelta!=null && stageDelta.size()==1,"Commit stage evidence must be present");
    check(stageDelta.getFirst().get("COUNT_STAR").toString().equals("3") && stageDelta.getFirst().get("SUM_TIMER_WAIT").toString().equals("17"),"Stage differences must remain exact");
    var untimedStage=List.of(Map.of("NAME","stage/sql/waiting for handler commit","ENABLED","YES","TIMED","NO"));
    before.put("instruments",untimedStage); after.put("instruments",untimedStage);
    stageDelta=(List<Map<String,Object>>)LocalDiagnosis.ioDelta(before,after).get("stageDeltas");
    check(stageDelta.getFirst().get("COUNT_STAR").toString().equals("3") && stageDelta.getFirst().get("SUM_TIMER_WAIT")==null,"Untimed stage keeps count and withholds timer");
    var disabledStage=List.of(Map.of("NAME","stage/sql/waiting for handler commit","ENABLED","NO","TIMED","NO"));
    before.put("instruments",disabledStage); after.put("instruments",disabledStage);
    stageDelta=(List<Map<String,Object>>)LocalDiagnosis.ioDelta(before,after).get("stageDeltas");
    check(stageDelta.getFirst().get("COUNT_STAR")==null && stageDelta.getFirst().get("SUM_TIMER_WAIT")==null,"Disabled stage is unavailable, not zero");
    after.put("stages",List.of(Map.of("EVENT_NAME","stage/sql/waiting for handler commit","COUNT_STAR",9L,"SUM_TIMER_WAIT",0L)));
    check(Boolean.FALSE.equals(LocalDiagnosis.ioDelta(before,after).get("valid")),"Reset stage counters invalidate interval");
    before=snapshot(10,100,100); after=snapshot(20,150,120);
    var cacheBefore=new LinkedHashMap<String,Object>((Map<String,Object>)before.get("status"));
    var cacheAfter=new LinkedHashMap<String,Object>((Map<String,Object>)after.get("status"));
    cacheBefore.put("Binlog_cache_use","9007199254740993"); cacheAfter.put("Binlog_cache_use","9007199254741010");
    cacheBefore.put("Binlog_cache_disk_use","2"); cacheAfter.put("Binlog_cache_disk_use","3");
    cacheBefore.put("Binlog_stmt_cache_use","0"); cacheAfter.put("Binlog_stmt_cache_use","0");
    before.put("status",cacheBefore); after.put("status",cacheAfter);
    counters=(Map<String,Object>)LocalDiagnosis.ioDelta(before,after).get("statusDeltas");
    check(counters.get("Binlog_cache_use")!=null && counters.get("Binlog_cache_use").toString().equals("17"),"Binlog cache transaction counts must retain exact interval differences");
    check(counters.get("Binlog_cache_disk_use").toString().equals("1") && counters.get("Binlog_stmt_cache_use").toString().equals("0"),"Track cache spills and nontransaction statement cache separately");
    check(counters.get("Innodb_os_log_fsyncs")==null,"A binlog cache use is not an fsync measurement");
    cacheAfter.put("Binlog_cache_use","9007199254740992");
    check(Boolean.FALSE.equals(LocalDiagnosis.ioDelta(before,after).get("valid")),"Reset binlog cache counters invalidate interval");
    System.out.println("Diagnostic measurement tests passed");
  }
}
