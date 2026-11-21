"""Admit native invocation state before capture; erase only its empty bookkeeping."""

import os
import stat
import sys
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal

import immutable_store as store
import managed_installation as install
import managed_update_journal as journal


class InvocationStateFailure(StrEnum):
    UNKNOWN_DIRECTORY = "UNKNOWN_DIRECTORY"
    UNSETTLED_INVOCATIONS = "UNSETTLED_INVOCATIONS"


@dataclass(frozen=True)
class InvocationStateAdmitted:
    outcome: Literal["ADMITTED"] = "ADMITTED"
    component: Literal["kast-invocation-state"] = "kast-invocation-state"


@dataclass(frozen=True)
class InvocationStateRejected:
    reason: InvocationStateFailure
    outcome: Literal["REJECTED"] = "REJECTED"
    component: Literal["kast-invocation-state"] = "kast-invocation-state"


def reject_invocation_state(reason: InvocationStateFailure) -> None:
    print(
        install.encoded(InvocationStateRejected(reason)).decode(),
        end="",
        file=sys.stderr,
    )
    raise ValueError(reason.value)


INVOCATION_DIRECTORIES = frozenset({"one-shot", "tool-sessions"})
COORDINATOR_SOCKET = "c.sock"
COORDINATOR_SOCKET_LOCK = "c.sock.lock"
COORDINATOR_ENTRIES = frozenset({COORDINATOR_SOCKET, COORDINATOR_SOCKET_LOCK})


def admit_coordinator_socket_state(run: Path) -> None:
    """A live coordinator owns exactly its socket and lock; its retirement removes both."""
    socket = run / COORDINATOR_SOCKET
    if os.path.lexists(socket):
        observed = socket.lstat()
        if not stat.S_ISSOCK(observed.st_mode) or observed.st_uid != os.getuid():
            reject_invocation_state(InvocationStateFailure.UNKNOWN_DIRECTORY)
    lock = run / COORDINATOR_SOCKET_LOCK
    if os.path.lexists(lock):
        observed = lock.lstat()
        if (
            not stat.S_ISREG(observed.st_mode)
            or observed.st_uid != os.getuid()
            or stat.S_IMODE(observed.st_mode) != 0o600
            or observed.st_size != 0
        ):
            reject_invocation_state(InvocationStateFailure.UNKNOWN_DIRECTORY)


def empty_invocation_directories(
    payload: Path, *, live_coordinator: bool = False
) -> tuple[tuple[Path, journal.Identity], ...]:
    """Only the exact empty native bookkeeping directories are removable, never records.

    Before the prior Control retires its semantic coordinator, that coordinator's socket
    state is admitted by exact shape. Afterwards any remaining socket state still rejects.
    """
    state = payload / "state"
    if not os.path.lexists(state):
        return ()
    store.physical(state)
    run = state / "run"
    if not os.path.lexists(run):
        return ()
    store.physical(run)
    coordinator: frozenset[str] = COORDINATOR_ENTRIES if live_coordinator else frozenset()
    admitted = INVOCATION_DIRECTORIES | coordinator
    if {child.name for child in run.iterdir()} - admitted:
        reject_invocation_state(InvocationStateFailure.UNKNOWN_DIRECTORY)
    if live_coordinator:
        admit_coordinator_socket_state(run)
    directories: list[tuple[Path, journal.Identity]] = []
    for child in sorted(path for path in run.iterdir() if path.name in INVOCATION_DIRECTORIES):
        store.physical(child)
        identity = journal.Identity.observe(child)
        if any(child.iterdir()):
            reject_invocation_state(InvocationStateFailure.UNSETTLED_INVOCATIONS)
        directories.append((child, identity))
    directories.append((run, journal.Identity.observe(run)))
    print(install.encoded(InvocationStateAdmitted()).decode(), end="", file=sys.stderr)
    return tuple(directories)


def admit(payload: Path) -> None:
    """Installation state holds only native invocation bookkeeping; anything else rejects."""
    state = payload / "state"
    if not os.path.lexists(state):
        return
    store.physical(state)
    if {child.name for child in state.iterdir()} - {"run"}:
        raise ValueError("unowned installation state")
    _ = empty_invocation_directories(payload)


def erase(root: Path) -> None:
    payload = root / "installation"
    admit(payload)
    state = payload / "state"
    if not os.path.lexists(state):
        return
    for directory, identity in empty_invocation_directories(payload):
        store.physical(directory)
        if journal.Identity.observe(directory) != identity:
            raise ValueError("invocation directory replaced")
        directory.rmdir()
    state.rmdir()
    install.RECOVERY.sync(state.parent)
