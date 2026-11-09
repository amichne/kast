"""Cheap failures stop expensive effects; fixture Git cannot touch its parent repository."""

from __future__ import annotations

import io
import os
import subprocess
import tempfile
import unittest
from collections.abc import Mapping
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

import fast_checks as fast


class FastChecksTest(unittest.TestCase):
    def test_failed_check_stops_before_every_later_stage(self) -> None:
        def check(failure_at: int) -> None:
            consumed: list[tuple[str, ...]] = []

            def execute(command: tuple[str, ...], *, cwd: Path, env: Mapping[str, str]) -> int:
                self.assertLessEqual(len(consumed), failure_at, "effect after rejection")
                consumed.append(command)
                return 23 if len(consumed) == failure_at + 1 else 0

            with redirect_stdout(io.StringIO()) as output:
                self.assertEqual(23, fast.run(fast.Mode.FULL, fast.ROOT, {}, execute))
            self.assertEqual(failure_at + 1, len(consumed))
            self.assertIn("PROCESS_FAILED exit=23", output.getvalue())
            self.assertNotIn("product STARTED", output.getvalue())

        for failure_at in range(4):
            with self.subTest(failure_at=failure_at):
                check(failure_at)

    def test_modes_keep_complete_checks_and_only_product_receives_caller_environment(self) -> None:
        def check(mode: fast.Mode, expected_count: int) -> None:
            observed: list[tuple[tuple[str, ...], dict[str, str]]] = []
            caller = {
                "JAVA_HOME": "project-jdk",
                "GH_TOKEN": "fixture-only",
                "GIT_DIR": "parent",
            }

            def execute(command: tuple[str, ...], *, cwd: Path, env: Mapping[str, str]) -> int:
                self.assertEqual(fast.ROOT, cwd)
                observed.append((command, dict(env)))
                return 7 if command[-1] == "distribution/release/run_product_gate.py" else 0

            with redirect_stdout(io.StringIO()):
                result = fast.run(mode, fast.ROOT, caller, execute)
            self.assertEqual(7 if mode is fast.Mode.FULL else 0, result)
            self.assertEqual(expected_count, len(observed))
            self.assertEqual(".github/scripts/ci", observed[0][0][-1])
            self.assertEqual(".github/scripts/release/test_build_candidate.py", observed[1][0][-1])
            self.assertEqual(
                (
                    "distribution/release/test_resolve_version.py",
                    "distribution/release/test_run_product_gate.py",
                    "distribution/release/test_ci_candidate.py",
                    "distribution/release/test_verify_environment.py",
                ),
                observed[2][0][-4:],
            )
            for _, environment in observed[: min(4, expected_count)]:
                self.assertTrue(set(caller).isdisjoint(environment))
                self.assertEqual(os.devnull, environment["GIT_CONFIG_GLOBAL"])
                self.assertFalse(Path(environment["HOME"]).exists(), "owned home was not removed")
            if expected_count >= 4:
                self.assertEqual("packaging/run-portable-tests.py", observed[3][0][-1])
            if mode is fast.Mode.FULL:
                self.assertEqual(caller, observed[-1][1])

        for mode, expected_count in (
            (fast.Mode.DECISIONS, 3),
            (fast.Mode.LOCAL, 4),
            (fast.Mode.FULL, 5),
        ):
            with self.subTest(mode=mode):
                check(mode, expected_count)

    def test_start_failure_and_signal_remain_failures(self) -> None:
        def check(result: int | OSError, expected: int, outcome: str) -> None:
            calls = 0

            def execute(command: tuple[str, ...], *, cwd: Path, env: Mapping[str, str]) -> int:
                nonlocal calls
                calls += 1
                self.assertEqual(1, calls, "effect after failure")
                if isinstance(result, OSError):
                    raise result
                return result

            with redirect_stdout(io.StringIO()) as output:
                self.assertEqual(expected, fast.run(fast.Mode.FULL, fast.ROOT, {}, execute))
            self.assertEqual(1, calls)
            self.assertIn(outcome, output.getvalue())

        for result, expected, outcome in (
            (FileNotFoundError(), 1, "START_FAILED"),
            (-15, 143, "PROCESS_FAILED"),
        ):
            with self.subTest(result=result):
                check(result, expected, outcome)

    def test_unknown_arguments_cannot_start_checks(self) -> None:
        for arguments in (["native"], ["pre-push"], ["--prod"], ["local", "extra"]):
            with self.subTest(arguments=arguments), patch.object(fast, "run") as run:
                with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as rejected:
                    fast.main(arguments)
                self.assertEqual(2, rejected.exception.code)
                run.assert_not_called()


