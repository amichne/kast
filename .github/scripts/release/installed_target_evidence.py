"""Admit installation-contract fixture receipts without claiming a real IDE launch."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path

import developer_catalog as catalog
import dual_candidate as candidate
import host_evidence
import host_targets as hosts

CHECKS = (
    "ARTIFACT_IDENTITY",
    "NATIVE_LAUNCHER",
    "CONTROL_STAGING",
    "INSTALLED_QUERY_DISPATCH",
    "NATIVE_HOST_AND_STATE_CLEANUP",
)


@dataclass(frozen=True, slots=True)
class Artifact:
    fileName: str
    sha256: str
    bytes: int


@dataclass(frozen=True, slots=True)
class Report:
    schemaVersion: int
    scope: str
    status: str
    intellijReleaseLine: str
    ideBuild: str
    kotlinPluginBuild: str
    targetProfileSha256: str
    controlVersion: str
    hostedPluginVersion: str
    sourceRevision: str
    sourceTree: str
    controlArtifact: Artifact
    hostArtifact: Artifact
    hostReleaseRecord: Artifact
    runtimeIdeLaunched: bool
    completedChecks: tuple[str, ...]


def expected(
    root: Path,
    release: Path,
    target: hosts.SdkTarget,
    identity: host_evidence.BuildIdentity,
) -> Report:
    def artifact(name: str) -> Artifact:
        path = release / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("expected a regular release asset")
        return Artifact(name, candidate.digest(path), path.stat().st_size)

    return Report(
        1,
        "INSTALLATION_CONTRACT_METADATA_FIXTURE",
        "PASSED",
        target.intellijReleaseLine,
        target.ideBuild,
        target.kotlinPluginBuild,
        candidate.digest(root / "config/intellij-targets.json"),
        identity.version,
        identity.version,
        identity.buildRevision,
        identity.sourceTree,
        artifact(f"kast-control-v{identity.version}-macos-aarch64.tar.gz"),
        artifact(f"kast-ide-hosted-v{identity.version}-idea-{target.intellijReleaseLine}.zip"),
        artifact(hosts.record_name(identity.version, target.intellijReleaseLine)),
        False,
        CHECKS,
    )


def admit(path: Path, expected: Report) -> Report | candidate.Failure:
    value = candidate.read_json(path)
    if (
        not catalog.mapping(value)
        or set(value) != set(Report.__dataclass_fields__)
        or type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
        or value["runtimeIdeLaunched"] is not False
    ):
        return candidate.Failure.INVALID_RECORD
    for name in ("controlArtifact", "hostArtifact", "hostReleaseRecord"):
        asset = value[name]
        if (
            not catalog.mapping(asset)
            or set(asset) != set(Artifact.__dataclass_fields__)
            or type(asset["bytes"]) is not int
        ):
            return candidate.Failure.INVALID_RECORD
    required: object = json.loads(json.dumps(asdict(expected)))
    if value != required:
        return candidate.Failure.IDENTITY_MISMATCH
    return expected
