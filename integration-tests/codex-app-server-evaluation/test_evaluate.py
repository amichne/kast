#!/usr/bin/env python3

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("evaluate.py")


class CodexAppServerEvaluationTest(unittest.TestCase):
    def request(self, directory: Path, mode: str = "dynamic-only") -> Path:
        workspace = directory / "workspace"
        workspace.mkdir()
        request = directory / "request.json"
        request.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "mode": mode,
                    "workspaceRoot": str(workspace),
                    "symbolQuery": "EnterpriseService",
                    "expectedCallerNames": ["createEnterpriseService"],
                    "model": "gpt-enterprise-test",
                }
            )
            + "\n"
        )
        return request

    def run_evaluation(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), *arguments],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_plan_only_emits_safe_dynamic_command_plan_without_writing_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            request = self.request(root)
            output = root / "evidence"

            result = self.run_evaluation(
                "--request",
                str(request),
                "--output-directory",
                str(output),
                "--plan-only",
            )

            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads(result.stdout)
            self.assertEqual("dynamic-only", plan["request"]["mode"])
            self.assertFalse(plan["fullAccessComparisonAuthorized"])
            self.assertFalse(output.exists())
            commands = {entry["name"]: entry for entry in plan["commands"]}
            self.assertIn("install-local", commands)
            self.assertIn("codex-login-status", commands)
            self.assertIn("kast-start", commands)
            evaluation = commands["app-server-evaluation"]
            self.assertIn(":cli:codexAppServerEvaluation", evaluation["argv"])

    def test_comparison_requires_separate_full_access_authorization(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            request = self.request(root, mode="comparison")

            result = self.run_evaluation(
                "--request",
                str(request),
                "--output-directory",
                str(root / "evidence"),
                "--plan-only",
            )

            self.assertNotEqual(0, result.returncode)
            error = json.loads(result.stderr)
            self.assertEqual("full-access-comparison-not-authorized", error["code"])

    def test_request_rejects_unknown_fields_before_any_output_is_created(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            request = self.request(root)
            document = json.loads(request.read_text())
            document["approvalPolicy"] = "never"
            request.write_text(json.dumps(document) + "\n")
            output = root / "evidence"

            result = self.run_evaluation(
                "--request",
                str(request),
                "--output-directory",
                str(output),
                "--plan-only",
            )

            self.assertNotEqual(0, result.returncode)
            error = json.loads(result.stderr)
            self.assertEqual("request-invalid", error["code"])
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
