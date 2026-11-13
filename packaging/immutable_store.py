"""Original immutable-entry evidence and deletion proofs owned by the uninstall journal."""

import hashlib
import os
import shutil
import stat
import tempfile
import zipfile
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, fields
from enum import StrEnum
from pathlib import Path
from typing import Literal

import managed_installation as install
import managed_product as product
import managed_release as release
import managed_update_contract as contract
import managed_update_journal as journal
from composition_platform import HostPlatform
from managed_headless import AssemblyRecord
from runtime_archive import RuntimeFile, RuntimeLink

PROVENANCE_FILES = (
    "provenance/archive.zip",
    "provenance/build-record.json",
    "provenance/qualification.json",
    "provenance/provenance.json",
)


class RecoveryAction(StrEnum):
    RUNTIME_DAMAGED = "RUNTIME_BYTES_DAMAGED_RUN_UPGRADE_REPAIR"
    BASE_DAMAGED = "BASE_BYTES_DAMAGED_RUN_UPGRADE_REPAIR"
    PUBLICATION_INTERRUPTED = "PUBLICATION_INTERRUPTED_RETRY_SAME_SELECTION"
    RESET_FILESYSTEM = "IMMUTABLE_RESET_REQUIRES_SAME_FILESYSTEM"


@dataclass(frozen=True)
class Provenance:
    kind: str
    target: str
    archiveSha256: str
    recordSha256: str
    manifestSha256: str
    schemaVersion: int = 1


@dataclass(frozen=True)
class ImmutableRemoval:
    kind: str
    archiveSha256: str
    identity: journal.Identity
    recordSha256: str
    manifestSha256: str
    provenanceSha256: str


@dataclass(frozen=True)
class McpRemoval:
    identity: journal.Identity
    stateIdentity: journal.Identity
    receiptSha256: str


@dataclass(frozen=True)
class ProductRemoval:
    phase: str
    executionIdentity: journal.Identity
    installationManifestSha256: str
    immutableRemovals: list[ImmutableRemoval]
    runtimeStoreIdentity: journal.Identity | None
    baseStoreIdentity: journal.Identity | None
    schemaVersion: int = 1
    mcp: McpRemoval | None = None


@dataclass(frozen=True)
class Evidence:
    provenance: Provenance
    record: bytes
    qualification: bytes
    manifest: bytes
    files: tuple[RuntimeFile | RuntimeLink, ...]


@dataclass(frozen=True)
class Publication:
    identity: journal.Identity
    provenance: Provenance
    record: str
    qualification: str
    manifest: str


class RepairPolicy(StrEnum):
    REJECT = "REJECT"
    PREPARE = "PREPARE"


@dataclass(frozen=True)
class DamagedEntry:
    root: Path
    identity: journal.Identity
    evidence: Evidence


@dataclass(frozen=True)
class ResetEntry:
    proof: ImmutableRemoval
    replacement: journal.Identity


@dataclass(frozen=True)
class ResetPlan:
    transaction: str
    entries: list[ResetEntry]


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def shape(
    value: object,
    kind: type[
        Provenance
        | ImmutableRemoval
        | ProductRemoval
        | McpRemoval
        | journal.Identity
        | Publication
        | ResetEntry
        | ResetPlan
    ],
) -> dict[object, object]:
    if not release.mapping(value) or set(value) != {item.name for item in fields(kind)}:
        raise ValueError("immutable evidence fields")
    return value


def sha(value: object) -> str:
    if not isinstance(value, str) or not release.DIGEST.fullmatch(value):
        raise ValueError("immutable evidence digest")
    return value


def identity(value: object) -> journal.Identity:
    item = shape(value, journal.Identity)
    values = tuple(item[key] for key in ("device", "inode", "owner"))
    if any(type(number) is not int or not 0 <= number < 2**63 for number in values):
        raise ValueError("immutable identity")
    device, inode, owner = values
    assert isinstance(device, int) and isinstance(inode, int) and isinstance(owner, int)
    if owner != os.getuid():
        raise ValueError("immutable owner")
    return journal.Identity(device, inode, owner)


