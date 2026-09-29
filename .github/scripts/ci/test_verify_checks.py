#!/usr/bin/env python3
"""Omitting graph inspection must preserve admission, preflight, and the actual gate."""
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("verify_checks", Path(__file__).with_name("verify-checks.py"))
assert SPEC is not None and SPEC.loader is not None
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class VerifyChecksTest(unittest.TestCase):
    def commands(self, *arguments):
        with patch.object(sys, "argv", ["verify-checks.py", *arguments]), \
                patch.object(verify.os.environ, "copy", return_value={"KAST_RELEASE_JDK_25": "/jdk25"}), \
                patch.object(verify.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                patch.object(verify.subprocess, "run") as execute, redirect_stdout(io.StringIO()):
            execute.return_value.returncode = 0
            verify.main()
        return [call.args[0] for call in execute.call_args_list]

    def test_graph_inspection_remains_the_default(self):
        commands = self.commands()
        self.assertIn(["python3", ".github/scripts/ci/routine_gate.py"], commands)
        self.assertIn(["python3", "distribution/release/run_product_gate.py"], commands)

    def test_skip_removes_only_graph_inspection(self):
        required = self.commands()
        selected = self.commands("--gate-graph", "skip")
        self.assertEqual([command for command in required if command != ["python3", ".github/scripts/ci/routine_gate.py"]], selected)
        self.assertEqual(1, selected.count(["python3", "distribution/release/run_product_gate.py"]))
        self.assertEqual(".github/scripts/release/admit-source.sh", selected[0][1])
        self.assertEqual(selected[0], selected[-1])

    def test_main_preflight_does_not_build_the_product_twice(self):
        for decision in ("run", "skip"):
            with self.subTest(decision=decision):
                commands = self.commands("--preflight-only", "--gate-graph", decision)
                self.assertNotIn(["python3", "distribution/release/run_product_gate.py"], commands)

    def test_unknown_graph_decision_rejects_before_effects(self):
        with patch.object(sys, "argv", ["verify-checks.py", "--gate-graph", "unknown"]), \
                patch.object(verify.subprocess, "run") as execute, redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
            verify.main()
        self.assertEqual(2, failure.exception.code)
        execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
