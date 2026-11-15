package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.projection.ClickHouseReadModel;
import dev.merchantpay.platform.sharding.AnalyticsPublicationService;
import dev.merchantpay.platform.sharding.ShardProjectionReadGate;
import dev.merchantpay.platform.web.Access;
import dev.merchantpay.platform.web.FlinkPreReconciliationController;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class FlinkPreReconciliationControllerTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 14);
  private static final Instant NOW = Instant.parse("2026-11-13T16:30:00Z");
  private static final String CHANNEL = "SIM_A";
  private static final Map<String, String> QUERY =
      Map.of("generation", "gen", "merchant", "merchant", "day", "2026-11-14", "channel", CHANNEL);

  private final Access access = mock(Access.class);
  private final ShardProjectionReadGate gate = mock(ShardProjectionReadGate.class);
  private final ClickHouseReadModel clickhouse = mock(ClickHouseReadModel.class);
  private final JdbcTemplate core = mock(JdbcTemplate.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final FlinkPreReconciliationController controller =
      new FlinkPreReconciliationController(access, gate, clickhouse, core, clock);

  @BeforeEach
  void scope() {
    when(access.merchant("merchant", true)).thenReturn("merchant");
    when(gate.generation()).thenReturn("gen");
  }

  @Test
  void comparesSuccessfulStreamPaymentsAgainstTheFullyProcessedBillAtVerifiedWatermark() {
    var publications = mock(AnalyticsPublicationService.class);
    ObjectProvider<AnalyticsPublicationService> provider = publicationsProvider(publications);
    var verified =
        new FlinkPreReconciliationController(access, gate, clickhouse, core, clock, provider);
    when(publications.current("gen"))
        .thenReturn(
            new AnalyticsPublicationService.Publication(
                "gen", "VERIFIED_AT_WATERMARK", "hash", NOW));
    when(clickhouse.query(anyString(), eq(QUERY)))
        .thenReturn(List.of(stream("275", "2026-11-14 00:15:00")));
    stubBill("COMPLETE", 6L);
    when(core.queryForMap(anyString(), eq("bill")))
        .thenReturn(Map.of("payment_rows", 2, "payment_minor", 200L));

    var preview = verified.preview("merchant", DAY, CHANNEL);

    assertThat(preview.consistency()).isEqualTo("PRELIMINARY_AT_VERIFIED_WATERMARK");
    assertThat(preview.stream())
        .isEqualTo(
            new FlinkPreReconciliationController.Totals(4, 3, 275, 25, "2026-11-14 00:15:00"));
    assertThat(preview.bill())
        .isEqualTo(new FlinkPreReconciliationController.Bill("bill", 2, "COMPLETE", 2, 200));
    assertThat(preview.countDelta()).isEqualTo(1L);
    assertThat(preview.minorDelta()).isEqualTo(75L);
    verify(access).require("ADMIN", "OPS", "FINANCE", "AUDITOR");
    verify(clickhouse).query(contains("AND deleted=0"), eq(QUERY));
    verify(core).queryForMap(contains("AND status='SUCCEEDED'"), eq("bill"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"RUNNING", "PRELIMINARY"})
  void withholdsBillComparisonWhenTheBatchIsUnfinished(String status) {
    when(clickhouse.query(anyString(), eq(QUERY)))
        .thenReturn(List.of(stream(275L, "2026-11-14 00:15:00")));
    stubBill(status, "RUNNING".equals(status) ? 6L : 5L);

    var preview = controller.preview("merchant", DAY, CHANNEL);

    assertThat(preview.consistency()).isEqualTo("PRELIMINARY_UNVERIFIED");
    assertThat(preview.stream()).isNotNull();
    assertThat(preview.bill()).isNull();
    assertThat(preview.countDelta()).isNull();
    assertThat(preview.minorDelta()).isNull();
    verify(core, never()).queryForMap(anyString(), any(Object[].class));
  }

  @Test
  void returnsUnknownDeltasWhenNoStreamRowHasArrived() {
    when(clickhouse.query(anyString(), eq(QUERY))).thenReturn(List.of());
    stubBill("PRELIMINARY", 6L);
    when(core.queryForMap(anyString(), eq("bill")))
        .thenReturn(Map.of("payment_rows", 0, "payment_minor", 0L));

    var preview = controller.preview("merchant", DAY, CHANNEL);

    assertThat(preview.bill().status()).isEqualTo("PRELIMINARY");
    assertThat(preview.stream()).isNull();
    assertThat(preview.countDelta()).isNull();
    assertThat(preview.minorDelta()).isNull();
  }

  @Test
  void rejectsMalformedSourceNumbersAndTimestamps() {
    when(clickhouse.query(anyString(), eq(QUERY)))
        .thenReturn(List.of(stream("2.75", "2026-11-14 00:15:00")), List.of(stream(275L, " ")));

    assertThatThrownBy(() -> controller.preview("merchant", DAY, CHANNEL))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("数值");
    assertThatThrownBy(() -> controller.preview("merchant", DAY, CHANNEL))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("时间");
    verifyNoInteractions(core);
  }

  @Test
  void rejectsAReportThatSpansTwoGenerations() {
    when(gate.generation()).thenReturn("gen", "new-gen");
    when(clickhouse.query(anyString(), eq(QUERY))).thenReturn(List.of());
    when(core.queryForList(anyString(), eq("merchant"), eq(CHANNEL), eq(Date.valueOf(DAY))))
        .thenReturn(List.of());

    assertThatThrownBy(() -> controller.preview("merchant", DAY, CHANNEL))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("投影代已切换");
  }

  @Test
  void rejectsOutOfRangeShanghaiDatesAndUnsanitizedChannelIdentifiersBeforeReads() {
    assertThatThrownBy(() -> controller.preview("merchant", DAY, "SIM-A"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("渠道");
    assertThatThrownBy(() -> controller.preview("merchant", DAY.plusDays(1), CHANNEL))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("日期");
    assertThatThrownBy(
            () -> controller.preview("merchant", DAY.minusYears(2).minusDays(1), CHANNEL))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("日期");
    verifyNoInteractions(gate, clickhouse, core);
  }

  private void stubBill(String status, long processedLines) {
    when(core.queryForList(anyString(), eq("merchant"), eq(CHANNEL), eq(Date.valueOf(DAY))))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    "bill",
                    "version",
                    2,
                    "status",
                    status,
                    "expected_count",
                    6L,
                    "processed_lines",
                    processedLines)));
  }

  private static Map<String, Object> stream(Object amount, String at) {
    return Map.of(
        "payments",
        4,
        "succeeded",
        3L,
        "succeeded_minor",
        amount,
        "refunded_minor",
        25L,
        "latest_source_updated_at",
        at);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<AnalyticsPublicationService> publicationsProvider(
      AnalyticsPublicationService publications) {
    var provider = (ObjectProvider<AnalyticsPublicationService>) mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(publications);
    return provider;
  }
}
