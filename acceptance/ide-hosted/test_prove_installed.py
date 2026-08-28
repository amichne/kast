import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("prove_installed.py")
SPEC = importlib.util.spec_from_file_location("prove_installed", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
PROVE_INSTALLED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROVE_INSTALLED)


def completed(document):
    return subprocess.CompletedProcess(
        args=["kast"],
        returncode=0,
        stdout=json.dumps(document) + "\n",
        stderr="",
    )


class InstalledReadinessTest(unittest.TestCase):
    @patch.object(PROVE_INSTALLED.time, "sleep")
    @patch.object(PROVE_INSTALLED.subprocess, "run")
    def test_transient_discovery_readiness_refines_to_complete(self, run, sleep):
        run.side_effect = [
            completed({
                "operation": "symbol.discover",
                "status": "rejected",
                "reason": "workspace-not-ready",
            }),
            completed({
                "operation": "symbol.discover",
                "status": "rejected",
                "reason": "workspace-not-ready",
            }),
            completed({"operation": "symbol.discover", "status": "complete", "items": []}),
        ]

        document, evidence = PROVE_INSTALLED.run_operation_until_ready(
            "kast",
            "/exact/root",
            ["symbol", "discover"],
            "symbol.discover",
            maximum_attempts=3,
        )

        self.assertEqual("complete", document["status"])
        self.assertEqual("complete", evidence["status"])
        self.assertEqual(3, run.call_count)
        self.assertEqual(2, sleep.call_count)

    @patch.object(PROVE_INSTALLED.time, "sleep")
    @patch.object(PROVE_INSTALLED.subprocess, "run")
    def test_other_incomplete_discovery_fails_closed_without_retry(self, run, sleep):
        run.return_value = completed({
            "operation": "symbol.discover",
            "status": "rejected",
            "reason": "project-unavailable",
        })

        with self.assertRaises(PROVE_INSTALLED.Rejected):
            PROVE_INSTALLED.run_operation_until_ready(
                "kast",
                "/exact/root",
                ["symbol", "discover"],
                "symbol.discover",
                maximum_attempts=3,
            )

        self.assertEqual(1, run.call_count)
        sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
