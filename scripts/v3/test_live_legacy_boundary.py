import unittest

from live_legacy_boundary import inspect_live
from test_release_boundary import snapshot


class FakeCursor:
    def __init__(self, source):
        self.source = source
        self.sql = ""

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, sql):
        self.sql = sql

    def fetchall(self):
        source = self.source
        sql = self.sql
        if "flyway_schema_history" in sql:
            return (("50", 1),)
        if "v3_shard_identity" in sql:
            return ((source.physical,),)
        if "event_transport" in sql:
            return (("POLLING",),)
        if "v3_history_event_locators" in sql:
            return ((source.cold,),)
        if "v3_shard_routes" in sql:
            return tuple((bucket, 0, 1) for bucket in range(16))
        if "v3_bucket_fences" in sql and "COUNT" not in sql:
            return tuple((bucket, 1, "FENCED" if source.fenced and bucket == 3
                          else "ACTIVE") for bucket in range(16))
        if "v3_shard_cutovers" in sql:
            return ((source.cutovers,),)
        if "COUNT" in sql:
            return ((0,),)
        raise AssertionError(sql)


class FakeConnection:
    def __init__(self, physical):
        self.physical = physical
        self.cold = 0
        self.fenced = False
        self.cutovers = 0

    def cursor(self):
        return FakeCursor(self)


class LiveLegacyBoundaryTest(unittest.TestCase):
    def test_fresh_four_source_observation_passes(self):
        inspect_live([FakeConnection(n) for n in range(4)], snapshot())

    def test_fenced_core_and_cutover_block(self):
        sources = [FakeConnection(n) for n in range(4)]
        sources[0].fenced = True
        with self.assertRaisesRegex(ValueError, "core fences"):
            inspect_live(sources, snapshot())
        sources[0].fenced = False
        sources[0].cutovers = 1
        with self.assertRaisesRegex(ValueError, "cutoverCount"):
            inspect_live(sources, snapshot())

    def test_cold_event_on_any_target_blocks(self):
        sources = [FakeConnection(n) for n in range(4)]
        sources[2].cold = 1
        with self.assertRaisesRegex(ValueError, "cold events"):
            inspect_live(sources, snapshot())


if __name__ == "__main__":
    unittest.main()