def removal(value: object) -> ProductRemoval:
    item = shape(value, ProductRemoval)
    phase = item["phase"]
    if (
        type(item["schemaVersion"]) is not int
        or item["schemaVersion"] != 1
        or phase not in ("PREPARING", "PREPARED")
        or not release.sequence(entries := item["immutableRemovals"])
        or len(entries) > 1024
    ):
        raise ValueError("immutable removal proof")
    assert isinstance(phase, str)
    proofs: list[ImmutableRemoval] = []
    for value in entries:
        entry = shape(value, ImmutableRemoval)
        kind = entry["kind"]
        if kind not in ("RUNTIME", "BASE"):
            raise ValueError("immutable kind")
        assert isinstance(kind, str)
        proofs.append(
            ImmutableRemoval(
                kind,
                sha(entry["archiveSha256"]),
                identity(entry["identity"]),
                sha(entry["recordSha256"]),
                sha(entry["manifestSha256"]),
                sha(entry["provenanceSha256"]),
            )
        )
    if len({(proof.kind, proof.archiveSha256) for proof in proofs}) != len(proofs):
        raise ValueError("duplicate immutable proof")
    mcp = None
    if item["mcp"] is not None:
        fact = shape(item["mcp"], McpRemoval)
        mcp = McpRemoval(
            identity(fact["identity"]), identity(fact["stateIdentity"]), sha(fact["receiptSha256"])
        )
    return ProductRemoval(
        phase,
        identity(item["executionIdentity"]),
        sha(item["installationManifestSha256"]),
        proofs,
        identity(item["runtimeStoreIdentity"])
        if item["runtimeStoreIdentity"] is not None
        else None,
        identity(item["baseStoreIdentity"]) if item["baseStoreIdentity"] is not None else None,
        mcp=mcp,
    )


def publish(
    root: Path, admitted: product.AdmittedProduct, kind: Literal["RUNTIME", "BASE"]
) -> None:
    asset = admitted.target.runtime if kind == "RUNTIME" else admitted.target.runtimeBase
    if asset is None:
        raise ValueError("base asset absent")
    manifest = root / ("manifest.json" if kind == "RUNTIME" else "base-manifest.json")
    directory = root / "provenance"
    directory.mkdir(mode=0o700)
    _ = shutil.copyfile(admitted.runtime.parent / asset.name, directory / "archive.zip")
    _ = shutil.copyfile(
        admitted.runtime.parent / admitted.target.qualification.receipt.name,
        directory / "qualification.json",
    )
    _ = (directory / "build-record.json").write_bytes(admitted.record)
    _ = (directory / "provenance.json").write_bytes(
        install.encoded(
            Provenance(
                kind,
                admitted.target.target,
                asset.sha256,
                digest(admitted.record),
                release.file_digest(manifest),
            )
        )
    )


def evidence(directory: Path, manifest: bytes | None = None) -> Evidence:
    raw = install.read_record(directory / "provenance.json")
    record = install.read_record(directory / "build-record.json", release.MAXIMUM_RECORD_BYTES)
    qualification = install.read_record(directory / "qualification.json", 1048576)
    if manifest is None:
        manifest = install.read_record(directory / "manifest.json", product.MAXIMUM_MANIFEST_BYTES)
    return original(raw, record, qualification, manifest)


def original(raw: bytes, record: bytes, qualification: bytes, manifest: bytes) -> Evidence:
    item = shape(release.document(raw), Provenance)
    kind, target = item["kind"], item["target"]
    if (
        kind not in ("RUNTIME", "BASE")
        or target not in release.PRODUCT_TARGETS
        or type(item["schemaVersion"]) is not int
        or item["schemaVersion"] != 1
    ):
        raise ValueError("immutable provenance")
    assert isinstance(kind, str) and isinstance(target, str)
    selected = Provenance(
        kind,
        target,
        sha(item["archiveSha256"]),
        sha(item["recordSha256"]),
        sha(item["manifestSha256"]),
    )
    parsed = release.decode_product_record(record)
    if isinstance(parsed, release.Failure) or digest(record) != selected.recordSha256:
        raise ValueError("original build record")
    facts = next(entry for entry in parsed.targets if entry.target == target)
    asset = facts.runtime if kind == "RUNTIME" else facts.runtimeBase
    if asset is None or asset.sha256 != selected.archiveSha256:
        raise ValueError("original asset")
    receipt = release.document(qualification)
    if (
        digest(qualification) != facts.qualification.receipt.sha256
        or not release.mapping(receipt)
        or receipt.get("qualification") != "COMPLETE"
        or receipt.get("sourceRevision") != parsed.sourceRevision
        or receipt.get("bundleSha256") != facts.bundle.sha256
        or not release.mapping(receipt.get("probe"))
        or not release.native_receipt_matches(facts, receipt)
    ):
        raise ValueError("original qualification")
    if digest(manifest) != selected.manifestSha256:
        raise ValueError("original manifest")
    if kind == "RUNTIME":
        files = product.runtime_manifest(manifest, parsed.version, HostPlatform(target)).files
    else:
        metadata = release.document(manifest)
        if (
            not release.mapping(metadata)
            or metadata.get("target") != target
            or metadata.get("kind") != "KAST_RUNTIME_BASE_V1"
            or metadata.get("donorSha256") != facts.inputs.donorSha256
        ):
            raise ValueError("original base manifest")
        files = product.runtime_entries(metadata.get("files"))
    return Evidence(selected, record, qualification, manifest, files)


