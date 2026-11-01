package dev.merchantpay.platform.projection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class ClickHouseBatchTest {
  @Test
  void largerClaimIsSplitIntoBoundedHttpRequests() {
    var target =
        new ClickHouseReadModel(
            JsonMapper.builder().build(), true, "http://127.0.0.1:18123/", "user", "secret");
    var old = (HttpClient) ReflectionTestUtils.getField(target, "http");
    old.shutdownNow();
    var http = mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(new byte[0]);
    when(http.sendAsync(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
        .thenReturn(CompletableFuture.completedFuture(response));
    ReflectionTestUtils.setField(target, "http", http);
    try {
      var row = Map.<String, Object>of("snapshot", "中".repeat(140000));
      target.write(List.of(row, row, row));
      var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
      verify(http, times(2))
          .sendAsync(
              requests.capture(),
              org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
      assertThat(requests.getAllValues())
          .allSatisfy(
              request ->
                  assertThat(request.bodyPublisher().orElseThrow().contentLength())
                      .isBetween(1L, 1048576L));
    } finally {
      target.close();
    }
  }
}
