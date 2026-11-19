#!/usr/bin/env python3
"""The real build adapter retains independent success and failure evidence."""
from pathlib import Path
import json
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

from build_execution import Outcome, run


class BuildExecutionTest(unittest.TestCase):
    def test_success_retains_elapsed_time_and_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "execution.json"
            executor = Mock(return_value=subprocess.CompletedProcess(["build"], 0))
            clock = Mock(side_effect=[10.0, 12.5])
            evidence = run(["build"], report, executor, clock)
            executor.assert_called_once_with(["build"], check=False)
            self.assertEqual(2, clock.call_count)
            self.assertEqual(Outcome.SUCCESS, evidence.outcome)
            self.assertEqual({"schemaVersion": 2, "outcome": "SUCCESS", "elapsedMilliseconds": 2500,
                              "exitCode": 0}, json.loads(report.read_text()))

    def test_failure_records_its_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "execution.json"
            executor = Mock(return_value=subprocess.CompletedProcess(["build"], 7))
            evidence = run(["build"], report, executor, Mock(side_effect=[1.0, 2.0]))
            self.assertEqual(Outcome.PROCESS_FAILED, evidence.outcome)
            self.assertEqual(7, evidence.exitCode)
            self.assertEqual("PROCESS_FAILED", json.loads(report.read_text())["outcome"])
            executor.assert_called_once()

    def test_os_refusal_is_distinct_from_a_process_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            executor = Mock(side_effect=OSError())
            evidence = run(["build"], Path(temporary) / "execution.json", executor, Mock(side_effect=[1.0, 1.0]))
            self.assertEqual(Outcome.EXECUTION_FAILED, evidence.outcome)
            self.assertNotEqual(0, evidence.exitCode)
            executor.assert_called_once()


if __name__ == "__main__":
    unittest.main()
