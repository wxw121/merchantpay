import unittest

from scripts.v3.render_analytics import render


def config():
    return {
        "database": "merchantpay",
        "generation": "g-1",
        "kafkaBootstrap": "kafka.example:9093",
        "checkpointUri": "s3://merchantpay-flink/checkpoints/g-1",
        "savepointUri": "s3://merchantpay-flink/savepoints/g-1",
        "sources": [
            {"host": f"mysql-{shard}.example", "port": 3306, "user": "cdc_analytics",
             "serverId": 7000 + shard, "passwordEnv": f"CDC_S{shard}_PASSWORD"}
            for shard in range(4)
        ],
    }


class RenderAnalyticsTest(unittest.TestCase):
    def test_routes_are_core_only_and_generation_is_isolated(self):
        sql, specs = render(config())
        self.assertEqual(len(specs), 4)
        self.assertIn("merchantpay-v3-payment-aggregates-g-1", sql)
        self.assertIn("merchantpay-v3-analytics-s0.merchantpay.v3_shard_routes", sql)
        self.assertEqual(len({s["database.server.id"] for s in specs}), 4)
        self.assertEqual(specs[3]["topic.prefix"], "merchantpay-v3-analytics-s3")
        self.assertNotIn("__DATABASE__", sql)
        self.assertIn("s3://merchantpay-flink/checkpoints/g-1", sql)

    def test_rejects_sql_injection_and_duplicate_server_ids(self):
        attack = config()
        attack["generation"] = "g';DROP TABLE payments;--"
        with self.assertRaises(ValueError):
            render(attack)
        duplicate = config()
        duplicate["sources"][1]["serverId"] = 7000
        with self.assertRaises(ValueError):
            render(duplicate)
        unsafe = config()
        unsafe["checkpointUri"] = "file:///local-only"
        with self.assertRaises(ValueError):
            render(unsafe)


if __name__ == "__main__":
    unittest.main()
