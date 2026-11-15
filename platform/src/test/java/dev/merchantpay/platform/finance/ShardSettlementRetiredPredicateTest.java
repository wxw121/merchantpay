package dev.merchantpay.platform.finance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.merchantpay.platform.sharding.ShardExportReadGate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShardSettlementRetiredPredicateTest {
  @Test
  void coreScanExcludesOnlyBucketsWhoseCoreCopyIsRetired() {
    var route =
        new ShardExportReadGate.Snapshot(
            List.of(),
            Map.of(
                4, Map.of(0, 1L),
                5, Map.of(1, 2L)));
    assertThat(ShardSettlementService.retiredSourcePredicate(route))
        .isEqualTo(" AND LEFT(e.payment_id,3) NOT IN ('p4_')");
    assertThat(
            ShardSettlementService.retiredSourcePredicate(
                new ShardExportReadGate.Snapshot(List.of(), Map.of(5, Map.of(1, 2L)))))
        .isEmpty();
  }
}
