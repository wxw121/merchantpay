package dev.merchantpay.platform.control;

import dev.merchantpay.platform.common.Signatures;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.*;

/**
 * Optional early pressure hint. Every admitted signed request still uses MySQL nonce/quota checks.
 */
@Component
public class RedisAdmission implements AutoCloseable {
  private final JedisPooled redis;
  private final int nodes;
  private final Map<String, Window> fallback = new HashMap<>();
  private long unavailable;

  private record Window(long second, int used) {}

  private static final String SCRIPT =
      "local v=tonumber(redis.call('GET',KEYS[1]) or '0'); if v>=tonumber(ARGV[1]) then return 0 end; v=redis.call('INCR',KEYS[1]); if v==1 then redis.call('PEXPIRE',KEYS[1],2000) end; return 1";

  public RedisAdmission(
      @Value("${merchantpay.redis-admission-enabled:false}") boolean enabled,
      @Value("${merchantpay.redis-uri:}") String uri,
      @Value("${merchantpay.maximum-api-instances:2}") int nodes) {
    if (nodes < 1 || nodes > 32)
      throw new IllegalArgumentException("Maximum API instances must be1..32");
    this.nodes = nodes;
    if (!enabled) {
      redis = null;
      return;
    }
    URI target = URI.create(uri);
    if (!Set.of("redis", "rediss").contains(target.getScheme()) || target.getHost() == null)
      throw new IllegalArgumentException("An explicit Redis URI is required");
    var pool = new GenericObjectPoolConfig<Connection>();
    pool.setMaxTotal(4);
    pool.setMaxIdle(2);
    pool.setMinIdle(0);
    pool.setMaxWait(Duration.ofMillis(200));
    pool.setBlockWhenExhausted(false);
    redis = new JedisPooled(pool, target, 200, 200);
  }

  public boolean allow(String merchant, long second, int cap) {
    if (redis == null) return true;
    if (cap < 1) throw new IllegalArgumentException("Positive hint quota required");
    String key = Signatures.sha256(merchant);
    try {
      return ((Number)
                  redis.eval(
                      SCRIPT,
                      List.of("mp:v2:admission:" + key + ":" + second),
                      List.of(Integer.toString(cap))))
              .longValue()
          == 1;
    } catch (RuntimeException unavailable) {
      return fallback(key, second, cap);
    }
  }

  private synchronized boolean fallback(String key, long second, int cap) {
    unavailable++;
    fallback.entrySet().removeIf(e -> e.getValue().second() < second - 1);
    if (fallback.size() >= 4096 && !fallback.containsKey(key)) return false;
    Window old = fallback.get(key);
    int used = old != null && old.second() == second ? old.used() : 0;
    if (used >= Math.max(1, cap / nodes)) return false;
    fallback.put(key, new Window(second, used + 1));
    return true;
  }

  public synchronized Map<String, Object> status() {
    return Map.of(
        "enabled",
        redis != null,
        "fallbackCount",
        Long.toString(unavailable),
        "fallbackKeys",
        fallback.size(),
        "maximumInstances",
        nodes);
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    if (redis != null) redis.close();
  }
}
