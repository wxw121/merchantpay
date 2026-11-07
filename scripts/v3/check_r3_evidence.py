"""Fail-closed R3 evidence gate. It never generates measurements.

Usage on an independent acceptance host:
    python check_r3_evidence.py /absolute/path/to/r3-evidence.json
The JSON file and every listed artifact stay in the same evidence directory.
"""

from __future__ import annotations

import hashlib
import json
import math
import sys
from pathlib import Path


class EvidenceError(ValueError):
    pass


def section(value: object, name: str) -> dict:
    if not isinstance(value, dict):
        raise EvidenceError(f"missing object: {name}")
    return value


def number(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError(f"missing number: {name}")
    result = float(value)
    if not math.isfinite(result) or result < 0:
        raise EvidenceError(f"invalid number: {name}")
    return result


def at_least(data: dict, key: str, limit: float, name: str) -> None:
    if number(data.get(key), name) < limit:
        raise EvidenceError(f"{name} below {limit:g}")


def at_most(data: dict, key: str, limit: float, name: str) -> None:
    if number(data.get(key), name) > limit:
        raise EvidenceError(f"{name} above {limit:g}")


def true(data: dict, key: str, name: str) -> None:
    if data.get(key) is not True:
        raise EvidenceError(f"{name} is not proven")


def artifacts(data: object, directory: Path) -> None:
    rows = data if isinstance(data, list) else []
    required = {
        "payment-load", "query-load", "mixed-load", "dataset-inventory",
        "bill-process", "soak-metrics", "money-reconciliation", "fault-recovery",
    }
    found: set[str] = set()
    for item in rows:
        entry = section(item, "artifact")
        kind = entry.get("kind")
        relative = entry.get("path")
        expected = entry.get("sha256")
        if not isinstance(kind, str) or kind in found:
            raise EvidenceError("artifact kind missing or duplicated")
        if not isinstance(relative, str) or not relative or not isinstance(expected, str):
            raise EvidenceError(f"artifact path or SHA-256 missing: {kind}")
        if len(expected) != 64 or any(c not in "0123456789abcdef" for c in expected):
            raise EvidenceError(f"artifact SHA-256 malformed: {kind}")
        path = (directory / relative).resolve()
        if not path.is_relative_to(directory) or not path.is_file():
            raise EvidenceError(f"artifact outside evidence directory or missing: {kind}")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise EvidenceError(f"artifact hash mismatch: {kind}")
        found.add(kind)
    if required - found:
        raise EvidenceError("missing artifact kinds: " + ", ".join(sorted(required - found)))


def verify(data: object, directory: Path) -> None:
    root = section(data, "R3 evidence")
    environment = section(root.get("environment"), "environment")
    true(environment, "independent", "independent R3 environment")
    if not isinstance(environment.get("runId"), str) or not environment["runId"].strip():
        raise EvidenceError("run ID missing")

    payment = section(root.get("payment"), "payment")
    at_least(payment, "durationSeconds", 3600, "payment duration")
    at_least(payment, "completedTpsLowerBound", 5000, "completed payment TPS")
    at_most(payment, "financialErrors", 0, "financial errors")

    query = section(root.get("query"), "query")
    at_least(query, "durationSeconds", 900, "query duration")
    at_least(query, "successfulQpsLowerBound", 20000, "successful exact-query QPS")
    at_most(query, "wrongResults", 0, "wrong exact-query results")

    mixed = section(root.get("mixed"), "mixed")
    at_least(mixed, "durationSeconds", 900, "mixed duration")
    at_least(mixed, "completedPayments", 1, "mixed completed payments")
    at_least(mixed, "successfulQueries", 1, "mixed successful queries")
    true(mixed, "moneyMatched", "mixed money reconciliation")

    dataset = section(root.get("dataset"), "dataset")
    at_least(dataset, "payments", 100_000_000, "payment rows")
    at_least(dataset, "ledgerEntries", 500_000_000, "ledger entries")
    at_least(dataset, "domainEvents", 1_000_000_000, "domain events")
    true(dataset, "historyVerified", "history object verification")

    bill = section(root.get("bill"), "bill")
    at_least(bill, "rows", 10_000_000, "bill rows")
    at_most(bill, "durationSeconds", 1800, "bill completion time")
    true(bill, "sealedAndMatched", "bill seal and money reconciliation")

    soak = section(root.get("soak"), "soak")
    at_least(soak, "durationSeconds", 86400, "soak duration")
    at_most(soak, "fatalErrors", 0, "soak fatal errors")
    true(soak, "moneyMatched", "soak money reconciliation")

    integrity = section(root.get("integrity"), "integrity")
    for name in ("ledgerBalanced", "refundPrincipalBounded", "noDuplicateSettlement",
                 "eventReplayIdempotent", "fourSourceProjectionMatched",
                 "archiveObjectsVerified", "contractsCompatible"):
        true(integrity, name, name)

    faults = section(root.get("faults"), "faults")
    for name in ("apiPod", "workerPod", "mysqlSource", "kafkaConsumer",
                 "objectStorage", "partialCutover", "backupRestore"):
        result = section(faults.get(name), name)
        true(result, "recovered", f"{name} recovered")
        true(result, "moneyMatched", f"{name} money reconciliation")

    artifacts(root.get("artifacts"), directory)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check_r3_evidence.py ABSOLUTE_EVIDENCE_JSON", file=sys.stderr)
        return 2
    path = Path(sys.argv[1]).resolve()
    try:
        if not path.is_file():
            raise EvidenceError("evidence JSON missing")
        verify(json.loads(path.read_text(encoding="utf-8")), path.parent)
    except (EvidenceError, json.JSONDecodeError, OSError) as error:
        print(f"R3 NOT_ACCEPTED: {error}", file=sys.stderr)
        return 1
    print("R3 evidence gate passed; review the attached raw artifacts before release.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
