"""Admit both source-bound Host receipts before staging either release target."""

from __future__ import annotations

import re
import shutil
from dataclasses import dataclass
from pathlib import Path

import developer_catalog as catalog
import dual_candidate as candidate
import host_evidence as evidence
import host_targets as hosts


@dataclass(frozen=True, slots=True)
class Artifact:
    fileName: str
    sha256: str
    bytes: int


@dataclass(frozen=True, slots=True)
class SdkEvidence:
    productInfoSha256: str
    kotlinPluginJarSha256: str


@dataclass(frozen=True, slots=True)
class Receipt:
    schemaVersion: int
    type: str
    intellijReleaseLine: str
    ideBuild: str
    kotlinPluginBuild: str
    version: str
    sourceRevision: str
    sourceTree: str
    targetProfileSha256: str
    archive: Artifact
    releaseRecord: Artifact
    providedHostedContract: evidence.consumer.HostedContract
    sdk: SdkEvidence


@dataclass(frozen=True, slots=True)
class Admitted:
    receipt: Receipt
    receipt_path: Path
    archive: Path
    record: Path


def sha(value: object) -> bool:
    return (
        isinstance(value, str)
        and value.startswith("sha256:")
        and catalog.DIGEST.fullmatch(value[7:]) is not None
    )


def artifact(value: object) -> Artifact | candidate.Failure:
    if not catalog.mapping(value) or set(value) != {"fileName", "sha256", "bytes"}:
        return candidate.Failure.INVALID_RECORD
    name, digest, size = value["fileName"], value["sha256"], value["bytes"]
    if (
        not isinstance(name, str)
        or not isinstance(digest, str)
        or not sha(digest)
        or type(size) is not int
        or not 0 < size <= evidence.consumer.MAXIMUM_PAYLOAD_BYTES
    ):
        return candidate.Failure.INVALID_RECORD
    return Artifact(name, digest, size)


def decode(value: object) -> Receipt | candidate.Failure:
    if not catalog.mapping(value) or set(value) != set(Receipt.__dataclass_fields__):
        return candidate.Failure.INVALID_RECORD
    fields = (
        "intellijReleaseLine",
        "ideBuild",
        "kotlinPluginBuild",
        "version",
        "sourceRevision",
        "sourceTree",
        "targetProfileSha256",
    )
    strings: list[str] = []
    for name in fields:
        field = value[name]
        if not isinstance(field, str):
            return candidate.Failure.INVALID_RECORD
        strings.append(field)
    line, ide, kotlin, version, revision, tree, profile_hash = strings
    target = hosts.sdk(dict(zip(fields[:3], strings[:3], strict=True)))
    archive, record = artifact(value["archive"]), artifact(value["releaseRecord"])
    contract = evidence.consumer.parse_contract(value["providedHostedContract"])
    sdk = value["sdk"]
    if (
        type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
        or value["type"] != "HOST_TARGET_BUILD"
        or isinstance(target, hosts.Failure)
        or isinstance(archive, candidate.Failure)
        or isinstance(record, candidate.Failure)
        or isinstance(contract, evidence.consumer.Failure)
        or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
        or not all(catalog.SHA.fullmatch(item) and item != "0" * 40 for item in (revision, tree))
        or not sha(profile_hash)
        or not catalog.mapping(sdk)
        or set(sdk) != {"productInfoSha256", "kotlinPluginJarSha256"}
    ):
        return candidate.Failure.INVALID_RECORD
    info, plugin = sdk["productInfoSha256"], sdk["kotlinPluginJarSha256"]
    if not isinstance(info, str) or not isinstance(plugin, str) or not sha(info) or not sha(plugin):
        return candidate.Failure.INVALID_RECORD
    if (
        archive.fileName != f"kast-ide-hosted-v{version}-idea-{line}.zip"
        or record.fileName != hosts.record_name(version, line)
    ):
        return candidate.Failure.TARGET_MISMATCH
    return Receipt(
        1,
        "HOST_TARGET_BUILD",
        line,
        ide,
        kotlin,
        version,
        revision,
        tree,
        profile_hash,
        archive,
        record,
        contract,
        SdkEvidence(info, plugin),
    )


