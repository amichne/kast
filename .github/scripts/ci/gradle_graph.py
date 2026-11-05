"""Stream and bound the source gate's Gradle graph process on Linux and macOS."""

from __future__ import annotations

import codecs
import json
import os
import secrets
import signal
import subprocess
import time
from collections.abc import Callable
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

import gradle_diagnostics

HEARTBEAT_SECONDS = 15
POLL_SECONDS = 0.1
STOP_SECONDS = 5
REPORTS = (
    "task-graph.log",
    "task-graph-execution.json",
    "task-graph-process.txt",
    "task-graph-daemon-threads.txt",
)


@dataclass(frozen=True, slots=True)
class Budget:
    seconds: int = 600

    def __post_init__(self) -> None:
        if type(self.seconds) is not int or not 1 <= self.seconds <= 3600:
            raise ValueError("graph timeout must be an integer from 1 to 3600 seconds")


DEFAULT_BUDGET = Budget()


class Outcome(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    PROCESS_FAILED = "PROCESS_FAILED"
    START_FAILED = "START_FAILED"
    TIMED_OUT = "TIMED_OUT"
    CANCELLED = "CANCELLED"


class Diagnostic(StrEnum):
    NOT_REQUESTED = "NOT_REQUESTED"
    RETAINED = "RETAINED"
    UNAVAILABLE = "UNAVAILABLE"
    TIMED_OUT = "TIMED_OUT"


class Cleanup(StrEnum):
    NOT_REQUIRED = "NOT_REQUIRED"
    CLIENT_REAPED = "CLIENT_REAPED"
    UNCONFIRMED = "UNCONFIRMED"


class Cancellation(StrEnum):
    NOT_REQUESTED = "NOT_REQUESTED"
    INTERRUPT = "INTERRUPT"
    TERMINATE = "TERMINATE"


@dataclass(slots=True)
class Interruption:
    reason: Cancellation = Cancellation.NOT_REQUESTED

    def receive(self, number: int, _frame: object) -> None:
        match number:
            case signal.SIGINT:
                self.reason = Cancellation.INTERRUPT
            case signal.SIGTERM:
                self.reason = Cancellation.TERMINATE
            case _:
                raise ValueError("unexpected graph cancellation signal")


@dataclass(frozen=True, slots=True)
class Result:
    outcome: Outcome
    exitCode: int
    elapsedMilliseconds: int
    diagnostic: Diagnostic = Diagnostic.NOT_REQUESTED
    cleanup: Cleanup = Cleanup.NOT_REQUIRED
    daemonDiagnostic: gradle_diagnostics.Outcome = gradle_diagnostics.Outcome.NOT_REQUESTED

    def __post_init__(self) -> None:
        if not 0 <= self.exitCode <= 255 or (self.exitCode == 0) != (
            self.outcome is Outcome.SUCCEEDED
        ):
            raise ValueError("only a successful graph process has exit code zero")


class State(StrEnum):
    STARTED = "STARTED"
    RUNNING = "RUNNING"


@dataclass(frozen=True, slots=True)
class Progress:
    state: State
    processId: int
    elapsedMilliseconds: int
    quietMilliseconds: int
    deadlineSeconds: int


def process_snapshot(pid: int, destination: Path) -> Diagnostic:
    # No command arguments, environment, source payloads, or unrelated processes.
    try:
        observed = subprocess.run(
            ["ps", "-p", str(pid), "-o", "pid=,ppid=,pgid=,stat=,etime=,time=,rss="],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=2,
            check=False,
        )
        if observed.returncode != 0 or not observed.stdout.strip():
            return Diagnostic.UNAVAILABLE
        destination.write_bytes(b"pid ppid pgid state elapsed cpu rss\n" + observed.stdout[:4096])
    except subprocess.TimeoutExpired:
        return Diagnostic.TIMED_OUT
    except OSError:
        return Diagnostic.UNAVAILABLE
    return Diagnostic.RETAINED


def stop_group(process: subprocess.Popen[bytes]) -> Cleanup:
    # This session belongs to this invocation. Never use Gradle --stop or select by name.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=STOP_SECONDS)
    except subprocess.TimeoutExpired:
        pass
    finally:
        # Retire descendants even if the client already exited after SIGTERM.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=STOP_SECONDS)
        return Cleanup.CLIENT_REAPED
    except subprocess.TimeoutExpired:
        return Cleanup.UNCONFIRMED


def run(
    command: tuple[str, ...],
    root: Path,
    destination: Path,
    budget: Budget = DEFAULT_BUDGET,
    *,
    clock: Callable[[], float] = time.monotonic,
) -> Result:
    """Own one graph client; a retained partial graph is never a successful result."""
    destination.mkdir(parents=True, exist_ok=True)
    for name in REPORTS:
        (destination / name).unlink(missing_ok=True)
    started = clock()
    interruption = Interruption()
    owner = secrets.token_hex(16)
    daemon_pids = gradle_diagnostics.DaemonPids()

    def finish(
        outcome: Outcome,
        code: int,
        diagnostic: Diagnostic = Diagnostic.NOT_REQUESTED,
        cleanup: Cleanup = Cleanup.NOT_REQUIRED,
        daemon_diagnostic: gradle_diagnostics.Outcome = gradle_diagnostics.Outcome.NOT_REQUESTED,
    ) -> Result:
        result = Result(
            outcome,
            code,
            round((clock() - started) * 1000),
            diagnostic,
            cleanup,
            daemon_diagnostic,
        )
        encoded = json.dumps(asdict(result))
        (destination / "task-graph-execution.json").write_text(encoded + "\n")
        print(f"source-validation: task-graph {encoded}", flush=True)
        return result

    previous = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM)}
    try:
        for number in previous:
            signal.signal(number, interruption.receive)
        with (destination / "task-graph.log").open("wb") as output:
            try:
                process = subprocess.Popen(
                    command,
                    cwd=root,
                    stdin=subprocess.DEVNULL,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                    env={**os.environ, gradle_diagnostics.OWNER_KEY: owner},
                )
            except OSError:
                return finish(Outcome.START_FAILED, 1)
            cleanup = Cleanup.NOT_REQUIRED
            try:
                with (destination / "task-graph.log").open("rb") as reader:
                    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
                    last_output = started

                    def feedback(state: State, now: float) -> None:
                        progress = Progress(
                            state,
                            process.pid,
                            round((now - started) * 1000),
                            round((now - last_output) * 1000),
                            budget.seconds,
                        )
                        print(
                            f"source-validation: task-graph {json.dumps(asdict(progress))}",
                            flush=True,
                        )

                    def stream() -> None:
                        nonlocal last_output
                        block = reader.read(65536)
                        if block:
                            daemon_pids.observe(block)
                            last_output = clock()
                            print(decoder.decode(block), end="", flush=True)

                    feedback(State.STARTED, started)
                    heartbeat = started + HEARTBEAT_SECONDS
                    while True:
                        stream()
                        now = clock()
                        if (
                            interruption.reason is not Cancellation.NOT_REQUESTED
                            or now - started >= budget.seconds
                        ):
                            diagnostic = process_snapshot(
                                process.pid, destination / "task-graph-process.txt"
                            )
                            try:
                                java = os.environ.get("JAVA_HOME")
                                daemon_diagnostic = gradle_diagnostics.snapshot(
                                    daemon_pids,
                                    destination / "task-graph-daemon-threads.txt",
                                    owner,
                                    Path(java) if java else None,
                                )
                            except Exception:
                                # Optional evidence must never replace the timeout or its cleanup.
                                daemon_diagnostic = gradle_diagnostics.Outcome.UNAVAILABLE
                            finally:
                                cleanup = stop_group(process)
                            # Drain a fixed extent: descendants cannot keep the reader alive.
                            end = os.fstat(reader.fileno()).st_size
                            while reader.tell() < end:
                                stream()
                            print(decoder.decode(b"", final=True), end="", flush=True)
                            match interruption.reason:
                                case Cancellation.INTERRUPT:
                                    return finish(
                                        Outcome.CANCELLED,
                                        130,
                                        diagnostic,
                                        cleanup,
                                        daemon_diagnostic,
                                    )
                                case Cancellation.TERMINATE:
                                    return finish(
                                        Outcome.CANCELLED,
                                        143,
                                        diagnostic,
                                        cleanup,
                                        daemon_diagnostic,
                                    )
                                case Cancellation.NOT_REQUESTED:
                                    return finish(
                                        Outcome.TIMED_OUT,
                                        124,
                                        diagnostic,
                                        cleanup,
                                        daemon_diagnostic,
                                    )
                        code = process.poll()
                        if code is not None:
                            end = os.fstat(reader.fileno()).st_size
                            while reader.tell() < end:
                                stream()
                            print(decoder.decode(b"", final=True), end="", flush=True)
                            return finish(
                                Outcome.SUCCEEDED if code == 0 else Outcome.PROCESS_FAILED,
                                code if code >= 0 else 128 - code,
                            )
                        if now >= heartbeat:
                            feedback(State.RUNNING, now)
                            heartbeat = now + HEARTBEAT_SECONDS
                        try:
                            process.wait(
                                timeout=min(POLL_SECONDS, budget.seconds - (now - started))
                            )
                        except subprocess.TimeoutExpired:
                            pass
            finally:
                if cleanup is Cleanup.NOT_REQUIRED and process.poll() is None:
                    stop_group(process)
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)
