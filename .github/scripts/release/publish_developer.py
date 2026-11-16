#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = []
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Publish admitted candidate bytes before an atomic developer catalog update."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from urllib.parse import quote

import build_candidate
import developer_catalog as catalog
import product_candidate

BRANCH = "developer-latest"
MAXIMUM_API_BYTES = 262_144
MAXIMUM_DISCOVERY_PAGES = 100


class Stage(StrEnum):
    INPUT = "INPUT"
    QUALIFICATION = "QUALIFICATION"
    IMMUTABLE_RELEASE = "IMMUTABLE_RELEASE"
    CATALOG = "CATALOG"


@dataclass(frozen=True, slots=True)
class Rejected:
    stage: Stage
    reason: catalog.Failure | build_candidate.Failure
    evidence: tuple[ReleaseEvent, ...] = ()


@dataclass(frozen=True, slots=True)
class PublicationAsset:
    source: Path
    sha256: str
    bytes: int


@dataclass(frozen=True, slots=True)
class PreparedPublication:
    build: catalog.Entry
    assets: tuple[PublicationAsset, ...]
    mapping: catalog.product.ReleaseMapping | None = None


@dataclass(frozen=True, slots=True)
class IncompleteDraft:
    missing: tuple[PublicationAsset, ...]


class ReleaseState(StrEnum):
    COMPLETE_DRAFT = "COMPLETE_DRAFT"
    PUBLISHED = "PUBLISHED"


@dataclass(frozen=True, slots=True)
class ReleaseId:
    value: int

    def __post_init__(self) -> None:
        if type(self.value) is not int or self.value <= 0:
            raise ValueError("release ID must be a positive integer")


class ReleaseEffect(StrEnum):
    UNCHANGED = "UNCHANGED"
    RECONCILED = "RECONCILED"


@dataclass(frozen=True, slots=True)
class PublishedRelease:
    identity: ReleaseId
    prepared: PreparedPublication
    effect: ReleaseEffect


@dataclass(frozen=True, slots=True)
class InspectedRelease:
    identity: ReleaseId
    upload_url: str
    state: IncompleteDraft | ReleaseState


class DiscoveryState(StrEnum):
    ABSENT = "ABSENT"


@dataclass(frozen=True, slots=True)
class DiscoveredRelease:
    identity: ReleaseId
    tag_name: str
    draft: bool


class ResponseProjection(StrEnum):
    RELEASE_DISCOVERY = "[.[] | {id,tag_name,draft}]"


class TagState(StrEnum):
    MATCHING = "MATCHING"
    ABSENT = "ABSENT"


class ReleaseOperation(StrEnum):
    CREATE = "CREATE"
    UPLOAD = "UPLOAD"
    PUBLISH = "PUBLISH"
    INSPECT = "INSPECT"


@dataclass(frozen=True, slots=True)
class ProcessResult:
    exit: int
    output: bytes


@dataclass(frozen=True, slots=True)
class ApiDocument:
    value: object
    has_next: bool = False


class ApiFailure(StrEnum):
    ABSENT = "ABSENT"
    CONTENDED = "CONTENDED"
    UNAVAILABLE = "UNAVAILABLE"
    INVALID = "INVALID"


@dataclass(frozen=True, slots=True)
class ReleaseEvent:
    operation: ReleaseOperation
    outcome: ApiFailure | ReleaseState
    identity: ReleaseId | None = None


@dataclass(frozen=True, slots=True)
class PublicationEvidence:
    events: tuple[ReleaseEvent, ...]


class LatestPolicy(StrEnum):
    TRUE = "true"
    FALSE = "false"


@dataclass(frozen=True, slots=True)
class ReleaseCreation:
    tag_name: str
    target_commitish: str
    name: str
    body: str
    prerelease: bool
    draft: bool = True
    make_latest: LatestPolicy = LatestPolicy.FALSE


@dataclass(frozen=True, slots=True)
class ReleasePublication:
    make_latest: LatestPolicy
    draft: bool = False


@dataclass(frozen=True, slots=True)
class GitTreeFile:
    path: str
    content: str
    mode: str = "100644"
    type: str = "blob"


@dataclass(frozen=True, slots=True)
class GitTreeRequest:
    base_tree: str
    tree: tuple[GitTreeFile, ...]


@dataclass(frozen=True, slots=True)
class GitCommitRequest:
    message: str
    tree: str
    parents: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class GitRefUpdate:
    sha: str
    force: bool = False


@dataclass(frozen=True, slots=True)
class GitRefCreation:
    ref: str
    sha: str