def admit(root: Path, kind: str, archive_sha: str, *, destination: Path | None = None) -> Evidence:
    result = original_entry(root, kind, archive_sha)
    verify(root, result, partial=False, destination=destination)
    return result


def original_entry(
    root: Path, kind: str, archive_sha: str, *, source: Path | None = None
) -> Evidence:
    physical(root)
    directory = root / "provenance"
    physical(directory)
    required = {
        "archive.zip",
        "build-record.json",
        "qualification.json",
        "provenance.json",
    }
    names = {path.name for path in directory.iterdir()}
    if names != required and not (source is not None and names == required - {"archive.zip"}):
        raise ValueError("immutable provenance inventory")
    archive = directory / "archive.zip"
    if os.path.lexists(archive) and (
        archive.is_symlink()
        or not archive.is_file()
        or archive.stat().st_uid != os.getuid()
        or archive.stat().st_nlink != 1
    ):
        raise ValueError("retained original archive")
    if (
        not archive.exists()
        or not 0 < archive.stat().st_size <= product.MAXIMUM_RUNTIME_BYTES
        or release.file_digest(archive) != archive_sha
    ):
        if (
            source is None
            or source.resolve() != source
            or not source.is_file()
            or not 0 < source.stat().st_size <= product.MAXIMUM_RUNTIME_BYTES
            or release.file_digest(source) != archive_sha
        ):
            raise ValueError("verified original archive unavailable")
        archive = source
    name = "manifest.json" if kind == "RUNTIME" else "base-manifest.json"
    with zipfile.ZipFile(archive) as incoming:
        headers = [entry for entry in incoming.infolist() if entry.filename == name]
        if len(headers) != 1 or headers[0].file_size > product.MAXIMUM_MANIFEST_BYTES:
            raise ValueError("original archive manifest")
        manifest = incoming.read(headers[0])
    # Admit from the retained original bytes, without a reconstructed qualification.
    result = evidence(directory, manifest)
    if result.provenance.kind != kind or result.provenance.archiveSha256 != archive_sha:
        raise ValueError("immutable artifact binding")
    return result


def repairable(root: Path, kind: str, archive_sha: str, *, source: Path | None = None) -> Evidence:
    selected = original_entry(root, kind, archive_sha, source=source)
    reset_layout(root, selected)
    return selected


def reset_layout(root: Path, selected: Evidence) -> None:
    # Original evidence proves expected identities, not ownership of foreign additions,
    # changed links, linked ancestors or replacement file kinds.
    expected = {item.path: item for item in inventory(root, selected)}
    parents = {parent.as_posix() for name in expected for parent in Path(name).parents}
    physical(root)
    if stat.S_IMODE(root.stat().st_mode) != 0o700:
        raise ValueError("immutable root permissions")
    count = 0
    for path in root.rglob("*"):
        count += 1
        if count > len(expected) + len(parents):
            raise ValueError("immutable inventory limit")
        name = path.relative_to(root).as_posix()
        if path.parent.resolve() != path.parent or path.lstat().st_uid != os.getuid():
            raise ValueError("immutable ownership changed")
        if path.is_dir() and not path.is_symlink() and name in parents:
            if path.stat().st_mode & 0o022:
                raise ValueError("foreign immutable directory permissions")
            continue
        item = expected.get(name)
        if isinstance(item, RuntimeLink):
            if not path.is_symlink() or os.readlink(path) != item.target:
                raise ValueError("immutable link ownership changed")
        elif (
            not isinstance(item, RuntimeFile)
            or path.is_symlink()
            or not path.is_file()
            or path.stat().st_nlink != 1
            or path.stat().st_mode & 0o022
        ):
            raise ValueError("foreign immutable entry")


def reusable(root: Path, kind: str, archive_sha: str) -> Evidence:
    selected = repairable(root, kind, archive_sha)
    try:
        verify(root, selected, partial=False)
    except ValueError:
        code = RecoveryAction.RUNTIME_DAMAGED if kind == "RUNTIME" else RecoveryAction.BASE_DAMAGED
        raise install.StageRejected(
            contract.Rejected(contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN, code)
        ) from None
    return selected


def deferred_reuse(
    root: Path, kind: str, archive_sha: str, policy: RepairPolicy, *, source: Path | None = None
) -> tuple[DamagedEntry, ...]:
    if policy == RepairPolicy.REJECT:
        _ = reusable(root, kind, archive_sha)
        return ()
    witness = journal.Identity.observe(root)
    selected = repairable(root, kind, archive_sha, source=source)
    try:
        verify(root, selected, partial=False)
    except ValueError:
        if journal.Identity.observe(root) != witness:
            raise ValueError("immutable root changed during admission") from None
        return (DamagedEntry(root, witness, selected),)
    return ()


