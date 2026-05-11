#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1]
EVALUATION_DIR = SCRIPT_DIR.parent
REPO_ROOT = EVALUATION_DIR.parent
SKILL_CREATOR_SCRIPT_DIR = REPO_ROOT / ".agents" / "skills" / "skill-creator" / "scripts"
SCRATCH_DIR = Path(__file__).resolve().parent / ".workstream-d-scratch"

for path in (SCRIPT_DIR, SKILL_CREATOR_SCRIPT_DIR):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

from run_value_proof import GRADING_SCHEMA_PATH, write_placeholder_grading
from validation import validate_grading_data


def write_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


class WorkstreamDTests(unittest.TestCase):
    def tearDown(self) -> None:
        shutil.rmtree(SCRATCH_DIR, ignore_errors=True)

    def test_generate_executive_summary_defaults_to_iteration_paths(self) -> None:
        iteration_dir = SCRATCH_DIR / "iteration-001"
        write_json(
            iteration_dir / "benchmark.json",
            {
                "metadata": {"skill_name": "kast-value-proof"},
                "runs": [],
                "run_summary": {
                    "with_skill": {},
                    "without_skill": {},
                    "delta": {},
                },
            },
        )
        write_json(
            iteration_dir / "bindings.json",
            {
                "target_repo": "demo-repo",
                "workspace_root": "/workspace/demo-repo",
                "slots": {},
            },
        )

        result = subprocess.run(
            [sys.executable, str(SCRIPT_DIR / "generate_executive_summary.py"), str(iteration_dir)],
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((iteration_dir / "executive-summary.md").exists())
        self.assertTrue((iteration_dir / "executive-summary.html").exists())
        self.assertIn("Kast Value Proof: demo-repo", (iteration_dir / "executive-summary.md").read_text())

    def test_generate_executive_summary_respects_explicit_overrides(self) -> None:
        iteration_dir = SCRATCH_DIR / "iteration-002"
        override_dir = SCRATCH_DIR / "overrides"
        write_json(
            override_dir / "benchmark.json",
            {
                "metadata": {"skill_name": "override-skill"},
                "runs": [],
                "run_summary": {
                    "with_skill": {},
                    "without_skill": {},
                    "delta": {},
                },
            },
        )
        write_json(
            override_dir / "bindings.json",
            {
                "target_repo": "override-repo",
                "workspace_root": "/workspace/override-repo",
                "slots": {},
            },
        )

        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT_DIR / "generate_executive_summary.py"),
                str(iteration_dir),
                "--benchmark",
                str(override_dir / "benchmark.json"),
                "--bindings",
                str(override_dir / "bindings.json"),
                "--output",
                str(override_dir / "summary.md"),
                "--html-output",
                str(override_dir / "summary.html"),
            ],
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((iteration_dir / "executive-summary.md").exists())
        self.assertTrue((override_dir / "summary.md").exists())
        self.assertTrue((override_dir / "summary.html").exists())

    def test_placeholder_grading_matches_published_schema(self) -> None:
        self.assertTrue(GRADING_SCHEMA_PATH.exists())
        grading_path = SCRATCH_DIR / "grading.json"
        grading_path.parent.mkdir(parents=True, exist_ok=True)

        write_placeholder_grading(grading_path)

        grading = json.loads(grading_path.read_text())
        report = validate_grading_data(grading, path=Path("grading.json"))
        self.assertTrue(report.is_valid, report.errors)


if __name__ == "__main__":
    unittest.main()
