package dev.merchantpay.simulator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AcceptanceControllerTest {
  private final ChannelStore store = mock(ChannelStore.class);
  private final AcceptanceController controller = new AcceptanceController(store, "证据令牌");

  @Test
  void exposesConfiguredProfileAndJoinsTheRequestedEvidencePage() {
    when(store.profile()).thenReturn(new StandardProfile("standard-v1", 37));
    when(store.evidence("MOCK_B", "REFUND", "run-1", "r1", 2))
        .thenReturn(
            List.of("r2\tm2\trun-1-a\tSUCCEEDED\t100\t1", "r3\tm2\trun-1-b\tFAILED\t50\t0"));

    assertThat(controller.profile("证据令牌")).isEqualTo("standard-v1\t37");
    assertThat(controller.results("证据令牌", "MOCK_B", "REFUND", "run-1", "r1", 2))
        .isEqualTo("r2\tm2\trun-1-a\tSUCCEEDED\t100\t1\nr3\tm2\trun-1-b\tFAILED\t50\t0");
  }

  @Test
  void rejectsEvidenceRequestsWithTheWrongToken() {
    assertThatExceptionOfType(ResponseStatusException.class)
        .isThrownBy(() -> controller.results("wrong", "MOCK_B", "REFUND", "run-1", "", 2))
        .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(store);
  }
}
