"""Offline admission of the existing composed bundle and its qualified runtime ZIP."""

import hashlib
import json
import os
import platform
import shutil
import stat
import sys
import tarfile
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Literal

import composed_bundle as bundle
import managed_release as release
import runtime_base
from composition_platform import HostPlatform, UnsupportedHost, host_platform
from runtime_archive import RuntimeFile, RuntimeLink, RuntimeManifest

MAXIMUM_RUNTIME_BYTES = 8_589_934_592
MAXIMUM_MANIFEST_BYTES = 16_777_216


@dataclass(frozen=True)
class AdmissionObservation:
    stage: release.Stage
    outcome: Literal["STARTED", "COMPLETED", "REJECTED"]


def observe(stage: release.Stage, outcome: Literal["STARTED", "COMPLETED", "REJECTED"]) -> None:
    print(json.dumps(asdict(AdmissionObservation(stage, outcome))), file=sys.stderr, flush=True)


@dataclass(frozen=True)
class RuntimeReceipt:
    archiveSha256: str
    manifestSha256: str
    schemaVersion: int = 1


@dataclass(frozen=True)
class ProductReference:
    releaseTag: str
    publicVersion: str | None
    version: str
    sourceRevision: str
    sourceTree: str
    buildRecordSha256: str
    target: str
    bundleSha256: str
    qualificationSha256: str
    runtimeRoot: str
    runtimeArchiveSha256: str
    runtimeManifestSha256: str
    policy: str
    schemaVersion: int = 1


@dataclass(frozen=True)
class AdmittedProduct:
    selection: release.ProductSelection
    target: release.ProductTarget
    record: bytes
    components: release.AdmittedComposition
    runtime: Path
    manifest: RuntimeManifest
    base: tuple[Path, runtime_base.BaseManifest, runtime_base.Composition] | None = None

    def reference(self, root: Path) -> ProductReference:
        selected = self.selection
        return ProductReference(
            selected.releaseTag,
            selected.publicVersion,
            selected.version,
            selected.record.sourceRevision,
            selected.record.sourceTree,
            selected.recordSha256,
            self.target.target,
            self.target.bundle.sha256,
            self.target.qualification.receipt.sha256,
            str(root.parent / "kast-workspaces/runtimes" / self.target.runtime.sha256),
            self.target.runtime.sha256,
            release.file_digest(self.runtime / "manifest.json"),
            selected.policy,
        )


def runtime_manifest(raw: bytes, version: str, target: HostPlatform) -> RuntimeManifest:
    value = release.document(raw)
    if (
        not release.mapping(value)
        or set(value)
        != {
            "schemaVersion",
            "kind",
            "version",
            "platform",
            "platformBuild",
            "distributionSha256",
            "bootstrapSha256",
            "files",
        }
        or type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
        or value["kind"] != "KAST_RUNTIME_V1"
        or value["version"] != version
        or value["platform"] != target.value
        or not release.sequence(value["files"])
    ):
        raise ValueError("runtime identity")
    build, donor, bootstrap = (
        value["platformBuild"],
        value["distributionSha256"],
        value["bootstrapSha256"],
    )
    if (
        not isinstance(build, str)
        or not build.startswith("262.")
        or not isinstance(donor, str)
        or not release.DIGEST.fullmatch(donor)
        or not isinstance(bootstrap, str)
        or not release.DIGEST.fullmatch(bootstrap)
    ):
        raise ValueError("runtime inputs")
    entries = runtime_entries(value["files"])
    prefix = "host" if target.is_linux else "host/Kast.app/Contents"
    boot = prefix + "/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar"
    if not any(
        isinstance(item, RuntimeFile) and item.path == boot and item.sha256 == bootstrap
        for item in entries
    ) or not any(
        isinstance(item, RuntimeFile)
        and item.path.startswith("components/plugins/kast-ide-hosted/lib/")
        for item in entries
    ):
        raise ValueError("runtime components")
    return RuntimeManifest(version, target, build, donor, bootstrap, entries)


