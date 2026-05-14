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
SCRATCH_DIR = Path(__file__).resolve().parent / ".history-assets-scratch"

if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from validate_history_assets import validate_assets


class HistoryAssetsTests(unittest.TestCase):
    def tearDown(self) -> None:
        shutil.rmtree(SCRATCH_DIR, ignore_errors=True)

    def test_repo_history_assets_validate(self) -> None:
        errors = validate_assets()
        self.assertEqual([], errors)

    def test_cli_fails_when_provenance_misses_canonical_case(self) -> None:
        scratch_eval = SCRATCH_DIR / "evaluation"
        (scratch_eval / "fixtures" / "staging").mkdir(parents=True, exist_ok=True)
        shutil.copy(EVALUATION_DIR / "catalog.json", scratch_eval / "catalog.json")
        shutil.copy(EVALUATION_DIR / "provenance.json", scratch_eval / "provenance.json")
        shutil.copy(
            EVALUATION_DIR / "fixtures" / "staging" / "copilot-history-candidates.json",
            scratch_eval / "fixtures" / "staging" / "copilot-history-candidates.json",
        )

        provenance_path = scratch_eval / "provenance.json"
        provenance = json.loads(provenance_path.read_text())
        provenance["case_coverage"] = provenance["case_coverage"][:-1]
        provenance_path.write_text(json.dumps(provenance, indent=2) + "\n")

        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT_DIR / "validate_history_assets.py"),
                "--catalog",
                str(scratch_eval / "catalog.json"),
                "--provenance",
                str(provenance_path),
                "--candidates",
                str(scratch_eval / "fixtures" / "staging" / "copilot-history-candidates.json"),
            ],
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing canonical cases", result.stderr)


if __name__ == "__main__":
    unittest.main()