def prepare_resets(
    prepared: Path,
    admitted: product.AdmittedProduct,
    damaged: tuple[DamagedEntry, ...],
    transaction: str,
) -> None:
    if not damaged:
        return
    directory = prepared / "immutable-reset"
    directory.mkdir(mode=0o700)
    entries: list[ResetEntry] = []
    for item in damaged:
        asset = (
            admitted.target.runtime
            if item.evidence.provenance.kind == "RUNTIME"
            else admitted.target.runtimeBase
        )
        assert asset is not None
        archive = admitted.runtime.parent / asset.name
        selected = repairable(
            item.root,
            item.evidence.provenance.kind,
            item.evidence.provenance.archiveSha256,
            source=archive,
        )
        if selected != item.evidence or journal.Identity.observe(item.root) != item.identity:
            raise ValueError("immutable original changed before reset preparation")
        seal = selected.provenance
        source = admitted.runtime
        if seal.kind == "BASE":
            assert admitted.base is not None
            source = admitted.base[0]
        ready = directory / (seal.kind + "-" + seal.archiveSha256)
        _ = shutil.copytree(source, ready, symlinks=True)
        (ready / "provenance").mkdir(mode=0o700)
        _ = shutil.copyfile(archive, ready / "provenance/archive.zip")
        # Preserve the original qualified build record, not the incoming selection's
        # qualification for a shared base. The archive/digest identity never changes.
        for name, raw in metadata(item.root, selected).items():
            path = ready / name
            if path.exists():
                path.chmod(0o600)
            _ = path.write_bytes(raw)
        for path in sorted(ready.rglob("*"), reverse=True):
            if not path.is_symlink():
                path.chmod(0o555 if path.is_dir() or path.stat().st_mode & 0o111 else 0o444)
                install.RECOVERY.sync(path)
        ready.chmod(0o700)
        install.RECOVERY.sync(ready)
        _ = admit(ready, seal.kind, seal.archiveSha256, destination=item.root)
        entries.append(
            ResetEntry(
                ImmutableRemoval(
                    seal.kind,
                    seal.archiveSha256,
                    item.identity,
                    seal.recordSha256,
                    seal.manifestSha256,
                    digest(install.encoded(seal)),
                ),
                journal.Identity.observe(ready),
            )
        )
    install.RECOVERY.save(directory / "receipt.json", ResetPlan(transaction, entries))


def reset_preflight(
    root: Path,
    control: Path,
    reference: product.ProductReference,
    record: journal.Replacement,
) -> tuple[tuple[ResetEntry, Evidence], ...]:
    directory = root / "recovery/replacement/immutable-reset"
    if not os.path.lexists(directory):
        return ()
    physical(directory)
    plan = shape(release.document(install.read_record(directory / "receipt.json")), ResetPlan)
    selected, reservation = record.product, record.reservation
    if (
        selected is None
        or reservation is None
        or plan["transaction"] != reservation.transaction
        or not release.sequence(values := plan["entries"])
        or not 0 < len(values) <= 2
    ):
        raise ValueError("immutable reset journal binding")
    parsed = release.decode_product_record(
        install.read_record(control / "share/kast/build-record.json")
    )
    if isinstance(parsed, release.Failure):
        raise ValueError("immutable reset selection")
    target = next(item for item in parsed.targets if item.target == reference.target)
    allowed = {"RUNTIME": target.runtime.sha256}
    if target.runtimeBase is not None:
        allowed["BASE"] = target.runtimeBase.sha256
    result: list[tuple[ResetEntry, Evidence]] = []
    for value in values:
        item = shape(value, ResetEntry)
        fact = shape(item["proof"], ImmutableRemoval)
        kind = fact["kind"]
        if not isinstance(kind, str) or kind not in allowed:
            raise ValueError("immutable reset kind")
        proof = ImmutableRemoval(
            kind,
            sha(fact["archiveSha256"]),
            identity(fact["identity"]),
            sha(fact["recordSha256"]),
            sha(fact["manifestSha256"]),
            sha(fact["provenanceSha256"]),
        )
        reset = ResetEntry(proof, identity(item["replacement"]))
        if proof.archiveSha256 != allowed[kind]:
            raise ValueError("immutable reset asset")
        destination = entry(root, proof)
        physical(destination.parent)
        if journal.Identity.observe(destination.parent).device != reset.replacement.device:
            raise install.StageRejected(
                contract.Rejected(
                    contract.Stage.RECOVERY,
                    contract.Failure.RECOVERY_UNPROVEN,
                    RecoveryAction.RESET_FILESYSTEM,
                )
            )
        ready = directory / (kind + "-" + proof.archiveSha256)
        candidate = ready if os.path.lexists(ready) else destination
        physical(candidate)
        if journal.Identity.observe(candidate) != reset.replacement:
            raise ValueError("immutable reset replacement changed")
        original = admit(candidate, kind, proof.archiveSha256, destination=destination)
        reset_layout(candidate, original)
        seal = original.provenance
        if seal.target != reference.target or (
            seal.recordSha256,
            seal.manifestSha256,
            digest(install.encoded(seal)),
        ) != (proof.recordSha256, proof.manifestSha256, proof.provenanceSha256):
            raise ValueError("immutable reset original changed")
        if candidate == ready and os.path.lexists(destination):
            if journal.Identity.observe(destination) != proof.identity:
                raise ValueError("damaged immutable root replaced")
            reset_layout(destination, original)
        if selected.phase in (journal.ManagedPhase.PREPARED, journal.ManagedPhase.FENCED):
            if candidate != ready or not os.path.lexists(destination):
                raise ValueError("immutable reset before retirement")
            # Before retirement, require intact original provenance as well as the
            # staged replacement; partial deletion is legal only after SETTLED.
            _ = repairable(
                destination, kind, proof.archiveSha256, source=ready / "provenance/archive.zip"
            )
        elif selected.phase != journal.ManagedPhase.SETTLED and candidate == ready:
            raise ValueError("immutable reset pending after Control replacement")
        result.append((reset, original))
    names = {item.proof.kind + "-" + item.proof.archiveSha256 for item, _ in result}
    if len(names) != len(result) or {path.name for path in directory.iterdir()} - names - {
        "receipt.json"
    }:
        raise ValueError("foreign immutable reset state")
    return tuple(result)


