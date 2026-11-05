"""Exercise preparation admission and bounded child execution without real dependencies."""

from __future__ import annotations

import contextlib
import io
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from collections.abc import Mapping
from pathlib import Path
from types import FrameType
from unittest.mock import patch

import test_agent_efficiency_gate as wrapper
from agent_efficiency_gate import (
    OUTPUT_BYTES,
    PHASE_SECONDS,
    Outcome,
    Phase,
    Result,
    run_checks,
    run_phase,
)


class GatePlanTests(unittest.TestCase):
    def test_preparation_and_bodies_have_separate_commands_and_owned_environment(self) -> None:
        root = Path("/private/example with spaces")
        inherited = {
            "UV_PROJECT_ENVIRONMENT": "/unrelated",
            "VIRTUAL_ENV": "/unrelated",
            "UV_NO_DEV": "true",
            "UV_NO_GROUP": "dev",
            "UV_NO_DEFAULT_GROUPS": "true",
            "UV_FROZEN": "true",
            "UV_CONFIG_FILE": "/unrelated.toml",
            "UV_NO_CONFIG": "true",
            "UV_PROJECT": "/other",
            "UV_WORKING_DIR": "/other",
            "PYTEST_ADDOPTS": "--collect-only",
            "PYTEST_PLUGINS": "unrelated",
            "PYTHONPATH": "/other",
            "PYTHONHOME": "/other",
            "NODE_OPTIONS": "--require other",
            "UV_CACHE_DIR": "/private/cache",
            "PATH": "/tools",
        }
        expected = [
            ("/tools/uv", "sync", "--locked", "--group", "dev", "--project", str(root)),
            (str(root / ".venv/bin/python"), "-m", "pytest", "-q", str(root / "efficiency-tests")),
            (
                "/tools/node",
                "--test",
                str(root / "agent_efficiency_session.test.mjs"),
                str(root / "agent_efficiency_limits.test.mjs"),
                str(root / "agent_efficiency_audit.test.mjs"),
            ),
        ]

        def execute(command: tuple[str, ...], cwd: Path, environment: Mapping[str, str]) -> Result:
            self.assertTrue(expected, "unexpected extra phase")
            self.assertEqual(expected.pop(0), command)
            self.assertEqual(root, cwd)
            self.assertEqual(
                {
                    "UV_CACHE_DIR": "/private/cache",
                    "PATH": "/tools",
                    "UV_PROJECT_ENVIRONMENT": str(root / ".venv"),
                    "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
                },
                dict(environment),
            )
            return Result(Outcome.PASSED, 0, "executed")

        with contextlib.redirect_stdout(io.StringIO()):
            results = run_checks(root, "/tools/uv", "/tools/node", inherited, execute)
        self.assertEqual([], expected)
        self.assertEqual([Phase.PREPARATION, Phase.PYTEST, Phase.NODE], [p for p, _ in results])
        self.assertEqual("--collect-only", inherited["PYTEST_ADDOPTS"])
        self.assertEqual(120, PHASE_SECONDS)

    def test_failed_phase_prevents_all_later_effects(self) -> None:
        for failed_phase in range(3):
            for outcome in (
                Outcome.FAILED,
                Outcome.TIMED_OUT,
                Outcome.UNAVAILABLE,
                Outcome.CANCELLED,
            ):
                with self.subTest(phase=failed_phase, outcome=outcome):
                    calls = 0

                    def execute(
                        _command: tuple[str, ...],
                        _cwd: Path,
                        _environment: Mapping[str, str],
                        failed_phase: int = failed_phase,
                        outcome: Outcome = outcome,
                    ) -> Result:
                        nonlocal calls
                        self.assertLessEqual(calls, failed_phase, "ran after rejection")
                        calls += 1
                        return Result(
                            outcome if calls == failed_phase + 1 else Outcome.PASSED,
                            7 if outcome == Outcome.FAILED else None,
                            "retained",
                        )

                    with contextlib.redirect_stdout(io.StringIO()):
                        results = run_checks(Path("/example"), "uv", "node", {}, execute)
                    self.assertEqual(failed_phase + 1, calls)
                    self.assertEqual(outcome, results[-1][1].outcome)

    def test_cleanup_failure_cannot_admit_next_phase(self) -> None:
        calls = 0

        def execute(
            _command: tuple[str, ...], _cwd: Path, _environment: Mapping[str, str]
        ) -> Result:
            nonlocal calls
            calls += 1
            self.assertEqual(1, calls)
            return Result(Outcome.PASSED, 0, "", cleanup_failed=True)

        with contextlib.redirect_stdout(io.StringIO()):
            results = run_checks(Path("/example"), "uv", "node", {}, execute)
        self.assertEqual(1, len(results))

    def test_wrapper_retains_timeout_phase_and_partial_output(self) -> None:
        result = unittest.TestResult()
        case = wrapper.AgentEfficiencyGate("test_locked_coordinator_and_native_session_contracts")
        with (
            patch.object(wrapper.shutil, "which", return_value="/tools/tool"),
            patch.object(
                wrapper.subprocess,
                "run",
                return_value=subprocess.CompletedProcess(["git"], 0, stdout="", stderr=""),
            ),
            patch.object(
                wrapper,
                "run_checks",
                return_value=(
                    (
                        Phase.PREPARATION,
                        Result(Outcome.TIMED_OUT, -9, "partial preparation output"),
                    ),
                ),
            ),
        ):
            case.run(result)
        self.assertEqual(1, len(result.failures))
        self.assertEqual([], result.errors)
        message = result.failures[0][1]
        self.assertIn("phase=LOCKED_PREPARATION outcome=TIMED_OUT", message)
        self.assertIn("partial preparation output", message)


