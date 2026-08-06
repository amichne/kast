#!/usr/bin/env python3
"""Bounded, atomic process authority for release indexing benchmarks."""

from __future__ import annotations

import argparse
import errno
import json
import os
import select
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import NoReturn, Sequence


SCHEMA_VERSION = 1
TYPE_COMMAND = "KAST_BENCHMARK_SUPERVISED_COMMAND"
TYPE_CAPTURE_GAP = "KAST_BENCHMARK_CAPTURE_GAP_PROOF"
TYPE_OWNED = "KAST_BENCHMARK_OWNED_PROCESSES"
STABLE_CONFIRMATION_PASSES = 2
CLOSURE_PROOF_RESERVE_MILLIS = 1_000
COMMAND_ADMISSION_RESERVE_MILLIS = 1_000
TIMEOUT_PHASE_PRE_SPAWN = "PRE_SPAWN"


class PreSpawnTimeoutReason(Enum):
    DEADLINE_EXPIRED = "DEADLINE_EXPIRED"
    INSUFFICIENT_CLEANUP_RESERVE = "INSUFFICIENT_CLEANUP_RESERVE"
    INSUFFICIENT_ADMISSION_BUDGET = "INSUFFICIENT_ADMISSION_BUDGET"

    @property
    def detail(self) -> str:
        if self is PreSpawnTimeoutReason.DEADLINE_EXPIRED:
            return "command deadline expired before spawn"
        if self is PreSpawnTimeoutReason.INSUFFICIENT_CLEANUP_RESERVE:
            return "insufficient cleanup reserve before spawn"
        return "insufficient command admission budget before spawn"


def fail(message: str, exit_code: int = 2) -> NoReturn:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(exit_code)


def monotonic_millis() -> int:
    return time.monotonic_ns() // 1_000_000


def epoch_millis() -> int:
    return time.time_ns() // 1_000_000


