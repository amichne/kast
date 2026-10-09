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
    def test_success_requires_retained_native_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "execution.json"
            native = Path(temporary) / "native.json"
            native.write_text('{"analysis_results":{}}')
            executor = Mock(return_value=subprocess.CompletedProcess(["build"], 0))
            clock = Mock(side_effect=[10.0, 12.5])
            evidence = run(["build"], report, native, executor, clock)
            executor.assert_called_once_with(["build"], check=False)
            self.assertEqual(2, clock.call_count)
            self.assertEqual(Outcome.SUCCESS, evidence.outcome)
            self.assertEqual({"schemaVersion": 1, "outcome": "SUCCESS", "elapsedMilliseconds": 2500,
                              "exitCode": 0, "nativeMetrics": "RETAINED"}, json.loads(report.read_text()))

    def test_failure_records_exit_without_manufacturing_native_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "execution.json"
            executor = Mock(return_value=subprocess.CompletedProcess(["build"], 7))
            evidence = run(["build"], report, Path(temporary) / "native.json", executor, Mock(side_effect=[1.0, 2.0]))
            self.assertEqual(Outcome.PROCESS_FAILED, evidence.outcome)
            self.assertEqual(7, evidence.exitCode)
            self.assertEqual("UNAVAILABLE", json.loads(report.read_text())["nativeMetrics"])
            executor.assert_called_once()

    def test_os_refusal_and_missing_success_metrics_are_distinct(self):
        for executor, outcome in ((Mock(side_effect=OSError()), Outcome.EXECUTION_FAILED),
                                   (Mock(return_value=subprocess.CompletedProcess(["build"], 0)), Outcome.NATIVE_METRICS_MISSING)):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temporary:
                evidence = run(["build"], Path(temporary) / "execution.json", Path(temporary) / "missing.json",
                               executor, Mock(side_effect=[1.0, 1.0]))
                self.assertEqual(outcome, evidence.outcome)
                self.assertNotEqual(0, evidence.exitCode)
                executor.assert_called_once()


if __name__ == "__main__":
    unittest.main()
