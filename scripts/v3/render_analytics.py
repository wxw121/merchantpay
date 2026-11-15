"""Render the four analytics CDC specs and one Flink SQL job without credentials."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from typing import Any
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
CONNECTOR = ROOT / "infra/connect/v3-analytics-payments.template.json"
FLINK = ROOT / "infra/flink/v3-payment-aggregates.sql"
NAME = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
HOST = re.compile(r"[A-Za-z0-9.:-]{1,255}\Z")
BOOTSTRAP = re.compile(r"[A-Za-z0-9.:-]+(?:,[A-Za-z0-9.:-]+)*\Z")
ENV = re.compile(r"[A-Z][A-Z0-9_]{1,99}\Z")


def _durable_uri(value: Any) -> str:
    if (not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._:/-]{1,500}", value)
            or ".." in value):
        raise ValueError("invalid Flink state URI")
    parsed = urlsplit(value)
    if parsed.scheme not in ("s3", "hdfs") or not parsed.netloc or not parsed.path.strip("/"):
        raise ValueError("Flink state needs a shared S3 or HDFS path")
    if parsed.query or parsed.fragment or parsed.username:
        raise ValueError("invalid Flink state URI")
    return value


def render(config: dict[str, Any]) -> tuple[str, list[dict[str, str]]]:
    database = config["database"]
    generation = config["generation"]
    bootstrap = config["kafkaBootstrap"]
    checkpoint = _durable_uri(config["checkpointUri"])
    savepoint = _durable_uri(config["savepointUri"])
    sources = config["sources"]
    if not isinstance(database, str) or not NAME.fullmatch(database):
        raise ValueError("invalid database name")
    if not isinstance(generation, str) or not NAME.fullmatch(generation):
        raise ValueError("invalid generation")
    if not isinstance(bootstrap, str) or not BOOTSTRAP.fullmatch(bootstrap):
        raise ValueError("invalid Kafka bootstrap")
    if not isinstance(sources, list) or len(sources) != 4:
        raise ValueError("exactly four sources are required")
    servers = set()
    connectors = []
    template = json.loads(CONNECTOR.read_text(encoding="utf-8"))
    for shard, source in enumerate(sources):
        host, port, server, user, password_env = (
            source["host"], source["port"], source["serverId"],
            source["user"], source["passwordEnv"]
        )
        if not isinstance(host, str) or not HOST.fullmatch(host):
            raise ValueError("invalid MySQL host")
        if type(port) is not int or port < 1 or port > 65535:
            raise ValueError("invalid MySQL port")
        if type(server) is not int or server < 1 or server > 4_294_967_295 or server in servers:
            raise ValueError("analytics CDC server IDs must be unique")
        servers.add(server)
        if not isinstance(user, str) or not NAME.fullmatch(user):
            raise ValueError("invalid CDC user")
        if not isinstance(password_env, str) or not ENV.fullmatch(password_env):
            raise ValueError("invalid CDC password environment variable")
        spec = json.loads(json.dumps(template))
        replacements = {
            "__MYSQL_HOST__": host, "__MYSQL_PORT__": str(port),
            "__UNIQUE_ANALYTICS_SERVER_ID__": str(server),
            "__CDC_USER__": user,
            "__CDC_PASSWORD_ENV__": password_env,
            "__DATABASE__": database, "__SHARD_ID__": str(shard),
            "__KAFKA_BOOTSTRAP__": bootstrap,
        }
        for key, value in spec.items():
            for marker, replacement in replacements.items():
                value = value.replace(marker, replacement)
            if re.search(r"__[A-Z_]+__", value):
                raise ValueError(f"unresolved connector placeholder in {key}")
            spec[key] = value
        connectors.append(spec)
    aggregate_topic = "merchantpay-v3-payment-aggregates-" + generation
    sql = FLINK.read_text(encoding="utf-8")
    replacements = {
        "__DATABASE__": database,
        "__KAFKA_BOOTSTRAP__": bootstrap,
        "__ANALYTICS_GROUP__": "merchantpay-v3-analytics-" + generation,
        "__GENERATION__": generation,
        "__AGGREGATE_TOPIC__": aggregate_topic,
        "__UNIQUE_ANALYTICS_TRANSACTION_PREFIX__": "merchantpay-v3-aggregate-" + generation,
        "__CHECKPOINT_URI__": checkpoint,
        "__SAVEPOINT_URI__": savepoint,
    }
    for marker, replacement in replacements.items():
        sql = sql.replace(marker, replacement)
    if re.search(r"__[A-Z_]+__", sql):
        raise ValueError("unresolved Flink SQL placeholder")
    return sql, connectors


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    sql, connectors = render(json.loads(args.config.read_text(encoding="utf-8")))
    if args.output.exists():
        raise ValueError("render output already exists")
    args.output.mkdir(parents=True)
    (args.output / "payment-aggregates.sql").write_text(sql, encoding="utf-8")
    for shard, spec in enumerate(connectors):
        (args.output / f"analytics-cdc-s{shard}.json").write_text(
            json.dumps(spec, indent=2) + "\n", encoding="utf-8"
        )


if __name__ == "__main__":
    main()
