package dev.merchantpay.simulator;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StandardProfileTest {
  @Test
  void rejectsUnknownProfileModes() {
    assertThatIllegalArgumentException().isThrownBy(() -> new StandardProfile("unknown", 37));
  }

  @ParameterizedTest
  @CsvSource({"fail:order, FAILED", "pending:order, PENDING", "order, SUCCEEDED"})
  void demoUsesBusinessNumberPrefixesWithoutDelayOrDuplicates(String key, String status) {
    var profile = new StandardProfile("demo", 20261019);

    assertThat(profile.status(key)).isEqualTo(status);
    assertThat(profile.delayMillis(key)).isZero();
    assertThat(profile.duplicate(key)).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "profile-1, SUCCEEDED, false, 48",
    "profile-0, SUCCEEDED, true, 78",
    "profile-8, SUCCEEDED, false, 380",
    "profile-49, SUCCEEDED, true, 412",
    "profile-13, FAILED, false, 21"
  })
  void standardV1ProducesStableOutcomesAcrossDelayBands(
      String key, String status, boolean duplicate, long delayMs) {
    var profile = new StandardProfile("standard-v1", 20261019);

    assertThat(profile.status(key)).isEqualTo(status);
    assertThat(profile.duplicate(key)).isEqualTo(duplicate);
    assertThat(profile.delayMillis(key)).isEqualTo(delayMs);
  }

  @Test
  void interruptedDelayPreservesTheInterruptFlag() {
    var profile = new StandardProfile("standard-v1", 20261019);
    Thread.currentThread().interrupt();
    try {
      assertThatIllegalStateException()
          .isThrownBy(() -> profile.delay("profile-49"))
          .withCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
