package dev.merchantpay.platform;

import dev.merchantpay.platform.channel.ChannelApi;
import dev.merchantpay.platform.channel.ChannelBillReader;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.service.registry.ImportHttpServices;

@SpringBootApplication
@EnableScheduling
@ImportHttpServices(group = "mock-channel", types = ChannelApi.class)
public class PlatformApplication {
  public static void main(String[] args) {
    SpringApplication.run(PlatformApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder
      channelHttpRequestFactoryBuilder(
          @Value("${merchantpay.channel-http-connections-per-route:64}") int perRoute) {
    if (perRoute < 1 || perRoute > 256) {
      throw new IllegalArgumentException("Channel HTTP connections per route must be 1..256");
    }
    return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.httpComponents()
        .withConnectionManagerCustomizer(
            manager -> manager.setMaxConnPerRoute(perRoute).setMaxConnTotal(perRoute * 2))
        .withDefaultRequestConfigCustomizer(
            config ->
                config.setConnectionRequestTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(2)));
  }

  @Bean
  ChannelBillReader channelBillReader(
      RestClient.Builder builder,
      @Value("${spring.http.serviceclient.mock-channel.base-url}") String base,
      @Value("${merchantpay.channel-token}") String token) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(Duration.ofSeconds(30));
    return new ChannelBillReader(
        builder.clone().baseUrl(base).requestFactory(factory).build(), token);
  }

  @Bean
  TransactionTemplate transactions(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }
}