def finish_resets(
    root: Path, control: Path, reference: product.ProductReference, record: journal.Replacement
) -> None:
    selected = record.product
    assert selected is not None
    resets = reset_preflight(root, control, reference, record)
    for reset, original in resets:
        ready = (
            root
            / "recovery/replacement/immutable-reset"
            / (reset.proof.kind + "-" + reset.proof.archiveSha256)
        )
        if not os.path.lexists(ready):
            continue
        if (
            selected.phase != journal.ManagedPhase.SETTLED
            or selected.fence is None
            or install.RECOVERY.managed_shutdown_fence(root / "installation") != selected.fence
        ):
            raise install.reject(contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN)
        destination = entry(root, reset.proof)
        install.emit(contract.Stage.RECOVERY, "STARTED")
        erase_damaged(destination, original, reset.proof)
        install.rename_file_no_replace(ready, destination)
        install.RECOVERY.sync(ready.parent)
        install.RECOVERY.sync(destination.parent)
        _ = admit(destination, reset.proof.kind, reset.proof.archiveSha256)
        install.emit(contract.Stage.RECOVERY, "COMPLETED")


def erase_damaged(root: Path, selected: Evidence, proof: ImmutableRemoval) -> None:
    if not os.path.lexists(root):
        return
    if journal.Identity.observe(root) != proof.identity:
        raise ValueError("damaged immutable root replaced")
    reset_layout(root, selected)
    paths = sorted(root.rglob("*"), key=lambda path: len(path.parts), reverse=True)
    for path in (root, *(item for item in paths if item.is_dir() and not item.is_symlink())):
        path.chmod(0o700)
    for path in paths:
        if journal.Identity.observe(root) != proof.identity or path.parent.resolve() != path.parent:
            raise ValueError("immutable root changed during reset")
        if path.is_dir() and not path.is_symlink():
            path.rmdir()
        else:
            path.unlink()
    root.rmdir()
    install.RECOVERY.sync(root.parent)


def incoming_evidence(admitted: product.AdmittedProduct, kind: str) -> Evidence:
    if kind == "RUNTIME":
        asset = admitted.target.runtime
        manifest = install.read_record(
            admitted.runtime / "manifest.json", product.MAXIMUM_MANIFEST_BYTES
        )
    else:
        assert admitted.base is not None and admitted.target.runtimeBase is not None
        asset = admitted.target.runtimeBase
        manifest = install.read_record(
            admitted.base[0] / "base-manifest.json", product.MAXIMUM_MANIFEST_BYTES
        )
    return original(
        install.encoded(
            Provenance(
                kind,
                admitted.target.target,
                asset.sha256,
                digest(admitted.record),
                digest(manifest),
            )
        ),
        admitted.record,
        install.read_record(
            admitted.runtime.parent / admitted.target.qualification.receipt.name, 1048576
        ),
        manifest,
    )


