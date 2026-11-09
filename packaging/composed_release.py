"""Build a portable Linux bundle with the existing composition and installer owners."""

from __future__ import annotations

import argparse
import json
import platform
import re
import shutil
import sys
import tarfile
import zipfile
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal

from composed_bundle import (
    BundleInstalled,
    BundleRejected,
    PackedBundle,
    install_bundle,
    pack_bundle,
    sha256,
)
from composed_host import CompositionRejected, compose
from composed_host_probe import ProbeReceipt, probe
from composition_descriptors import DescriptorRejected
from composition_graph import GraphRejected
from composition_index import IndexRejected
from composition_platform import HostPlatform, host_platform
from composition_recipe import BUILD
from managed_headless import (
    Input,
    Rejected,
    admit,
    select_assembly_components,
    unpack_archive,
)

REPOSITORY = Path(__file__).resolve().parent.parent


class ReleaseFailure(StrEnum):
    INVALID_IDENTITY = "INVALID_IDENTITY"
    EXISTING_OUTPUT = "EXISTING_OUTPUT"
    UNSAFE_PATH = "UNSAFE_PATH"
    WRONG_PLATFORM = "WRONG_PLATFORM"
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
    bundle: PackedBundle
    # Assembly and native qualification are separate evidence boundaries.
    qualification: Literal["NOT_RUN"] = "NOT_RUN"


@dataclass(frozen=True, slots=True)
class QualificationReceipt:
    sourceRevision: str
    bundleSha256: str
    probe: ProbeReceipt
    qualification: Literal["COMPLETE"] = "COMPLETE"


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
) -> AssemblyResult:
    """The workflow admits the exact clean source before building these inputs."""
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
    target = host_platform(platform.system(), platform.machine())
    if not isinstance(target, HostPlatform) or target != HostPlatform.LINUX_X86_64:
        observe(ReleaseStage.ADMISSION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.WRONG_PLATFORM, "Linux x86_64 required")
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
    observe(ReleaseStage.ADMISSION, StageOutcome.COMPLETE)
    root.mkdir(mode=0o700)
    donor = root / "donor"
    donor.mkdir()
    observe(ReleaseStage.DONOR, StageOutcome.STARTED)
    source = unpack_archive(archive, donor, "Linux")
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
        output / f"kast-composed-setup-v{version}-linux-x86_64.tar.gz",
        version,
    )
    if isinstance(packed, BundleRejected):
        observe(ReleaseStage.BUNDLE, StageOutcome.REJECTED)
        return packed
    path = Path(packed.archive)
    _ = path.with_name(path.name + ".sha256").write_text(f"{packed.sha256}  {path.name}\n")
    result = ReleasePacked(revision, version, packed)
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
) -> QualificationReceipt | ReleaseRejected | BundleRejected | Rejected:
    """Install the produced archive; never qualify the producer's installation."""
    observe(ReleaseStage.QUALIFICATION, StageOutcome.STARTED)
    if any(path.exists() or path.is_symlink() for path in (root, fixture, evidence)):
        observe(ReleaseStage.QUALIFICATION, StageOutcome.REJECTED)
        return ReleaseRejected(ReleaseFailure.EXISTING_OUTPUT, "qualification paths")
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
    installed = install_bundle(archive, expected, root, version, fixture)
    if not isinstance(installed, BundleInstalled):
        observe(ReleaseStage.QUALIFICATION, StageOutcome.REJECTED)
        return installed
    native = probe(root, fixture, evidence, None)
    result = QualificationReceipt(revision, expected, native)
    _ = (evidence / "qualification.json").write_text(json.dumps(asdict(result), indent=2) + "\n")
    observe(ReleaseStage.QUALIFICATION, StageOutcome.COMPLETE)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    assemble = commands.add_parser("assemble")
    qualify = commands.add_parser("qualify")
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
    args = parser.parse_args()
    try:
        result = (
            assemble_release(
                archive=args.archive,
                bootstrap=args.bootstrap,
                assets=args.assets,
                root=args.root,
                output=args.output,
                version=args.version,
                revision=args.source_revision,
            )
            if args.command == "assemble"
            else qualify_release(
                archive=args.archive,
                expected=args.archive_sha256,
                version=args.version,
                revision=args.source_revision,
                root=args.root,
                fixture=args.fixture,
                evidence=args.evidence,
            )
        )
    except (OSError, ValueError, tarfile.TarError, zipfile.BadZipFile) as error:
        result = ReleaseRejected(ReleaseFailure.EFFECT_FAILED, type(error).__name__)
    print(json.dumps(asdict(result), indent=2))
    return 0 if isinstance(result, (ReleasePacked, QualificationReceipt)) else 1


if __name__ == "__main__":
    raise SystemExit(main())
