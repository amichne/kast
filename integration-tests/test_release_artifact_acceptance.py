#!/usr/bin/env python3
"""CLI independence includes the default Codex home when CODEX_HOME is absent."""
from pathlib import Path
from contextlib import ExitStack
import json
import os
import subprocess
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

    def test_unavailable_source_identity_retains_finite_failure_without_private_error_text(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            host = SimpleNamespace(root=root, runtime=root / "runtime", workspace=root,
                child_environment=lambda: {"KAST_ACCEPTANCE_IDEA_HOME": str(root)})
            bounds = {key: 1 for key in ("maximumOutputBytes", "maximumOperationSeconds", "maximumStartupSeconds", "maximumReconciliationSeconds")}
            observed = acceptance.ObservedAcceptance(root / "kast", host, bounds, root / "observations.json", "a" * 40)
            with mock.patch.object(acceptance.enterprise, "workspace_source_identity", side_effect=SystemExit("private source/path error")):
                with self.assertRaisesRegex(acceptance.gate.GateRejected, "repository source identity"):
                    observed.prove_workspace_preservation(acceptance.WorkspacePreservationStage.FIRST_UNINSTALL, "sha256:" + "a" * 64)
            serialized = (root / "observations.json").read_text()
            document = json.loads(serialized)
            self.assertEqual("repository-source-unproven", document["failure"])
            self.assertEqual("repository-source-unproven", document["workspacePreservation"][0]["cause"])
            self.assertNotIn("observedDigest", document["workspacePreservation"][0])
            self.assertNotIn("private source", serialized)


class WorkspacePreservationTest(unittest.TestCase):
    def journey(self, root, damage_stage=None, damage=None):
        workspace = root / "workspace"
        workspace.mkdir()
        def git(*args):
            return subprocess.check_output(["git", *args], cwd=workspace, stderr=subprocess.DEVNULL)
        git("init", "-b", "main")
        git("config", "user.name", "Acceptance Test")
        git("config", "user.email", "acceptance@kast.invalid")
        source = workspace / "Tracked.kt"
        source.write_text("class Tracked\n")
        git("add", ".")
        git("-c", "commit.gpgsign=false", "commit", "-m", "fixture")
        untracked = workspace / "PrivateUntracked.kt"
        untracked.write_text("class PrivateUntracked\n")
        source.write_text("class Staged\n")
        git("add", "Tracked.kt")
        original_diff = git("diff")
        home = root / "home"
        home.mkdir()
        runtime = root / "runtime"
        executable = root / "bin/kast"
        executable.parent.mkdir()
        def write_executable():
            executable.write_text("#!/bin/sh\nprintf 'kast 1.0.0 (IntelliJ sidecar)\\n'\n")
            executable.chmod(0o700)
        write_executable()
        def change(stage):
            if stage != damage_stage:
                return
            if damage == "untracked":
                untracked.unlink()
            elif damage == "staged":
                source.write_text("class ChangedWithoutDiff\n")
                git("add", "Tracked.kt")
            self.assertEqual(original_diff, git("diff"), "fixture must escape the previous git diff guard")
        host = SimpleNamespace(root=root, home=home, workspace=workspace, runtime=runtime,
            readiness_file=root / "readiness.json", broker_socket=root / "broker.sock",
            child_environment=lambda: {"KAST_ACCEPTANCE_IDEA_HOME": str(root), "KAST_CACHE_ROOT": str(runtime / "intellij-caches")})
        uninstalls = 0
        def run(command, *_):
            nonlocal uninstalls
            if "uninstall" in command:
                uninstalls += 1
                executable.unlink()
                change("first-uninstall" if uninstalls == 1 else "final-uninstall")
        def install(*_):
            write_executable()
            change("reinstall")
            return {}
        assets = root / "assets"
        assets.mkdir()
        asset_name = "kast-semantic-runtime-1.0.0-macos-aarch64.zip"
        bounds = {key: 60 for key in ("maximumOutputBytes", "maximumOperationSeconds", "maximumStartupSeconds", "maximumReconciliationSeconds")}
        def read(path):
            return bounds if path.name == "enterprise-acceptance.json" else {
                "status": "passed", "readOnlyCatalog": True, "cliEquivalent": True, "selectorReused": True}
        patches = [mock.patch.object(acceptance, "ROOT", root),
             mock.patch.object(acceptance.sys, "argv", ["acceptance", "--assets-directory", str(assets), "--version", "1.0.0", "--source-revision", "a" * 40]),
             mock.patch.dict(os.environ, {f"KAST_RELEASE_JDK_{feature}": str(root) for feature in (17, 21, 25)}),
             mock.patch.object(acceptance.gate, "admit_source"),
             mock.patch.object(acceptance.gate, "prepare_idea", return_value=root),
             mock.patch.object(acceptance.gate, "asset_identities", return_value={asset_name: "sha256:" + "a" * 64}),
             mock.patch.object(acceptance.gate, "digest", return_value="sha256:" + "a" * 64),
             mock.patch.object(acceptance.gate, "environment_identity", return_value={}),
             mock.patch.object(acceptance.gate, "read", side_effect=read),
             mock.patch.object(acceptance.gate, "run", side_effect=run),
             mock.patch.object(acceptance.gate, "validate_upgrade"),
             mock.patch.object(acceptance.gate, "validate_gradle_matrix"),
             mock.patch.object(acceptance.gate, "validate_semantic_corruption"),
             mock.patch.object(acceptance.enterprise.IsolatedAcceptanceHost, "create", return_value=host),
             mock.patch.object(acceptance.enterprise.AmbientBrokerSnapshot, "capture", return_value=SimpleNamespace(assert_unchanged=lambda: None)),
             mock.patch.object(acceptance.enterprise, "prepare_workspace_fixture"),
             mock.patch.object(acceptance.enterprise, "stop_indexer"),
             mock.patch.object(acceptance.shutil, "copytree"),
             mock.patch.object(acceptance.ObservedAcceptance, "prove_installed_surface"),
             mock.patch.object(acceptance.ObservedAcceptance, "prove_workspace_write_scope"),
             mock.patch.object(acceptance.semantic_corruption, "prove_semantic_corruption", return_value={}),
             mock.patch.object(acceptance.upgrade_acceptance, "install_candidate_with_upgrade_proof", return_value=({}, {})),
             mock.patch.object(acceptance, "install", side_effect=install)]
        with ExitStack() as stack:
            for patch in patches:
                stack.enter_context(patch)
            acceptance.main()

    def test_each_installation_transition_rejects_untracked_and_staged_source_damage(self):
        for stage in ("first-uninstall", "reinstall", "final-uninstall"):
            for damage in ("untracked", "staged"):
                with self.subTest(stage=stage, damage=damage), tempfile.TemporaryDirectory() as raw:
                    root = Path(raw).resolve()
                    with self.assertRaisesRegex(acceptance.gate.GateRejected, "repository source"):
                        self.journey(root, stage, damage)
                    report = (root / "build/reports/release-gate/installed-observations.json").read_text()
                    document = json.loads(report)
                    self.assertEqual("rejected", document["status"])
                    self.assertEqual("repository-source-changed", document["failure"])
                    observation = document["workspacePreservation"][-1]
                    self.assertEqual(stage, observation["stage"])
                    self.assertEqual("rejected", observation["status"])
                    self.assertEqual("repository-source-changed", observation["cause"])
                    self.assertNotEqual(observation["expectedDigest"], observation["observedDigest"])
                    self.assertNotIn("PrivateUntracked", report)
                    self.assertNotIn("Tracked.kt", report)
                    self.assertFalse((root / "build/reports/release-gate/installed.json").exists())

    def test_unchanged_source_retains_all_three_preservation_observations(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            self.journey(root)
            proof = json.loads((root / "build/reports/release-gate/installed.json").read_text())["workspacePreservation"]
            self.assertEqual(["first-uninstall", "reinstall", "final-uninstall"], [item["stage"] for item in proof])
            self.assertTrue(all(item["status"] == "passed" and item["expectedDigest"] == item["observedDigest"] for item in proof))
            self.assertEqual(1, len({item["expectedDigest"] for item in proof}))


if __name__ == "__main__":
    unittest.main()
