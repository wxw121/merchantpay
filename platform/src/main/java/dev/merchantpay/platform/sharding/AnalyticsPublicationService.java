package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.Signatures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Publishes an independently signed aggregate proof only at its exact source watermark. */
@Service
@ConditionalOnProperty(
    name = {
      "merchantpay.analytics.flink-enabled",
      "merchantpay.analytics.publication-enabled",
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.projection-enabled",
      "merchantpay.read-model-enabled"
    },
    havingValue = "true")
public final class AnalyticsPublicationService {
  public record Publication(
      String generation, String status, String evidenceHash, Instant publishedAt) {}

  private final ShardProjectionReadGate projection;
  private final ShardExportReadGate routes;
  private final JdbcTemplate core;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final Clock clock;
  private final byte[] key;

  public AnalyticsPublicationService(
      ShardProjectionReadGate projection,
      ShardExportReadGate routes,
      JdbcTemplate core,
      TransactionTemplate tx,
      JsonMapper json,
      Clock clock,
      @Value("${merchantpay.analytics.release-key:}") String secret) {
    this.projection = projection;
    this.routes = routes;
    this.core = core;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
    key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32)
      throw new IllegalStateException(
          "Analytics publication requires a release signing key of at least 32 bytes");
  }

  public Publication publish(String payload, String signature, Actor actor) {
    actor.require("ADMIN", "OPS");
    if (actor.merchantId() != null && !actor.merchantId().isBlank())
      throw new ApiException("FORBIDDEN", 403, "分析代发布仅允许平台运维");
    if (payload == null
        || payload.length() > 16_384
        || signature == null
        || !signature.matches("[a-f0-9]{64}")) throw conflict("发布证据格式无效");
    byte[] expected = signed(payload);
    byte[] supplied = java.util.HexFormat.of().parseHex(signature);
    if (!MessageDigest.isEqual(expected, supplied)) throw conflict("发布证据签名无效");
    JsonNode evidence;
    try {
      evidence = json.readTree(payload);
    } catch (RuntimeException invalid) {
      throw conflict("发布证据不是有效 JSON");
    }
    validateShape(evidence);
    String generation = value(evidence, "generation");
    String hash = Signatures.sha256(payload);
    tx.executeWithoutResult(
        ignored -> {
          verifyLive(evidence, false);
          var prior =
              core.queryForList(
                  "SELECT evidence_hash,signature FROM "
                      + "v3_analytics_generation_publications WHERE generation=? FOR UPDATE",
                  generation);
          if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("evidence_hash"))
                || !signature.equals(prior.getFirst().get("signature")))
              throw conflict("该分析代已用不同证据发布");
            return;
          }
          var now = Timestamp.from(clock.instant());
          core.update(
              "INSERT INTO v3_analytics_generation_publications(generation,evidence_hash,"
                  + "evidence_payload,signature,published_by,published_at) VALUES(?,?,?,?,?,?)",
              generation,
              hash,
              payload,
              signature,
              actor.id(),
              now);
          core.update(
              "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,"
                  + "detail,created_at) VALUES(?,NULL,?,'V3_ANALYTICS_PUBLISH',?,?,?)",
              UUID.randomUUID().toString(),
              actor.id(),
              generation,
              hash,
              now);
        });
    return current(generation);
  }

  public Publication current(String generation) {
    if (generation == null || !generation.matches("[A-Za-z0-9_-]{1,36}")) throw conflict("分析代号无效");
    var rows =
        core.queryForList(
            "SELECT evidence_hash,evidence_payload,published_at FROM "
                + "v3_analytics_generation_publications WHERE generation=?",
            generation);
    if (rows.isEmpty()) return new Publication(generation, "UNPUBLISHED", null, null);
    var row = rows.getFirst();
    String status;
    try {
      status =
          verifyLive(json.readTree((String) row.get("evidence_payload")), true)
              ? "VERIFIED_AT_WATERMARK"
              : "STALE";
    } catch (RuntimeException unavailable) {
      status = "STALE";
    }
    return new Publication(
        generation,
        status,
        (String) row.get("evidence_hash"),
        ((Timestamp) row.get("published_at")).toInstant());
  }

  private boolean verifyLive(JsonNode evidence, boolean afterPublication) {
    if (!value(evidence, "generation").equals(projection.generation())) {
      if (afterPublication) return false;
      throw conflict("四库服务代与发布证据不一致");
    }
    var captured = routes.capture();
    JsonNode observedRoutes = evidence.get("routes");
    for (int bucket = 0; bucket < 16; bucket++) {
      var expected = captured.routes().get(bucket);
      var item = observedRoutes.get(bucket);
      if (number(item, "bucket") != bucket
          || number(item, "physicalShard") != expected.physical()
          || number(item, "epoch") != expected.epoch()) {
        if (afterPublication) return false;
        throw conflict("路由已偏离比对证据");
      }
    }
    for (int physical = 0; physical < 4; physical++) {
      var position = evidence.get("sourceGtidPositions").get(physical);
      var current =
          new JdbcTemplate(routes.source(physical))
              .queryForMap(
                  "SELECT @@GLOBAL.server_uuid AS server_uuid,"
                      + "@@GLOBAL.gtid_executed AS gtid_executed");
      String server = value(position, "serverUuid");
      String gtid = value(position, "gtidExecuted");
      if (!server.equals(current.get("server_uuid"))) {
        if (afterPublication) return false;
        throw conflict("物理库身份已偏离比对证据");
      }
      if (gtid.equals(current.get("gtid_executed"))) continue;
      if (afterPublication
          && physical == 0
          && onePublicationCommit(new JdbcTemplate(routes.source(0)), server, gtid)) continue;
      if (afterPublication) return false;
      throw conflict("四库 GTID 已偏离比对证据");
    }
    routes.validate(captured);
    return true;
  }

  private static boolean onePublicationCommit(JdbcTemplate source, String server, String before) {
    var delta =
        source.queryForMap(
            "SELECT GTID_SUBTRACT(@@GLOBAL.gtid_executed, ?) AS extra,"
                + "GTID_SUBTRACT(?,@@GLOBAL.gtid_executed) AS missing",
            before,
            before);
    String extra = Objects.toString(delta.get("extra"), "");
    return Objects.toString(delta.get("missing"), "").isEmpty()
        && Pattern.compile(Pattern.quote(server) + ":[1-9][0-9]*").matcher(extra).matches();
  }

  private void validateShape(JsonNode evidence) {
    if (evidence == null
        || !evidence.isObject()
        || number(evidence, "schemaVersion") != 1
        || !"VERIFIED_AT_WATERMARK".equals(value(evidence, "status"))
        || !value(evidence, "generation").matches("[A-Za-z0-9_-]{1,36}")
        || !value(evidence, "comparisonSha256").matches("[a-f0-9]{64}")
        || !value(evidence, "aggregateSha256").matches("[a-f0-9]{64}")
        || !value(evidence, "flinkJobId").matches("[a-fA-F0-9]{32}")
        || number(evidence, "flinkCheckpointId") < 1) throw conflict("发布证据缺少完整比对或检查点");
    Instant verified;
    try {
      verified = Instant.parse(value(evidence, "verifiedAt"));
    } catch (RuntimeException invalid) {
      throw conflict("发布水位时间无效");
    }
    if (verified.isAfter(clock.instant().plusSeconds(30))
        || Duration.between(verified, clock.instant()).compareTo(Duration.ofMinutes(10)) > 0)
      throw conflict("发布水位已过期");
    var positions = evidence.get("sourceGtidPositions");
    var catalog = evidence.get("routes");
    var offsets = evidence.get("outputOffsets");
    if (positions == null
        || !positions.isArray()
        || positions.size() != 4
        || catalog == null
        || !catalog.isArray()
        || catalog.size() != 16
        || offsets == null
        || !offsets.isArray()
        || offsets.size() != 16) throw conflict("四库来源、路由或输出分区证据不完整");
    for (int i = 0; i < 4; i++) {
      var item = positions.get(i);
      if (number(item, "physicalShard") != i
          || value(item, "serverUuid").isBlank()
          || value(item, "gtidExecuted").isBlank()) throw conflict("来源 GTID 证据不完整");
    }
    for (int i = 0; i < 16; i++) {
      var route = catalog.get(i);
      var offset = offsets.get(i);
      if (number(route, "bucket") != i
          || number(route, "physicalShard") > 3
          || number(route, "epoch") < 1
          || number(offset, "partition") != i
          || number(offset, "end") < 0
          || number(offset, "committed") != number(offset, "end")) throw conflict("路由或桥接水位证据无效");
    }
  }

  private static String value(JsonNode node, String name) {
    var value = node == null ? null : node.get(name);
    if (value == null || !value.isString()) throw conflict("发布证据字段缺失: " + name);
    return value.asString();
  }

  private static long number(JsonNode node, String name) {
    var value = node == null ? null : node.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
      throw conflict("发布证据数字字段无效: " + name);
    return value.asLong();
  }

  private byte[] signed(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException unavailable) {
      throw new IllegalStateException("Analytics release signer unavailable", unavailable);
    }
  }

  private static ApiException conflict(String detail) {
    return new ApiException("ANALYTICS_PUBLICATION_CONFLICT", 409, detail);
  }
}