type GitRequest = GitTreeRequest | GitCommitRequest | GitRefUpdate | GitRefCreation
type ApiRequest = GitRequest | ReleaseCreation | ReleasePublication | PublicationAsset
type Invoke = Callable[[Sequence[str], bytes | None], ProcessResult | catalog.Failure]


def invoke(arguments: Sequence[str], payload: bytes | None) -> ProcessResult | catalog.Failure:
    try:
        result = subprocess.run(
            arguments, input=payload, capture_output=True, timeout=180, check=False
        )
    except (OSError, subprocess.TimeoutExpired):
        return catalog.Failure.REMOTE_UNAVAILABLE
    if len(result.stdout) > MAXIMUM_API_BYTES:
        return catalog.Failure.REMOTE_INVALID
    return ProcessResult(result.returncode, result.stdout)


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def prepare(
    repository: str, revision: str, version: str, assets: Path, promotion: Path
) -> PreparedPublication | Rejected:
    """Keep original qualification, producer, retained bytes, and promotion together."""
    if assets.name != "payload" or promotion.name != "promotion.json":
        return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
    try:
        admitted = build_candidate.verify_publication(
            repository, revision, version, assets.parent, promotion
        )
        candidate = build_candidate.read(assets.parent)
        candidate_path = assets.parent / "candidate.json"
        candidate_digest = digest(candidate_path)
        promotion_bytes = promotion.read_bytes()
        if candidate_digest != admitted.candidateSha256 or json.loads(
            promotion_bytes, object_pairs_hook=catalog.unique_object
        ) != asdict(admitted):
            return Rejected(Stage.QUALIFICATION, build_candidate.Failure.PAYLOAD_MISMATCH)
        entry = catalog.Build(
            admitted.mergedRevision,
            admitted.buildRevision,
            admitted.sourceTree,
            f"developer-v{candidate.version}",
            candidate.version,
            (candidate.platform,),
            catalog.RecordReference("candidate.json", candidate_digest),
            catalog.RecordReference("promotion.json", hashlib.sha256(promotion_bytes).hexdigest()),
        )
        if not catalog.valid(entry):
            return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
        files = tuple(
            PublicationAsset(assets / asset.name, asset.sha256, asset.bytes)
            for asset in candidate.assets
        ) + (
            PublicationAsset(candidate_path, candidate_digest, candidate_path.stat().st_size),
            PublicationAsset(promotion, entry.promotion.sha256, len(promotion_bytes)),
        )
        if len({asset.source.name for asset in files}) != len(files):
            return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
        return PreparedPublication(entry, files)
    except build_candidate.Rejected as failure:
        return Rejected(Stage.QUALIFICATION, failure.failure)
    except (OSError, ValueError):
        return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)


def prepare_product(directory: Path) -> PreparedPublication | Rejected:
    try:
        record = product_candidate.admit(directory)
        if isinstance(record, build_candidate.Failure):
            return Rejected(Stage.QUALIFICATION, record)
        record_path = directory / "build-record.json"
        entry = catalog.ProductBuild(
            record.sourceRevision, f"developer-v{record.version}", record, digest(record_path)
        )
        files = tuple(
            PublicationAsset(directory / "payload" / asset.name, asset.sha256, asset.bytes)
            for asset in (*record.assets(), *product_candidate.acceptance.assets(directory, record))
        ) + (PublicationAsset(record_path, entry.recordSha256, record_path.stat().st_size),)
        return PreparedPublication(entry, files)
    except build_candidate.Rejected as failure:
        return Rejected(Stage.QUALIFICATION, failure.failure)
    except (OSError, ValueError):
        return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)


def prepare_stable(
    directory: Path, public_version: str, expected_digest: str
) -> PreparedPublication | Rejected:
    """A stable name maps to the original build record; no archive is opened for writing."""
    record = product_candidate.read(directory, accepted=True)
    if isinstance(record, build_candidate.Failure):
        return Rejected(Stage.QUALIFICATION, record)
    record_path = directory / "build-record.json"
    if (
        len(public_version) > 64
        or not catalog.product.VERSION.fullmatch(public_version)
        or digest(record_path) != expected_digest
    ):
        return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_IDENTITY)
    mapping = catalog.product.ReleaseMapping(1, public_version, expected_digest)
    mapping_path = directory.parent / "release-mapping.json"
    raw = (json.dumps(asdict(mapping), separators=(",", ":")) + "\n").encode()
    if mapping_path.exists():
        if mapping_path.is_symlink() or mapping_path.read_bytes() != raw:
            return Rejected(Stage.INPUT, build_candidate.Failure.PAYLOAD_MISMATCH)
    else:
        with mapping_path.open("xb") as output:
            _ = output.write(raw)
    entry = catalog.ProductBuild(
        record.sourceRevision, f"v{public_version}", record, expected_digest
    )
    files = tuple(
        PublicationAsset(directory / "payload" / asset.name, asset.sha256, asset.bytes)
        for asset in (*record.assets(), *product_candidate.acceptance.assets(directory, record))
    ) + (
        PublicationAsset(record_path, expected_digest, record_path.stat().st_size),
        PublicationAsset(mapping_path, hashlib.sha256(raw).hexdigest(), len(raw)),
    )
    return PreparedPublication(entry, files, mapping)


