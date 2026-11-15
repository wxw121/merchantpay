package dev.merchantpay.platform.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** SQL routing definitions only; the native workflow compiles but does not execute tests. */
class AlipaySandboxShardTest {
  static final class RecordingJdbc extends JdbcTemplate {
    final List<String> statements = new ArrayList<>();

    @Override
    public int update(String sql, Object... args) {
      statements.add(sql);
      return 1;
    }
  }

  @Test
  void targetEvidenceIntentAndCheckoutUseSelectedJdbcAndPinnedCheckpoints() {
    var core = new RecordingJdbc();
    var target = new RecordingJdbc();
    var original =
        new AlipaySandbox(
            core, JsonMapper.builder().build(), Clock.systemUTC(), false, "", "", "", "", "", "");
    var checkpoints = new AtomicInteger();
    var local =
        original.forShard(
            target,
            action -> {
              checkpoints.incrementAndGet();
              action.run();
            },
            () -> {});

    local.saveEvidence("r1", "REFUND_SUBMIT", "signed intent");
    local.saveEvidence("r1", "alipay.trade.refund", "signed response");
    local.saveCheckout("p1", "https://example.test/checkout");

    assertThat(checkpoints).hasValue(3);
    assertThat(target.statements).hasSize(3);
    assertThat(target.statements.get(0)).contains("INSERT INTO alipay_evidence");
    assertThat(target.statements.get(1)).contains("INSERT INTO alipay_evidence");
    assertThat(target.statements.get(2)).contains("INSERT INTO sandbox_checkouts");
    assertThat(core.statements).isEmpty();
  }
}
