"""Include the locked agent experiment checks in hostObservationTest."""

import os
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
        # Git hooks export repository identity. Fixtures must own their Git state.
        local_names = subprocess.run(
            ["git", "rev-parse", "--local-env-vars"],
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.splitlines()
        environment = {key: value for key, value in os.environ.items() if key not in local_names}
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
                str(root / "agent_efficiency_chaining.test.mjs"),
            ],
        ):
            result = subprocess.run(
                command,
                cwd=root,
                env=environment,
                capture_output=True,
                text=True,
                timeout=120,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
