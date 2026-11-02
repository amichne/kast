#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = ["pytest==9.0.3", "jsonschema==4.26.0", "defusedxml==0.7.1"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Run cheap checks before GitHub release observation, Gradle, or native compilation."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Protocol

ROOT = Path(__file__).resolve().parents[3]


class Mode(StrEnum):
    LOCAL = "local"
    DECISIONS = "decisions"
    PRE_PUSH = "pre-push"


class Stage(StrEnum):
    CI_DECISIONS = "ci-decisions"
    CANDIDATE = "candidate"
    RELEASE_PREFLIGHT = "release-preflight"
    PORTABLE = "portable"
    PRODUCT = "product"


class Failure(StrEnum):
    PROCESS_FAILED = "PROCESS_FAILED"
    START_FAILED = "START_FAILED"


class Passed(StrEnum):
    CHECK = "PASSED"


@dataclass(frozen=True, slots=True)
class Rejected:
    failure: Failure
    exit_code: int

    def __post_init__(self) -> None:
        if not 1 <= self.exit_code <= 255:
            raise ValueError("a rejected check requires a nonzero shell exit code")


class Executor(Protocol):
    def __call__(self, command: tuple[str, ...], *, cwd: Path, env: Mapping[str, str]) -> int: ...


def execute(command: tuple[str, ...], *, cwd: Path, env: Mapping[str, str]) -> int:
    return subprocess.run(
        command, cwd=cwd, env=env, stdin=subprocess.DEVNULL, check=False
    ).returncode


def isolated_environment(owned: Path) -> dict[str, str]:
    """No inherited Git repository, credentials, user configuration, or pytest options."""
    for name in ("home", "tmp", "config", "data", "state", "cache"):
        (owned / name).mkdir(mode=0o700)
    return {
        "PATH": str(Path(sys.executable).parent) + os.pathsep + os.defpath,
        "HOME": str(owned / "home"),
        "TMPDIR": str(owned / "tmp"),
        "TMP": str(owned / "tmp"),
        "TEMP": str(owned / "tmp"),
        "XDG_CONFIG_HOME": str(owned / "config"),
        "XDG_DATA_HOME": str(owned / "data"),
        "XDG_STATE_HOME": str(owned / "state"),
        "XDG_CACHE_HOME": str(owned / "cache"),
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_TERMINAL_PROMPT": "0",
        "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
        "PYTHONDONTWRITEBYTECODE": "1",
        "PYTHONNOUSERSITE": "1",
        "LC_ALL": "C",
        "TZ": "UTC",
    }


def run_stage(
    stage: Stage,
    command: tuple[str, ...],
    root: Path,
    environment: Mapping[str, str],
    executor: Executor,
) -> Passed | Rejected:
    print(f"fast-checks: {stage.value} STARTED", flush=True)
    started = time.monotonic()
    try:
        code = executor(command, cwd=root, env=environment)
        # A signal remains a failed invocation when projected to a shell exit code.
        code = code if code >= 0 else 128 - code
        result = Passed.CHECK if code == 0 else Rejected(Failure.PROCESS_FAILED, code)
    except OSError:
        code, result = 1, Rejected(Failure.START_FAILED, 1)
    elapsed = round((time.monotonic() - started) * 1000)
    outcome = result.failure.value if isinstance(result, Rejected) else result.value
    print(f"fast-checks: {stage.value} {outcome} exit={code} elapsedMs={elapsed}", flush=True)
    return result


def run(
    mode: Mode,
    root: Path,
    environment: Mapping[str, str],
    executor: Executor = execute,
) -> int:
    # pytest runs the existing unittest cases and rejects an empty selection (exit 5).
    # Explicit configuration avoids user-wide import modes, plugins and test filters.
    pytest = (
        sys.executable,
        "-m",
        "pytest",
        "-c",
        os.devnull,
        "--rootdir",
        str(root),
        "--import-mode=prepend",
        "--strict-config",
        "--strict-markers",
        "-p",
        "no:cacheprovider",
        "-q",
    )
    decisions = (
        (Stage.CI_DECISIONS, (*pytest, ".github/scripts/ci")),
        (Stage.CANDIDATE, (*pytest, ".github/scripts/release/test_build_candidate.py")),
        (
            Stage.RELEASE_PREFLIGHT,
            (
                *pytest,
                "distribution/release/test_resolve_version.py",
                "distribution/release/test_run_product_gate.py",
                "distribution/release/test_ci_candidate.py",
                "distribution/release/test_verify_environment.py",
            ),
        ),
    )
    with tempfile.TemporaryDirectory(prefix="kast-fast-checks-") as temporary:
        isolated = isolated_environment(Path(temporary).resolve())
        for stage, command in decisions:
            result = run_stage(stage, command, root, isolated, executor)
            if isinstance(result, Rejected):
                return result.exit_code
        if mode is not Mode.DECISIONS:
            result = run_stage(
                Stage.PORTABLE,
                (sys.executable, "packaging/run-portable-tests.py"),
                root,
                isolated,
                executor,
            )
            if isinstance(result, Rejected):
                return result.exit_code
    if mode is Mode.PRE_PUSH:
        # Only the unchanged full gate receives the caller's project JDK and gh credentials.
        result = run_stage(
            Stage.PRODUCT,
            (sys.executable, "distribution/release/run_product_gate.py"),
            root,
            environment,
            executor,
        )
        return result.exit_code if isinstance(result, Rejected) else 0
    print("fast-checks: passed; source and native qualification were not run", flush=True)
    return 0


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument(
        "mode",
        type=Mode,
        choices=tuple(Mode),
        nargs="?",
        default=Mode.LOCAL,
        help="local: decisions and packaging (default); decisions: CI scope checks; "
        "pre-push: cheap checks followed by the full product gate",
    )
    args = parser.parse_args(arguments)
    return run(Mode(args.mode), ROOT, os.environ.copy())


if __name__ == "__main__":
    raise SystemExit(main())
