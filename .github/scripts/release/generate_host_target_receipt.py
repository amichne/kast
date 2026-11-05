"""Seal actual target build bytes against clean source and its declared vendor SDK."""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as XML
import zipfile
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

import developer_catalog as catalog
import dual_candidate as candidate
import host_evidence as evidence
import host_staging as staging
import host_targets as hosts


class Failure(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    SOURCE_REJECTED = "SOURCE_REJECTED"
    TARGET_UNAVAILABLE = "TARGET_UNAVAILABLE"
    SDK_REJECTED = "SDK_REJECTED"
    COMPONENT_REJECTED = "COMPONENT_REJECTED"
    ARCHIVE_REJECTED = "ARCHIVE_REJECTED"
    INPUT_CHANGED = "INPUT_CHANGED"
    IO_FAILURE = "IO_FAILURE"


@dataclass(frozen=True, slots=True)
class Request:
    root: Path
    sdk: Path
    line: str
    identity: evidence.BuildIdentity
    archive: Path
    record: Path
    contract: Path

    @property
    def output(self) -> Path:
        return self.root / "build/host-targets" / self.line / "target-receipt.json"


@dataclass(frozen=True, slots=True)
class Rejected:
    type: str
    reason: Failure


@dataclass(frozen=True, slots=True)
class Written:
    type: str
    file: str


@dataclass(frozen=True, slots=True)
class Snapshot:
    path: Path
    identity: os.stat_result


def valid_request(request: Request) -> bool:
    try:
        return (
            request.line in hosts.LINES
            and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", request.identity.version) is not None
            and len(request.identity.version) <= 64
            and all(
                catalog.SHA.fullmatch(value) and value != "0" * 40
                for value in (request.identity.buildRevision, request.identity.sourceTree)
            )
            and request.root.is_absolute()
            and request.root.is_dir()
            and request.root.resolve(strict=True) == request.root
            and request.output.parent.resolve(strict=False) == request.output.parent
        )
    except (OSError, ValueError):
        return False


def source_admitted(request: Request) -> bool:
    try:
        result = subprocess.run(
            [
                "/bin/bash",
                str(request.root / ".github/scripts/release/admit-source.sh"),
                "--repository-root",
                str(request.root),
                "--expected-source-revision",
                request.identity.buildRevision,
            ],
            capture_output=True,
            timeout=30,
            check=False,
        )
        tree = subprocess.run(
            ["git", "-C", str(request.root), "rev-parse", "HEAD^{tree}"],
            capture_output=True,
            timeout=10,
            check=False,
        )
        return (
            result.returncode == tree.returncode == 0
            and result.stdout.decode("ascii").strip() == request.identity.buildRevision
            and tree.stdout.decode("ascii").strip() == request.identity.sourceTree
        )
    except (OSError, UnicodeError, subprocess.TimeoutExpired):
        return False


def physical_file(path: Path) -> os.stat_result:
    info = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode) or path.resolve(strict=True) != path:
        raise ValueError("receipt input must be a physical regular file")
    return info


def unchanged(path: Path, before: os.stat_result) -> bool:
    after = physical_file(path)
    return (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    ) == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns)


def sdk_evidence(
    home: Path, target: hosts.SdkTarget
) -> tuple[staging.SdkEvidence, tuple[Snapshot, ...]] | Failure:
    try:
        if not home.is_absolute() or home.resolve(strict=True) != home:
            return Failure.SDK_REJECTED
        metadata = [
            path
            for path in (home / "product-info.json", home / "Resources/product-info.json")
            if path.exists()
        ]
        if len(metadata) != 1:
            return Failure.SDK_REJECTED
        info = metadata[0]
        plugin = home / "plugins/Kotlin/lib/kotlin-plugin.jar"
        snapshots = tuple(Snapshot(path, physical_file(path)) for path in (info, plugin))
        if (
            not 0 < info.stat().st_size <= evidence.consumer.MAXIMUM_RECORD_BYTES
            or not 0 < plugin.stat().st_size <= evidence.consumer.MAXIMUM_PAYLOAD_BYTES
        ):
            return Failure.SDK_REJECTED
        product = evidence.consumer.document(info.read_bytes())
        if not catalog.mapping(product) or product.get("buildNumber") != target.ideBuild:
            return Failure.SDK_REJECTED
        with zipfile.ZipFile(plugin) as archive:
            descriptors = [
                entry for entry in archive.infolist() if entry.filename == "META-INF/plugin.xml"
            ]
            if (
                len(descriptors) != 1
                or not 0 < descriptors[0].file_size <= evidence.consumer.MAXIMUM_RECORD_BYTES
            ):
                return Failure.SDK_REJECTED
            descriptor = XML.fromstring(archive.read(descriptors[0]))
            if (
                len(descriptor.findall("version")) != 1
                or descriptor.findtext("version") != target.kotlinPluginBuild
                or len(descriptor.findall("id")) != 1
                or descriptor.findtext("id") != "org.jetbrains.kotlin"
            ):
                return Failure.SDK_REJECTED
        result = staging.SdkEvidence(
            "sha256:" + candidate.digest(info), "sha256:" + candidate.digest(plugin)
        )
        if not all(unchanged(snapshot.path, snapshot.identity) for snapshot in snapshots):
            return Failure.INPUT_CHANGED
        return result, snapshots
    except (OSError, ValueError, XML.ParseError, zipfile.BadZipFile):
        return Failure.SDK_REJECTED


