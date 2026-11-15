"""Materialize Flink upserts into a replaceable ClickHouse table.

This process has no business-write credentials. A Kafka offset is the version for
its key; the output topic's partition count must never change within a generation.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import urllib.parse
import urllib.request
from typing import Any

PARTITIONS = 16
KEYS = ("generation", "merchant_id", "business_day", "channel")
VALUES = ("payments", "succeeded", "succeeded_minor", "refunded_minor")
SOURCE_TIME = "latest_source_updated_at"
INSERT = "INSERT INTO merchantpay.v3_payment_aggregates FORMAT JSONEachRow\n"


def _object(raw: bytes) -> dict[str, Any]:
    if raw is None:
        raise ValueError("aggregate Kafka key is missing")
    if len(raw) > 1_048_576:
        raise ValueError("aggregate Kafka record exceeds 1 MiB")
    def unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("aggregate JSON has duplicate fields")
            value[key] = item
        return value
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    if not isinstance(value, dict):
        raise ValueError("aggregate Kafka record must be an object")
    return value


def decode(key: bytes, value: bytes | None, partition: int, offset: int,
           expected_generation: str | None = None) -> dict[str, Any]:
    if partition < 0 or partition >= PARTITIONS or offset < 0:
        raise ValueError("aggregate Kafka partition or offset is invalid")
    identity = _object(key)
    if set(identity) != set(KEYS):
        raise ValueError("aggregate key fields changed")
    limits = {"generation": 64, "merchant_id": 64, "business_day": 10, "channel": 32}
    for field in KEYS:
        if not isinstance(identity[field], str) or not identity[field] or len(identity[field]) > limits[field]:
            raise ValueError(f"invalid aggregate key: {field}")
    if expected_generation is not None and identity["generation"] != expected_generation:
        raise ValueError("aggregate generation differs from deployed job")
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", identity["business_day"]):
        raise ValueError("invalid aggregate day")
    try:
        dt.date.fromisoformat(identity["business_day"])
    except ValueError as invalid:
        raise ValueError("invalid aggregate day") from invalid
    if value is None:
        metrics = {field: 0 for field in VALUES}
        metrics[SOURCE_TIME] = ""
        deleted = 1
    else:
        metrics = _object(value)
        if set(metrics) != set(VALUES) | {SOURCE_TIME}:
            raise ValueError("aggregate value fields changed")
        if any(type(metrics[field]) is not int or metrics[field] < 0 for field in VALUES):
            raise ValueError("aggregate value must be a nonnegative integer")
        if (not isinstance(metrics[SOURCE_TIME], str) or not re.fullmatch(
                r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z",
                metrics[SOURCE_TIME])):
            raise ValueError("aggregate source timestamp is invalid")
        if metrics["succeeded"] > metrics["payments"]:
            raise ValueError("aggregate success count exceeds payments")
        deleted = 0
    return {
        **identity,
        **metrics,
        "deleted": deleted,
        "kafka_offset": offset + 1,
    }


def _endpoint(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username:
        raise ValueError("explicit ClickHouse HTTP endpoint required")
    if parsed.query or parsed.fragment:
        raise ValueError("ClickHouse URL must not contain a query or fragment")
    if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("remote ClickHouse endpoint requires HTTPS")
    return url


def kafka_security(environment: dict[str, str]) -> dict[str, str]:
    protocol = environment.get("KAFKA_SECURITY_PROTOCOL")
    if protocol not in ("SSL", "SASL_SSL"):
        raise ValueError("aggregate Kafka transport requires SSL or SASL_SSL")
    config = {"security.protocol": protocol}
    ca = environment.get("KAFKA_SSL_CA_LOCATION")
    if ca:
        config["ssl.ca.location"] = ca
    if protocol == "SASL_SSL":
        mechanism = environment.get("KAFKA_SASL_MECHANISM")
        user = environment.get("KAFKA_SASL_USERNAME")
        password = environment.get("KAFKA_SASL_PASSWORD")
        if mechanism not in ("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512") or not user or not password:
            raise ValueError("SASL_SSL requires mechanism, username and password")
        config.update({"sasl.mechanisms": mechanism,
                       "sasl.username": user, "sasl.password": password})
    return config


def write_rows(url: str, user: str, password: str, rows: list[dict[str, Any]]) -> None:
    if not rows:
        return
    body = INSERT + "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows)
    payload = body.encode("utf-8")
    if len(payload) > 4_194_304:
        raise ValueError("aggregate ClickHouse batch exceeds 4 MiB")
    request = urllib.request.Request(
        _endpoint(url), payload,
        {"X-ClickHouse-User": user, "X-ClickHouse-Key": password},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        if response.status != 200:
            raise RuntimeError(f"ClickHouse rejected aggregate batch: {response.status}")
        if len(response.read(1025)) > 1024:
            raise RuntimeError("ClickHouse response exceeds limit")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--topic", required=True)
    parser.add_argument("--generation", required=True)
    parser.add_argument("--bootstrap", required=True)
    parser.add_argument("--group", required=True)
    parser.add_argument("--clickhouse-url", required=True)
    parser.add_argument("--clickhouse-user", required=True)
    args = parser.parse_args()
    password = os.environ["CLICKHOUSE_PASSWORD"]
    url = _endpoint(args.clickhouse_url)
    topic_name = args.topic
    if topic_name != "merchantpay-v3-payment-aggregates-" + args.generation:
        raise ValueError("aggregate topic must match the deployed generation")
    # Imported only by the deployed bridge. Local static checks need no Kafka client.
    from confluent_kafka import Consumer, KafkaException  # type: ignore[import-not-found]

    consumer = Consumer({
        "bootstrap.servers": args.bootstrap,
        "group.id": args.group,
        "enable.auto.commit": False,
        "auto.offset.reset": "earliest",
        "isolation.level": "read_committed",
        **kafka_security(dict(os.environ)),
    })
    try:
        metadata = consumer.list_topics(topic_name, timeout=10)
        topic = metadata.topics.get(topic_name)
        if topic is None or topic.error is not None or len(topic.partitions) != PARTITIONS:
            raise RuntimeError("aggregate topic must have exactly 16 immutable partitions")
        consumer.subscribe([topic_name])
        rows: list[dict[str, Any]] = []
        while True:
            message = consumer.poll(1.0)
            if message is not None:
                if message.error():
                    raise KafkaException(message.error())
                if message.topic() != topic_name:
                    raise RuntimeError("unexpected aggregate topic")
                rows.append(decode(message.key(), message.value(),
                                   message.partition(), message.offset(), args.generation))
            if rows and (len(rows) >= 500 or message is None):
                write_rows(url, args.clickhouse_user, password, rows)
                consumer.commit(asynchronous=False)
                rows.clear()
    finally:
        consumer.close()


if __name__ == "__main__":
    main()