def runtime_entries(value: object) -> tuple[RuntimeFile | RuntimeLink, ...]:
    if not release.sequence(value):
        raise ValueError("runtime files")
    entries: list[RuntimeFile | RuntimeLink] = []
    names: set[str] = set()
    for item in value:
        if not release.mapping(item):
            raise ValueError("runtime entry")
        path = item.get("path")
        if (
            not isinstance(path, str)
            or not bundle.safe_path(path)
            or "\n" in path
            or "\r" in path
            or not path.startswith(("host/", "components/plugins/"))
            or path in names
        ):
            raise ValueError("runtime path")
        names.add(path)
        if item.get("type") == "FILE" and set(item) == {
            "type",
            "path",
            "bytes",
            "sha256",
            "executable",
        }:
            size, digest, executable = item["bytes"], item["sha256"], item["executable"]
            if (
                type(size) is not int
                or size < 0
                or size > MAXIMUM_RUNTIME_BYTES
                or not isinstance(digest, str)
                or not release.DIGEST.fullmatch(digest)
                or type(executable) is not bool
            ):
                raise ValueError("runtime file")
            entries.append(RuntimeFile(path, size, digest, executable))
        elif item.get("type") == "SYMLINK" and set(item) == {"type", "path", "target"}:
            link = item["target"]
            if (
                not path.startswith("host/")
                or not isinstance(link, str)
                or not link
                or len(link.encode()) > 4096
                or any(c in link for c in "\x00\r\n\\:")
                or link.startswith("/")
            ):
                raise ValueError("runtime link")
            normalized = os.path.normpath(str(PurePosixPath(path).parent / link))
            if not normalized.startswith("host/"):
                raise ValueError("runtime link escape")
            entries.append(RuntimeLink(path, link))
        else:
            raise ValueError("runtime variant")
    if (
        not entries
        or len(entries) > 100_000
        or sum(item.bytes for item in entries if isinstance(item, RuntimeFile))
        > MAXIMUM_RUNTIME_BYTES
        or any(str(parent) in names for name in names for parent in PurePosixPath(name).parents)
    ):
        raise ValueError("runtime inventory")
    return tuple(entries)


def extract_runtime(
    archive: Path,
    root: Path,
    version: str,
    target: HostPlatform,
    *,
    base: tuple[Path, runtime_base.BaseManifest, runtime_base.Composition] | None = None,
) -> RuntimeManifest:
    with zipfile.ZipFile(archive) as incoming:
        entries = incoming.infolist()
        names = {item.filename for item in entries}
        if (
            len(entries) > 100_001
            or len(names) != len(entries)
            or any(item.is_dir() for item in entries)
        ):
            raise ValueError("runtime ZIP inventory")
        metadata = incoming.getinfo("manifest.json")
        if not 0 < metadata.file_size <= MAXIMUM_MANIFEST_BYTES:
            raise ValueError("runtime manifest size")
        raw = incoming.read(metadata)
        manifest = runtime_manifest(raw, version, target)
        base_names = {item.path for item in base[1].files} if base else set[str]()
        if base:
            if (
                runtime_base.reference(incoming.read(runtime_base.MARKER)) != base[2]
                or base[1].target != target.value
            ):
                raise ValueError("runtime base reference")
            if base[1].donorSha256 != manifest.distributionSha256 or any(
                item not in manifest.files for item in base[1].files
            ):
                raise ValueError("runtime base inventory binding")
        if names != ({item.path for item in manifest.files} - base_names) | {"manifest.json"} | (
            {runtime_base.MARKER} if base else set()
        ):
            raise ValueError("runtime ZIP extras")
        root.mkdir(mode=0o700)
        if base:
            runtime_base.overlay(base[0], base[1], root)
        for item in manifest.files:
            if item.path in base_names:
                continue
            output = root / item.path
            output.parent.mkdir(parents=True, exist_ok=True)
            entry = incoming.getinfo(item.path)
            if isinstance(item, RuntimeLink):
                if entry.file_size > 4096 or incoming.read(entry) != os.fsencode(item.target):
                    raise ValueError("runtime link bytes")
                continue
            if entry.file_size != item.bytes:
                raise ValueError("runtime size")
            with incoming.open(entry) as source, output.open("xb") as destination:
                shutil.copyfileobj(source, destination)
            if release.file_digest(output) != item.sha256:
                raise ValueError("runtime digest")
            output.chmod(0o555 if item.executable else 0o444)
        for item in manifest.files:
            if isinstance(item, RuntimeLink) and item.path not in base_names:
                (root / item.path).symlink_to(item.target)
        _ = (root / "manifest.json").write_bytes(raw)
    verify_runtime(root, manifest)
    return manifest


def verify_runtime(root: Path, manifest: RuntimeManifest, *, assembly: str | None = None) -> None:
    if root.resolve() != root:
        raise ValueError("runtime root")
    expected = {item.path for item in manifest.files} | {"manifest.json"}
    if assembly is not None:
        from immutable_store import PROVENANCE_FILES

        expected.update(("runtime.json", assembly))
        expected.update(PROVENANCE_FILES)
    actual = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if not path.is_dir() or path.is_symlink()
    }
    if actual != expected:
        raise ValueError("runtime inventory changed")
    for item in manifest.files:
        path = root / item.path
        if path.parent.resolve() != path.parent:
            raise ValueError("runtime linked ancestor")
        if isinstance(item, RuntimeLink):
            try:
                target = path.resolve(strict=True)
            except (OSError, RuntimeError) as failure:
                raise ValueError("runtime link target unavailable") from failure
            if (
                not path.is_symlink()
                or os.readlink(path) != item.target
                or not target.is_relative_to(root / "host")
            ):
                raise ValueError("runtime link changed")
        elif (
            path.is_symlink()
            or not path.is_file()
            or path.stat().st_size != item.bytes
            or release.file_digest(path) != item.sha256
            or stat.S_IMODE(path.stat().st_mode) != (0o555 if item.executable else 0o444)
        ):
            raise ValueError("runtime changed")


