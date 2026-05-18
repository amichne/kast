#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import textwrap
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1]
EVALUATION_DIR = SCRIPT_DIR.parent
SCRATCH_DIR = Path(__file__).resolve().parent / ".run-evaluation-scratch"
BENCHMARK_SCHEMA_PATH = EVALUATION_DIR / "benchmark.schema.json"


def write_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


class RunEvaluationTests(unittest.TestCase):
    def tearDown(self) -> None:
        shutil.rmtree(SCRATCH_DIR, ignore_errors=True)

    def test_orchestrator_forwards_concurrency_and_max_retries(self) -> None:
        sys.path.insert(0, str(SCRIPT_DIR))
        try:
            import run_evaluation

            parser = run_evaluation.build_parser()
            args = parser.parse_args(
                [
                    "--catalog", "c.json",
                    "--bindings", "b.json",
                    "--workspace", "w",
                    "--concurrency", "7",
                    "--max-retries", "3",
                ]
            )
            self.assertEqual(7, args.concurrency)
            self.assertEqual(3, args.max_retries)
            defaults = parser.parse_args(
                ["--catalog", "c.json", "--bindings", "b.json", "--workspace", "w"]
            )
            self.assertEqual(4, defaults.concurrency)
            self.assertEqual(1, defaults.max_retries)
        finally:
            sys.path.remove(str(SCRIPT_DIR))

    def test_run_evaluation_orchestrates_end_to_end(self) -> None:
        workspace_root = SCRATCH_DIR / "workspace-root"
        workspace_root.mkdir(parents=True)
        subprocess.run(["git", "init"], cwd=workspace_root, check=True, capture_output=True)
        (workspace_root / "README.md").write_text("demo\n")
        subprocess.run(["git", "add", "README.md"], cwd=workspace_root, check=True, capture_output=True)
        subprocess.run(
            ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "init"],
            cwd=workspace_root,
            check=True,
            capture_output=True,
        )

        catalog_path = SCRATCH_DIR / "catalog.json"
        write_json(
            catalog_path,
            {
                "skill_name": "kast-value-proof",
                "version": 1,
                "cases": [
                    {
                        "id": "vp-demo",
                        "title": "Demo case",
                        "prompt": "Check {{DISAMBIGUATE_MEMBER.symbol}}",
                        "expected_output": "demo",
                        "expectations": [
                            {
                                "id": "demo-outcome",
                                "text": "Outcome succeeds",
                                "kind": "outcome",
                                "dimension": "accuracy",
                                "applicability": "both",
                                "graded_by": "llm",
                            }
                        ],
                    }
                ],
            },
        )
        bindings_path = SCRATCH_DIR / "bindings.json"
        write_json(
            bindings_path,
            {
                "target_repo": "kast",
                "workspace_root": str(workspace_root),
                "slots": {
                    "SEALED_HIERARCHY": {
                        "symbol": "Demo",
                        "fqName": "demo.Demo",
                        "file": "Demo.kt",
                        "description": "demo",
                        "expected": {"implementations": [{"fqName": "demo.DemoImpl", "module": "demo"}]},
                    },
                    "DISAMBIGUATE_MEMBER": {
                        "symbol": "name",
                        "fqName": "demo.Demo.name",
                        "file": "Demo.kt",
                        "description": "demo",
                        "containingType": "demo.Demo",
                        "expected": {"minimumUsageSites": 1, "expectedFiles": ["Demo.kt"], "decoyFiles": []},
                    },
                    "CROSS_MODULE_CLASS": {
                        "symbol": "Demo",
                        "fqName": "demo.Demo",
                        "file": "Demo.kt",
                        "description": "demo",
                        "expected": {
                            "minimumReferences": 1,
                            "expectedConsumerModules": ["demo"],
                            "expectedConsumerFiles": ["Demo.kt"],
                        },
                    },
                    "OVERLOADED_OR_COMMON_FUNCTION": {
                        "symbol": "run",
                        "fqName": "demo.Demo.run",
                        "file": "Demo.kt",
                        "description": "demo",
                        "containingType": "demo.Demo",
                        "expected": {"minimumCallers": 1, "expectedCallerFqNames": ["demo.CallSite"]},
                    },
                    "RENAME_TARGET": {
                        "symbol": "Demo",
                        "fqName": "demo.Demo",
                        "file": "Demo.kt",
                        "description": "demo",
                        "newName": "DemoRenamed",
                        "expected": {"affectedFiles": ["Demo.kt"]},
                    },
                    "LARGE_CLASS": {
                        "symbol": "Demo",
                        "fqName": "demo.Demo",
                        "file": "Demo.kt",
                        "description": "demo",
                        "expected": {"rawFileLineCount": 10, "publicMembers": ["run()"], "nestedTypes": []},
                    },
                    "MODULE_LIST": {
                        "modules": ["demo"],
                        "description": "demo",
                        "expected": {"moduleFileCounts": {"demo": 1}},
                    },
                },
            },
        )

        runner_path = SCRATCH_DIR / "fake_runner.py"
        runner_path.write_text(
            textwrap.dedent(
                """
                import pathlib
                import sys

                transcript = pathlib.Path(sys.argv[1])
                configuration = sys.argv[2]
                transcript.parent.mkdir(parents=True, exist_ok=True)
                if configuration == "with_skill":
                    transcript.write_text("<tool name=\\"kast_resolve\\">{}</tool>\\n")
                else:
                    transcript.write_text("baseline run\\n")
                """
            ).strip()
            + "\n"
        )
        grader_path = SCRATCH_DIR / "fake_grader.py"
        grader_path.write_text(
            textwrap.dedent(
                """
                import json
                import pathlib
                import sys

                grading = pathlib.Path(sys.argv[1])
                configuration = sys.argv[2]
                passed = configuration == "with_skill"
                payload = {
                    "status": "graded",
                    "expectations": [
                        {
                            "id": "demo-outcome",
                            "text": "Outcome succeeds",
                            "passed": passed,
                            "evidence": "synthetic evidence",
                            "kind": "outcome",
                            "applicability": "both",
                            "graded_by": "llm"
                        }
                    ],
                    "summary": {
                        "passed": 1 if passed else 0,
                        "failed": 0 if passed else 1,
                        "total": 1,
                        "pass_rate": 1.0 if passed else 0.0
                    },
                    "execution_metrics": {
                        "tool_calls": {},
                        "total_tool_calls": 0,
                        "total_steps": 1,
                        "errors_encountered": 0,
                        "output_chars": 0,
                        "transcript_chars": 0
                    },
                    "timing": {
                        "executor_duration_seconds": 0.1,
                        "grader_duration_seconds": 0.1,
                        "total_duration_seconds": 0.2
                    }
                }
                grading.write_text(json.dumps(payload, indent=2) + "\\n")
                """
            ).strip()
            + "\n"
        )

        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT_DIR / "run_evaluation.py"),
                "--catalog",
                str(catalog_path),
                "--bindings",
                str(bindings_path),
                "--workspace",
                str(SCRATCH_DIR / "benchmarks"),
                "--iteration",
                "iteration-001",
                "--runs-per-config",
                "1",
                "--dispatch-command-template",
                f"{sys.executable} {runner_path} {{transcript}} {{configuration}}",
                "--llm-grade-command-template",
                f"{sys.executable} {grader_path} {{llm_grade}} {{configuration}}",
            ],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        iteration_dir = SCRATCH_DIR / "benchmarks" / "iteration-001"
        benchmark = json.loads((iteration_dir / "benchmark.json").read_text())
        schema = json.loads(BENCHMARK_SCHEMA_PATH.read_text())
        self.assertEqual(
            "https://json-schema.org/draft/2020-12/schema",
            schema["$schema"],
        )
        self.assertEqual(
            "https://github.com/amichne/kast/evaluation/benchmark.schema.json",
            benchmark["$schema"],
        )
        self.assertIn("mechanical_summary", benchmark)
        self.assertIn("llm_graded_summary", benchmark)
        self.assertIn("combined_summary", benchmark)
        self.assertEqual(["with_skill", "tool_only", "without_skill"], benchmark["metadata"]["configurations"])
        self.assertIn("mechanical", benchmark["runs"][0])
        self.assertIn("llm_graded", benchmark["runs"][0])
        self.assertIn("combined", benchmark["runs"][0])
        self.assertEqual("evaluation", benchmark["metadata"]["skill_path"])
        self.assertEqual(2, benchmark["schema_version"])
        self.assertEqual(
            "scored",
            benchmark["paired_analysis"]["statistics"]["score_metrics"]["accuracy"]["status"],
        )
        self.assertTrue((iteration_dir / "rendered-catalog.json").exists())
        self.assertTrue((iteration_dir / "bindings.json").exists())
        persisted_bindings = json.loads((iteration_dir / "bindings.json").read_text())
        self.assertIn("slots", persisted_bindings)
        self.assertIn("DISAMBIGUATE_MEMBER", persisted_bindings["slots"])

    def test_copilot_sdk_runner_scaffolds_smoke_command(self) -> None:
        workspace_root = SCRATCH_DIR / "workspace-root"
        workspace_root.mkdir(parents=True)
        subprocess.run(["git", "init"], cwd=workspace_root, check=True, capture_output=True)
        source_file = workspace_root / "src" / "Demo.kt"
        source_file.parent.mkdir(parents=True)
        source_file.write_text("class Demo { val name = \"demo\" }\nfun use(d: Demo) = d.name\n")
        subprocess.run(["git", "add", "src/Demo.kt"], cwd=workspace_root, check=True, capture_output=True)
        subprocess.run(
            ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "init"],
            cwd=workspace_root,
            check=True,
            capture_output=True,
        )

        catalog_path = SCRATCH_DIR / "catalog.json"
        write_json(
            catalog_path,
            {
                "skill_name": "kast-value-proof",
                "version": 1,
                "cases": [
                    {
                        "id": "vp-disambiguate-member",
                        "title": "Disambiguate member",
                        "prompt": "Find {{DISAMBIGUATE_MEMBER.symbol}}",
                        "expectations": [
                            {
                                "id": "om-recall",
                                "text": "Reports usages in every expected file",
                                "kind": "outcome",
                                "dimension": "accuracy",
                                "applicability": "both",
                                "oracle": "DISAMBIGUATE_MEMBER.expected.expectedFiles",
                                "graded_by": "script",
                            },
                            {
                                "id": "pm-uses-resolve",
                                "text": "Resolves the member before scanning usages",
                                "kind": "process",
                                "dimension": "reliability",
                                "applicability": "with_skill_only",
                                "graded_by": "script",
                            },
                        ],
                    }
                ],
            },
        )
        bindings_path = SCRATCH_DIR / "bindings.json"
        write_json(
            bindings_path,
            {
                "target_repo": "demo",
                "workspace_root": str(workspace_root),
                "slots": {
                    "DISAMBIGUATE_MEMBER": {
                        "symbol": "name",
                        "fqName": "demo.Demo.name",
                        "file": "src/Demo.kt",
                        "description": "demo",
                        "containingType": "demo.Demo",
                        "expected": {
                            "minimumUsageSites": 1,
                            "expectedFiles": ["src/Demo.kt"],
                            "decoyFiles": [],
                        },
                    }
                },
            },
        )
        env = {**os.environ, "KAST_EVAL_SKIP_NPM_CI": "1"}
        result = subprocess.run(
            [
                str(EVALUATION_DIR / "runners" / "copilot-sdk" / "run-benchmark.sh"),
                "--catalog",
                str(catalog_path),
                "--bindings",
                str(bindings_path),
                "--workspace",
                str(SCRATCH_DIR / "benchmarks"),
                "--iteration",
                "smoke",
                "--runs-per-config",
                "1",
                "--concurrency",
                "1",
                "--skip-dispatch",
                "--skip-grade",
                "--skip-aggregate",
                "--",
                "--case",
                "vp-disambiguate-member",
            ],
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        iteration_dir = SCRATCH_DIR / "benchmarks" / "smoke"
        run_dir = iteration_dir / "eval-vp-disambiguate-member" / "with_skill" / "run-1"
        self.assertTrue((run_dir / "run_instructions.md").exists())
        self.assertIn("Find name", (run_dir / "run_instructions.md").read_text())
        rendered = json.loads((iteration_dir / "rendered-catalog.json").read_text())
        self.assertEqual(["vp-disambiguate-member"], [case["id"] for case in rendered["cases"]])
        self.assertFalse((EVALUATION_DIR / "runners" / "copilot" / "run-benchmark.sh").exists())

    def test_single_mock_benchmark_dry_run_shows_mock_codex_and_publish_contract(self) -> None:
        result = subprocess.run(
            [
                str(EVALUATION_DIR / "runners" / "copilot-sdk" / "run-single-mock-benchmark.sh"),
                "--dry-run",
                "--workspace",
                str(SCRATCH_DIR / "mock-benchmarks"),
                "--results-repo",
                str(SCRATCH_DIR / "cast-benchmarks"),
                "--iteration",
                "mock-single",
                "--run-slug",
                "mock-single-dry-run",
            ],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("run-benchmark.sh", result.stdout)
        self.assertIn("--runs-per-config 1", result.stdout)
        self.assertIn("--max-retries 0", result.stdout)
        self.assertIn("--kast-backend mock", result.stdout)
        self.assertIn("--model gpt-5-mini", result.stdout)
        self.assertIn("codex exec", result.stdout)
        self.assertIn("--model gpt-5.5", result.stdout)
        self.assertIn('model_reasoning_effort="xhigh"', result.stdout)
        self.assertIn("--sandbox danger-full-access", result.stdout)
        self.assertIn("--ask-for-approval never", result.stdout)
        self.assertIn("cast-benchmarks", result.stdout)


if __name__ == "__main__":
    unittest.main()
