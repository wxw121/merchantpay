package dev.merchantpay.platform.sharding;

import dev.merchantpay.platform.common.Actor;
import dev.merchantpay.platform.common.ApiException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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

/** One bounded target commit per request; only a complete canonical copy can be cut over. */
@Service
@ConditionalOnProperty(
    name = {
      "merchantpay.sharding.enabled",
      "merchantpay.sharding.migration-quiesce-enabled",
      "merchantpay.sharding.migration-copy-enabled",
      "merchantpay.sharding.migration-batch-enabled"
    },
    havingValue = "true")
public final class ShardMigrationBatchCopy {
  public record Status(
      String planId,
      int bucket,
      String state,
      String tableName,
      long tableCopied,
      long tableRows,
      long totalCopied,
      long totalRows) {}

  private record BatchPlan(
      int bucket,
      long sourceEpoch,
      long planVersion,
      String sourceHash,
      String copyReason,
      String discardReason,
      String state) {}

  static record Progress(
      long sourceCount,
      String sourceHash,
      long copiedCount,
      String cursorA,
      String cursorB,
      boolean remapId,
      boolean complete) {}

  static record Row(Object[] values, String cursorA, String cursorB) {}

  static record PageRows(List<String> columns, List<Row> rows) {}

  static final int BATCH_SIZE = 1_000;

  private final ShardCatalog catalog;
  private final ShardMigrationManifest manifests;
  private final ShardMigrationPreflight preflight;
  private final ShardExportReadGate gate;
  private final JdbcTemplate core;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ShardMigrationBatchCopy(
      ShardCatalog catalog,
      ShardMigrationManifest manifests,
      ShardMigrationPreflight preflight,
      ShardExportReadGate gate,
      JdbcTemplate core,
      TransactionTemplate template,
      Clock clock) {
    this.catalog = catalog;
    this.manifests = manifests;
    this.preflight = preflight;
    this.gate = gate;
    this.core = core;
    this.clock = clock;
    tx = new TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(21_600);
  }

  public Status next(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = requested(id, expectedVersion, reason, writersStopped, actor);
    return Objects.requireNonNull(tx.execute(ignored -> nextLocked(plan, reason, actor)));
  }

  /** Deletes at most one target table page while the source route stays fenced. */
  public Status discardNext(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = requested(id, expectedVersion, reason, writersStopped, actor);
    return Objects.requireNonNull(tx.execute(ignored -> discardLocked(plan, reason, actor)));
  }

