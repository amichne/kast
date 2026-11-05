"""Include the locked agent experiment checks in hostObservationTest."""

import os
import shutil
import subprocess
import unittest
from pathlib import Path

from agent_efficiency_gate import Outcome, run_checks


class AgentEfficiencyGate(unittest.TestCase):
    def test_locked_coordinator_and_native_session_contracts(self) -> None:
        root = Path(__file__).resolve().parent
        uv = shutil.which("uv")
        node = shutil.which("node")
        if uv is None:
            self.fail("Agent experiment checks need uv 0.12.9 or later")
        if node is None:
            self.fail("Native session contracts need Node.js")
        # Git hooks export repository identity. Fixtures must own their Git state.
        local_names = subprocess.run(
            ["git", "rev-parse", "--local-env-vars"],
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.splitlines()
        environment = {key: value for key, value in os.environ.items() if key not in local_names}
        for phase, result in run_checks(root, uv, node, environment):
            self.assertEqual(
                (Outcome.PASSED, False),
                (result.outcome, result.cleanup_failed),
                f"phase={phase} outcome={result.outcome} returncode={result.returncode} "
                f"outputTruncated={result.truncated} cleanupFailed={result.cleanup_failed}\n"
                f"{result.output}",
            )
