import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import legacy_rollback
from legacy_rollback import commands, deployment_stopped
from release_boundary import NEW_CONTRACT_FLAGS, check


def snapshot():
    return {
        "databases": [
            {"physicalShard": n, "identity": n, "flywayVersion": 50,
             "businessRows": 0, "activeFences": 0} for n in range(4)
        ],
        "routes": [{"bucket": n, "physicalShard": 0, "epoch": 1}
                   for n in range(16)],
        "coreFences": [{"bucket": n, "routeEpoch": 1,
                        "accessState": "ACTIVE"} for n in range(16)],
        "backupRestoreVerified": True,
        "writersStopped": True,
        "flags": {name: False for name in NEW_CONTRACT_FLAGS},
        "targetWritesEverEnabled": False,
        "coldEventsEverRetired": False,
        "cdcSwitched": False,
        "routeMovesEverCompleted": False,
        "oldBinaryContractVerified": True,
        "eventTransportMode": "POLLING",
        "openMigrationCount": 0,
        "reverseRunCount": 0,
        "cutoverCount": 0,
        "coldLocatorCount": 0,
    }


class ReleaseBoundaryTest(unittest.TestCase):
    def test_empty_bootstrap_and_legacy_boundary(self):
        self.assertEqual(check(snapshot(), "bootstrap"), [])
        self.assertEqual(check(snapshot(), "legacy-rollback"), [])

    def test_refuses_unsafe_rollback_even_after_route_returns_to_core(self):
        evidence = snapshot()
        evidence["targetWritesEverEnabled"] = True
        self.assertIn("targetWritesEverEnabled must be explicitly false",
                      check(evidence, "legacy-rollback"))
        evidence = snapshot()
        evidence["coldEventsEverRetired"] = True
        self.assertIn("coldEventsEverRetired must be explicitly false",
                      check(evidence, "legacy-rollback"))

    def test_requires_complete_routes_and_four_distinct_identities(self):
        evidence = snapshot()
        evidence["routes"][15] = evidence["routes"][14].copy()
        evidence["databases"][3]["identity"] = 2
        self.assertTrue(check(evidence, "bootstrap"))

    def test_rejects_target_rows_and_missing_backup(self):
        evidence = snapshot()
        evidence["databases"][2]["businessRows"] = 1
        evidence["backupRestoreVerified"] = False
        result = check(evidence, "bootstrap")
        self.assertTrue(any("target 2" in item for item in result))
        self.assertTrue(any("backup" in item for item in result))

    def test_rollback_refuses_fenced_core_or_in_progress_move(self):
        evidence = snapshot()
        evidence["coreFences"][3]["accessState"] = "FENCED"
        evidence["openMigrationCount"] = 1
        result = check(evidence, "legacy-rollback")
        self.assertTrue(any("core fence mismatch" in item for item in result))
        self.assertTrue(any("openMigrationCount" in item for item in result))

    def test_rollback_commands_require_digest_and_target_every_deployment(self):
        digest = "registry.example/merchantpay/platform@sha256:" + "a" * 64
        steps = commands("merchantpay-v3", digest)
        self.assertEqual(len(steps), 8)
        self.assertTrue(all("kubectl" == step[0] for step in steps))
        with self.assertRaises(ValueError):
            commands("merchantpay-v3", "merchantpay/platform:old")

    def test_running_or_draining_pods_block_rollback(self):
        self.assertTrue(deployment_stopped({"spec": {"replicas": 0},
                                            "status": {"replicas": 0}}))
        self.assertFalse(deployment_stopped({"spec": {"replicas": 0},
                                             "status": {"replicas": 1}}))
        self.assertFalse(deployment_stopped({"spec": {"replicas": 1},
                                             "status": {"replicas": 0}}))

    def test_rollback_cli_dry_run_outputs_all_actions_without_invoking_kubectl(self):
        with patch.object(legacy_rollback.subprocess, "run") as run:
            result, output = self.run_rollback(snapshot())
            run.assert_not_called()
        self.assertEqual(result, 0)
        self.assertEqual(len(output.splitlines()), 8)
        self.assertIn("DRY_RUN: kubectl -n merchantpay-v3 set image deployment/merchantpay-api", output)

    def test_rollback_cli_rejects_a_live_flag_before_inspecting_deployments(self):
        with patch.object(legacy_rollback.subprocess, "run") as run:
            run.return_value.stdout = json.dumps({"data": {
                **{name: "false" for name in NEW_CONTRACT_FLAGS},
                NEW_CONTRACT_FLAGS[0]: "true",
            }})
            with self.assertRaisesRegex(ValueError, "flags are not all disabled"):
                self.run_rollback(snapshot(), execute=True)
            self.assertEqual(run.call_count, 1)
            self.assertIn("configmap", run.call_args.args[0])

    @staticmethod
    def run_rollback(evidence, *, execute=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "boundary.json"
            path.write_text(json.dumps(evidence), encoding="utf-8")
            digest = "registry.example/merchantpay/platform@sha256:" + "a" * 64
            argv = ["legacy_rollback", "--evidence", str(path), "--old-image", digest]
            if execute:
                argv.append("--execute")
            output = io.StringIO()
            with patch.object(sys, "argv", argv), contextlib.redirect_stdout(output):
                return legacy_rollback.main(), output.getvalue()


if __name__ == "__main__":
    unittest.main()
