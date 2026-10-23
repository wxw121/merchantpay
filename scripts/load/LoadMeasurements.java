import java.util.*;
import java.util.concurrent.TimeUnit;

/** Per business key measurements; all times use the generator's monotonic clock. */
final class LoadMeasurements {
  record Fact(int index, String status, boolean posted) {}
  static final class Request {
    final long due;
    volatile boolean sent;
    volatile long started, finished;
    volatile int response; // 0: not sent/in flight; -1: transport exception
    String retryReason;
    boolean persisted, complete;
    String paymentStatus;
    long completionLower, completionUpper;
    Request(long due) { this.due = due; }
    boolean accepted() { return response / 100 == 2; }
  }
  final Request[] requests;
  Long previousSnapshotStart;
  long snapshots, snapshotNanos, maxSnapshotNanos, maxVisibilityIntervalNanos;

  LoadMeasurements(int count, int rate, long origin) {
    requests = new Request[count];
    for (int i = 0; i < count; i++) requests[i] = new Request(origin + (long)i * 1_000_000_000L / rate);
  }
  void sent(int index, long now) { requests[index].started = now; requests[index].sent = true; }
  void finished(int index, int response, long now) {
    requests[index].finished = now; requests[index].response = response;
  }
  void observed(List<Fact> facts, long started, long ended) {
    for (var fact : facts) {
      if (fact.index < 0 || fact.index >= requests.length || !requests[fact.index].sent)
        throw new IllegalStateException("Observed a payment outside the sent request set");
      var request = requests[fact.index];
      request.persisted = true; request.paymentStatus = fact.status;
      if (fact.posted && !request.complete) {
        request.completionLower = previousSnapshotStart == null ? request.started : Math.max(request.started, previousSnapshotStart);
        request.completionUpper = ended; request.complete = true;
        maxVisibilityIntervalNanos = Math.max(maxVisibilityIntervalNanos, ended - request.completionLower);
      }
    }
    previousSnapshotStart = started;
    snapshots++; recordObservationCost(started, ended);
  }
  void failedObservation(long started, long ended) {
    // No visibility claim is possible, but all time spent waiting is still measurement cost.
    recordObservationCost(started, ended);
  }
  private void recordObservationCost(long started, long ended) {
    snapshotNanos += ended - started;
    maxSnapshotNanos = Math.max(maxSnapshotNanos, ended - started);
  }
  long accepted() { return Arrays.stream(requests).filter(Request::accepted).count(); }
  long persisted() { return Arrays.stream(requests).filter(r -> r.persisted).count(); }
  long completed() { return Arrays.stream(requests).filter(r -> r.complete).count(); }
  static boolean failed(Request r) { return Set.of("FAILED", "CLOSED").contains(Objects.toString(r.paymentStatus, "")); }
  long unfinishedAccepted() { return Arrays.stream(requests).filter(r -> r.accepted() && !r.complete && !failed(r)).count(); }
  long failedAccepted() { return Arrays.stream(requests).filter(r -> r.accepted() && failed(r)).count(); }
  long unacknowledgedCommitted() { return Arrays.stream(requests).filter(r -> r.persisted && !r.accepted()).count(); }
  long windowCountLower(long end) {
    return Arrays.stream(requests).filter(r -> r.complete && r.completionUpper < end).count();
  }
  long windowCountUpper(long end) {
    return Arrays.stream(requests).filter(r -> r.sent && (r.complete ? r.completionLower < end :
        r.started < end && (previousSnapshotStart == null || previousSnapshotStart < end))).count();
  }
  List<Long> httpMicros() {
    return Arrays.stream(requests).filter(r -> r.sent && r.response != 0)
        .map(r -> TimeUnit.NANOSECONDS.toMicros(r.finished - r.started)).sorted().toList();
  }
  Long acceptedP99UpperMicros() {
    var values = Arrays.stream(requests).filter(Request::accepted)
        .map(r -> r.complete ? TimeUnit.NANOSECONDS.toMicros(r.completionUpper - r.due) : Long.MAX_VALUE).sorted().toList();
    Long value = LocalLoad.percentile(values, .99);
    return value == null || value == Long.MAX_VALUE ? null : value;
  }
  long oldestUnfinishedMicros(long now) {
    return Arrays.stream(requests).filter(r -> r.accepted() && !r.complete && !failed(r))
        .mapToLong(r -> TimeUnit.NANOSECONDS.toMicros(now - r.due)).max().orElse(0);
  }
}
