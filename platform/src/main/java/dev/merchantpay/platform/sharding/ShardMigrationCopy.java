package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline copy of one stopped bucket. A verified copy does not change the route. */
@Service
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled"
    },
    havingValue = "true")
public final class ShardMigrationCopy {
  public record Copy(
      String planId,
      int bucket,
      long sourceEpoch,
      long planVersion,
      String manifestHash,
      String targetHash,
      long totalRows,
      String copiedBy,
      String reason,
      Instant createdAt,
      String state,
      String discardedBy,
      String discardReason,
      Instant discardedAt) {}

  private static final long MAX_COPY_ROWS = 20_000;
  private final ShardCatalog catalog;
  private final ShardMigrationManifest manifests;
  private final ShardMigrationPreflight preflight;
  private final ShardExportReadGate gate;
  private final JdbcTemplate core;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ShardMigrationCopy(
      ShardCatalog catalog,
      ShardMigrationManifest manifests,
      ShardMigrationPreflight preflight,
      ShardExportReadGate gate,
      JdbcTemplate core,
      TransactionTemplate transactions,
      Clock clock) {
    this.catalog = catalog;
    this.manifests = manifests;
    this.preflight = preflight;
    this.gate = gate;
    this.core = core;
    this.clock = clock;
    tx = new TransactionTemplate(transactions.getTransactionManager());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(300);
  }

  public Copy read(String id, Actor actor) {
    catalog.detail(id, actor);
    var rows = stored(id);
    if (rows.isEmpty()) throw new ApiException("SHARD_COPY_NOT_FOUND", 404, "该计划尚未完成目标库复制");
    return rows.getFirst();
  }

  public Copy copy(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = requestedPlan(id, expectedVersion, reason, writersStopped, actor);
    return Objects.requireNonNull(tx.execute(ignored -> copyLocked(plan, reason, actor)));
  }

  public Copy discard(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = requestedPlan(id, expectedVersion, reason, writersStopped, actor);
    return Objects.requireNonNull(tx.execute(ignored -> discardLocked(plan, reason, actor)));
  }

  private ShardCatalog.Plan requestedPlan(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = catalog.detail(id, actor);
    if (!writersStopped
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000
        || expectedVersion < 1
        || plan.version() != expectedVersion
        || !"PLANNED".equals(plan.state())
        || plan.sourceShard() != 0
        || plan.targetShard() == 0) throw conflict("须保持停写并使用物理0来源的当前计划版本及有效原因");
    return plan;
  }

