package dev.merchantpay.platform.sharding;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("merchantpay.sharding")
public class ShardSettings {
  public record Target(String url, String username, String password) {}

  private int connectionsPerShard = 2;
  private Map<Integer, Target> targets = new LinkedHashMap<>();

  public int getConnectionsPerShard() {
    return connectionsPerShard;
  }

  public void setConnectionsPerShard(int value) {
    connectionsPerShard = value;
  }

  public Map<Integer, Target> getTargets() {
    return targets;
  }

  public void setTargets(Map<Integer, Target> value) {
    targets = value;
  }
}
