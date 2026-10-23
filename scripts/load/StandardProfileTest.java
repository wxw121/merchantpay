import java.lang.reflect.*;
import java.util.*;

class StandardProfileTest {
  static Object profile(long seed)throws Exception {
    try{return Class.forName("dev.merchantpay.simulator.StandardProfile").getConstructor(String.class,long.class).newInstance("standard-v1",seed);}
    catch(ClassNotFoundException missing){throw new AssertionError("Explicit seeded standard channel profile is missing",missing);}
  }
  static Object call(Object target,String method,String key)throws Exception{return target.getClass().getMethod(method,String.class).invoke(target,key);}
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    Object first=profile(20261019),restart=profile(20261019);int failed=0,duplicates=0;var delays=new ArrayList<Long>();
    for(int i=0;i<100_000;i++){
      String key="standard-sample-"+i,status=(String)call(first,"status",key);long delay=(long)call(first,"delayMillis",key);
      check(status.equals(call(restart,"status",key))&&delay==(long)call(restart,"delayMillis",key),"Restart/order-independent seed must preserve results");
      if(status.equals("FAILED"))failed++;else if((boolean)call(first,"duplicate",key))duplicates++;delays.add(delay);
    }
    delays.sort(Long::compare);check(failed>1800&&failed<2200,"Standard profile must produce approximately two percent authoritative failures");
    check(duplicates>18500&&duplicates<20800,"Twenty percent successful notifications duplicate once");
    check(delays.get(49999)<=52&&delays.get(94999)<=205&&delays.get(98999)<=405,"Standard channel delay percentiles must follow 50/200/400ms");
    Object demo=Class.forName("dev.merchantpay.simulator.StandardProfile").getConstructor(String.class,long.class).newInstance("demo",20261019L);
    check((long)call(demo,"delayMillis","ordinary-order")==0,"Default demo must not gain performance delay");
    check(call(demo,"status","fail:order").equals("FAILED"),"Demo explicit failure semantics stay available");
    System.out.println("Seeded standard profile regression tests passed.");
  }
}