def inspect(request: Request) -> staging.Receipt | Failure:
    if not valid_request(request):
        return Failure.INVALID_INPUT
    if not source_admitted(request):
        return Failure.SOURCE_REJECTED
    try:
        profile_path = request.root / "config/intellij-targets.json"
        profile_snapshot = Snapshot(profile_path, physical_file(profile_path))
        if not 0 < profile_snapshot.identity.st_size <= evidence.consumer.MAXIMUM_RECORD_BYTES:
            return Failure.TARGET_UNAVAILABLE
        profile = hosts.decode_profile(evidence.consumer.document(profile_path.read_bytes()))
        if isinstance(profile, hosts.Failure):
            return Failure.TARGET_UNAVAILABLE
        target = next(
            target for target in profile.targets if target.intellijReleaseLine == request.line
        )
        sdk = sdk_evidence(request.sdk, target)
        if isinstance(sdk, Failure):
            return sdk
        actual_sdk, sdk_snapshots = sdk
        paths = (
            request.archive,
            request.record,
            request.contract,
            request.root / evidence.QUERY_SCHEMA_PATH,
            request.root / evidence.OPERATION_REGISTRY_PATH,
        )
        snapshots = (
            profile_snapshot,
            *sdk_snapshots,
            *(Snapshot(path, physical_file(path)) for path in paths),
        )
        if not 0 < request.archive.stat().st_size <= evidence.consumer.MAXIMUM_PAYLOAD_BYTES or any(
            not 0 < path.stat().st_size <= evidence.consumer.MAXIMUM_RECORD_BYTES
            for path in (request.record, request.contract)
        ):
            return Failure.COMPONENT_REJECTED
        if (
            request.archive.name
            != f"kast-ide-hosted-v{request.identity.version}-idea-{request.line}.zip"
            or request.record.name != hosts.record_name(request.identity.version, request.line)
        ):
            return Failure.COMPONENT_REJECTED
        component = evidence.consumer.parse_component(
            request.record.read_bytes(),
            request.identity.version,
            request.archive.name,
            request.line,
        )
        contract = evidence.consumer.parse_contract(
            evidence.consumer.document(request.contract.read_bytes())
        )
        if (
            isinstance(component, evidence.consumer.Failure)
            or isinstance(contract, evidence.consumer.Failure)
            or component.source_revision != request.identity.buildRevision
            or component.contract != contract
            or component.bytes != request.archive.stat().st_size
            or component.sha256 != candidate.digest(request.archive)
        ):
            return Failure.COMPONENT_REJECTED
        target_record = hosts.HostTarget(
            target.intellijReleaseLine,
            target.ideBuild,
            target.kotlinPluginBuild,
            catalog.RecordReference(request.record.name, candidate.digest(request.record)),
        )
        inspected = evidence.inspect(
            request.archive,
            target_record,
            request.identity,
            contract,
            evidence.source_resources(request.root),
        )
        if isinstance(inspected, candidate.Failure):
            return Failure.ARCHIVE_REJECTED
        receipt = staging.Receipt(
            1,
            "HOST_TARGET_BUILD",
            target.intellijReleaseLine,
            target.ideBuild,
            target.kotlinPluginBuild,
            request.identity.version,
            request.identity.buildRevision,
            request.identity.sourceTree,
            "sha256:" + candidate.digest(profile_path),
            staging.Artifact(
                request.archive.name, "sha256:" + candidate.digest(request.archive), component.bytes
            ),
            staging.Artifact(
                request.record.name,
                "sha256:" + candidate.digest(request.record),
                request.record.stat().st_size,
            ),
            contract,
            actual_sdk,
        )
        if not all(unchanged(snapshot.path, snapshot.identity) for snapshot in snapshots):
            return Failure.INPUT_CHANGED
        return receipt if source_admitted(request) else Failure.SOURCE_REJECTED
    except (OSError, ValueError, StopIteration):
        return Failure.IO_FAILURE


def write(request: Request) -> staging.Receipt | Failure:
    if not valid_request(request):
        return Failure.INVALID_INPUT
    try:
        # The target-owned success record must not survive any new failed attempt.
        request.output.unlink(missing_ok=True)
        receipt = inspect(request)
        if isinstance(receipt, Failure):
            return receipt
        request.output.parent.mkdir(parents=True, exist_ok=True)
        if request.output.parent.resolve(strict=True) != request.output.parent:
            return Failure.INVALID_INPUT
        with tempfile.NamedTemporaryFile(
            dir=request.output.parent, prefix=".target-receipt-", delete=False
        ) as stream:
            temporary = Path(stream.name)
            try:
                stream.write((json.dumps(asdict(receipt), indent=2) + "\n").encode("utf-8"))
                stream.flush()
                os.fsync(stream.fileno())
                os.replace(temporary, request.output)
            finally:
                temporary.unlink(missing_ok=True)
        return receipt
    except (OSError, ValueError):
        return Failure.IO_FAILURE


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in (
        "repository-root",
        "sdk-home",
        "target",
        "version",
        "source-revision",
        "source-tree",
        "archive",
        "release-record",
        "hosted-contract",
    ):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    request = Request(
        Path(args.repository_root),
        Path(args.sdk_home),
        args.target,
        evidence.BuildIdentity(args.version, args.source_revision, args.source_tree),
        Path(args.archive),
        Path(args.release_record),
        Path(args.hosted_contract),
    )
    result = write(request)
    if isinstance(result, Failure):
        print(json.dumps(asdict(Rejected("HOST_TARGET_BUILD_REJECTED", result))))
        return 1
    print(json.dumps(asdict(Written("HOST_TARGET_BUILD_WRITTEN", str(request.output)))))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
