package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.BoundedHttpBody;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class BoundedHttpBodyTest {
  @Test
  void exceedingLimitCancelsBeforeKeepingTheOversizedChunk() {
    var body = new BoundedHttpBody(4);
    var flow = mock(Flow.Subscription.class);
    body.onSubscribe(flow);
    body.onNext(List.of(ByteBuffer.wrap(new byte[5])));
    verify(flow).cancel();
    assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
  }

  @Test
  void exactLimitCompletesWithoutChangingBytes() {
    var body = new BoundedHttpBody(4);
    body.onSubscribe(mock(Flow.Subscription.class));
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4})));
    body.onComplete();
    assertThat(body.getBody().toCompletableFuture().join()).containsExactly(1, 2, 3, 4);
  }
}
