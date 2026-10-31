package dev.merchantpay.platform.common;

import java.time.Instant;
import java.util.UUID;

/** RFC 9562 UUIDv7 for locality in large InnoDB primary-key indexes. */
public final class TimeOrderedIds {
  private TimeOrderedIds() {}

  public static String uuidV7(Instant time) {
    long milliseconds = time.toEpochMilli();
    UUID random = UUID.randomUUID();
    long high =
        ((milliseconds & 0xffffffffffffL) << 16)
            | 0x7000L
            | (random.getMostSignificantBits() & 0xfffL);
    return new UUID(high, random.getLeastSignificantBits()).toString();
  }
}