@contextmanager
def publication(
    parent: Path, admitted: product.AdmittedProduct, kind: Literal["RUNTIME", "BASE"]
) -> Iterator[Path]:
    selected = incoming_evidence(admitted, kind)
    asset = admitted.target.runtime if kind == "RUNTIME" else admitted.target.runtimeBase
    assert asset is not None
    source = admitted.runtime.parent / asset.name
    recover_publications(parent, kind, source=source, target=admitted.target.target)
    # Allocation and receipt-removal crash windows must not leave unknown children
    # in the Control root, workspace store, or digest-only immutable stores.
    exterior = parent.parent.parent if kind == "RUNTIME" else parent.parent
    physical(exterior)
    stage = Path(tempfile.mkdtemp(prefix=".kast-" + kind.lower() + "-", dir=exterior))
    record = Publication(
        journal.Identity.observe(stage),
        selected.provenance,
        selected.record.decode(),
        selected.qualification.decode(),
        selected.manifest.decode(),
    )
    try:
        install.RECOVERY.save(stage / "publication.json", record)
        install.RECOVERY.sync(exterior)
        yield stage / "payload"
    finally:
        if os.path.lexists(stage / "publication.json"):
            clean_publication(
                stage, kind, store=parent, source=source, target=admitted.target.target
            )
        elif journal.Identity.observe(stage) == record.identity and not any(stage.iterdir()):
            stage.rmdir()
            install.RECOVERY.sync(exterior)
        else:
            raise ValueError("unproven publication reservation")


def recover_publications(
    parent: Path, kind: str, *, source: Path | None = None, target: str | None = None
) -> None:
    physical(parent)
    children = sorted(parent.iterdir())
    if len(children) > 1024:
        raise ValueError("immutable entry limit")
    for child in children:
        if child.name.startswith("." + kind.lower() + "-"):
            clean_publication(child, kind, source=source, target=target)


def clean_publication(
    stage: Path,
    kind: str,
    *,
    store: Path | None = None,
    source: Path | None = None,
    target: str | None = None,
) -> None:
    physical(stage)
    parent = stage.parent if store is None else store
    marker = stage / "publication.json"
    payload = stage / "payload"
    if os.path.lexists(marker):
        raw = install.read_record(marker, 67_108_864)
        item = shape(release.document(raw), Publication)
        witness = identity(item["identity"])
        texts = tuple(item[key] for key in ("record", "qualification", "manifest"))
        if not all(isinstance(value, str) for value in texts):
            raise ValueError("publication evidence")
        record, qualification, manifest = texts
        assert (
            isinstance(record, str) and isinstance(qualification, str) and isinstance(manifest, str)
        )
        selected = original(
            install.encoded(install.RECOVERY.decode(Provenance, item["provenance"])),
            record.encode(),
            qualification.encode(),
            manifest.encode(),
        )
    else:
        # Older publishers had no stage receipt. Only a complete original immutable
        # payload proves one of those directories; a familiar prefix proves nothing.
        raw = install.read_record(payload / "provenance/provenance.json")
        archive_sha = sha(shape(release.document(raw), Provenance)["archiveSha256"])
        selected = admit(payload, kind, archive_sha, destination=parent / archive_sha)
        witness = journal.Identity.observe(stage)
    if journal.Identity.observe(stage) != witness or stat.S_IMODE(stage.stat().st_mode) != 0o700:
        raise ValueError("publication stage replaced")
    if selected.provenance.kind != kind or (
        target is not None and selected.provenance.target != target
    ):
        raise ValueError("publication kind")
    if {child.name for child in stage.iterdir()} - {"publication.json", "payload"}:
        raise ValueError("foreign publication state")
    destination = parent / selected.provenance.archiveSha256
    expected = {entry.path: entry for entry in inventory(destination, selected)}
    parents = {parent.as_posix() for name in expected for parent in Path(name).parents}
    if os.path.lexists(payload):
        physical(payload)
    paths: list[Path] = []
    for path in payload.rglob("*"):
        if len(paths) >= len(expected) + len(parents):
            raise ValueError("publication entry limit")
        paths.append(path)
    paths.sort(key=lambda path: len(path.parts), reverse=True)
    prefixes: list[tuple[Path, RuntimeFile]] = []
    # Validate the entire survivor set before relaxing any permissions or deleting.
    for path in paths:
        name = path.relative_to(payload).as_posix()
        if path.parent.resolve() != path.parent or path.lstat().st_uid != os.getuid():
            raise ValueError("publication ownership changed")
        if path.is_dir() and not path.is_symlink():
            if name not in parents or path.stat().st_mode & 0o022:
                raise ValueError("foreign publication directory")
            continue
        entry = expected.get(name)
        if isinstance(entry, RuntimeLink):
            if not path.is_symlink() or os.readlink(path) != entry.target:
                raise ValueError("publication link changed")
        elif (
            not isinstance(entry, RuntimeFile)
            or path.is_symlink()
            or not path.is_file()
            or path.stat().st_nlink != 1
            or path.stat().st_mode & 0o022
            or path.stat().st_size > entry.bytes
        ):
            raise ValueError("foreign publication file")
        elif path.stat().st_size == entry.bytes and release.file_digest(path) == entry.sha256:
            continue
        else:
            prefixes.append((path, entry))
    if paths:
        archive = publication_archive(payload, selected, source)
        for path, entry in prefixes:
            publication_prefix(path, entry, destination, selected, archive)
    if journal.Identity.observe(stage) != witness:
        raise ValueError("publication changed before cleanup")
    if os.path.lexists(payload):
        for directory in (
            payload,
            *(path for path in paths if path.is_dir() and not path.is_symlink()),
        ):
            directory.chmod(0o700)
        install.LIFECYCLE.delete_tree(payload)
    if os.path.lexists(marker):
        marker.unlink()
    stage.rmdir()
    install.RECOVERY.sync(stage.parent)


