package dev.merchantpay.platform.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Time policy only; persisted tasks and the existing channel controls own execution. */
public final class TaskRecovery {
  private TaskRecovery() {}

  public static final Duration NOTIFICATION_WINDOW = Duration.ofHours(24);

  public static boolean notification(String kind) {
    return Set.of(
            "PAYMENT_SUCCEEDED",
            "PAYMENT_FAILED",
            "PAYMENT_CLOSED",
            "REFUND_SUCCEEDED",
            "REFUND_FAILED")
        .contains(kind);
  }

  public static boolean channel(String kind) {
    return Set.of("CHANNEL_PAYMENT", "CHANNEL_REFUND", "CHANNEL_CLOSE").contains(kind);
  }

  public static String stage(Instant since, Instant now) {
    if (since == null) return "STANDARD";
    long age = Duration.between(since, now).getSeconds();
    if (age >= 86400) return "LONG_TERM";
    if (age >= 1800) return "MANUAL";
    if (age >= 120) return "ALERT";
    return "PRIORITY";
  }

  public static Duration queryDelay(int attempts) {
    long maximum = Math.min(300, 1L << Math.clamp(attempts - 1, 0, 9)) * 1000;
    long minimum = Math.max(1000, maximum / 2);
    return Duration.ofMillis(ThreadLocalRandom.current().nextLong(minimum, maximum + 1));
  }
}
