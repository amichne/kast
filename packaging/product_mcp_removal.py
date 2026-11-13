"""Admit optional MCP state before capture; erase only journal-bound survivors."""

import os
import sys
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal

import immutable_store as store
import managed_installation as install
import managed_release as release
import managed_update_journal as journal
import mcp_environment as mcp


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


def empty_invocation_directories(payload: Path) -> tuple[tuple[Path, journal.Identity], ...]:
    """Only the exact empty native bookkeeping directories are removable, never records."""
    state = payload / "state"
    if not os.path.lexists(state):
        return ()
    store.physical(state)
    run = state / "run"
    if not os.path.lexists(run):
        return ()
    store.physical(run)
    if {child.name for child in run.iterdir()} - {"one-shot", "tool-sessions"}:
        reject_invocation_state(InvocationStateFailure.UNKNOWN_DIRECTORY)
    directories: list[tuple[Path, journal.Identity]] = []
    for child in run.iterdir():
        store.physical(child)
        identity = journal.Identity.observe(child)
        if any(child.iterdir()):
            reject_invocation_state(InvocationStateFailure.UNSETTLED_INVOCATIONS)
        directories.append((child, identity))
    directories.append((run, journal.Identity.observe(run)))
    print(install.encoded(InvocationStateAdmitted()).decode(), end="", file=sys.stderr)
    return tuple(directories)


def capture(payload: Path) -> store.McpRemoval | None:
    state = payload / "state"
    if not os.path.lexists(state):
        return None
    store.physical(state)
    if {child.name for child in state.iterdir()} - {"mcp", "run"}:
        raise ValueError("unowned installation state")
    _ = empty_invocation_directories(payload)
    if not os.path.lexists(state / "mcp"):
        return None
    installation = mcp.admit_installation(payload)
    if isinstance(installation, mcp.Rejected):
        raise ValueError("MCP installation rejected")
    environment = mcp.admit_environment(installation)
    if isinstance(environment, mcp.Rejected):
        raise ValueError("MCP environment rejected")
    return store.McpRemoval(
        journal.Identity.observe(environment.root),
        journal.Identity.observe(state),
        release.file_digest(environment.root / "receipt.json"),
    )


def inventory(raw: bytes, payload: Path, proof: store.ProductRemoval) -> mcp.Inventory:
    if proof.mcp is None or store.digest(raw) != proof.mcp.receiptSha256:
        raise ValueError("MCP receipt changed")
    receipt = release.document(raw)
    if (
        not release.mapping(receipt)
        or set(receipt)
        != {"installationRoot", "manifestSha256", "export", "inventory", "schemaVersion"}
        or type(receipt["schemaVersion"]) is not int
        or receipt["schemaVersion"] != 1
        or receipt["installationRoot"] != str(payload)
        or receipt["manifestSha256"] != "sha256:" + proof.installationManifestSha256
        or not release.mapping(value := receipt["inventory"])
        or set(value) != {"files", "directories"}
        or not release.sequence(files := value["files"])
        or not release.sequence(directories := value["directories"])
        or not 0 < len(files) + len(directories) <= mcp.MAX_ENTRIES
    ):
        raise ValueError("MCP original inventory")
    selected: list[mcp.File] = []
    for item in files:
        entry = mcp.file_record(item)
        if isinstance(entry, mcp.Rejected):
            raise ValueError("MCP inventory file")
        selected.append(entry)
    names: list[str] = []
    for name in directories:
        if (
            not isinstance(name, str)
            or not name
            or Path(name).is_absolute()
            or ".." in Path(name).parts
            or Path(name).as_posix() != name
        ):
            raise ValueError("MCP inventory directory")
        names.append(name)
    if len({item.path for item in selected}) != len(selected) or len(set(names)) != len(names):
        raise ValueError("MCP duplicate inventory")
    return mcp.Inventory(tuple(selected), tuple(names))


def verify(root: Path, bundle: Path, proof: store.ProductRemoval) -> None:
    state = root / "installation/state"
    _ = empty_invocation_directories(root / "installation")
    if proof.mcp is None:
        if os.path.lexists(state) and {child.name for child in state.iterdir()} - {"run"}:
            raise ValueError("unrecorded installation state")
        return
    raw = install.read_record(bundle / "mcp-receipt.json", mcp.MAX_RECORD)
    expected = inventory(raw, root / "installation", proof)
    if not os.path.lexists(state):
        return
    store.physical(state)
    if journal.Identity.observe(state) != proof.mcp.stateIdentity:
        raise ValueError("MCP state replaced")
    if {child.name for child in state.iterdir()} - {"mcp", "run"}:
        raise ValueError("foreign installation state")
    destination = state / "mcp"
    if not os.path.lexists(destination):
        return
    store.physical(destination)
    if journal.Identity.observe(destination) != proof.mcp.identity:
        raise ValueError("MCP environment replaced")
    if {child.name for child in destination.iterdir()} - {"site-packages", "receipt.json"}:
        raise ValueError("foreign MCP state")
    receipt = destination / "receipt.json"
    if os.path.lexists(receipt) and install.read_record(receipt, mcp.MAX_RECORD) != raw:
        raise ValueError("MCP receipt replaced")
    site = destination / "site-packages"
    if os.path.lexists(site):
        observed = mcp.inventory(site)
        if (
            isinstance(observed, mcp.Rejected)
            or not set(observed.files) <= set(expected.files)
            or not set(observed.directories) <= set(expected.directories)
        ):
            raise ValueError("foreign or changed MCP files")


def erase(root: Path, bundle: Path, proof: store.ProductRemoval) -> None:
    verify(root, bundle, proof)
    state = root / "installation/state"
    if not os.path.lexists(state):
        return
    for directory, identity in empty_invocation_directories(root / "installation"):
        store.physical(directory)
        if journal.Identity.observe(directory) != identity:
            raise ValueError("invocation directory replaced")
        directory.rmdir()
    if proof.mcp is None:
        state.rmdir()
        install.RECOVERY.sync(state.parent)
        return
    destination = state / "mcp"
    if os.path.lexists(destination):
        receipt = destination / "receipt.json"
        paths = sorted(destination.rglob("*"), key=lambda path: len(path.parts), reverse=True)
        for directory in (destination, *(path for path in paths if path.is_dir())):
            directory.chmod(0o700)
        for path in paths:
            if (
                journal.Identity.observe(destination) != proof.mcp.identity
                or path.parent.resolve() != path.parent
            ):
                raise ValueError("MCP environment changed during removal")
            if path == receipt:
                continue
            if path.is_dir():
                path.rmdir()
            else:
                path.unlink()
        if receipt.exists():
            receipt.unlink()
        destination.rmdir()
    state.rmdir()
    install.RECOVERY.sync(state.parent)
