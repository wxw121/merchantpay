package dev.merchantpay.platform.outbox;

/** Confirms that a reconciliation task may be queued against its authoritative source. */
public interface OutboxReconcileGate {
  void requireReadable();
}
