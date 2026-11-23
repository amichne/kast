#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["defusedxml==0.7.1", "types-defusedxml==0.7.0.20260504"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Candidate bootstrap and recoverable composition coordinator."""

from __future__ import annotations

import argparse
import ctypes
import errno
import fcntl
import hashlib
import importlib.util
import json
import os
import platform
import signal
import stat
import subprocess
import sys
import tempfile
import zipfile
from collections.abc import Iterable, Iterator
from contextlib import contextmanager
from dataclasses import asdict, dataclass, is_dataclass
from enum import Enum
from pathlib import Path
from types import ModuleType
from typing import TYPE_CHECKING, Literal, Protocol

# Imported helpers belong to the immutable installed payload, including on first invocation.
if not sys.dont_write_bytecode:
    sys.dont_write_bytecode = True

import managed_release as release
import managed_update_contract as contract
import managed_update_journal as journal_types

if TYPE_CHECKING:
    from managed_product_installation import ProductInstalled


def module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError("installation adapter unavailable")
    owner = importlib.util.module_from_spec(spec)
    sys.modules[name] = owner
    spec.loader.exec_module(owner)
    return owner


RECOVERY = module("kast_managed_recovery", Path(__file__).with_name("installation-recovery.py"))
LIFECYCLE = module("kast_managed_lifecycle", Path(__file__).with_name("installation-lifecycle.py"))

MAXIMUM_FILE_PREIMAGE_BYTES = 128 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class ShutdownRequest:
    installationRoot: str
    schemaVersion: int = 1


@dataclass(frozen=True, slots=True)
class SelectionRejected:
    reason: release.Failure
    stage: release.Stage
    requested: str
    platform: str
    selectable: tuple[str, ...]
    transfer: release.TransferRejected | None = None
    type: Literal["REJECTED"] = "REJECTED"


@dataclass(slots=True)
class InterruptState:
    exit_code: int | None = None


class ChildRunner(Protocol):
    def __call__(
        self, arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]: ...


def run_child(
    arguments: list[str], environment: dict[str, str], timeout: float
) -> subprocess.CompletedProcess[bytes]:
    with subprocess.Popen(
        arguments,
        env=environment,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ) as child:
        try:
            stdout, stderr = child.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            child.kill()
            _ = child.communicate()
            raise
        except KeyboardInterrupt:
            # A partial native effect must finish or stop before the journal owner restores files.
            child.terminate()
            try:
                _ = child.communicate(timeout=min(timeout, 5))
            except subprocess.TimeoutExpired:
                child.kill()
                _ = child.communicate()
            raise
        return subprocess.CompletedProcess(arguments, child.returncode, stdout, stderr)


@contextmanager
def management_lock(root: Path) -> Iterator[None]:
    """Hold the POSIX record lock that Kotlin's FileChannel.tryLock takes on the same file.

    flock and fcntl record locks are independent on Linux, so only a record lock excludes
    `kast uninstall` and workspace edits. A record lock belongs to this process and is
    released when any descriptor for the file closes here: never open this path again
    while the lock is held.
    """
    if root.resolve() != root:
        raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
    root.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(root / "management.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "a+b") as lock:
        attributes = os.fstat(lock.fileno())
        if not stat.S_ISREG(attributes.st_mode) or attributes.st_uid != os.getuid():
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.OWNERSHIP_UNPROVEN)
        try:
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as problem:
            # POSIX lets F_SETLK report a conflicting holder as EACCES or EAGAIN; keep
            # the single EAGAIN rejection that flock reported before.
            if problem.errno in (errno.EACCES, errno.EAGAIN):
                raise BlockingIOError(errno.EAGAIN, "management.lock is held") from None
            raise
        yield


@dataclass(frozen=True, slots=True)
class StageEvidence:
    stage: contract.Stage
    outcome: Literal["STARTED", "COMPLETED", "REJECTED"]
    reason: contract.Failure | None = None


class StageRejected(Exception):
    def __init__(self, failure: contract.Rejected) -> None:
        self.failure = failure
        super().__init__(failure.reason.value)