class FastChecksProcessTest(unittest.TestCase):
    def fixture(self, root: Path, ci_test: str) -> None:
        tests = {
            ".github/scripts/ci/test_example.py": ci_test,
            ".github/scripts/release/test_build_candidate.py": "def test_candidate(): assert 2 + 3 == 5\n",
            "distribution/release/test_resolve_version.py": "def test_version(): assert 2 + 3 == 5\n",
            "distribution/release/test_run_product_gate.py": "def test_gate(): assert 2 + 3 == 5\n",
            "distribution/release/test_ci_candidate.py": "def test_candidate(): assert 2 + 3 == 5\n",
            "distribution/release/test_verify_environment.py": "def test_environment(): assert 2 + 3 == 5\n",
        }
        for relative, source in tests.items():
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(source)

    def test_empty_inventory_cannot_qualify_or_start_product(self) -> None:
        with tempfile.TemporaryDirectory(prefix="kast empty checks ") as directory:
            root = Path(directory)
            self.fixture(root, "# No selected tests.\n")
            self.assertEqual(5, fast.run(fast.Mode.FULL, root, {}))

    def test_real_decision_process_ignores_hook_git_state_and_user_test_filters(self) -> None:
        with tempfile.TemporaryDirectory(prefix="kast checks ; ") as directory:
            owned = Path(directory).resolve()
            parent = owned / "parent"
            parent.mkdir()
            environment = {
                "PATH": os.defpath,
                "HOME": str(owned),
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
            }

            def git(*arguments: str) -> bytes:
                return subprocess.check_output(("git", *arguments), cwd=parent, env=environment)

            git("init", "-q")
            (parent / "sentinel").write_text("parent-owned\n")
            git("add", "sentinel")
            git(
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "commit",
                "-qm",
                "parent",
            )
            before = git("rev-parse", "HEAD"), (parent / ".git/index").read_bytes()
            root = owned / "checkout with spaces"
            self.fixture(
                root,
                """import os, pathlib, subprocess, tempfile
def test_isolation():
    assert 'GIT_DIR' not in os.environ
    assert 'GIT_CONFIG_COUNT' not in os.environ
    assert 'GH_TOKEN' not in os.environ
    assert 'PYTEST_ADDOPTS' not in os.environ
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        def git(*args):
            return subprocess.check_output(('git', *args), cwd=root)
        git('init', '-q')
        (root / 'owned').write_text('fixture')
        git('add', '.')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
        assert git('ls-files').strip() == b'owned'
""",
            )
            caller = {
                **environment,
                "GIT_DIR": str(parent / ".git"),
                "GIT_WORK_TREE": str(parent),
                "GIT_INDEX_FILE": str(parent / ".git/index"),
                "GIT_COMMON_DIR": str(parent / ".git"),
                "GIT_CONFIG_COUNT": "1",
                "GIT_CONFIG_KEY_0": "commit.gpgsign",
                "GIT_CONFIG_VALUE_0": "true",
                "PYTEST_ADDOPTS": "-k nonexistent --import-mode=importlib",
                "GH_TOKEN": "fixture-only",
            }
            self.assertEqual(0, fast.run(fast.Mode.DECISIONS, root, caller))
            self.assertEqual(
                before, (git("rev-parse", "HEAD"), (parent / ".git/index").read_bytes())
            )
            self.assertEqual("parent-owned\n", (parent / "sentinel").read_text())
            self.assertEqual(b"", git("status", "--porcelain"))


if __name__ == "__main__":
    unittest.main()
