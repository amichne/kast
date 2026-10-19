"""Include the locked agent experiment checks in hostObservationTest."""

import shutil
import subprocess
import unittest
from pathlib import Path


class AgentEfficiencyGate(unittest.TestCase):
    def test_locked_coordinator_and_native_session_contracts(self) -> None:
        root = Path(__file__).resolve().parent
        uv = shutil.which("uv")
        node = shutil.which("node")
        if uv is None:
            self.fail("Agent experiment checks need uv 0.12.9 or later")
        if node is None:
            self.fail("Native session contracts need Node.js")
        for command in (
            [
                uv,
                "run",
                "--locked",
                "--project",
                str(root),
                "pytest",
                "-q",
                str(root / "efficiency-tests"),
            ],
            [
                node,
                "--test",
                str(root / "agent_efficiency_session.test.mjs"),
                str(root / "agent_efficiency_limits.test.mjs"),
                str(root / "agent_efficiency_audit.test.mjs"),
            ],
        ):
            result = subprocess.run(
                command, cwd=root, capture_output=True, text=True, timeout=120, check=False
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