class StateRejected(Exception):
    """A state admission check refused named entries; the owning flow binds its stage."""

    def __init__(self, rejection: contract.StateRejection) -> None:
        self.rejection = rejection
        super().__init__(rejection.reason.value)

    def failure(self, stage: contract.Stage) -> contract.Rejected:
        rejection = self.rejection
        return contract.Rejected(
            stage,
            rejection.reason,
            state=contract.StateEntries(rejection.component, rejection.entries),
        )


def reject_state(
    component: contract.StateComponent, reason: contract.Failure, entries: Iterable[str]
) -> StateRejected:
    """Emit the check's single bounded observation, then return its typed rejection."""
    rejection = contract.StateRejection(reason, component, contract.reported_entries(entries))
    print(encoded(rejection).decode(), end="", file=sys.stderr)
    return StateRejected(rejection)


def rejection(problem: BaseException) -> contract.Rejected:
    """Map one caught failure to its outcome without erasing an adapter's own code.

    Checks that raise ValueError still report OWNERSHIP_UNPROVEN. Anything no check
    owns is an installer defect, reported with its exception class name only.
    An AssertionError is never an outcome: it is re-raised, so a broken invariant or a
    test double's fixture violation stays a failure rather than a product result.
    """
    if isinstance(problem, AssertionError):
        raise problem
    preparation = contract.Stage.PREPARATION
    failure = contract.Failure
    if isinstance(problem, StageRejected):
        return problem.failure
    if isinstance(problem, StateRejected):
        return problem.failure(preparation)
    if isinstance(problem, KeyboardInterrupt):
        return contract.Rejected(preparation, failure.CANCELLED)
    if isinstance(problem, journal_types.Rejected):
        code = problem.failure.value
        if problem.failure is journal_types.Failure.CONTROL_LIMIT:
            return contract.Rejected(preparation, failure.CONTROL_REJECTED, code)
        if problem.failure is journal_types.Failure.FILESYSTEM:
            return contract.Rejected(preparation, failure.FILESYSTEM_REJECTED, code)
        if problem.entry is not None:
            return contract.Rejected(
                preparation,
                failure.UNOWNED_STATE_ENTRY,
                code,
                contract.StateEntries(
                    contract.StateComponent.CONTROL_PAYLOAD_TREE,
                    contract.reported_entries((problem.entry,)),
                ),
            )
        return contract.Rejected(preparation, failure.OWNERSHIP_UNPROVEN, code)
    if isinstance(problem, LIFECYCLE.Rejected):
        # The lifecycle adapter is loaded by path and untyped; admit its closed code here.
        observed: object = getattr(problem, "failure", None)
        lifecycle: type[Enum] = LIFECYCLE.Failure
        if not isinstance(observed, lifecycle) or not isinstance(observed.value, str):
            return contract.Rejected(preparation, failure.INSTALLATION_DEFECT, "LifecycleRejected")
        code: str = observed.value
        if observed is lifecycle["CONTROL_LIMIT_EXCEEDED"]:
            return contract.Rejected(preparation, failure.CONTROL_REJECTED, code)
        if observed is lifecycle["FILESYSTEM_REJECTED"]:
            return contract.Rejected(preparation, failure.FILESYSTEM_REJECTED, code)
        return contract.Rejected(preparation, failure.OWNERSHIP_UNPROVEN, code)
    if isinstance(problem, OSError) and problem.errno is not None:
        if problem.errno == errno.ENOSPC:
            return contract.Rejected(preparation, failure.STORAGE_EXHAUSTED)
        return contract.Rejected(
            preparation, failure.FILESYSTEM_REJECTED, errno.errorcode.get(problem.errno)
        )
    if isinstance(problem, (OSError, ValueError, zipfile.BadZipFile)):
        return contract.Rejected(preparation, failure.OWNERSHIP_UNPROVEN)
    return contract.Rejected(
        preparation,
        failure.INSTALLATION_DEFECT,
        type(problem).__name__[: contract.MAXIMUM_REPORTED_NAME_CHARACTERS],
    )


