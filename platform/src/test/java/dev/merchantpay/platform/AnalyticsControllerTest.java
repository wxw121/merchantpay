package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import dev.merchantpay.platform.sharding.ShardProjectionReadGate;
import dev.merchantpay.platform.web.Access;
import dev.merchantpay.platform.web.AnalyticsController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnalyticsControllerTest {
  private final Access access = mock(Access.class);
  private final ShardProjectionReadGate gate = mock(ShardProjectionReadGate.class);
  private final ClickHouseReadModel clickhouse = mock(ClickHouseReadModel.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
  private final AnalyticsController controller =
      new AnalyticsController(access, gate, clickhouse, clock);

  @Test
  void merchantScopeAndVerifiedGenerationBoundAggregateRead() {
    when(access.merchant("merchant-1", true)).thenReturn("merchant-1");
    when(gate.generation()).thenReturn("generation-7");
    when(clickhouse.query(anyString(), anyMap()))
        .thenReturn(
            List.of(
                Map.ofEntries(
                    Map.entry("label", "2026-11-04"),
                    Map.entry("payments", 2),
                    Map.entry("succeeded", 1),
                    Map.entry("succeeded_minor", "100"),
                    Map.entry("refunded_minor", "25"),
                    Map.entry("latency_samples", 1),
                    Map.entry("avg_success_latency_ms", 120),
                    Map.entry("p99_success_latency_ms", 120))));

    var report = controller.payments("merchant-1", null, null, "DAY");

    assertThat(report.generation()).isEqualTo("generation-7");
    assertThat(report.rows()).hasSize(1);
    assertThat(report.sourceWatermarkStatus()).isEqualTo("UNVERIFIED");
    assertThat(report.rows().getFirst())
        .containsEntry("success_rate_pct", new java.math.BigDecimal("50.00"))
        .containsEntry("refund_rate_pct", new java.math.BigDecimal("25.00"))
        .containsEntry("latency_samples", 1);
    var values = ArgumentCaptor.forClass(Map.class);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(clickhouse).query(sql.capture(), values.capture());
    assertThat(sql.getValue())
        .contains("succeeded_at", "quantileTDigestIf", "generation={generation:String}");
    assertThat(values.getValue())
        .containsEntry("merchant", "merchant-1")
        .containsEntry("generation", "generation-7");
    verify(access).require("ADMIN", "OPS", "FINANCE", "AUDITOR", "MERCHANT");
  }

  @Test
  void absentLatencyAndZeroDenominatorsStayUnknown() {
    when(access.merchant("merchant-1", true)).thenReturn("merchant-1");
    when(gate.generation()).thenReturn("generation-7");
    when(clickhouse.query(anyString(), anyMap()))
        .thenReturn(
            List.of(
                Map.of(
                    "label",
                    "2026-11-04",
                    "payments",
                    0,
                    "succeeded",
                    0,
                    "succeeded_minor",
                    "0",
                    "refunded_minor",
                    "0",
                    "latency_samples",
                    0,
                    "avg_success_latency_ms",
                    0,
                    "p99_success_latency_ms",
                    0)));

    var row = controller.payments("merchant-1", null, null, "DAY").rows().getFirst();

    assertThat(row)
        .containsEntry("success_rate_pct", null)
        .containsEntry("refund_rate_pct", null)
        .containsEntry("avg_success_latency_ms", null)
        .containsEntry("p99_success_latency_ms", null);
  }

  @Test
  void rejectsTruncatedChannelDistribution() {
    when(access.merchant("merchant-1", true)).thenReturn("merchant-1");
    when(gate.generation()).thenReturn("generation-7");
    when(clickhouse.query(anyString(), anyMap()))
        .thenReturn(java.util.Collections.nCopies(101, Map.<String, Object>of("label", "channel")));

    assertThatThrownBy(() -> controller.payments("merchant-1", null, null, "CHANNEL"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("无法完整展示");
  }

  @Test
  void rejectsGenerationSwitchDuringQuery() {
    when(access.merchant("merchant-1", true)).thenReturn("merchant-1");
    when(gate.generation()).thenReturn("old", "new");
    when(clickhouse.query(anyString(), anyMap())).thenReturn(List.of());
    assertThatThrownBy(() -> controller.payments("merchant-1", null, null, "CHANNEL"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("投影代已切换");
  }
}
