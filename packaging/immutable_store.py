"""Original immutable-entry evidence and deletion proofs owned by the uninstall journal."""

import hashlib
import os
import shutil
import stat
import zipfile
from dataclasses import dataclass, fields
from pathlib import Path
from typing import Literal

import managed_installation as install
import managed_product as product
import managed_release as release
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


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def shape(
    value: object,
    kind: type[Provenance | ImmutableRemoval | ProductRemoval | McpRemoval | journal.Identity],
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
    record = install.read_record(directory / "build-record.json", release.MAXIMUM_RECORD_BYTES)
    parsed = release.decode_product_record(record)
    if isinstance(parsed, release.Failure) or digest(record) != selected.recordSha256:
        raise ValueError("original build record")
    facts = next(entry for entry in parsed.targets if entry.target == target)
    asset = facts.runtime if kind == "RUNTIME" else facts.runtimeBase
    if asset is None or asset.sha256 != selected.archiveSha256:
        raise ValueError("original asset")
    qualification = install.read_record(directory / "qualification.json", 1048576)
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
    if manifest is None:
        manifest = install.read_record(directory / "manifest.json", product.MAXIMUM_MANIFEST_BYTES)
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
    physical(root)
    directory = root / "provenance"
    physical(directory)
    if {path.name for path in directory.iterdir()} != {
        "archive.zip",
        "build-record.json",
        "qualification.json",
        "provenance.json",
    }:
        raise ValueError("immutable provenance inventory")
    archive = directory / "archive.zip"
    if (
        archive.is_symlink()
        or not archive.is_file()
        or archive.stat().st_uid != os.getuid()
        or not 0 < archive.stat().st_size <= product.MAXIMUM_RUNTIME_BYTES
        or release.file_digest(archive) != archive_sha
    ):
        raise ValueError("retained original archive")
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
    verify(root, result, partial=False, destination=destination)
    return result


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
    marker = "manifest.json" if seal.kind == "RUNTIME" else "base-manifest.json"
    result.extend(
        (
            regular(marker, selected.manifest),
            regular("provenance/build-record.json", selected.record),
            regular("provenance/qualification.json", selected.qualification),
            regular("provenance/provenance.json", install.encoded(seal)),
        )
    )
    parsed = release.decode_product_record(selected.record)
    assert not isinstance(parsed, release.Failure)
    facts = next(item for item in parsed.targets if item.target == seal.target)
    asset = facts.runtime if seal.kind == "RUNTIME" else facts.runtimeBase
    assert asset is not None
    result.append(RuntimeFile("provenance/archive.zip", asset.bytes, asset.sha256, False))
    if seal.kind == "RUNTIME":
        manifest = product.runtime_manifest(
            selected.manifest, parsed.version, HostPlatform(seal.target)
        )
        prefix = "host" if manifest.platform.is_linux else "host/Kast.app/Contents"
        result.extend(
            (
                regular(
                    "runtime.json",
                    install.encoded(
                        product.RuntimeReceipt(seal.archiveSha256, seal.manifestSha256)
                    ),
                ),
                regular(
                    prefix + "/kast-headless.json",
                    install.encoded(
                        AssemblyRecord(
                            1,
                            str(root / prefix),
                            manifest.distributionSha256,
                            manifest.bootstrapSha256,
                            manifest.platformBuild,
                            manifest.platformBuild + "-IJ",
                        )
                    ),
                ),
            )
        )
    return tuple(result)


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