def transfer_message(failure: release.TransferRejected) -> str:
    cause = release.TransferFailure
    roots = f" (trust roots: {failure.trust})" if failure.trust is not None else ""
    if failure.cause is cause.TLS_CERTIFICATE_UNTRUSTED and (
        failure.trust is release.TrustRoots.ENVIRONMENT
    ):
        return (
            f"kast: TLS certificate verification failed{roots}. The configured SSL_CERT_FILE "
            "or SSL_CERT_DIR must include the root CA of any proxy that inspects HTTPS."
        )
    if failure.cause is cause.TLS_CERTIFICATE_UNTRUSTED:
        return (
            f"kast: TLS certificate verification failed{roots}. If a proxy inspects HTTPS, "
            "set SSL_CERT_FILE to a PEM bundle that includes its root CA and retry."
        )
    if failure.cause is cause.HTTP_STATUS:
        return f"kast: release transfer failed with HTTP status {failure.httpStatus}{roots}."
    return f"kast: release transfer failed: {failure.cause}{roots}."


def encoded(value: object) -> bytes:
    if not is_dataclass(value) or isinstance(value, type):
        raise TypeError("management output needs a typed document")
    return (json.dumps(asdict(value), separators=(",", ":")) + "\n").encode()


def read_record(path: Path, limit: int = 65_536) -> bytes:
    if path.resolve() != path:
        raise OSError("record path rejected")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if path.is_symlink() or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            raise OSError("record owner rejected")
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise OSError("record limit exceeded")
    return data


def emit(
    stage: contract.Stage,
    outcome: Literal["STARTED", "COMPLETED", "REJECTED"],
    reason: contract.Failure | None = None,
) -> None:
    print(encoded(StageEvidence(stage, outcome, reason)).decode().rstrip(), file=sys.stderr)


def reject(
    stage: contract.Stage, reason: contract.Failure, adapter: str | None = None
) -> StageRejected:
    emit(stage, "REJECTED", reason)
    return StageRejected(contract.Rejected(stage, reason, adapter))


def saved_file(path: Path, preimage: Path) -> journal_types.ManagedFilePreimage:
    if not os.path.lexists(path):
        return journal_types.ManagedFileAbsent(str(path), "ABSENT")
    data = read_record(path, MAXIMUM_FILE_PREIMAGE_BYTES)
    _ = preimage.write_bytes(data)
    preimage.chmod(0o600)
    return journal_types.ManagedFilePresent(
        str(path),
        str(preimage),
        hashlib.sha256(data).hexdigest(),
        stat.S_IMODE(path.stat().st_mode),
        "PRESENT",
    )


def file_matches(path: Path, digest: str, mode: int) -> bool:
    return (
        os.path.lexists(path)
        and hashlib.sha256(read_record(path, MAXIMUM_FILE_PREIMAGE_BYTES)).hexdigest() == digest
        and stat.S_IMODE(path.stat().st_mode) == mode
    )


def exchange_files(first: Path, second: Path) -> None:
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform == "darwin":
        rename = library.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = rename(os.fsencode(first), os.fsencode(second), 2)
    else:
        rename = library.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        result = rename(-100, os.fsencode(first), -100, os.fsencode(second), 2)
    if result != 0:
        raise OSError(ctypes.get_errno(), "owned file exchange failed")


