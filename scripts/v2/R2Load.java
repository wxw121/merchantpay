import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

/** Explicitly invoked bounded native HTTP generator. Acceptance counts are NOT money completion. */
public class R2Load {
  static String required(String key){var value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value;}
  static final Pattern ID=Pattern.compile("\"id\"\\s*:\\s*\"([A-Za-z0-9_-]{1,64})\"");
  static String digest(String body)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}
  static HttpRequest request(URI base,String method,String path,String body,String key,String secret)throws Exception{
    String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String signature=HexFormat.of().formatHex(mac.doFinal((timestamp+"\n"+nonce+"\n"+method+"\n"+path+"\n"+digest(body)).getBytes(StandardCharsets.UTF_8)));
    var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).header("X-Key-Id",key).header("X-Timestamp",timestamp).header("X-Nonce",nonce).header("X-Signature",signature);
    return method.equals("GET")?builder.GET().build():builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
  }
  public static void main(String[] args)throws Exception{
    URI base=URI.create(required("R2_BASE_URL"));if(!"https".equals(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!"/".equals(base.getPath()))throw new IllegalArgumentException("Explicit HTTPS root endpoint required");
    if(!required("R2_CONFIRM_TARGET").equals(base.getHost()))throw new IllegalArgumentException("Target confirmation mismatch");
    String key=required("R2_KEY_ID"),secret=required("R2_KEY_SECRET"),run=required("R2_RUN_ID");if(!run.matches("[A-Za-z0-9-]{1,50}"))throw new IllegalArgumentException("Invalid run ID");
    int seconds=Integer.parseInt(required("R2_SECONDS")),payRate=Integer.parseInt(required("R2_PAY_RATE")),readRate=Integer.parseInt(required("R2_READ_RATE"));
    if(seconds<1||seconds>3600||payRate<0||payRate>2000||readRate<0||readRate>8000||payRate+readRate<1)throw new IllegalArgumentException("Workload outside bounded R2 window");
    var pool=new ArrayBlockingQueue<String>(10000);String seed=System.getenv("R2_QUERY_IDS");if(seed!=null)for(String id:Files.readAllLines(Path.of(seed))){if(!id.matches("[A-Za-z0-9_-]{1,64}")||!pool.offer(id))throw new IllegalArgumentException("At most10000 reviewed query IDs");}
    if(readRate>0&&pool.isEmpty())throw new IllegalArgumentException("Query load requires a credential-owned source pool");
    var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();var slots=new Semaphore(128);var completed=new AtomicLong();var acceptedPay=new AtomicLong();var acceptedRead=new AtomicLong();var failed=new AtomicLong();var unsent=new AtomicLong();var sequence=new AtomicLong();var latency=new AtomicLongArray(12);long started=System.nanoTime();
    Path output=Path.of(required("R2_OUTPUT"));Files.createDirectories(output);Path report=output.resolve(run+".json");if(Files.exists(report))throw new IllegalArgumentException("Run ID already recorded");
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
      for(int second=0;second<seconds;second++){
        for(int tick=0;tick<1000;tick++){
          long due=started+TimeUnit.SECONDS.toNanos(second)+TimeUnit.MILLISECONDS.toNanos(tick),wait=due-System.nanoTime();if(wait>0)TimeUnit.NANOSECONDS.sleep(wait);
          int pay=(tick+1)*payRate/1000-tick*payRate/1000,reads=(tick+1)*readRate/1000-tick*readRate/1000;
          for(int i=0;i<pay+reads;i++){
            if(!slots.tryAcquire()){unsent.incrementAndGet();continue;}boolean payment=i<pay;long index=sequence.incrementAndGet();String id=payment?null:pool.poll();if(!payment){if(id==null){slots.release();unsent.incrementAndGet();continue;}pool.offer(id);}String path=payment?"/api/v1/payments":"/api/v1/payments/"+id;String body=payment?"{\"merchantOrderNo\":\""+run+"-"+index+"\",\"amountMinor\":\"10000\",\"currency\":\"CNY\",\"channel\":\"MOCK_A\"}":"";
            executor.submit(()->{long sent=System.nanoTime();try{var response=http.send(request(base,payment?"POST":"GET",path,body,key,secret),HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100==2){if(payment){acceptedPay.incrementAndGet();var matcher=ID.matcher(response.body());if(matcher.find())pool.offer(matcher.group(1));}else acceptedRead.incrementAndGet();}else failed.incrementAndGet();}catch(Exception failure){failed.incrementAndGet();}finally{long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-sent);int bucket=0;while(bucket<11&&millis>(1L<<bucket))bucket++;latency.incrementAndGet(bucket);completed.incrementAndGet();slots.release();}});
          }
        }
      }
    }finally{http.close();}
    String buckets=java.util.stream.IntStream.range(0,12).mapToObj(i->Long.toString(latency.get(i))).collect(java.util.stream.Collectors.joining(","));
    String json="{\"status\":\"MEASURED_NOT_CERTIFIED\",\"runId\":\""+run+"\",\"seconds\":"+seconds+",\"acceptedPayments\":"+acceptedPay+",\"successfulQueries\":"+acceptedRead+",\"errors\":"+failed+",\"generatorUnsent\":"+unsent+",\"completedRequests\":"+completed+",\"latencyPowerOfTwoMilliseconds\":["+buckets+"],\"moneyCompletionVerified\":false,\"r2Certified\":false}";
    Files.writeString(report,json,StandardOpenOption.CREATE_NEW);System.out.println("Measurement saved; authoritative money/source, resource, failover and twelve-hour acceptance remain required.");
  }
}
