package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.*;
import dev.merchantpay.platform.merchant.MerchantService;
import java.sql.Timestamp;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationKeyTest extends FinancialFixture {
  @Test
  void oneQueryReturnsNewestValidKeyAndImmediatelyObservesRevocationAndExpiry() {
    var vault = new KeyVault("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    Instant now = Instant.now();
    for (int i = 1; i <= 4; i++)
      jdbc.update(
          "INSERT INTO merchant_keys(id,merchant_id,secret_cipher,enabled,created_at,expires_at) VALUES (?,'merchant-a',?,?,?,?)",
          "key-" + i,
          vault.encrypt("secret-" + i),
          i != 3,
          Timestamp.from(now.plusSeconds(i)),
          i == 4 ? Timestamp.from(now.minusSeconds(1)) : null);
    var tracked = spy(jdbc);
    var service =
        new MerchantService(
            tracked,
            vault,
            new NotificationPolicy(""),
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
            Clock.fixed(now, ZoneOffset.UTC));
    assertThat(service.notificationKey("merchant-a").secret()).isEqualTo("secret-2");
    assertThat(
            mockingDetails(tracked).getInvocations().stream()
                .filter(
                    call ->
                        call.getMethod().getName().equals("query")
                            && java.util.Arrays.equals(
                                call.getMethod().getParameterTypes(),
                                new Class<?>[] {
                                  String.class,
                                  org.springframework.jdbc.core.RowMapper.class,
                                  Object[].class
                                }))
                .count())
        .isEqualTo(1);
    jdbc.update("UPDATE merchant_keys SET enabled=FALSE WHERE id='key-2'");
    assertThat(service.notificationKey("merchant-a").keyId()).isEqualTo("key-1");
    jdbc.update("UPDATE merchant_keys SET enabled=FALSE WHERE id='key-1'");
    assertThatThrownBy(() -> service.notificationKey("merchant-a"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("通知密钥");
  }
}
