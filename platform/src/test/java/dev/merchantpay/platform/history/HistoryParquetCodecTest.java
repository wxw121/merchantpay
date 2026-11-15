package dev.merchantpay.platform.history;

import static org.assertj.core.api.Assertions.*;

import dev.merchantpay.platform.common.Signatures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryParquetCodecTest {
  @TempDir Path directory;

  @Test
  void roundTripAndObjectHashFence() throws Exception {
    String payload = "{\"id\":\"p4_01\",\"amountMinor\":100}";
    var fact =
        new HistoryParquetCodec.Fact(
            "PAYMENT",
            "merchant",
            "p4_01",
            "p4_01",
            1_700_000_000_000_000L,
            Signatures.sha256(payload),
            payload);
    Path file = directory.resolve("root.parquet");
    var written = HistoryParquetCodec.write(file, List.of(fact));
    assertThat(written.rows()).isEqualTo(1);
    assertThat(written.bytes()).isPositive();
    assertThat(HistoryParquetCodec.read(file, written.sha256())).containsExactly(fact);
    assertThatThrownBy(() -> HistoryParquetCodec.read(file, "0".repeat(64)))
        .hasMessageContaining("hash");
    Files.write(file, new byte[] {1, 2, 3});
    assertThatThrownBy(() -> HistoryParquetCodec.read(file, written.sha256()))
        .hasMessageContaining("hash");
  }

  @Test
  void rejectsFactWithWrongPayloadHashBeforePublishing() {
    var bad =
        new HistoryParquetCodec.Fact(
            "EVENT", "merchant", "p4_01", "event", 1, "0".repeat(64), "{}");
    Path file = directory.resolve("bad.parquet");
    assertThatThrownBy(() -> HistoryParquetCodec.write(file, List.of(bad)))
        .hasMessageContaining("Invalid history fact");
    assertThat(file).doesNotExist();
  }
}
