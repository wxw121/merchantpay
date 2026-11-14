package dev.merchantpay.platform.sharding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.Signatures;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class AnalyticsPublicationServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
  private static final String SECRET = "a-32-byte-publication-secret-for-tests";
  private static final Actor OPS = new Actor("ops", null, Set.of("OPS"));
  private final ShardProjectionReadGate projection = mock(ShardProjectionReadGate.class);
  private final ShardExportReadGate routes = mock(ShardExportReadGate.class);
  private final JsonMapper json = JsonMapper.builder().build();
  private final DriverManagerDataSource database = source();
  private final JdbcTemplate core = new JdbcTemplate(database);
  private final AnalyticsPublicationService service =
      new AnalyticsPublicationService(
          projection,
          routes,
          core,
          new TransactionTemplate(new DataSourceTransactionManager(database)),
          json,
          Clock.fixed(NOW, ZoneOffset.UTC),
          SECRET);

  @BeforeEach
  void schema() {
    core.execute(
        "CREATE TABLE v3_analytics_generation_publications(generation VARCHAR(36) PRIMARY KEY,"
            + "evidence_hash VARCHAR(64),evidence_payload TEXT,signature VARCHAR(64),published_by VARCHAR(80),published_at TIMESTAMP)");
    core.execute(
        "CREATE TABLE audit_records(id VARCHAR(36),merchant_id VARCHAR(64),actor VARCHAR(80),"
            + "operation VARCHAR(80),resource_id VARCHAR(80),detail VARCHAR(100),created_at TIMESTAMP)");
  }

  @Test
  void unsignedOrAlteredReleaseCannotReachSourceDatabases() {
    assertThatThrownBy(() -> service.publish("{\"generation\":\"g1\"}", "0".repeat(64), OPS))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("签名无效");
    verifyNoInteractions(projection, routes);
    assertThat(service.current("g1").status()).isEqualTo("UNPUBLISHED");
  }

  @Test
  void rejectsValidlySignedStaleIncompleteOrUnconsumedEvidenceBeforeDatabaseAccess()
      throws Exception {
    var evidence = evidence();
    evidence.put("verifiedAt", NOW.minusSeconds(601).toString());
    reject(evidence, "已过期");
    evidence.put("verifiedAt", NOW.toString());
    var offsets = new ArrayList<>(offsets());
    offsets.removeLast();
    evidence.put("outputOffsets", offsets);
    reject(evidence, "证据不完整");
    offsets.add(Map.of("partition", 15, "end", 20, "committed", 19));
    reject(evidence, "水位证据无效");
    verifyNoInteractions(projection, routes);
    assertThat(core.queryForObject("SELECT COUNT(*) FROM audit_records", Long.class)).isZero();
  }

  @Test
  void staleGenerationOrChangedRouteCannotPublishEvenWithAValidSignature() throws Exception {
    var evidence = evidence();
    when(projection.generation()).thenReturn("other");
    reject(evidence, "服务代与发布证据不一致");
    when(projection.generation()).thenReturn("g1");
    var actualRoutes = new ArrayList<>(routeSnapshot().routes());
    actualRoutes.set(7, new ShardExportReadGate.Route(7, 1, 2));
    when(routes.capture()).thenReturn(new ShardExportReadGate.Snapshot(actualRoutes, Map.of()));
    reject(evidence, "路由已偏离");
    verify(routes, never()).source(anyInt());
    assertThat(service.current("g1").status()).isEqualTo("UNPUBLISHED");
  }

  @Test
  void merchantScopedOperatorCannotPublishForThePlatform() {
    assertThatThrownBy(
            () ->
                service.publish(
                    "{}", "0".repeat(64), new Actor("operator", "merchant", Set.of("OPS"))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("仅允许平台运维");
    verifyNoInteractions(projection, routes);
  }

  @Test
  void persistedPublicationBecomesStaleIfItsGenerationOrEvidenceIsUnreadable() {
    core.update(
        "INSERT INTO v3_analytics_generation_publications VALUES('g1','digest',?,'sig','ops',CURRENT_TIMESTAMP)",
        "{\"generation\":\"g1\"}");
    when(projection.generation()).thenReturn("other");
    assertThat(service.current("g1"))
        .satisfies(
            publication -> {
              assertThat(publication.status()).isEqualTo("STALE");
              assertThat(publication.evidenceHash()).isEqualTo("digest");
              assertThat(publication.publishedAt()).isNotNull();
            });
    core.update("UPDATE v3_analytics_generation_publications SET evidence_payload='{'");
    assertThat(service.current("g1").status()).isEqualTo("STALE");
    assertThatThrownBy(() -> service.current("../g1")).isInstanceOf(ApiException.class);
  }

  private void reject(Map<String, Object> fields, String message) throws Exception {
    String payload = json.writeValueAsString(fields);
    assertThatThrownBy(() -> service.publish(payload, signature(payload), OPS))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining(message);
  }

  private static Map<String, Object> evidence() {
    var fields = new HashMap<String, Object>();
    fields.put("schemaVersion", 1);
    fields.put("status", "VERIFIED_AT_WATERMARK");
    fields.put("generation", "g1");
    fields.put("comparisonSha256", Signatures.sha256("truth"));
    fields.put("aggregateSha256", Signatures.sha256("projection"));
    fields.put("flinkJobId", "f".repeat(32));
    fields.put("flinkCheckpointId", 9);
    fields.put("verifiedAt", NOW.toString());
    fields.put(
        "routes",
        IntStream.range(0, 16)
            .mapToObj(bucket -> Map.of("bucket", bucket, "physicalShard", 0, "epoch", 1))
            .toList());
    fields.put(
        "sourceGtidPositions",
        IntStream.range(0, 4)
            .mapToObj(
                shard ->
                    Map.of(
                        "physicalShard",
                        shard,
                        "serverUuid",
                        "server-" + shard,
                        "gtidExecuted",
                        "server-" + shard + ":1-3"))
            .toList());
    fields.put("outputOffsets", offsets());
    return fields;
  }

  private static List<Map<String, Integer>> offsets() {
    return IntStream.range(0, 16)
        .mapToObj(partition -> Map.of("partition", partition, "end", 20, "committed", 20))
        .toList();
  }

  private static ShardExportReadGate.Snapshot routeSnapshot() {
    return new ShardExportReadGate.Snapshot(
        IntStream.range(0, 16)
            .mapToObj(bucket -> new ShardExportReadGate.Route(bucket, 0, 1))
            .toList(),
        Map.of());
  }

  private static String signature(String payload) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private static DriverManagerDataSource source() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:publications_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sa",
        "");
  }
}
