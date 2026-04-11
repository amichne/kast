#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT = REPO_ROOT / "evals" / "harness" / "report.py"


def case_result(
    case_id: str,
    case_name: str,
    *,
    passed: bool,
) -> dict[str, object]:
    assertion_name = "tool_used(kast-resolve.sh)" if not passed else "tool_used(kast-callers.sh)"
    actual = "[]" if not passed else "kast-callers.sh"
    return {
        "case_id": case_id,
        "case_name": case_name,
        "suite": "kast-routing",
        "assertions": [
            {
                "assertion": assertion_name,
                "passed": passed,
                "expected": "kast tool invocation",
                "actual": actual,
                "message": "PASS" if passed else "FAIL",
            }
        ],
    }


class ReportCliTest(unittest.TestCase):
    def test_accepts_jsonl_case_results_with_blank_lines(self) -> None:
        raw_results = json.dumps(
            [
                case_result("resolve-symbol", "Resolve symbol", passed=True),
                case_result("find-callers", "Find callers", passed=False),
            ]
        )

        result = subprocess.run(
            [sys.executable, str(REPORT), "--format=json"],
            input=raw_results,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(result.returncode, 1, msg=result.stderr)

        report = json.loads(result.stdout)
        self.assertEqual(report["suite"], "kast-routing")
        self.assertEqual(report["total"], 2)
        self.assertEqual(report["passed"], 1)
        self.assertEqual(report["failed"], 1)
        self.assertEqual(report["llm_routing_score"], 50)
        self.assertEqual(
            report["routing_violations"],
            [
                {
                    "case_id": "find-callers",
                    "violation": "did not route through kast-resolve.sh",
                }
            ],
        )


if __name__ == "__main__":
    unittest.main()