def publication_prefix(
    path: Path, entry: RuntimeFile, destination: Path, selected: Evidence, archive: Path
) -> None:
    fixed = metadata(destination, selected)
    if entry.path in fixed:
        if not fixed[entry.path].startswith(install.read_record(path, entry.bytes)):
            raise ValueError("publication metadata prefix changed")
        return
    if entry.path == "provenance/archive.zip":
        with archive.open("rb") as original_file, path.open("rb") as partial:
            while block := partial.read(1024 * 1024):
                if original_file.read(len(block)) != block:
                    raise ValueError("publication archive prefix changed")
        return
    with zipfile.ZipFile(archive) as incoming:
        if entry.path in incoming.namelist():
            with incoming.open(entry.path) as original_file, path.open("rb") as partial:
                while block := partial.read(1024 * 1024):
                    if original_file.read(len(block)) != block:
                        raise ValueError("publication file prefix changed")
            return
    # A split runtime archive omits the vendor files. Its original build record
    # binds the separate base archive; never substitute an inferred base directory.
    parsed = release.decode_product_record(selected.record)
    assert not isinstance(parsed, release.Failure)
    facts = next(item for item in parsed.targets if item.target == selected.provenance.target)
    if selected.provenance.kind != "RUNTIME" or facts.runtimeBase is None:
        raise ValueError("publication member absent")
    base = facts.runtimeBase
    retained = destination.parent.parent.with_name("kast-runtime-bases") / base.sha256
    candidates = (archive.parent / base.name, retained / "provenance/archive.zip")
    for candidate in candidates:
        if (
            candidate.is_file()
            and candidate.resolve() == candidate
            and release.file_digest(candidate) == base.sha256
        ):
            with (
                zipfile.ZipFile(candidate) as incoming,
                incoming.open(entry.path) as original_file,
                path.open("rb") as partial,
            ):
                while block := partial.read(1024 * 1024):
                    if original_file.read(len(block)) != block:
                        raise ValueError("publication base prefix changed")
            return
    raise install.StageRejected(
        contract.Rejected(
            contract.Stage.RECOVERY,
            contract.Failure.RECOVERY_UNPROVEN,
            RecoveryAction.PUBLICATION_INTERRUPTED,
        )
    )


def publication_archive(payload: Path, selected: Evidence, source: Path | None) -> Path:
    retained = payload / "provenance/archive.zip"
    candidates = (source, retained) if source is not None else (retained,)
    for candidate in candidates:
        if (
            candidate.is_file()
            and candidate.resolve() == candidate
            and release.file_digest(candidate) == selected.provenance.archiveSha256
        ):
            marker = (
                "manifest.json" if selected.provenance.kind == "RUNTIME" else "base-manifest.json"
            )
            with zipfile.ZipFile(candidate) as incoming:
                metadata = incoming.getinfo(marker)
                if (
                    metadata.file_size > product.MAXIMUM_MANIFEST_BYTES
                    or incoming.read(metadata) != selected.manifest
                ):
                    raise ValueError("publication archive manifest changed")
            return candidate
    raise install.StageRejected(
        contract.Rejected(
            contract.Stage.RECOVERY,
            contract.Failure.RECOVERY_UNPROVEN,
            RecoveryAction.PUBLICATION_INTERRUPTED,
        )
    )


def physical(path: Path) -> None:
    if (
        not path.is_dir()
        or path.resolve(strict=True) != path
        or path.stat().st_uid != os.getuid()
        or path.stat().st_mode & 0o022
    ):
        raise ValueError("immutable directory ownership")


def regular(path: str, raw: bytes, executable: bool = False) -> RuntimeFile:
    return RuntimeFile(path, len(raw), digest(raw), executable)


