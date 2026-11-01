package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
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
  List<Map<String, Object>> quarantined(@RequestParam(defaultValue = "") String after) {
    operator();
    return jdbc.query(
        "SELECT id,topic,partition_no,record_offset,payload_hash,reason,status,attempts,created_at,updated_at,replayed_by FROM event_quarantine WHERE id>? ORDER BY id LIMIT 100",
        Queries.ROW,
        after);
  }

  @PostMapping("/quarantine/{id}/replay")
  Map<String, Object> replay(@PathVariable String id) {
    return delivery.replay(id, operator());
  }

  @GetMapping("/status")
  Map<String, Object> status() {
    operator();
    var result =
        new LinkedHashMap<>(
            jdbc.query("SELECT * FROM event_transport WHERE id=1", Queries.ROW).getFirst());
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
}
