import unittest
import datetime as dt
from unittest.mock import patch

from scripts.v3.publish_analytics import comparison, payload_for_release, flink_checkpoint


def matched():
    return {
        "generation": "g1", "status": "MATCHED_UNSEALED",
        "mismatchPairs": 0, "watermarkVerified": False,
        "writersStoppedDeclared": True, "sourceSha256": "a" * 64,
        "clickhouseSha256": "a" * 64, "sourcePayments": 8,
        "clickhousePayments": 8,
        "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "routes": [{"bucket": i, "physicalShard": i % 4, "epoch": 1}
                   for i in range(16)],
        "sourceGtidPositions": [
            {"physicalShard": i, "serverUuid": f"server-{i}",
             "gtidExecuted": f"server-{i}:1-20"} for i in range(4)],
    }


class ReleaseGateTest(unittest.TestCase):
    def test_exact_watermark_and_bridge_offset_required(self):
        raw = matched()
        offsets = [{"partition": i, "end": 10, "committed": 10}
                   for i in range(16)]
        evidence = payload_for_release(raw, b"comparison", "g1",
                                       raw["sourceGtidPositions"], 7, offsets, "a" * 32)
        self.assertEqual(evidence["status"], "VERIFIED_AT_WATERMARK")
        offsets[4]["committed"] = 9
        with self.assertRaisesRegex(ValueError, "bridge"):
            payload_for_release(raw, b"comparison", "g1",
                                raw["sourceGtidPositions"], 7, offsets, "a" * 32)

    def test_source_or_comparison_change_blocks_publication(self):
        raw = matched()
        offsets = [{"partition": i, "end": 1, "committed": 1}
                   for i in range(16)]
        changed = [dict(item) for item in raw["sourceGtidPositions"]]
        changed[1]["gtidExecuted"] += ":21"
        with self.assertRaisesRegex(ValueError, "GTIDs"):
            payload_for_release(raw, b"comparison", "g1", changed, 7, offsets, "a" * 32)
        raw["status"] = "MISMATCH"
        with self.assertRaisesRegex(ValueError, "exact match"):
            comparison(raw, "g1")

    def test_checkpoint_must_follow_comparison(self):
        observed = dt.datetime.now(dt.timezone.utc)
        stamp = int(observed.timestamp() * 1000)
        responses = [{"jid": "a" * 32, "state": "RUNNING"},
                     {"latest": {"completed": {"id": 7, "status": "COMPLETED",
                                               "trigger_timestamp": stamp - 1}}}]
        with patch("scripts.v3.publish_analytics.get_json", side_effect=responses):
            with self.assertRaisesRegex(ValueError, "predates"):
                flink_checkpoint("https://flink.example", "a" * 32, "token",
                                 observed.isoformat())
        responses[1]["latest"]["completed"]["trigger_timestamp"] = stamp + 1
        with patch("scripts.v3.publish_analytics.get_json", side_effect=responses):
            self.assertEqual(flink_checkpoint("https://flink.example", "a" * 32,
                                              "token", observed.isoformat()), 7)


if __name__ == "__main__":
    unittest.main()