class RemotePublisher:
    def __init__(self, repository: str, incoming: catalog.Entry, run: Invoke = invoke):
        self.repository = repository
        self.incoming = incoming
        self.run = run
        self.evidence: list[ReleaseEvent] = []

    def api(
        self,
        method: str,
        route: str,
        request: ApiRequest | None = None,
        *,
        projection: ResponseProjection | None = None,
    ) -> ApiDocument | ApiFailure:
        arguments = [
            "gh",
            "api",
            "--include",
            "--method",
            method,
            route
            if route.startswith("https://uploads.github.com/")
            else f"repos/{self.repository}/{route}".rstrip("/"),
        ]
        if projection is not None:
            if (
                projection is not ResponseProjection.RELEASE_DISCOVERY
                or method != "GET"
                or request is not None
                or re.fullmatch(r"releases\?per_page=100&page=[1-9][0-9]*", route) is None
            ):
                return ApiFailure.INVALID
            # gh projects the full page before invoke applies its stdout limit.
            # Asset inventory and all other endpoints keep their original schemas.
            arguments.extend(["--jq", projection.value])
        payload = None
        if isinstance(request, PublicationAsset):
            arguments.extend(
                [
                    "--header",
                    "Content-Type: application/octet-stream",
                    "--input",
                    str(request.source),
                ]
            )
        elif request is not None:
            arguments.extend(["--input", "-"])
            payload = json.dumps(asdict(request)).encode()
        result = self.run(arguments, payload)
        if isinstance(result, catalog.Failure):
            return (
                ApiFailure.INVALID
                if result is catalog.Failure.REMOTE_INVALID
                else ApiFailure.UNAVAILABLE
            )
        header, separator, body = result.output.replace(b"\r\n", b"\n").partition(b"\n\n")
        first = header.split(b"\n", 1)[0].split()
        if not separator or len(first) < 2 or not first[1].isdigit():
            return ApiFailure.INVALID
        status = int(first[1])
        if status == 404:
            return ApiFailure.ABSENT
        if status in (409, 422):
            return ApiFailure.CONTENDED
        if result.exit or not 200 <= status < 300:
            return ApiFailure.UNAVAILABLE
        try:
            value: object = json.loads(body, object_pairs_hook=catalog.unique_object)
            relations: set[str] = set()
            for line in header.split(b"\n")[1:]:
                name, separator, raw_value = line.partition(b":")
                if separator and name.lower() == b"link":
                    for link in raw_value.decode("ascii").split(","):
                        match = re.fullmatch(
                            r'<https://api[.]github[.]com/[^<>\s]+>; rel="(next|prev|first|last)"',
                            link.strip(),
                        )
                        if match is None or match[1] in relations:
                            return ApiFailure.INVALID
                        relations.add(match[1])
            return ApiDocument(value, "next" in relations)
        except (ValueError, UnicodeDecodeError):
            return ApiFailure.INVALID

    def listing(
        self, route: str, *, projection: ResponseProjection | None = None
    ) -> tuple[object, ...] | catalog.Failure:
        """Read through an empty terminal page; a failed page never establishes absence."""
        records: list[object] = []
        for page in range(1, MAXIMUM_DISCOVERY_PAGES + 1):
            result = self.api("GET", f"{route}?per_page=100&page={page}", projection=projection)
            if not isinstance(result, ApiDocument):
                return (
                    catalog.Failure.REMOTE_INVALID
                    if result is ApiFailure.INVALID
                    else catalog.Failure.RELEASE_DISCOVERY_INCOMPLETE
                )
            if not catalog.sequence(result.value) or len(result.value) > 100:
                return catalog.Failure.REMOTE_INVALID
            if not result.value:
                return (
                    catalog.Failure.RELEASE_DISCOVERY_INCOMPLETE
                    if result.has_next
                    else tuple(records)
                )
            records.extend(result.value)
        return catalog.Failure.RELEASE_DISCOVERY_INCOMPLETE

    def discover(self, tag: str) -> ReleaseId | DiscoveryState | catalog.Failure:
        repository = self.api("GET", "")
        if not isinstance(repository, ApiDocument) or not catalog.mapping(repository.value):
            return catalog.Failure.DRAFT_VISIBILITY_UNPROVEN
        permissions = repository.value.get("permissions")
        if (
            repository.value.get("full_name") != self.repository
            or not catalog.mapping(permissions)
            or permissions.get("push") is not True
        ):
            return catalog.Failure.DRAFT_VISIBILITY_UNPROVEN
        releases = self.listing("releases", projection=ResponseProjection.RELEASE_DISCOVERY)
        if isinstance(releases, catalog.Failure):
            return releases
        seen: set[ReleaseId] = set()
        matches: list[ReleaseId] = []
        for release in releases:
            admitted = discovered_release(release)
            if isinstance(admitted, catalog.Failure):
                return admitted
            if admitted.identity in seen:
                return catalog.Failure.RELEASE_IDENTITY_CONFLICT
            seen.add(admitted.identity)
            if admitted.tag_name == tag:
                matches.append(admitted.identity)
        if len(matches) > 1:
            return catalog.Failure.RELEASE_IDENTITY_CONFLICT
        return matches[0] if matches else DiscoveryState.ABSENT

    def inspect(
        self, identity: ReleaseId, prepared: PreparedPublication
    ) -> InspectedRelease | catalog.Failure:
        route = f"releases/{identity.value}"
        result = self.api("GET", route)
        if not isinstance(result, ApiDocument) or not catalog.mapping(result.value):
            self.evidence.append(
                ReleaseEvent(
                    ReleaseOperation.INSPECT,
                    result if isinstance(result, ApiFailure) else ApiFailure.INVALID,
                    identity,
                )
            )
            return (
                catalog.Failure.REMOTE_INVALID
                if result is ApiFailure.INVALID or isinstance(result, ApiDocument)
                else catalog.Failure.REMOTE_UNAVAILABLE
            )
        observed_identity = release_id(result.value)
        if isinstance(observed_identity, catalog.Failure):
            return observed_identity
        if observed_identity != identity:
            return catalog.Failure.RELEASE_IDENTITY_CONFLICT
        upload_url = result.value.get("upload_url")
        expected_url = (
            f"https://uploads.github.com/repos/{self.repository}/releases/{identity.value}/assets"
        )
        if upload_url != expected_url + "{?name,label}":
            return catalog.Failure.REMOTE_INVALID
        inventory = self.listing(f"{route}/assets")
        if isinstance(inventory, catalog.Failure):
            return inventory
        state = inspect_release(result.value, prepared, inventory)
        if isinstance(state, catalog.Failure):
            return state
        return InspectedRelease(identity, expected_url, state)

    def tag_target(
        self, prepared: PreparedPublication, *, draft: bool
    ) -> TagState | catalog.Failure:
        result = self.api("GET", f"git/ref/tags/{quote(prepared.build.releaseTag, safe='')}")
        if result is ApiFailure.ABSENT and draft:
            return TagState.ABSENT
        if not isinstance(result, ApiDocument) or not catalog.mapping(result.value):
            return catalog.Failure.REMOTE_UNAVAILABLE
        target = result.value.get("object")
        for _ in range(8):
            if not catalog.mapping(target):
                return catalog.Failure.REMOTE_INVALID
            sha, kind = target.get("sha"), target.get("type")
            if not isinstance(sha, str) or not catalog.SHA.fullmatch(sha):
                return catalog.Failure.REMOTE_INVALID
            if kind == "commit":
                return (
                    TagState.MATCHING
                    if sha == prepared.build.buildRevision
                    else catalog.Failure.ASSET_MISMATCH
                )
            if kind != "tag":
                return catalog.Failure.REMOTE_INVALID
            annotated = self.api("GET", f"git/tags/{sha}")
            if not isinstance(annotated, ApiDocument) or not catalog.mapping(annotated.value):
                return catalog.Failure.REMOTE_UNAVAILABLE
            if annotated.value.get("sha") != sha:
                return catalog.Failure.REMOTE_INVALID
            target = annotated.value.get("object")
        return catalog.Failure.REMOTE_INVALID

    def release(self, prepared: PreparedPublication) -> PublishedRelease | catalog.Failure:
        self.evidence.clear()
        try:
            if any(
                asset.source.is_symlink()
                or not asset.source.is_file()
                or asset.source.stat().st_size != asset.bytes
                or digest(asset.source) != asset.sha256
                for asset in prepared.assets
            ):
                return catalog.Failure.ASSET_MISMATCH
        except OSError:
            return catalog.Failure.ASSET_MISMATCH
        identity = self.discover(prepared.build.releaseTag)
        if isinstance(identity, catalog.Failure):
            return identity
        effect = ReleaseEffect.UNCHANGED
        if identity is DiscoveryState.ABSENT:
            target_failure = self.tag_target(prepared, draft=True)
            if isinstance(target_failure, catalog.Failure):
                return target_failure
            build = prepared.build
            effect = ReleaseEffect.RECONCILED
            created = self.api(
                "POST",
                "releases",
                ReleaseCreation(
                    build.releaseTag,
                    build.buildRevision,
                    (
                        f"Kast developer {build.version}"
                        if prepared.mapping is None
                        else f"Kast {prepared.mapping.publicVersion}"
                    ),
                    f"Retained build {build.version} from {build.buildRevision}. "
                    "Publication does not rebuild or change archive bytes.",
                    prepared.mapping is None,
                ),
            )
            if isinstance(created, ApiDocument):
                identity = release_id(created.value)
                if isinstance(identity, catalog.Failure):
                    return identity
                state = inspect_release(created.value, prepared)
                if isinstance(state, catalog.Failure):
                    return state
            else:
                self.evidence.append(ReleaseEvent(ReleaseOperation.CREATE, created))
                # Creation is not idempotent. Reconcile once, never blindly create again.
                identity = self.discover(prepared.build.releaseTag)
                if isinstance(identity, catalog.Failure):
                    return identity
                if identity is DiscoveryState.ABSENT:
                    return catalog.Failure.REMOTE_UNAVAILABLE
        current = self.inspect(identity, prepared)
        if isinstance(current, catalog.Failure):
            return current
        if current.state is not ReleaseState.PUBLISHED:
            target_failure = self.tag_target(prepared, draft=True)
            if isinstance(target_failure, catalog.Failure):
                return target_failure
        if isinstance(current.state, IncompleteDraft):
            effect = ReleaseEffect.RECONCILED
            for asset in current.state.missing:
                uploaded = self.api(
                    "POST", f"{current.upload_url}?name={quote(asset.source.name, safe='')}", asset
                )
                if not isinstance(uploaded, ApiDocument):
                    self.evidence.append(ReleaseEvent(ReleaseOperation.UPLOAD, uploaded, identity))
                    break
            current = self.inspect(identity, prepared)
            if isinstance(current, catalog.Failure):
                return current
        if current.state is ReleaseState.COMPLETE_DRAFT:
            effect = ReleaseEffect.RECONCILED
            published = self.api(
                "PATCH",
                f"releases/{identity.value}",
                ReleasePublication(
                    LatestPolicy.FALSE if prepared.mapping is None else LatestPolicy.TRUE
                ),
            )
            if not isinstance(published, ApiDocument):
                self.evidence.append(ReleaseEvent(ReleaseOperation.PUBLISH, published, identity))
            else:
                observed_identity = release_id(published.value)
                if isinstance(observed_identity, catalog.Failure):
                    return observed_identity
                if observed_identity != identity:
                    return catalog.Failure.RELEASE_IDENTITY_CONFLICT
            current = self.inspect(identity, prepared)
            if isinstance(current, catalog.Failure):
                return current
        if current.state is not ReleaseState.PUBLISHED:
            return catalog.Failure.REMOTE_UNAVAILABLE
        target_failure = self.tag_target(prepared, draft=False)
        if isinstance(target_failure, catalog.Failure):
            return target_failure
        self.evidence.append(
            ReleaseEvent(ReleaseOperation.INSPECT, ReleaseState.PUBLISHED, identity)
        )
        return PublishedRelease(identity, prepared, effect)

    def snapshot(self) -> catalog.Snapshot | catalog.Failure:
        ref = self.api("GET", f"git/ref/heads/{BRANCH}")
        absent = ref is ApiFailure.ABSENT
        if absent:
            head = self.incoming.mainRevision
        elif isinstance(ref, ApiDocument) and catalog.mapping(ref.value):
            target = ref.value.get("object")
            head = target.get("sha") if catalog.mapping(target) else None
        else:
            return catalog.Failure.REMOTE_UNAVAILABLE
        if not isinstance(head, str) or not catalog.SHA.fullmatch(head):
            return catalog.Failure.REMOTE_INVALID
        commit = self.api("GET", f"git/commits/{head}")
        if not isinstance(commit, ApiDocument) or not catalog.mapping(commit.value):
            return catalog.Failure.REMOTE_UNAVAILABLE
        tree = commit.value.get("tree")
        tree_sha = tree.get("sha") if catalog.mapping(tree) else None
        if (
            commit.value.get("sha") != head
            or not isinstance(tree_sha, str)
            or not catalog.SHA.fullmatch(tree_sha)
        ):
            return catalog.Failure.REMOTE_INVALID
        document = (
            self.api("GET", f"contents/builds.json?ref={head}") if not absent else ApiFailure.ABSENT
        )
        if document is ApiFailure.ABSENT:
            return catalog.Snapshot(head, tree_sha, catalog.Catalog(1, ()), absent)
        if not isinstance(document, ApiDocument) or not catalog.mapping(document.value):
            return catalog.Failure.REMOTE_UNAVAILABLE
        raw = file_text(document)
        if isinstance(raw, catalog.Failure):
            return raw
        admitted = catalog.decode(raw)
        if isinstance(admitted, catalog.Failure):
            return admitted
        pointer_document = self.api("GET", f"contents/latest.txt?ref={head}")
        if pointer_document is ApiFailure.ABSENT:
            pointer = None
        else:
            pointer = file_text(pointer_document)
            if isinstance(pointer, catalog.Failure):
                return pointer
        return catalog.Snapshot(head, tree_sha, admitted, absent, pointer)

    def comparison(self, older: str, newer: str) -> str | catalog.Failure:
        # Status describes the whole comparison on every page. Avoid the first
        # page's unrelated file patches and retain at most one commit record.
        result = self.api("GET", f"compare/{older}...{newer}?per_page=1&page=2")
        if not isinstance(result, ApiDocument) or not catalog.mapping(result.value):
            return catalog.Failure.MAIN_HISTORY_UNPROVEN
        status = result.value.get("status")
        if status not in ("ahead", "behind", "identical"):
            return catalog.Failure.MAIN_HISTORY_UNPROVEN
        assert isinstance(status, str)
        return status

    def history(self, revisions: tuple[str, ...]) -> tuple[str, ...] | catalog.Failure:
        main = self.api("GET", "git/ref/heads/main")
        if not isinstance(main, ApiDocument) or not catalog.mapping(main.value):
            return catalog.Failure.MAIN_HISTORY_UNPROVEN
        target = main.value.get("object")
        head = target.get("sha") if catalog.mapping(target) else None
        if not isinstance(head, str) or not catalog.SHA.fullmatch(head):
            return catalog.Failure.MAIN_HISTORY_UNPROVEN
        for revision in revisions:
            if self.comparison(revision, head) not in ("ahead", "identical"):
                return catalog.Failure.MAIN_HISTORY_UNPROVEN
        positions = dict.fromkeys(revisions, 0)
        for index, left in enumerate(revisions):
            for right in revisions[index + 1 :]:
                order = self.comparison(left, right)
                if order == "ahead":
                    positions[left] += 1
                elif order == "behind":
                    positions[right] += 1
                else:
                    return catalog.Failure.MAIN_HISTORY_UNPROVEN
        if set(positions.values()) != set(range(len(revisions))):
            return catalog.Failure.MAIN_HISTORY_UNPROVEN
        return tuple(sorted(revisions, key=lambda revision: positions[revision]))

    def commit(
        self, snapshot: catalog.Snapshot, document: catalog.Catalog, pointer: str
    ) -> catalog.CommitResult | catalog.Failure:
        tree = self.api(
            "POST",
            "git/trees",
            GitTreeRequest(
                snapshot.tree,
                (
                    GitTreeFile("builds.json", catalog.encode(document)),
                    GitTreeFile("latest.txt", pointer),
                ),
            ),
        )
        tree_sha = response_sha(tree)
        if isinstance(tree_sha, catalog.Failure):
            return tree_sha
        commit = self.api(
            "POST",
            "git/commits",
            GitCommitRequest(
                f"chore(distribution): admit {self.incoming.mainRevision} to published main builds",
                tree_sha,
                (snapshot.head,),
            ),
        )
        sha = response_sha(commit)
        if isinstance(sha, catalog.Failure):
            return sha
        result = (
            self.api("POST", "git/refs", GitRefCreation(f"refs/heads/{BRANCH}", sha))
            if snapshot.absent
            else self.api("PATCH", f"git/refs/heads/{BRANCH}", GitRefUpdate(sha))
        )
        if result is ApiFailure.CONTENDED:
            return catalog.CommitResult.CONTENDED
        if not isinstance(result, ApiDocument) or not catalog.mapping(result.value):
            return catalog.Failure.REMOTE_UNAVAILABLE
        target = result.value.get("object")
        if not catalog.mapping(target) or target.get("sha") != sha:
            return catalog.Failure.REMOTE_INVALID
        return catalog.CommitResult.COMMITTED


