package dev.merchantpay.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.control.RedisAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.JedisPooled;

class V2RedisAdmissionTest {
  @Test
  void disabledAdmissionNeverNeedsRedis() {
    try (var control = new RedisAdmission(false, "", 2)) {
      assertThat(control.allow("merchant", 1, 100)).isTrue();
    }
  }

  @Test
  void redisFailureUsesConservativePerNodeCapWithoutGrantingAnUnlimitedWindow() {
    try (var control = new RedisAdmission(true, "redis://127.0.0.1:1", 2)) {
      var original = (JedisPooled) ReflectionTestUtils.getField(control, "redis");
      original.close();
      var redis = mock(JedisPooled.class);
      when(redis.eval(anyString(), anyList(), anyList()))
          .thenThrow(new IllegalStateException("unavailable"));
      ReflectionTestUtils.setField(control, "redis", redis);
      assertThat(control.allow("merchant", 1, 4)).isTrue();
      assertThat(control.allow("merchant", 1, 4)).isTrue();
      assertThat(control.allow("merchant", 1, 4)).isFalse();
      assertThat(control.allow("merchant", 2, 4)).isTrue();
    }
  }
}
