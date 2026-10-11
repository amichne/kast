"""Include launch-free Node policy/controller tests in hostObservationTest."""
import shutil
import subprocess
import unittest
from pathlib import Path


class PiEvaluationTest(unittest.TestCase):
    def test_deterministic_controller(self):
        node = shutil.which("node")
        self.assertIsNotNone(node, "Pi controller checks require Node.js; missing tooling is not GREEN")
        result = subprocess.run([node, "--test", "pi_evaluation.test.mjs", "pi_evaluation_payload.test.mjs", "pi_evaluation_records.test.mjs", "pi_evaluation_calibrations.test.mjs", "pi_evaluation_preflight.test.mjs", "pi_evaluation_worker.test.mjs"],
                                cwd=Path(__file__).parent, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
