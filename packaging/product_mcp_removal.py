"""Admit optional MCP state before capture; erase only journal-bound survivors."""

import os
from pathlib import Path

import immutable_store as store
import managed_installation as install
import managed_release as release
import managed_update_journal as journal
import mcp_environment as mcp


def capture(payload: Path) -> store.McpRemoval | None:
    state = payload / "state"
    if not os.path.lexists(state):
        return None
    store.physical(state)
    if {child.name for child in state.iterdir()} != {"mcp"}:
        raise ValueError("unowned installation state")
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
    if proof.mcp is None:
        if os.path.lexists(state):
            raise ValueError("unrecorded installation state")
        return
    raw = install.read_record(bundle / "mcp-receipt.json", mcp.MAX_RECORD)
    expected = inventory(raw, root / "installation", proof)
    if not os.path.lexists(state):
        return
    store.physical(state)
    if journal.Identity.observe(state) != proof.mcp.stateIdentity:
        raise ValueError("MCP state replaced")
    if {child.name for child in state.iterdir()} - {"mcp"}:
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
    if proof.mcp is None:
        return
    state = root / "installation/state"
    if not os.path.lexists(state):
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