def admit(
    root: Path,
    target: hosts.SdkTarget,
    identity: evidence.BuildIdentity,
    profile_hash: str,
) -> Admitted | candidate.Failure:
    output = root / "build/host-targets" / target.intellijReleaseLine
    receipt_path = output / "target-receipt.json"
    receipt = decode(candidate.read_json(receipt_path))
    if isinstance(receipt, candidate.Failure):
        return receipt
    if (
        hosts.SdkTarget(receipt.intellijReleaseLine, receipt.ideBuild, receipt.kotlinPluginBuild)
        != target
        or receipt.version != identity.version
        or receipt.sourceRevision != identity.buildRevision
        or receipt.sourceTree != identity.sourceTree
        or receipt.targetProfileSha256 != profile_hash
    ):
        return candidate.Failure.IDENTITY_MISMATCH
    archive = (
        root
        / "runtime/hosted/build/ide-targets"
        / target.intellijReleaseLine
        / "distributions"
        / receipt.archive.fileName
    )
    record = output / "component-release" / receipt.releaseRecord.fileName
    try:
        for path, declared in ((archive, receipt.archive), (record, receipt.releaseRecord)):
            if (
                path.is_symlink()
                or not path.is_file()
                or path.stat().st_size != declared.bytes
                or "sha256:" + candidate.digest(path) != declared.sha256
            ):
                return candidate.Failure.PAYLOAD_MISMATCH
        component = evidence.consumer.parse_component(
            record.read_bytes(), identity.version, archive.name, target.intellijReleaseLine
        )
        if (
            isinstance(component, evidence.consumer.Failure)
            or component.source_revision != identity.buildRevision
            or component.contract != receipt.providedHostedContract
            or "sha256:" + component.sha256 != receipt.archive.sha256
            or component.bytes != receipt.archive.bytes
        ):
            return candidate.Failure.PAYLOAD_MISMATCH
        host = hosts.HostTarget(
            target.intellijReleaseLine,
            target.ideBuild,
            target.kotlinPluginBuild,
            catalog.RecordReference(record.name, receipt.releaseRecord.sha256[7:]),
        )
        inspected = evidence.inspect(
            archive, host, identity, component.contract, evidence.source_resources(root)
        )
        if isinstance(inspected, candidate.Failure):
            return inspected
        return Admitted(receipt, receipt_path, archive, record)
    except OSError:
        return candidate.Failure.PAYLOAD_MISMATCH


def stage(
    root: Path, destination: Path, identity: evidence.BuildIdentity
) -> None | candidate.Failure:
    profile_path = root / "config/intellij-targets.json"
    profile = hosts.decode_profile(candidate.read_json(profile_path))
    if isinstance(profile, hosts.Failure):
        return candidate.Failure.INVALID_RECORD
    try:
        profile_hash = "sha256:" + candidate.digest(profile_path)
        admitted: list[Admitted] = []
        for target in profile.targets:
            result = admit(root, target, identity, profile_hash)
            if isinstance(result, candidate.Failure):
                return result
            admitted.append(result)
        if destination.is_symlink() or not destination.is_dir():
            return candidate.Failure.PAYLOAD_MISMATCH
        for value in admitted:
            for path in (value.archive, value.record):
                output = destination / path.name
                checksum = output.with_name(output.name + ".sha256")
                if output.is_symlink() or checksum.is_symlink():
                    return candidate.Failure.PAYLOAD_MISMATCH
                _ = shutil.copyfile(path, output)
                _ = checksum.write_text(f"{candidate.digest(output)}  {output.name}\n")
            report = root / "build/reports/host-targets" / value.receipt.intellijReleaseLine
            report.mkdir(parents=True, exist_ok=True)
            _ = shutil.copyfile(value.receipt_path, report / "target-receipt.json")
        return None
    except OSError:
        return candidate.Failure.PAYLOAD_MISMATCH
