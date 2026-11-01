package dev.merchantpay.platform.common;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Cancels before retaining bytes beyond the configured transport budget. */
public final class BoundedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
  private final int maximum;
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private final ByteArrayOutputStream output = new ByteArrayOutputStream();
  private Flow.Subscription subscription;

  public BoundedHttpBody(int maximum) {
    if (maximum < 1 || maximum > 1048576) throw new IllegalArgumentException("Invalid body limit");
    this.maximum = maximum;
  }

  public CompletionStage<byte[]> getBody() {
    return body;
  }

  public void onSubscribe(Flow.Subscription value) {
    subscription = value;
    value.request(1);
  }

  public void onNext(List<ByteBuffer> buffers) {
    for (var buffer : buffers) {
      if (buffer.remaining() > maximum - output.size()) {
        subscription.cancel();
        body.completeExceptionally(new IOException("Response exceeded transport budget"));
        return;
      }
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      output.writeBytes(bytes);
    }
    subscription.request(1);
  }

  public void onError(Throwable failure) {
    body.completeExceptionally(failure);
  }

  public void onComplete() {
    body.complete(output.toByteArray());
  }
}
