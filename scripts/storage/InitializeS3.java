import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Creates only the configured local demonstration bucket through the real S3 API. */
class InitializeS3 {
  public static void main(String[] args) {
    var endpoint = URI.create(System.getenv("S3_ENDPOINT"));
    if (!endpoint.getScheme().equals("http") || !java.util.Set.of("127.0.0.1", "localhost").contains(endpoint.getHost()))
      throw new IllegalArgumentException("This bootstrap supports loopback demonstration storage only");
    String bucket = System.getenv("S3_BUCKET");
    try (var client = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).forcePathStyle(true)
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)))
        .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))).build()) {
      try { client.headBucket(request -> request.bucket(bucket)); }
      catch (S3Exception missing) {
        if (missing.statusCode() != 404) throw missing;
        client.createBucket(request -> request.bucket(bucket));
      }
      client.headBucket(request -> request.bucket(bucket));
      System.out.println("Local S3 bucket is available; existing objects were preserved.");
    }
  }
}
