"""One exact payload union with independently hashed V1 and V2 candidate views."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, replace
from enum import StrEnum
from pathlib import Path

import build_candidate as legacy
import developer_catalog as catalog
import host_targets as hosts


class Failure(StrEnum):
    INVALID_RECORD = "INVALID_RECORD"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    PAYLOAD_MISMATCH = "PAYLOAD_MISMATCH"
    TARGET_MISMATCH = "TARGET_MISMATCH"


@dataclass(frozen=True, slots=True)
class Candidate(legacy.Candidate):
    hostTargets: tuple[hosts.HostTarget, ...]


@dataclass(frozen=True, slots=True)
class Pair:
    legacy: legacy.Candidate
    current: Candidate


def base_names(version: str) -> tuple[str, ...]:
    return (
        f"kast-control-v{version}-macos-aarch64.tar.gz",
        f"kast-ide-hosted-v{version}-idea-262.zip",
        f"kast-plugin-v{version}.zip",
        f"kast-marketplace-v{version}.zip",
        f"kast-control-release-v{version}.json",
        hosts.record_name(version, "262"),
        "host-installation.py",
        f"kast-hosted-catalog-v{version}.json",
        f"kast-module-knowledge-v{version}.json",
        f"kast-sbom-v{version}.cdx.json",
    )


def names(version: str, dual: bool) -> frozenset[str]:
    primary = base_names(version)
    if dual:
        primary += (f"kast-ide-hosted-v{version}-idea-263.zip", hosts.record_name(version, "263"))
    return frozenset((*primary, *(f"{name}.sha256" for name in primary)))


def projection(candidate: Candidate) -> legacy.Candidate:
    expected = names(candidate.version, False)
    return legacy.Candidate(
        1,
        candidate.repository,
        candidate.sourceRevision,
        candidate.sourceTree,
        candidate.version,
        candidate.runId,
        candidate.runNumber,
        candidate.runAttempt,
        candidate.event,
        candidate.platform,
        candidate.toolchain,
        tuple(asset for asset in candidate.assets if asset.name in expected),
    )


def decode(value: object) -> Candidate | Failure:
    if not catalog.mapping(value) or set(value) != set(Candidate.__dataclass_fields__):
        return Failure.INVALID_RECORD
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 2:
        return Failure.INVALID_RECORD
    projected = {key: field for key, field in value.items() if key != "hostTargets"}
    projected["schemaVersion"] = 1
    try:
        base = legacy.decode(projected)
    except legacy.Rejected:
        return Failure.INVALID_RECORD
    targets = hosts.decode_targets(value["hostTargets"], base.version)
    if isinstance(targets, hosts.Failure):
        return Failure.INVALID_RECORD
    if frozenset(asset.name for asset in base.assets) != names(base.version, True):
        return Failure.PAYLOAD_MISMATCH
    if tuple(sorted(base.assets, key=lambda asset: asset.name)) != base.assets:
        return Failure.INVALID_RECORD
    result = Candidate(
        2,
        base.repository,
        base.sourceRevision,
        base.sourceTree,
        base.version,
        base.runId,
        base.runNumber,
        base.runAttempt,
        base.event,
        base.platform,
        base.toolchain,
        base.assets,
        targets,
    )
    by_name = {asset.name: asset for asset in result.assets}
    if any(by_name[target.record.name].sha256 != target.record.sha256 for target in targets):
        return Failure.TARGET_MISMATCH
    return result


def admit_pair(old: legacy.Candidate, current: Candidate) -> Pair | Failure:
    admitted = decode(asdict(current))
    if isinstance(admitted, Failure):
        return admitted
    try:
        decoded_old = legacy.decode(asdict(old))
    except legacy.Rejected:
        return Failure.INVALID_RECORD
    if frozenset(asset.name for asset in decoded_old.assets) != names(old.version, False):
        return Failure.PAYLOAD_MISMATCH
    if decoded_old != projection(admitted):
        return Failure.IDENTITY_MISMATCH
    return Pair(decoded_old, admitted)


def read_json(path: Path, maximum: int = 65_536) -> object | Failure:
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > maximum:
            return Failure.INVALID_RECORD
        value: object = json.loads(path.read_bytes(), object_pairs_hook=catalog.unique_object)
        return value
    except (OSError, ValueError, RecursionError):
        return Failure.INVALID_RECORD


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read(bundle: Path) -> Pair | Failure:
    try:
        if (
            bundle.is_symlink()
            or not bundle.is_dir()
            or {path.name for path in bundle.iterdir()}
            != {"candidate.json", "candidate-v2.json", "payload"}
        ):
            return Failure.INVALID_RECORD
        current = decode(read_json(bundle / "candidate-v2.json"))
        if isinstance(current, Failure):
            return current
        try:
            old = legacy.decode(read_json(bundle / "candidate.json"))
        except legacy.Rejected:
            return Failure.INVALID_RECORD
        pair = admit_pair(old, current)
        if isinstance(pair, Failure):
            return pair
        payload = bundle / "payload"
        if (
            payload.is_symlink()
            or not payload.is_dir()
            or frozenset(path.name for path in payload.iterdir()) != names(current.version, True)
        ):
            return Failure.PAYLOAD_MISMATCH
        for asset in current.assets:
            path = payload / asset.name
            if (
                path.is_symlink()
                or not path.is_file()
                or path.stat().st_size != asset.bytes
                or digest(path) != asset.sha256
            ):
                return Failure.PAYLOAD_MISMATCH
            if not asset.name.endswith(".sha256") and (
                (payload / f"{asset.name}.sha256").read_bytes()
                != f"{asset.sha256}  {asset.name}\n".encode()
            ):
                return Failure.PAYLOAD_MISMATCH
        return pair
    except OSError:
        return Failure.PAYLOAD_MISMATCH


def admit_sbom(payload: Path, value: Candidate) -> None | Failure:
    sbom = read_json(payload / f"kast-sbom-v{value.version}.cdx.json", 32 * 1024 * 1024)
    if not catalog.mapping(sbom) or sbom.get("bomFormat") != "CycloneDX":
        return Failure.PAYLOAD_MISMATCH
    metadata = sbom.get("metadata")
    if not catalog.mapping(metadata):
        return Failure.PAYLOAD_MISMATCH
    values = metadata.get("properties")
    if not catalog.sequence(values):
        return Failure.PAYLOAD_MISMATCH
    properties: dict[str, str] = {}
    for item in values:
        if not catalog.mapping(item) or set(item) != {"name", "value"}:
            return Failure.PAYLOAD_MISMATCH
        name, content = item["name"], item["value"]
        if not isinstance(name, str) or not isinstance(content, str) or name in properties:
            return Failure.PAYLOAD_MISMATCH
        properties[name] = content
    if properties.get("kast:source-revision") != value.sourceRevision:
        return Failure.PAYLOAD_MISMATCH
    archives = (*base_names(value.version)[:4], f"kast-ide-hosted-v{value.version}-idea-263.zip")
    by_name = {asset.name: asset for asset in value.assets}
    if any(
        properties.get(f"kast:archive:{name}") != f"sha256:{by_name[name].sha256}"
        for name in archives
    ):
        return Failure.PAYLOAD_MISMATCH
    return None


def admit_promotions(
    pair: Pair,
    old: legacy.Promotion,
    current: legacy.Promotion,
    old_digest: str,
    current_digest: str,
) -> legacy.Promotion | Failure:
    if (
        type(old.schemaVersion) is not int
        or old.schemaVersion != 1
        or type(current.schemaVersion) is not int
        or current.schemaVersion != 2
        or old.candidateSha256 != old_digest
        or current.candidateSha256 != current_digest
        or not catalog.DIGEST.fullmatch(old_digest)
        or not catalog.DIGEST.fullmatch(current_digest)
        or replace(current, schemaVersion=1, candidateSha256=old_digest) != old
    ):
        return Failure.IDENTITY_MISMATCH
    candidate = pair.legacy
    if (
        old.repository != candidate.repository
        or old.buildRevision != candidate.sourceRevision
        or old.sourceTree != candidate.sourceTree
        or old.version != candidate.version
        or old.buildRunId != candidate.runId
        or old.buildRunAttempt != candidate.runAttempt
    ):
        return Failure.IDENTITY_MISMATCH
    return current
