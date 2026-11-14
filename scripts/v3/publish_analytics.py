"""Verify live release watermarks and sign a single-use analytics publication request.

Run from an independent environment while writers remain stopped. The platform
rechecks the four source positions and route before storing the publication.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
import re
from pathlib import Path
import urllib.parse
import urllib.request
from typing import Any

if __package__:
    from .aggregate_bridge import PARTITIONS, kafka_security
    from .verify_analytics import _mysql_connection, source_gtids
else:
    from aggregate_bridge import PARTITIONS, kafka_security
    from verify_analytics import _mysql_connection, source_gtids


def comparison(raw: dict[str, Any], generation: str) -> None:
    if (raw.get("generation") != generation or raw.get("status") != "MATCHED_UNSEALED"
        or raw.get("mismatchPairs") != 0 or raw.get("watermarkVerified") is not False
        or raw.get("writersStoppedDeclared") is not True):
        raise ValueError("comparison is not an unsealed exact match")
    if raw.get("sourceSha256") != raw.get("clickhouseSha256"):
        raise ValueError("source and ClickHouse comparison hashes differ")
    if (not isinstance(raw.get("sourcePayments"), int) or raw["sourcePayments"] < 1
        or raw.get("sourcePayments") != raw.get("clickhousePayments")):
        raise ValueError("comparison payment totals are invalid")
    routes = raw.get("routes")
    if (not isinstance(routes, list) or len(routes) != 16
        or any(route.get("bucket") != index or route.get("physicalShard") not in range(4)
               or not isinstance(route.get("epoch"), int) or route["epoch"] < 1
               for index, route in enumerate(routes))):
        raise ValueError("comparison route catalog is incomplete")
    positions = raw.get("sourceGtidPositions")
    if (not isinstance(positions, list) or len(positions) != 4
        or any(item.get("physicalShard") != index or not item.get("serverUuid")
               or not item.get("gtidExecuted") for index, item in enumerate(positions))):
        raise ValueError("comparison source positions are incomplete")
    try:
        observed = dt.datetime.fromisoformat(raw["observedAt"])
    except (KeyError, TypeError, ValueError) as invalid:
        raise ValueError("comparison observation time is missing") from invalid
    if observed.tzinfo is None:
        raise ValueError("comparison observation time lacks a timezone")


def get_json(base: str, path: str, token: str) -> dict[str, Any]:
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.query:
        raise ValueError("control API must use HTTPS without URL credentials")
    request = urllib.request.Request(base.rstrip("/") + path,
        headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, timeout=10) as response:
        if response.status != 200:
            raise RuntimeError("control API rejected release verification")
        body = response.read(1_048_577)
    if len(body) > 1_048_576:
        raise ValueError("control API response exceeds 1 MiB")
    result = json.loads(body)
    if not isinstance(result, dict):
        raise ValueError("control API response is not an object")
    return result


def flink_checkpoint(base: str, job: str, token: str,
                     comparison_time: str) -> int:
    if not job or not all(character in "0123456789abcdef" for character in job.lower()):
        raise ValueError("Flink job ID is invalid")
    detail = get_json(base, f"/jobs/{job}", token)
    if detail.get("jid", "").lower() != job.lower() or detail.get("state") != "RUNNING":
        raise ValueError("Flink job is not running")
    checkpoints = get_json(base, f"/jobs/{job}/checkpoints", token)
    latest = checkpoints.get("latest", {}).get("completed")
    if (not isinstance(latest, dict) or latest.get("status") != "COMPLETED"
        or type(latest.get("id")) is not int or latest["id"] < 1):
        raise ValueError("Flink has no completed checkpoint")
    trigger = latest.get("trigger_timestamp")
    observed_ms = int(dt.datetime.fromisoformat(comparison_time).timestamp() * 1000)
    if type(trigger) is not int or trigger < observed_ms:
        raise ValueError("Flink checkpoint predates aggregate comparison")
    return latest["id"]


def connectors_running(base: str, names: list[str], token: str) -> None:
    if len(names) != 4 or len(set(names)) != 4:
        raise ValueError("four distinct analytics connectors are required")
    for name in names:
        status = get_json(base, "/connectors/" + urllib.parse.quote(name, safe="")
                          + "/status", token)
        tasks = status.get("tasks")
        if (status.get("name") != name or status.get("connector", {}).get("state") != "RUNNING"
            or not isinstance(tasks, list) or len(tasks) != 1
            or tasks[0].get("state") != "RUNNING"):
            raise ValueError(f"analytics connector {name} is not running")


def bridge_offsets(bootstrap: str, generation: str, group: str) -> list[dict[str, int]]:
    from confluent_kafka import Consumer, TopicPartition  # type: ignore[import-not-found]
    topic = "merchantpay-v3-payment-aggregates-" + generation
    consumer = Consumer({"bootstrap.servers": bootstrap, "group.id": group,
                         "enable.auto.commit": False, "isolation.level": "read_committed",
                         **kafka_security(dict(os.environ))})
    try:
        metadata = consumer.list_topics(topic, timeout=10)
        found = metadata.topics.get(topic)
        if found is None or found.error is not None or len(found.partitions) != PARTITIONS:
            raise ValueError("aggregate topic must have 16 healthy partitions")
        partitions = [TopicPartition(topic, index) for index in range(PARTITIONS)]
        committed = consumer.committed(partitions, timeout=10)
        offsets = []
        for partition, position in zip(partitions, committed):
            low, high = consumer.get_watermark_offsets(partition, timeout=10,
                                                        cached=False)
            if position.offset < low or position.offset != high:
                raise ValueError(f"bridge has not committed partition {partition.partition}")
            offsets.append({"partition": partition.partition, "end": high,
                            "committed": position.offset})
        return offsets
    finally:
        consumer.close()


def payload_for_release(raw: dict[str, Any], comparison_bytes: bytes,
                        generation: str, positions: list[dict[str, str]],
                        checkpoint: int, offsets: list[dict[str, int]],
                        job: str) -> dict[str, Any]:
    comparison(raw, generation)
    if positions != raw["sourceGtidPositions"]:
        raise ValueError("source GTIDs changed after aggregate comparison")
    if (len(offsets) != PARTITIONS or any(item["partition"] != index
        or item["committed"] != item["end"] for index, item in enumerate(offsets))):
        raise ValueError("bridge has not caught up to all output partitions")
    return {"schemaVersion": 1, "status": "VERIFIED_AT_WATERMARK",
            "generation": generation,
            "verifiedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "comparisonSha256": hashlib.sha256(comparison_bytes).hexdigest(),
            "aggregateSha256": raw["sourceSha256"],
            "sourceGtidPositions": positions, "routes": raw["routes"],
            "flinkJobId": job, "flinkCheckpointId": checkpoint,
            "outputOffsets": offsets}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--comparison", type=Path, required=True)
    parser.add_argument("--generation", required=True)
    parser.add_argument("--connect-url", required=True)
    parser.add_argument("--connector", action="append", required=True)
    parser.add_argument("--flink-url", required=True)
    parser.add_argument("--flink-job", required=True)
    parser.add_argument("--kafka-bootstrap", required=True)
    parser.add_argument("--bridge-group", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or not re.fullmatch(r"[A-Za-z0-9_-]{1,36}", args.generation):
        raise ValueError("new output and valid generation are required")
    key = os.environ["ANALYTICS_RELEASE_KEY"].encode()
    if len(key) < 32:
        raise ValueError("analytics release HMAC key must be at least 32 bytes")
    raw_bytes = args.comparison.read_bytes()
    raw = json.loads(raw_bytes)
    comparison(raw, args.generation)
    import pymysql  # type: ignore[import-not-found]
    sources = [_mysql_connection(shard, os.environ["MYSQL_CA_FILE"], pymysql)
               for shard in range(4)]
    try:
        positions = source_gtids(sources)
        if positions != raw["sourceGtidPositions"]:
            raise ValueError("source GTIDs changed after comparison")
        connectors_running(args.connect_url, args.connector,
                           os.environ["CONNECT_BEARER_TOKEN"])
        checkpoint = flink_checkpoint(args.flink_url, args.flink_job,
                                      os.environ["FLINK_BEARER_TOKEN"],
                                      raw["observedAt"])
        offsets = bridge_offsets(args.kafka_bootstrap, args.generation,
                                 args.bridge_group)
        if positions != source_gtids(sources):
            raise ValueError("source GTIDs changed during watermark verification")
        payload = payload_for_release(raw, raw_bytes, args.generation, positions,
                                      checkpoint, offsets, args.flink_job)
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        signature = hmac.new(key, encoded.encode(), hashlib.sha256).hexdigest()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"payload": encoded, "signature": signature},
                                         ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    finally:
        for source in sources:
            source.close()


if __name__ == "__main__":
    main()
