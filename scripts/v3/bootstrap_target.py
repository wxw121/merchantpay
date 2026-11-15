"""Prepare a separately migrated, empty V3 target as physical shard 1..3.

Dry run by default. Requires PyMySQL and MYSQL_S{N}_* / MYSQL_CA_FILE from
verify_analytics.py. Run only in the independent release environment.
"""

from __future__ import annotations

import argparse
import os
import re
from typing import Any

from release_boundary import REQUIRED_VERSION


SEEDED = {
    "flyway_schema_history", "payment_admission", "payment_admission_mode",
    "payment_admission_slots", "event_transport", "projection_generations",
    "projection_control", "channel_controls", "v3_physical_shards",
    "v3_shard_routes", "v3_shard_identity", "v3_bucket_fences",
}


def inspect_and_prepare(connection: Any, physical: int, apply: bool) -> list[str]:
    if physical not in (1, 2, 3):
        raise ValueError("target physical shard must be 1, 2 or 3")
    try:
        connection.begin()
        with connection.cursor() as cursor:
            cursor.execute("SELECT version,success FROM flyway_schema_history "
                           "ORDER BY installed_rank DESC LIMIT 1")
            latest = cursor.fetchall()
            if latest != ((str(REQUIRED_VERSION), 1),):
                raise ValueError(f"target must have successful Flyway V{REQUIRED_VERSION}")
            cursor.execute("SELECT physical_shard FROM v3_shard_identity WHERE id=1 FOR UPDATE")
            identity = cursor.fetchall()
            if identity == ((physical,),):
                raise ValueError("target already bootstrapped; refusing to repeat")
            if identity != ((0,),):
                raise ValueError("target is not an untouched core candidate")
            cursor.execute("SELECT bucket,physical_shard,epoch FROM v3_shard_routes "
                           "ORDER BY bucket FOR UPDATE")
            if cursor.fetchall() != tuple((bucket, 0, 1) for bucket in range(16)):
                raise ValueError("target candidate routes changed")
            cursor.execute("SELECT bucket,route_epoch,access_state FROM v3_bucket_fences "
                           "ORDER BY bucket FOR UPDATE")
            if cursor.fetchall() != tuple((bucket, 1, "ACTIVE")
                                         for bucket in range(16)):
                raise ValueError("target candidate fences changed")
            cursor.execute("SELECT 1 FROM payment_admission_slots "
                           "WHERE payment_id IS NOT NULL LIMIT 1")
            if cursor.fetchone() is not None:
                raise ValueError("target admission slots contain payment ownership")
            cursor.execute("SELECT 1 FROM payment_admission WHERE inflight<>0 LIMIT 1")
            if cursor.fetchone() is not None:
                raise ValueError("target admission counters are not empty")
            cursor.execute("SELECT mode FROM event_transport WHERE id=1")
            if cursor.fetchall() != (("POLLING",),):
                raise ValueError("target transport mode is not the initial polling mode")
            cursor.execute("SELECT id,state FROM projection_generations")
            if cursor.fetchall() != (("v2-initial", "BUILDING"),):
                raise ValueError("target projection generation changed")
            cursor.execute("SHOW TABLES")
            tables = [row[0] for row in cursor.fetchall()]
            if not set(SEEDED).issubset(tables):
                raise ValueError("target schema is missing a required control table")
            checked = []
            for table in tables:
                if table in SEEDED:
                    continue
                if not re.fullmatch(r"[A-Za-z0-9_]+", table):
                    raise ValueError("unexpected target table name")
                cursor.execute(f"SELECT 1 FROM `{table}` LIMIT 1")
                if cursor.fetchone() is not None:
                    raise ValueError(f"target business/control table is not empty: {table}")
                checked.append(table)
            if apply:
                cursor.execute("DELETE FROM v3_bucket_fences")
                if cursor.rowcount != 16:
                    raise ValueError("candidate fence deletion was incomplete")
                cursor.execute("UPDATE v3_shard_identity SET physical_shard=%s "
                               "WHERE id=1 AND physical_shard=0", (physical,))
                if cursor.rowcount != 1:
                    raise ValueError("candidate identity changed")
        if apply:
            connection.commit()
        else:
            connection.rollback()
        return checked
    except BaseException:
        connection.rollback()
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=int, choices=(1, 2, 3), required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    import pymysql  # type: ignore[import-not-found]
    from verify_analytics import _mysql_connection

    connection = _mysql_connection(args.target, os.environ["MYSQL_CA_FILE"], pymysql)
    try:
        checked = inspect_and_prepare(connection, args.target, args.apply)
    finally:
        connection.close()
    print(f"target {args.target}: checked {len(checked)} empty tables; "
          + ("identity and fences updated" if args.apply else "dry run only"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
