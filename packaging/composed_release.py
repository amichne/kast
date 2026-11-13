"""Assemble and qualify the complete archive on its native target."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from dataclasses import asdict, dataclass, replace
from enum import StrEnum
from pathlib import Path
from typing import TYPE_CHECKING, Literal

import composed_bundle as bundle
import managed_product
import managed_release as release
from component_probe import ComponentHarness
from composed_bundle import (
    BundleFailure,
    BundleRejected,
    PackedBundle,
    pack_bundle,
    sha256,
)
from composed_host_probe import ProbeReceipt, probe
from composition_platform import HostPlatform, host_platform
from container_probe import ContainerProbeReceipt
from container_probe import probe as probe_containers
from isolated_runtime import ZIPAPP
from managed_headless import (
    Input,
    Rejected,
    admit,
    object_fields,
    select_assembly_components,
    unpack_archive,
)
from managed_release import NativeCompositionEvidence

if TYPE_CHECKING:
    from composed_host import CompositionRejected
    from composition_descriptors import DescriptorRejected
    from composition_graph import GraphRejected
    from composition_index import IndexRejected

REPOSITORY = Path(__file__).resolve().parent.parent
LINUX_PACKAGES = {
    "gcc-12-base": (),
    "libstdc++6": ("libstdc++.so.6",),
    "libfontconfig1": ("libfontconfig.so.1",),
    "libfreetype6": ("libfreetype.so.6",),
    "libexpat1": ("libexpat.so.1",),
    "libext2fs2": ("libe2p.so.2",),
    "libpng16-16": ("libpng16.so.16",),
    "libbrotli1": ("libbrotlicommon.so.1", "libbrotlidec.so.1"),
    "libuuid1": ("libuuid.so.1",),
    "fonts-dejavu-core": (
        "DejaVuSans.ttf",
        "DejaVuSans-Bold.ttf",
        "DejaVuSansMono.ttf",
        "DejaVuSansMono-Bold.ttf",
        "DejaVuSerif.ttf",
        "DejaVuSerif-Bold.ttf",
    ),
}


class ReleaseFailure(StrEnum):
    INVALID_IDENTITY = "INVALID_IDENTITY"
    EXISTING_OUTPUT = "EXISTING_OUTPUT"
    UNSAFE_PATH = "UNSAFE_PATH"
    WRONG_PLATFORM = "WRONG_PLATFORM"
    INSTALLER_FAILED = "INSTALLER_FAILED"
    EFFECT_FAILED = "EFFECT_FAILED"
    LINUX_PACKAGE_IDENTITY = "LINUX_PACKAGE_IDENTITY"
    LINUX_PACKAGE_INVENTORY = "LINUX_PACKAGE_INVENTORY"
    LINUX_PACKAGE_LOCATION = "LINUX_PACKAGE_LOCATION"
    LINUX_PACKAGE_NOTICE = "LINUX_PACKAGE_NOTICE"
    LINUX_PACKAGE_IO = "LINUX_PACKAGE_IO"
    LINUX_PACKAGE_PROCESS = "LINUX_PACKAGE_PROCESS"


class ReleaseStage(StrEnum):
    ADMISSION = "ADMISSION"
    DONOR = "DONOR"
    INVENTORY = "INVENTORY"
    COMPOSITION = "COMPOSITION"
    BUNDLE = "BUNDLE"
    QUALIFICATION = "QUALIFICATION"
    LINUX_PACKAGE_ADMISSION = "LINUX_PACKAGE_ADMISSION"
    LINUX_PACKAGE_EXTRACTION = "LINUX_PACKAGE_EXTRACTION"
    LINUX_PACKAGE_PAYLOAD = "LINUX_PACKAGE_PAYLOAD"


class StageOutcome(StrEnum):
    STARTED = "STARTED"
    COMPLETE = "COMPLETE"
    REJECTED = "REJECTED"


@dataclass(frozen=True, slots=True)
class StageObservation:
    stage: ReleaseStage
    outcome: StageOutcome


def observe(stage: ReleaseStage, outcome: StageOutcome) -> None:
    print(json.dumps(asdict(StageObservation(stage, outcome))), file=sys.stderr, flush=True)


@dataclass(frozen=True, slots=True)
class ReleaseRejected:
    reason: ReleaseFailure
    subject: str


@dataclass(frozen=True, slots=True)
class ReleasePacked:
    sourceRevision: str
    version: str
    platform: HostPlatform
    bundle: PackedBundle
    # Assembly and native qualification are separate evidence boundaries.
    qualification: Literal["NOT_RUN"] = "NOT_RUN"


@dataclass(frozen=True, slots=True)
class QualificationReceipt:
    sourceRevision: str
    bundleSha256: str
    harnessSha256: str
    probe: ProbeReceipt
    containers: ContainerProbeReceipt | None = None
    qualification: Literal["COMPLETE"] = "COMPLETE"
    qualificationStage: Literal["COMPONENT_NATIVE"] = "COMPONENT_NATIVE"
    composition: NativeCompositionEvidence | None = None


@dataclass(frozen=True, slots=True)
class RuntimeCandidate:
    archive: Path
    sha256: str


@dataclass(frozen=True, slots=True)
class LinuxPackage:
    package: str
    source: str
    version: str
    architecture: str
    archiveSha256: str
    files: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class LinuxSupportPrepared:
    output: str
    sourceSuite: Literal["ubuntu-jammy"]
    packages: tuple[LinuxPackage, ...]


@dataclass(frozen=True, slots=True)
class LinuxSupportManifest:
    sourceSuite: str
    packages: tuple[LinuxPackage, ...]


@dataclass(frozen=True, slots=True)
class ScratchSpace:
    path: str
    freeBefore: int
    freeAfter: int


@dataclass(frozen=True, slots=True)
class AssemblyInputsDiscarded:
    space: tuple[ScratchSpace, ...]


type AssemblyResult = (
    ReleasePacked
    | ReleaseRejected
    | Rejected
    | CompositionRejected
    | DescriptorRejected
    | GraphRejected
    | IndexRejected
    | BundleRejected
)


def assemble_release(
    *,
    archive: Path,
    bootstrap: Path,
    assets: Path,
    root: Path,
    output: Path,
    version: str,
    revision: str,
    linux_support: Path | None = None,
    split_runtime: bool = True,
) -> AssemblyResult:
    """The workflow admits the exact clean source before building these inputs."""
    from composed_host import CompositionRejected, compose
    from composition_descriptors import DescriptorRejected
    from composition_graph import GraphRejected
    from composition_index import IndexRejected
    from composition_recipe import BUILD

    observe(ReleaseStage.ADMISSION, StageOutcome.STARTED)
    if (
        not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
        or not re.fullmatch(r"[a-f0-9]{40}", revision)
        or revision == "0" * 40
    ):
        observe(ReleaseStage.ADMISSION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "identity")
    if root.exists() or root.is_symlink() or output.exists() or output.is_symlink():
        observe(ReleaseStage.ADMISSION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.EXISTING_OUTPUT, "root or output")
    if (
        root.resolve() != root
        or output.resolve() != output
        or root.is_relative_to(output)
        or output.is_relative_to(root)
    ):
        observe(ReleaseStage.ADMISSION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.UNSAFE_PATH, "root or output")
    system = platform.system()
    target = host_platform(system, platform.machine())
    if not isinstance(target, HostPlatform):
        observe(ReleaseStage.ADMISSION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.WRONG_PLATFORM, "native target")
    assert isinstance(target, HostPlatform)
    inputs = Input(
        root / "p",
        archive,
        target.layout.archive_sha256,
        bootstrap,
        sha256(bootstrap),
        assets,
        version,
        BUILD,
    )
    admitted = admit(inputs)
    if isinstance(admitted, Rejected):
        observe(ReleaseStage.ADMISSION, StageOutcome.REJECTED)
        return admitted
    if target.is_linux != (linux_support is not None):
        observe(ReleaseStage.ADMISSION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "Linux support input")
    observe(ReleaseStage.ADMISSION, StageOutcome.COMPLETE)
    root.mkdir(mode=0o700)
    donor = root / "donor"
    donor.mkdir()
    observe(ReleaseStage.DONOR, StageOutcome.STARTED)
    source = unpack_archive(archive, donor, system)
    if isinstance(source, Rejected):
        observe(ReleaseStage.DONOR, StageOutcome.REJECTED)
        return source
    observe(ReleaseStage.DONOR, StageOutcome.COMPLETE)
    observe(ReleaseStage.INVENTORY, StageOutcome.STARTED)
    installer = REPOSITORY / "packaging/install-legacy.sh"
    inventory = select_assembly_components(source, admitted, installer)
    if isinstance(inventory, Rejected):
        observe(ReleaseStage.INVENTORY, StageOutcome.REJECTED)
        return inventory
    manifest = root / "donor-manifest.json"
    _ = manifest.write_text(json.dumps(asdict(inventory), indent=2) + "\n")
    observe(ReleaseStage.INVENTORY, StageOutcome.COMPLETE)
    observe(ReleaseStage.COMPOSITION, StageOutcome.STARTED)
    composed = compose(inputs, source, manifest, sha256(manifest), installer)
    if isinstance(
        composed, (Rejected, CompositionRejected, DescriptorRejected, GraphRejected, IndexRejected)
    ):
        observe(ReleaseStage.COMPOSITION, StageOutcome.REJECTED)
        return composed
    observe(ReleaseStage.COMPOSITION, StageOutcome.COMPLETE)
    # Only this call created the donor directory. Keep the inventory as evidence.
    shutil.rmtree(donor)
    output.mkdir()
    observe(ReleaseStage.BUNDLE, StageOutcome.STARTED)
    packed = pack_bundle(
        inputs.root,
        assets,
        bootstrap,
        sha256(inputs.root / "composition-manifest.json"),
        output / f"kast-composed-setup-v{version}-{target.value}.tar.gz",
        version,
        linux_support=linux_support,
    )
    if isinstance(packed, BundleRejected):
        observe(ReleaseStage.BUNDLE, StageOutcome.REJECTED)
        return packed
    path = Path(packed.archive)
    _ = path.with_name(path.name + ".sha256").write_text(f"{packed.sha256}  {path.name}\n")
    from runtime_archive import pack_runtime

    runtime = pack_runtime(
        inputs.root,
        output / f"kast-runtime-v{version}-{target.value}.zip",
        version,
        target,
        linux_support=linux_support,
    )
    if isinstance(runtime, (BundleRejected, Rejected)):
        observe(ReleaseStage.BUNDLE, StageOutcome.REJECTED)
        return runtime
    if split_runtime:
        from runtime_base import split

        with tempfile.TemporaryDirectory(prefix=".split-", dir=root) as temporary:
            _ = split(
                Path(runtime.archive), path, target, REPOSITORY / "packaging", Path(temporary)
            )
    packed = PackedBundle(str(path), sha256(path), path.stat().st_size)
    result = ReleasePacked(revision, version, target, packed)
    _ = (output / "assembly.json").write_text(json.dumps(asdict(result), indent=2) + "\n")
    observe(ReleaseStage.BUNDLE, StageOutcome.COMPLETE)
    return result


def discard_assembly_inputs(root: Path, inputs: Path) -> AssemblyInputsDiscarded:
    paths = (root, inputs)
    if any(path.resolve() != path or not path.is_dir() for path in paths):
        raise ValueError("assembly inputs must be canonical directories")
    if root.is_relative_to(inputs) or inputs.is_relative_to(root):
        raise ValueError("assembly inputs must be disjoint")
    before = tuple(shutil.disk_usage(path.parent).free for path in paths)
    for path in paths:
        # The assembled Host is sealed. Unlinking its files needs writable parents.
        for directory, _, _ in os.walk(path, followlinks=False):
            parent = Path(directory)
            mode = stat.S_IMODE(parent.stat().st_mode)
            if not mode & stat.S_IWUSR:
                parent.chmod(mode | stat.S_IWUSR)
        shutil.rmtree(path)
    return AssemblyInputsDiscarded(
        tuple(
            ScratchSpace(str(path.parent), free, shutil.disk_usage(path.parent).free)
            for path, free in zip(paths, before, strict=True)
        )
    )


def qualify_release(
    *,
    archive: Path,
    expected: str,
    version: str,
    revision: str,
    root: Path,
    fixture: Path,
    evidence: Path,
    sandbox: Path | None = None,
    runtime: RuntimeCandidate | None = None,
    harness: Path | None = None,
    harness_sha256: str | None = None,
) -> QualificationReceipt | ReleaseRejected | BundleRejected:
    from runtime_base import project_native, read_reference

    if runtime is None or read_reference(runtime.archive) is None:
        return _qualify_release(
            archive=archive,
            expected=expected,
            version=version,
            revision=revision,
            root=root,
            fixture=fixture,
            evidence=evidence,
            sandbox=sandbox,
            runtime=runtime,
            harness=harness,
            harness_sha256=harness_sha256,
        )
    if sha256(runtime.archive) != runtime.sha256 or sha256(archive) != expected:
        return BundleRejected(BundleFailure.DIGEST_MISMATCH, "split qualification inputs")
    with tempfile.TemporaryDirectory(prefix="kast-native-composition-") as temporary:
        native_runtime, native_bundle, composition = project_native(
            runtime.archive, archive, Path(temporary).resolve()
        )
        runtime_digest, bundle_digest = sha256(native_runtime), sha256(native_bundle)
        result = _qualify_release(
            archive=native_bundle,
            expected=bundle_digest,
            version=version,
            revision=revision,
            root=root,
            fixture=fixture,
            evidence=evidence,
            sandbox=sandbox,
            runtime=RuntimeCandidate(native_runtime, runtime_digest),
            harness=harness,
            harness_sha256=harness_sha256,
        )
        if not isinstance(result, QualificationReceipt):
            return result
        if result.containers is None or result.containers.runtimeSha256 != runtime_digest:
            return BundleRejected(BundleFailure.DIGEST_MISMATCH, "native composition proof")
        qualified = replace(
            result,
            bundleSha256=expected,
            composition=NativeCompositionEvidence(
                composition.base.sha256, runtime_digest, bundle_digest, runtime.sha256
            ),
        )
        _ = (evidence / "qualification.json").write_text(
            json.dumps(asdict(qualified), indent=2) + "\n"
        )
        return qualified


def _qualify_release(
    *,
    archive: Path,
    expected: str,
    version: str,
    revision: str,
    root: Path,
    fixture: Path,
    evidence: Path,
    sandbox: Path | None = None,
    runtime: RuntimeCandidate | None = None,
    harness: Path | None = None,
    harness_sha256: str | None = None,
) -> QualificationReceipt | ReleaseRejected | BundleRejected:
    """Qualify exact components without claiming installed-product admission."""
    observe(ReleaseStage.QUALIFICATION, StageOutcome.STARTED)
    if (
        not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
        or not re.fullmatch(r"[a-f0-9]{40}", revision)
        or revision == "0" * 40
    ):
        observe(ReleaseStage.QUALIFICATION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "identity")
    if any(path.exists() or path.is_symlink() for path in (root, fixture, evidence)):
        observe(ReleaseStage.QUALIFICATION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.EXISTING_OUTPUT, "qualification paths")
    if not re.fullmatch("[a-f0-9]{64}", expected) or sha256(archive) != expected:
        observe(ReleaseStage.QUALIFICATION, StageOutcome.REJECTED)
        return BundleRejected(BundleFailure.DIGEST_MISMATCH, "bundle archive")
    entry = installer_zipapp(archive, version, revision)
    if isinstance(entry, BundleRejected):
        observe(ReleaseStage.QUALIFICATION, StageOutcome.REJECTED)
        return entry
    if (
        runtime is None
        or harness is None
        or harness_sha256 is None
        or harness.is_symlink()
        or not harness.is_file()
        or not os.access(harness, os.X_OK)
        or not re.fullmatch("[a-f0-9]{64}", harness_sha256)
    ):
        observe(ReleaseStage.QUALIFICATION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "native component inputs")
    if sha256(harness) != harness_sha256 or sha256(runtime.archive) != runtime.sha256:
        return BundleRejected(BundleFailure.DIGEST_MISMATCH, "native component inputs")
    target = host_platform(platform.system(), platform.machine())
    if target not in (HostPlatform.MACOS_ARM64, HostPlatform.LINUX_X86_64):
        return ReleaseRejected(ReleaseFailure.WRONG_PLATFORM, "native component producer")
    assert isinstance(target, HostPlatform)
    with harness.open("rb") as stream:
        header = stream.read(20)
    if (
        target.is_linux
        and (header[:8] != b"\x7fELF\x02\x01\x01\x00" or header[18:20] != b"\x3e\x00")
        or not target.is_linux
        and header[:8] != b"\xcf\xfa\xed\xfe\x0c\x00\x00\x01"
    ):
        return ReleaseRejected(ReleaseFailure.WRONG_PLATFORM, "native component harness")
    evidence.mkdir(parents=True)
    root.mkdir(mode=0o700, parents=True)
    for directory in ("home", "tmp", "bundle", "components"):
        (root / directory).mkdir(mode=0o700)
    extracted = bundle.extract_bundle(archive, root / "bundle", expected)
    if isinstance(extracted, BundleRejected):
        return extracted
    metadata = bundle.read_manifest((extracted.source / "manifest.json").read_bytes())
    if isinstance(metadata, BundleRejected):
        return metadata
    if metadata.version != version or metadata.platform != target:
        return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "component bundle")
    inventory = bundle.examine_files(extracted.source, metadata.files)
    if isinstance(inventory, BundleRejected):
        return inventory
    components = release.stage_component_archives(
        release.StableSelection(version, "v" + version, release.StablePolicy.PIN),
        metadata.platformBuild.split(".")[0],
        root / "components",
        managed_product.BundledAssets(extracted.source / "assets"),
        target=target.value,
    )
    if isinstance(components, release.Rejected) or components.identity.buildRevision != revision:
        return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "component records")
    runtime_proof = root / "runtime-proof"
    manifest = managed_product.extract_runtime(runtime.archive, runtime_proof, version, target)
    if (
        manifest.platformBuild != metadata.platformBuild
        or manifest.distributionSha256 != metadata.sourceDistributionSha256
    ):
        return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "component runtime")
    managed_product.verify_composed_runtime(
        extracted.source, metadata, components.host_archive, runtime_proof, manifest
    )
    # Comparisons are complete; native execution uses the independent archives.
    _ = discard_assembly_inputs(root / "bundle", runtime_proof)
    control = components.control_root / "share/kast/libexec/kast-management"
    discovery = subprocess.run(
        [str(control), "--internal-entrypoint=query", "describe", "--help"],
        cwd=root,
        env={"PATH": os.defpath, "HOME": str(root / "home")},
        capture_output=True,
        timeout=30,
        check=False,
    )
    _ = (evidence / "control-discovery.stdout").write_bytes(discovery.stdout)
    _ = (evidence / "control-discovery.stderr").write_bytes(discovery.stderr)
    if discovery.returncode != 0 or any((root / "home").iterdir()):
        return ReleaseRejected(ReleaseFailure.EFFECT_FAILED, "passive Control discovery")
    _ = shutil.copytree(
        REPOSITORY / "experiments/host-observation/static-callback-fixture", fixture
    )
    _ = shutil.copytree(
        REPOSITORY / "experiments/host-observation/composed-host-fixture-overlay",
        fixture,
        dirs_exist_ok=True,
    )
    _ = shutil.copytree(REPOSITORY / "gradle/wrapper", fixture / "gradle/wrapper")
    _ = shutil.copy2(REPOSITORY / "gradlew", fixture / "gradlew")
    execution = ComponentHarness(
        harness,
        root,
        runtime.archive,
        runtime.sha256,
        version,
        control,
        sha256(control),
        revision,
        sandbox,
    )
    native = probe(execution, fixture, evidence / "native")
    containers = probe_containers(execution, fixture, control, evidence / "containers")
    if sha256(harness) != harness_sha256:
        return BundleRejected(BundleFailure.DIGEST_MISMATCH, "changed native harness")
    result = QualificationReceipt(revision, expected, harness_sha256, native, containers)
    _ = (evidence / "qualification.json").write_text(json.dumps(asdict(result), indent=2) + "\n")
    observe(ReleaseStage.QUALIFICATION, StageOutcome.COMPLETE)
    return result


def installer_zipapp(archive: Path, version: str, revision: str) -> bytes | BundleRejected:
    name = f"setup-kast/{ZIPAPP}"
    releases = {
        f"setup-kast/assets/kast-control-release-v{version}.json": (
            "CONTROL_RELEASE",
            "controlVersion",
        ),
        f"setup-kast/assets/kast-host-release-v{version}.json": (
            "HOST_RELEASE",
            "hostedPluginVersion",
        ),
    }
    entries: dict[str, bytes] = {}
    with tarfile.open(archive, "r|gz") as contents:
        for member in contents:
            if member.name != name and member.name not in releases:
                continue
            if member.name in entries or not member.isfile() or not 0 < member.size <= 1_048_576:
                return BundleRejected(BundleFailure.UNSAFE_PATH, member.name)
            source = contents.extractfile(member)
            assert source is not None
            with source:
                entries[member.name] = source.read()
    if name not in entries:
        return BundleRejected(BundleFailure.INVALID_MANIFEST, name)
    for component, (kind, version_key) in releases.items():
        data = entries.get(component)
        record: object = json.loads(data) if data is not None else None
        if (
            not object_fields(record)
            or record.get("type") != kind
            or record.get("sourceRevision") != revision
            or record.get(version_key) != version
        ):
            return BundleRejected(BundleFailure.INVALID_MANIFEST, component)
    return entries[name]


def prepare_linux_support(output: Path) -> LinuxSupportPrepared | ReleaseRejected:
    """Copy named runtime files and notices from APT-authenticated producer inputs."""
    selected = host_platform(platform.system(), platform.machine())
    if not isinstance(selected, HostPlatform) or not selected.is_linux:
        return ReleaseRejected(ReleaseFailure.WRONG_PLATFORM, "Linux support producer")
    if output.exists() or output.resolve() != output:
        return ReleaseRejected(ReleaseFailure.EXISTING_OUTPUT, "Linux support output")
    with tempfile.TemporaryDirectory(prefix="kast-support-", dir=output.parent) as temporary:
        work = Path(temporary).resolve()
        files = work / "support"
        for directory in ("lib", "fonts", "licenses"):
            (files / directory).mkdir(parents=True)
        sources = work / "sources.list"
        keyring = "/usr/share/keyrings/ubuntu-archive-keyring.gpg"
        architecture = "arm64" if selected == HostPlatform.LINUX_ARM64 else "amd64"
        repository = (
            "https://ports.ubuntu.com/ubuntu-ports"
            if selected == HostPlatform.LINUX_ARM64
            else "https://archive.ubuntu.com/ubuntu"
        )
        security = (
            repository
            if selected == HostPlatform.LINUX_ARM64
            else "https://security.ubuntu.com/ubuntu"
        )
        signed = f"deb [arch={architecture} signed-by={keyring}]"
        _ = sources.write_text(
            f"{signed} {repository} jammy main\n"
            f"{signed} {repository} jammy-updates main\n"
            f"{signed} {security} jammy-security main\n"
        )
        lists = work / "lists"
        lists.mkdir()
        status = work / "status"
        status.touch()
        # APT verifies the archive signature and package hashes in a producer-owned index.
        apt = [
            "apt-get",
            "-o",
            f"Dir::Etc::sourcelist={sources}",
            "-o",
            "Dir::Etc::sourceparts=-",
            "-o",
            f"Dir::State::lists={lists}",
            "-o",
            f"Dir::State::status={status}",
            "-o",
            "APT::Get::List-Cleanup=false",
            "-o",
            "Acquire::Languages=none",
            "-o",
            "APT::Update::Error-Mode=any",
        ]
        for command in (["update"], ["download", *LINUX_PACKAGES]):
            _ = subprocess.run([*apt, *command], cwd=work, stdout=sys.stderr, check=True)
        packages = prepare_linux_packages(tuple(sorted(work.glob("*.deb"))), files)
        if isinstance(packages, ReleaseRejected):
            return packages
        if sorted(record.package for record in packages) != sorted(LINUX_PACKAGES):
            return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "Linux support packages")
        _ = (files / "fonts.conf").write_text(
            '<fontconfig><dir prefix="relative">fonts</dir>'
            '<cachedir prefix="xdg">fontconfig</cachedir></fontconfig>\n'
        )
        result = LinuxSupportPrepared(str(output), "ubuntu-jammy", packages)
        _ = (files / "licenses/packages.json").write_text(
            json.dumps(asdict(LinuxSupportManifest(result.sourceSuite, packages)), indent=2) + "\n"
        )
        _ = files.rename(output)
    return result


@dataclass(frozen=True, slots=True)
class _LinuxPackageInput:
    archive: Path
    name: str
    source: str
    version: str
    architecture: str
    archive_sha256: str

    @property
    def directory(self) -> Path:
        return self.archive.with_suffix("")


def read_linux_package(deb: Path, native: str) -> _LinuxPackageInput | ReleaseRejected:
    def field(name: str) -> str:
        return subprocess.check_output(["dpkg-deb", "-f", str(deb), name], text=True).strip()

    name, version, architecture = field("Package"), field("Version"), field("Architecture")
    if name not in LINUX_PACKAGES or not version or architecture not in {native, "all"}:
        return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_IDENTITY, "Linux support")
    return _LinuxPackageInput(
        deb, name, field("Source") or name, version, architecture, sha256(deb)
    )


def prepare_linux_packages(
    archives: tuple[Path, ...], files: Path
) -> tuple[LinuxPackage, ...] | ReleaseRejected:
    """Admit inputs before extraction and resolve shared notices through admitted suppliers."""
    stage = ReleaseStage.LINUX_PACKAGE_ADMISSION
    observe(stage, StageOutcome.STARTED)
    if not archives or len(archives) > len(LINUX_PACKAGES):
        observe(stage, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_IDENTITY, "Linux support")
    try:
        native = subprocess.check_output(["dpkg", "--print-architecture"], text=True).strip()
        inputs: list[_LinuxPackageInput] = []
        for archive in archives:
            admitted = read_linux_package(archive, native)
            if isinstance(admitted, ReleaseRejected):
                observe(stage, StageOutcome.REJECTED)
                return admitted
            inputs.append(admitted)
        if not inputs or len({item.name for item in inputs}) != len(inputs):
            observe(stage, StageOutcome.REJECTED)
            return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_IDENTITY, "Linux support")
        observe(stage, StageOutcome.COMPLETE)
        stage = ReleaseStage.LINUX_PACKAGE_EXTRACTION
        observe(stage, StageOutcome.STARTED)
        for item in inputs:
            _ = subprocess.run(
                ["dpkg-deb", "-x", str(item.archive), str(item.directory)], check=True
            )
        observe(stage, StageOutcome.COMPLETE)
        stage = ReleaseStage.LINUX_PACKAGE_PAYLOAD
        observe(stage, StageOutcome.STARTED)
        packages: list[LinuxPackage] = []
        for item in inputs:
            copied = copy_linux_package(item, tuple(inputs), files)
            if isinstance(copied, ReleaseRejected):
                observe(stage, StageOutcome.REJECTED)
                return copied
            packages.append(copied)
        observe(stage, StageOutcome.COMPLETE)
        return tuple(packages)
    except OSError:
        observe(stage, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_IO, "Linux support")
    except subprocess.SubprocessError:
        observe(stage, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_PROCESS, "Linux support")


def linux_package_notice(
    item: _LinuxPackageInput, inputs: tuple[_LinuxPackageInput, ...]
) -> Path | ReleaseRejected:
    notice = (item.directory / "usr/share/doc" / item.name / "copyright").resolve()
    if not notice.is_relative_to(item.directory):
        return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_LOCATION, "Linux support notice")
    if notice.is_file():
        return notice
    # Debian's libstdc++6 documentation is a relative link to gcc-12-base.
    # Keep each archive's file ownership; do not merge extracted package trees.
    relative = notice.relative_to(item.directory)
    suppliers = tuple(
        supplier
        for supplier in inputs
        if relative == Path("usr/share/doc") / supplier.name / "copyright"
        and supplier.name != item.name
        and supplier.source == item.source
        and supplier.version == item.version
    )
    if len(suppliers) != 1:
        return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_NOTICE, "Linux support notice")
    supplier = suppliers[0]
    provided = (supplier.directory / relative).resolve()
    if not provided.is_relative_to(supplier.directory) or not provided.is_file():
        return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_NOTICE, "Linux support notice")
    return provided


def copy_linux_package(
    item: _LinuxPackageInput, inputs: tuple[_LinuxPackageInput, ...], files: Path
) -> LinuxPackage | ReleaseRejected:
    notice = linux_package_notice(item, inputs)
    if isinstance(notice, ReleaseRejected):
        return notice
    installed: list[str] = []
    for member in LINUX_PACKAGES[item.name]:
        candidates = tuple(item.directory.rglob(member))
        if len(candidates) != 1:
            return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_INVENTORY, "Linux support file")
        source = candidates[0].resolve()
        if not source.is_relative_to(item.directory) or not source.is_file():
            return ReleaseRejected(ReleaseFailure.LINUX_PACKAGE_LOCATION, "Linux support file")
        # IntelliJ's JNA filesystem probe opens the unversioned name first.
        filename = "libe2p.so" if member == "libe2p.so.2" else member
        location = ("fonts/" if filename.endswith(".ttf") else "lib/") + filename
        _ = shutil.copyfile(source, files / location)
        installed.append(location)
    _ = shutil.copyfile(notice, files / "licenses" / (item.name + ".copyright"))
    return LinuxPackage(
        item.name,
        item.source,
        item.version,
        item.architecture,
        item.archive_sha256,
        tuple(installed),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    assemble = commands.add_parser("assemble")
    qualify = commands.add_parser("qualify")
    support = commands.add_parser("linux-support")
    discard = commands.add_parser("discard-assembly-inputs")
    _ = support.add_argument("--output", type=Path, required=True)
    _ = discard.add_argument("--root", type=Path, required=True)
    _ = discard.add_argument("--inputs", type=Path, required=True)
    for command in (assemble, qualify):
        for name in ("archive", "root"):
            _ = command.add_argument("--" + name, type=Path, required=True)
        _ = command.add_argument("--version", required=True)
        _ = command.add_argument("--source-revision", required=True)
    for name in ("bootstrap", "assets", "output"):
        _ = assemble.add_argument("--" + name, type=Path, required=True)
    for name in ("fixture", "evidence"):
        _ = qualify.add_argument("--" + name, type=Path, required=True)
    _ = qualify.add_argument("--archive-sha256", required=True)
    _ = qualify.add_argument("--runtime-archive", type=Path, required=True)
    _ = qualify.add_argument("--runtime-sha256", required=True)
    _ = qualify.add_argument("--harness", type=Path, required=True)
    _ = qualify.add_argument("--harness-sha256", required=True)
    _ = qualify.add_argument("--sandbox", type=Path)
    _ = assemble.add_argument("--linux-support", type=Path)
    _ = assemble.add_argument(
        "--full-native-archives",
        action="store_true",
        help="Keep full native archives for local acceptance instead of splitting a shared base",
    )
    args = parser.parse_args()
    try:
        if args.command == "linux-support":
            result = prepare_linux_support(args.output)
        elif args.command == "discard-assembly-inputs":
            result = discard_assembly_inputs(args.root, args.inputs)
        elif args.command == "assemble":
            result = assemble_release(
                archive=args.archive,
                bootstrap=args.bootstrap,
                assets=args.assets,
                root=args.root,
                output=args.output,
                version=args.version,
                revision=args.source_revision,
                linux_support=args.linux_support,
                split_runtime=not args.full_native_archives,
            )
        else:
            result = qualify_release(
                archive=args.archive,
                expected=args.archive_sha256,
                version=args.version,
                revision=args.source_revision,
                root=args.root,
                fixture=args.fixture,
                evidence=args.evidence,
                sandbox=args.sandbox,
                runtime=RuntimeCandidate(args.runtime_archive, args.runtime_sha256),
                harness=args.harness,
                harness_sha256=args.harness_sha256,
            )
    except (
        OSError,
        ValueError,
        tarfile.TarError,
        zipfile.BadZipFile,
        subprocess.SubprocessError,
    ) as error:
        result = ReleaseRejected(ReleaseFailure.EFFECT_FAILED, type(error).__name__)
    print(json.dumps(asdict(result), indent=2))
    return (
        0
        if isinstance(
            result,
            (ReleasePacked, QualificationReceipt, LinuxSupportPrepared, AssemblyInputsDiscarded),
        )
        else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
