import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** One immutable, explicitly non-financial backup canary on local storage only. */
class BackupS3Probe {
  public static void main(String[] args) {
    try(var client=S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:19002")).region(Region.US_EAST_1).forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5))).overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(10))).build()) {
      String content="merchantpay-v1-backup-proof\n";
      try {client.putObject(r->r.bucket("merchantpay-v1").key("recovery-probe/v1.txt").ifNoneMatch("*"),RequestBody.fromString(content));}
      catch(S3Exception existing){if(existing.statusCode()!=412)throw existing;}
      if(!client.getObjectAsBytes(r->r.bucket("merchantpay-v1").key("recovery-probe/v1.txt")).asUtf8String().equals(content))throw new IllegalStateException("Backup canary mismatch");
      System.out.println("Immutable local S3 recovery object is available.");
    }
  }
}
