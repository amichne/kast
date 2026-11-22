"""Admit native invocation state before capture; erase only its empty bookkeeping."""

import os
import stat
import sys
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import immutable_store as store
import managed_installation as install
import managed_update_contract as contract
import managed_update_journal as journal


@dataclass(frozen=True)
class InvocationStateAdmitted:
    outcome: Literal["ADMITTED"] = "ADMITTED"
    component: Literal[contract.StateComponent.INVOCATION_STATE] = (
        contract.StateComponent.INVOCATION_STATE
    )


def reject_invocation_state(
    reason: contract.Failure, names: Iterable[str]
) -> install.StateRejected:
    """Names the offending entries under state/run, bounded; never their contents."""
    return install.reject_state(
        contract.StateComponent.INVOCATION_STATE, reason, ("state/run/" + name for name in names)
    )


INVOCATION_DIRECTORIES = frozenset({"one-shot", "tool-sessions"})
COORDINATOR_SOCKET = "c.sock"
COORDINATOR_SOCKET_LOCK = "c.sock.lock"
# A socket path longer than the platform limit binds through a /tmp alias; the coordinator
# records that alias's identity here and alias retirement deletes it (InstalledEndpointAliases).
COORDINATOR_ENDPOINT_ALIAS_RECEIPT = "endpoint-alias.json"
# The same bound installation-lifecycle applies when it reads the receipt.
MAXIMUM_ENDPOINT_ALIAS_RECEIPT_BYTES = 8192
COORDINATOR_ENTRIES = frozenset(
    {COORDINATOR_SOCKET, COORDINATOR_SOCKET_LOCK, COORDINATOR_ENDPOINT_ALIAS_RECEIPT}
)


def private_regular_file(observed: os.stat_result, *, minimum: int, maximum: int) -> bool:
    return (
        stat.S_ISREG(observed.st_mode)
        and observed.st_uid == os.getuid()
        and stat.S_IMODE(observed.st_mode) == 0o600
        and minimum <= observed.st_size <= maximum
    )


def admit_coordinator_socket_state(run: Path) -> None:
    """A live coordinator owns only its socket, lock, and alias receipt; retirement removes them."""
    unowned = contract.Failure.UNOWNED_STATE_ENTRY
    socket = run / COORDINATOR_SOCKET
    if os.path.lexists(socket):
        observed = socket.lstat()
        if not stat.S_ISSOCK(observed.st_mode) or observed.st_uid != os.getuid():
            raise reject_invocation_state(unowned, (COORDINATOR_SOCKET,))
    lock = run / COORDINATOR_SOCKET_LOCK
    if os.path.lexists(lock) and not private_regular_file(lock.lstat(), minimum=0, maximum=0):
        raise reject_invocation_state(unowned, (COORDINATOR_SOCKET_LOCK,))
    receipt = run / COORDINATOR_ENDPOINT_ALIAS_RECEIPT
    if os.path.lexists(receipt) and not private_regular_file(
        receipt.lstat(), minimum=1, maximum=MAXIMUM_ENDPOINT_ALIAS_RECEIPT_BYTES
    ):
        raise reject_invocation_state(unowned, (COORDINATOR_ENDPOINT_ALIAS_RECEIPT,))


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
    unexpected = {child.name for child in run.iterdir()} - admitted
    if unexpected:
        raise reject_invocation_state(contract.Failure.UNOWNED_STATE_ENTRY, unexpected)
    if live_coordinator:
        admit_coordinator_socket_state(run)
    directories: list[tuple[Path, journal.Identity]] = []
    for child in sorted(path for path in run.iterdir() if path.name in INVOCATION_DIRECTORIES):
        store.physical(child)
        identity = journal.Identity.observe(child)
        if any(child.iterdir()):
            raise reject_invocation_state(contract.Failure.UNSETTLED_INVOCATIONS, (child.name,))
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
    if unowned := {child.name for child in state.iterdir()} - {"run"}:
        raise install.reject_state(
            contract.StateComponent.INSTALLATION_STATE,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ("state/" + name for name in unowned),
        )
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