class GateProcessTests(unittest.TestCase):
    def run_child(self, source: str, seconds: float = 3) -> Result:
        with tempfile.TemporaryDirectory() as directory:
            return run_phase((sys.executable, "-c", source), Path(directory), os.environ, seconds)

    def test_exit_code_and_both_output_streams_survive(self) -> None:
        result = self.run_child(
            "import sys; print('stdout'); print('stderr', file=sys.stderr); sys.exit(19)"
        )
        self.assertEqual(Outcome.FAILED, result.outcome)
        self.assertEqual(19, result.returncode)
        self.assertIn("stdout", result.output)
        self.assertIn("stderr", result.output)
        self.assertFalse(result.cleanup_failed)

    def test_late_cancellation_with_returning_handler_cannot_pass(self) -> None:
        received: list[int] = []

        def returning_handler(signum: int, _frame: FrameType | None) -> None:
            received.append(signum)

        original_killpg = os.killpg

        def cancel_during_cleanup(pgid: int, signum: int) -> None:
            signal.raise_signal(signal.SIGTERM)
            original_killpg(pgid, signum)

        original_handler = signal.signal(signal.SIGTERM, returning_handler)
        try:
            with patch("agent_efficiency_gate.os.killpg", side_effect=cancel_during_cleanup):
                with contextlib.redirect_stdout(io.StringIO()):
                    result = self.run_child("pass")
            self.assertEqual(Outcome.CANCELLED, result.outcome)
            self.assertEqual([signal.SIGTERM], received)
            self.assertIs(returning_handler, signal.getsignal(signal.SIGTERM))
        finally:
            signal.signal(signal.SIGTERM, original_handler)

    def test_success_preserves_only_bounded_tail(self) -> None:
        result = self.run_child("import os; os.write(1, b'x' * 262144); os.write(1, b'END')")
        self.assertEqual(Outcome.PASSED, result.outcome)
        self.assertEqual(OUTPUT_BYTES, len(result.output))
        self.assertTrue(result.output.endswith("END"))
        self.assertTrue(result.truncated)

    def test_timeout_retains_partial_output(self) -> None:
        result = self.run_child("import signal; print('READY', flush=True); signal.pause()", 1)
        self.assertEqual(Outcome.TIMED_OUT, result.outcome)
        self.assertIn("READY", result.output)
        self.assertEqual(-signal.SIGKILL, result.returncode)
        self.assertFalse(result.cleanup_failed)

    def test_continuous_output_cannot_defeat_deadline(self) -> None:
        result = self.run_child("import os\nwhile True: os.write(1, b'x' * 65536)", 1)
        self.assertEqual(Outcome.TIMED_OUT, result.outcome)
        self.assertEqual(OUTPUT_BYTES, len(result.output))
        self.assertTrue(result.truncated)

    def test_pipe_holder_after_leader_exit_is_bounded(self) -> None:
        result = self.run_child(
            "import subprocess, sys\n"
            "subprocess.Popen([sys.executable, '-c', "
            "\"import signal; print('HOLDER', flush=True); signal.pause()\"])\n",
            1,
        )
        self.assertEqual(Outcome.TIMED_OUT, result.outcome)
        self.assertEqual(0, result.returncode)
        self.assertIn("HOLDER", result.output)
        self.assertFalse(result.cleanup_failed)

    def test_missing_project_interpreter_cannot_use_path_python(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = run_phase((str(root / ".venv/bin/python"), "-m", "pytest"), root, os.environ)
        self.assertEqual(Outcome.UNAVAILABLE, result.outcome)

    def test_cancellation_reaps_owned_child_and_preserves_unrelated_process(self) -> None:
        module_root = Path(__file__).resolve().parent
        for signum in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signum), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                sentinel = subprocess.Popen([sys.executable, "-c", "import signal; signal.pause()"])
                child_source = (
                    "import os, signal; from pathlib import Path; "
                    "Path('owned.pid').write_text(str(os.getpid())); "
                    "os.kill(os.getppid(), " + str(int(signum)) + "); signal.pause()"
                )
                source = (
                    "import os, sys; from pathlib import Path; "
                    f"sys.path.insert(0, {str(module_root)!r}); "
                    "from agent_efficiency_gate import run_phase; "
                    f"run_phase((sys.executable, '-c', {child_source!r}), Path('.'), os.environ)"
                )
                try:
                    result = subprocess.run(
                        [sys.executable, "-c", source],
                        cwd=root,
                        capture_output=True,
                        text=True,
                        timeout=5,
                        check=False,
                    )
                    self.assertEqual(-signum, result.returncode)
                    self.assertIn("outcome=CANCELLED", result.stdout)
                    self.assertIn("cleanupFailed=False", result.stdout)
                    owned_pid = int((root / "owned.pid").read_text())
                    with self.assertRaises(ProcessLookupError):
                        os.kill(owned_pid, 0)
                    self.assertIsNone(sentinel.poll())
                finally:
                    sentinel.kill()
                    sentinel.wait(timeout=2)


if __name__ == "__main__":
    unittest.main()
