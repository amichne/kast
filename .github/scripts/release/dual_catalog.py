"""Admit and publish one release through paired legacy and target-aware views."""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import asdict, dataclass, replace

import developer_catalog as legacy
import host_targets as hosts


@dataclass(frozen=True, slots=True)
class Build(legacy.Build):
    hostTargets: tuple[hosts.HostTarget, ...]


@dataclass(frozen=True, slots=True)
class Catalog:
    schemaVersion: int
    builds: tuple[Build, ...]


@dataclass(frozen=True, slots=True)
class PairedBuild:
    legacy: legacy.Build
    current: Build


@dataclass(frozen=True, slots=True)
class CatalogPair:
    legacy: legacy.Catalog
    current: Catalog


@dataclass(frozen=True, slots=True)
class Snapshot:
    head: str
    tree: str
    catalogs: CatalogPair
    absent: bool


def common(build: legacy.Build) -> legacy.Build:
    return legacy.Build(
        build.mainRevision,
        build.buildRevision,
        build.sourceTree,
        build.releaseTag,
        build.version,
        build.platformTargets,
        legacy.RecordReference("candidate.json", "0" * 64),
        legacy.RecordReference("promotion.json", "0" * 64),
    )


def valid(build: Build) -> bool:
    return (
        legacy.valid(common(build))
        and build.candidate.name == "candidate-v2.json"
        and build.promotion.name == "promotion-v2.json"
        and all(legacy.DIGEST.fullmatch(ref.sha256) for ref in (build.candidate, build.promotion))
        and hosts.decode_targets(
            [asdict(target) for target in build.hostTargets],
            build.version,
        )
        == build.hostTargets
    )


def parse_build(value: object) -> Build | legacy.Failure:
    if not legacy.mapping(value) or set(value) != set(Build.__dataclass_fields__):
        return legacy.Failure.CATALOG_INVALID
    candidate = legacy.record(value["candidate"], "candidate-v2.json")
    promotion = legacy.record(value["promotion"], "promotion-v2.json")
    if isinstance(candidate, legacy.Failure) or isinstance(promotion, legacy.Failure):
        return legacy.Failure.CATALOG_INVALID
    projection = {key: field for key, field in value.items() if key != "hostTargets"}
    projection["candidate"] = asdict(replace(candidate, name="candidate.json"))
    projection["promotion"] = asdict(replace(promotion, name="promotion.json"))
    base = legacy.build(projection)
    if isinstance(base, legacy.Failure):
        return base
    targets = hosts.decode_targets(value["hostTargets"], base.version)
    if isinstance(targets, hosts.Failure):
        return legacy.Failure.CATALOG_INVALID
    return Build(
        base.mainRevision,
        base.buildRevision,
        base.sourceTree,
        base.releaseTag,
        base.version,
        base.platformTargets,
        candidate,
        promotion,
        targets,
    )


def decode(raw: str) -> Catalog | legacy.Failure:
    if len(raw.encode()) > legacy.MAXIMUM_CATALOG_BYTES:
        return legacy.Failure.CATALOG_INVALID
    try:
        value: object = json.loads(raw, object_pairs_hook=legacy.unique_object)
    except (ValueError, RecursionError):
        return legacy.Failure.CATALOG_INVALID
    if not legacy.mapping(value) or set(value) != {"schemaVersion", "builds"}:
        return legacy.Failure.CATALOG_INVALID
    values = value["builds"]
    if (
        type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 2
        or not legacy.sequence(values)
        or len(values) > legacy.WINDOW
    ):
        return legacy.Failure.CATALOG_INVALID
    builds: list[Build] = []
    for item in values:
        parsed = parse_build(item)
        if isinstance(parsed, legacy.Failure):
            return parsed
        builds.append(parsed)
    if len({build.mainRevision for build in builds}) != len(builds):
        return legacy.Failure.CATALOG_INVALID
    return Catalog(2, tuple(builds))


def encode(catalog: Catalog) -> str:
    return json.dumps(asdict(catalog), separators=(",", ":")) + "\n"


def merge(
    current: CatalogPair,
    incoming: PairedBuild,
    history: tuple[str, ...],
) -> CatalogPair | legacy.Failure:
    if (
        current.current.schemaVersion != 2
        or len(current.current.builds) > legacy.WINDOW
        or not valid(incoming.current)
        or not all(valid(item) for item in current.current.builds)
        or len({item.mainRevision for item in current.current.builds})
        != len(current.current.builds)
    ):
        return legacy.Failure.CATALOG_INVALID
    if common(incoming.legacy) != common(incoming.current):
        return legacy.Failure.IDENTITY_CONFLICT
    old_by_revision = {item.mainRevision: item for item in current.legacy.builds}
    for item in current.current.builds:
        old = old_by_revision.get(item.mainRevision)
        if old is None or common(old) != common(item):
            return legacy.Failure.IDENTITY_CONFLICT
    merged = legacy.merge(current.legacy, incoming.legacy, history)
    if isinstance(merged, legacy.Failure):
        return merged
    positions = {revision: index for index, revision in enumerate(history)}
    if (
        tuple(sorted(current.current.builds, key=lambda item: positions[item.mainRevision]))
        != current.current.builds
    ):
        return legacy.Failure.CATALOG_INVALID
    previous = next(
        (
            item
            for item in current.current.builds
            if item.mainRevision == incoming.current.mainRevision
        ),
        None,
    )
    if previous is not None and previous != incoming.current:
        return legacy.Failure.IDENTITY_CONFLICT
    entries = current.current.builds if previous else (*current.current.builds, incoming.current)
    retained = {item.mainRevision for item in merged.builds}
    ordered = tuple(sorted(entries, key=lambda item: positions[item.mainRevision]))
    return CatalogPair(
        merged, Catalog(2, tuple(item for item in ordered if item.mainRevision in retained))
    )


def publish(
    incoming: PairedBuild,
    read: Callable[[], Snapshot | legacy.Failure],
    history: Callable[[tuple[str, ...]], tuple[str, ...] | legacy.Failure],
    commit: Callable[[Snapshot, CatalogPair, str], legacy.CommitResult | legacy.Failure],
) -> legacy.CommitResult | legacy.Failure:
    for _ in range(legacy.MAXIMUM_PUBLICATION_TRIES):
        snapshot = read()
        if isinstance(snapshot, legacy.Failure):
            return snapshot
        revisions = tuple(
            dict.fromkeys(
                item.mainRevision for item in (*snapshot.catalogs.legacy.builds, incoming.legacy)
            )
        )
        ordered = history(revisions)
        if isinstance(ordered, legacy.Failure):
            return ordered
        merged = merge(snapshot.catalogs, incoming, ordered)
        if isinstance(merged, legacy.Failure):
            return merged
        result = commit(snapshot, merged, legacy.latest_pointer(merged.legacy))
        if result is not legacy.CommitResult.CONTENDED:
            return result
    return legacy.Failure.CONCURRENT_CHANGE