  private Status discardLocked(ShardCatalog.Plan plan, String reason, Actor actor) {
    manifests.locked(plan);
    var source = manifests.read(plan.id(), actor);
    if (!core.queryForList(
            "SELECT plan_id FROM v3_shard_cutovers WHERE plan_id=? FOR UPDATE",
            String.class,
            plan.id())
        .isEmpty()) throw conflict("切流已开始，不能清理目标复制");
    try (var connection = gate.source(plan.targetShard()).getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        var target = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        target.setQueryTimeout(60);
        targetIdentity(target, plan);
        var header = header(target, plan.id());
        if (header == null) throw conflict("该计划没有可清理的目标批次");
        verifyHeader(header, plan, source);
        if ("DISCARDING".equals(header.state()) || "DISCARDED".equals(header.state())) {
          if (!reason.equals(header.discardReason())) throw conflict("分批清理原因与首次清理请求不一致");
        } else if (!"COPYING".equals(header.state()) && !"VERIFIED".equals(header.state())) {
          throw conflict("目标批次状态无效");
        }
        if ("DISCARDED".equals(header.state())) {
          if (!preflight.targetEmpty(plan)) throw conflict("已清理的目标桶重新出现数据");
        } else {
          target.update(
              "UPDATE v3_shard_copy_batch_plans SET state='DISCARDING',discard_reason=?,updated_at=? WHERE plan_id=?",
              reason,
              Timestamp.from(clock.instant()),
              plan.id());
          var probes = new ArrayList<>(ShardMigrationPreflight.BUCKET_PROBES);
          Collections.reverse(probes);
          for (var probe : probes) {
            int deleted = deletePage(target, probe, plan.bucket());
            if (deleted == 0) continue;
            connection.commit();
            return new Status(plan.id(), plan.bucket(), "DISCARDING", probe.name(), 0, 0, 0, 0);
          }
          target.update("DELETE FROM v3_shard_copy_id_map WHERE plan_id=?", plan.id());
          if (!preflight.targetEmpty(plan)) throw conflict("目标桶仍有数据，不能结束清理");
          target.update(
              "UPDATE v3_shard_copy_batch_plans SET state='DISCARDED',updated_at=? WHERE plan_id=?",
              Timestamp.from(clock.instant()),
              plan.id());
        }
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        throw failure;
      }
    } catch (SQLException unavailable) {
      throw new ApiException("SHARD_BATCH_DISCARD_UNAVAILABLE", 503, "目标批次清理事务失败，来源保持隔离；可按同一计划重试");
    } catch (DataAccessException unavailable) {
      throw new ApiException("SHARD_BATCH_DISCARD_UNAVAILABLE", 503, "目标批次清理数据不可用，来源保持隔离；可按同一计划重试");
    }
    var copy =
        core.queryForList(
            "SELECT state FROM v3_shard_copy_runs WHERE plan_id=? FOR UPDATE", plan.id());
    if (!copy.isEmpty() && "VERIFIED".equals(copy.getFirst().get("state"))) {
      var now = Timestamp.from(clock.instant());
      core.update(
          "UPDATE v3_shard_copy_runs SET state='DISCARDED',discarded_by=?,discard_reason=?,discarded_at=? WHERE plan_id=? AND state='VERIFIED'",
          actor.id(),
          reason,
          now,
          plan.id());
      core.update(
          "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES(?,NULL,?,'V3_SHARD_BATCH_DISCARD',?,?,?)",
          UUID.randomUUID().toString(),
          actor.id(),
          plan.id(),
          reason,
          now);
    } else if (!copy.isEmpty() && !"DISCARDED".equals(copy.getFirst().get("state"))) {
      throw conflict("核心复制记录状态无效");
    }
    return new Status(plan.id(), plan.bucket(), "DISCARDED", null, 0, 0, 0, 0);
  }

  private static int deletePage(
      JdbcTemplate target, ShardMigrationPreflight.Probe probe, int bucket) {
    var columns = probe.cursorColumns();
    var keys = new LinkedHashSet<List<Object>>();
    target.query(
        probe.pageSql(false),
        rows -> {
          var key = new ArrayList<Object>(columns.size());
          for (var column : columns) key.add(rows.getObject(column));
          keys.add(List.copyOf(key));
        },
        probe.pageArgs(bucket, null, null, BATCH_SIZE));
    String where = String.join(" AND ", columns.stream().map(column -> column + "=?").toList());
    String sql = "DELETE FROM " + probe.name() + " WHERE " + where;
    for (var key : keys) if (target.update(sql, key.toArray()) != 1) throw conflict("目标批次清理主键发生变化");
    return keys.size();
  }

  private ShardCatalog.Plan requested(
      String id, long expectedVersion, String reason, boolean writersStopped, Actor actor) {
    var plan = catalog.detail(id, actor);
    if (!writersStopped
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000
        || plan.version() != expectedVersion
        || !"PLANNED".equals(plan.state())
        || plan.sourceShard() != 0
        || plan.targetShard() == 0
        || plan.sourceEpoch() == Long.MAX_VALUE) throw conflict("分批复制须在来源停写、当前计划与有效原因下执行");
    return plan;
  }

  private Status nextLocked(ShardCatalog.Plan plan, String reason, Actor actor) {
    manifests.locked(plan);
    var source = manifests.read(plan.id(), actor);
    if (source.bucket() != plan.bucket()
        || source.sourceEpoch() != plan.sourceEpoch()
        || source.planVersion() != plan.version()
        || source.tables().size() != ShardMigrationPreflight.BUCKET_PROBES.size())
      throw conflict("来源清单与当前计划不一致");
    long total = source.tables().stream().mapToLong(ShardMigrationManifest.Table::count).sum();
    try (var connection = gate.source(plan.targetShard()).getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        var target = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        target.setQueryTimeout(60);
        targetIdentity(target, plan);
        var header = header(target, plan.id());
        if (header == null) {
          if (!preflight.targetEmpty(plan)) throw conflict("目标桶已有数据，不能开始分批复制");
          var now = Timestamp.from(clock.instant());
          target.update(
              "INSERT INTO v3_shard_copy_batch_plans(plan_id,bucket,source_epoch,plan_version,source_hash,copy_reason,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'COPYING',?,?)",
              plan.id(),
              plan.bucket(),
              plan.sourceEpoch(),
              plan.version(),
              source.hash(),
              reason,
              now,
              now);
          header = header(target, plan.id());
        }
        verifyHeader(header, plan, source);
        if (!reason.equals(header.copyReason())) throw conflict("分批续跑原因与首次复制请求不一致");
        if ("DISCARDING".equals(header.state()) || "DISCARDED".equals(header.state()))
          throw conflict("目标复制已进入回退阶段");
        if (!"COPYING".equals(header.state()) && !"VERIFIED".equals(header.state()))
          throw conflict("目标批次状态无效");
        if ("COPYING".equals(header.state())) {
          for (var probe : ShardMigrationPreflight.BUCKET_PROBES) {
            var expected =
                source.tables().stream()
                    .filter(t -> t.name().equals(probe.name()))
                    .findFirst()
                    .orElseThrow(() -> conflict("来源清单缺少桶表"));
            var progress = progress(target, plan.id(), probe.name());
            if (progress != null) {
              verifyProgress(progress, expected);
              if (progress.complete()) continue;
            } else {
              if (!target
                  .queryForList(
                      probe.existenceSql(), ShardMigrationPreflight.probeArgs(probe, plan.bucket()))
                  .isEmpty()) throw conflict("目标桶表有未登记的部分复制记录");
              // Large sources must not materialize every source ID to discover a collision.
              // Always remap these two local sequences and persist the canonical ID map.
              boolean remap =
                  "ledger_entries".equals(probe.name()) || "domain_events".equals(probe.name());
              target.update(
                  "INSERT INTO v3_shard_copy_batch_tables(plan_id,table_name,source_count,source_hash,remap_id) VALUES(?,?,?,?,?)",
                  plan.id(),
                  probe.name(),
                  expected.count(),
                  expected.hash(),
                  remap);
              progress = progress(target, plan.id(), probe.name());
            }
            var page = page(core, probe, plan.bucket(), progress);
            long copied = Math.addExact(progress.copiedCount(), page.rows().size());
            if (copied > expected.count() || page.rows().isEmpty() && copied != expected.count())
              throw conflict("分批来源行数与已记录清单不一致");
            insert(connection, probe, plan.id(), progress.remapId(), page);
            String cursorA =
                page.rows().isEmpty() ? progress.cursorA() : page.rows().getLast().cursorA();
            String cursorB =
                page.rows().isEmpty() ? progress.cursorB() : page.rows().getLast().cursorB();
            boolean complete = copied == expected.count();
            if (target.update(
                    "UPDATE v3_shard_copy_batch_tables SET copied_count=?,cursor_a=?,cursor_b=?,complete=? WHERE plan_id=? AND table_name=? AND copied_count=?",
                    copied,
                    cursorA,
                    cursorB,
                    complete,
                    plan.id(),
                    probe.name(),
                    progress.copiedCount())
                != 1) throw conflict("目标分批进度已变化");
            target.update(
                "UPDATE v3_shard_copy_batch_plans SET updated_at=? WHERE plan_id=?",
                Timestamp.from(clock.instant()),
                plan.id());
            connection.commit();
            return new Status(
                plan.id(),
                plan.bucket(),
                "COPYING",
                probe.name(),
                copied,
                expected.count(),
                copiedTotal(plan.id(), plan.targetShard()),
                total);
          }
        }
        for (var probe : ShardMigrationPreflight.BUCKET_PROBES) {
          var expected =
              source.tables().stream()
                  .filter(t -> t.name().equals(probe.name()))
                  .findFirst()
                  .orElseThrow(() -> conflict("来源清单缺少桶表"));
          var finished = progress(target, plan.id(), probe.name());
          if (finished == null || !finished.complete()) throw conflict("目标批次表尚未全部完成");
          verifyProgress(finished, expected);
        }
        Long progressRows =
            target.queryForObject(
                "SELECT COUNT(*) FROM v3_shard_copy_batch_tables WHERE plan_id=?",
                Long.class,
                plan.id());
        Long progressTotal =
            target.queryForObject(
                "SELECT COALESCE(SUM(copied_count),0) FROM v3_shard_copy_batch_tables WHERE plan_id=? AND complete=TRUE",
                Long.class,
                plan.id());
        if (!Objects.equals(progressRows, (long) ShardMigrationPreflight.BUCKET_PROBES.size())
            || !Objects.equals(progressTotal, total)) throw conflict("目标批次进度表不完整");
        var actual = ShardMigrationManifest.snapshot(target, plan.bucket(), plan.id());
        if (!actual.equals(source.tables())
            || !ShardMigrationManifest.manifestHash(actual).equals(source.hash()))
          throw conflict("目标分批复制与来源逐表摘要不一致");
        if (!ShardMigrationManifest.snapshot(core, plan.bucket()).equals(source.tables()))
          throw conflict("最终核对时来源事实已变化");
        if ("COPYING".equals(header.state()))
          target.update(
              "UPDATE v3_shard_copy_batch_plans SET state='VERIFIED',updated_at=? WHERE plan_id=? AND state='COPYING'",
              Timestamp.from(clock.instant()),
              plan.id());
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        throw failure;
      }
    } catch (SQLException unavailable) {
      if (unavailable.getSQLState() != null && unavailable.getSQLState().startsWith("23"))
        throw conflict("目标业务键冲突，复制批次已回滚");
      throw new ApiException("SHARD_BATCH_COPY_UNAVAILABLE", 503, "目标分批复制事务失败；来源保持隔离，可按同一计划重试");
    } catch (DuplicateKeyException collision) {
      throw conflict("目标业务键或编号发生冲突，当前批次已回滚");
    } catch (DataAccessException unavailable) {
      throw new ApiException("SHARD_BATCH_COPY_UNAVAILABLE", 503, "目标分批复制数据不可用；来源保持隔离，可按同一计划重试");
    }
    var existing =
        core.queryForList(
            "SELECT manifest_hash,total_rows,state FROM v3_shard_copy_runs WHERE plan_id=? FOR UPDATE",
            plan.id());
    if (existing.isEmpty()) {
      var now = Timestamp.from(clock.instant());
      core.update(
          "INSERT INTO v3_shard_copy_runs(plan_id,bucket,source_epoch,plan_version,manifest_hash,target_hash,total_rows,copied_by,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
          plan.id(),
          plan.bucket(),
          plan.sourceEpoch(),
          plan.version(),
          source.hash(),
          source.hash(),
          total,
          actor.id(),
          reason,
          now);
      core.update(
          "INSERT INTO audit_records(id,merchant_id,actor,operation,resource_id,detail,created_at) VALUES(?,NULL,?,'V3_SHARD_BATCH_COPY',?,?,?)",
          UUID.randomUUID().toString(),
          actor.id(),
          plan.id(),
          "hash=" + source.hash() + "; " + reason,
          now);
    } else if (!source.hash().equals(existing.getFirst().get("manifest_hash"))
        || ((Number) existing.getFirst().get("total_rows")).longValue() != total
        || !"VERIFIED".equals(existing.getFirst().get("state"))) {
      throw conflict("核心复制记录与目标分批结果不一致");
    }
    return new Status(plan.id(), plan.bucket(), "VERIFIED", null, 0, 0, total, total);
  }

  private static void verifyHeader(
      BatchPlan header, ShardCatalog.Plan plan, ShardMigrationManifest.Manifest source) {
    if (header.bucket() != plan.bucket()
        || header.sourceEpoch() != plan.sourceEpoch()
        || header.planVersion() != plan.version()
        || !header.sourceHash().equals(source.hash())) throw conflict("目标分批计划与来源清单不一致");
  }

  static void verifyProgress(Progress progress, ShardMigrationManifest.Table expected) {
    boolean mustRemap =
        "ledger_entries".equals(expected.name()) || "domain_events".equals(expected.name());
    if (progress.sourceCount() != expected.count()
        || !progress.sourceHash().equals(expected.hash())
        || progress.remapId() != mustRemap
        || progress.copiedCount() < 0
        || progress.copiedCount() > expected.count()
        || progress.complete() && progress.copiedCount() != expected.count()
        || progress.copiedCount() != 0 && progress.cursorA() == null)
      throw conflict("目标分批进度与来源清单不一致");
  }

  private static BatchPlan header(JdbcTemplate target, String planId) {
    var rows =
        target.query(
            "SELECT * FROM v3_shard_copy_batch_plans WHERE plan_id=? FOR UPDATE",
            (rs, ignored) ->
                new BatchPlan(
                    rs.getInt("bucket"),
                    rs.getLong("source_epoch"),
                    rs.getLong("plan_version"),
                    rs.getString("source_hash"),
                    rs.getString("copy_reason"),
                    rs.getString("discard_reason"),
                    rs.getString("state")),
            planId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  static Progress progress(JdbcTemplate target, String planId, String table) {
    var rows =
        target.query(
            "SELECT * FROM v3_shard_copy_batch_tables WHERE plan_id=? AND table_name=? FOR UPDATE",
            (rs, ignored) ->
                new Progress(
                    rs.getLong("source_count"),
                    rs.getString("source_hash"),
                    rs.getLong("copied_count"),
                    rs.getString("cursor_a"),
                    rs.getString("cursor_b"),
                    rs.getBoolean("remap_id"),
                    rs.getBoolean("complete")),
            planId,
            table);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private long copiedTotal(String planId, int targetShard) {
    return new JdbcTemplate(gate.source(targetShard))
        .queryForObject(
            "SELECT COALESCE(SUM(copied_count),0) FROM v3_shard_copy_batch_tables WHERE plan_id=?",
            Long.class,
            planId);
  }

  static PageRows page(
      JdbcTemplate source, ShardMigrationPreflight.Probe probe, int bucket, Progress progress) {
    return source.execute(
        (ConnectionCallback<PageRows>)
            connection -> {
              try (var statement =
                  connection.prepareStatement(
                      probe.pageSql(progress.cursorA() != null),
                      ResultSet.TYPE_FORWARD_ONLY,
                      ResultSet.CONCUR_READ_ONLY)) {
                statement.setQueryTimeout(60);
                var args =
                    probe.pageArgs(bucket, progress.cursorA(), progress.cursorB(), BATCH_SIZE);
                for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                try (var rows = statement.executeQuery()) {
                  var meta = rows.getMetaData();
                  var names = new ArrayList<String>();
                  for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String name = meta.getColumnLabel(i);
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw conflict("来源表列名不适合安全复制");
                    names.add(name);
                  }
                  var values = new ArrayList<Row>();
                  while (rows.next()) {
                    Object[] cells = new Object[names.size()];
                    for (int i = 0; i < cells.length; i++) cells[i] = rows.getObject(i + 1);
                    var cursors = probe.cursorColumns();
                    String first = rows.getString(cursors.getFirst());
                    String second = cursors.size() == 2 ? rows.getString(cursors.get(1)) : null;
                    if (first == null || cursors.size() == 2 && second == null)
                      throw conflict("来源表分页键为空");
                    values.add(new Row(cells, first, second));
                  }
                  return new PageRows(List.copyOf(names), List.copyOf(values));
                }
              }
            });
  }

  static void insert(
      Connection connection,
      ShardMigrationPreflight.Probe probe,
      String planId,
      boolean remap,
      PageRows page)
      throws SQLException {
    if (page.rows().isEmpty()) return;
    var columns = new ArrayList<>(page.columns());
    String idColumn = "domain_events".equals(probe.name()) ? "sequence_no" : "id";
    int idIndex = -1;
    if (remap)
      for (int i = 0; i < columns.size(); i++)
        if (idColumn.equalsIgnoreCase(columns.get(i))) {
          idIndex = i;
          break;
        }
    if (remap && idIndex < 0) throw conflict("来源自增列缺失");
    if (remap) columns.remove(idIndex);
    String sql =
        "INSERT INTO "
            + probe.name()
            + " ("
            + String.join(",", columns)
            + ") VALUES ("
            + String.join(",", java.util.Collections.nCopies(columns.size(), "?"))
            + ")";
    try (var write =
            connection.prepareStatement(
                sql, remap ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
        var map =
            remap
                ? connection.prepareStatement(
                    "INSERT INTO v3_shard_copy_id_map(plan_id,entity_name,source_id,target_id) VALUES(?,?,?,?)")
                : null) {
      for (var row : page.rows()) {
        int parameter = 1;
        for (int i = 0; i < row.values().length; i++)
          if (!remap || i != idIndex) write.setObject(parameter++, row.values()[i]);
        if (write.executeUpdate() != 1) throw conflict("目标批次行数异常");
        if (remap) {
          try (var keys = write.getGeneratedKeys()) {
            if (!keys.next()) throw conflict("目标自增编号缺失");
            long targetId = keys.getLong(1);
            if (targetId < 1 || keys.next()) throw conflict("目标自增编号无效");
            map.setString(1, planId);
            map.setString(2, probe.name());
            map.setLong(3, ((Number) row.values()[idIndex]).longValue());
            map.setLong(4, targetId);
            if (map.executeUpdate() != 1) throw conflict("目标编号映射未持久化");
          }
        }
      }
    }
  }

  private static void targetIdentity(JdbcTemplate target, ShardCatalog.Plan plan) {
    var identities =
        target.queryForList(
            "SELECT physical_shard FROM v3_shard_identity WHERE id=1", Integer.class);
    if (identities.size() != 1 || identities.getFirst() != plan.targetShard())
      throw conflict("目标物理身份不符合复制计划");
    var fences =
        target.queryForList(
            "SELECT route_epoch,access_state FROM v3_bucket_fences WHERE bucket=?", plan.bucket());
    if (fences.size() > 1
        || !fences.isEmpty()
            && (!"FENCED".equals(fences.getFirst().get("access_state"))
                || ((Number) fences.getFirst().get("route_epoch")).longValue()
                    != plan.sourceEpoch() + 1)) throw conflict("目标桶栅栏版本或状态不符合停写复制");
    String payment = "p" + Integer.toHexString(plan.bucket()) + "_";
    if (!target
            .queryForList(
                "SELECT payment_id FROM payment_admission_slots WHERE LEFT(payment_id,3)=? LIMIT 1",
                String.class,
                payment)
            .isEmpty()
        || !target
            .queryForList(
                "SELECT logical_shard FROM payment_admission WHERE logical_shard=? AND inflight<>0 LIMIT 1",
                Integer.class,
                plan.bucket())
            .isEmpty()
        || !target
            .queryForList(
                "SELECT status FROM event_quarantine WHERE status<>'REPLAYED' LIMIT 1",
                String.class)
            .isEmpty()) throw conflict("目标库仍有准入槽或隔离事件");
  }

  private static ApiException conflict(String message) {
    return new ApiException("SHARD_BATCH_COPY_CONFLICT", 409, message);
  }
}
