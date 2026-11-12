"""One bounded discovery window over already admitted immutable developer releases."""

from __future__ import annotations

import json
import re
import sys
from collections.abc import Callable
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import TypeGuard

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "packaging"))
import managed_release as product

MAXIMUM_PUBLICATION_TRIES = 4
MAXIMUM_CATALOG_BYTES = 65_536
WINDOW = 5
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
VERSION = re.compile(r"0\.0\.[1-9][0-9]*\Z")


class Failure(StrEnum):
    CATALOG_INVALID = "CATALOG_INVALID"
    IDENTITY_CONFLICT = "IDENTITY_CONFLICT"
    MAIN_HISTORY_UNPROVEN = "MAIN_HISTORY_UNPROVEN"
    CONCURRENT_CHANGE = "CONCURRENT_CHANGE"
    REMOTE_UNAVAILABLE = "REMOTE_UNAVAILABLE"
    REMOTE_INVALID = "REMOTE_INVALID"
    ASSET_MISMATCH = "ASSET_MISMATCH"


class CommitResult(StrEnum):
    COMMITTED = "COMMITTED"
    CONTENDED = "CONTENDED"


@dataclass(frozen=True, slots=True)
class RecordReference:
    name: str
    sha256: str


@dataclass(frozen=True, slots=True)
class Build:
    mainRevision: str
    buildRevision: str
    sourceTree: str
    releaseTag: str
    version: str
    platformTargets: tuple[str, ...]
    candidate: RecordReference
    promotion: RecordReference


@dataclass(frozen=True, slots=True)
class ProductBuild:
    mainRevision: str
    releaseTag: str
    record: product.ProductRecord
    recordSha256: str

    @property
    def buildRevision(self) -> str:
        return self.record.sourceRevision

    @property
    def sourceTree(self) -> str:
        return self.record.sourceTree

    @property
    def version(self) -> str:
        return self.record.version


type Entry = Build | ProductBuild


@dataclass(frozen=True, slots=True)
class Catalog:
    schemaVersion: int
    builds: tuple[Entry, ...]


@dataclass(frozen=True, slots=True)
class Snapshot:
    head: str
    tree: str
    catalog: Catalog
    absent: bool


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def sequence(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def valid(build: Entry) -> bool:
    if isinstance(build, ProductBuild):
        return (
            SHA.fullmatch(build.mainRevision) is not None
            and build.mainRevision != "0" * 40
            and build.mainRevision == build.record.sourceRevision
            and build.releaseTag == f"developer-v{build.version}"
            and DIGEST.fullmatch(build.recordSha256) is not None
            and product.decode_product_record(json.dumps(asdict(build.record)).encode())
            == build.record
        )
    return (
        all(
            SHA.fullmatch(value) and value != "0" * 40
            for value in (build.mainRevision, build.buildRevision, build.sourceTree)
        )
        and VERSION.fullmatch(build.version) is not None
        and build.releaseTag == f"developer-v{build.version}"
        and build.platformTargets == ("macos-aarch64",)
        and build.candidate.name == "candidate.json"
        and build.promotion.name == "promotion.json"
        and all(DIGEST.fullmatch(record.sha256) for record in (build.candidate, build.promotion))
    )


def record(value: object, name: str) -> RecordReference | Failure:
    if not mapping(value) or set(value) != {"name", "sha256"}:
        return Failure.CATALOG_INVALID
    filename, digest = value["name"], value["sha256"]
    if not isinstance(filename, str) or filename != name or not isinstance(digest, str):
        return Failure.CATALOG_INVALID
    if not DIGEST.fullmatch(digest):
        return Failure.CATALOG_INVALID
    return RecordReference(filename, digest)


def build(value: object) -> Entry | Failure:
    if mapping(value) and set(value) == {"mainRevision", "releaseTag", "record", "recordSha256"}:
        main, tag, digest = value["mainRevision"], value["releaseTag"], value["recordSha256"]
        product_record = product.parse_product_record(value["record"])
        if (
            not isinstance(main, str)
            or not isinstance(tag, str)
            or not isinstance(digest, str)
            or isinstance(product_record, product.Failure)
        ):
            return Failure.CATALOG_INVALID
        complete = ProductBuild(main, tag, product_record, digest)
        return complete if valid(complete) else Failure.CATALOG_INVALID
    if not mapping(value) or set(value) != {
        "mainRevision",
        "buildRevision",
        "sourceTree",
        "releaseTag",
        "version",
        "platformTargets",
        "candidate",
        "promotion",
    }:
        return Failure.CATALOG_INVALID
    main, original, tree = value["mainRevision"], value["buildRevision"], value["sourceTree"]
    tag, version, platforms = value["releaseTag"], value["version"], value["platformTargets"]
    candidate, promotion = (
        record(value["candidate"], "candidate.json"),
        record(value["promotion"], "promotion.json"),
    )
    if (
        not isinstance(main, str)
        or not isinstance(original, str)
        or not isinstance(tree, str)
        or not isinstance(tag, str)
        or not isinstance(version, str)
        or not sequence(platforms)
        or platforms != ["macos-aarch64"]
        or isinstance(candidate, Failure)
        or isinstance(promotion, Failure)
    ):
        return Failure.CATALOG_INVALID
    result = Build(main, original, tree, tag, version, ("macos-aarch64",), candidate, promotion)
    return result if valid(result) else Failure.CATALOG_INVALID


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("duplicate field")
    return result


def decode(raw: str) -> Catalog | Failure:
    if len(raw.encode()) > MAXIMUM_CATALOG_BYTES:
        return Failure.CATALOG_INVALID
    try:
        value: object = json.loads(raw, object_pairs_hook=unique_object)
    except (ValueError, RecursionError):
        return Failure.CATALOG_INVALID
    if not mapping(value) or set(value) != {"schemaVersion", "builds"}:
        return Failure.CATALOG_INVALID
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] not in (1, 2):
        return Failure.CATALOG_INVALID
    values = value["builds"]
    if not sequence(values) or len(values) > WINDOW:
        return Failure.CATALOG_INVALID
    entries: list[Entry] = []
    for item in values:
        admitted = build(item)
        if (
            isinstance(admitted, Failure)
            or (value["schemaVersion"] == 1 and isinstance(admitted, ProductBuild))
            or (value["schemaVersion"] == 2 and isinstance(admitted, Build))
        ):
            return Failure.CATALOG_INVALID
        entries.append(admitted)
    if len({item.mainRevision for item in entries}) != len(entries):
        return Failure.CATALOG_INVALID
    return Catalog(value["schemaVersion"], tuple(entries))