  private Copy copyLocked(ShardCatalog.Plan plan, String reason, Actor actor) {
    var source = lockedSource(plan, actor);
    long total = source.tables().stream().mapToLong(ShardMigrationManifest.Table::count).sum();
    if (total > MAX_COPY_ROWS) throw conflict("单次复制超过2万行，须使用后续分批搬迁流程");
    var prior = stored(plan.id());
    if (!prior.isEmpty() && !"VERIFIED".equals(prior.getFirst().state()))
      throw conflict("复制已回退，不能在原计划上重新复制");
    if (!prior.isEmpty()
        && (!prior.getFirst().manifestHash().equals(source.hash())
            || prior.getFirst().bucket() != plan.bucket()
            || prior.getFirst().sourceEpoch() != plan.sourceEpoch()
            || prior.getFirst().planVersion() != plan.version()
            || !prior.getFirst().targetHash().equals(source.hash())
            || prior.getFirst().totalRows() != total)) throw conflict("已记录的复制结果与来源清单不一致");

    try (var targetConnection = gate.source(plan.targetShard()).getConnection()) {
      targetConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      targetConnection.setAutoCommit(false);
      try {
        var target = new JdbcTemplate(new SingleConnectionDataSource(targetConnection, true));
        target.setQueryTimeout(60);
        targetIdentity(target, plan);
        targetGuard(target, plan.bucket());
        var before = ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id());
        boolean empty = before.stream().allMatch(table -> table.count() == 0);
        if (empty && prior.isEmpty()) {
          boolean remapEntries =
              ShardMigrationPreflight.autoIdOverlap(core, target, plan.bucket(), "ledger_entries");
          boolean remapEvents =
              ShardMigrationPreflight.autoIdOverlap(core, target, plan.bucket(), "domain_events");
          for (var probe : ShardMigrationPreflight.BUCKET_PROBES)
            copyRows(
                targetConnection,
                probe,
                plan.bucket(),
                plan.id(),
                "ledger_entries".equals(probe.name()) && remapEntries
                    || "domain_events".equals(probe.name()) && remapEvents);
        } else if (!before.equals(source.tables())) {
          throw conflict("目标库存在部分或不一致的数据，禁止覆盖及续写");
        }
        var after = ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id());
        if (!after.equals(source.tables())
            || !ShardMigrationManifest.manifestHash(after).equals(source.hash()))
          throw conflict("目标库逐表摘要与来源清单不一致，复制已回滚");
        if (!ShardMigrationManifest.snapshot(core, plan.bucket()).equals(source.tables()))
          throw conflict("复制期间来源数据发生变化，复制已回滚");
        targetConnection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          targetConnection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        throw failure;
      }
    } catch (SQLException unavailable) {
      throw new ApiException("SHARD_COPY_TARGET_UNAVAILABLE", 503, "无法完成目标库事务，来源仍保持隔离");
    } catch (DuplicateKeyException collision) {
      throw conflict("目标库存在冲突的业务键或主键，复制已回滚");
    } catch (DataAccessException unavailable) {
      throw new ApiException("SHARD_COPY_DATA_UNAVAILABLE", 503, "无法读取或写入完整的搬迁数据，来源仍保持隔离");
    }

    if (!prior.isEmpty()) return prior.getFirst();
    var now = Timestamp.from(clock.instant());
    if (core.update(
            "INSERT INTO v3_shard_copy_runs(plan_id,bucket,source_epoch,plan_version,manifest_hash,target_hash,total_rows,copied_by,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            plan.id(),
            plan.bucket(),
            plan.sourceEpoch(),
            plan.version(),
            source.hash(),
            source.hash(),
            total,
            actor.id(),
            reason,
            now)
        != 1) throw conflict("复制记录未写入；目标库可能已完成复制，请重试核对");
    core.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,NULL,?,'V3_SHARD_COPY',?,?,?)",
        UUID.randomUUID().toString(),
        actor.id(),
        plan.id(),
        "hash=" + source.hash() + "; " + reason,
        now);
    return stored(plan.id()).getFirst();
  }

  private Copy discardLocked(ShardCatalog.Plan plan, String reason, Actor actor) {
    var source = lockedSource(plan, actor);
    if (!core.queryForList(
            "SELECT plan_id FROM v3_shard_cutovers WHERE plan_id=? FOR UPDATE",
            String.class,
            plan.id())
        .isEmpty()) throw conflict("切流已开始，不能回退目标复制；请保持停写并按原请求重试切流");
    var copies = stored(plan.id());
    if (copies.size() != 1) throw conflict("该计划没有可回退的复制记录");
    var copy = copies.getFirst();
    long total = source.tables().stream().mapToLong(ShardMigrationManifest.Table::count).sum();
    if (copy.bucket() != plan.bucket()
        || copy.sourceEpoch() != plan.sourceEpoch()
        || copy.planVersion() != plan.version()
        || copy.totalRows() != total
        || !copy.manifestHash().equals(source.hash())
        || !copy.targetHash().equals(source.hash())) throw conflict("复制记录与当前来源清单不一致");

    try (var targetConnection = gate.source(plan.targetShard()).getConnection()) {
      targetConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      targetConnection.setAutoCommit(false);
      try {
        var target = new JdbcTemplate(new SingleConnectionDataSource(targetConnection, true));
        target.setQueryTimeout(60);
        targetIdentity(target, plan);
        targetGuard(target, plan.bucket());
        var before = ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id());
        boolean empty = before.stream().allMatch(table -> table.count() == 0);
        if ("DISCARDED".equals(copy.state())) {
          if (!empty) throw conflict("已回退的目标桶重新出现数据");
        } else if ("VERIFIED".equals(copy.state())) {
          if (!before.equals(source.tables()) && !empty) throw conflict("目标桶出现部分数据或摘要漂移，禁止删除");
          if (!empty) {
            var probes = new ArrayList<>(ShardMigrationPreflight.BUCKET_PROBES);
            Collections.reverse(probes);
            for (var probe : probes) deleteRows(target, probe, plan.bucket());
          }
        } else {
          throw conflict("复制记录状态无效");
        }
        target.update("DELETE FROM v3_shard_copy_id_map WHERE plan_id=?", plan.id());
        var after = ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id());
        if (after.stream().anyMatch(table -> table.count() != 0)) throw conflict("目标桶仍有数据，删除已回滚");
        targetConnection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          targetConnection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        throw failure;
      }
    } catch (SQLException unavailable) {
      throw new ApiException("SHARD_COPY_TARGET_UNAVAILABLE", 503, "无法完成目标库回退事务，来源仍保持隔离");
    } catch (DataAccessException unavailable) {
      throw new ApiException("SHARD_COPY_DATA_UNAVAILABLE", 503, "无法完整核对目标库回退数据，来源仍保持隔离");
    }

    // The target commit can succeed before the core update. A retry accepts only
    // the now-empty target, then finishes the durable state transition.
    boolean targetEmpty;
    try {
      targetEmpty = preflight.targetEmpty(plan);
    } catch (DataAccessException unavailable) {
      throw new ApiException("SHARD_COPY_DATA_UNAVAILABLE", 503, "目标库回退后状态无法复核，来源仍保持隔离");
    }
    if (!ShardMigrationManifest.snapshot(core, plan.bucket()).equals(source.tables())
        || !targetEmpty) throw conflict("回退提交后来源或目标状态变化，来源保持隔离");
    if ("DISCARDED".equals(copy.state())) return copy;
    var now = Timestamp.from(clock.instant());
    if (core.update(
            "UPDATE v3_shard_copy_runs SET state='DISCARDED',discarded_by=?,discard_reason=?,discarded_at=? WHERE plan_id=? AND state='VERIFIED'",
            actor.id(),
            reason,
            now,
            plan.id())
        != 1) throw conflict("回退记录已变化，请重新核对");
    core.update(
        "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES (?,NULL,?,'V3_SHARD_COPY_DISCARD',?,?,?)",
        UUID.randomUUID().toString(),
        actor.id(),
        plan.id(),
        "hash=" + source.hash() + "; " + reason,
        now);
    return stored(plan.id()).getFirst();
  }

  private ShardMigrationManifest.Manifest lockedSource(ShardCatalog.Plan plan, Actor actor) {
    manifests.locked(plan);
    var source = manifests.read(plan.id(), actor);
    if (source.bucket() != plan.bucket()
        || source.sourceEpoch() != plan.sourceEpoch()
        || source.planVersion() != plan.version()
        || source.tables().size() != ShardMigrationPreflight.BUCKET_PROBES.size()
        || !source.tables().stream()
            .map(ShardMigrationManifest.Table::name)
            .toList()
            .equals(
                ShardMigrationPreflight.BUCKET_PROBES.stream()
                    .map(ShardMigrationPreflight.Probe::name)
                    .sorted()
                    .toList())) throw conflict("来源清单与当前计划或搬迁表集合不一致");
    if (!ShardMigrationManifest.snapshot(core, plan.bucket()).equals(source.tables()))
      throw conflict("停写后来源数据与已记录清单不一致");
    return source;
  }

  private void targetIdentity(JdbcTemplate target, ShardCatalog.Plan plan) {
    var identities =
        target.queryForList(
            "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
    if (identities.size() != 1
        || identities.getFirst() != plan.targetShard()
        || !target
            .queryForList(
                "SELECT bucket FROM v3_bucket_fences WHERE bucket=? AND access_state='ACTIVE'",
                Integer.class,
                plan.bucket())
            .isEmpty()) throw conflict("目标物理身份或桶栅栏不符合离线复制条件");
  }

  private void targetGuard(JdbcTemplate target, int bucket) {
    String prefix = "p" + Integer.toHexString(bucket) + "_";
    if (!target
            .queryForList(
                "SELECT payment_id FROM payment_admission_slots WHERE LEFT(payment_id,3)=? LIMIT 1",
                String.class,
                prefix)
            .isEmpty()
        || !target
            .queryForList(
                "SELECT logical_shard FROM payment_admission WHERE logical_shard=? AND inflight<>0 LIMIT 1",
                Integer.class,
                bucket)
            .isEmpty()
        || !target
            .queryForList(
                "SELECT status FROM event_quarantine WHERE status<>'REPLAYED' LIMIT 1",
                String.class)
            .isEmpty()) throw conflict("目标库仍有准入槽或隔离事件");
  }

  private void copyRows(
      Connection target,
      ShardMigrationPreflight.Probe probe,
      int bucket,
      String planId,
      boolean remap) {
    core.execute(
        (ConnectionCallback<Void>)
            source -> {
              try (var select =
                  source.prepareStatement(
                      probe.rowSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                select.setQueryTimeout(60);
                select.setFetchSize(
                    source.getMetaData().getURL().startsWith("jdbc:mysql:")
                        ? Integer.MIN_VALUE
                        : 1000);
                var args = ShardMigrationPreflight.probeArgs(probe, bucket);
                for (int index = 0; index < args.length; index++)
                  select.setObject(index + 1, args[index]);
                try (var rows = select.executeQuery()) {
                  var meta = rows.getMetaData();
                  var names = new java.util.ArrayList<String>();
                  for (int column = 1; column <= meta.getColumnCount(); column++) {
                    String name = meta.getColumnLabel(column);
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw conflict("来源表含有无法安全复制的列名");
                    names.add(name);
                  }
                  String idColumn = "ledger_entries".equals(probe.name()) ? "id" : "sequence_no";
                  int idIndex = -1;
                  if (remap)
                    for (int index = 0; index < names.size(); index++)
                      if (idColumn.equalsIgnoreCase(names.get(index))) {
                        idIndex = index;
                        break;
                      }
                  if (remap && idIndex < 0) throw conflict("来源自增列缺失，复制已回滚");
                  var insertColumns = new java.util.ArrayList<String>(names);
                  if (remap) insertColumns.remove(idIndex);
                  String placeholders =
                      String.join(",", java.util.Collections.nCopies(insertColumns.size(), "?"));
                  String sql =
                      "INSERT INTO "
                          + probe.name()
                          + " ("
                          + String.join(",", insertColumns)
                          + ") VALUES ("
                          + placeholders
                          + ")";
                  try (var insert =
                          target.prepareStatement(
                              sql,
                              remap
                                  ? java.sql.Statement.RETURN_GENERATED_KEYS
                                  : java.sql.Statement.NO_GENERATED_KEYS);
                      var mapping =
                          remap
                              ? target.prepareStatement(
                                  "INSERT INTO v3_shard_copy_id_map(plan_id,entity_name,source_id,target_id) VALUES(?,?,?,?)")
                              : null) {
                    long count = 0;
                    while (rows.next()) {
                      if (++count > MAX_COPY_ROWS) throw conflict("来源单表超过复制上限");
                      int parameter = 1;
                      for (int column = 1; column <= names.size(); column++)
                        if (!remap || column != idIndex + 1)
                          insert.setObject(parameter++, rows.getObject(column));
                      if (insert.executeUpdate() != 1) throw conflict("目标行写入数量异常");
                      if (remap) {
                        try (var keys = insert.getGeneratedKeys()) {
                          if (!keys.next()) throw conflict("目标自增编号未返回，复制已回滚");
                          long generated = keys.getLong(1);
                          if (generated < 1 || keys.next()) throw conflict("目标自增编号无效，复制已回滚");
                          mapping.setString(1, planId);
                          mapping.setString(2, probe.name());
                          mapping.setLong(3, rows.getLong(idIndex + 1));
                          mapping.setLong(4, generated);
                          if (mapping.executeUpdate() != 1) throw conflict("目标编号映射未持久化，复制已回滚");
                        }
                      }
                    }
                  }
                }
              }
              return null;
            });
  }

  private void deleteRows(JdbcTemplate target, ShardMigrationPreflight.Probe probe, int bucket) {
    List<String> columns = probe.cursorColumns();
    var keys =
        target.query(
            probe.rowSql(),
            (rows, ignored) -> {
              Object[] values = new Object[columns.size()];
              for (int index = 0; index < columns.size(); index++)
                values[index] = rows.getObject(columns.get(index));
              return values;
            },
            ShardMigrationPreflight.probeArgs(probe, bucket));
    if (keys.size() > MAX_COPY_ROWS) throw conflict("目标表超过可回退的行数上限");
    String where = String.join(" AND ", columns.stream().map(column -> column + "=?").toList());
    String sql = "DELETE FROM " + probe.name() + " WHERE " + where;
    for (var key : keys) if (target.update(sql, key) != 1) throw conflict("目标表主键删除结果异常，回退已回滚");
  }

  private List<Copy> stored(String id) {
    return core.query(
        "SELECT * FROM v3_shard_copy_runs WHERE plan_id=?",
        (rs, ignored) ->
            new Copy(
                rs.getString("plan_id"),
                rs.getInt("bucket"),
                rs.getLong("source_epoch"),
                rs.getLong("plan_version"),
                rs.getString("manifest_hash"),
                rs.getString("target_hash"),
                rs.getLong("total_rows"),
                rs.getString("copied_by"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("state"),
                rs.getString("discarded_by"),
                rs.getString("discard_reason"),
                rs.getTimestamp("discarded_at") == null
                    ? null
                    : rs.getTimestamp("discarded_at").toInstant()),
        id);
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARD_COPY_CONFLICT", 409, message);
  }
}
