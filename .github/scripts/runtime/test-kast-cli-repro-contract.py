#!/usr/bin/env python3
"""Contract tests for the local Kast CLI incident reproduction platform."""

from __future__ import annotations

import dataclasses
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
RUNNER = Path(__file__).with_name("kast-cli-repro.py")
REQUIRED_SCENARIO_COMMANDS = (
    "runtime-stop",
    "cold-up",
    "cold-observer",
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
    "workspace-refresh",
    "refresh-observer",
)


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
            completion_token = f"contract-{name}"
            completion_frame = (
                f"::kast-repro-exit={completion_token}:{exit_code}:{finished}"
            )
            text = text.replace(f"::kast-repro-exit={exit_code}", completion_frame)
            path = transcripts / f"{name}.txt"
            path.write_text(text, encoding="utf-8")
            return {
                "name": name,
                "argv": ["kast", name],
                "startedAtEpochMillis": started,
                "finishedAtEpochMillis": finished,
                "exitCode": exit_code,
                "timedOut": False,
                "completionToken": completion_token,
                "transcript": str(path.relative_to(directory)),
                "outputBytes": output_bytes if output_bytes is not None else len(text.encode()),
            }

        terminated = "result: ok\n::kast-repro-exit=0\n"
        if incident:
            commands = [
                command("cold-up", terminated, started=100, finished=500),
                command(
                    "cold-observer",
                    "__KAST_SAMPLE__=1\n__KAST_OBSERVATION__=HOME\n"
                    "ready: true\nruntime: READY\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=200\n"
                    "__KAST_OBSERVATION__=RESOLVE\n"
                    "next[2]: kast refresh,kast symbol find <query>\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=250\n"
                    "__KAST_SAMPLE__=2\n__KAST_OBSERVATION__=HOME\n"
                    "ready: false\nruntime: READY\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=510\n"
                    "__KAST_OBSERVATION__=RESOLVE\nresult: resolved\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=520\n"
                    "unterminated::kast-repro-exit=0\n",
                    started=120,
                    finished=550,
                ),
                command(
                    "workspace-refresh",
                    "error: WORKSPACE_RECONCILIATION_REQUIRED\n"
                    "next: \"Run `kast --help` for valid commands and arguments.\"\n"
                    "::kast-repro-exit=1\n",
                    started=600,
                    finished=900,
                    exit_code=1,
                ),
                command(
                    "refresh-observer",
                    "__KAST_SAMPLE__=1\n__KAST_OBSERVATION__=HOME\n"
                    "ready: false\nruntime: INDEXING\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=650\n"
                    "__KAST_OBSERVATION__=RESOLVE\nerror: CONFLICT\n"
                    "message: Semantic operation started while the workspace was not READY\n"
                    "next: \"Run `kast --help` for valid commands and arguments.\"\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=1\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=700\n"
                    "__KAST_SAMPLE__=2\n__KAST_OBSERVATION__=HOME\n"
                    "ready: true\nruntime: READY\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=910\n"
                    "__KAST_OBSERVATION__=RESOLVE\nresult: resolved\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=920\n"
                    "::kast-repro-exit=0\n",
                    started=620,
                    finished=950,
                ),
                command(
                    "graph-nodes",
                    "x" * 30_001 + "\n::kast-repro-exit=0\n",
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
                command(
                    "cold-observer",
                    "__KAST_SAMPLE__=1\n__KAST_OBSERVATION__=HOME\nready: false\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=108\n"
                    "__KAST_OBSERVATION__=RESOLVE\nresult: missing\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=109\n"
                    "__KAST_SAMPLE__=2\n__KAST_OBSERVATION__=HOME\nready: true\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=115\n"
                    "__KAST_OBSERVATION__=RESOLVE\nresult: resolved\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=116\n::kast-repro-exit=0\n",
                    started=105,
                    finished=130,
                ),
                command("workspace-refresh", terminated, started=200, finished=210),
                command(
                    "refresh-observer",
                    "__KAST_SAMPLE__=1\n__KAST_OBSERVATION__=HOME\nready: true\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=208\n"
                    "__KAST_OBSERVATION__=RESOLVE\nresult: resolved\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=209\n"
                    "__KAST_SAMPLE__=2\n__KAST_OBSERVATION__=HOME\nready: true\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=215\n"
                    "__KAST_OBSERVATION__=RESOLVE\nresult: resolved\n"
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                    "__KAST_OBSERVATION_EPOCH_MILLIS__=216\n::kast-repro-exit=0\n",
                    started=205,
                    finished=230,
                ),
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

        present = {item["name"] for item in commands}
        for index, name in enumerate(REQUIRED_SCENARIO_COMMANDS):
            if name not in present:
                started = 1_200 + index * 20
                commands.append(
                    command(name, terminated, started=started, finished=started + 10)
                )

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

    def append_command_evidence(
        self,
        directory: Path,
        manifest: dict[str, Any],
        name: str,
        *,
        exit_code: int = 0,
    ) -> None:
        completion_token = f"contract-{name}"
        started = 2_000 + len(manifest["commands"]) * 20
        finished = started + 10
        transcript_path = directory / "transcripts" / f"{name}.txt"
        transcript = (
            "result: ok\n"
            f"::kast-repro-exit={completion_token}:{exit_code}:{finished}\n"
        )
        transcript_path.write_text(transcript, encoding="utf-8")
        manifest["commands"].append(
            {
                "name": name,
                "argv": ["kastctl", name],
                "startedAtEpochMillis": started,
                "finishedAtEpochMillis": finished,
                "exitCode": exit_code,
                "timedOut": False,
                "completionToken": completion_token,
                "transcript": str(transcript_path.relative_to(directory)),
                "outputBytes": len(transcript.encode()),
            }
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
                "terminatedProcessIds": [],
                "processesRemaining": [],
                "rootDeleted": True,
            }
            self.append_command_evidence(
                directory,
                manifest,
                "capsule-runtime-stop",
                exit_code=1,
            )
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
            self.append_command_evidence(directory, manifest, "capsule-runtime-stop")
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
            cold_up_transcript = directory / str(cold_up["transcript"])
            cold_up_transcript.write_text(
                cold_up_transcript.read_text(encoding="utf-8").replace(
                    f"::kast-repro-exit={cold_up['completionToken']}:0:110",
                    f"::kast-repro-exit={cold_up['completionToken']}:0:200",
                ),
                encoding="utf-8",
            )
            cold_up["finishedAtEpochMillis"] = 200
            observer = next(
                command for command in manifest["commands"] if command["name"] == "cold-observer"
            )
            observer["startedAtEpochMillis"] = 120
            observer["finishedAtEpochMillis"] = 300
            (directory / observer["transcript"]).write_text(
                "__KAST_SAMPLE__=1\n__KAST_OBSERVATION__=HOME\nready: false\n"
                "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                "__KAST_OBSERVATION_EPOCH_MILLIS__=150\n"
                "__KAST_OBSERVATION__=RESOLVE\nresult: missing\n"
                "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                "__KAST_OBSERVATION_EPOCH_MILLIS__=160\n"
                "__KAST_SAMPLE__=2\n__KAST_OBSERVATION__=HOME\nready: true\n"
                "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                "__KAST_OBSERVATION_EPOCH_MILLIS__=250\n"
                "__KAST_OBSERVATION__=RESOLVE\nresult: resolved\n"
                "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                "__KAST_OBSERVATION_EPOCH_MILLIS__=260\n"
                f"::kast-repro-exit={observer['completionToken']}:0:300\n",
                encoding="utf-8",
            )
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

            result = self.run_runner("analyze", "--evidence-dir", str(directory), "--format", "json")

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], json.loads(result.stdout)["findings"])

    def test_observed_operation_records_operation_completion_before_observer(self) -> None:
        runner = load_runner_module()
        capture = mock.Mock()
        operation = mock.sentinel.operation
        observer = mock.sentinel.observer
        operation_evidence = mock.sentinel.operation_evidence
        observer_evidence = mock.sentinel.observer_evidence
        capture.finish.side_effect = [operation_evidence, observer_evidence]

        evidence = runner.finish_observed_operation(capture, operation, observer)

        self.assertEqual((operation_evidence, observer_evidence), evidence)
        self.assertEqual(
            [mock.call(operation), mock.call(observer)],
            capture.finish.call_args_list,
        )
        capture.request_observer_completion.assert_called_once_with(observer)

    def test_command_completion_uses_a_nonce_and_the_wrapper_timestamp(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            evidence = directory / "evidence"
            (evidence / "transcripts").mkdir(parents=True)
            capture = runner.TmuxCapture("session", directory, evidence, keep_session=False)
            active = runner.ActiveCommand(
                runner.CommandSpec("probe", ("kast", "symbol", "show")),
                "%1",
                100,
                "nonce",
                float("inf"),
                300,
            )
            collision = subprocess.CompletedProcess(
                [],
                0,
                "source preview ::kast-repro-exit=9\n",
                "",
            )
            completion = subprocess.CompletedProcess(
                [],
                0,
                "source preview ::kast-repro-exit=9"
                "::kast-repro-exit=nonce:0:234\n",
                "",
            )
            killed = subprocess.CompletedProcess([], 0, "", "")

            with (
                mock.patch.object(
                    runner.subprocess,
                    "run",
                    side_effect=[collision, completion, killed],
                ),
                mock.patch.object(runner.time, "sleep"),
            ):
                command = capture.finish(active)

            self.assertEqual(0, command.exitCode)
            self.assertEqual(234, command.finishedAtEpochMillis)

    def test_command_timeout_deadline_is_bound_when_the_command_begins(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            evidence = directory / "evidence"
            (evidence / "transcripts").mkdir(parents=True)
            capture = runner.TmuxCapture("session", directory, evidence, keep_session=False)
            active = runner.ActiveCommand(
                runner.CommandSpec(
                    "probe",
                    ("kast", "symbol", "show"),
                    timeout_seconds=50.0,
                ),
                "%1",
                100,
                "nonce",
                100.0,
                150,
            )
            pending = subprocess.CompletedProcess([], 0, "still running\n", "")
            late_completion = subprocess.CompletedProcess(
                [],
                0,
                "::kast-repro-exit=nonce:0:234\n",
                "",
            )
            killed = subprocess.CompletedProcess([], 0, "", "")

            with (
                mock.patch.object(
                    runner.subprocess,
                    "run",
                    side_effect=[pending, killed, late_completion, killed],
                ),
                mock.patch.object(runner.time, "monotonic", return_value=101.0),
                mock.patch.object(runner.time, "sleep"),
            ):
                command = capture.finish(active)

            self.assertTrue(command.timedOut)
            self.assertEqual(124, command.exitCode)

    def test_completion_recorded_after_the_deadline_is_timed_out(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            evidence = directory / "evidence"
            (evidence / "transcripts").mkdir(parents=True)
            capture = runner.TmuxCapture("session", directory, evidence, keep_session=False)
            active = runner.ActiveCommand(
                runner.CommandSpec("probe", ("kast", "symbol", "show")),
                "%1",
                100,
                "nonce",
                1_000.0,
                200,
            )
            late_completion = subprocess.CompletedProcess(
                [],
                0,
                "::kast-repro-exit=nonce:0:234\n",
                "",
            )
            killed = subprocess.CompletedProcess([], 0, "", "")

            with (
                mock.patch.object(
                    runner.subprocess,
                    "run",
                    side_effect=[late_completion, killed],
                ),
                mock.patch.object(runner.time, "monotonic", return_value=101.0),
            ):
                command = capture.finish(active)

            self.assertTrue(command.timedOut)
            self.assertEqual(124, command.exitCode)

    def test_incomplete_cold_observer_cannot_replay_as_clean(self) -> None:
        for failure in ("failed-command", "missing-home"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as raw_directory:
                directory = Path(raw_directory)
                self.write_evidence(directory, incident=False)
                manifest_path = directory / "manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                observer = next(
                    command for command in manifest["commands"]
                    if command["name"] == "cold-observer"
                )
                if failure == "failed-command":
                    observer["exitCode"] = 1
                    transcript_path = directory / str(observer["transcript"])
                    transcript_path.write_text(
                        transcript_path.read_text(encoding="utf-8").replace(
                            f"::kast-repro-exit={observer['completionToken']}:0:130",
                            f"::kast-repro-exit={observer['completionToken']}:1:130",
                        ),
                        encoding="utf-8",
                    )
                else:
                    (directory / observer["transcript"]).write_text(
                        "no parseable observations\n"
                        f"::kast-repro-exit={observer['completionToken']}:0:130\n",
                        encoding="utf-8",
                    )
                manifest_path.write_text(
                    json.dumps(manifest, indent=2) + "\n",
                    encoding="utf-8",
                )

                result = self.run_runner(
                    "analyze", "--evidence-dir", str(directory), "--format", "json"
                )

                self.assertEqual(1, result.returncode, result.stderr)
                self.assertEqual(
                    ["OBSERVER_EVIDENCE_INCOMPLETE"],
                    [finding["code"] for finding in json.loads(result.stdout)["findings"]],
                )

    def test_missing_transition_operation_cannot_replay_as_clean(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["commands"] = []
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertEqual("INVALID_EVIDENCE", json.loads(result.stderr)["status"])

    def test_missing_scenario_command_cannot_replay_as_clean(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["commands"] = [
                command for command in manifest["commands"]
                if command["name"] != "graph-topology"
            ]
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("graph-topology", error["error"])

    def test_failed_required_scenario_command_cannot_replay_as_clean(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            topology = next(
                command for command in manifest["commands"]
                if command["name"] == "graph-topology"
            )
            topology["exitCode"] = 1
            topology["timedOut"] = True
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("graph-topology", error["error"])

    def test_transition_timeout_status_must_be_boolean(self) -> None:
        for timed_out in (None, "false"):
            with self.subTest(timed_out=timed_out), tempfile.TemporaryDirectory() as raw_directory:
                directory = Path(raw_directory)
                self.write_evidence(directory, incident=False)
                manifest_path = directory / "manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                refresh = next(
                    command
                    for command in manifest["commands"]
                    if command["name"] == "workspace-refresh"
                )
                if timed_out is None:
                    refresh.pop("timedOut")
                else:
                    refresh["timedOut"] = timed_out
                manifest_path.write_text(
                    json.dumps(manifest, indent=2) + "\n",
                    encoding="utf-8",
                )

                result = self.run_runner(
                    "analyze", "--evidence-dir", str(directory), "--format", "json"
                )

                self.assertEqual(2, result.returncode)
                self.assertEqual("", result.stdout)
                error = json.loads(result.stderr)
                self.assertEqual("INVALID_EVIDENCE", error["status"])
                self.assertIn("workspace-refresh", error["error"])

    def test_transition_timeout_preserves_findings_without_an_exact_frame(self) -> None:
        for retained_frame in (False, True):
            with (
                self.subTest(retained_frame=retained_frame),
                tempfile.TemporaryDirectory() as raw_directory,
            ):
                directory = Path(raw_directory)
                self.write_evidence(directory, incident=False)
                manifest_path = directory / "manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                refresh = next(
                    command
                    for command in manifest["commands"]
                    if command["name"] == "workspace-refresh"
                )
                refresh["exitCode"] = 124
                refresh["timedOut"] = True
                if not retained_frame:
                    (directory / str(refresh["transcript"])).write_text(
                        "partial output before timeout\n",
                        encoding="utf-8",
                    )
                manifest_path.write_text(
                    json.dumps(manifest, indent=2) + "\n",
                    encoding="utf-8",
                )

                result = self.run_runner(
                    "analyze", "--evidence-dir", str(directory), "--format", "json"
                )

                self.assertEqual(1, result.returncode, result.stderr)
                self.assertEqual(
                    ["REFRESH_DID_NOT_CONVERGE"],
                    [finding["code"] for finding in json.loads(result.stdout)["findings"]],
                )

    def test_nonobject_manifest_is_structured_invalid_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            (directory / "manifest.json").write_text("[]\n", encoding="utf-8")

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertNotIn("Traceback", result.stderr)

    def test_invalid_utf8_manifest_is_structured_invalid_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            (directory / "manifest.json").write_bytes(b"\xff\xfe\x00")

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertNotIn("Traceback", result.stderr)

    def test_nonobject_capsule_proof_is_structured_invalid_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["capsule"] = []
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("capsule", error["error"])

    def test_incomplete_capsule_proof_is_structured_invalid_evidence(self) -> None:
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
                "processesRemaining": [],
                "rootDeleted": True,
            }
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("terminatedProcessIds", error["error"])

    def test_replay_requires_runtime_stop_and_cold_transition_commands(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["commands"] = [
                command
                for command in manifest["commands"]
                if command["name"]
                not in {"runtime-stop", "cold-up", "cold-observer"}
            ]
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("runtime-stop", error["error"])
            self.assertIn("cold-observer", error["error"])
            self.assertIn("cold-up", error["error"])

    def test_capsule_replay_requires_runtime_stop_command(self) -> None:
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
                "terminatedProcessIds": [],
                "processesRemaining": [],
                "rootDeleted": True,
            }
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("capsule-runtime-stop", error["error"])

    def test_capsule_stop_proof_matches_captured_command_status(self) -> None:
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
                "terminatedProcessIds": [],
                "processesRemaining": [],
                "rootDeleted": True,
            }
            self.append_command_evidence(
                directory,
                manifest,
                "capsule-runtime-stop",
                exit_code=1,
            )
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("runtimeStopSucceeded", error["error"])

    def test_required_command_requires_completion_token(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            topology = next(
                command
                for command in manifest["commands"]
                if command["name"] == "graph-topology"
            )
            topology.pop("completionToken")
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("graph-topology", error["error"])

    def test_required_command_requires_exact_nonce_bound_completion_frame(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            topology = next(
                command
                for command in manifest["commands"]
                if command["name"] == "graph-topology"
            )
            transcript_path = directory / str(topology["transcript"])
            transcript_path.write_text(
                transcript_path.read_text(encoding="utf-8").replace(
                    str(topology["completionToken"]),
                    "wrong-completion-token",
                ),
                encoding="utf-8",
            )
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)
            error = json.loads(result.stderr)
            self.assertEqual("INVALID_EVIDENCE", error["status"])
            self.assertIn("graph-topology", error["error"])

    def test_failed_observer_subcommand_cannot_replay_as_clean(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            observer = next(
                command for command in manifest["commands"]
                if command["name"] == "cold-observer"
            )
            transcript_path = directory / str(observer["transcript"])
            transcript_path.write_text(
                transcript_path.read_text(encoding="utf-8").replace(
                    "__KAST_OBSERVATION_EXIT_CODE__=0\n",
                    "__KAST_OBSERVATION_EXIT_CODE__=127\n",
                    1,
                ),
                encoding="utf-8",
            )
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertEqual(
                ["OBSERVER_EVIDENCE_INCOMPLETE"],
                [finding["code"] for finding in json.loads(result.stdout)["findings"]],
            )

    def test_unexpected_typed_observer_failure_cannot_replay_as_clean(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
            observer = next(
                command for command in manifest["commands"]
                if command["name"] == "cold-observer"
            )
            transcript_path = directory / str(observer["transcript"])
            transcript_path.write_text(
                transcript_path.read_text(encoding="utf-8").replace(
                    "result: missing\n__KAST_OBSERVATION_EXIT_CODE__=0\n",
                    "error: INTERNAL\n__KAST_OBSERVATION_EXIT_CODE__=1\n",
                ),
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertEqual(
                ["OBSERVER_EVIDENCE_INCOMPLETE"],
                [finding["code"] for finding in json.loads(result.stdout)["findings"]],
            )

    def test_non_overlapping_observer_cannot_replay_as_clean(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            observer = next(
                command for command in manifest["commands"]
                if command["name"] == "cold-observer"
            )
            observer["startedAtEpochMillis"] = 120
            observer["finishedAtEpochMillis"] = 130
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertEqual(
                ["OBSERVER_EVIDENCE_INCOMPLETE"],
                [finding["code"] for finding in json.loads(result.stdout)["findings"]],
            )

    def test_observer_must_cover_transition_completion(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            operation = next(
                command for command in manifest["commands"]
                if command["name"] == "cold-up"
            )
            transcript_path = directory / str(operation["transcript"])
            transcript_path.write_text(
                transcript_path.read_text(encoding="utf-8").replace(
                    f"::kast-repro-exit={operation['completionToken']}:0:110",
                    f"::kast-repro-exit={operation['completionToken']}:0:150",
                ),
                encoding="utf-8",
            )
            operation["finishedAtEpochMillis"] = 150
            observer = next(
                command for command in manifest["commands"]
                if command["name"] == "cold-observer"
            )
            observer_transcript = directory / str(observer["transcript"])
            observer_transcript.write_text(
                observer_transcript.read_text(encoding="utf-8").replace(
                    f"::kast-repro-exit={observer['completionToken']}:0:130",
                    f"::kast-repro-exit={observer['completionToken']}:0:170",
                ),
                encoding="utf-8",
            )
            observer["finishedAtEpochMillis"] = 170
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertEqual(
                ["OBSERVER_EVIDENCE_INCOMPLETE"],
                [finding["code"] for finding in json.loads(result.stdout)["findings"]],
            )

    def test_observer_without_a_sample_during_transition_cannot_replay_as_clean(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            observer = next(
                command for command in manifest["commands"]
                if command["name"] == "cold-observer"
            )
            transcript_path = directory / str(observer["transcript"])
            transcript_path.write_text(
                transcript_path.read_text(encoding="utf-8")
                .replace("__KAST_OBSERVATION_EPOCH_MILLIS__=108", "__KAST_OBSERVATION_EPOCH_MILLIS__=125")
                .replace("__KAST_OBSERVATION_EPOCH_MILLIS__=109", "__KAST_OBSERVATION_EPOCH_MILLIS__=126"),
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertEqual(
                ["OBSERVER_EVIDENCE_INCOMPLETE"],
                [finding["code"] for finding in json.loads(result.stdout)["findings"]],
            )

    def test_observer_requires_claim_bearing_sample_during_transition(self) -> None:
        cases = (
            ("cold-observer", "108", "125"),
            ("refresh-observer", "209", "225"),
        )
        for observer_name, original_timestamp, late_timestamp in cases:
            with self.subTest(observer=observer_name), tempfile.TemporaryDirectory() as raw_directory:
                directory = Path(raw_directory)
                self.write_evidence(directory, incident=False)
                manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
                observer = next(
                    command for command in manifest["commands"]
                    if command["name"] == observer_name
                )
                transcript_path = directory / str(observer["transcript"])
                transcript_path.write_text(
                    transcript_path.read_text(encoding="utf-8").replace(
                        f"__KAST_OBSERVATION_EPOCH_MILLIS__={original_timestamp}",
                        f"__KAST_OBSERVATION_EPOCH_MILLIS__={late_timestamp}",
                    ),
                    encoding="utf-8",
                )

                result = self.run_runner(
                    "analyze", "--evidence-dir", str(directory), "--format", "json"
                )

                self.assertEqual(1, result.returncode, result.stderr)
                self.assertEqual(
                    ["OBSERVER_EVIDENCE_INCOMPLETE"],
                    [finding["code"] for finding in json.loads(result.stdout)["findings"]],
                )

    def test_new_descendant_is_rescanned_during_forced_teardown(self) -> None:
        runner = load_runner_module()
        root = Path("/tmp/kast")
        observed = {100: "java --state /tmp/kast/cache"}
        rescans = [
            observed,
            {**observed, 101: "late-child"},
            {101: "late-child"},
            {},
            {},
        ]

        with (
            mock.patch.object(runner, "capsule_processes", side_effect=rescans),
            mock.patch.object(runner.os, "kill") as kill,
            mock.patch.object(runner.time, "sleep"),
        ):
            targeted, remaining = runner.terminate_capsule_processes(root, observed)

        self.assertEqual([100, 101], targeted)
        self.assertEqual([], remaining)
        self.assertIn(mock.call(101, runner.signal.SIGTERM), kill.call_args_list)

    def test_process_that_exits_before_signal_is_not_reported_as_terminated(self) -> None:
        runner = load_runner_module()
        root = Path("/tmp/kast")
        observed = {100: "java --state /tmp/kast/cache"}

        with (
            mock.patch.object(
                runner,
                "capsule_processes",
                side_effect=[observed, {}, {}],
            ),
            mock.patch.object(runner.os, "kill", side_effect=ProcessLookupError),
            mock.patch.object(runner.time, "sleep"),
        ):
            targeted, remaining = runner.terminate_capsule_processes(root, observed)

        self.assertEqual([], targeted)
        self.assertEqual([], remaining)

    def test_log_paths_honors_configured_telemetry_output(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory)
            workspace = root / "workspace"
            workspace.mkdir()
            workspace_data = root / ("a" * 64)
            config = {
                "configPath": str(workspace_data / "config.json"),
                "effective": {
                    "paths": {"cacheDir": str(root / "cache")},
                    "telemetry": {"outputFile": "telemetry/custom-spans.jsonl"},
                },
            }

            telemetry, _ = runner.log_paths(config, workspace)

            self.assertEqual(
                (workspace / "telemetry" / "custom-spans.jsonl").resolve(),
                telemetry,
            )

    def test_telemetry_output_treats_tilde_as_a_workspace_relative_path(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            workspace = Path(raw_directory) / "workspace"
            workspace.mkdir()
            config = {
                "effective": {
                    "telemetry": {"outputFile": "~/telemetry/custom-spans.jsonl"},
                },
            }

            telemetry = runner.telemetry_output_path(config, workspace)

            self.assertEqual(
                (workspace / "~" / "telemetry" / "custom-spans.jsonl").resolve(),
                telemetry,
            )

    def test_safe_ephemeral_cleanup_does_not_depend_on_capture_success(self) -> None:
        runner = load_runner_module()
        capsule = runner.CapsuleContext(
            "EPHEMERAL",
            Path("/private/tmp/kast-capsule-contract"),
            Path("/bundle"),
            {},
            Path("/bootstrap-kastctl"),
            Path("/idea-host"),
        )
        proof = {
            "runtimeStopped": True,
            "runtimeStopSucceeded": True,
            "installationContained": True,
            "stateContained": True,
        }

        self.assertTrue(
            runner.ephemeral_capsule_is_safely_deletable(capsule, proof, [])
        )
        self.assertFalse(
            runner.ephemeral_capsule_is_safely_deletable(
                capsule,
                {**proof, "runtimeStopSucceeded": False},
                [],
            )
        )

    def test_capsule_rejects_configured_telemetry_output_outside_its_root(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory) / "capsule"
            capsule = runner.CapsuleContext(
                "PERSISTENT",
                root,
                root / "bundle",
                {},
                root / "bootstrap-kastctl",
                root / "idea-host",
            )
            for executable in (capsule.kast, capsule.kastctl):
                executable.parent.mkdir(parents=True, exist_ok=True)
                executable.write_text("#!/bin/sh\n", encoding="utf-8")
                executable.chmod(0o755)
            config = {
                "configPath": str(root / "config" / ("a" * 64) / "config.json"),
                "effective": {
                    "paths": {"cacheDir": str(root / "cache")},
                    "telemetry": {"outputFile": "/tmp/external-spans.jsonl"},
                },
            }

            with (
                mock.patch.object(
                    runner,
                    "discover_developer_cli",
                    return_value=capsule.kastctl,
                ),
                mock.patch.object(runner, "read_config", return_value=config),
                self.assertRaisesRegex(runner.ReproError, "escaped its root"),
            ):
                runner.verify_capsule_install(capsule, root / "workspace")

    def test_persistent_capsule_rejects_state_symlinks_before_setup(self) -> None:
        runner = load_runner_module()
        for relative_symlink in (Path("kast-home"), Path("kast-home/releases")):
            with (
                self.subTest(relative_symlink=relative_symlink),
                tempfile.TemporaryDirectory(dir="/private/tmp") as raw_directory,
            ):
                base = Path(raw_directory)
                root = base / "capsule"
                root.mkdir()
                external = base / "external-state"
                external.mkdir()
                symlink = root / relative_symlink
                symlink.parent.mkdir(parents=True, exist_ok=True)
                symlink.symlink_to(external, target_is_directory=True)
                workspace = base / "workspace"
                workspace.mkdir()
                bundle = base / "bundle"
                bundle.mkdir()
                args = runner.argparse.Namespace(
                    capsule_root=root,
                    ephemeral_capsule=False,
                    bundle_source=bundle,
                    idea_host=None,
                )

                with (
                    mock.patch.object(
                        runner,
                        "discover_developer_cli",
                        return_value=base / "bootstrap-kastctl",
                    ),
                    mock.patch.object(
                        runner,
                        "discover_idea_host",
                        return_value=base / "idea-host",
                    ),
                    self.assertRaisesRegex(runner.ReproError, "state .* escaped"),
                ):
                    runner.build_capsule(args, workspace, "kast", dry_run=False)

    def test_ephemeral_capsule_is_removed_when_post_allocation_validation_fails(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            base = Path(raw_directory)
            bundle = base / "bundle"
            bundle.mkdir()
            allocated = Path(
                tempfile.mkdtemp(prefix="kast-capsule-contract-", dir="/private/tmp")
            )
            allocated.rmdir()
            args = runner.argparse.Namespace(
                capsule_root=None,
                ephemeral_capsule=True,
                bundle_source=bundle,
                idea_host=None,
            )

            def allocate(*, prefix: str, dir: str) -> str:
                self.assertEqual("kast-capsule-", prefix)
                self.assertEqual("/private/tmp", dir)
                allocated.mkdir()
                return str(allocated)

            try:
                with (
                    mock.patch.object(
                        runner,
                        "discover_developer_cli",
                        return_value=base / "bootstrap-kastctl",
                    ),
                    mock.patch.object(
                        runner,
                        "discover_idea_host",
                        return_value=base / "idea-host",
                    ),
                    mock.patch.object(runner.tempfile, "mkdtemp", side_effect=allocate),
                    self.assertRaisesRegex(runner.ReproError, "must not overlap"),
                ):
                    runner.build_capsule(args, Path("/private/tmp"), "kast", dry_run=False)

                self.assertFalse(allocated.exists())
            finally:
                if allocated.exists():
                    allocated.rmdir()

    def test_failed_telemetry_restore_stops_runtime_without_restarting(self) -> None:
        runner = load_runner_module()
        failed = runner.CommandEvidence(
            name="restore-enabled",
            argv=["kastctl"],
            startedAtEpochMillis=1,
            finishedAtEpochMillis=2,
            exitCode=1,
            timedOut=False,
            transcript="transcripts/restore-enabled.txt",
            outputBytes=0,
        )
        succeeded = dataclasses.replace(failed, name="restore-detail", exitCode=0)
        stopped = dataclasses.replace(failed, name="restore-runtime-stop", exitCode=0)
        capture = mock.Mock()
        capture.run.side_effect = [failed, succeeded, stopped]
        config = {
            "mutableFields": [
                {"key": "telemetry.enabled", "workspaceOverride": False},
                {"key": "telemetry.detail", "workspaceOverride": False},
            ],
            "effective": {"telemetry": {"enabled": False, "detail": "normal"}},
        }

        errors = runner.restore_config_and_runtime(
            capture,
            Path("/tmp/kastctl"),
            "/tmp/kast",
            Path("/tmp/workspace"),
            config,
            420.0,
        )

        self.assertEqual(1, len(errors))
        self.assertIn("restore-enabled", errors[0])
        self.assertEqual(
            ["restore-enabled", "restore-detail", "restore-runtime-stop"],
            [call.args[0].name for call in capture.run.call_args_list],
        )

    def test_successful_telemetry_restore_restarts_runtime(self) -> None:
        runner = load_runner_module()
        succeeded = runner.CommandEvidence(
            name="succeeded",
            argv=["kastctl"],
            startedAtEpochMillis=1,
            finishedAtEpochMillis=2,
            exitCode=0,
            timedOut=False,
            transcript="transcripts/succeeded.txt",
            outputBytes=0,
        )
        capture = mock.Mock()
        capture.run.return_value = succeeded
        config = {
            "mutableFields": [
                {"key": "telemetry.enabled", "workspaceOverride": False},
                {"key": "telemetry.detail", "workspaceOverride": False},
            ],
            "effective": {"telemetry": {"enabled": False, "detail": "normal"}},
        }

        errors = runner.restore_config_and_runtime(
            capture,
            Path("/tmp/kastctl"),
            "/tmp/kast",
            Path("/tmp/workspace"),
            config,
            420.0,
        )

        self.assertEqual([], errors)
        specs = [call.args[0] for call in capture.run.call_args_list]
        self.assertEqual(
            [
                "restore-enabled",
                "restore-detail",
                "restore-runtime-stop",
                "restore-runtime-start",
            ],
            [spec.name for spec in specs],
        )
        self.assertEqual(
            ("/tmp/kast", "workspace", "ensure"),
            specs[-1].argv,
        )

    def test_live_non_capsule_capture_requires_runtime_restart_authority(self) -> None:
        runner = load_runner_module()

        with self.assertRaisesRegex(runner.ReproError, "--restart-runtime is required"):
            runner.require_runtime_restart_authority(
                restart_requested=False,
                capsule=None,
                dry_run=False,
            )

        runner.require_runtime_restart_authority(
            restart_requested=False,
            capsule=mock.sentinel.capsule,
            dry_run=False,
        )
        runner.require_runtime_restart_authority(
            restart_requested=True,
            capsule=None,
            dry_run=False,
        )
        runner.require_runtime_restart_authority(
            restart_requested=False,
            capsule=None,
            dry_run=True,
        )

    def test_generic_exit_text_does_not_drive_newline_analysis(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
            command = next(item for item in manifest["commands"] if item["name"] == "graph-nodes")
            (directory / command["transcript"]).write_text(
                "source preview unterminated::kast-repro-exit=9\n"
                "result: ok\n"
                f"::kast-repro-exit={command['completionToken']}:0:310\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], json.loads(result.stdout)["findings"])

    def test_graph_budget_uses_transcript_bytes_not_manifest_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            graph_nodes = next(
                command
                for command in manifest["commands"]
                if command["name"] == "graph-nodes"
            )
            transcript_path = directory / str(graph_nodes["transcript"])
            transcript_path.write_text(
                "x" * 30_001
                + "\n"
                + f"::kast-repro-exit={graph_nodes['completionToken']}:0:310\n",
                encoding="utf-8",
            )
            graph_nodes["outputBytes"] = 1
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )

            result = self.run_runner(
                "analyze", "--evidence-dir", str(directory), "--format", "json"
            )

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertEqual(
                ["DEFAULT_OUTPUT_EXCEEDS_BUDGET"],
                [finding["code"] for finding in json.loads(result.stdout)["findings"]],
            )

    def test_failed_cold_start_is_not_a_green_replay(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            cold_up = next(command for command in manifest["commands"] if command["name"] == "cold-up")
            cold_up["exitCode"] = 1
            transcript_path = directory / str(cold_up["transcript"])
            transcript_path.write_text(
                transcript_path.read_text(encoding="utf-8").replace(
                    f"::kast-repro-exit={cold_up['completionToken']}:0:110",
                    f"::kast-repro-exit={cold_up['completionToken']}:1:110",
                ),
                encoding="utf-8",
            )
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

            result = self.run_runner("analyze", "--evidence-dir", str(directory), "--format", "json")

            self.assertEqual(1, result.returncode, result.stderr)
            self.assertEqual(
                ["COLD_START_DID_NOT_CONVERGE"],
                [finding["code"] for finding in json.loads(result.stdout)["findings"]],
            )

    def test_conflict_after_refresh_completion_is_not_attributed_to_refresh(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            self.write_evidence(directory, incident=False)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            observer = next(
                command for command in manifest["commands"] if command["name"] == "refresh-observer"
            )
            observer["startedAtEpochMillis"] = 205
            observer["finishedAtEpochMillis"] = 260
            (directory / observer["transcript"]).write_text(
                "__KAST_SAMPLE__=1\n__KAST_OBSERVATION__=HOME\nready: false\n"
                "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                "__KAST_OBSERVATION_EPOCH_MILLIS__=205\n"
                "__KAST_OBSERVATION__=RESOLVE\nresult: resolved\n"
                "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                "__KAST_OBSERVATION_EPOCH_MILLIS__=206\n"
                "__KAST_SAMPLE__=2\n__KAST_OBSERVATION__=HOME\nready: true\n"
                "__KAST_OBSERVATION_EXIT_CODE__=0\n"
                "__KAST_OBSERVATION_EPOCH_MILLIS__=240\n"
                "__KAST_OBSERVATION__=RESOLVE\nerror: CONFLICT\n"
                "next: Run `kast --help`\n"
                "__KAST_OBSERVATION_EXIT_CODE__=1\n"
                "__KAST_OBSERVATION_EPOCH_MILLIS__=250\n"
                f"::kast-repro-exit={observer['completionToken']}:0:260\n",
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
            self.assertIn("__KAST_OBSERVATION__=HOME", cold_observer["argv"][-1])
            self.assertIn("__KAST_OBSERVATION__=RESOLVE", cold_observer["argv"][-1])
            self.assertEqual(
                2,
                cold_observer["argv"][-1].count("__KAST_OBSERVATION_EPOCH_MILLIS__"),
            )
            self.assertEqual(
                2,
                cold_observer["argv"][-1].count("__KAST_OBSERVATION_EXIT_CODE__"),
            )
            self.assertIn("KAST_REPRO_OBSERVER_STOP", cold_observer["argv"][-1])
            self.assertNotIn("for i in $(seq", cold_observer["argv"][-1])
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

    def test_mutation_plan_probe_rejects_non_top_level_symbol_queries(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            workspace = Path(raw_directory)
            source = workspace / "src" / "Probe.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package example\nclass Probe\n", encoding="utf-8")

            for query in ("example.Widget.render()", "example.Widget.Nested"):
                with self.subTest(query=query):
                    result = self.run_runner(
                        "capture",
                        "--workspace-root",
                        str(workspace),
                        "--file",
                        "src/Probe.kt",
                        "--symbol",
                        query,
                        "--exercise-plans",
                        "--dry-run",
                    )

                    self.assertEqual(2, result.returncode)
                    self.assertIn(
                        "--exercise-plans requires a top-level declaration from the --file package",
                        result.stderr,
                    )

    def test_mutation_plan_chooses_an_absent_probe_path(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            workspace = Path(raw_directory)
            source = workspace / "src" / "Probe.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package example\nclass Probe\n", encoding="utf-8")
            occupied = source.parent / "KastCliReproProbe.kt"
            occupied.write_text("package example\nobject Existing\n", encoding="utf-8")

            result = self.run_runner(
                "capture",
                "--workspace-root",
                str(workspace),
                "--file",
                "src/Probe.kt",
                "--symbol",
                "example.Probe",
                "--exercise-plans",
                "--dry-run",
            )

            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads(result.stdout)
            add_file = next(
                command for command in plan["commands"]
                if command["name"] == "change-plan-add-file"
            )
            probe_path = workspace / add_file["argv"][-1]
            self.assertNotEqual(occupied, probe_path)
            self.assertFalse(probe_path.exists())

    def test_capsule_process_ownership_requires_a_path_boundary(self) -> None:
        runner = load_runner_module()
        root = Path("/tmp/kast")

        self.assertTrue(runner.command_mentions_capsule_root("java --state /tmp/kast/cache", root))
        self.assertFalse(
            runner.command_mentions_capsule_root("java --state /tmp/kast-backup/cache", root)
        )

    def test_owned_descendant_survives_parent_exit_with_pid_reuse_guard(self) -> None:
        runner = load_runner_module()
        root = Path("/tmp/kast")
        initial = {
            100: (1, "java --state /tmp/kast/cache"),
            101: (100, "capsule-worker"),
        }
        after_parent_exit = {101: (1, "capsule-worker")}
        reused_pid = {101: (1, "unrelated-worker")}

        with (
            mock.patch.object(runner, "process_ancestry", return_value=set()),
            mock.patch.object(runner, "process_table", return_value=initial),
        ):
            owned = runner.capsule_processes(root)

        self.assertEqual(
            {100: "java --state /tmp/kast/cache", 101: "capsule-worker"},
            owned,
        )
        with (
            mock.patch.object(runner, "process_ancestry", return_value=set()),
            mock.patch.object(runner, "process_table", return_value=after_parent_exit),
        ):
            self.assertEqual({101: "capsule-worker"}, runner.capsule_processes(root, owned))
        with (
            mock.patch.object(runner, "process_ancestry", return_value=set()),
            mock.patch.object(runner, "process_table", return_value=reused_pid),
        ):
            self.assertEqual({}, runner.capsule_processes(root, owned))

    def test_nonfinite_capture_intervals_are_rejected(self) -> None:
        runner = load_runner_module()
        for field, value in (
            ("sample_interval", float("nan")),
            ("sample_interval", float("inf")),
            ("transition_timeout", float("nan")),
            ("transition_timeout", float("inf")),
        ):
            with self.subTest(field=field, value=value):
                arguments = runner.argparse.Namespace(
                    samples=1,
                    sample_interval=0.1,
                    transition_timeout=1.0,
                )
                setattr(arguments, field, value)
                with self.assertRaises(runner.ReproError):
                    runner.validate_numeric_arguments(arguments)

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

    def test_existing_session_is_rejected_before_ephemeral_capsule_allocation(self) -> None:
        runner = load_runner_module()
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory)
            workspace = root / "workspace"
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
                    "--session-name",
                    "occupied",
                    "--ephemeral-capsule",
                ]
            )

            with (
                mock.patch.object(runner.shutil, "which", return_value="/usr/bin/true"),
                mock.patch.object(
                    runner.subprocess,
                    "run",
                    return_value=subprocess.CompletedProcess([], 0, "", ""),
                ),
                mock.patch.object(runner, "build_capsule", return_value=None) as build_capsule,
                self.assertRaisesRegex(runner.ReproError, "tmux session already exists"),
            ):
                runner.capture(args)

            build_capsule.assert_not_called()

    def test_missing_capture_paths_are_structured_invalid_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            root = Path(raw_directory)
            workspace = root / "workspace"
            workspace.mkdir()
            cases = (
                (root / "missing-workspace", "src/Probe.kt"),
                (workspace, "src/Missing.kt"),
            )
            for missing_workspace, file_name in cases:
                with self.subTest(workspace=missing_workspace, file=file_name):
                    result = self.run_runner(
                        "capture",
                        "--workspace-root",
                        str(missing_workspace),
                        "--file",
                        file_name,
                        "--symbol",
                        "example.Probe",
                        "--dry-run",
                    )

                    self.assertEqual(2, result.returncode)
                    self.assertEqual("", result.stdout)
                    error = json.loads(result.stderr)
                    self.assertEqual("INVALID_EVIDENCE", error["status"])
                    self.assertNotIn("Traceback", result.stderr)

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