def encode(catalog: Catalog) -> str:
    return json.dumps(asdict(catalog), separators=(",", ":")) + "\n"


def merge(current: Catalog, incoming: Entry, main_history: tuple[str, ...]) -> Catalog | Failure:
    """History lists source identities newest first. Job time and version do not order builds."""
    if (
        current.schemaVersion not in (1, 2)
        or len(current.builds) > WINDOW
        or not valid(incoming)
        or not all(valid(item) for item in current.builds)
        or len({item.mainRevision for item in current.builds}) != len(current.builds)
    ):
        return Failure.CATALOG_INVALID
    positions = {revision: index for index, revision in enumerate(main_history)}
    if len(positions) != len(main_history) or any(
        item.mainRevision not in positions for item in (*current.builds, incoming)
    ):
        return Failure.MAIN_HISTORY_UNPROVEN
    if (
        tuple(sorted(current.builds, key=lambda item: positions[item.mainRevision]))
        != current.builds
    ):
        return Failure.CATALOG_INVALID
    previous = next(
        (item for item in current.builds if item.mainRevision == incoming.mainRevision), None
    )
    if previous is not None and previous != incoming:
        return Failure.IDENTITY_CONFLICT
    entries = current.builds if previous is not None else (*current.builds, incoming)
    if isinstance(incoming, ProductBuild):
        # Old releases and pins are untouched; the discovery window now advertises products only.
        entries = tuple(item for item in entries if isinstance(item, ProductBuild))
    elif current.schemaVersion == 2:
        return Failure.CATALOG_INVALID
    return Catalog(
        2 if isinstance(incoming, ProductBuild) or current.schemaVersion == 2 else 1,
        tuple(sorted(entries, key=lambda item: positions[item.mainRevision])[:WINDOW]),
    )


def latest_pointer(catalog: Catalog) -> str:
    newest = catalog.builds[0]
    return f"{newest.releaseTag} {newest.version} {newest.buildRevision}\n"


def publish(
    incoming: Entry,
    read: Callable[[], Snapshot | Failure],
    history: Callable[[tuple[str, ...]], tuple[str, ...] | Failure],
    commit: Callable[[Snapshot, Catalog, str], CommitResult | Failure],
) -> CommitResult | Failure:
    """A contended fast-forward guard always restarts from the newest admitted snapshot."""
    for _ in range(MAXIMUM_PUBLICATION_TRIES):
        snapshot = read()
        if isinstance(snapshot, Failure):
            return snapshot
        revisions = tuple(
            dict.fromkeys(item.mainRevision for item in (*snapshot.catalog.builds, incoming))
        )
        ordered = history(revisions)
        if isinstance(ordered, Failure):
            return ordered
        merged = merge(snapshot.catalog, incoming, ordered)
        if isinstance(merged, Failure):
            return merged
        result = commit(snapshot, merged, latest_pointer(merged))
        if result is not CommitResult.CONTENDED:
            return result
    return Failure.CONCURRENT_CHANGE
