import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch


MODULE = Path(__file__).with_name("aggregate_bridge.py")
SPEC = importlib.util.spec_from_file_location("aggregate_bridge", MODULE)
bridge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bridge)


class AggregateBridgeTest(unittest.TestCase):
    def setUp(self):
        self.key = json.dumps({
            "generation": "g1", "merchant_id": "merchant",
            "business_day": "2026-11-05", "channel": "MOCK",
        }).encode()
        self.value = json.dumps({
            "payments": 2, "succeeded": 1,
            "succeeded_minor": 100, "refunded_minor": 20,
            "latest_source_updated_at": "2026-11-05T00:00:00Z",
        }).encode()

    def test_upsert_and_tombstone_have_same_key_and_monotone_offset(self):
        row = bridge.decode(self.key, self.value, 3, 9)
        deleted = bridge.decode(self.key, None, 3, 10)
        self.assertEqual(row["payments"], 2)
        self.assertEqual(row["kafka_offset"], 10)
        self.assertEqual(deleted["deleted"], 1)
        self.assertEqual(deleted["kafka_offset"], 11)

    def test_rejects_schema_and_partition_drift(self):
        with self.assertRaises(ValueError):
            bridge.decode(self.key, b'{"payments":1}', 0, 1)
        with self.assertRaises(ValueError):
            bridge.decode(self.key, self.value, 16, 1)
        with self.assertRaises(ValueError):
            bridge.decode(self.key, self.value, 0, 1, "another-generation")
        with self.assertRaises(ValueError):
            bridge.decode(self.key, self.value.replace(b'"succeeded": 1', b'"succeeded": true'), 0, 1)
        with self.assertRaises(ValueError):
            bridge.decode(self.key.replace(b'"generation": "g1"',
                                           b'"generation": "g1", "generation": "g2"'),
                          self.value, 0, 1)

    def test_http_failure_is_not_swallowed(self):
        row = bridge.decode(self.key, self.value, 0, 0)
        with patch.object(bridge.urllib.request, "urlopen", side_effect=OSError("unavailable")):
            with self.assertRaises(OSError):
                bridge.write_rows("https://clickhouse.example/", "user", "secret", [row])

    def test_remote_plain_http_is_rejected(self):
        with self.assertRaises(ValueError):
            bridge._endpoint("http://clickhouse.example/")

    def test_kafka_requires_authenticated_tls_configuration(self):
        with self.assertRaises(ValueError):
            bridge.kafka_security({"KAFKA_SECURITY_PROTOCOL": "PLAINTEXT"})
        config = bridge.kafka_security({
            "KAFKA_SECURITY_PROTOCOL": "SASL_SSL",
            "KAFKA_SASL_MECHANISM": "SCRAM-SHA-512",
            "KAFKA_SASL_USERNAME": "bridge",
            "KAFKA_SASL_PASSWORD": "secret",
        })
        self.assertEqual(config["security.protocol"], "SASL_SSL")
        self.assertEqual(config["sasl.mechanisms"], "SCRAM-SHA-512")


if __name__ == "__main__":
    unittest.main()
