"""Stream-compare a stopped-writer four-source payment aggregate with ClickHouse.

This emits comparison evidence. It does not prove that writers were stopped or
promote a generation; an independent operator must check the CDC/checkpoint
watermarks before treating a matching comparison as release evidence.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import heapq
import itertools
import json
import os
from pathlib import Path
import urllib.parse
import urllib.request
from typing import Any, Iterable, Iterator

Row = tuple[str, str, str, int, int, int, int]
FIELDS = ("payments", "succeeded", "succeeded_minor", "refunded_minor")


def parse_row(raw: dict[str, Any]) -> Row:
    key = tuple(str(raw[field]) for field in ("merchant_id", "business_day", "channel"))
    if not all(key):
        raise ValueError("aggregate key is empty")
    values = []
    for field in FIELDS:
        value = raw[field]
        if isinstance(value, bool) or not str(value).isdigit():
            raise ValueError(f"invalid aggregate {field}")
        values.append(int(value))
    if values[1] > values[0]:
        raise ValueError("successful payments exceed all payments")
    return (*key, *values)


def merge_sources(sources: Iterable[Iterator[Row]]) -> Iterator[Row]:
    ordered = heapq.merge(*sources, key=lambda row: row[:3])
    for key, group in itertools.groupby(ordered, key=lambda row: row[:3]):
        total = [0, 0, 0, 0]
        for row in group:
            for index, value in enumerate(row[3:]):
                total[index] += value
        yield (*key, *total)


def compare(source: Iterator[Row], target: Iterator[Row]) -> dict[str, Any]:
    source_hash, target_hash = hashlib.sha256(), hashlib.sha256()
    source_count = target_count = mismatch_count = 0
    source_payments = target_payments = 0
    first_mismatches = []
    previous_source = previous_target = None
    actual = next(source, None)
    observed = next(target, None)
    while actual is not None or observed is not None:
        source_next = actual is not None and (observed is None or actual[:3] <= observed[:3])
        target_next = observed is not None and (actual is None or observed[:3] <= actual[:3])
        left = actual if source_next else None
        right = observed if target_next else None
        if left is not None:
            if previous_source is not None and left[:3] <= previous_source:
                raise ValueError("source aggregation is not strictly ordered")
            previous_source = left[:3]
            source_hash.update((json.dumps(left, ensure_ascii=False, separators=(",", ":")) + "\n").encode())
            source_count += 1
            source_payments += left[3]
        if right is not None:
            if previous_target is not None and right[:3] <= previous_target:
                raise ValueError("ClickHouse aggregation is not strictly ordered")
            previous_target = right[:3]
            target_hash.update((json.dumps(right, ensure_ascii=False, separators=(",", ":")) + "\n").encode())
            target_count += 1
            target_payments += right[3]
        if left != right:
            mismatch_count += 1
            if len(first_mismatches) < 20:
                first_mismatches.append({"source": left, "clickhouse": right})
        if source_next:
            actual = next(source, None)
        if target_next:
            observed = next(target, None)
    return {
        "status": "MATCHED_UNSEALED" if mismatch_count == 0 else "MISMATCH",
        "sourceGroups": source_count,
        "clickhouseGroups": target_count,
        "sourcePayments": source_payments,
        "clickhousePayments": target_payments,
        "sourceSha256": source_hash.hexdigest(),
        "clickhouseSha256": target_hash.hexdigest(),
        "mismatchPairs": mismatch_count,
        "firstMismatches": first_mismatches,
    }


def mysql_rows(connection: Any, buckets: list[int], cursor_class: Any) -> Iterator[Row]:
    if not buckets:
        return
    slots = ",".join("%s" for _ in buckets)
    sql = (
        "SELECT merchant_id,DATE(created_at) AS business_day,channel,"
        "COUNT(*) AS payments,"
        "SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,"
        "SUM(CASE WHEN status='SUCCEEDED' THEN amount_minor ELSE 0 END) AS succeeded_minor,"
        "SUM(refunded_minor) AS refunded_minor "
        f"FROM payments WHERE logical_shard IN ({slots}) "
        "GROUP BY merchant_id,DATE(created_at),channel "
        "ORDER BY merchant_id,business_day,channel"
    )
    with connection.cursor(cursor_class) as cursor:
        cursor.execute(sql, buckets)
        for merchant, day, channel, payments, succeeded, amount, refunded in cursor:
            yield parse_row({"merchant_id": merchant, "business_day": day.isoformat(),
                             "channel": channel, "payments": payments,
                             "succeeded": succeeded, "succeeded_minor": amount,
                             "refunded_minor": refunded})


def clickhouse_rows(url: str, user: str, password: str, generation: str) -> Iterator[Row]:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.query:
        raise ValueError("verified analytics require an HTTPS ClickHouse endpoint")
    query = "SELECT merchant_id,business_day,channel,payments,succeeded,"
    query += "succeeded_minor,refunded_minor FROM merchantpay.v3_payment_aggregates FINAL "
    query += "WHERE generation={generation:String} AND deleted=0 "
    query += "ORDER BY merchant_id,business_day,channel FORMAT JSONEachRow"
    endpoint = url.rstrip("/") + "/?" + urllib.parse.urlencode({"param_generation": generation})
    request = urllib.request.Request(endpoint, query.encode(),
                                     {"X-ClickHouse-User": user,
                                      "X-ClickHouse-Key": password}, method="POST")
    with urllib.request.urlopen(request, timeout=3600) as response:
        if response.status != 200:
            raise RuntimeError("ClickHouse aggregate query failed")
        for line in response:
            if len(line) > 1_048_576:
                raise ValueError("ClickHouse aggregate row exceeds 1 MiB")
            yield parse_row(json.loads(line))


def _mysql_connection(shard: int, ca_file: str, pymysql: Any) -> Any:
    prefix = f"MYSQL_S{shard}_"
    return pymysql.connect(
        host=os.environ[prefix + "HOST"],
        port=int(os.environ.get(prefix + "PORT", "3306")),
        user=os.environ[prefix + "USER"],
        password=os.environ[prefix + "PASSWORD"],
        database=os.environ[prefix + "DATABASE"],
        ssl_ca=ca_file, ssl_verify_cert=True, ssl_verify_identity=True,
        autocommit=False, charset="utf8mb4",
        init_command="SET time_zone = '+08:00'",
    )


def _routes_and_fences(connections: list[Any], generation: str) -> list[tuple[int, int, int]]:
    with connections[0].cursor() as cursor:
        cursor.execute("SELECT bucket,physical_shard,epoch FROM v3_shard_routes "
                       "ORDER BY bucket FOR UPDATE")
        routes = [(int(bucket), int(physical), int(epoch)) for bucket, physical, epoch in cursor]
    if len(routes) != 16 or any(route[0] != index or route[1] not in range(4) or route[2] < 1
                                for index, route in enumerate(routes)):
        raise ValueError("core route catalog is incomplete")
    for physical, connection in enumerate(connections):
        with connection.cursor() as cursor:
            cursor.execute("SELECT physical_shard FROM v3_shard_identity WHERE id=1")
            identity = cursor.fetchall()
            if identity != ((physical,),):
                raise ValueError(f"source {physical} identity mismatch")
            cursor.execute("SELECT active_generation FROM projection_control WHERE id=1")
            active = cursor.fetchall()
            if active != ((generation,),):
                raise ValueError(f"source {physical} serving generation mismatch")
            cursor.execute("SELECT state,scan_complete FROM projection_generations WHERE id=%s",
                           (generation,))
            serving = cursor.fetchall()
            if len(serving) != 1 or serving[0][0] != "SERVING" or not serving[0][1]:
                raise ValueError(f"source {physical} projection generation is not serving")
            cursor.execute("SELECT mode FROM event_transport WHERE id=1")
            if cursor.fetchall() != (("CDC",),):
                raise ValueError(f"source {physical} event transport is not CDC")
            cursor.execute("SELECT bucket,route_epoch,access_state FROM v3_bucket_fences")
            fences = {int(bucket): (int(epoch), state) for bucket, epoch, state in cursor}
            for bucket, owner, epoch in routes:
                fence = fences.get(bucket)
                if owner == physical and fence != (epoch, "ACTIVE"):
                    raise ValueError(f"source {physical} bucket {bucket} lacks active fence")
                if owner != physical and fence is not None and fence[1] == "ACTIVE":
                    raise ValueError(f"source {physical} bucket {bucket} is falsely active")
    return routes


def source_gtids(connections: list[Any]) -> list[dict[str, str]]:
    """Observe committed source positions outside transaction snapshot semantics."""
    positions = []
    for physical, connection in enumerate(connections):
        with connection.cursor() as cursor:
            cursor.execute("SELECT @@GLOBAL.server_uuid,@@GLOBAL.gtid_executed")
            rows = cursor.fetchall()
        if len(rows) != 1 or len(rows[0]) != 2:
            raise ValueError(f"source {physical} GTID position is unavailable")
        server, gtid = rows[0]
        if not isinstance(server, str) or not server or not isinstance(gtid, str) or not gtid:
            raise ValueError(f"source {physical} requires a nonempty GTID position")
        positions.append({"physicalShard": physical, "serverUuid": server,
                          "gtidExecuted": gtid})
    if len({item["serverUuid"] for item in positions}) != 4:
        raise ValueError("the four physical sources must have distinct server UUIDs")
    return positions


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generation", required=True)
    parser.add_argument("--writers-stopped", action="store_true", required=True)
    parser.add_argument("--min-payments", type=int, default=1)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.writers_stopped or not args.generation or len(args.generation) > 64:
        raise ValueError("stopped-writer declaration and generation are required")
    if args.min_payments < 1:
        raise ValueError("minimum payment count must be positive")
    if args.output.exists() or args.output.with_suffix(args.output.suffix + ".tmp").exists():
        raise ValueError("comparison evidence output already exists")
    ca_file = os.environ["MYSQL_CA_FILE"]
    if not Path(ca_file).is_file():
        raise ValueError("MySQL CA file is missing")
    import pymysql  # type: ignore[import-not-found]

    connections: list[Any] = []
    try:
        for shard in range(4):
            connections.append(_mysql_connection(shard, ca_file, pymysql))
        for connection in connections:
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                cursor.execute("START TRANSACTION READ ONLY" if connection != connections[0]
                               else "START TRANSACTION")
        routes = _routes_and_fences(connections, args.generation)
        positions = source_gtids(connections)
        sources = [mysql_rows(connections[physical],
                              [bucket for bucket, owner, _ in routes if owner == physical],
                              pymysql.cursors.SSCursor) for physical in range(4)]
        result = compare(
            merge_sources(sources),
            clickhouse_rows(os.environ["CLICKHOUSE_URL"],
                            os.environ["CLICKHOUSE_USER"],
                            os.environ["CLICKHOUSE_PASSWORD"], args.generation),
        )
        if positions != source_gtids(connections):
            result["status"] = "SOURCE_POSITION_CHANGED"
        result.update({"generation": args.generation,
                       "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                       "routes": [{"bucket": bucket, "physicalShard": owner,
                                   "epoch": epoch} for bucket, owner, epoch in routes],
                       "writersStoppedDeclared": True,
                       "sourceGtidPositions": positions,
                       "watermarkVerified": False})
        if result["sourcePayments"] < args.min_payments:
            result["status"] = "BELOW_MINIMUM"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8")
        temporary.replace(args.output)
        if result["status"] != "MATCHED_UNSEALED":
            raise SystemExit("aggregate mismatch; inspect comparison evidence")
    finally:
        for connection in connections:
            connection.rollback()
            connection.close()


if __name__ == "__main__":
    main()
