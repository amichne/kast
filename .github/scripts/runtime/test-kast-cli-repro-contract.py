#!/usr/bin/env python3
"""Contract tests for the local Kast CLI incident reproduction platform."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
RUNNER = Path(__file__).with_name("kast-cli-repro.py")


def load_runner_module():
    module_name = "kast_cli_repro_contract_subject"
    existing = sys.modules.get(module_name)
    if existing is not None:
        return existing
    spec = importlib.util.spec_from_file_location(module_name, RUNNER)
    if spec is None or spec.loader is None:
        raise AssertionError("cannot load the Kast CLI reproduction runner")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


class KastCliReproContractTest(unittest.TestCase):
    def run_runner(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        self.assertTrue(RUNNER.is_file(), "the Kast CLI reproduction runner is missing")
        return subprocess.run(
            ["python3", str(RUNNER), *arguments],
            cwd=REPOSITORY_ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def write_evidence(
        self,
        directory: Path,
        *,
        incident: bool,
    ) -> None:
        transcripts = directory / "transcripts"
        transcripts.mkdir(parents=True)

        def command(
            name: str,
            text: str,
            *,
            started: int,
            finished: int,
            exit_code: int = 0,
            output_bytes: int | None = None,
        ) -> dict[str, object]:
            path = transcripts / f"{name}.txt"
            path.write_text(text, encoding="utf-8")
            return {
                "name": name,
                "argv": ["kast", name],
                "startedAtEpochMillis": started,
                "finishedAtEpochMillis": finished,
                "exitCode": exit_code,
                "timedOut": False,
                "transcript": str(path.relative_to(directory)),
                "outputBytes": output_bytes if output_bytes is not None else len(text.encode()),
            }

        terminated = "result: ok\n::kast-repro-exit=0\n"
        if incident:
            commands = [
                command("cold-up", terminated, started=100, finished=500),
                command(
                    "cold-observer",
                    "__KAST_SAMPLE__=1\nready: true\nruntime: READY\n"
                    "__KAST_SAMPLE_EPOCH_MILLIS__=200\n"
                    "next[2]: kast refresh,kast symbol find <query>"
                    "::kast-repro-exit=0\n",
                    started=120,
                    finished=300,
                ),
                command(
                    "refresh",
                    "error: WORKSPACE_RECONCILIATION_REQUIRED\n"
                    "next: \"Run `kast --help` for valid commands and arguments.\"\n"
                    "::kast-repro-exit=1\n",
                    started=600,
                    finished=900,
                    exit_code=1,
                ),
                command(
                    "refresh-observer",
                    "ready: false\nruntime: INDEXING\nerror: CONFLICT\n"
                    "message: Semantic operation started while the workspace was not READY\n"
                    "next: \"Run `kast --help` for valid commands and arguments.\"\n"
                    "::kast-repro-exit=0\n",
                    started=620,
                    finished=780,
                ),
                command(
                    "graph-nodes",
                    terminated,
                    started=1000,
                    finished=1100,
                    output_bytes=139_205,
                ),
            ]
            telemetry = {
                "name": "kast.idea.resolveSymbol",
                "attributes": {"kast.workspace.root": "/tmp/workspace"},
            }
        else:
            commands = [
                command("cold-up", terminated, started=100, finished=110),
                command("cold-observer", "ready: false\n::kast-repro-exit=0\n", started=120, finished=130),
                command("refresh", terminated, started=200, finished=210),
                command("refresh-observer", "ready: false\n::kast-repro-exit=0\n", started=220, finished=230),
                command("graph-nodes", terminated, started=300, finished=310, output_bytes=2_000),
            ]
            telemetry = {
                "name": "kast.idea.resolveSymbol",
                "attributes": {
                    "runtimeInstanceId": "runtime-1",
                    "semanticGenerationStart": 4,
                    "semanticGenerationEnd": 4,
                    "dumbModeState": "SMART",
                    "typedOutcome": "RESOLVED",
                },
            }

        (directory / "telemetry.jsonl").write_text(json.dumps(telemetry) + "\n", encoding="utf-8")
        manifest = {
            "schemaVersion": 1,
            "workspaceRoot": "/tmp/workspace",
            "sessionName": "kast-cli-repro-test",
            "commands": commands,
            "telemetry": "telemetry.jsonl",
        }
        (directory / "manifest.json").write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

    def test_incident_evidence_emits_stable_findings(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=True)

            result = self.run_runner("analyze", "--evidence-dir", str(directory), "--format", "json")

            self.assertEqual(1, result.returncode, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual("OBSERVATIONS_REPRODUCED", report["status"])
            codes = {finding["code"] for finding in report["findings"]}
            self.assertEqual(
                {
                    "READY_DURING_PENDING_UP",
                    "GENERIC_CONFLICT_DURING_REFRESH",
                    "REFRESH_DID_NOT_CONVERGE",
                    "MISSING_TRAILING_NEWLINE",
                    "DEFAULT_OUTPUT_EXCEEDS_BUDGET",
                    "TRACE_CORRELATION_INCOMPLETE",
                },
                codes,
            )

    def test_clean_evidence_is_a_green_replay(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)

            result = self.run_runner("analyze", "--evidence-dir", str(directory), "--format", "json")

            self.assertEqual(0, result.returncode, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual("PASS", report["status"])
            self.assertEqual([], report["findings"])

    def test_capsule_stop_failure_is_distinct_from_process_leak(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["capsule"] = {
                "mode": "EPHEMERAL",
                "installationContained": True,
                "stateContained": True,
                "runtimeStopSucceeded": False,
                "runtimeStopped": True,
                "processesRemaining": [],
                "rootDeleted": True,
            }
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

            result = self.run_runner("analyze", "--evidence-dir", str(directory), "--format", "json")

            self.assertEqual(1, result.returncode, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(
                ["CAPSULE_RUNTIME_STOP_FAILED"],
                [finding["code"] for finding in report["findings"]],
            )

    def test_forced_cleanup_process_is_reported_as_a_public_stop_leak(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["capsule"] = {
                "mode": "EPHEMERAL",
                "installationContained": True,
                "stateContained": True,
                "runtimeStopSucceeded": True,
                "runtimeStopped": True,
                "terminatedProcessIds": [4242],
                "processesRemaining": [],
                "rootDeleted": True,
            }
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

            result = self.run_runner("analyze", "--evidence-dir", str(directory), "--format", "json")

            self.assertEqual(1, result.returncode, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(
                ["CAPSULE_PROCESS_LEAKED"],
                [finding["code"] for finding in report["findings"]],
            )

    def test_ready_sample_after_up_completion_is_not_pending_up_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            cold_up = next(command for command in manifest["commands"] if command["name"] == "cold-up")
            cold_up["finishedAtEpochMillis"] = 200
            observer = next(
                command for command in manifest["commands"] if command["name"] == "cold-observer"
            )
            observer["startedAtEpochMillis"] = 120
            observer["finishedAtEpochMillis"] = 300
            (directory / observer["transcript"]).write_text(
                "__KAST_SAMPLE__=1\nready: true\n"
                "__KAST_SAMPLE_EPOCH_MILLIS__=250\n::kast-repro-exit=0\n",
                encoding="utf-8",
            )
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

            result = self.run_runner("analyze", "--evidence-dir", str(directory), "--format", "json")

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], json.loads(result.stdout)["findings"])

    def test_telemetry_fields_distributed_across_requests_are_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            records = [
                {"attributes": {"runtimeInstanceId": "runtime-1"}},
                {"attributes": {"semanticGenerationStart": 4}},
                {"attributes": {"semanticGenerationEnd": 4}},
                {"attributes": {"dumbModeState": "SMART"}},
                {"attributes": {"typedOutcome": "RESOLVED"}},
            ]
            (directory / "telemetry.jsonl").write_text(
                "".join(json.dumps(record) + "\n" for record in records),
                encoding="utf-8",
            )

            result = self.run_runner("analyze", "--evidence-dir", str(directory), "--format", "json")

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertEqual(
                ["TRACE_CORRELATION_INCOMPLETE"],
                [finding["code"] for finding in json.loads(result.stdout)["findings"]],
            )

    def test_dry_run_covers_the_public_agent_surface(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            workspace = Path(raw_directory)
            source = workspace / "src" / "Probe.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package example\nclass Probe\n", encoding="utf-8")

            result = self.run_runner(
                "capture",
                "--workspace-root",
                str(workspace),
                "--file",
                "src/Probe.kt",
                "--symbol",
                "example.Probe",
                "--restart-runtime",
                "--exercise-plans",
                "--dry-run",
            )

            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads(result.stdout)
            names = {command["name"] for command in plan["commands"]}
            cold_observer = next(
                command for command in plan["commands"] if command["name"] == "cold-observer"
            )
            self.assertIn("__KAST_SAMPLE_EPOCH_MILLIS__", cold_observer["argv"][-1])
            by_name = {command["name"]: command for command in plan["commands"]}
            self.assertEqual(
                ["file", "list", "--match", "src/Probe.kt"],
                by_name["file-list"]["argv"][1:],
            )
            self.assertEqual(
                ["symbol", "search", "--query", "example.Probe"],
                by_name["symbol-search"]["argv"][1:],
            )
            self.assertEqual(
                ["symbol", "resolve", "--query", "example.Probe"],
                by_name["symbol-resolve"]["argv"][1:],
            )
            self.assertEqual(
                ["workspace", "ensure"],
                by_name["cold-up"]["argv"][1:],
            )
            symbol_show = by_name["symbol-show"]["argv"][-1]
            graph_neighbors = by_name["graph-neighbors"]["argv"][-1]
            self.assertIn("symbol resolve --query example.Probe", symbol_show)
            self.assertIn('symbol show --selector "$selector"', symbol_show)
            self.assertNotIn("--selector example.Probe", symbol_show)
            self.assertIn("graph nodes", graph_neighbors)
            self.assertIn('graph neighbors --node-selector "$node_selector"', graph_neighbors)
            self.assertTrue(
                {
                    "home",
                    "help",
                    "file-list",
                    "symbol-search",
                    "symbol-resolve",
                    "symbol-show",
                    "relation-references",
                    "relation-calls-incoming",
                    "relation-calls-outgoing",
                    "relation-implementations",
                    "relation-hierarchy-supertypes",
                    "relation-hierarchy-subtypes",
                    "graph-summary",
                    "graph-nodes",
                    "graph-neighbors",
                    "graph-topology",
                    "graph-communities",
                    "graph-impact",
                    "diagnostic-check",
                    "change-plan-rename",
                    "change-plan-add-file",
                    "change-plan-add-declaration",
                    "change-plan-replace",
                    "workspace-refresh",
                    "refresh-observer",
                    "cold-up",
                    "cold-observer",
                }.issubset(names),
                names,
            )

    def test_capsule_process_ownership_requires_a_path_boundary(self) -> None:
        runner = load_runner_module()
        root = Path("/tmp/kast")

        self.assertTrue(runner.command_mentions_capsule_root("java --state /tmp/kast/cache", root))
        self.assertFalse(
            runner.command_mentions_capsule_root("java --state /tmp/kast-backup/cache", root)
        )

    def test_missing_tmux_is_rejected_before_ephemeral_capsule_allocation(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            workspace = Path(raw_directory)
            source = workspace / "src" / "Probe.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package example\nclass Probe\n", encoding="utf-8")
            args = runner.parser().parse_args(
                [
                    "capture",
                    "--workspace-root",
                    str(workspace),
                    "--file",
                    "src/Probe.kt",
                    "--symbol",
                    "example.Probe",
                    "--ephemeral-capsule",
                ]
            )

            def executable(name: str):
                return "/usr/bin/true" if name == "kast" else None

            with (
                mock.patch.object(runner.shutil, "which", side_effect=executable),
                mock.patch.object(runner, "build_capsule", return_value=None) as build_capsule,
                self.assertRaises(runner.ReproError),
            ):
                runner.capture(args)

            build_capsule.assert_not_called()

    def test_invalid_session_is_rejected_before_ephemeral_capsule_allocation(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory)
            workspace = root / "workspace"
            source = workspace / "src" / "Probe.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package example\nclass Probe\n", encoding="utf-8")
            output = root / "evidence"
            args = runner.parser().parse_args(
                [
                    "capture",
                    "--workspace-root",
                    str(workspace),
                    "--file",
                    "src/Probe.kt",
                    "--symbol",
                    "example.Probe",
                    "--output-dir",
                    str(output),
                    "--session-name",
                    "invalid session",
                    "--ephemeral-capsule",
                ]
            )

            with (
                mock.patch.object(runner.shutil, "which", return_value="/usr/bin/true"),
                mock.patch.object(runner, "build_capsule", return_value=None) as build_capsule,
                self.assertRaises(runner.ReproError),
            ):
                runner.capture(args)

            build_capsule.assert_not_called()
            self.assertFalse(output.exists())

    def test_nonempty_output_is_rejected_before_ephemeral_capsule_allocation(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory)
            workspace = root / "workspace"
            source = workspace / "src" / "Probe.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package example\nclass Probe\n", encoding="utf-8")
            output = root / "evidence"
            output.mkdir()
            (output / "existing.txt").write_text("keep\n", encoding="utf-8")
            args = runner.parser().parse_args(
                [
                    "capture",
                    "--workspace-root",
                    str(workspace),
                    "--file",
                    "src/Probe.kt",
                    "--symbol",
                    "example.Probe",
                    "--output-dir",
                    str(output),
                    "--ephemeral-capsule",
                ]
            )

            with (
                mock.patch.object(runner.shutil, "which", return_value="/usr/bin/true"),
                mock.patch.object(runner, "build_capsule", return_value=None) as build_capsule,
                self.assertRaises(runner.ReproError),
            ):
                runner.capture(args)

            build_capsule.assert_not_called()
            self.assertEqual("keep\n", (output / "existing.txt").read_text(encoding="utf-8"))

    def test_ephemeral_capsule_plan_confines_install_state_and_teardown(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory)
            workspace = root / "workspace"
            source = workspace / "src" / "Probe.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package example\nclass Probe\n", encoding="utf-8")
            bundle = root / "bundle"
            bundle.mkdir()

            result = self.run_runner(
                "capture",
                "--workspace-root",
                str(workspace),
                "--file",
                "src/Probe.kt",
                "--symbol",
                "example.Probe",
                "--bundle-source",
                str(bundle),
                "--ephemeral-capsule",
                "--dry-run",
            )

            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads(result.stdout)
            capsule = plan["capsule"]
            self.assertEqual("EPHEMERAL", capsule["mode"])
            self.assertEqual("<ephemeral>", capsule["root"])
            self.assertEqual("STOP_VERIFY_DELETE", capsule["cleanup"])
            self.assertEqual(str(bundle.resolve()), capsule["bundleSource"])
            self.assertEqual("<supported-idea-host>", capsule["ideaHost"])
            self.assertEqual(
                {
                    "HOME",
                    "KAST_HOME",
                    "KAST_CONFIG_HOME",
                    "KAST_CACHE_HOME",
                    "GRADLE_USER_HOME",
                    "TMPDIR",
                    "XDG_CACHE_HOME",
                    "XDG_CONFIG_HOME",
                    "XDG_DATA_HOME",
                    "KAST_WORKSPACE_ID",
                    "KAST_IDEA_TRACE",
                    "PATH",
                },
                set(capsule["environment"]),
            )
            for key, value in capsule["environment"].items():
                if key not in {"KAST_WORKSPACE_ID", "KAST_IDEA_TRACE", "PATH"}:
                    self.assertTrue(value.startswith("<ephemeral>/"), (key, value))
            self.assertTrue(capsule["install"]["kast"].startswith("<ephemeral>/"))
            self.assertTrue(capsule["install"]["kastctl"].startswith("<ephemeral>/"))
            names = [command["name"] for command in plan["commands"]]
            self.assertEqual("capsule-setup", names[0])
            self.assertEqual("capsule-idea-host", names[1])
            self.assertEqual(
                ["capsule-runtime-stop", "capsule-teardown-verify"],
                names[-2:],
            )

    def test_persistent_capsule_plan_keeps_one_explicit_root(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory)
            workspace = root / "workspace"
            source = workspace / "src" / "Probe.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package example\nclass Probe\n", encoding="utf-8")
            bundle = root / "bundle"
            bundle.mkdir()
            capsule_root = root / "persistent-capsule"

            result = self.run_runner(
                "capture",
                "--workspace-root",
                str(workspace),
                "--file",
                "src/Probe.kt",
                "--symbol",
                "example.Probe",
                "--bundle-source",
                str(bundle),
                "--capsule-root",
                str(capsule_root),
                "--dry-run",
            )

            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads(result.stdout)
            capsule = plan["capsule"]
            self.assertEqual("PERSISTENT", capsule["mode"])
            self.assertEqual(str(capsule_root.resolve()), capsule["root"])
            self.assertEqual("STOP_VERIFY_KEEP", capsule["cleanup"])
            for key, value in capsule["environment"].items():
                if key not in {"KAST_WORKSPACE_ID", "KAST_IDEA_TRACE", "PATH"}:
                    self.assertTrue(
                        Path(value).is_relative_to(capsule_root.resolve()),
                        (key, value),
                    )


if __name__ == "__main__":
    unittest.main()
