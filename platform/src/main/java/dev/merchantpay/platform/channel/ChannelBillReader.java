package dev.merchantpay.platform.channel;

import dev.merchantpay.platform.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Response ownership stays here; callers stream into bounded, hashed evidence storage. */
public final class ChannelBillReader {
  @FunctionalInterface
  public interface Consumer<T> {
    T accept(InputStream input) throws IOException;
  }

  private final RestClient client;
  private final String token;

  public ChannelBillReader(RestClient client, String token) {
    this.client = client;
    this.token = token;
  }

  public <T> T read(String channel, String merchant, String day, Consumer<T> consumer) {
    if (!"MOCK_A".equals(channel) && !"MOCK_B".equals(channel)) {
      throw new ApiException("INVALID_CHANNEL", 400, "不支持该渠道");
    }
    return client
        .get()
        .uri(
            builder ->
                builder
                    .path("/channels/{channel}/bill")
                    .queryParam("merchantId", merchant)
                    .queryParam("day", day)
                    .build(channel))
        .accept(MediaType.parseMediaType("text/csv"))
        .header("X-Channel-Token", token)
        .exchange(
            (request, response) -> {
              if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ApiException("BILL_CHANNEL_UNAVAILABLE", 502, "渠道账单暂不可用");
              }
              return consumer.accept(response.getBody());
            });
  }
}