def file_text(result: ApiDocument | ApiFailure) -> str | catalog.Failure:
    if not isinstance(result, ApiDocument) or not catalog.mapping(result.value):
        return catalog.Failure.REMOTE_UNAVAILABLE
    content = result.value.get("content")
    if (
        result.value.get("type") != "file"
        or result.value.get("encoding") != "base64"
        or not isinstance(content, str)
    ):
        return catalog.Failure.CATALOG_INVALID
    try:
        return base64.b64decode(content.replace("\n", ""), validate=True).decode("utf-8")
    except (ValueError, UnicodeDecodeError):
        return catalog.Failure.CATALOG_INVALID


def release_id(value: object) -> ReleaseId | catalog.Failure:
    if not catalog.mapping(value):
        return catalog.Failure.REMOTE_INVALID
    identity = value.get("id")
    if type(identity) is not int or identity <= 0:
        return catalog.Failure.REMOTE_INVALID
    return ReleaseId(identity)


def discovered_release(value: object) -> DiscoveredRelease | catalog.Failure:
    if not catalog.mapping(value) or set(value) != {"id", "tag_name", "draft"}:
        return catalog.Failure.REMOTE_INVALID
    identity = release_id(value)
    if isinstance(identity, catalog.Failure):
        return identity
    tag, draft = value["tag_name"], value["draft"]
    if not isinstance(tag, str) or not tag or type(draft) is not bool:
        return catalog.Failure.REMOTE_INVALID
    return DiscoveredRelease(identity, tag, draft)


