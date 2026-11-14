package dev.merchantpay.platform.history;

import dev.merchantpay.platform.common.Signatures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;

/** A bounded, versioned Parquet container for immutable payment-root facts. */
public final class HistoryParquetCodec {
  public record Fact(
      String kind,
      String merchantId,
      String paymentId,
      String factId,
      long occurredMicros,
      String payloadHash,
      String payloadJson) {}

  public record Written(long rows, long bytes, String sha256) {}

  private static final int MAX_FACTS = 20_000;
  private static final long MAX_INPUT_BYTES = 32L * 1024 * 1024;
  private static final Schema SCHEMA =
      new Schema.Parser()
          .parse(
              """
      {"type":"record","name":"PaymentHistoryFactV1","namespace":"dev.merchantpay.history",
       "fields":[
         {"name":"kind","type":"string"},
         {"name":"merchant_id","type":"string"},
         {"name":"payment_id","type":"string"},
         {"name":"fact_id","type":"string"},
         {"name":"occurred_micros","type":"long"},
         {"name":"payload_hash","type":"string"},
         {"name":"payload_json","type":"string"}
       ]}
      """);

  private HistoryParquetCodec() {}

  public static Written write(Path file, List<Fact> facts) throws IOException {
    if (facts == null || facts.isEmpty() || facts.size() > MAX_FACTS)
      throw new IllegalArgumentException("A history object requires 1..20000 facts");
    long inputBytes = 0;
    for (var fact : facts) {
      validate(fact);
      inputBytes += fact.payloadJson().getBytes(StandardCharsets.UTF_8).length;
      if (inputBytes > MAX_INPUT_BYTES)
        throw new IOException("History object exceeds input size limit");
    }
    if (Files.exists(file)) throw new IOException("History object path already exists");
    try (var writer =
        AvroParquetWriter.<GenericRecord>builder(new LocalOutputFile(file))
            .withSchema(SCHEMA)
            .withDataModel(GenericData.get())
            .withCompressionCodec(CompressionCodecName.SNAPPY)
            .build()) {
      for (var fact : facts) {
        var row = new GenericData.Record(SCHEMA);
        row.put("kind", fact.kind());
        row.put("merchant_id", fact.merchantId());
        row.put("payment_id", fact.paymentId());
        row.put("fact_id", fact.factId());
        row.put("occurred_micros", fact.occurredMicros());
        row.put("payload_hash", fact.payloadHash());
        row.put("payload_json", fact.payloadJson());
        writer.write(row);
      }
    } catch (IOException | RuntimeException failure) {
      Files.deleteIfExists(file);
      throw failure;
    }
    return new Written(facts.size(), Files.size(file), sha256(file));
  }

  public static List<Fact> read(Path file, String expectedSha256) throws IOException {
    if (Files.size(file) > 64L * 1024 * 1024)
      throw new IOException("History object exceeds stored size limit");
    if (expectedSha256 == null || !expectedSha256.equals(sha256(file)))
      throw new IOException("History Parquet object hash does not match the manifest");
    var facts = new ArrayList<Fact>();
    try (var reader =
        AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
            .withDataModel(GenericData.get())
            .build()) {
      GenericRecord row;
      while ((row = reader.read()) != null) {
        if (facts.size() == MAX_FACTS) throw new IOException("History object exceeds row limit");
        var fact =
            new Fact(
                text(row, "kind"),
                text(row, "merchant_id"),
                text(row, "payment_id"),
                text(row, "fact_id"),
                (Long) row.get("occurred_micros"),
                text(row, "payload_hash"),
                text(row, "payload_json"));
        validate(fact);
        facts.add(fact);
      }
    }
    return List.copyOf(facts);
  }

  private static String text(GenericRecord row, String name) {
    return row.get(name).toString();
  }

  private static void validate(Fact fact) throws IOException {
    if (fact == null
        || fact.kind() == null
        || !Set.of("PAYMENT", "REFUND", "JOURNAL", "ENTRY", "EVENT").contains(fact.kind())
        || fact.merchantId() == null
        || fact.merchantId().isBlank()
        || fact.paymentId() == null
        || fact.paymentId().isBlank()
        || fact.factId() == null
        || fact.factId().isBlank()
        || fact.occurredMicros() < 0
        || fact.payloadJson() == null
        || fact.payloadJson().length() > 1_000_000
        || fact.payloadHash() == null
        || !fact.payloadHash().matches("[a-f0-9]{64}")
        || !fact.payloadHash().equals(Signatures.sha256(fact.payloadJson())))
      throw new IOException("Invalid history fact");
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    try (var input = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      int n;
      while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