def positive_millis(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def nonnegative_millis(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be a non-negative integer")
    return parsed


def required_cleanup_reserve_millis(
    *,
    term_grace_millis: int,
    kill_grace_millis: int,
) -> int:
    return (
        term_grace_millis
        + kill_grace_millis
        + CLOSURE_PROOF_RESERVE_MILLIS
    )


def required_admission_budget_millis(
    *,
    term_grace_millis: int,
    kill_grace_millis: int,
) -> int:
    return required_cleanup_reserve_millis(
        term_grace_millis=term_grace_millis,
        kill_grace_millis=kill_grace_millis,
    ) + COMMAND_ADMISSION_RESERVE_MILLIS


def classify_pre_spawn_timeout(
    *,
    remaining_budget_millis: int,
    required_cleanup_millis: int,
    required_admission_millis: int,
) -> PreSpawnTimeoutReason | None:
    if remaining_budget_millis == 0:
        return PreSpawnTimeoutReason.DEADLINE_EXPIRED
    if remaining_budget_millis <= required_cleanup_millis:
        return PreSpawnTimeoutReason.INSUFFICIENT_CLEANUP_RESERVE
    if remaining_budget_millis <= required_admission_millis:
        return PreSpawnTimeoutReason.INSUFFICIENT_ADMISSION_BUDGET
    return None


def require_linux_pidfd() -> None:
    if sys.platform != "linux":
        fail("production benchmark supervision requires Linux pidfd support")
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        fail("Python runtime does not expose required Linux pidfd operations")


def append_json_line(path: str | None, payload: dict[str, object]) -> None:
    if path is None:
        return
    encoded = (json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n").encode()
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(destination, os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
    try:
        os.write(descriptor, encoded)
    finally:
        os.close(descriptor)


def write_json(path: str, payload: dict[str, object]) -> None:
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.next")
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, destination)


def normalized_exit_code(return_code: int) -> int:
    return 128 + (-return_code) if return_code < 0 else return_code


def proc_start_identity(pid: int) -> str | None:
    try:
        raw = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    except (FileNotFoundError, PermissionError, OSError, UnicodeDecodeError):
        return None
    command_end = raw.rfind(")")
    fields = raw[command_end + 2 :].split()
    if command_end < 0 or len(fields) <= 19:
        return None
    return f"proc-start-ticks:{fields[19]}"


def portable_start_identity(pid: int) -> str | None:
    if sys.platform == "linux":
        return proc_start_identity(pid)
    result = subprocess.run(
        ["ps", "-p", str(pid), "-o", "lstart="],
        check=False,
        capture_output=True,
        text=True,
        timeout=2,
    )
    started = " ".join(result.stdout.split())
    if result.returncode == 0 and started:
        return f"ps-lstart:{started}"
    return None


def pidfd_is_ready(pidfd: int) -> bool:
    poller = select.poll()
    poller.register(pidfd, select.POLLIN | select.POLLHUP | select.POLLERR)
    return bool(poller.poll(0))


def wait_pidfd(pidfd: int, deadline_monotonic_ms: int) -> bool:
    remaining = max(deadline_monotonic_ms - monotonic_millis(), 0)
    poller = select.poll()
    poller.register(pidfd, select.POLLIN | select.POLLHUP | select.POLLERR)
    return bool(poller.poll(remaining))


@dataclass(frozen=True)
class TestSignalBoundary:
    sourced: bool
    mode: bool
    allowed: bool
    helper: str | None

    @property
    def enabled(self) -> bool:
        return self.sourced and self.mode and self.allowed and self.helper is not None

    def validate(self) -> None:
        requested = self.helper is not None or self.mode or self.allowed
        if requested and not self.enabled:
            fail("test signal helper requires sourced mode and both explicit test opt-ins")
        if self.helper is not None and not os.access(self.helper, os.X_OK):
            fail("test signal helper is not executable")


@dataclass
class TestControl:
    enabled: bool
    closure_snapshot_file: Path | None
    closure_release_file: Path | None
    closure_barrier_phase: str | None
    capture_pidfd_errno: int | None
    capture_pid_file: Path | None
    parent_identity_mismatch_pid_file: Path | None
    closure_barrier_triggered: bool = False

    @classmethod
    def from_args(
        cls,
        args: argparse.Namespace,
        boundary: TestSignalBoundary,
    ) -> TestControl:
        snapshot = getattr(args, "test_closure_snapshot_file", None)
        release = getattr(args, "test_closure_release_file", None)
        barrier_phase = getattr(args, "test_closure_barrier_phase", None)
        capture_error = getattr(args, "test_capture_pidfd_error", None)
        capture_pid_file = getattr(args, "test_capture_pid_file", None)
        mismatch_file = getattr(args, "test_parent_identity_mismatch_pid_file", None)
        requested = any(
            value is not None
            for value in (
                snapshot,
                release,
                barrier_phase,
                capture_error,
                capture_pid_file,
                mismatch_file,
            )
        )
        if requested and not boundary.enabled:
            fail("process-capture fault injection requires the complete test boundary")
        if (snapshot is None) != (release is None) or (
            snapshot is not None and barrier_phase is None
        ):
            fail("closure barrier requires snapshot, release, and phase")
        if (capture_error is None) != (capture_pid_file is None):
            fail("capture pidfd fault requires an error and exact PID file")
        capture_errno = None
        if capture_error is not None:
            capture_errno = {"EMFILE": errno.EMFILE, "EPERM": errno.EPERM}[capture_error]
        return cls(
            enabled=boundary.enabled,
            closure_snapshot_file=None if snapshot is None else Path(snapshot),
            closure_release_file=None if release is None else Path(release),
            closure_barrier_phase=barrier_phase,
            capture_pidfd_errno=capture_errno,
            capture_pid_file=None if capture_pid_file is None else Path(capture_pid_file),
            parent_identity_mismatch_pid_file=(
                None if mismatch_file is None else Path(mismatch_file)
            ),
        )

    @classmethod
    def disabled(cls) -> TestControl:
        return cls(False, None, None, None, None, None, None)

    @staticmethod
    def read_pid(path: Path | None) -> int | None:
        if path is None:
            return None
        try:
            return int(path.read_text(encoding="utf-8").strip())
        except (FileNotFoundError, OSError, ValueError):
            return None

    def injected_pidfd_error(self, pid: int) -> OSError | None:
        if self.capture_pidfd_errno is None:
            return None
        if self.read_pid(self.capture_pid_file) != pid:
            return None
        code = self.capture_pidfd_errno
        return OSError(code, f"injected {errno.errorcode[code]} for captured process")

    def parent_identity_matches(self, pid: int) -> bool:
        return self.read_pid(self.parent_identity_mismatch_pid_file) != pid

    def after_snapshot(self, phase: str, deadline_monotonic_ms: int) -> None:
        if (
            self.closure_barrier_triggered
            or self.closure_barrier_phase != phase
            or self.closure_snapshot_file is None
            or self.closure_release_file is None
        ):
            return
        self.closure_barrier_triggered = True
        self.closure_snapshot_file.parent.mkdir(parents=True, exist_ok=True)
        self.closure_snapshot_file.touch()
        while not self.closure_release_file.is_file():
            if monotonic_millis() >= deadline_monotonic_ms:
                raise TimeoutError("test closure barrier exceeded the absolute deadline")
            time.sleep(0.005)


def test_boundary(args: argparse.Namespace) -> TestSignalBoundary:
    boundary = TestSignalBoundary(
        sourced=args.test_sourced,
        mode=args.test_mode,
        allowed=args.test_allow_signal_helper,
        helper=args.test_signal_helper,
    )
    boundary.validate()
    return boundary


def signal_test_child(
    boundary: TestSignalBoundary,
    signal_name: str,
    pid: int,
    expected_identity: str,
    deadline_monotonic_ms: int,
) -> None:
    if not boundary.enabled:
        fail("non-Linux signaling is available only to a sourced focused contract")
    assert boundary.helper is not None
    remaining_seconds = max(
        (deadline_monotonic_ms - monotonic_millis()) / 1000.0,
        0.001,
    )
    try:
        result = subprocess.run(
            [boundary.helper, signal_name, str(pid), expected_identity],
            check=False,
            timeout=min(2.0, remaining_seconds),
        )
    except subprocess.TimeoutExpired:
        fail("test signal helper exceeded its own deadline", 125)
    if result.returncode != 0:
        fail(f"test signal helper failed with exit {result.returncode}", 125)


def command_event(
    *,
    args: argparse.Namespace,
    started_epoch_ms: int,
    started_monotonic_ms: int,
    finished_epoch_ms: int,
    finished_monotonic_ms: int,
    pid: int | None,
    capture_identity: str | None,
    pidfd_opened: bool,
    outcome: str,
    exit_code: int,
    term_sent: bool,
    kill_sent: bool,
    process_group_closure: dict[str, object],
    detail: str | None = None,
) -> dict[str, object]:
    payload: dict[str, object] = {
        "schemaVersion": SCHEMA_VERSION,
        "type": TYPE_COMMAND,
        "operation": args.operation,
        "executable": Path(args.command[0]).name if args.command else None,
        "pid": pid,
        "captureIdentity": capture_identity,
        "pidfdOpenedBeforeWait": pidfd_opened,
        "startedAtEpochMillis": started_epoch_ms,
        "finishedAtEpochMillis": finished_epoch_ms,
        "startedAtMonotonicMillis": started_monotonic_ms,
        "finishedAtMonotonicMillis": finished_monotonic_ms,
        "durationMillis": max(finished_monotonic_ms - started_monotonic_ms, 0),
        "deadlineMonotonicMillis": args.deadline_monotonic_ms,
        "outcome": outcome,
        "exitCode": exit_code,
        "termination": {"termSent": term_sent, "killSent": kill_sent},
        "processGroupClosure": process_group_closure,
    }
    if detail is not None:
        payload["detail"] = detail
    return payload


def no_process_group_closure() -> dict[str, object]:
    payload: dict[str, object] = {
        "required": False,
        "proven": None,
        "pidfdsRetained": False,
        "capturedProcesses": [],
        "remainingProcesses": [],
        "recapturePasses": 0,
        "stableConfirmationPasses": 0,
    }
    return payload


def unreaped_linux_exit_code(process: subprocess.Popen[bytes]) -> int | None:
    observation = os.waitid(
        os.P_PID,
        process.pid,
        os.WEXITED | os.WNOHANG | os.WNOWAIT,
    )
    if observation is None:
        return None
    if observation.si_code == os.CLD_EXITED:
        return int(observation.si_status)
    if observation.si_code in {os.CLD_KILLED, os.CLD_DUMPED}:
        return 128 + int(observation.si_status)
    return None


class ExecutionComplete(Exception):
    """Internal control flow after one supervised execution has a final state."""


def mark_evidence_persistence_failure(
    event: dict[str, object],
    label: str,
    error: OSError | ValueError,
) -> str:
    message = f"could not persist {label} evidence: {error}"
    previous_detail = event.get("detail")
    event["detail"] = (
        f"{previous_detail}; {message}"
        if isinstance(previous_detail, str) and previous_detail
        else message
    )
    event["outcome"] = "SUPERVISION_FAILED"
    event["exitCode"] = 125
    return message


def persist_command_event(
    args: argparse.Namespace,
    event: dict[str, object],
) -> int:
    failures: list[str] = []
    result_written = False
    if args.result_json is not None:
        try:
            write_json(args.result_json, event)
            result_written = True
        except (OSError, ValueError) as error:
            failures.append(mark_evidence_persistence_failure(event, "result", error))

    if args.event_log is not None:
        try:
            append_json_line(args.event_log, event)
        except (OSError, ValueError) as error:
            failures.append(mark_evidence_persistence_failure(event, "event", error))
            if result_written and args.result_json is not None:
                try:
                    write_json(args.result_json, event)
                except (OSError, ValueError) as correction_error:
                    failures.append(mark_evidence_persistence_failure(
                        event,
                        "corrected result",
                        correction_error,
                    ))

    for failure in failures:
        print(f"error: could not persist supervisor evidence: {failure}", file=sys.stderr)
    return 125 if failures else int(event["exitCode"])


def persist_pre_spawn_timeout(
    *,
    args: argparse.Namespace,
    started_epoch_ms: int,
    started_monotonic_ms: int,
    reason: PreSpawnTimeoutReason,
    remaining_budget_millis: int,
    required_cleanup_millis: int,
) -> int:
    required_admission_millis = required_admission_budget_millis(
        term_grace_millis=args.term_grace_millis,
        kill_grace_millis=args.kill_grace_millis,
    )
    event = command_event(
        args=args,
        started_epoch_ms=started_epoch_ms,
        started_monotonic_ms=started_monotonic_ms,
        finished_epoch_ms=epoch_millis(),
        finished_monotonic_ms=monotonic_millis(),
        pid=None,
        capture_identity=None,
        pidfd_opened=False,
        outcome="TIMED_OUT",
        exit_code=124,
        term_sent=False,
        kill_sent=False,
        process_group_closure=no_process_group_closure(),
        detail=reason.detail,
    )
    event.update({
        "timeoutPhase": TIMEOUT_PHASE_PRE_SPAWN,
        "timeoutReason": reason.value,
        "remainingBudgetMillis": remaining_budget_millis,
        "requiredCleanupReserveMillis": required_cleanup_millis,
        "termGraceMillis": args.term_grace_millis,
        "killGraceMillis": args.kill_grace_millis,
        "closureProofReserveMillis": CLOSURE_PROOF_RESERVE_MILLIS,
        "admissionReserveMillis": COMMAND_ADMISSION_RESERVE_MILLIS,
        "requiredAdmissionBudgetMillis": required_admission_millis,
    })
    return persist_command_event(args, event)


def run_command(args: argparse.Namespace) -> int:
    if args.command and args.command[0] == "--":
        args.command = args.command[1:]
    if not args.command:
        fail("supervised run requires a command after --")
    boundary = test_boundary(args)
    control = TestControl.from_args(args, boundary)
    if args.test_fail_pidfd_open and not boundary.enabled:
        fail("pidfd-open fault injection requires the complete test boundary")
    if args.test_pidfd_open_failure_ready_file and not args.test_fail_pidfd_open:
        fail("pidfd-open fault readiness requires pidfd-open fault injection")
    if sys.platform == "linux":
        require_linux_pidfd()
    elif not boundary.enabled:
        fail("production benchmark supervision requires Linux pidfd support")

    started_epoch_ms = epoch_millis()
    started_monotonic_ms = monotonic_millis()
    required_cleanup_millis = required_cleanup_reserve_millis(
        term_grace_millis=args.term_grace_millis,
        kill_grace_millis=args.kill_grace_millis,
    )
    available_millis = max(args.deadline_monotonic_ms - started_monotonic_ms, 0)
    required_admission_millis = required_admission_budget_millis(
        term_grace_millis=args.term_grace_millis,
        kill_grace_millis=args.kill_grace_millis,
    )
    pre_spawn_timeout = classify_pre_spawn_timeout(
        remaining_budget_millis=available_millis,
        required_cleanup_millis=required_cleanup_millis,
        required_admission_millis=required_admission_millis,
    )
    if pre_spawn_timeout is not None:
        return persist_pre_spawn_timeout(
            args=args,
            started_epoch_ms=started_epoch_ms,
            started_monotonic_ms=started_monotonic_ms,
            reason=pre_spawn_timeout,
            remaining_budget_millis=available_millis,
            required_cleanup_millis=required_cleanup_millis,
        )
    cleanup_reserve = required_cleanup_millis
    termination_start_monotonic_ms = max(
        started_monotonic_ms,
        args.deadline_monotonic_ms - cleanup_reserve,
    )
    process: subprocess.Popen[bytes] | None = None
    pidfd: int | None = None
    capture_identity: str | None = None
    term_sent = False
    kill_sent = False
    outcome = "SUPERVISION_FAILED"
    exit_code = 125
    detail: str | None = None
    process_group_closure = no_process_group_closure()
    try:
        try:
            process = subprocess.Popen(args.command, start_new_session=True)
        except OSError as error:
            detail = f"spawn failed: {error}"
            raise ExecutionComplete

        if sys.platform == "linux":
            try:
                # Popen remains the unreaped parent. Its direct child's PID cannot
                # be reused before this pidfd is opened.
                if args.test_fail_pidfd_open:
                    if args.test_pidfd_open_failure_ready_file is not None:
                        ready_file = Path(args.test_pidfd_open_failure_ready_file)
                        while (
                            not ready_file.is_file()
                            and monotonic_millis() < termination_start_monotonic_ms
                        ):
                            time.sleep(0.01)
                    raise OSError(errno.EMFILE, "injected pidfd_open failure")
                pidfd = os.pidfd_open(process.pid, 0)
            except OSError as error:
                detail = f"pidfd_open failed: {error}"
                capture_identity = proc_start_identity(process.pid)
                process_group_closure = terminate_linux_process_group(
                    process=process,
                    leader_pidfd=None,
                    leader_start_identity=capture_identity,
                    term_grace_millis=args.term_grace_millis,
                    kill_grace_millis=args.kill_grace_millis,
                    deadline_monotonic_ms=args.deadline_monotonic_ms,
                    test_control=control,
                )
                term_sent = bool(process_group_closure["termSent"])
                kill_sent = bool(process_group_closure["killSent"])
                if unreaped_linux_exit_code(process) is not None:
                    process.wait()
                if not process_group_closure["proven"]:
                    detail += "; stable process-group closure was not proven"
                raise ExecutionComplete
            capture_identity = proc_start_identity(process.pid)
            if wait_pidfd(pidfd, termination_start_monotonic_ms):
                observed_exit_code = unreaped_linux_exit_code(process)
                if observed_exit_code == 0:
                    exit_code = normalized_exit_code(process.wait())
                    outcome = "SUCCEEDED"
                    raise ExecutionComplete
                process_group_closure = terminate_linux_process_group(
                    process=process,
                    leader_pidfd=pidfd,
                    leader_start_identity=capture_identity,
                    term_grace_millis=args.term_grace_millis,
                    kill_grace_millis=args.kill_grace_millis,
                    deadline_monotonic_ms=args.deadline_monotonic_ms,
                    test_control=control,
                )
                term_sent = bool(process_group_closure["termSent"])
                kill_sent = bool(process_group_closure["killSent"])
                if unreaped_linux_exit_code(process) is not None:
                    exit_code = normalized_exit_code(process.wait())
                elif observed_exit_code is not None:
                    exit_code = observed_exit_code
                if process_group_closure["proven"]:
                    outcome = "FAILED"
                else:
                    outcome = "SUPERVISION_FAILED"
                    exit_code = 125
                    detail = "failed command process-group closure was not proven"
                raise ExecutionComplete
        else:
            capture_identity = portable_start_identity(process.pid)
            if capture_identity is None:
                detail = "test child start identity is unavailable"
                raise ExecutionComplete
            remaining_seconds = max(
                (termination_start_monotonic_ms - monotonic_millis()) / 1000.0,
                0.0,
            )
            try:
                exit_code = normalized_exit_code(process.wait(timeout=remaining_seconds))
                outcome = "SUCCEEDED" if exit_code == 0 else "FAILED"
                raise ExecutionComplete
            except subprocess.TimeoutExpired:
                pass

        if pidfd is not None:
            process_group_closure = terminate_linux_process_group(
                process=process,
                leader_pidfd=pidfd,
                leader_start_identity=capture_identity,
                term_grace_millis=args.term_grace_millis,
                kill_grace_millis=args.kill_grace_millis,
                deadline_monotonic_ms=args.deadline_monotonic_ms,
                test_control=control,
            )
            term_sent = bool(process_group_closure["termSent"])
            kill_sent = bool(process_group_closure["killSent"])
            if unreaped_linux_exit_code(process) is not None:
                process.wait()
            if process_group_closure["proven"]:
                outcome = "TIMED_OUT"
                exit_code = 124
            else:
                outcome = "SUPERVISION_FAILED"
                exit_code = 125
                detail = "timed-out command process-group closure was not proven"
            raise ExecutionComplete
        else:
            term_sent = True
            assert capture_identity is not None
            signal_test_child(
                boundary,
                "TERM",
                process.pid,
                capture_identity,
                args.deadline_monotonic_ms,
            )
            try:
                process.wait(timeout=max(min(
                    args.term_grace_millis / 1000.0,
                    (args.deadline_monotonic_ms - monotonic_millis()) / 1000.0,
                ), 0.0))
                outcome = "TIMED_OUT"
                exit_code = 124
                raise ExecutionComplete
            except subprocess.TimeoutExpired:
                pass

        kill_sent = True
        assert capture_identity is not None
        signal_test_child(
            boundary,
            "KILL",
            process.pid,
            capture_identity,
            args.deadline_monotonic_ms,
        )
        try:
            process.wait(timeout=max(min(
                args.kill_grace_millis / 1000.0,
                (args.deadline_monotonic_ms - monotonic_millis()) / 1000.0,
            ), 0.0))
        except subprocess.TimeoutExpired:
            detail = "test child remained live after TERM and KILL"
            raise ExecutionComplete
        outcome = "TIMED_OUT"
        exit_code = 124
        raise ExecutionComplete
    except ExecutionComplete:
        pass
    except (OSError, ValueError) as error:
        detail = f"supervision failed: {error}"
    finally:
        if pidfd is not None:
            os.close(pidfd)

    finished_epoch_ms = epoch_millis()
    finished_monotonic_ms = monotonic_millis()
    if (
        finished_monotonic_ms > args.deadline_monotonic_ms
        and outcome != "SUPERVISION_FAILED"
    ):
        outcome = "SUPERVISION_FAILED"
        exit_code = 125
        detail = "supervision exhausted the absolute command deadline"
    event = command_event(
        args=args,
        started_epoch_ms=started_epoch_ms,
        started_monotonic_ms=started_monotonic_ms,
        finished_epoch_ms=finished_epoch_ms,
        finished_monotonic_ms=finished_monotonic_ms,
        pid=None if process is None else process.pid,
        capture_identity=capture_identity,
        pidfd_opened=pidfd is not None,
        outcome=outcome,
        exit_code=exit_code,
        term_sent=term_sent,
        kill_sent=kill_sent,
        process_group_closure=process_group_closure,
        detail=detail,
    )
    return persist_command_event(args, event)


@dataclass
class OwnedProcess:
    pid: int
    parent_pid: int
    start_identity: str
    pidfd: int

    def close(self) -> None:
        os.close(self.pidfd)


@dataclass
class StableConfirmation:
    passes: int = 0

    def observe(self, *, empty: bool, captured_new_process: bool) -> bool:
        if empty and not captured_new_process:
            self.passes += 1
        else:
            self.passes = 0
        return self.passes >= STABLE_CONFIRMATION_PASSES


def capture_error_evidence(error: OSError | TimeoutError) -> dict[str, object]:
    if isinstance(error, OSError) and error.errno is not None:
        code = errno.errorcode.get(error.errno, f"ERRNO_{error.errno}")
    else:
        code = "DEADLINE_EXCEEDED"
    return {"code": code, "detail": str(error)}


def proc_parent_and_start(pid: int) -> tuple[int, str] | None:
    try:
        raw = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    except (FileNotFoundError, PermissionError, OSError, UnicodeDecodeError):
        return None
    command_end = raw.rfind(")")
    fields = raw[command_end + 2 :].split()
    if command_end < 0 or len(fields) <= 19:
        return None
    try:
        parent = int(fields[1])
    except ValueError:
        return None
    return parent, f"proc-start-ticks:{fields[19]}"


def proc_group_and_start(pid: int) -> tuple[int, str] | None:
    try:
        raw = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    except (FileNotFoundError, PermissionError, OSError, UnicodeDecodeError):
        return None
    command_end = raw.rfind(")")
    fields = raw[command_end + 2 :].split()
    if command_end < 0 or len(fields) <= 19:
        return None
    try:
        process_group = int(fields[2])
    except ValueError:
        return None
    return process_group, f"proc-start-ticks:{fields[19]}"


def proc_has_marker(pid: int, marker: bytes) -> bool:
    try:
        values = Path(f"/proc/{pid}/environ").read_bytes().split(b"\0")
    except (FileNotFoundError, PermissionError, OSError):
        return False
    return marker in values


def open_captured_pidfd(pid: int, test_control: TestControl) -> int:
    injected = test_control.injected_pidfd_error(pid)
    if injected is not None:
        raise injected
    return os.pidfd_open(pid, 0)


def capture_linux_process(
    pid: int,
    marker: bytes,
    parents: dict[int, OwnedProcess],
    test_control: TestControl,
) -> OwnedProcess | None:
    if pid in {os.getpid(), os.getppid()}:
        return None
    before = proc_parent_and_start(pid)
    if before is None:
        return None
    parent, start_identity = before
    retained_parent = parents.get(parent)
    marker_owned = proc_has_marker(pid, marker)
    parent_owned = (
        retained_parent is not None
        and test_control.parent_identity_matches(parent)
        and retained_process_is_live(retained_parent)
    )
    if not marker_owned and not parent_owned:
        return None
    try:
        pidfd = open_captured_pidfd(pid, test_control)
    except ProcessLookupError:
        return None
    captured: OwnedProcess | None = None
    try:
        if pidfd_is_ready(pidfd):
            return None
        after = proc_parent_and_start(pid)
        retained_parent = parents.get(parent)
        still_owned = proc_has_marker(pid, marker) or (
            retained_parent is not None
            and test_control.parent_identity_matches(parent)
            and retained_process_is_live(retained_parent)
        )
        if not still_owned or after != before or pidfd_is_ready(pidfd):
            return None
        captured = OwnedProcess(pid, parent, start_identity, pidfd)
        return captured
    finally:
        if captured is None:
            os.close(pidfd)


def capture_owned_linux(
    marker: str,
    deadline_monotonic_ms: int,
    test_control: TestControl | None = None,
) -> dict[tuple[int, str], OwnedProcess]:
    require_linux_pidfd()
    control = test_control or TestControl.disabled()
    marker_bytes = f"KAST_BENCHMARK_RUN_ID={marker}".encode()
    captured: dict[tuple[int, str], OwnedProcess] = {}
    try:
        changed = True
        while changed:
            if monotonic_millis() >= deadline_monotonic_ms:
                raise TimeoutError("owned-process enumeration exceeded its monotonic deadline")
            changed = False
            parents = {
                process.pid: process
                for process in captured.values()
                if retained_process_is_live(process)
            }
            pids = sorted(int(path.name) for path in Path("/proc").iterdir() if path.name.isdigit())
            for pid in pids:
                if monotonic_millis() >= deadline_monotonic_ms:
                    raise TimeoutError("owned-process enumeration exceeded its monotonic deadline")
                process = capture_linux_process(pid, marker_bytes, parents, control)
                if process is not None:
                    key = (process.pid, process.start_identity)
                    if key in captured:
                        process.close()
                        continue
                    captured[key] = process
                    changed = True
        return captured
    except (OSError, TimeoutError):
        for process in captured.values():
            process.close()
        raise


def capture_linux_group_process(
    pid: int,
    process_group: int,
    test_control: TestControl,
) -> OwnedProcess | None:
    if pid in {os.getpid(), os.getppid(), process_group}:
        return None
    before = proc_group_and_start(pid)
    if before is None or before[0] != process_group:
        return None
    try:
        pidfd = open_captured_pidfd(pid, test_control)
    except ProcessLookupError:
        return None
    captured: OwnedProcess | None = None
    try:
        if pidfd_is_ready(pidfd):
            return None
        parent_and_start = proc_parent_and_start(pid)
        if parent_and_start is None:
            return None
        observed_group, start_identity = before
        parent, parent_start_identity = parent_and_start
        after = proc_group_and_start(pid)
        if (
            observed_group != process_group
            or start_identity != parent_start_identity
            or after != before
            or pidfd_is_ready(pidfd)
        ):
            return None
        captured = OwnedProcess(pid, parent, start_identity, pidfd)
        return captured
    finally:
        if captured is None:
            os.close(pidfd)


def capture_process_group_linux(
    process_group: int,
    deadline_monotonic_ms: int,
    test_control: TestControl,
) -> dict[int, OwnedProcess]:
    require_linux_pidfd()
    captured: dict[int, OwnedProcess] = {}
    try:
        pids = sorted(int(path.name) for path in Path("/proc").iterdir() if path.name.isdigit())
        for pid in pids:
            if monotonic_millis() >= deadline_monotonic_ms:
                raise TimeoutError("process-group enumeration exceeded its monotonic deadline")
            process = capture_linux_group_process(pid, process_group, test_control)
            if process is not None:
                captured[pid] = process
        return captured
    except (OSError, TimeoutError):
        for process in captured.values():
            process.close()
        raise


def retained_process_is_live(process: OwnedProcess) -> bool:
    if pidfd_is_ready(process.pidfd):
        return False
    observed = proc_parent_and_start(process.pid)
    return observed is not None and observed[1] == process.start_identity


def process_evidence(process: OwnedProcess) -> dict[str, object]:
    return {"pid": process.pid, "startIdentity": process.start_identity}


def merge_retained_processes(
    retained: dict[tuple[int, str], OwnedProcess],
    captured: Sequence[OwnedProcess],
) -> list[OwnedProcess]:
    added: list[OwnedProcess] = []
    for process in captured:
        key = (process.pid, process.start_identity)
        if key in retained:
            process.close()
            continue
        retained[key] = process
        added.append(process)
    return added


def live_retained_processes(
    retained: dict[tuple[int, str], OwnedProcess],
) -> list[OwnedProcess]:
    return [process for process in retained.values() if retained_process_is_live(process)]


def linux_leader_is_live(
    process: subprocess.Popen[bytes],
    leader_pidfd: int | None,
) -> bool:
    if leader_pidfd is not None:
        return not pidfd_is_ready(leader_pidfd)
    return unreaped_linux_exit_code(process) is None


def process_group_closure_payload(
    *,
    process_group: int,
    retained: dict[tuple[int, str], OwnedProcess],
    remaining: Sequence[OwnedProcess],
    leader_live: bool,
    leader_start_identity: str | None,
    leader_pidfd: int | None,
    recapture_passes: int,
    stable_confirmation_passes: int,
    proven: bool,
    term_sent: bool,
    kill_sent: bool,
    enumeration_error: dict[str, object] | None,
) -> dict[str, object]:
    remaining_evidence = [
        process_evidence(process)
        for process in sorted(remaining, key=lambda item: (item.pid, item.start_identity))
    ]
    if leader_live:
        remaining_evidence.insert(
            0,
            {"pid": process_group, "startIdentity": leader_start_identity},
        )
    payload: dict[str, object] = {
        "required": True,
        "proven": proven,
        "pidfdsRetained": leader_pidfd is not None,
        "processGroupId": process_group,
        "capturedProcesses": [
            process_evidence(process)
            for process in sorted(
                retained.values(),
                key=lambda item: (item.pid, item.start_identity),
            )
        ],
        "remainingProcesses": remaining_evidence,
        "recapturePasses": recapture_passes,
        "stableConfirmationPasses": stable_confirmation_passes,
        "termSent": term_sent,
        "killSent": kill_sent,
    }
    if enumeration_error is not None:
        payload["enumerationError"] = enumeration_error
    return payload


def terminate_linux_process_group(
    *,
    process: subprocess.Popen[bytes],
    leader_pidfd: int | None,
    leader_start_identity: str | None,
    term_grace_millis: int,
    kill_grace_millis: int,
    deadline_monotonic_ms: int,
    test_control: TestControl,
) -> dict[str, object]:
    """Close one stable child process group while its leader remains unreaped."""
    process_group = process.pid
    retained: dict[tuple[int, str], OwnedProcess] = {}
    recapture_passes = 0
    stability = StableConfirmation()
    term_sent = False
    kill_sent = False
    enumeration_error: dict[str, object] | None = None

    def recapture(
        sent_signal: signal.Signals | None,
        phase: str,
    ) -> list[OwnedProcess]:
        nonlocal recapture_passes, enumeration_error
        try:
            captured = capture_process_group_linux(
                process_group,
                deadline_monotonic_ms,
                test_control,
            )
            recapture_passes += 1
            added = merge_retained_processes(retained, list(captured.values()))
            test_control.after_snapshot(phase, deadline_monotonic_ms)
        except (OSError, TimeoutError) as error:
            enumeration_error = capture_error_evidence(error)
            raise
        if sent_signal is not None:
            signal_retained(added, sent_signal)
        return added

    def result(proven: bool) -> dict[str, object]:
        remaining = live_retained_processes(retained)
        return process_group_closure_payload(
            process_group=process_group,
            retained=retained,
            remaining=remaining,
            leader_live=linux_leader_is_live(process, leader_pidfd),
            leader_start_identity=leader_start_identity,
            leader_pidfd=leader_pidfd,
            recapture_passes=recapture_passes,
            stable_confirmation_passes=stability.passes,
            proven=proven,
            term_sent=term_sent,
            kill_sent=kill_sent,
            enumeration_error=enumeration_error,
        )

    try:
        try:
            recapture(None, "initial")
        except (OSError, TimeoutError):
            pass

        term_sent = True
        try:
            # The direct leader remains unreaped. Its PID keeps this PGID from
            # being mistaken for a later, unrelated process group.
            os.killpg(process_group, signal.SIGTERM)
        except ProcessLookupError:
            pass
        except OSError:
            if leader_pidfd is not None and linux_leader_is_live(process, leader_pidfd):
                signal.pidfd_send_signal(leader_pidfd, signal.SIGTERM, None, 0)
            signal_retained(live_retained_processes(retained), signal.SIGTERM)

        term_deadline = min(
            deadline_monotonic_ms,
            monotonic_millis() + term_grace_millis,
        )
        while enumeration_error is None and monotonic_millis() < term_deadline:
            try:
                added = recapture(signal.SIGTERM, "after-signal")
            except (OSError, TimeoutError):
                break
            remaining = live_retained_processes(retained)
            if stability.observe(
                empty=not linux_leader_is_live(process, leader_pidfd) and not remaining,
                captured_new_process=bool(added),
            ):
                return result(True)
            time.sleep(0.02)

        kill_sent = True
        try:
            os.killpg(process_group, signal.SIGKILL)
        except ProcessLookupError:
            pass
        if leader_pidfd is not None and linux_leader_is_live(process, leader_pidfd):
            try:
                signal.pidfd_send_signal(leader_pidfd, signal.SIGKILL, None, 0)
            except ProcessLookupError:
                pass
        signal_retained(live_retained_processes(retained), signal.SIGKILL)

        while enumeration_error is None and monotonic_millis() < deadline_monotonic_ms:
            try:
                added = recapture(signal.SIGKILL, "after-signal")
            except (OSError, TimeoutError):
                break
            remaining = live_retained_processes(retained)
            if stability.observe(
                empty=not linux_leader_is_live(process, leader_pidfd) and not remaining,
                captured_new_process=bool(added),
            ):
                return result(True)
            time.sleep(0.02)
        if leader_pidfd is not None:
            wait_pidfd(leader_pidfd, deadline_monotonic_ms)
        else:
            while (
                linux_leader_is_live(process, None)
                and monotonic_millis() < deadline_monotonic_ms
            ):
                time.sleep(0.005)
        return result(False)
    finally:
        for captured_process in retained.values():
            captured_process.close()


def owned_payload(
    operation: str,
    marker: str,
    processes: Sequence[OwnedProcess],
    outcome: str,
    started_epoch_ms: int,
    started_monotonic_ms: int,
    deadline: int,
    term_sent: bool,
    kill_sent: bool,
    ownership_closure: dict[str, object] | None = None,
) -> dict[str, object]:
    finished_epoch_ms = epoch_millis()
    finished_monotonic_ms = monotonic_millis()
    payload: dict[str, object] = {
        "schemaVersion": SCHEMA_VERSION,
        "type": TYPE_OWNED,
        "operation": operation,
        "marker": marker,
        "outcome": outcome,
        "processes": [
            {"pid": process.pid, "startIdentity": process.start_identity}
            for process in sorted(processes, key=lambda item: item.pid)
        ],
        "startedAtEpochMillis": started_epoch_ms,
        "finishedAtEpochMillis": finished_epoch_ms,
        "startedAtMonotonicMillis": started_monotonic_ms,
        "finishedAtMonotonicMillis": finished_monotonic_ms,
        "durationMillis": max(finished_monotonic_ms - started_monotonic_ms, 0),
        "deadlineMonotonicMillis": deadline,
        "termination": {"termSent": term_sent, "killSent": kill_sent},
    }
    if ownership_closure is not None:
        payload["ownershipClosure"] = ownership_closure
    return payload


def list_owned(args: argparse.Namespace) -> int:
    started_epoch_ms = epoch_millis()
    started_monotonic_ms = monotonic_millis()
    try:
        processes = capture_owned_linux(args.marker, args.deadline_monotonic_ms)
    except (OSError, TimeoutError) as error:
        outcome = "TIMED_OUT" if isinstance(error, TimeoutError) else "SUPERVISION_FAILED"
        result = 124 if isinstance(error, TimeoutError) else 125
        payload = owned_payload(
            "process-enumeration",
            args.marker,
            [],
            outcome,
            started_epoch_ms,
            started_monotonic_ms,
            args.deadline_monotonic_ms,
            False,
            False,
        )
        payload["detail"] = str(error)
        write_json(args.result_json, payload)
        append_json_line(args.event_log, payload)
        return result
    try:
        payload = owned_payload(
            "process-enumeration",
            args.marker,
            list(processes.values()),
            "SUCCEEDED",
            started_epoch_ms,
            started_monotonic_ms,
            args.deadline_monotonic_ms,
            False,
            False,
        )
        write_json(args.result_json, payload)
        append_json_line(args.event_log, payload)
        if args.print_pids:
            for process in sorted(processes.values(), key=lambda item: item.pid):
                print(process.pid)
        return 0
    finally:
        for process in processes.values():
            process.close()


def signal_retained(processes: Sequence[OwnedProcess], sent_signal: signal.Signals) -> None:
    for process in processes:
        if not retained_process_is_live(process):
            continue
        try:
            signal.pidfd_send_signal(process.pidfd, sent_signal, None, 0)
        except ProcessLookupError:
            continue


def wait_retained(processes: Sequence[OwnedProcess], deadline: int) -> list[OwnedProcess]:
    remaining = [process for process in processes if retained_process_is_live(process)]
    while remaining and monotonic_millis() < deadline:
        time.sleep(0.02)
        remaining = [process for process in remaining if retained_process_is_live(process)]
    return remaining


def ownership_closure_payload(
    *,
    retained: dict[tuple[int, str], OwnedProcess],
    remaining: Sequence[OwnedProcess],
    recapture_passes: int,
    stable_confirmation_passes: int,
    proven: bool,
    enumeration_error: dict[str, object] | None = None,
) -> dict[str, object]:
    payload: dict[str, object] = {
        "required": True,
        "proven": proven,
        "pidfdsRetained": True,
        "capturedProcesses": [
            process_evidence(process)
            for process in sorted(
                retained.values(),
                key=lambda item: (item.pid, item.start_identity),
            )
        ],
        "remainingProcesses": [
            process_evidence(process)
            for process in sorted(
                remaining,
                key=lambda item: (item.pid, item.start_identity),
            )
        ],
        "recapturePasses": recapture_passes,
        "stableConfirmationPasses": stable_confirmation_passes,
    }
    if enumeration_error is not None:
        payload["enumerationError"] = enumeration_error
    return payload


def terminate_owned(args: argparse.Namespace) -> int:
    require_linux_pidfd()
    boundary = test_boundary(args)
    control = TestControl.from_args(args, boundary)
    started_epoch_ms = epoch_millis()
    started_monotonic_ms = monotonic_millis()
    if started_monotonic_ms >= args.deadline_monotonic_ms:
        payload = owned_payload(
            "teardown-enumeration",
            args.marker,
            [],
            "TIMED_OUT",
            started_epoch_ms,
            started_monotonic_ms,
            args.deadline_monotonic_ms,
            False,
            False,
            ownership_closure_payload(
                retained={},
                remaining=[],
                recapture_passes=0,
                stable_confirmation_passes=0,
                proven=False,
                enumeration_error={
                    "code": "DEADLINE_EXCEEDED",
                    "detail": "owned-process teardown has no remaining monotonic budget",
                },
            ),
        )
        payload["detail"] = "owned-process teardown has no remaining monotonic budget"
        write_json(args.result_json, payload)
        append_json_line(args.event_log, payload)
        return 124
    initial_processes: dict[tuple[int, str], OwnedProcess] = {}
    try:
        initial_processes = capture_owned_linux(
            args.marker,
            args.deadline_monotonic_ms,
            control,
        )
        control.after_snapshot("initial", args.deadline_monotonic_ms)
    except (OSError, TimeoutError) as error:
        for captured_process in initial_processes.values():
            captured_process.close()
        outcome = "TIMED_OUT" if isinstance(error, TimeoutError) else "SUPERVISION_FAILED"
        result = 124 if isinstance(error, TimeoutError) else 125
        payload = owned_payload(
            "teardown-signaling",
            args.marker,
            [],
            outcome,
            started_epoch_ms,
            started_monotonic_ms,
            args.deadline_monotonic_ms,
            False,
            False,
            ownership_closure_payload(
                retained={},
                remaining=[],
                recapture_passes=0,
                stable_confirmation_passes=0,
                proven=False,
                enumeration_error=capture_error_evidence(error),
            ),
        )
        payload["detail"] = str(error)
        write_json(args.result_json, payload)
        append_json_line(args.event_log, payload)
        return result
    retained: dict[tuple[int, str], OwnedProcess] = {}
    initial = merge_retained_processes(retained, list(initial_processes.values()))
    recapture_passes = 1
    stability = StableConfirmation()
    term_sent = bool(initial)
    kill_sent = False
    outcome = "SUCCEEDED"
    result = 0
    enumeration_error: dict[str, object] | None = None

    def recapture(sent_signal: signal.Signals | None) -> list[OwnedProcess]:
        nonlocal recapture_passes, enumeration_error
        try:
            captured = capture_owned_linux(
                args.marker,
                args.deadline_monotonic_ms,
                control,
            )
            recapture_passes += 1
            added = merge_retained_processes(retained, list(captured.values()))
            control.after_snapshot("after-signal", args.deadline_monotonic_ms)
        except (OSError, TimeoutError) as error:
            enumeration_error = capture_error_evidence(error)
            raise
        if sent_signal is not None:
            signal_retained(added, sent_signal)
        return added

    try:
        append_json_line(args.event_log, owned_payload(
            "teardown-enumeration",
            args.marker,
            initial,
            "SUCCEEDED",
            started_epoch_ms,
            started_monotonic_ms,
            args.deadline_monotonic_ms,
            False,
            False,
        ))
        signal_retained(initial, signal.SIGTERM)
        term_deadline = min(
            args.deadline_monotonic_ms,
            monotonic_millis() + args.term_grace_millis,
        )
        remaining = live_retained_processes(retained)
        proven = stability.observe(
            empty=not remaining,
            captured_new_process=False,
        )
        while not proven and monotonic_millis() < term_deadline:
            try:
                added = recapture(signal.SIGTERM)
            except (OSError, TimeoutError):
                break
            if added:
                term_sent = True
            remaining = live_retained_processes(retained)
            proven = stability.observe(
                empty=not remaining,
                captured_new_process=bool(added),
            )
            if not proven:
                time.sleep(0.02)

        if not proven:
            kill_sent = True
            signal_retained(remaining, signal.SIGKILL)
            kill_deadline = min(
                args.deadline_monotonic_ms,
                monotonic_millis() + args.kill_grace_millis,
            )
            while (
                enumeration_error is None
                and not proven
                and monotonic_millis() < kill_deadline
            ):
                try:
                    added = recapture(signal.SIGKILL)
                except (OSError, TimeoutError):
                    break
                remaining = live_retained_processes(retained)
                proven = stability.observe(
                    empty=not remaining,
                    captured_new_process=bool(added),
                )
                if not proven:
                    time.sleep(0.02)

        if not proven:
            outcome = (
                "SUPERVISION_FAILED"
                if enumeration_error is not None
                else "TEARDOWN_FAILED"
            )
            result = 125
        closure = ownership_closure_payload(
            retained=retained,
            remaining=remaining,
            recapture_passes=recapture_passes,
            stable_confirmation_passes=stability.passes,
            proven=proven,
            enumeration_error=enumeration_error,
        )
        payload = owned_payload(
            "teardown-signaling",
            args.marker,
            remaining,
            outcome,
            started_epoch_ms,
            started_monotonic_ms,
            args.deadline_monotonic_ms,
            term_sent,
            kill_sent,
            closure,
        )
        write_json(args.result_json, payload)
        append_json_line(args.event_log, payload)
        return result
    finally:
        for process in retained.values():
            process.close()


def self_test_capture_gap(args: argparse.Namespace) -> int:
    process = subprocess.Popen([sys.executable, "-c", "pass"])
    time.sleep(args.delay_millis / 1000.0)
    # No wait or poll occurs before capture. Even an already-exited child remains
    # unreaped, so the kernel cannot reuse its PID during this interval.
    unreaped_before_capture = process.returncode is None
    child_exited_before_capture = False
    pidfd: int | None = None
    if sys.platform == "linux":
        require_linux_pidfd()
        observation = os.waitid(
            os.P_PID,
            process.pid,
            os.WEXITED | os.WNOHANG | os.WNOWAIT,
        )
        child_exited_before_capture = observation is not None
        pidfd = os.pidfd_open(process.pid, 0)
    capture_identity = portable_start_identity(process.pid)
    process.wait()
    if pidfd is not None:
        os.close(pidfd)
    payload = {
        "schemaVersion": SCHEMA_VERSION,
        "type": TYPE_CAPTURE_GAP,
        "pid": process.pid,
        "captureIdentity": capture_identity,
        "delayMillis": args.delay_millis,
        "directChildRemainedUnreapedUntilCapture": unreaped_before_capture,
        "childExitedBeforeCapture": child_exited_before_capture,
        "pidfdOpenedBeforeReap": pidfd is not None,
        "replacementWasClaimed": False,
        "replacementWasSignaled": False,
    }
    write_json(args.result_json, payload)
    return 0


def add_test_boundary(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--test-sourced", action="store_true")
    parser.add_argument("--test-mode", action="store_true")
    parser.add_argument("--test-allow-signal-helper", action="store_true")
    parser.add_argument("--test-signal-helper")


def add_capture_test_control(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--test-closure-snapshot-file", help=argparse.SUPPRESS)
    parser.add_argument("--test-closure-release-file", help=argparse.SUPPRESS)
    parser.add_argument(
        "--test-closure-barrier-phase",
        choices=("initial", "after-signal"),
        help=argparse.SUPPRESS,
    )
    parser.add_argument(
        "--test-capture-pidfd-error",
        choices=("EMFILE", "EPERM"),
        help=argparse.SUPPRESS,
    )
    parser.add_argument("--test-capture-pid-file", help=argparse.SUPPRESS)
    parser.add_argument(
        "--test-parent-identity-mismatch-pid-file",
        help=argparse.SUPPRESS,
    )


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="subcommand", required=True)

    run = commands.add_parser("run", help="run one direct child within an absolute deadline")
    run.add_argument("--deadline-monotonic-ms", required=True, type=positive_millis)
    run.add_argument("--operation", required=True)
    run.add_argument("--event-log")
    run.add_argument("--result-json")
    run.add_argument("--term-grace-millis", type=positive_millis, default=5000)
    run.add_argument("--kill-grace-millis", type=positive_millis, default=2000)
    add_test_boundary(run)
    add_capture_test_control(run)
    run.add_argument("--test-fail-pidfd-open", action="store_true", help=argparse.SUPPRESS)
    run.add_argument("--test-pidfd-open-failure-ready-file", help=argparse.SUPPRESS)
    run.add_argument("command", nargs=argparse.REMAINDER)
    run.set_defaults(handler=run_command)

    owned = commands.add_parser("list-owned", help="atomically classify marked Linux processes")
    owned.add_argument("--deadline-monotonic-ms", required=True, type=positive_millis)
    owned.add_argument("--marker", required=True)
    owned.add_argument("--result-json", required=True)
    owned.add_argument("--event-log")
    owned.add_argument("--print-pids", action="store_true")
    owned.set_defaults(handler=list_owned)

    terminate = commands.add_parser(
        "terminate-owned",
        help="retain pidfds while classifying and signaling marked Linux processes",
    )
    terminate.add_argument("--deadline-monotonic-ms", required=True, type=positive_millis)
    terminate.add_argument("--marker", required=True)
    terminate.add_argument("--event-log")
    terminate.add_argument("--result-json", required=True)
    terminate.add_argument("--term-grace-millis", type=positive_millis, default=5000)
    terminate.add_argument("--kill-grace-millis", type=positive_millis, default=2000)
    add_test_boundary(terminate)
    add_capture_test_control(terminate)
    terminate.set_defaults(handler=terminate_owned)

    capture = commands.add_parser(
        "self-test-capture-gap",
        help="prove fast-exit direct children remain unreaped until pidfd capture",
    )
    capture.add_argument("--delay-millis", type=nonnegative_millis, default=100)
    capture.add_argument("--result-json", required=True)
    capture.set_defaults(handler=self_test_capture_gap)
    return root


def main() -> int:
    args = parser().parse_args()
    return int(args.handler(args))


if __name__ == "__main__":
    raise SystemExit(main())
