package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import dev.merchantpay.platform.channel.ChannelBillReader;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ChannelHttpContractTest {
  @Test
  void billClientExplicitlyAcceptsCsvInsteadOfTheJsonReceiptContract() {
    var builder = RestClient.builder().baseUrl("http://channel.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "http://channel.test/channels/MOCK_A/bill?merchantId=merchant-a&day=2026-10-18"))
        .andExpect(header(HttpHeaders.ACCEPT, "text/csv"))
        .andRespond(withSuccess("#merchantpay-bill-v1", MediaType.parseMediaType("text/csv")));
    var reader = new ChannelBillReader(builder.build(), "test");
    assertThat(
            reader.<String>read(
                "MOCK_A", "merchant-a", "2026-10-18", input -> new String(input.readNBytes(100))))
        .startsWith("#merchantpay-bill-v1");
    server.verify();
  }

  @Test
  void consumerReadsInsideResponseScopeWithoutBufferingTheWholeBill() {
    var builder = RestClient.builder().baseUrl("http://channel.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var reads = new AtomicInteger();
    InputStream stream =
        new InputStream() {
          @Override
          public int read() {
            if (reads.incrementAndGet() > 8) throw new AssertionError("Bill eagerly buffered");
            return 'x';
          }
        };
    server
        .expect(anything())
        .andRespond(request -> new MockClientHttpResponse(stream, HttpStatus.OK));
    var reader = new ChannelBillReader(builder.build(), "test");
    assertThat(
            reader.<Integer>read(
                "MOCK_A", "merchant-a", "2026-10-18", input -> input.readNBytes(8).length))
        .isEqualTo(8);
    assertThat(reads.get()).isEqualTo(8);
    server.verify();
  }
}
