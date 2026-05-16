#!/usr/bin/env python3
from __future__ import annotations

import json
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
                "--grade-command-template",
                f"{sys.executable} {grader_path} {{grading}} {{configuration}}",
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
        self.assertEqual("evaluation", benchmark["metadata"]["skill_path"])
        self.assertEqual(1, benchmark["schema_version"])
        self.assertEqual(
            "scored",
            benchmark["paired_analysis"]["statistics"]["score_metrics"]["accuracy"]["status"],
        )
        self.assertTrue((iteration_dir / "rendered-catalog.json").exists())
        self.assertTrue((iteration_dir / "bindings.json").exists())


if __name__ == "__main__":
    unittest.main()