def replace_file(
    path: Path, data: bytes, mode: int, expected: journal_types.ManagedFilePreimage, token: str
) -> None:
    """Keep a displaced concurrent file until its captured preimage proves ownership."""
    temporary = path.with_name(".kast-managed-" + token)
    selected_digest = hashlib.sha256(data).hexdigest()
    if os.path.lexists(temporary):
        if (
            isinstance(expected, journal_types.ManagedFilePresent)
            and file_matches(temporary, expected.sha256, expected.mode)
            and file_matches(path, selected_digest, mode)
        ):
            temporary.unlink()
            RECOVERY.sync(path.parent)
            return
        if not file_matches(temporary, selected_digest, mode):
            raise reject(contract.Stage.RECOVERY, contract.Failure.CONCURRENT_CHANGE)
    else:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
        with os.fdopen(descriptor, "wb") as output:
            _ = output.write(data)
            output.flush()
            os.fchmod(output.fileno(), mode)
            os.fsync(output.fileno())
    selected_identity = journal_types.Identity.observe(temporary)
    try:
        if not unchanged_preimage(expected):
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.CONCURRENT_CHANGE)
        if isinstance(expected, journal_types.ManagedFileAbsent):
            os.link(temporary, path, follow_symlinks=False)
        else:
            exchange_files(temporary, path)
            if not file_matches(temporary, expected.sha256, expected.mode):
                if journal_types.Identity.observe(path) == selected_identity and file_matches(
                    path, selected_digest, mode
                ):
                    exchange_files(temporary, path)
                raise reject(contract.Stage.RE_ADMISSION, contract.Failure.CONCURRENT_CHANGE)
        if journal_types.Identity.observe(path) != selected_identity or not file_matches(
            path, selected_digest, mode
        ):
            raise reject(contract.Stage.RE_ADMISSION, contract.Failure.CONCURRENT_CHANGE)
        RECOVERY.sync(path.parent)
        temporary.unlink()
        RECOVERY.sync(path.parent)
    except BaseException:
        if (
            os.path.lexists(temporary)
            and journal_types.Identity.observe(temporary) == selected_identity
            and file_matches(temporary, selected_digest, mode)
        ):
            temporary.unlink()
        raise


def unchanged_preimage(preimage: journal_types.ManagedFilePreimage) -> bool:
    path = Path(preimage.destination)
    if isinstance(preimage, journal_types.ManagedFileAbsent):
        return not os.path.lexists(path)
    return (
        os.path.lexists(path)
        and hashlib.sha256(read_record(path, MAXIMUM_FILE_PREIMAGE_BYTES)).hexdigest()
        == preimage.sha256
        and stat.S_IMODE(path.stat().st_mode) == preimage.mode
    )


def journal_types_record(value: object) -> journal_types.Replacement:
    return journal_types.admit_replacement(value)


def rename_file_no_replace(first: Path, second: Path) -> None:
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform == "darwin":
        rename = library.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = rename(os.fsencode(first), os.fsencode(second), 4)
    else:
        rename = library.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        result = rename(-100, os.fsencode(first), -100, os.fsencode(second), 1)
    if result != 0:
        raise OSError(ctypes.get_errno(), "owned file quarantine failed")


