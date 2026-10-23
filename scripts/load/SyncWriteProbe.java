import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Bounded storage diagnostic: only a newly created private temporary file is written/deleted. */
class SyncWriteProbe {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]).toAbsolutePath().normalize().toRealPath();
    int count = Integer.parseInt(args[1]);
    if (count < 32 || count > 4096 || !Files.isDirectory(root)) throw new IllegalArgumentException("Use an existing directory and 32..4096 samples");
    boolean force = Boolean.parseBoolean(args[2]);
    Path file = Files.createTempFile(root, "merchantpay-sync-probe-", ".tmp");
    if (!file.getParent().equals(root)) throw new IllegalStateException("Probe escaped its directory");
    long[] micros = new long[count];
    try {
      try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
        var bytes = ByteBuffer.allocateDirect(4096);
        for (int sample = 0; sample < count; sample++) {
          bytes.clear();
          long started = System.nanoTime();
          while (bytes.hasRemaining()) channel.write(bytes);
          if (force) channel.force(false);
          micros[sample] = (System.nanoTime() - started) / 1000;
        }
        channel.force(true);
      }
    } finally {
      Files.deleteIfExists(file);
    }
    long[] sorted = micros.clone(); Arrays.sort(sorted);
    System.out.println("{\"samples\":" + count + ",\"bytesPerWrite\":4096,\"forceEachWrite\":" + force
        + ",\"p50Micros\":" + sorted[(int)Math.ceil(count*.50)-1] + ",\"p95Micros\":" + sorted[(int)Math.ceil(count*.95)-1]
        + ",\"p99Micros\":" + sorted[(int)Math.ceil(count*.99)-1] + ",\"maxMicros\":" + sorted[count-1]
        + ",\"totalMicros\":" + Arrays.stream(micros).sum() + ",\"privateFileRemoved\":" + !Files.exists(file) + ",\"rawMicros\":" + Arrays.toString(micros) + "}");
  }
}
