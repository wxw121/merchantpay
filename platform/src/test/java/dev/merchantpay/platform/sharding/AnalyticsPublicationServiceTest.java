package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class AnalyticsPublicationServiceTest {
  @Test
  void unsignedOrAlteredReleaseCannotReachSourceDatabases() {
    var projection = mock(ShardProjectionReadGate.class);
    var routes = mock(ShardExportReadGate.class);
    var core = mock(JdbcTemplate.class);
    var service =
        new AnalyticsPublicationService(
            projection,
            routes,
            core,
            mock(TransactionTemplate.class),
            JsonMapper.builder().build(),
            Clock.systemUTC(),
            "a-32-byte-publication-secret-for-tests");
    var operator = new Actor("ops", null, Set.of("OPS"));
    assertThatThrownBy(() -> service.publish("{\"generation\":\"g1\"}", "0".repeat(64), operator))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("签名无效");
    verifyNoInteractions(projection, routes, core);
  }
}