def main(
    arguments: list[str],
    source: release.ReleaseSource | None = None,
    runner: ChildRunner = run_child,
    interrupt: InterruptState | None = None,
) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    _ = group.add_argument("--channel", choices=("stable", "preview"))
    _ = group.add_argument("--version")
    _ = group.add_argument("--commit")
    _ = group.add_argument("--resolved-selection", type=Path)
    _ = group.add_argument(
        "--local-artifact",
        type=Path,
        help="Complete local product directory (build-record.json and payload/)",
    )
    _ = parser.add_argument("--install-root", type=Path)
    _ = parser.add_argument("--dry-run", action="store_true")
    _ = parser.add_argument("--recover-only", action="store_true")
    _ = parser.add_argument("--json", action="store_true")
    _ = parser.add_argument("--private-python-archive", type=Path, help=argparse.SUPPRESS)
    _ = parser.add_argument("--private-dependency", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args(arguments)
    from managed_product_installation import ProductInstalled, ProductPlanned, activate, resume
    from private_interpreter import PrivateInterpreter

    state = interrupt if interrupt is not None else InterruptState()
    home = Path(os.environ["HOME"]).resolve()
    root = home / ".local/share/kast"
    outcome: (
        contract.Rejected
        | contract.RecoveryRequired
        | SelectionRejected
        | ProductInstalled
        | ProductPlanned
    )
    try:
        provider = (
            release.LocalReleaseSource(args.local_artifact)
            if args.local_artifact is not None
            else source
        )
        if args.install_root is not None and args.install_root != root:
            outcome = contract.Rejected(
                contract.Stage.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN
            )
        elif (platform.system(), platform.machine()) not in (
            ("Darwin", "arm64"),
            ("Linux", "x86_64"),
        ):
            outcome = contract.Rejected(
                contract.Stage.PREPARATION, contract.Failure.PLATFORM_UNAVAILABLE
            )
        elif (
            args.recover_only
            or os.path.lexists(root / "recovery/replacement")
            or os.path.lexists(root / "shutdown.json")
        ):
            pending = root / "recovery/replacement/receipt.json"
            record = (
                journal_types_record(RECOVERY.load(pending, journal_types.Replacement))
                if pending.is_file()
                else None
            )
            unproven = contract.Rejected(
                contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN
            )
            if record is not None and record.product is not None:
                outcome = unproven if args.dry_run else resume(root, home, runner)
            else:
                # Only product journals are recoverable; any other recorded state fails closed.
                outcome = contract.RecoveryRequired("recorded", unproven, unproven)
        else:
            provider = provider if provider is not None else release.HttpReleaseSource()
            selection = (
                provider.selection()
                if isinstance(provider, release.LocalReleaseSource)
                else release.decode_selection(read_record(args.resolved_selection))
                if args.resolved_selection is not None
                else release.resolve_selection(args.channel, args.version, args.commit, provider)
            )
            if isinstance(selection, release.Rejected):
                outcome = SelectionRejected(
                    selection.reason,
                    selection.stage,
                    selection.requested,
                    selection.platform,
                    selection.selectable,
                    selection.transfer,
                )
            else:
                with tempfile.TemporaryDirectory(prefix="kast-managed-install-") as temporary:
                    workspace = Path(temporary).resolve()
                    directory = workspace / "release"
                    directory.mkdir(mode=0o700)
                    admitted = release.download_composition(selection, "262", directory, provider)
                    if isinstance(admitted, release.Rejected):
                        outcome = SelectionRejected(
                            admitted.reason,
                            admitted.stage,
                            admitted.requested,
                            admitted.platform,
                            admitted.selectable,
                            admitted.transfer,
                        )
                    else:
                        interpreter = (
                            PrivateInterpreter.admit(
                                args.private_python_archive,
                                args.private_dependency,
                                admitted.target.target,
                            )
                            if args.private_python_archive is not None
                            and args.private_dependency is not None
                            else None
                        )
                        if (args.private_python_archive is None) != (
                            args.private_dependency is None
                        ):
                            raise ValueError("private interpreter inputs must be paired")
                        outcome = activate(root, home, admitted, args.dry_run, interpreter, runner)
    except (Exception, KeyboardInterrupt) as problem:
        if isinstance(problem, KeyboardInterrupt) and state.exit_code is None:
            state.exit_code = 130
        outcome = rejection(problem)
    if args.json:
        print(encoded(outcome).decode().rstrip())
    else:
        print(outcome.type)
        if not isinstance(outcome, (ProductInstalled, ProductPlanned)):
            print(encoded(outcome).decode().rstrip(), file=sys.stderr)
        if isinstance(outcome, SelectionRejected) and outcome.transfer is not None:
            print(transfer_message(outcome.transfer), file=sys.stderr)
    return (
        state.exit_code
        if state.exit_code is not None
        else 0
        if isinstance(outcome, (ProductInstalled, ProductPlanned))
        else 1
    )


def entrypoint() -> int:
    interruption = InterruptState()

    def interrupted(number: int, _frame: object) -> None:
        interruption.exit_code = 128 + number
        raise KeyboardInterrupt()

    _ = signal.signal(signal.SIGTERM, interrupted)
    _ = signal.signal(signal.SIGINT, interrupted)
    return main(sys.argv[1:], interrupt=interruption)


if __name__ == "__main__":
    raise SystemExit(entrypoint())
