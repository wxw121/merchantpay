"""Fail-closed checks for an operator-captured V3 release/rollback snapshot.

This does not query live systems. Capture and independently review the evidence
described in docs/releases/v3-kubernetes.md before using this gate.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


REQUIRED_VERSION = 50
NEW_CONTRACT_FLAGS = (
    "SHARD_EXACT_READS_ENABLED",
    "SHARD_MONEY_WRITES_ENABLED",
    "SHARD_TARGET_HTTP_WRITES_ENABLED",
    "SHARD_CHANNEL_WORKERS_ENABLED",
    "SHARD_CDC_ENABLED",
    "SHARD_PROJECTION_ENABLED",
    "SHARD_PROJECTION_CONSUMER_ENABLED",
    "SHARD_PROJECTION_WORKER_ENABLED",
    "SHARD_MIGRATION_QUIESCE_ENABLED",
    "SHARD_MIGRATION_COPY_ENABLED",
    "SHARD_MIGRATION_BATCH_ENABLED",
    "SHARD_MIGRATION_CUTOVER_ENABLED",
    "SHARD_REVERSE_ENABLED",
    "HISTORY_ARCHIVE_ENABLED",
    "HISTORY_EVENT_RETIRE_ENABLED",
    "FLINK_ANALYTICS_ENABLED",
    "ANALYTICS_PUBLICATION_ENABLED",
)


def check(snapshot: dict, mode: str) -> list[str]:
    failures: list[str] = []
    databases = snapshot.get("databases")
    if not isinstance(databases, list) or len(databases) != 4:
        return ["exactly four database observations are required"]
    physicals = [row.get("physicalShard") for row in databases if isinstance(row, dict)]
    if len(physicals) != 4 or any(type(value) is not int for value in physicals) \
            or set(physicals) != {0, 1, 2, 3}:
        failures.append("database physical identities must be 0,1,2,3 exactly once")
    for row in databases:
        if not isinstance(row, dict):
            failures.append("database observation must be an object")
            continue
        if row.get("flywayVersion") != REQUIRED_VERSION:
            failures.append(f"database {row.get('physicalShard')} must be at V{REQUIRED_VERSION}")
        if row.get("identity") != row.get("physicalShard"):
            failures.append(f"database {row.get('physicalShard')} identity mismatch")
    routes = snapshot.get("routes")
    if not isinstance(routes, list) or len(routes) != 16:
        failures.append("exactly sixteen core routes are required")
    else:
        buckets = [row.get("bucket") for row in routes if isinstance(row, dict)]
        if len(buckets) != 16 or any(type(value) is not int for value in buckets) \
                or set(buckets) != set(range(16)):
            failures.append("core routes must cover buckets 0..15 exactly once")
        for route in routes:
            if not isinstance(route, dict) or route.get("physicalShard") not in range(4) \
                    or not isinstance(route.get("epoch"), int) or route["epoch"] < 1:
                failures.append("route owner or epoch is invalid")
                break
    core_fences = snapshot.get("coreFences")
    if not isinstance(core_fences, list) or len(core_fences) != 16:
        failures.append("exactly sixteen core fence observations are required")
    elif isinstance(routes, list) and len(routes) == 16:
        by_bucket = {item.get("bucket"): item for item in core_fences
                     if isinstance(item, dict)}
        if len(by_bucket) != 16 or set(by_bucket) != set(range(16)):
            failures.append("core fences must cover buckets 0..15 exactly once")
        else:
            for route in routes:
                if not isinstance(route, dict) or route.get("bucket") not in by_bucket:
                    continue
                fence = by_bucket[route["bucket"]]
                expected_state = "ACTIVE" if route.get("physicalShard") == 0 else "FENCED"
                if fence.get("accessState") != expected_state \
                        or fence.get("routeEpoch") != route.get("epoch"):
                    failures.append(f"core fence mismatch for bucket {route['bucket']}")
    if snapshot.get("backupRestoreVerified") is not True:
        failures.append("restore-tested four-database and object-store backup is required")
    if snapshot.get("writersStopped") is not True:
        failures.append("writers-stopped observation is required")
    if mode == "bootstrap":
        for row in databases:
            if not isinstance(row, dict) or row.get("physicalShard") == 0:
                continue
            if row.get("businessRows") != 0 or row.get("activeFences") != 0:
                failures.append(f"target {row.get('physicalShard')} must be empty and unfenced")
        if isinstance(routes, list) and any(not isinstance(row, dict)
                                            or row.get("physicalShard") != 0 for row in routes):
            failures.append("bootstrap routes must all remain on core 0")
    elif mode == "legacy-rollback":
        for key in ("openMigrationCount", "reverseRunCount", "cutoverCount",
                    "coldLocatorCount"):
            if snapshot.get(key) != 0:
                failures.append(f"{key} must be explicitly zero")
        for row in databases:
            if isinstance(row, dict) and row.get("physicalShard") != 0 \
                    and row.get("activeFences") != 0:
                failures.append(f"target {row.get('physicalShard')} has active fences")
        flags = snapshot.get("flags")
        if not isinstance(flags, dict) or any(flags.get(name) is not False
                                               for name in NEW_CONTRACT_FLAGS):
            failures.append("all new-contract flags must be explicitly false")
        for key in ("targetWritesEverEnabled", "coldEventsEverRetired",
                    "cdcSwitched", "routeMovesEverCompleted"):
            if snapshot.get(key) is not False:
                failures.append(f"{key} must be explicitly false")
        if snapshot.get("oldBinaryContractVerified") is not True:
            failures.append("old binary/schema/event/task contract review is required")
        if snapshot.get("eventTransportMode") != "POLLING":
            failures.append("legacy rollback requires POLLING event transport")
        if isinstance(routes, list) and any(not isinstance(row, dict)
                                            or row.get("physicalShard") != 0 for row in routes):
            failures.append("legacy rollback requires all buckets on core 0")
    else:
        failures.append("unsupported mode")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("bootstrap", "legacy-rollback"))
    parser.add_argument("snapshot", type=Path)
    args = parser.parse_args()
    failures = check(json.loads(args.snapshot.read_text(encoding="utf-8")), args.mode)
    for failure in failures:
        print("BLOCKED:", failure)
    if failures:
        return 1
    print("GATE_PASS: review the source observations before changing infrastructure")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
