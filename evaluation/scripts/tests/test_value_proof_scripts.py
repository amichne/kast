#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1]
VALUE_PROOF_DIR = SCRIPT_DIR.parent
TEST_WORKSPACE = VALUE_PROOF_DIR / ".test-workspace"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from dispatch_runs import DispatchOptions, dispatch_iteration
from parse_tool_calls import parse_run_dir
from run_value_proof import scaffold_workspace


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


class ValueProofScriptTests(unittest.TestCase):
    def setUp(self) -> None:
        shutil.rmtree(TEST_WORKSPACE, ignore_errors=True)
        TEST_WORKSPACE.mkdir(parents=True)

    def tearDown(self) -> None:
        shutil.rmtree(TEST_WORKSPACE, ignore_errors=True)

    def test_scaffold_writes_manifest_and_chain_metadata(self) -> None:
        catalog_path = TEST_WORKSPACE / "catalog.json"
        write_json(
            catalog_path,
            {
                "skill_name": "kast-value-proof",
                "version": 1,
                "cases": [
                    {
                        "id": "vp-disambiguate-member",
                        "title": "Disambiguate member",
                        "prompt": "Find the right member.",
                        "expectations": ["Uses semantic resolution"],
                    },
                    {
                        "id": "vp-multi-file-rename",
                        "title": "Safe rename",
                        "prompt": "Rename safely.",
                        "expectations": ["Uses kast_rename"],
                        "chain_id": "safe-mutations-chain",
                    },
                ],
            },
        )

        iteration_dir = scaffold_workspace(
            catalog_path=catalog_path,
            workspace_dir=TEST_WORKSPACE / "workspace",
            runs_per_config=1,
            configs=["with_skill"],
            iteration="iteration-001",
            aggregate=False,
        )

        manifest = json.loads((iteration_dir / "manifest.json").read_text())
        self.assertEqual(
            {
                "vp-disambiguate-member": {
                    "dir": "eval-vp-disambiguate-member",
                    "chain_id": None,
                },
                "vp-multi-file-rename": {
                    "dir": "eval-vp-multi-file-rename",
                    "chain_id": "safe-mutations-chain",
                },
            },
            manifest["evals"],
        )
        metadata = json.loads(
            (iteration_dir / "eval-vp-multi-file-rename" / "eval_metadata.json").read_text()
        )
        self.assertEqual("safe-mutations-chain", metadata["chain_id"])

    def test_dispatch_uses_manifest_retries_empty_transcript_and_records_timing(self) -> None:
        iteration_dir = self.create_iteration_fixture()
        runner = TEST_WORKSPACE / "retry_runner.py"
        runner.write_text(
            "\n".join(
                [
                    "import pathlib, sys",
                    "transcript = pathlib.Path(sys.argv[1])",
                    "attempt = int(sys.argv[2])",
                    "transcript.parent.mkdir(parents=True, exist_ok=True)",
                    "transcript.write_text('' if attempt == 1 else 'completed\\n')",
                    "",
                ]
            )
        )
        command = f"{sys.executable} {runner} {{transcript}} {{attempt}}"

        summary = dispatch_iteration(
            iteration_dir,
            DispatchOptions(command_template=command, concurrency=2, max_retries=1),
        )

        self.assertEqual(1, summary.succeeded)
        self.assertEqual(0, summary.failed)
        self.assertEqual(1, summary.retried)
        timing = json.loads(
            (
                iteration_dir
                / "eval-vp-disambiguate-member"
                / "with_skill"
                / "run-1"
                / "timing.json"
            ).read_text()
        )
        self.assertEqual("succeeded", timing["status"])
        self.assertEqual(2, timing["attempts"])
        self.assertGreaterEqual(timing["executor_duration_seconds"], 0.0)
        self.assertTrue(timing["start_ts"].endswith("Z"))
        self.assertTrue(timing["end_ts"].endswith("Z"))

    def test_dispatch_serializes_runs_that_share_chain_id(self) -> None:
        iteration_dir = self.create_iteration_fixture(
            cases={
                "vp-multi-file-rename": "safe-mutations-chain",
                "vp-edit-and-validate": "safe-mutations-chain",
            }
        )
        lock_file = TEST_WORKSPACE / "chain.lock"
        runner = TEST_WORKSPACE / "chain_runner.py"
        runner.write_text(
            "\n".join(
                [
                    "import pathlib, sys, time",
                    f"lock_file = pathlib.Path({str(lock_file)!r})",
                    "transcript = pathlib.Path(sys.argv[1])",
                    "if lock_file.exists():",
                    "    sys.exit(17)",
                    "lock_file.write_text('locked')",
                    "time.sleep(0.05)",
                    "transcript.parent.mkdir(parents=True, exist_ok=True)",
                    "transcript.write_text('completed\\n')",
                    "lock_file.unlink()",
                    "",
                ]
            )
        )
        command = f"{sys.executable} {runner} {{transcript}}"

        summary = dispatch_iteration(
            iteration_dir,
            DispatchOptions(command_template=command, concurrency=2, max_retries=0),
        )

        self.assertEqual(2, summary.succeeded)
        self.assertEqual(0, summary.failed)

    def test_dispatch_discovers_runs_without_manifest(self) -> None:
        iteration_dir = self.create_iteration_fixture(write_manifest=False)
        runner = TEST_WORKSPACE / "success_runner.py"
        runner.write_text(
            "\n".join(
                [
                    "import pathlib, sys",
                    "transcript = pathlib.Path(sys.argv[1])",
                    "transcript.parent.mkdir(parents=True, exist_ok=True)",
                    "transcript.write_text('completed\\n')",
                    "",
                ]
            )
        )

        summary = dispatch_iteration(
            iteration_dir,
            DispatchOptions(command_template=f"{sys.executable} {runner} {{transcript}}"),
        )

        self.assertEqual(1, summary.succeeded)
        self.assertEqual(0, summary.failed)

    def test_dispatch_cli_reports_failure_for_missing_transcript(self) -> None:
        iteration_dir = self.create_iteration_fixture()
        command = f"{sys.executable} -c 'pass'"

        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT_DIR / "dispatch_runs.py"),
                str(iteration_dir),
                "--command-template",
                command,
                "--max-retries",
                "0",
            ],
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("0 succeeded, 1 failed, 0 retried", result.stdout)

    def test_parse_tool_calls_reads_copilot_json_output(self) -> None:
        run_dir = TEST_WORKSPACE / "run"
        outputs = run_dir / "outputs"
        outputs.mkdir(parents=True)
        transcript = [
            {
                "type": "assistant.message",
                "data": {
                    "content": "I will resolve the symbol.",
                    "toolRequests": [
                        {
                            "name": "kast_resolve",
                            "arguments": {"symbol": "Demo.name"},
                        }
                    ],
                },
            },
            {
                "type": "tool.completed",
                "data": {
                    "toolName": "kast_references",
                    "result": {"ok": True},
                },
            },
        ]
        (outputs / "transcript.md").write_text("\n".join(json.dumps(row) for row in transcript) + "\n")

        summary = parse_run_dir(run_dir)

        self.assertEqual(2, summary["total_tool_calls"])
        self.assertEqual(2, summary["kast_calls"])
        self.assertEqual(
            {"kast_resolve": 1, "kast_references": 1},
            summary["tool_calls"],
        )

    def create_iteration_fixture(
        self,
        *,
        cases: dict[str, str | None] | None = None,
        write_manifest: bool = True,
    ) -> Path:
        selected_cases = cases or {"vp-disambiguate-member": None}
        iteration_dir = TEST_WORKSPACE / "iteration-001"
        evals = {}
        for case_id, chain_id in selected_cases.items():
            eval_dir = iteration_dir / f"eval-{case_id}"
            run_dir = eval_dir / "with_skill" / "run-1"
            (run_dir / "outputs").mkdir(parents=True, exist_ok=True)
            (run_dir / "run_instructions.md").write_text(f"# {case_id}\n")
            write_json(
                eval_dir / "eval_metadata.json",
                {
                    "eval_id": case_id,
                    "eval_name": case_id,
                    "prompt": f"Prompt for {case_id}",
                    "assertions": [],
                    "chain_id": chain_id,
                },
            )
            evals[case_id] = {"dir": f"eval-{case_id}", "chain_id": chain_id}
        if write_manifest:
            write_json(iteration_dir / "manifest.json", {"evals": evals})
        return iteration_dir


if __name__ == "__main__":
    unittest.main()
