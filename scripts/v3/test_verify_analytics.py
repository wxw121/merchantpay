import unittest

from scripts.v3.verify_analytics import compare, merge_sources, source_gtids


class VerifyAnalyticsTest(unittest.TestCase):
    def test_source_positions_require_four_distinct_nonempty_gtids(self):
        class Cursor:
            def __init__(self, row):
                self.row = row

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return None

            def execute(self, sql):
                self.sql = sql

            def fetchall(self):
                return (self.row,)

        class Connection:
            def __init__(self, row):
                self.row = row

            def cursor(self):
                return Cursor(self.row)

        sources = [Connection((f"server-{n}", f"gtid-{n}:1-2")) for n in range(4)]
        self.assertEqual(len(source_gtids(sources)), 4)
        with self.assertRaisesRegex(ValueError, "distinct server UUIDs"):
            source_gtids([Connection(("same", f"gtid-{n}:1-2")) for n in range(4)])
        with self.assertRaisesRegex(ValueError, "nonempty GTID"):
            source_gtids([Connection((f"server-{n}", "")) for n in range(4)])

    def test_merges_same_merchant_day_channel_across_sources(self):
        source = merge_sources(iter([
            iter([("m", "2026-11-05", "MOCK", 2, 1, 100, 10)]),
            iter([("m", "2026-11-05", "MOCK", 3, 2, 200, 20)]),
        ]))
        result = compare(source, iter([("m", "2026-11-05", "MOCK", 5, 3, 300, 30)]))
        self.assertEqual(result["status"], "MATCHED_UNSEALED")
        self.assertEqual(result["sourceSha256"], result["clickhouseSha256"])

    def test_missing_or_duplicate_group_fails(self):
        row = ("m", "2026-11-05", "MOCK", 5, 3, 300, 30)
        result = compare(iter([row]), iter([]))
        self.assertEqual(result["status"], "MISMATCH")
        with self.assertRaises(ValueError):
            compare(iter([row]), iter([row, row]))

    def test_missing_middle_group_does_not_shift_following_comparisons(self):
        first = ("a", "2026-11-05", "MOCK", 1, 1, 10, 0)
        middle = ("b", "2026-11-05", "MOCK", 1, 1, 10, 0)
        last = ("c", "2026-11-05", "MOCK", 1, 1, 10, 0)
        result = compare(iter([first, middle, last]), iter([first, last]))
        self.assertEqual(result["mismatchPairs"], 1)
        self.assertEqual(result["firstMismatches"][0]["source"][0], "b")
        self.assertIsNone(result["firstMismatches"][0]["clickhouse"])


if __name__ == "__main__":
    unittest.main()