def response_sha(result: ApiDocument | ApiFailure) -> str | catalog.Failure:
    if not isinstance(result, ApiDocument) or not catalog.mapping(result.value):
        return catalog.Failure.REMOTE_UNAVAILABLE
    sha = result.value.get("sha")
    return (
        sha
        if isinstance(sha, str) and catalog.SHA.fullmatch(sha)
        else catalog.Failure.REMOTE_INVALID
    )


def inspect_release(
    value: object, prepared: PreparedPublication, inventory: tuple[object, ...] | None = None
) -> IncompleteDraft | ReleaseState | catalog.Failure:
    if not catalog.mapping(value) or value.get("target_commitish") != prepared.build.buildRevision:
        return catalog.Failure.ASSET_MISMATCH
    if (
        value.get("tag_name") != prepared.build.releaseTag
        or type(value.get("draft")) is not bool
        or value.get("prerelease") is not (prepared.mapping is None)
    ):
        return catalog.Failure.ASSET_MISMATCH
    raw_assets = value.get("assets")
    assets: Sequence[object]
    if inventory is not None:
        assets = inventory
    elif catalog.sequence(raw_assets):
        assets = raw_assets
    else:
        return catalog.Failure.ASSET_MISMATCH
    actual: dict[str, tuple[str, int]] = {}
    for asset in assets:
        if not catalog.mapping(asset):
            return catalog.Failure.ASSET_MISMATCH
        name, sha, size = asset.get("name"), asset.get("digest"), asset.get("size")
        if (
            not isinstance(name, str)
            or not isinstance(sha, str)
            or type(size) is not int
            or asset.get("state") != "uploaded"
            or name in actual
        ):
            return catalog.Failure.ASSET_MISMATCH
        actual[name] = (sha, size)
    expected = {
        asset.source.name: (f"sha256:{asset.sha256}", asset.bytes) for asset in prepared.assets
    }
    if any(expected.get(name) != identity for name, identity in actual.items()):
        return catalog.Failure.ASSET_MISMATCH
    if value["draft"] is False:
        return ReleaseState.PUBLISHED if actual == expected else catalog.Failure.ASSET_MISMATCH
    missing = tuple(asset for asset in prepared.assets if asset.source.name not in actual)
    return IncompleteDraft(missing) if missing else ReleaseState.COMPLETE_DRAFT


