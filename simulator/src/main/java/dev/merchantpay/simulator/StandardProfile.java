package dev.merchantpay.simulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable by seed and business key, so concurrency and restarts cannot change channel truth. */
public final class StandardProfile {
  private final String mode;
  private final long seed;

  public StandardProfile(String mode, long seed) {
    if (!mode.equals("demo") && !mode.equals("standard-v1")) {
      throw new IllegalArgumentException("Channel profile must be demo or standard-v1");
    }
    this.mode = mode;
    this.seed = seed;
  }

  public boolean enabled() {
    return mode.equals("standard-v1");
  }

  public String descriptor() {
    return mode + "\t" + seed;
  }

  private int bucket(String dimension, String key) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest((seed + "\0" + dimension + "\0" + key).getBytes(StandardCharsets.UTF_8));
      return (int) (Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(hash).getInt()) % 10000);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public String status(String key) {
    if (enabled()) return bucket("status", key) < 200 ? "FAILED" : "SUCCEEDED";
    return key.startsWith("fail:")
        ? "FAILED"
        : key.startsWith("pending:") ? "PENDING" : "SUCCEEDED";
  }

  public boolean duplicate(String key) {
    return enabled() && bucket("duplicate", key) < 2000;
  }

  public long delayMillis(String key) {
    if (!enabled()) return 0;
    int bucket = bucket("delay", key);
    if (bucket < 5000) return 10 + bucket * 40L / 5000;
    if (bucket < 9500) return 50 + (bucket - 5000) * 150L / 4500;
    if (bucket < 9900) return 200 + (bucket - 9500) * 200L / 400;
    return 400 + (bucket - 9900) / 2;
  }

  public void delay(String key) {
    try {
      Thread.sleep(delayMillis(key));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Channel processing interrupted", interrupted);
    }
  }
}
