"""Payload and immutable-store adapter for the existing uninstall retirement journal.

The caller owns workspace retirement and its management/registration locks. This
adapter never retires a Host, removes workspace profiles, or cleans registrations.
"""

import argparse
import os
import zipfile
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal

import immutable_store
import managed_installation as install
import managed_product as product
import managed_product_installation as activation
import managed_release as release
import managed_update_journal as journal
import product_mcp_removal
import product_removal_recovery as recovery


class Failure(StrEnum):
    ADMISSION = "REMOVAL_ADMISSION_REJECTED"
    FILESYSTEM = "REMOVAL_FILESYSTEM_REJECTED"
    PUBLICATION_RETRY = "PUBLICATION_INTERRUPTED_RETRY_SAME_SELECTION"


class Stage(StrEnum):
    ADMISSION = "ADMISSION"
    RETAIN_EXECUTION = "RETAIN_EXECUTION"
    IMMUTABLE_ENTRIES = "IMMUTABLE_ENTRIES"
    CONTROL_PAYLOAD = "CONTROL_PAYLOAD"


@dataclass(frozen=True)
class Removed:
    installationRoot: str
    status: Literal["CONTROL_REMOVED"] = "CONTROL_REMOVED"


@dataclass(frozen=True)
class Rejected:
    failure: Failure
    stage: Stage
    status: Literal["REMOVAL_REJECTED"] = "REMOVAL_REJECTED"


def payload_directories(root: Path, document: object, *, owned_state: bool = False) -> None:
    if not release.mapping(document) or not release.sequence(files := document.get("payloadFiles")):
        raise ValueError("payload inventory")
    activation.verify_client_configuration(root, partial=True)
    directories: set[Path] = {Path("config")}
    for item in files:
        if not release.mapping(item) or not isinstance(name := item.get("path"), str):
            raise ValueError("payload entry")
        directories.update(Path(name).parents)
    if any(
        path.is_dir()
        and path.relative_to(root) not in directories
        and not (owned_state and path.relative_to(root).parts[0] == "state")
        for path in root.rglob("*")
    ):
        raise ValueError("foreign payload directory")


def remove(root: Path, path: Path) -> Removed | Rejected:
    stage = Stage.ADMISSION
    try:
        if (
            not root.is_absolute()
            or root.resolve(strict=True) != root
            or not root.is_dir()
            or root.stat().st_uid != os.getuid()
            or os.path.lexists(root / "recovery/replacement")
        ):
            raise ValueError("managed root")
        payload = root / "installation"
        raw_journal = release.document(
            install.read_record(path, install.LIFECYCLE.UNINSTALL_JOURNAL_MAXIMUM_BYTES)
        )
        if not release.mapping(raw_journal):
            raise ValueError("uninstall journal")
        recorded = install.LIFECYCLE.journal_identity(raw_journal.get("controlIdentity"))
        present = os.path.lexists(payload)
        if present and (payload.resolve(strict=True) != payload or not payload.is_dir()):
            raise ValueError("payload root")
        identity = install.LIFECYCLE.FileIdentity.observe(payload) if present else recorded
        selected = install.LIFECYCLE.ProductRemovalRoot(payload, identity)
        admitted = install.LIFECYCLE.admit_uninstall_journal(path, selected)
        record = admitted[0]
        if not isinstance(record, install.LIFECYCLE.ProductUninstallRetirementJournal):
            raise ValueError("product retirement required")
        reference = record.product
        if not isinstance(reference, product.ProductReference):
            raise ValueError("product reference")
        receipt = release.document(install.read_record(root / "management.json", 65536))
        if (
            not release.mapping(receipt)
            or receipt.get("schemaVersion") != 3
            or receipt.get("installationRoot") != str(root)
            or receipt.get("executable") != record.executable
            or receipt.get("executableSha256") != record.executableSha256
            or receipt.get("product") != release.document(install.encoded(reference))
        ):
            raise ValueError("retirement receipt binding")
        retired = record.type == install.LIFECYCLE.UninstallPhase.CONTROL_RETIRED
        manifest = payload / "installation.json"
        if present and os.path.lexists(manifest):
            installation = install.LIFECYCLE.Installation.admit(
                str(payload), retired_product=record if retired else None
            )
            document = release.document(install.read_record(manifest, 67_108_864))
            if (
                not release.mapping(document)
                or document.get("payloadIdentity") != "sha256:" + reference.buildRecordSha256
                or document.get("semanticVersion") != reference.version
                or document.get("schemaVersion") != 3
                or document.get("externalAnchors") != []
                or {entry.name for entry in payload.iterdir()}
                - {"bin", "lib", "share", "config", "installation.json", "state"}
            ):
                raise ValueError("product payload identity")
            # State is independently admitted below, before recovery capture or deletion.
            payload_directories(payload, document, owned_state=True)
            if not retired:
                management = activation.read_management(
                    install.read_record(root / "management.json"),
                    root,
                    Path(record.executable),
                    payload,
                )
                if management.product != reference:
                    raise ValueError("installed product changed")
        elif not retired or (present and any(payload.iterdir())):
            raise ValueError("unproven residual payload")
        # Retain the private interpreter before any self-removal, and capture every
        # immutable entry from its own original evidence before deleting any entry.
        stage = Stage.RETAIN_EXECUTION
        proof = recovery.prepare(root, path)
        if present and os.path.lexists(manifest):
            if release.file_digest(manifest) != proof.installationManifestSha256:
                raise ValueError("original installation inventory changed")
        stage = Stage.IMMUTABLE_ENTRIES
        entries = recovery.preflight(root, proof, partial=True)
        for item, evidence in entries:
            immutable_store.erase(immutable_store.entry(root, item), evidence, item)
        product_mcp_removal.erase(root, recovery.location(root), proof)
        stage = Stage.CONTROL_PAYLOAD
        if not present:
            return Removed(str(root))
        if not os.path.lexists(manifest):
            payload.rmdir()
            install.RECOVERY.sync(root)
            return Removed(str(root))
        admitted = install.LIFECYCLE.admit_uninstall_journal(path, selected)
        installation = install.LIFECYCLE.Installation.admit(
            str(payload), retired_product=admitted[0]
        )
        installation.revalidate()
        for directory in ("bin", "lib", "share", "config"):
            child = payload / directory
            if child.exists():
                install.LIFECYCLE.delete_tree(child)
        # Keep the inventory until every payload subtree is gone, so a partial retry
        # can reject foreign or changed survivors rather than inferring ownership.
        manifest.unlink()
        payload.rmdir()
        install.RECOVERY.sync(root)
        return Removed(str(root))
    except install.StageRejected as problem:
        if problem.failure.adapterCode == immutable_store.RecoveryAction.PUBLICATION_INTERRUPTED:
            return Rejected(Failure.PUBLICATION_RETRY, stage)
        raise
    except OSError:
        return Rejected(Failure.FILESYSTEM, stage)
    except (
        ValueError,
        TypeError,
        KeyError,
        zipfile.BadZipFile,
        journal.Rejected,
        install.LIFECYCLE.Rejected,
        install.RECOVERY.Rejected,
    ):
        return Rejected(Failure.ADMISSION, stage)


def main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--install-root", type=Path, required=True)
    _ = parser.add_argument("--uninstall-journal", type=Path, required=True)
    args = parser.parse_args(arguments)
    result = remove(args.install_root, args.uninstall_journal)
    print(install.encoded(result).decode(), end="")
    return 1 if isinstance(result, Rejected) else 0