def publish(
    prepared: PreparedPublication, remote: RemotePublisher
) -> catalog.CommitResult | Rejected:
    released = remote.release(prepared)
    if isinstance(released, catalog.Failure):
        return Rejected(Stage.IMMUTABLE_RELEASE, released, tuple(remote.evidence))
    if prepared.mapping is not None:
        return (
            catalog.CommitResult.UNCHANGED
            if released.effect is ReleaseEffect.UNCHANGED
            else catalog.CommitResult.COMMITTED
        )
    result = catalog.publish(
        released.prepared.build, remote.snapshot, remote.history, remote.commit
    )
    if isinstance(result, catalog.Failure):
        return Rejected(Stage.CATALOG, result, tuple(remote.evidence))
    if result is catalog.CommitResult.UNCHANGED and released.effect is ReleaseEffect.RECONCILED:
        return catalog.CommitResult.COMMITTED
    return result


def main(arguments: list[str]) -> int:
    if arguments and arguments[0] in ("product", "stable"):
        try:
            return product_main(arguments)
        except (OSError, ValueError):
            print("publish-product: INVALID_RECORD", file=sys.stderr)
            return 1
    if arguments == ["--self-test"]:
        suite = unittest.defaultTestLoader.discover(
            str(Path(__file__).parent), pattern="test_developer*.py"
        )
        return (
            0
            if suite.countTestCases() > 0 and unittest.TextTestRunner().run(suite).wasSuccessful()
            else 1
        )
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--version", required=True)
    _ = parser.add_argument("--commit", required=True)
    _ = parser.add_argument("--assets-directory", required=True, type=Path)
    _ = parser.add_argument("--promotion-record", required=True, type=Path)
    args = parser.parse_args(arguments)
    repository = os.environ.get("GITHUB_REPOSITORY", "amichne/kast")
    if not os.environ.get("GH_TOKEN"):
        print("publish-developer: GH_TOKEN is absent", file=sys.stderr)
        return 1
    prepared = prepare(
        repository,
        args.commit,
        args.version,
        args.assets_directory.resolve(),
        args.promotion_record.resolve(),
    )
    if isinstance(prepared, Rejected):
        print(json.dumps(asdict(prepared)), file=sys.stderr)
        return 1
    # Legacy candidates remain readable for recovery, but are not complete products.
    print("publish-developer: complete build-record.json required", file=sys.stderr)
    return 1


