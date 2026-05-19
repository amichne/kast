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
from finalize_grading import finalize
from parse_tool_calls import parse_run_dir
from run_value_proof import scaffold_workspace
from script_grader import grade
from generate_executive_summary import generate_summary_documents
from value_proof_aggregate import _integrity, _invalid_reason, aggregate, write_outputs


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

    def test_scaffold_writes_redesign_artifact_placeholders(self) -> None:
        catalog_path = TEST_WORKSPACE / "catalog.json"
        write_json(
            catalog_path,
            {
                "skill_name": "kast-value-proof",
                "version": 1,
                "cases": [
                    {
                        "id": "vp-demo",
                        "title": "Demo case",
                        "prompt": "Inspect the demo.",
                        "expectations": [],
                    }
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

        run_dir = iteration_dir / "eval-vp-demo" / "with_skill" / "run-1"
        self.assertTrue((run_dir / "inputs.json").exists())
        self.assertTrue((run_dir / "sdk-events.jsonl").exists())
        self.assertTrue((run_dir / "otel.jsonl").exists())
        self.assertTrue((run_dir / "final-answer.md").exists())
        self.assertTrue((run_dir / "mechanical.json").exists())
        self.assertTrue((run_dir / "llm-grade.json").exists())
        self.assertTrue((run_dir / "llm-grade-input.json").exists())
        self.assertTrue((run_dir / "grading.json").exists())

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

    def test_parse_tool_calls_dedupes_sdk_tool_call_events(self) -> None:
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
                            "toolCallId": "call_1",
                            "name": "kast_resolve",
                            "arguments": {"symbol": "Demo.name"},
                        }
                    ],
                },
            },
            {
                "type": "tool.execution_start",
                "data": {
                    "toolCallId": "call_1",
                    "toolName": "kast_resolve",
                    "arguments": {"symbol": "Demo.name"},
                },
            },
            {
                "type": "tool.execution_complete",
                "data": {
                    "toolCallId": "call_1",
                    "toolName": "kast_resolve",
                    "result": {"ok": True},
                },
            },
        ]
        (outputs / "transcript.md").write_text("\n".join(json.dumps(row) for row in transcript) + "\n")

        summary = parse_run_dir(run_dir)

        self.assertEqual(1, summary["total_tool_calls"])
        self.assertEqual(1, summary["kast_calls"])
        self.assertEqual({"kast_resolve": 1}, summary["tool_calls"])

    def test_script_grader_uses_assistant_text_for_sdk_jsonl_outcomes(self) -> None:
        workspace_root = TEST_WORKSPACE / "workspace-root"
        source = workspace_root / "src" / "Demo.kt"
        source.parent.mkdir(parents=True)
        source.write_text("class Demo\nfun use() = Demo()\n")

        iteration_dir = TEST_WORKSPACE / "iteration-001"
        eval_dir = iteration_dir / "eval-vp-demo"
        run_dir = eval_dir / "with_skill" / "run-1"
        outputs = run_dir / "outputs"
        outputs.mkdir(parents=True)
        write_json(
            eval_dir / "eval_metadata.json",
            {
                "eval_id": "vp-demo",
                "eval_name": "vp-demo",
                "assertions": [
                    {
                        "id": "om-recall",
                        "text": "Reports expected file",
                        "kind": "outcome",
                        "oracle": "DISAMBIGUATE_MEMBER.expected.expectedFiles",
                        "graded_by": "script",
                    },
                    {
                        "id": "om-min-sites",
                        "text": "Reports enough citations",
                        "kind": "outcome",
                        "oracle": "DISAMBIGUATE_MEMBER.expected.minimumUsageSites",
                        "graded_by": "script",
                    },
                    {
                        "id": "om-citations-resolve",
                        "text": "Citations resolve",
                        "kind": "outcome",
                        "graded_by": "script",
                    },
                ],
            },
        )
        bindings_path = TEST_WORKSPACE / "bindings.json"
        write_json(
            bindings_path,
            {
                "workspace_root": str(workspace_root),
                "slots": {
                    "DISAMBIGUATE_MEMBER": {
                        "expected": {
                            "expectedFiles": ["src/Demo.kt"],
                            "minimumUsageSites": 1,
                        }
                    }
                },
            },
        )
        transcript = [
            {
                "type": "assistant.message",
                "data": {
                    "content": "The usage is src/Demo.kt — line 2.",
                    "encryptedContent": "/not/a/real/file.kt:999",
                },
            },
            {
                "type": "assistant.reasoning",
                "data": {"content": "/also/not/real.kt:777"},
            },
        ]
        (outputs / "transcript.md").write_text("\n".join(json.dumps(row) for row in transcript) + "\n")

        result = grade(run_dir, bindings_path)

        self.assertEqual(3, result["summary"]["passed"])
        self.assertEqual(0, result["summary"]["failed"])
        self.assertLess(
            result["execution_metrics"]["output_chars"],
            result["execution_metrics"]["transcript_chars"],
        )

    def test_finalize_merges_mechanical_and_llm_surfaces(self) -> None:
        iteration_dir = TEST_WORKSPACE / "iteration-001"
        eval_dir = iteration_dir / "eval-vp-demo"
        run_dir = eval_dir / "with_skill" / "run-1"
        (run_dir / "outputs").mkdir(parents=True)
        write_json(
            eval_dir / "eval_metadata.json",
            {
                "eval_id": "vp-demo",
                "eval_name": "vp-demo",
                "assertions": [
                    {
                        "id": "script-outcome",
                        "text": "Mechanical outcome",
                        "kind": "outcome",
                        "dimension": "accuracy",
                        "applicability": "both",
                        "graded_by": "script",
                    },
                    {
                        "id": "llm-outcome",
                        "text": "LLM outcome",
                        "kind": "outcome",
                        "dimension": "task_completion",
                        "applicability": "both",
                        "graded_by": "llm",
                    },
                ],
            },
        )
        write_json(
            run_dir / "mechanical.json",
            {
                "schema_version": 1,
                "status": "graded",
                "expectations": [
                    {
                        "id": "script-outcome",
                        "text": "Mechanical outcome",
                        "passed": True,
                        "evidence": "mechanical evidence",
                        "kind": "outcome",
                        "dimension": "accuracy",
                        "applicability": "both",
                        "graded_by": "script",
                    }
                ],
                "summary": {
                    "passed": 1,
                    "failed": 0,
                    "total": 1,
                    "pass_rate": 1.0,
                    "outcome_passed": 1,
                    "outcome_total": 1,
                    "outcome_pass_rate": 1.0,
                    "process_pass_rate": 0.0,
                    "skipped": 0,
                },
                "execution_metrics": {
                    "tool_calls": {"kast_resolve": 1},
                    "tool_call_log": "outputs/tool_calls.jsonl",
                    "total_tool_calls": 1,
                    "total_steps": 1,
                    "errors_encountered": 0,
                    "output_chars": 10,
                    "transcript_chars": 10,
                    "kast_calls": 1,
                    "grep_or_find_calls": 0,
                },
                "timing": {
                    "executor_duration_seconds": 1.0,
                    "grader_duration_seconds": 0.0,
                    "total_duration_seconds": 1.0,
                    "executor_duration_source": "dispatcher",
                },
                "integrity": {
                    "contradictions": [],
                    "baseline_isolation_violation": False,
                    "attempts": 1,
                    "flaky": False,
                },
            },
        )
        write_json(
            run_dir / "llm-grade.json",
            {
                "schema_version": 1,
                "status": "graded",
                "expectations": [
                    {
                        "id": "llm-outcome",
                        "text": "LLM outcome",
                        "passed": True,
                        "evidence": "llm evidence",
                        "kind": "outcome",
                        "dimension": "task_completion",
                        "applicability": "both",
                        "graded_by": "llm",
                    }
                ],
                "summary": {
                    "passed": 1,
                    "failed": 0,
                    "total": 1,
                    "pass_rate": 1.0,
                    "outcome_passed": 1,
                    "outcome_total": 1,
                    "outcome_pass_rate": 1.0,
                    "process_pass_rate": 0.0,
                    "skipped": 0,
                },
            },
        )
        write_json(
            run_dir / "timing.json",
            {
                "executor_duration_seconds": 1.0,
                "grader_duration_seconds": 0.25,
                "attempts": 1,
            },
        )
        (run_dir / "outputs" / "tool_calls.jsonl").write_text("")
        (run_dir / "outputs" / "transcript.md").write_text("placeholder\n")

        finalized = finalize(run_dir)

        self.assertIn("mechanical", finalized)
        self.assertIn("llm_graded", finalized)
        self.assertIn("combined", finalized)
        self.assertEqual(2, len(finalized["combined"]["expectations"]))
        self.assertEqual(2, finalized["summary"]["passed"])

    def test_finalize_prefers_run_local_post_state_over_parent_workspace_state(self) -> None:
        _, run_dir = self.create_finalized_run_fixture()
        mechanical = json.loads((run_dir / "mechanical.json").read_text())
        mechanical["integrity"] = {
            **mechanical["integrity"],
            "git_sha_post": "run-sha",
            "workspace_dirty_post": False,
        }
        mechanical["repo_state"] = {
            "post_run": {
                "sha": "run-sha",
                "dirty": False,
                "diff_name_status": [],
                "touched_files": [],
                "patch_hash": None,
            }
        }
        write_json(run_dir / "mechanical.json", mechanical)
        dirty_workspace = TEST_WORKSPACE / "dirty-parent-workspace"
        dirty_workspace.mkdir()
        subprocess.run(["git", "init"], cwd=dirty_workspace, check=True, capture_output=True)
        (dirty_workspace / "untracked.txt").write_text("dirty\n")

        finalized = finalize(run_dir, workspace_root=dirty_workspace)

        self.assertEqual("run-sha", finalized["integrity"]["git_sha_post"])
        self.assertFalse(finalized["integrity"]["workspace_dirty_post"])

    def test_finalize_allows_successful_harness_exit_code_evidence(self) -> None:
        iteration_dir = TEST_WORKSPACE / "iteration-001"
        eval_dir = iteration_dir / "eval-vp-demo"
        run_dir = eval_dir / "with_skill" / "run-1"
        (run_dir / "outputs").mkdir(parents=True)
        write_json(
            eval_dir / "eval_metadata.json",
            {
                "eval_id": "vp-demo",
                "eval_name": "vp-demo",
                "assertions": [
                    {
                        "id": "compile-probe",
                        "text": "Post-edit compile status is reported",
                        "kind": "outcome",
                        "graded_by": "script",
                    }
                ],
            },
        )
        write_json(
            run_dir / "mechanical.json",
            {
                "status": "graded",
                "expectations": [
                    {
                        "id": "compile-probe",
                        "text": "Post-edit compile status is reported",
                        "passed": True,
                        "evidence": "Harness probe exit code = 0.",
                        "kind": "outcome",
                        "graded_by": "script",
                    }
                ],
            },
        )
        write_json(run_dir / "llm-grade.json", {"expectations": []})
        write_json(run_dir / "timing.json", {"attempts": 1})
        (run_dir / "outputs" / "tool_calls.jsonl").write_text("")
        (run_dir / "outputs" / "transcript.md").write_text("compile probe passed\n")

        finalized = finalize(run_dir)

        self.assertEqual([], finalized["integrity"]["contradictions"])
        self.assertEqual(1, finalized["summary"]["passed"])

    def test_finalize_flags_passed_zero_count_evidence_as_contradiction(self) -> None:
        iteration_dir = TEST_WORKSPACE / "iteration-001"
        eval_dir = iteration_dir / "eval-vp-demo"
        run_dir = eval_dir / "with_skill" / "run-1"
        (run_dir / "outputs").mkdir(parents=True)
        write_json(
            eval_dir / "eval_metadata.json",
            {
                "eval_id": "vp-demo",
                "eval_name": "vp-demo",
                "assertions": [
                    {
                        "id": "citations",
                        "text": "Reports file citations",
                        "kind": "outcome",
                        "graded_by": "script",
                    }
                ],
            },
        )
        write_json(
            run_dir / "mechanical.json",
            {
                "status": "graded",
                "expectations": [
                    {
                        "id": "citations",
                        "text": "Reports file citations",
                        "passed": True,
                        "evidence": "References found = 0.",
                        "kind": "outcome",
                        "graded_by": "script",
                    }
                ],
            },
        )
        write_json(run_dir / "llm-grade.json", {"expectations": []})
        write_json(run_dir / "timing.json", {"attempts": 1})
        (run_dir / "outputs" / "tool_calls.jsonl").write_text("")
        (run_dir / "outputs" / "transcript.md").write_text("no citations\n")

        finalized = finalize(run_dir)

        self.assertEqual(1, len(finalized["integrity"]["contradictions"]))
        self.assertIn("References found = 0", finalized["integrity"]["contradictions"][0])

    def test_script_grader_uses_harness_probe_for_compile_expectations(self) -> None:
        worktree = TEST_WORKSPACE / "worktree"
        scripts_dir = worktree / "scripts"
        scripts_dir.mkdir(parents=True)
        (scripts_dir / "compile.sh").write_text("#!/usr/bin/env bash\nexit 0\n")
        subprocess.run(["chmod", "+x", str(scripts_dir / "compile.sh")], check=True, capture_output=True)

        iteration_dir = TEST_WORKSPACE / "iteration-001"
        eval_dir = iteration_dir / "eval-vp-demo"
        run_dir = eval_dir / "with_skill" / "run-1"
        outputs = run_dir / "outputs"
        outputs.mkdir(parents=True)
        write_json(
            eval_dir / "eval_metadata.json",
            {
                "eval_id": "vp-demo",
                "eval_name": "vp-demo",
                "assertions": [
                    {
                        "id": "or-compiles",
                        "text": "Compiles cleanly",
                        "kind": "outcome",
                        "graded_by": "script",
                        "oracle": "RENAME_TARGET.expected.compileCommand",
                    }
                ],
            },
        )
        bindings_path = TEST_WORKSPACE / "bindings.json"
        write_json(
            bindings_path,
            {
                "workspace_root": str(TEST_WORKSPACE),
                "slots": {
                    "RENAME_TARGET": {
                        "expected": {
                            "compileCommand": "bash scripts/compile.sh",
                        }
                    }
                },
            },
        )
        write_json(
            run_dir / "mechanical.json",
            {
                "repo_state": {
                    "post_run": {
                        "worktree_path": str(worktree),
                    }
                },
                "build_test_iterations": {
                    "commands": [],
                    "total_invocations": 0,
                },
            },
        )
        (outputs / "transcript.md").write_text("no compile prose\n")

        result = grade(run_dir, bindings_path)

        self.assertTrue(result["expectations"][0]["passed"])
        self.assertEqual("passed", result["harness_validation"]["compile_probe"]["final_status"])

    def test_script_grader_uses_touched_files_for_mutation_oracles(self) -> None:
        iteration_dir = TEST_WORKSPACE / "iteration-001"
        eval_dir = iteration_dir / "eval-vp-demo"
        run_dir = eval_dir / "with_skill" / "run-1"
        outputs = run_dir / "outputs"
        outputs.mkdir(parents=True)
        write_json(
            eval_dir / "eval_metadata.json",
            {
                "eval_id": "vp-demo",
                "eval_name": "vp-demo",
                "assertions": [
                    {
                        "id": "or-files-touched",
                        "text": "Touches expected files",
                        "kind": "outcome",
                        "graded_by": "script",
                        "oracle": "RENAME_TARGET.expected.affectedFiles",
                    },
                    {
                        "id": "or-files-extra",
                        "text": "Avoids extra files",
                        "kind": "outcome",
                        "graded_by": "script",
                        "oracle": "RENAME_TARGET.expected.affectedFiles",
                    },
                ],
            },
        )
        bindings_path = TEST_WORKSPACE / "bindings.json"
        write_json(
            bindings_path,
            {
                "workspace_root": str(TEST_WORKSPACE),
                "slots": {
                    "RENAME_TARGET": {
                        "expected": {
                            "affectedFiles": ["src/Demo.kt"],
                        }
                    }
                },
            },
        )
        write_json(
            run_dir / "mechanical.json",
            {
                "repo_state": {
                    "post_run": {
                        "touched_files": ["src/Demo.kt"],
                    }
                }
            },
        )
        (outputs / "transcript.md").write_text("mutation done\n")

        result = grade(run_dir, bindings_path)

        self.assertTrue(all(expectation["passed"] for expectation in result["expectations"]))

    def test_mock_backend_errors_invalidate_aggregate_runs(self) -> None:
        integrity = _integrity(
            {
                "integrity": {
                    "mock_backend_error_count": 1,
                    "mock_backend_error_samples": ["No mock payload matched symbol/resolve."],
                }
            },
            "with_skill",
        )

        self.assertEqual(1, integrity["mock_backend_error_count"])
        self.assertEqual("mock_backend_error", _invalid_reason([], integrity))

    def test_dirty_post_run_worktree_invalidates_aggregate_runs(self) -> None:
        integrity = _integrity(
            {
                "integrity": {
                    "workspace_dirty_post": True,
                }
            },
            "with_skill",
        )

        self.assertTrue(integrity["workspace_dirty_post"])
        self.assertEqual("workspace_dirty_post", _invalid_reason([], integrity))

    def test_failed_timing_invalidates_aggregate_run_as_executor_failure(self) -> None:
        iteration_dir, run_dir = self.create_finalized_run_fixture()
        write_json(
            run_dir / "timing.json",
            {
                "status": "failed",
                "attempts": 1,
                "last_exit_code": 1,
                "message": "exit=1; transcript=non-empty; stderr=No space left on device",
            },
        )

        finalized = finalize(run_dir)
        benchmark = aggregate(
            iteration_dir,
            skill_name="kast-value-proof",
            bindings_path=None,
            catalog_path=None,
        )
        run = benchmark["runs"][0]

        self.assertEqual("failed", finalized["integrity"]["executor_status"])
        self.assertEqual("executor_failed", run["invalid_reason"])
        self.assertEqual("failed", run["integrity"]["executor_status"])
        self.assertEqual(1, run["integrity"]["executor_exit_code"])
        self.assertIn("No space left on device", run["integrity"]["executor_message"])
        self.assertTrue(run["integrity"]["transcript_present"])

    def test_empty_transcript_invalidates_aggregate_and_blocks_empty_output_passes(self) -> None:
        iteration_dir, run_dir = self.create_finalized_run_fixture()
        (run_dir / "outputs" / "transcript.md").write_text("")

        finalize(run_dir)
        benchmark = aggregate(
            iteration_dir,
            skill_name="kast-value-proof",
            bindings_path=None,
            catalog_path=None,
        )

        self.assertEqual("empty_transcript", benchmark["runs"][0]["invalid_reason"])

        grading_dir = TEST_WORKSPACE / "empty-output-grader" / "iteration-001" / "eval-vp-demo"
        grading_run = grading_dir / "with_skill" / "run-1"
        (grading_run / "outputs").mkdir(parents=True)
        write_json(
            grading_dir / "eval_metadata.json",
            {
                "eval_id": "vp-demo",
                "eval_name": "vp-demo",
                "assertions": [
                    {
                        "id": "om-precision",
                        "text": "No decoy usage is reported",
                        "kind": "outcome",
                        "graded_by": "script",
                        "oracle": "DISAMBIGUATE_MEMBER.expected.decoyFiles",
                    },
                    {
                        "id": "or-imports-updated",
                        "text": "Imports are updated",
                        "kind": "outcome",
                        "graded_by": "script",
                    },
                    {
                        "id": "pm-uses-resolve",
                        "text": "Uses semantic resolve",
                        "kind": "process",
                        "applicability": "with_skill_only",
                        "graded_by": "script",
                    },
                ],
            },
        )
        bindings_path = TEST_WORKSPACE / "empty-output-bindings.json"
        write_json(
            bindings_path,
            {
                "workspace_root": str(TEST_WORKSPACE),
                "slots": {"DISAMBIGUATE_MEMBER": {"expected": {"decoyFiles": ["src/Decoy.kt"]}}},
            },
        )
        write_json(
            grading_run / "outputs" / "tool_calls.jsonl",
            {"tool": "kast_resolve", "source": "sdk"},
        )
        (grading_run / "outputs" / "transcript.md").write_text("")

        result = grade(grading_run, bindings_path)

        self.assertEqual(0, result["summary"]["passed"])
        self.assertEqual(3, result["summary"]["failed"])
        self.assertTrue(
            all("No assistant-visible output" in entry["evidence"] for entry in result["expectations"])
        )

    def test_sdk_hook_or_session_errors_invalidate_aggregate_run(self) -> None:
        iteration_dir, run_dir = self.create_finalized_run_fixture()
        (run_dir / "sdk-events.jsonl").write_text(
            "\n".join(
                [
                    json.dumps(
                        {
                            "type": "hook.end",
                            "data": {
                                "hookType": "sessionStart",
                                "success": False,
                                "error": {"message": "unsupported hook output"},
                            },
                        }
                    ),
                    json.dumps(
                        {
                            "type": "session.error",
                            "data": {"message": "tool registration failed"},
                        }
                    ),
                ]
            )
            + "\n"
        )

        finalize(run_dir)
        benchmark = aggregate(
            iteration_dir,
            skill_name="kast-value-proof",
            bindings_path=None,
            catalog_path=None,
        )
        run = benchmark["runs"][0]

        self.assertEqual("hook_error", run["invalid_reason"])
        self.assertEqual(1, run["integrity"]["hook_error_count"])
        self.assertEqual(1, run["integrity"]["session_error_count"])

    def test_token_metrics_are_aggregated_and_rendered_in_markdown_summaries(self) -> None:
        iteration_dir, with_run = self.create_finalized_run_fixture(configuration="with_skill")
        _, without_run = self.create_finalized_run_fixture(
            iteration_dir=iteration_dir,
            configuration="without_skill",
        )
        for run_dir, input_tokens, output_tokens, cache_read_tokens in (
            (with_run, 100, 40, 10),
            (without_run, 75, 25, 0),
        ):
            mechanical = json.loads((run_dir / "mechanical.json").read_text())
            mechanical["tokens"] = {
                "input_tokens": {"value": input_tokens, "source": "assistant.usage"},
                "output_tokens": {"value": output_tokens, "source": "assistant.usage"},
                "cache_read_tokens": {"value": cache_read_tokens, "source": "assistant.usage"},
                "total_tokens": {
                    "value": input_tokens + output_tokens + cache_read_tokens,
                    "source": "derived_from_assistant.usage",
                },
            }
            write_json(run_dir / "mechanical.json", mechanical)
            finalize(run_dir)

        bindings_path = iteration_dir / "bindings.json"
        write_json(bindings_path, {"target_repo": "kast", "workspace_root": str(TEST_WORKSPACE)})
        benchmark = aggregate(
            iteration_dir,
            skill_name="kast-value-proof",
            bindings_path=bindings_path,
            catalog_path=None,
        )
        write_outputs(iteration_dir, benchmark)
        generate_summary_documents(
            benchmark_path=iteration_dir / "benchmark.json",
            bindings_path=bindings_path,
            output_path=iteration_dir / "executive-summary.md",
        )

        with_skill = next(run for run in benchmark["runs"] if run["configuration"] == "with_skill")
        self.assertEqual(100, with_skill["efficiency"]["input_tokens"])
        self.assertEqual(40, with_skill["efficiency"]["output_tokens"])
        self.assertEqual(10, with_skill["efficiency"]["cache_read_tokens"])
        self.assertEqual(150, with_skill["efficiency"]["total_tokens"])
        self.assertEqual(
            150,
            benchmark["summary"]["by_configuration"]["with_skill"]["efficiency"]["total_tokens"]["mean"],
        )
        self.assertIn("total_tokens", (iteration_dir / "benchmark.md").read_text())
        self.assertIn("Total tokens", (iteration_dir / "executive-summary.md").read_text())

    def test_benchmark_markdown_handles_single_configuration_runs(self) -> None:
        iteration_dir, run_dir = self.create_finalized_run_fixture(configuration="with_skill")
        finalize(run_dir)

        benchmark = aggregate(
            iteration_dir,
            skill_name="kast-value-proof",
            bindings_path=None,
            catalog_path=None,
        )
        write_outputs(iteration_dir, benchmark)

        report = (iteration_dir / "benchmark.md").read_text()
        self.assertIn("| task_completion | 1.000 | n/a | n/a | n/a |", report)

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

    def create_finalized_run_fixture(
        self,
        *,
        iteration_dir: Path | None = None,
        configuration: str = "with_skill",
    ) -> tuple[Path, Path]:
        iteration_dir = iteration_dir or TEST_WORKSPACE / "iteration-001"
        eval_dir = iteration_dir / "eval-vp-demo"
        run_number = len(list((eval_dir / configuration).glob("run-*"))) + 1
        run_dir = eval_dir / configuration / f"run-{run_number}"
        (run_dir / "outputs").mkdir(parents=True, exist_ok=True)
        write_json(
            eval_dir / "eval_metadata.json",
            {
                "eval_id": "vp-demo",
                "eval_name": "vp-demo",
                "assertions": [
                    {
                        "id": "script-outcome",
                        "text": "Mechanical outcome",
                        "kind": "outcome",
                        "dimension": "accuracy",
                        "applicability": "both",
                        "graded_by": "script",
                    }
                ],
            },
        )
        write_json(
            run_dir / "mechanical.json",
            {
                "status": "graded",
                "expectations": [
                    {
                        "id": "script-outcome",
                        "text": "Mechanical outcome",
                        "passed": True,
                        "evidence": "mechanical evidence",
                        "kind": "outcome",
                        "dimension": "accuracy",
                        "applicability": "both",
                        "graded_by": "script",
                    }
                ],
                "execution_metrics": {
                    "tool_calls": {},
                    "total_tool_calls": 0,
                    "total_steps": 0,
                    "errors_encountered": 0,
                    "output_chars": 19,
                    "transcript_chars": 19,
                    "kast_calls": 0,
                    "grep_or_find_calls": 0,
                },
                "timing": {
                    "executor_duration_seconds": 1.0,
                    "grader_duration_seconds": 0.0,
                    "total_duration_seconds": 1.0,
                    "executor_duration_source": "dispatcher",
                },
                "integrity": {
                    "contradictions": [],
                    "baseline_isolation_violation": False,
                    "attempts": 1,
                    "flaky": False,
                },
            },
        )
        write_json(run_dir / "llm-grade.json", {"expectations": []})
        write_json(
            run_dir / "timing.json",
            {
                "status": "succeeded",
                "attempts": 1,
                "last_exit_code": 0,
                "message": "completed",
            },
        )
        (run_dir / "outputs" / "tool_calls.jsonl").write_text("")
        (run_dir / "outputs" / "transcript.md").write_text("mechanical evidence\n")
        return iteration_dir, run_dir


if __name__ == "__main__":
    unittest.main()
