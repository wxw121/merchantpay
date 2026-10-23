package dev.merchantpay.platform.reconciliation;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Immutable attempt objects; database pointers decide which completed attempt is visible. */
@Component
public class ObjectStorage implements AutoCloseable {
  private final Path directory;
  private final S3Client s3;
  private final String bucket;

  @Autowired
  public ObjectStorage(
      @Value("${merchantpay.data-directory}") String directory,
      @Value("${merchantpay.storage.type:local}") String type,
      @Value("${merchantpay.storage.s3.endpoint:}") String endpoint,
      @Value("${merchantpay.storage.s3.bucket:}") String bucket,
      @Value("${merchantpay.storage.s3.region:us-east-1}") String region,
      @Value("${merchantpay.storage.s3.path-style:false}") boolean pathStyle) {
    this.directory = Path.of(directory).toAbsolutePath().normalize();
    this.bucket = bucket;
    if (type.equals("local")) {
      s3 = null;
    } else if (type.equals("s3") && !bucket.isBlank()) {
      var builder =
          S3Client.builder()
              .region(Region.of(region))
              .forcePathStyle(pathStyle)
              .httpClientBuilder(
                  UrlConnectionHttpClient.builder()
                      .connectionTimeout(Duration.ofSeconds(3))
                      .socketTimeout(Duration.ofSeconds(30)))
              .overrideConfiguration(
                  configuration ->
                      configuration
                          .apiCallAttemptTimeout(Duration.ofSeconds(60))
                          .apiCallTimeout(Duration.ofMinutes(5)));
      if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
      s3 = builder.build();
    } else {
      throw new IllegalArgumentException("Storage requires type local or s3 with a bucket");
    }
  }

  public ObjectStorage(String directory) {
    this.directory = Path.of(directory).toAbsolutePath().normalize();
    this.s3 = null;
    this.bucket = "";
  }

  public ObjectStorage(Path directory, S3Client s3, String bucket) {
    this.directory = directory.toAbsolutePath().normalize();
    this.s3 = s3;
    this.bucket = bucket;
  }

  public Path temporary(String prefix) throws IOException {
    Path staging = directory.resolve(".staging");
    Files.createDirectories(staging);
    return Files.createTempFile(staging, prefix, ".part");
  }

  private static String validate(String pointer) {
    String key = pointer.startsWith("s3:") ? pointer.substring(3) : pointer;
    if (!key.matches(
        "bills/[a-f0-9]{64}(-[a-f0-9-]{36})?\\.csv|exports/[a-f0-9-]{36}-[a-f0-9-]{36}\\.zip")) {
      throw new IllegalStateException("Invalid stored object key");
    }
    return key;
  }

  private static String readKey(String pointer) {
    if (pointer.matches("exports/[a-f0-9-]{36}\\.zip")) return pointer;
    return validate(pointer);
  }

  public String publish(Path source, String key) throws IOException {
    validate(key);
    if (key.startsWith("s3:")) throw new IllegalArgumentException("Publish requires an object key");
    if (s3 == null) {
      Path destination = directory.resolve(key);
      Files.createDirectories(destination.getParent());
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
      return key;
    }
    try {
      s3.putObject(
          request ->
              request
                  .bucket(bucket)
                  .key(key)
                  .ifNoneMatch("*")
                  .contentType(key.endsWith(".zip") ? "application/zip" : "text/csv;charset=UTF-8"),
          RequestBody.fromFile(source));
      return "s3:" + key;
    } catch (SdkException failure) {
      throw new IOException("Object publication failed", failure);
    }
  }

  public InputStream open(String pointer) throws IOException {
    String key = readKey(pointer);
    if (!pointer.startsWith("s3:")) return Files.newInputStream(directory.resolve(key));
    if (s3 == null) throw new IOException("S3 object requires configured S3 storage");
    try {
      return s3.getObject(request -> request.bucket(bucket).key(key));
    } catch (SdkException failure) {
      throw new IOException("Stored object unavailable", failure);
    }
  }

  /**
   * Opens before the HTTP response/audit; a missing object cannot become an empty successful file.
   */
  public InputStreamResource resource(String pointer) throws IOException {
    String key = readKey(pointer);
    if (!pointer.startsWith("s3:")) {
      long length = Files.size(directory.resolve(key));
      return resource(open(pointer), length);
    }
    if (s3 == null) throw new IOException("S3 object requires configured S3 storage");
    try {
      var input = s3.getObject(request -> request.bucket(bucket).key(key));
      return resource(input, input.response().contentLength());
    } catch (SdkException failure) {
      throw new IOException("Stored object unavailable", failure);
    }
  }

  private static InputStreamResource resource(InputStream input, long length) {
    return new InputStreamResource(input) {
      @Override
      public long contentLength() {
        return length;
      }
    };
  }

  public Path localPath(String pointer) {
    String key = readKey(pointer);
    if (pointer.startsWith("s3:")) throw new IllegalStateException("S3 objects have no local path");
    Path path = directory.resolve(key);
    try {
      Files.size(path);
      return path;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  @Override
  public void close() {
    if (s3 != null) s3.close();
  }
}
