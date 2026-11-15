package dev.merchantpay.platform.web;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.sharding.ShardCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/console/shards")
public class ShardController {
  private final Access access;
  private final ShardCatalog catalog;

  public ShardController(Access access, ShardCatalog catalog) {
    this.access = access;
    this.catalog = catalog;
  }

  private Actor actor() {
    var caller = access.caller();
    return new Actor(caller.actor(), caller.merchantId(), caller.roles());
  }

  @GetMapping
  ShardCatalog.Directory directory() {
    return catalog.directory(actor());
  }

  @GetMapping("/resolve")
  ShardCatalog.Resolution resolve(
      @RequestParam String kind,
      @RequestParam(required = false) String merchantId,
      @RequestParam String key) {
    return catalog.resolve(kind, merchantId, key, actor());
  }

  @GetMapping("/plans")
  List<ShardCatalog.Plan> plans(@RequestParam(defaultValue = "PLANNED") String state) {
    return catalog.plans(state, actor());
  }

  @GetMapping("/plans/{id}")
  ShardCatalog.Plan detail(@PathVariable String id) {
    return catalog.detail(id, actor());
  }

  @PostMapping("/plans")
  ShardCatalog.Plan plan(@RequestBody JsonNode input) {
    return catalog.plan(
        text(input, "requestKey"),
        (int) number(input, "bucket", 0, 15),
        (int) number(input, "targetShard", 0, 3),
        number(input, "expectedEpoch", 1, Long.MAX_VALUE),
        text(input, "reason"),
        actor());
  }

  @PostMapping("/plans/{id}/cancel")
  ShardCatalog.Plan cancel(@PathVariable String id, @RequestBody JsonNode input) {
    return catalog.cancel(
        id, number(input, "expectedVersion", 1, Long.MAX_VALUE), text(input, "reason"), actor());
  }

  private static String text(JsonNode input, String name) {
    var value = input != null && input.isObject() ? input.get(name) : null;
    if (value == null || !value.isString())
      throw new ApiException("INVALID_REQUEST", 400, name + "必须是JSON字符串");
    return value.asString();
  }

  private static long number(JsonNode input, String name, long minimum, long maximum) {
    var value = input != null && input.isObject() ? input.get(name) : null;
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
      throw new ApiException("INVALID_REQUEST", 400, name + "必须是有效范围内的JSON整数");
    long parsed = value.asLong();
    if (parsed < minimum || parsed > maximum)
      throw new ApiException("INVALID_REQUEST", 400, name + "超出允许范围");
    return parsed;
  }
}
