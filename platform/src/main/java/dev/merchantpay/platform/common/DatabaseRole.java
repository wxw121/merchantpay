package dev.merchantpay.platform.common;

/**
 * A lexical role selects a connection pool; transaction-bound JDBC connections remain
 * authoritative.
 */
public final class DatabaseRole {
  private static final ScopedValue<Boolean> WORKER = ScopedValue.newInstance();

  private DatabaseRole() {}

  public static boolean isWorker() {
    return WORKER.isBound() && WORKER.get();
  }

  public static void worker(Runnable action) {
    ScopedValue.where(WORKER, true).run(action);
  }
}
