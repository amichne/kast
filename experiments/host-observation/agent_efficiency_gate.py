"""Separate locked readiness from bounded agent experiment test execution."""

from __future__ import annotations

import os
import selectors
import signal
import subprocess
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from types import FrameType

PHASE_SECONDS = 120
OUTPUT_BYTES = 64 * 1024


class Phase(StrEnum):
    PREPARATION = "LOCKED_PREPARATION"
    PYTEST = "PYTEST"
    NODE = "NODE_CONTRACTS"


class Outcome(StrEnum):
    PASSED = "PASSED"
    FAILED = "FAILED"
    TIMED_OUT = "TIMED_OUT"
    UNAVAILABLE = "UNAVAILABLE"
    CANCELLED = "CANCELLED"


@dataclass(frozen=True)
class Result:
    outcome: Outcome
    returncode: int | None
    output: str
    truncated: bool = False
    cleanup_failed: bool = False


def run_phase(
    command: tuple[str, ...],
    root: Path,
    environment: Mapping[str, str],
    seconds: float = PHASE_SECONDS,
) -> Result:
    """Drain output within fixed memory and time bounds; reap only this process group."""
    cancellation: int | None = None

    def record_cancellation(signum: int, _frame: FrameType | None) -> None:
        nonlocal cancellation
        cancellation = signum

    # Recording instead of raising also covers cancellation during process creation
    # and cleanup, before or after the child can be safely referenced.
    handlers = {
        signum: signal.signal(signum, record_cancellation)
        for signum in (signal.SIGINT, signal.SIGTERM)
    }
    try:
        process = subprocess.Popen(
            command,
            cwd=root,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    except OSError as error:
        for signum, handler in handlers.items():
            signal.signal(signum, handler)
        if cancellation is not None:
            signal.raise_signal(cancellation)
        return Result(Outcome.UNAVAILABLE, None, str(error)[:OUTPUT_BYTES])
    tail = bytearray()
    truncated = False
    outcome = Outcome.TIMED_OUT
    cleanup_failed = False
    deadline = time.monotonic() + seconds
    stream = process.stdout
    try:
        if stream is None:
            outcome = Outcome.UNAVAILABLE
        else:
            with selectors.DefaultSelector() as selector:
                selector.register(stream, selectors.EVENT_READ)
                ended = False
                while (remaining := deadline - time.monotonic()) > 0:
                    if cancellation is not None:
                        outcome = Outcome.CANCELLED
                        break
                    for _key, _events in selector.select(min(remaining, 0.05)):
                        block = os.read(stream.fileno(), OUTPUT_BYTES)
                        if block:
                            tail.extend(block)
                            if len(tail) > OUTPUT_BYTES:
                                del tail[:-OUTPUT_BYTES]
                                truncated = True
                        else:
                            selector.unregister(stream)
                            ended = True
                    code = process.poll()
                    if ended and code is not None:
                        outcome = Outcome.PASSED if code == 0 else Outcome.FAILED
                        break
    except OSError:
        outcome = Outcome.UNAVAILABLE
    finally:
        # Children can retain the pipe after their leader exits. The new session
        # gives this invocation ownership of the group, including those children.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except OSError:
            cleanup_failed = True
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            cleanup_failed = True
        if stream is not None:
            stream.close()
        for signum, handler in handlers.items():
            signal.signal(signum, handler)
    if cancellation is not None:
        outcome = Outcome.CANCELLED
        print(
            f"agent-efficiency outcome=CANCELLED signal={cancellation} "
            f"cleanupFailed={cleanup_failed}",
            flush=True,
        )
        signal.raise_signal(cancellation)
    return Result(
        outcome,
        process.returncode,
        tail.decode("utf-8", errors="replace"),
        truncated,
        cleanup_failed,
    )


Executor = Callable[[tuple[str, ...], Path, Mapping[str, str]], Result]


def run_checks(
    root: Path, uv: str, node: str, environment: Mapping[str, str], execute: Executor = run_phase
) -> tuple[tuple[Phase, Result], ...]:
    """Stop on readiness or test failure; test commands do not synchronize dependencies."""
    owned_environment = dict(environment)
    for name in (
        "VIRTUAL_ENV",
        "UV_NO_DEV",
        "UV_NO_GROUP",
        "UV_NO_DEFAULT_GROUPS",
        "UV_FROZEN",
        "UV_CONFIG_FILE",
        "UV_NO_CONFIG",
        "UV_PROJECT",
        "UV_WORKING_DIR",
        "PYTHONPATH",
        "PYTHONHOME",
        "PYTEST_ADDOPTS",
        "PYTEST_PLUGINS",
        "NODE_OPTIONS",
    ):
        owned_environment.pop(name, None)
    owned_environment["UV_PROJECT_ENVIRONMENT"] = str(root / ".venv")
    owned_environment["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] = "1"
    commands = (
        (Phase.PREPARATION, (uv, "sync", "--locked", "--group", "dev", "--project", str(root))),
        (
            Phase.PYTEST,
            (str(root / ".venv/bin/python"), "-m", "pytest", "-q", str(root / "efficiency-tests")),
        ),
        (
            Phase.NODE,
            (
                node,
                "--test",
                str(root / "agent_efficiency_session.test.mjs"),
                str(root / "agent_efficiency_limits.test.mjs"),
                str(root / "agent_efficiency_audit.test.mjs"),
            ),
        ),
    )
    results: list[tuple[Phase, Result]] = []
    for phase, command in commands:
        print(
            f"agent-efficiency phase={phase} state=STARTED budgetSeconds={PHASE_SECONDS}",
            flush=True,
        )
        result = execute(command, root, owned_environment)
        print(
            f"agent-efficiency phase={phase} outcome={result.outcome} "
            f"returncode={result.returncode} outputTruncated={result.truncated} "
            f"cleanupFailed={result.cleanup_failed}",
            flush=True,
        )
        results.append((phase, result))
        if result.outcome != Outcome.PASSED or result.cleanup_failed:
            break
    return tuple(results)