def product_main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("action", choices=("product", "stable"))
    _ = parser.add_argument("--directory", type=Path)
    _ = parser.add_argument("--source-tag")
    _ = parser.add_argument("--record-sha256")
    _ = parser.add_argument("--public-version")
    args = parser.parse_args(arguments)
    repository = os.environ.get("GITHUB_REPOSITORY", "amichne/kast")
    if repository != catalog.product.REPOSITORY or not os.environ.get("GH_TOKEN"):
        return 1
    with tempfile.TemporaryDirectory(prefix="kast-product-promotion-") as temporary:
        if args.action == "product":
            if args.directory is None:
                return 1
            prepared = prepare_product(args.directory)
        else:
            if not all((args.source_tag, args.record_sha256, args.public_version)):
                return 1
            directory = Path(temporary) / "candidate"
            fetched = product_candidate.fetch_release(
                args.source_tag, args.record_sha256, directory, catalog.product.HttpReleaseSource()
            )
            if isinstance(fetched, build_candidate.Failure):
                print(f"publish-stable: {fetched.value}", file=sys.stderr)
                return 1
            # The immutable preview release is the trust anchor after Actions retention expires.
            preview = PreparedPublication(
                catalog.ProductBuild(
                    fetched.sourceRevision, args.source_tag, fetched, args.record_sha256
                ),
                tuple(
                    PublicationAsset(directory / "payload" / asset.name, asset.sha256, asset.bytes)
                    for asset in (
                        *fetched.assets(),
                        *product_candidate.acceptance.assets(directory, fetched),
                    )
                )
                + (
                    PublicationAsset(
                        directory / "build-record.json",
                        args.record_sha256,
                        (directory / "build-record.json").stat().st_size,
                    ),
                ),
            )
            remote = RemotePublisher(repository, preview.build)
            observed = remote.api("GET", f"releases/tags/{args.source_tag}")
            if (
                not isinstance(observed, ApiDocument)
                or inspect_release(observed.value, preview) is not ReleaseState.PUBLISHED
            ):
                return 1
            prepared = prepare_stable(directory, args.public_version, args.record_sha256)
        if isinstance(prepared, Rejected):
            print(json.dumps(asdict(prepared)), file=sys.stderr)
            return 1
        remote = RemotePublisher(repository, prepared.build)
        result = publish(prepared, remote)
        if remote.evidence:
            print(json.dumps(asdict(PublicationEvidence(tuple(remote.evidence)))), file=sys.stderr)
    if isinstance(result, Rejected):
        print(json.dumps(asdict(result)), file=sys.stderr)
        return 1
    print(f"https://raw.githubusercontent.com/{repository}/{BRANCH}/builds.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
