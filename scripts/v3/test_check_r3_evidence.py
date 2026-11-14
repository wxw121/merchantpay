import hashlib
import tempfile
import unittest
from pathlib import Path

from check_r3_evidence import EvidenceError, verify


class EvidenceGateTest(unittest.TestCase):
    def temporary_directory(self):
        return tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)

    def fixture(self, directory: Path):
        names = ("payment-load", "query-load", "mixed-load", "dataset-inventory",
                 "bill-process", "soak-metrics", "money-reconciliation", "fault-recovery")
        records = []
        for name in names:
            path = directory / f"{name}.txt"
            path.write_text(name, encoding="utf-8")
            records.append({"kind": name, "path": path.name,
                            "sha256": hashlib.sha256(name.encode()).hexdigest()})
        return {
            "environment": {"independent": True, "runId": "r3-1"},
            "payment": {"durationSeconds": 3600, "completedTpsLowerBound": 5000,
                        "financialErrors": 0},
            "query": {"durationSeconds": 900, "successfulQpsLowerBound": 20000,
                      "wrongResults": 0},
            "mixed": {"durationSeconds": 900, "completedPayments": 1,
                      "successfulQueries": 1, "moneyMatched": True},
            "dataset": {"payments": 100_000_000, "ledgerEntries": 500_000_000,
                        "domainEvents": 1_000_000_000, "historyVerified": True},
            "bill": {"rows": 10_000_000, "durationSeconds": 1800,
                     "sealedAndMatched": True},
            "soak": {"durationSeconds": 86400, "fatalErrors": 0,
                     "moneyMatched": True},
            "integrity": dict.fromkeys(("ledgerBalanced", "refundPrincipalBounded",
                                        "noDuplicateSettlement", "eventReplayIdempotent",
                                        "fourSourceProjectionMatched", "archiveObjectsVerified",
                                        "contractsCompatible"), True),
            "faults": {name: {"recovered": True, "moneyMatched": True}
                       for name in ("apiPod", "workerPod", "mysqlSource", "kafkaConsumer",
                                    "objectStorage", "partialCutover", "backupRestore")},
            "artifacts": records,
        }

    def test_boundary_values_require_all_artifacts(self):
        with self.temporary_directory() as temp:
            root = Path(temp)
            verify(self.fixture(root), root)

    def test_rejects_shortfall_and_changed_artifact(self):
        with self.temporary_directory() as temp:
            root = Path(temp)
            data = self.fixture(root)
            data["payment"]["completedTpsLowerBound"] = 4999.9
            with self.assertRaisesRegex(EvidenceError, "completed payment TPS"):
                verify(data, root)
            data["payment"]["completedTpsLowerBound"] = 5000
            (root / "payment-load.txt").write_text("changed", encoding="utf-8")
            with self.assertRaisesRegex(EvidenceError, "artifact hash mismatch"):
                verify(data, root)

    def test_missing_fault_never_passes(self):
        with self.temporary_directory() as temp:
            root = Path(temp)
            data = self.fixture(root)
            del data["faults"]["partialCutover"]
            with self.assertRaisesRegex(EvidenceError, "partialCutover"):
                verify(data, root)


if __name__ == "__main__":
    unittest.main()
