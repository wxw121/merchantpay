import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Read-only verification of an existing local restored bucket. */
class VerifyS3 {
  public static void main(String[] args) throws Exception {
    var endpoint=URI.create(System.getenv("S3_ENDPOINT"));
    if(!endpoint.getHost().equals("127.0.0.1")||endpoint.getPort()!=19003)throw new IllegalArgumentException("Only the isolated restored bucket is permitted");
    try(var client=S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5))).overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(10))).build()) {
      Exception last=null;
      for(int attempt=0;attempt<15;attempt++) {
        try {
          client.headBucket(r->r.bucket(System.getenv("S3_BUCKET")));
          var bytes=client.getObjectAsBytes(r->r.bucket(System.getenv("S3_BUCKET")).key("recovery-probe/v1.txt")).asByteArray();
          if(!java.util.Arrays.equals(bytes,"merchantpay-v1-backup-proof\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new IllegalStateException("Restored object content mismatch");
          System.out.println("Restored existing S3 bucket and exact recovery object bytes are verified.");return;
        }
        catch(Exception failure){last=failure;Thread.sleep(1000);}
      }
      throw last;
    }
  }
}
