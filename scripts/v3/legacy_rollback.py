"""Guarded pre-write V3 Kubernetes binary rollback; dry run by default.

Never use for a bucket that has moved, a cold event, or target writes.
The evidence JSON must be independently captured from live systems.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

from release_boundary import NEW_CONTRACT_FLAGS, check
from live_legacy_boundary import inspect_live


DEPLOYMENTS = ("merchantpay-channel-worker", "merchantpay-bulk-worker",
               "merchantpay-projection-worker", "merchantpay-api")


def deployment_stopped(live: dict) -> bool:
    return live.get("spec", {}).get("replicas") == 0 \
        and all(live.get("status", {}).get(key, 0) == 0 for key in
                ("replicas", "readyReplicas", "availableReplicas", "updatedReplicas"))


def commands(namespace: str, image: str) -> list[list[str]]:
    if not re.fullmatch(r"[a-zA-Z0-9._/-]+@sha256:[a-f0-9]{64}", image):
        raise ValueError("old image must be pinned to a sha256 digest")
    if not re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?", namespace):
        raise ValueError("invalid Kubernetes namespace")
    result = []
    for deployment in DEPLOYMENTS:
        result.append(["kubectl", "-n", namespace, "set", "image",
                       f"deployment/{deployment}", f"platform={image}"])
        result.append(["kubectl", "-n", namespace, "rollout", "status",
                       f"deployment/{deployment}", "--timeout=10m"])
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--old-image", required=True)
    parser.add_argument("--namespace", default="merchantpay-v3")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    snapshot = json.loads(args.evidence.read_text(encoding="utf-8"))
    failures = check(snapshot, "legacy-rollback")
    if failures:
        for failure in failures:
            print("BLOCKED:", failure)
        return 1
    steps = commands(args.namespace, args.old_image)
    if not args.execute:
        for step in steps:
            print("DRY_RUN:", " ".join(step))
        return 0
    observed = subprocess.run(
        ["kubectl", "-n", args.namespace, "get", "configmap",
         "merchantpay-v3-flags", "-o", "json"],
        check=True, capture_output=True, text=True,
    )
    flags = json.loads(observed.stdout).get("data", {})
    if any(flags.get(name) != "false" for name in NEW_CONTRACT_FLAGS):
        raise ValueError("live Kubernetes flags are not all disabled")
    for deployment in DEPLOYMENTS:
        observed = subprocess.run(
            ["kubectl", "-n", args.namespace, "get", "deployment", deployment,
             "-o", "json"], check=True, capture_output=True, text=True,
        )
        live = json.loads(observed.stdout)
        if not deployment_stopped(live):
            raise ValueError(f"deployment {deployment} must be fully scaled to zero")
    import pymysql  # type: ignore[import-not-found]
    import os
    from verify_analytics import _mysql_connection

    connections = []
    try:
        for shard in range(4):
            connections.append(_mysql_connection(
                shard, os.environ["MYSQL_CA_FILE"], pymysql))
        inspect_live(connections, snapshot)
    finally:
        for connection in connections:
            connection.rollback()
            connection.close()
    for step in steps:
        subprocess.run(step, check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