def inventory(root: Path, selected: Evidence) -> tuple[RuntimeFile | RuntimeLink, ...]:
    seal = selected.provenance
    result = list(selected.files)
    result.extend(regular(name, raw) for name, raw in metadata(root, selected).items())
    parsed = release.decode_product_record(selected.record)
    assert not isinstance(parsed, release.Failure)
    facts = next(item for item in parsed.targets if item.target == seal.target)
    asset = facts.runtime if seal.kind == "RUNTIME" else facts.runtimeBase
    assert asset is not None
    result.append(RuntimeFile("provenance/archive.zip", asset.bytes, asset.sha256, False))
    return tuple(result)


def metadata(root: Path, selected: Evidence) -> dict[str, bytes]:
    seal = selected.provenance
    marker = "manifest.json" if seal.kind == "RUNTIME" else "base-manifest.json"
    result = {
        marker: selected.manifest,
        "provenance/build-record.json": selected.record,
        "provenance/qualification.json": selected.qualification,
        "provenance/provenance.json": install.encoded(seal),
    }
    if seal.kind == "RUNTIME":
        parsed = release.decode_product_record(selected.record)
        assert not isinstance(parsed, release.Failure)
        manifest = product.runtime_manifest(
            selected.manifest, parsed.version, HostPlatform(seal.target)
        )
        prefix = "host" if manifest.platform.is_linux else "host/Kast.app/Contents"
        result["runtime.json"] = install.encoded(
            product.RuntimeReceipt(seal.archiveSha256, seal.manifestSha256)
        )
        result[prefix + "/kast-headless.json"] = install.encoded(
            AssemblyRecord(
                1,
                str(root / prefix),
                manifest.distributionSha256,
                manifest.bootstrapSha256,
                manifest.platformBuild,
                manifest.platformBuild + "-IJ",
            )
        )
    return result


def verify(
    root: Path, selected: Evidence, *, partial: bool, destination: Path | None = None
) -> None:
    physical(root)
    if stat.S_IMODE(root.stat().st_mode) != 0o700:
        raise ValueError("immutable root mode")
    expected = {item.path: item for item in inventory(destination or root, selected)}
    parents = {parent.as_posix() for name in expected for parent in Path(name).parents}
    actual: set[str] = set()
    for path in root.rglob("*"):
        name = path.relative_to(root).as_posix()
        if (
            len(actual) > 100010
            or path.parent.resolve() != path.parent
            or path.lstat().st_uid != os.getuid()
        ):
            raise ValueError("immutable inventory ownership")
        if path.is_dir() and not path.is_symlink():
            modes = (0o555, 0o700) if partial else (0o555,)
            if name not in parents or stat.S_IMODE(path.stat().st_mode) not in modes:
                raise ValueError("foreign immutable directory")
            continue
        item = expected.get(name)
        if isinstance(item, RuntimeLink):
            if not path.is_symlink() or os.readlink(path) != item.target:
                raise ValueError("immutable link changed")
        elif (
            not isinstance(item, RuntimeFile)
            or path.is_symlink()
            or not path.is_file()
            or path.stat().st_size != item.bytes
            or release.file_digest(path) != item.sha256
            or stat.S_IMODE(path.stat().st_mode) != (0o555 if item.executable else 0o444)
        ):
            raise ValueError("immutable file changed")
        actual.add(name)
    if not partial and actual != set(expected):
        raise ValueError("incomplete immutable entry")


def entry(root: Path, proof: ImmutableRemoval) -> Path:
    parent = root.parent / (
        "kast-workspaces/runtimes" if proof.kind == "RUNTIME" else "kast-runtime-bases"
    )
    return parent / proof.archiveSha256


def snapshot_directory(bundle: Path, proof: ImmutableRemoval) -> Path:
    return bundle / "evidence" / proof.kind / proof.archiveSha256


def erase(root: Path, selected: Evidence, proof: ImmutableRemoval) -> None:
    if not os.path.lexists(root):
        return
    if journal.Identity.observe(root) != proof.identity:
        raise ValueError("immutable entry replaced")
    verify(root, selected, partial=True)
    marker = root / ("manifest.json" if proof.kind == "RUNTIME" else "base-manifest.json")
    paths = sorted(root.rglob("*"), key=lambda path: len(path.parts), reverse=True)
    # Directory permissions may be relaxed for erasure; file modes and bytes never are.
    for path in (root, *(item for item in paths if item.is_dir() and not item.is_symlink())):
        path.chmod(0o700)
    for path in paths:
        if journal.Identity.observe(root) != proof.identity or path.parent.resolve() != path.parent:
            raise ValueError("immutable entry changed during erasure")
        if path == marker:
            continue
        if path.is_dir() and not path.is_symlink():
            path.rmdir()
        else:
            path.unlink()
    if marker.exists():
        marker.unlink()
    root.rmdir()
    install.RECOVERY.sync(root.parent)
