package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.outbox.EventDelivery;
import dev.merchantpay.platform.query.Queries;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/console/events")
public class EventController {
  private final Access access;
  private final EventDelivery delivery;
  private final JdbcTemplate jdbc;
  private dev.merchantpay.platform.sharding.ShardEventDelivery shards;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShards(dev.merchantpay.platform.sharding.ShardEventDelivery shards) {
    this.shards = shards;
  }

  public EventController(Access access, EventDelivery delivery, JdbcTemplate jdbc) {
    this.access = access;
    this.delivery = delivery;
    this.jdbc = jdbc;
  }

  private Actor operator() {
    var c = access.caller();
    var actor = new Actor(c.actor(), c.merchantId(), c.roles());
    EventDelivery.requireOperator(actor);
    return actor;
  }

  @GetMapping("/quarantine")
  List<Map<String, Object>> quarantined(
      @RequestParam(defaultValue = "") String after,
      @RequestParam(defaultValue = "0") int physicalShard) {
    operator();
    if (shards != null) return shards.quarantined(physicalShard, after);
    coreOnly(physicalShard);
    return jdbc.query(
        "SELECT id,topic,partition_no,record_offset,payload_hash,reason,status,attempts,created_at,updated_at,replayed_by FROM event_quarantine WHERE id>? ORDER BY id LIMIT 100",
        Queries.ROW,
        after);
  }

  @PostMapping("/quarantine/{id}/replay")
  Map<String, Object> replay(
      @PathVariable String id, @RequestParam(defaultValue = "0") int physicalShard) {
    var actor = operator();
    if (shards != null) return shards.replay(physicalShard, id, actor);
    coreOnly(physicalShard);
    return delivery.replay(id, actor);
  }

  @GetMapping("/status")
  Map<String, Object> status(@RequestParam(defaultValue = "0") int physicalShard) {
    operator();
    if (shards != null) return shards.status(physicalShard);
    coreOnly(physicalShard);
    var result =
        new LinkedHashMap<>(
            jdbc.query("SELECT * FROM event_transport WHERE id=1", Queries.ROW).getFirst());
    result.put("physicalShard", 0);
    result.put("shardedCdcEnabled", false);
    result.put(
        "unreceived",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM domain_events d WHERE delivery_mode='CDC' AND NOT EXISTS(SELECT 1 FROM event_receipts r WHERE r.event_id=d.event_id AND r.consumer_name=?)",
            Long.class,
            EventDelivery.CONSUMER));
    result.put(
        "quarantined",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM event_quarantine WHERE status<>'REPLAYED'", Long.class));
    return result;
  }

  private static void coreOnly(int physicalShard) {
    if (physicalShard != 0) throw new ApiException("INVALID_SHARD", 400, "物理分片编号无效");
  }
}
