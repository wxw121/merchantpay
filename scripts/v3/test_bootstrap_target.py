import unittest

from bootstrap_target import SEEDED, inspect_and_prepare


class FakeCursor:
    def __init__(self, rows=False):
        self.rows = rows
        self.sql = ""
        self.rowcount = 0

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, sql, *_):
        self.sql = sql
        self.rowcount = 16 if sql.startswith("DELETE FROM") else 1

    def fetchall(self):
        if "flyway_schema_history" in self.sql:
            return (("50", 1),)
        if "v3_shard_identity" in self.sql:
            return ((0,),)
        if "v3_shard_routes" in self.sql:
            return tuple((bucket, 0, 1) for bucket in range(16))
        if "v3_bucket_fences" in self.sql:
            return tuple((bucket, 1, "ACTIVE") for bucket in range(16))
        if "event_transport" in self.sql:
            return (("POLLING",),)
        if "projection_generations" in self.sql:
            return (("v2-initial", "BUILDING"),)
        if self.sql == "SHOW TABLES":
            return tuple((table,) for table in sorted(SEEDED | {"payments"}))
        raise AssertionError(self.sql)

    def fetchone(self):
        if "payment_admission" in self.sql:
            return None
        return (1,) if self.rows else None


class FakeConnection:
    def __init__(self, rows=False):
        self.fake = FakeCursor(rows)
        self.committed = False
        self.rolled_back = False

    def begin(self):
        pass

    def cursor(self):
        return self.fake

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True


class BootstrapTargetTest(unittest.TestCase):
    def test_dry_run_does_not_change_identity(self):
        connection = FakeConnection()
        self.assertEqual(inspect_and_prepare(connection, 2, False), ["payments"])
        self.assertFalse(connection.committed)
        self.assertTrue(connection.rolled_back)

    def test_apply_changes_candidate_after_empty_scan(self):
        connection = FakeConnection()
        self.assertEqual(inspect_and_prepare(connection, 2, True), ["payments"])
        self.assertTrue(connection.committed)

    def test_nonempty_target_blocks_and_rolls_back(self):
        connection = FakeConnection(rows=True)
        with self.assertRaisesRegex(ValueError, "not empty: payments"):
            inspect_and_prepare(connection, 2, True)
        self.assertFalse(connection.committed)
        self.assertTrue(connection.rolled_back)


if __name__ == "__main__":
    unittest.main()
