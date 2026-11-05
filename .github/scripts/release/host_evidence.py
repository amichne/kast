"""Bind each declared compile target to its actual packaged Host metadata."""

from __future__ import annotations

import io
import stat
import sys
import xml.etree.ElementTree as XML
import zipfile
from dataclasses import dataclass
from pathlib import Path

import build_candidate
import dual_candidate as candidate
import host_targets as hosts

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "packaging"))
import managed_release as consumer


@dataclass(frozen=True, slots=True)
class BuildIdentity:
    version: str
    buildRevision: str
    sourceTree: str


QUERY_SCHEMA_PATH = "protocol/contract/src/main/resources/ide-hosted/hosted-query.schema.json"
OPERATION_REGISTRY_PATH = (
    "protocol/contract/src/main/resources/ide-hosted/hosted-query.operations.json"
)


@dataclass(frozen=True, slots=True)
class SourceResources:
    querySchemaSha256: str
    operationRegistrySha256: str


def source_resources(root: Path) -> SourceResources:
    return SourceResources(
        "sha256:" + candidate.digest(root / QUERY_SCHEMA_PATH),
        "sha256:" + candidate.digest(root / OPERATION_REGISTRY_PATH),
    )


@dataclass(frozen=True, slots=True)
class HostEvidence:
    target: hosts.HostTarget
    archive: build_candidate.Asset
    record: build_candidate.Asset


def inspect(
    archive: Path,
    target: hosts.HostTarget,
    identity: BuildIdentity,
    contract: consumer.HostedContract,
    resources: SourceResources,
) -> consumer.Admission | candidate.Failure:
    try:
        with zipfile.ZipFile(archive) as outer:
            entries = outer.infolist()
            paths = [entry.filename.rstrip("/") for entry in entries]
            if (
                not entries
                or len(entries) > consumer.MAXIMUM_ENTRIES
                or len(paths) != len(set(paths))
                or sum(entry.file_size for entry in entries) > consumer.MAXIMUM_PAYLOAD_BYTES
                or any(
                    (path != "kast-ide-hosted" and not path.startswith("kast-ide-hosted/"))
                    or "\\" in path
                    or any(part in ("", ".", "..") for part in path.split("/"))
                    or stat.S_ISLNK(entry.external_attr >> 16)
                    for path, entry in zip(paths, entries, strict=True)
                )
            ):
                return candidate.Failure.PAYLOAD_MISMATCH
            primary = "kast-ide-hosted/lib/kast-ide-hosted.jar"
            primary_candidates = [
                name
                for name in paths
                if name.startswith("kast-ide-hosted/lib/kast-ide-hosted") and name.endswith(".jar")
            ]
            if primary_candidates != [primary]:
                return candidate.Failure.PAYLOAD_MISMATCH
            with zipfile.ZipFile(io.BytesIO(outer.read(primary))) as plugin:
                entries = plugin.infolist()
                if (
                    len({entry.filename for entry in entries}) != len(entries)
                    or sum(entry.file_size for entry in entries) > consumer.MAXIMUM_PAYLOAD_BYTES
                    or any(
                        plugin.getinfo(name).file_size > consumer.MAXIMUM_RECORD_BYTES
                        for name in (
                            "META-INF/plugin.xml",
                            "hosted-contract.json",
                            "kast-hosted-query.properties",
                        )
                    )
                ):
                    return candidate.Failure.PAYLOAD_MISMATCH
                descriptor = XML.fromstring(plugin.read("META-INF/plugin.xml"))
                if (
                    len(descriptor.findall("id")) != 1
                    or len(descriptor.findall("version")) != 1
                    or descriptor.findtext("id") != "io.github.amichne.kast.ide-hosted"
                    or descriptor.findtext("version") != identity.version
                    or consumer.parse_contract(
                        consumer.document(plugin.read("hosted-contract.json"))
                    )
                    != contract
                ):
                    return candidate.Failure.PAYLOAD_MISMATCH
                versions = descriptor.findall("idea-version")
                if (
                    len(versions) != 1
                    or versions[0].get("since-build") != target.intellijReleaseLine
                    or versions[0].get("until-build") != f"{target.intellijReleaseLine}.*"
                ):
                    return candidate.Failure.TARGET_MISMATCH
                properties: dict[str, str] = {}
                for line in (
                    plugin.read("kast-hosted-query.properties").decode("utf-8").splitlines()
                ):
                    if not line or line.startswith("#"):
                        continue
                    key, separator, value = line.partition("=")
                    if not separator or key in properties:
                        return candidate.Failure.PAYLOAD_MISMATCH
                    properties[key] = value
                if (
                    properties.get("version"),
                    properties.get("sourceRevision"),
                    properties.get("sourceTree"),
                ) != (
                    identity.version,
                    identity.buildRevision,
                    identity.sourceTree,
                ):
                    return candidate.Failure.PAYLOAD_MISMATCH
                if (
                    properties.get("ideBuild") != target.ideBuild
                    or properties.get("kotlinBuild") != target.kotlinPluginBuild
                    or properties.get("registryDigest") != resources.operationRegistrySha256
                    or properties.get("schemaDigest") != resources.querySchemaSha256
                ):
                    return candidate.Failure.TARGET_MISMATCH
        return consumer.Admission.ADMITTED
    except (OSError, ValueError, KeyError, UnicodeError, XML.ParseError, zipfile.BadZipFile):
        return candidate.Failure.PAYLOAD_MISMATCH


def admit(
    payload: Path,
    value: candidate.Candidate,
    profile: hosts.Profile,
    resources: SourceResources,
) -> tuple[HostEvidence, ...] | candidate.Failure:
    declared = tuple(
        hosts.SdkTarget(target.intellijReleaseLine, target.ideBuild, target.kotlinPluginBuild)
        for target in value.hostTargets
    )
    if declared != profile.targets:
        return candidate.Failure.TARGET_MISMATCH
    identity = BuildIdentity(value.version, value.sourceRevision, value.sourceTree)
    assets = {asset.name: asset for asset in value.assets}
    evidence: list[HostEvidence] = []
    try:
        control_name = f"kast-control-v{value.version}-macos-aarch64.tar.gz"
        control = consumer.parse_component(
            (payload / f"kast-control-release-v{value.version}.json").read_bytes(),
            value.version,
            control_name,
            None,
        )
        if (
            isinstance(control, consumer.Failure)
            or control.source_revision != value.sourceRevision
            or assets[control_name].sha256 != control.sha256
            or assets[control_name].bytes != control.bytes
        ):
            return candidate.Failure.PAYLOAD_MISMATCH
        for target in value.hostTargets:
            archive_name = f"kast-ide-hosted-v{value.version}-idea-{target.intellijReleaseLine}.zip"
            record = consumer.parse_component(
                (payload / target.record.name).read_bytes(),
                value.version,
                archive_name,
                target.intellijReleaseLine,
            )
            if (
                isinstance(record, consumer.Failure)
                or record.source_revision != value.sourceRevision
                or record.contract != control.contract
                or assets[archive_name].sha256 != record.sha256
                or assets[archive_name].bytes != record.bytes
                or assets[target.record.name].sha256 != target.record.sha256
            ):
                return candidate.Failure.PAYLOAD_MISMATCH
            result = inspect(payload / archive_name, target, identity, record.contract, resources)
            if isinstance(result, candidate.Failure):
                return result
            evidence.append(HostEvidence(target, assets[archive_name], assets[target.record.name]))
        return tuple(evidence)
    except (OSError, KeyError):
        return candidate.Failure.PAYLOAD_MISMATCH