class BundledAssets:
    """Component admission reads only authenticated bundle members, never a network fallback."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def read(self, url: str, limit: int) -> bytes | release.TransferFailure:
        path = self.root / url.rsplit("/", 1)[-1]
        if not path.is_file() or path.is_symlink() or path.stat().st_size > limit:
            return release.TransferFailure.NOT_FOUND
        return path.read_bytes()

    def download(
        self, url: str, destination: Path, limit: int
    ) -> release.DownloadedFile | release.TransferFailure:
        path = self.root / url.rsplit("/", 1)[-1]
        if not path.is_file() or path.is_symlink() or path.stat().st_size > limit:
            return release.TransferFailure.NOT_FOUND
        with path.open("rb") as source, destination.open("xb") as output:
            shutil.copyfileobj(source, output)
        return release.DownloadedFile(
            destination, release.file_digest(destination), destination.stat().st_size
        )


def stage_product(
    selected: release.ProductSelection, raw: bytes, directory: Path, source: release.ReleaseSource
) -> AdmittedProduct | release.Rejected:
    phase = release.Stage.STAGING
    observe(phase, "STARTED")
    target = host_platform(platform.system(), platform.machine())
    if isinstance(target, UnsupportedHost) or target.value not in release.PRODUCT_TARGETS:
        return release.Rejected(
            release.Failure.PLATFORM_UNAVAILABLE, release.Stage.SELECTION, selected.version
        )
    try:
        if directory.resolve() != directory or not directory.is_dir() or any(directory.iterdir()):
            raise ValueError("owned empty staging required")
        facts = next(item for item in selected.record.targets if item.target == target.value)
        phase = release.Stage.DOWNLOAD
        observe(phase, "STARTED")
        for asset in facts.assets():
            path = directory / asset.name
            result = source.download(
                f"{release.RELEASE_URL}/{selected.releaseTag}/{asset.name}", path, asset.bytes
            )
            if (
                isinstance(result, release.TransferFailure)
                or result.path != path
                or path.is_symlink()
                or not path.is_file()
                or path.stat().st_size != asset.bytes
                or result.sha256 != asset.sha256
                or result.bytes != asset.bytes
                or release.file_digest(path) != asset.sha256
            ):
                raise ValueError("qualified asset bytes")
        phase = release.Stage.RECORDS
        observe(phase, "STARTED")
        receipt_path = directory / facts.qualification.receipt.name
        if receipt_path.stat().st_size > 1_048_576:
            raise ValueError("qualification limit")
        receipt = release.document(receipt_path.read_bytes())
        if (
            not release.mapping(receipt)
            or receipt.get("qualification") != "COMPLETE"
            or receipt.get("sourceRevision") != selected.record.sourceRevision
            or receipt.get("bundleSha256") != facts.bundle.sha256
            or not release.mapping(receipt.get("probe"))
        ):
            raise ValueError("native qualification")
        if not release.native_receipt_matches(facts, receipt):
            raise ValueError("qualified runtime")
        phase = release.Stage.ARCHIVE
        observe(phase, "STARTED")
        unpacked = directory / "bundle"
        unpacked.mkdir()
        base: tuple[Path, runtime_base.BaseManifest, runtime_base.Composition] | None = None
        if facts.runtimeBase:
            composition = runtime_base.Composition(facts.target, facts.runtimeBase)
            base_root = directory / "base"
            base_manifest = runtime_base.extract_base(
                directory / facts.runtimeBase.name, composition, base_root
            )
            if base_manifest.donorSha256 != facts.inputs.donorSha256 or not any(
                item.path == "composition_recipe.py"
                and item.sha256 == facts.inputs.compositionRecipeSha256
                for item in base_manifest.recipeInputs
            ):
                raise ValueError("base source inputs")
            base = (base_root, base_manifest, composition)
            contents = runtime_base.hydrate_bundle(
                directory / facts.bundle.name, unpacked, composition, base_root, base_manifest
            )
        else:
            extracted = bundle.extract_bundle(
                directory / facts.bundle.name, unpacked, facts.bundle.sha256
            )
            if isinstance(extracted, bundle.BundleRejected):
                raise ValueError("composed bundle")
            contents = extracted.source
        metadata = bundle.read_manifest((contents / "manifest.json").read_bytes())
        if (
            isinstance(metadata, bundle.BundleRejected)
            or metadata.version != selected.version
            or metadata.platform != target
            or metadata.sourceDistributionSha256 != facts.inputs.donorSha256
            or isinstance(bundle.examine_files(contents, metadata.files), bundle.BundleRejected)
        ):
            raise ValueError("composed inventory")
        actual = {
            path.relative_to(contents).as_posix()
            for path in contents.rglob("*")
            if path.is_file() or path.is_symlink()
        }
        if (
            actual != {item.path for item in metadata.files} | {"manifest.json"}
            or release.file_digest(contents / "composition-manifest.json")
            != metadata.compositionManifestSha256
            or release.file_digest(contents / "bootstrap.zip") != metadata.bootstrapArchiveSha256
        ):
            raise ValueError("composed identity")
        components_dir = directory / "components"
        components_dir.mkdir()
        phase = release.Stage.CONTRACT
        observe(phase, "STARTED")
        components = release.stage_component_archives(
            release.StableSelection(
                selected.version, "v" + selected.version, release.StablePolicy.PIN
            ),
            metadata.platformBuild.split(".")[0],
            components_dir,
            BundledAssets(contents / "assets"),
            target=target.value,
        )
        if isinstance(components, release.Rejected):
            observe(phase, "REJECTED")
            return components
        if (
            components.identity.buildRevision != selected.record.sourceRevision
            or components.identity.sourceTree != selected.record.sourceTree
            or release.file_digest(components.control_root / "share/kast/libexec/kast-management")
            != facts.qualification.controlSha256
        ):
            raise ValueError("qualified Control")
        runtime_root = directory / "runtime"
        phase = release.Stage.ARCHIVE
        observe(phase, "STARTED")
        manifest = extract_runtime(
            directory / facts.runtime.name, runtime_root, selected.version, target, base=base
        )
        if (
            manifest.distributionSha256 != facts.inputs.donorSha256
            or manifest.platformBuild != metadata.platformBuild
        ):
            raise ValueError("runtime donor")
        verify_composed_runtime(contents, metadata, components.host_archive, runtime_root, manifest)
        observe(release.Stage.STAGING, "COMPLETED")
        return AdmittedProduct(selected, facts, raw, components, runtime_root, manifest, base)
    except (
        OSError,
        ValueError,
        KeyError,
        UnicodeError,
        RecursionError,
        zipfile.BadZipFile,
        tarfile.TarError,
    ):
        observe(phase, "REJECTED")
        return release.Rejected(release.Failure.ARTIFACT_INVALID, phase, selected.version)


def verify_composed_runtime(
    contents: Path,
    metadata: bundle.BundleManifest,
    host_archive: Path,
    runtime_root: Path,
    manifest: RuntimeManifest,
) -> None:
    """Bind the same vendor, bootstrap, and Host bytes before qualification or installation."""
    prefix = "host" if manifest.platform.is_linux else "host/Kast.app/Contents"
    runtime_entries = {item.path: item for item in manifest.files}
    for entry in metadata.files:
        if not entry.path.startswith("runtime/"):
            continue
        runtime_entry = runtime_entries.get(prefix + "/" + entry.path.removeprefix("runtime/"))
        if isinstance(runtime_entry, RuntimeFile):
            if entry.kind.value != "FILE" or (entry.sha256, entry.bytes) != (
                runtime_entry.sha256,
                runtime_entry.bytes,
            ):
                raise ValueError("composed runtime mismatch")
        elif isinstance(runtime_entry, RuntimeLink):
            if (
                entry.kind.value != "SYMLINK"
                or entry.sha256 != hashlib.sha256(os.fsencode(runtime_entry.target)).hexdigest()
            ):
                raise ValueError("composed runtime link mismatch")
        else:
            raise ValueError("composed runtime missing")
    with zipfile.ZipFile(contents / "bootstrap.zip") as bootstrap:
        files = [item for item in bootstrap.infolist() if not item.is_dir()]
        if (
            len(files) != 1
            or files[0].filename != "kast-managed-headless/lib/kast-managed-headless.jar"
            or files[0].file_size > release.MAXIMUM_PAYLOAD_BYTES
            or hashlib.sha256(bootstrap.read(files[0])).hexdigest() != manifest.bootstrapSha256
        ):
            raise ValueError("runtime bootstrap mismatch")
    with zipfile.ZipFile(host_archive) as host:
        files = [entry for entry in host.infolist() if not entry.is_dir()]
        expected = {"components/plugins/" + entry.filename for entry in files}
        actual_host = {item.path for item in manifest.files if item.path.startswith("components/")}
        if expected != actual_host:
            raise ValueError("runtime Host inventory")
        for entry in files:
            if hashlib.sha256(host.read(entry)).hexdigest() != release.file_digest(
                runtime_root / "components/plugins" / entry.filename
            ):
                raise ValueError("runtime Host digest")
