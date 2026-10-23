import java.lang.reflect.*;
import java.util.*;

/** No network, no secrets, no dependency on Maven. */
class AcceptanceToolsTest {
  static Object call(String name, Class<?>[] types, Object... args) throws Exception {
    Class<?> type;
    try { type = Class.forName("AcceptanceLoad"); }
    catch (ClassNotFoundException missing) { throw new AssertionError("Acceptance load protocol is missing", missing); }
    var method = type.getDeclaredMethod(name, types); method.setAccessible(true);
    try { return method.invoke(null, args); }
    catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
  }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    check((boolean)call("d1", new Class[]{long.class,long.class,long.class,long.class,long.class}, 1_000_000L,5_000_000L,10_000_000L,1000L,30L), "Full D1 counters qualify");
    check(!(boolean)call("d1", new Class[]{long.class,long.class,long.class,long.class,long.class}, 999_999L,5_000_000L,10_000_000L,1000L,30L), "Scaled data must not qualify as D1");
    check(!(boolean)call("d1", new Class[]{long.class,long.class,long.class,long.class,long.class}, 1_000_000L,5_000_000L,10_000_000L,1000L,1L), "Current-day data must not qualify as D1");
    int[] frequencies = new int[1000];
    var random = new Random(20261019);
    for(int i=0;i<100_000;i++) frequencies[(int)call("queryIndex",new Class[]{Random.class,int.class},random,1000)]++;
    check(Arrays.stream(frequencies).allMatch(n -> n>0), "Random queries must cover complete online range");
    check(frequencies[0]>15_000 && frequencies[0]<25_000, "20 percent hot key with 80 percent full-range selection");
    check((int)call("soakPercent",new Class[]{int.class},3299)==20, "Base soak load is 20 percent C3");
    check((int)call("soakPercent",new Class[]{int.class},3300)==100, "First hour must also contain a five minute spike");
    check((int)call("soakPercent",new Class[]{int.class},3599)==100, "Hourly spike lasts five minutes");
    check((int)call("soakPercent",new Class[]{int.class},3600)==20, "Next hour returns to base load");
    check((int)call("soakPercent",new Class[]{int.class},3900)==20, "Load returns after spike");
    check((boolean)call("terminal",new Class[]{String.class},"FAILED"), "Authoritative business failure is terminal");
    check(!(boolean)call("terminal",new Class[]{String.class},"UNKNOWN"), "Unknown must not disappear as business failure");
    var parsed=(Map<?,?>)call("credentials",new Class[]{String.class},"merchant-a\tkey-a\tsecret-a\nmerchant-b\tkey-b\tsecret-b");
    check(parsed.size()==2 && parsed.containsKey("merchant-b"), "Credentials remain indexed by owning tenant");
    boolean rejected=false;
    try { call("credentials",new Class[]{String.class},"merchant-a\tkey-a\tsecret-a\nmerchant-a\tkey-b\tsecret-b"); }
    catch(IllegalArgumentException expected) { rejected=true; }
    check(rejected,"Duplicate merchant credentials must not silently overwrite ownership");
    var second=(LoadMeasurements)call("schedule",new Class[]{int.class,int.class,long.class,boolean.class},2,3,10L,false);
    check(second.requests.length==6&&second.requests[3].due==1_000_000_010L,"Independent arrivals must keep scheduled time even when senders slow down");
    var fractional=(LoadMeasurements)call("schedule",new Class[]{int.class,int.class,long.class,boolean.class},10,1,10L,true);
    check(fractional.requests.length==2&&fractional.requests[0].due==10L&&fractional.requests[1].due==5_000_000_010L,"Scaled soak must retain fractional baseline arrivals instead of rounding them to zero");
    var hour=(LoadMeasurements)call("schedule",new Class[]{int.class,int.class,long.class,boolean.class},3600,1,0L,true);
    check(hour.requests.length==960&&hour.requests[660].due==3_300_000_000_000L,"Hourly spike and fractional baseline retain their exact total");
    var smallBatches=Map.of("reconciliation",List.of(Map.of("status","COMPLETE","rows",2L)),"exports",List.of(Map.of("status","COMPLETE","rows",2L)));
    check((boolean)call("batchesAccepted",new Class[]{Map.class,boolean.class},smallBatches,true),"Explicitly scaled mixed coverage accepts complete nonempty batches");
    check(!(boolean)call("batchesAccepted",new Class[]{Map.class,boolean.class},smallBatches,false),"Formal mixed coverage still requires 100000 rows");
    check(!(boolean)call("batchesAccepted",new Class[]{Map.class,boolean.class},Map.of("reconciliation",List.of(),"exports",List.of()),true),"Scaled coverage cannot accept empty task samples");
    check((boolean)call("deliveryConverged",new Class[]{Map.class},Map.of("unfinishedTasks",0L,"unfinishedNotifications",0L,"undeliveredNotifications",0L)),"Complete real HTTP deliveries and queue drain pass");
    check(!(boolean)call("deliveryConverged",new Class[]{Map.class},Map.of("unfinishedTasks",1L,"unfinishedNotifications",1L,"undeliveredNotifications",1L)),"Permanent webhook failures must fail even when money has posted");
    check(!(boolean)call("deliveryConverged",new Class[]{Map.class},Map.of()),"Missing HTTP delivery evidence must fail closed");
    var queries=new LoadMeasurements(3,1,10L);
    for(int i=0;i<3;i++)queries.sent(i,queries.requests[i].due);
    queries.finished(0,200,9_000_000_000L);queries.finished(1,200,9_000_000_001L);queries.finished(2,200,11_000_000_000L);
    var queryStats=(Map<?,?>)call("stats",new Class[]{LoadMeasurements.class,long.class,int.class,boolean.class},queries,10_000_000_000L,10,false);
    check(Objects.equals(queryStats.get("successfulPerArrivalSecond"),.2),"Query throughput excludes responses that finish after the arrival window");
    String[] authority={"id","merchant","business","SUCCEEDED","100","1","MOCK_A"};
    check((boolean)call("factsMatch",new Class[]{String[].class,String[].class},authority,authority.clone()),"All channel fields match");
    for(int field:new int[]{2,5,6}) {
      String[] corrupted=authority.clone();corrupted[field]="wrong";
      check(!(boolean)call("factsMatch",new Class[]{String[].class,String[].class},authority,corrupted),"Business number, fee and channel mismatches must fail");
    }
    var tolerance=new LoadMeasurements(1000,100,10L);
    for(int i=0;i<1000;i++){tolerance.sent(i,10L);tolerance.finished(i,i==0?500:200,20L);}
    check((boolean)call("responsesAccepted",new Class[]{LoadMeasurements.class,boolean.class},tolerance,false),"Query system error tolerance is 0.1 percent");
    check(!(boolean)call("responsesAccepted",new Class[]{LoadMeasurements.class,boolean.class},tolerance,true),"Financial uncertainty still fails the driver");
    tolerance.finished(1,500,20L);
    check(!(boolean)call("responsesAccepted",new Class[]{LoadMeasurements.class,boolean.class},tolerance,false),"Excessive query errors fail");
    var reasonSample=new LoadMeasurements(1,1,10L);reasonSample.sent(0,10L);reasonSample.requests[0].retryReason="API_INFLIGHT";reasonSample.finished(0,503,20L);
    var reasons=(Map<?,?>)call("stats",new Class[]{LoadMeasurements.class,long.class,int.class,boolean.class},reasonSample,30L,1,false);
    check(Map.of("API_INFLIGHT",1L).equals(reasons.get("retryReasons")),"Acceptance reports retain normalized 503 reason evidence without changing the error gate");
    System.out.println("Acceptance tool regression tests passed.");
  }
}
