package dev.merchantpay.platform.outbox;

import java.util.Set;

/** Shared with the standalone JDBC maintenance command; no application runtime is needed there. */
final class EventKinds {
  private static final Set<String> TASKABLE =
      Set.of(
          "CHANNEL_PAYMENT",
          "CHANNEL_REFUND",
          "CHANNEL_CLOSE",
          "PROCESS_INBOX",
          "PAYMENT_SUCCEEDED",
          "PAYMENT_FAILED",
          "PAYMENT_CLOSED",
          "REFUND_SUCCEEDED",
          "REFUND_FAILED",
          "RECONCILE",
          "EXPORT");

  static boolean taskable(String kind) {
    return TASKABLE.contains(kind);
  }
}
