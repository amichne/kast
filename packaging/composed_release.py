"""Assemble and qualify the complete archive on its native target."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import TYPE_CHECKING, Literal

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

if TYPE_CHECKING:
    from composed_host import CompositionRejected
    from composition_descriptors import DescriptorRejected
    from composition_graph import GraphRejected
    from composition_index import IndexRejected

REPOSITORY = Path(__file__).resolve().parent.parent
LINUX_PACKAGES = {
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


class ReleaseStage(StrEnum):
    ADMISSION = "ADMISSION"
    DONOR = "DONOR"
    INVENTORY = "INVENTORY"
    COMPOSITION = "COMPOSITION"
    BUNDLE = "BUNDLE"
    QUALIFICATION = "QUALIFICATION"


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
    probe: ProbeReceipt
    containers: ContainerProbeReceipt | None = None
    qualification: Literal["COMPLETE"] = "COMPLETE"


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
    installer = REPOSITORY / "install.sh"
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
    result = ReleasePacked(revision, version, target, packed)
    _ = (output / "assembly.json").write_text(json.dumps(asdict(result), indent=2) + "\n")
    observe(ReleaseStage.BUNDLE, StageOutcome.COMPLETE)
    return result


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
) -> QualificationReceipt | ReleaseRejected | BundleRejected:
    """Run the exact archive installer before native fixture effects."""
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
    evidence.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix="kast-qualify-") as temporary:
        work = Path(temporary).resolve()
        bootstrap = work / "setup-kast.pyz"
        _ = bootstrap.write_bytes(entry)
        installed = subprocess.run(
            [
                sys.executable,
                "-I",
                str(bootstrap),
                "--archive",
                str(archive),
                "--archive-sha256",
                expected,
                "--version",
                version,
                "--root",
                str(root),
            ],
            cwd=work,
            env={"PATH": os.defpath, "HOME": str(work), "TMPDIR": str(work)},
            capture_output=True,
            timeout=1800,
            check=False,
        )
    _ = (evidence / "installation.json").write_bytes(installed.stdout)
    _ = (evidence / "installation.stderr").write_bytes(installed.stderr)
    if installed.returncode != 0:
        observe(ReleaseStage.QUALIFICATION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.INSTALLER_FAILED, str(installed.returncode))
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
    containers = (
        None
        if runtime is None
        else probe_containers(
            root, fixture, runtime.archive, runtime.sha256, evidence / "containers"
        )
    )
    native = probe(root, fixture, evidence / "native", sandbox)
    result = QualificationReceipt(revision, expected, native, containers)
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
        packages = tuple(prepare_linux_package(deb, files) for deb in sorted(work.glob("*.deb")))
        if sorted(record.package for record in packages) != sorted(LINUX_PACKAGES):
            return ReleaseRejected(ReleaseFailure.INVALID_IDENTITY, "Linux support packages")
        _ = (files / "fonts.conf").write_text(
            '<fontconfig><dir prefix="relative">fonts</dir>'
            '<cachedir prefix="xdg">fontconfig</cachedir></fontconfig>\n'
        )
        result = LinuxSupportPrepared(str(output), "ubuntu-jammy", packages)
        _ = (files / "licenses/packages.json").write_text(
            json.dumps(asdict(result), indent=2) + "\n"
        )
        _ = files.rename(output)
    return result


def prepare_linux_package(deb: Path, files: Path) -> LinuxPackage:
    def field(name: str) -> str:
        return subprocess.check_output(["dpkg-deb", "-f", str(deb), name], text=True).strip()

    name, version, architecture = field("Package"), field("Version"), field("Architecture")
    native_architecture = subprocess.check_output(
        ["dpkg", "--print-architecture"], text=True
    ).strip()
    if (
        name not in LINUX_PACKAGES
        or not version
        or architecture not in {native_architecture, "all"}
    ):
        raise ValueError("Linux support package identity")
    package_root = deb.with_suffix("")
    _ = subprocess.run(["dpkg-deb", "-x", str(deb), str(package_root)], check=True)
    installed: list[str] = []
    for member in LINUX_PACKAGES[name]:
        candidates = tuple(package_root.rglob(member))
        if len(candidates) != 1:
            raise ValueError("Linux support file inventory")
        source = candidates[0].resolve()
        if not source.is_relative_to(package_root) or not source.is_file():
            raise ValueError("Linux support file location")
        # IntelliJ's JNA filesystem probe opens the unversioned name first.
        filename = "libe2p.so" if member == "libe2p.so.2" else member
        location = ("fonts/" if filename.endswith(".ttf") else "lib/") + filename
        _ = shutil.copyfile(source, files / location)
        installed.append(location)
    notice = (package_root / "usr/share/doc" / name / "copyright").resolve()
    if not notice.is_relative_to(package_root) or not notice.is_file():
        raise ValueError("Linux support package notice")
    _ = shutil.copyfile(notice, files / "licenses" / (name + ".copyright"))
    return LinuxPackage(
        name, field("Source") or name, version, architecture, sha256(deb), tuple(installed)
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    assemble = commands.add_parser("assemble")
    qualify = commands.add_parser("qualify")
    support = commands.add_parser("linux-support")
    _ = support.add_argument("--output", type=Path, required=True)
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
    _ = qualify.add_argument("--sandbox", type=Path)
    _ = assemble.add_argument("--linux-support", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "linux-support":
            result = prepare_linux_support(args.output)
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
        0 if isinstance(result, (ReleasePacked, QualificationReceipt, LinuxSupportPrepared)) else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
