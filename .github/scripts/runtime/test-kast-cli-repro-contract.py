#!/usr/bin/env python3
"""Contract tests for the local Kast CLI incident reproduction platform."""

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
RUNNER = Path(__file__).with_name("kast-cli-repro.py")


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
                    "ready: true\nruntime: READY\nnext[2]: kast refresh,kast symbol find <query>"
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
            self.assertTrue(
                {
                    "home",
                    "help",
                    "files",
                    "symbol-find",
                    "symbol-show",
                    "symbol-refs",
                    "symbol-callers",
                    "symbol-callees",
                    "symbol-implementations",
                    "symbol-supertypes",
                    "symbol-subtypes",
                    "graph-summary",
                    "graph-nodes",
                    "graph-neighbors",
                    "graph-topology",
                    "graph-communities",
                    "graph-impact",
                    "check",
                    "change-rename",
                    "change-add-file",
                    "change-add-declaration",
                    "change-replace",
                    "apply-invalid",
                    "recover-invalid",
                    "refresh",
                    "refresh-observer",
                    "cold-up",
                    "cold-observer",
                }.issubset(names),
                names,
            )

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
