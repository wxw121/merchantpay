"""Read live four-MySQL facts immediately before a stopped V3 binary rollback."""

from __future__ import annotations

from typing import Any

from release_boundary import REQUIRED_VERSION


def _rows(cursor: Any, sql: str) -> tuple:
    cursor.execute(sql)
    return tuple(cursor.fetchall())


def inspect_live(connections: list[Any], snapshot: dict) -> None:
    if len(connections) != 4:
        raise ValueError("four live database connections are required")
    cold = 0
    for physical, connection in enumerate(connections):
        with connection.cursor() as cursor:
            if _rows(cursor, "SELECT version,success FROM flyway_schema_history "
                     "ORDER BY installed_rank DESC LIMIT 1") != ((str(REQUIRED_VERSION), 1),):
                raise ValueError(f"live database {physical} Flyway version changed")
            if _rows(cursor, "SELECT physical_shard FROM v3_shard_identity WHERE id=1") \
                    != ((physical,),):
                raise ValueError(f"live database {physical} identity changed")
            if _rows(cursor, "SELECT mode FROM event_transport WHERE id=1") \
                    != (("POLLING",),):
                raise ValueError(f"live database {physical} event transport is not POLLING")
            if physical:
                active = _rows(cursor, "SELECT COUNT(*) FROM v3_bucket_fences "
                               "WHERE access_state='ACTIVE'")
                if active != ((0,),):
                    raise ValueError(f"live target {physical} has ACTIVE fences")
            cold_rows = _rows(cursor, "SELECT COUNT(*) FROM v3_history_event_locators "
                              "WHERE state='COLD'")
            if len(cold_rows) != 1:
                raise ValueError("cold locator count unavailable")
            cold += int(cold_rows[0][0])
    if cold != 0 or snapshot.get("coldLocatorCount") != cold:
        raise ValueError("cold events exist or changed since evidence capture")
    with connections[0].cursor() as cursor:
        routes = _rows(cursor, "SELECT bucket,physical_shard,epoch FROM v3_shard_routes "
                       "ORDER BY bucket")
        if len(routes) != 16 or any(bucket != index or owner != 0 or epoch < 1
                                    for index, (bucket, owner, epoch) in enumerate(routes)):
            raise ValueError("live core routes are not sixteen core-owned buckets")
        observed_routes = [{"bucket": int(bucket), "physicalShard": int(owner),
                            "epoch": int(epoch)} for bucket, owner, epoch in routes]
        if observed_routes != snapshot.get("routes"):
            raise ValueError("live core routes differ from reviewed evidence")
        fences = _rows(cursor, "SELECT bucket,route_epoch,access_state FROM v3_bucket_fences "
                       "ORDER BY bucket")
        observed_fences = [{"bucket": int(bucket), "routeEpoch": int(epoch),
                            "accessState": state} for bucket, epoch, state in fences]
        expected_fences = [{"bucket": route["bucket"], "routeEpoch": route["epoch"],
                            "accessState": "ACTIVE"} for route in observed_routes]
        if observed_fences != expected_fences \
                or observed_fences != snapshot.get("coreFences"):
            raise ValueError("live core fences are not all ACTIVE at route epochs")
        checks = {
            "openMigrationCount": "SELECT COUNT(*) FROM v3_shard_migrations "
                                  "WHERE state<>'CANCELLED'",
            "reverseRunCount": "SELECT COUNT(*) FROM v3_shard_reverse_runs",
            "cutoverCount": "SELECT COUNT(*) FROM v3_shard_cutovers",
        }
        for key, sql in checks.items():
            rows = _rows(cursor, sql)
            if rows != ((0,),) or snapshot.get(key) != 0:
                raise ValueError(f"live {key} is nonzero or changed")
