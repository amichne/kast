#!/usr/bin/env python3
"""CLI independence includes the default Codex home when CODEX_HOME is absent."""
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import release_artifact_acceptance as acceptance


class OptionalBrokerStateTest(unittest.TestCase):
    def test_default_codex_home_cannot_escape_the_no_broker_assertion(self):
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            host = SimpleNamespace(home=home, readiness_file=home / "explicit/readiness.json",
                                   broker_socket=home / "explicit/broker.sock")
            acceptance.assert_broker_absent(host)
            (home / ".codex").mkdir()
            with self.assertRaisesRegex(acceptance.gate.GateRejected, "optional broker state"):
                acceptance.assert_broker_absent(host)


class DurableObservationTest(unittest.TestCase):
    def test_failed_command_preserves_bounded_failure_after_cleanup(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            host = SimpleNamespace(root=root, runtime=root / "runtime", workspace=root,
                child_environment=lambda: {"KAST_ACCEPTANCE_IDEA_HOME": str(root)})
            bounds = {key: 1 for key in ("maximumOutputBytes", "maximumOperationSeconds", "maximumStartupSeconds", "maximumReconciliationSeconds")}
            observed = acceptance.ObservedAcceptance(root / "kast", host, bounds, root / "observations.json", "a" * 40)
            with mock.patch.object(acceptance.enterprise.Acceptance, "command", side_effect=SystemExit("private-source-error")):
                with self.assertRaises(SystemExit):
                    observed.command("symbol", "discover", "--query", "private-source-name")
            document = acceptance.gate.read(root / "observations.json")
            self.assertEqual("rejected", document["status"])
            self.assertEqual("command-rejected", document["failure"])
            self.assertEqual("symbol discover", document["observations"][0]["command"])
            self.assertNotIn("private-source", (root / "observations.json").read_text())
            sample = {"schemaVersion": 1, "stage": "after-stop", "status": "not-running", "cause": "pid-marker-absent"}
            with mock.patch.object(acceptance.enterprise.Acceptance, "command", return_value={"runtime": "stopped"}), mock.patch.object(acceptance.resources, "observe", return_value=sample):
                observed.command("stop")
            self.assertEqual("rejected", acceptance.gate.read(root / "observations.json")["status"])

    def test_successful_lifecycle_records_all_resource_stages(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            host = SimpleNamespace(root=root, runtime=root / "runtime", workspace=root,
                child_environment=lambda: {"KAST_ACCEPTANCE_IDEA_HOME": str(root)})
            bounds = {key: 1 for key in ("maximumOutputBytes", "maximumOperationSeconds", "maximumStartupSeconds", "maximumReconciliationSeconds")}
            observed = acceptance.ObservedAcceptance(root / "kast", host, bounds, root / "observations.json", "a" * 40)
            def sample(stage, **_):
                return {"schemaVersion": 1, "stage": stage.value, "status": "not-running" if stage.value == "after-stop" else "observed"}
            with mock.patch.object(acceptance.resources, "observe", side_effect=sample):
                for argv, result in [(("start",), {"runtime": "running"}), (("symbol", "discover"), {"status": "complete"}),
                                     (("stop",), {"runtime": "stopped"}), (("start",), {"runtime": "running"})]:
                    with mock.patch.object(acceptance.enterprise.Acceptance, "command", return_value=result):
                        observed.command(*argv)
            self.assertEqual({stage.value for stage in acceptance.resources.ResourceStage}, {sample["stage"] for sample in observed.resource_samples})


if __name__ == "__main__":
    unittest.main()
