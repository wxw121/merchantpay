package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import dev.merchantpay.platform.common.Signatures;
import dev.merchantpay.platform.outbox.EventTransportMaintenance;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable source evidence after quiescence. No target data or route is changed. */
@Service
@ConditionalOnProperty(
    name = {"merchantpay.sharding.enabled", "merchantpay.sharding.migration-quiesce-enabled"},
    havingValue = "true")
public final class ShardMigrationManifest {
  public record Table(String name, long count, String hash) {}

  public record Manifest(
      String planId,
      int bucket,
      long sourceEpoch,
      long planVersion,
      String hash,
      String recordedBy,
      String reason,
      Instant createdAt,
      List<Table> tables) {}

  private record Header(
      int bucket,
      long sourceEpoch,
      long planVersion,
      int tableCount,
      String hash,
      String recordedBy,
      String reason,
      Instant createdAt) {}

  // Offline scans stream rows; the previous 100k cap prevented a large bucket
  // from ever reaching the resumable copy path.
  private static final long MAX_ROWS_PER_TABLE = 1_000_000_000L;
  private final ShardCatalog catalog;
  private final ShardMigrationPreflight preflight;
  private final ShardExportReadGate gate;
  private final JdbcTemplate core;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ShardMigrationManifest(
      ShardCatalog catalog,
      ShardMigrationPreflight preflight,
      ShardExportReadGate gate,
      JdbcTemplate core,
      TransactionTemplate transactions,
      Clock clock) {
    this.catalog = catalog;
    this.preflight = preflight;
    this.gate = gate;
    this.core = core;
    this.clock = clock;
    tx = new TransactionTemplate(transactions.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(21_600);
  }

  public Manifest read(String id, Actor actor) {
    catalog.detail(id, actor);
    return stored(id)
        .orElseThrow(() -> new ApiException("SHARD_MANIFEST_NOT_FOUND", 404, "该计划尚未记录来源清单"));
  }

  public Manifest capture(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = catalog.detail(id, actor);
    if (!writersStopped
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000
        || expectedVersion < 1
        || plan.version() != expectedVersion
        || !"PLANNED".equals(plan.state())
        || plan.sourceShard() != 0) throw conflict("须保持停写并使用物理0来源的当前计划版本及有效原因");
    return Objects.requireNonNull(
        tx.execute(
            ignored -> {
              locked(plan);
              if (!preflight.targetEmpty(plan)) throw conflict("目标桶已有数据，不能记录源清单");
              drained();
              var tables = snapshot(core, plan.bucket());
              if (!preflight.targetEmpty(plan)) throw conflict("扫描期间目标桶出现数据");
              String hash = manifestHash(tables);
              var prior = stored(id);
              if (prior.isPresent()) {
                if (prior.get().bucket() != plan.bucket()
                    || prior.get().sourceEpoch() != plan.sourceEpoch()
                    || prior.get().planVersion() != expectedVersion
                    || !prior.get().reason().equals(reason)
                    || !prior.get().hash().equals(hash)
                    || !prior.get().tables().equals(tables))
                  throw conflict("隔离后的来源清单已变化，须核对外部写入或损坏");
                return prior.get();
              }
              var now = Timestamp.from(clock.instant());
              if (core.update(
                      "INSERT INTO v3_shard_source_manifests(plan_id,bucket,source_epoch,plan_version,table_count,manifest_hash,recorded_by,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                      id,
                      plan.bucket(),
                      plan.sourceEpoch(),
                      expectedVersion,
                      tables.size(),
                      hash,
                      actor.id(),
                      reason,
                      now)
                  != 1) throw conflict("来源清单未写入");
              for (var table : tables)
                if (core.update(
                        "INSERT INTO v3_shard_source_manifest_tables(plan_id,table_name,source_count,source_hash) VALUES (?,?,?,?)",
                        id,
                        table.name(),
                        table.count(),
                        table.hash())
                    != 1) throw conflict("来源表清单未写入");
              core.update(
                  "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,NULL,?,'V3_SHARD_SOURCE_MANIFEST',?,?,?)",
                  UUID.randomUUID().toString(),
                  actor.id(),
                  id,
                  "hash=" + hash + "; " + reason,
                  now);
              return stored(id).orElseThrow();
            }));
  }

  void locked(ShardCatalog.Plan plan) {
    var identity =
        core.queryForList("SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
    if (identity.size() != 1 || identity.getFirst() != 0) throw conflict("核心来源物理身份已变化");
    var route =
        core.queryForMap(
            "SELECT physical_shard,epoch FROM v3_shard_routes WHERE bucket=? FOR UPDATE",
            plan.bucket());
    if (((Number) route.get("physical_shard")).intValue() != 0
        || ((Number) route.get("epoch")).longValue() != plan.sourceEpoch())
      throw conflict("来源目录已经变化");
    var storedPlan =
        core.queryForMap(
            "SELECT bucket,source_shard,target_shard,source_epoch,version,state FROM v3_shard_migrations WHERE id=? FOR UPDATE",
            plan.id());
    if (((Number) storedPlan.get("bucket")).intValue() != plan.bucket()
        || ((Number) storedPlan.get("source_shard")).intValue() != 0
        || ((Number) storedPlan.get("target_shard")).intValue() != plan.targetShard()
        || ((Number) storedPlan.get("source_epoch")).longValue() != plan.sourceEpoch()
        || ((Number) storedPlan.get("version")).longValue() != plan.version()
        || !"PLANNED".equals(storedPlan.get("state"))) throw conflict("计划在清单扫描前已变化");
    var isolation =
        core.queryForMap(
            "SELECT bucket,source_epoch,plan_version,state FROM v3_shard_isolations WHERE plan_id=? FOR UPDATE",
            plan.id());
    if (((Number) isolation.get("bucket")).intValue() != plan.bucket()
        || ((Number) isolation.get("source_epoch")).longValue() != plan.sourceEpoch()
        || ((Number) isolation.get("plan_version")).longValue() != plan.version()
        || !"QUIESCED".equals(isolation.get("state"))) throw conflict("来源桶未保持隔离");
    var fence =
        core.queryForMap(
            "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=? FOR UPDATE",
            plan.bucket());
    if (((Number) fence.get("route_epoch")).longValue() != plan.sourceEpoch()
        || !"FENCED".equals(fence.get("access_state"))) throw conflict("来源桶栅栏未保持隔离");
  }

  private void drained() {
    for (int physical = 0; physical < 4; physical++) {
      try (var connection = gate.source(physical).getConnection()) {
        EventTransportMaintenance.requireDrained(connection);
        try (var statement =
                connection.prepareStatement(
                    "SELECT 1 FROM event_quarantine WHERE status<>'REPLAYED' LIMIT 1");
            var rows = statement.executeQuery()) {
          if (rows.next()) throw conflict("四库仍有隔离事件");
        }
      } catch (SQLException failure) {
        throw new ApiException("SHARD_MANIFEST_SOURCE_UNAVAILABLE", 503, "无法核对四库排空状态，来源清单未写入");
      } catch (IllegalStateException pending) {
        throw conflict("四库仍有未排空的业务任务或事件");
      }
    }
  }

  static List<Table> snapshot(JdbcTemplate jdbc, int bucket) {
    return snapshot(jdbc, bucket, null);
  }

  static List<Table> snapshot(JdbcTemplate jdbc, int bucket, String copyPlanId) {
    var tables = new ArrayList<Table>();
    for (var probe : ShardMigrationPreflight.BUCKET_PROBES)
      tables.add(fingerprint(jdbc, probe, bucket, copyPlanId));
    tables.sort(Comparator.comparing(Table::name));
    return List.copyOf(tables);
  }

  private static Table fingerprint(
      JdbcTemplate jdbc, ShardMigrationPreflight.Probe probe, int bucket, String copyPlanId) {
    return jdbc.execute(
        (ConnectionCallback<Table>)
            connection -> {
              MessageDigest digest = sha256();
              boolean canonical =
                  copyPlanId != null
                      && ("ledger_entries".equals(probe.name())
                          || "domain_events".equals(probe.name()));
              try (var statement =
                  connection.prepareStatement(
                      canonical ? probe.canonicalRowSql() : probe.rowSql(),
                      ResultSet.TYPE_FORWARD_ONLY,
                      ResultSet.CONCUR_READ_ONLY)) {
                statement.setQueryTimeout(21_600);
                statement.setFetchSize(
                    connection.getMetaData().getURL().startsWith("jdbc:mysql:")
                        ? Integer.MIN_VALUE
                        : 1000);
                Object[] args = ShardMigrationPreflight.probeArgs(probe, bucket);
                int offset = 0;
                if (canonical) {
                  statement.setString(1, copyPlanId);
                  offset = 1;
                }
                for (int index = 0; index < args.length; index++)
                  statement.setObject(index + 1 + offset, args[index]);
                long count = 0;
                try (var rows = statement.executeQuery()) {
                  var meta = rows.getMetaData();
                  int columns = meta.getColumnCount() - (canonical ? 1 : 0);
                  put(digest, probe.name());
                  for (int column = 1; column <= columns; column++) {
                    put(digest, meta.getColumnLabel(column));
                    put(digest, meta.getColumnType(column));
                  }
                  while (rows.next()) {
                    if (++count > MAX_ROWS_PER_TABLE) throw conflict("单表超过10亿行，须拆分迁移范围");
                    String originalId = canonical ? rows.getString("v3_source_id") : null;
                    for (int column = 1; column <= columns; column++) {
                      String name = meta.getColumnLabel(column);
                      boolean identifier =
                          "ledger_entries".equals(probe.name()) && "id".equalsIgnoreCase(name)
                              || "domain_events".equals(probe.name())
                                  && "sequence_no".equalsIgnoreCase(name);
                      put(
                          digest,
                          canonical && identifier && originalId != null
                              ? originalId
                              : rows.getString(column));
                    }
                  }
                }
                if (canonical) {
                  String key = "ledger_entries".equals(probe.name()) ? "id" : "sequence_no";
                  String predicate =
                      "ledger_entries".equals(probe.name())
                          ? "LEFT(e.payment_id,3)=?"
                          : "(e.logical_bucket=? OR LEFT(e.aggregateid,3) IN (?,?))";
                  String sql =
                      "SELECT 1 FROM v3_shard_copy_id_map m WHERE m.plan_id=?"
                          + " AND m.entity_name=? AND NOT EXISTS (SELECT 1 FROM "
                          + probe.name()
                          + " e WHERE e."
                          + key
                          + "=m.target_id AND "
                          + predicate
                          + ") LIMIT 1";
                  try (var orphan = connection.prepareStatement(sql)) {
                    orphan.setString(1, copyPlanId);
                    orphan.setString(2, probe.name());
                    for (int index = 0; index < args.length; index++)
                      orphan.setObject(index + 3, args[index]);
                    try (var found = orphan.executeQuery()) {
                      if (found.next()) throw conflict("目标编号映射指向缺失或错误桶的记录");
                    }
                  }
                }
                return new Table(probe.name(), count, HexFormat.of().formatHex(digest.digest()));
              }
            });
  }

  private Optional<Manifest> stored(String id) {
    var rows =
        core.query(
            "SELECT * FROM v3_shard_source_manifests WHERE plan_id=?",
            (rs, ignored) ->
                new Header(
                    rs.getInt("bucket"),
                    rs.getLong("source_epoch"),
                    rs.getLong("plan_version"),
                    rs.getInt("table_count"),
                    rs.getString("manifest_hash"),
                    rs.getString("recorded_by"),
                    rs.getString("reason"),
                    rs.getTimestamp("created_at").toInstant()),
            id);
    if (rows.isEmpty()) return Optional.empty();
    var row = rows.getFirst();
    var tables =
        core.query(
            "SELECT table_name,source_count,source_hash FROM v3_shard_source_manifest_tables WHERE plan_id=? ORDER BY table_name",
            (rs, ignored) -> new Table(rs.getString(1), rs.getLong(2), rs.getString(3)),
            id);
    if (tables.size() != row.tableCount()) throw conflict("来源清单表数不完整");
    if (!manifestHash(tables).equals(row.hash())) throw conflict("来源清单摘要与逐表证据不一致");
    return Optional.of(
        new Manifest(
            id,
            row.bucket(),
            row.sourceEpoch(),
            row.planVersion(),
            row.hash(),
            row.recordedBy(),
            row.reason(),
            row.createdAt(),
            List.copyOf(tables)));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static String manifestHash(List<Table> tables) {
    var witness = new StringBuilder();
    for (var table : tables)
      witness
          .append(table.name())
          .append(':')
          .append(table.count())
          .append(':')
          .append(table.hash())
          .append('\n');
    return Signatures.sha256(witness.toString());
  }

  private static void put(MessageDigest digest, String value) {
    if (value == null) {
      put(digest, -1L);
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    put(digest, bytes.length);
    digest.update(bytes);
  }

  private static void put(MessageDigest digest, long value) {
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARD_MANIFEST_CONFLICT", 409, message);
  }
}
