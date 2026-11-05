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
import subprocess
import sys
import unittest
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path

import build_candidate
import developer_catalog as catalog
import dual_candidate
import dual_catalog

BRANCH = "developer-latest"
MAXIMUM_API_BYTES = 262_144


class Stage(StrEnum):
    INPUT = "INPUT"
    QUALIFICATION = "QUALIFICATION"
    IMMUTABLE_RELEASE = "IMMUTABLE_RELEASE"
    CATALOG = "CATALOG"


@dataclass(frozen=True, slots=True)
class Rejected:
    stage: Stage
    reason: catalog.Failure | build_candidate.Failure


@dataclass(frozen=True, slots=True)
class PublicationAsset:
    source: Path
    sha256: str
    bytes: int


@dataclass(frozen=True, slots=True)
class PreparedPublication:
    build: catalog.Build
    assets: tuple[PublicationAsset, ...]
    paired: dual_catalog.Build | None = None


@dataclass(frozen=True, slots=True)
class ProcessResult:
    exit: int
    output: bytes


@dataclass(frozen=True, slots=True)
class ApiDocument:
    value: object


class ApiFailure(StrEnum):
    ABSENT = "ABSENT"
    CONTENDED = "CONTENDED"
    UNAVAILABLE = "UNAVAILABLE"
    INVALID = "INVALID"


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
        candidate = build_candidate.read_retained(assets.parent)
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


def prepare_dual(
    repository: str,
    revision: str,
    version: str,
    assets: Path,
    promotion: Path,
) -> PreparedPublication | Rejected:
    base = prepare(repository, revision, version, assets, promotion)
    if isinstance(base, Rejected):
        return base
    pair = dual_candidate.read(assets.parent)
    if isinstance(pair, dual_candidate.Failure):
        return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
    current_promotion_path = promotion.with_name("promotion-v2.json")
    try:
        old_value = dual_candidate.read_json(promotion, 4096)
        new_value = dual_candidate.read_json(current_promotion_path, 4096)
        if (
            not catalog.mapping(old_value)
            or not catalog.mapping(new_value)
            or set(old_value) != set(build_candidate.Promotion.__dataclass_fields__)
            or set(new_value) != set(build_candidate.Promotion.__dataclass_fields__)
        ):
            return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
        old = decode_promotion(old_value)
        current = decode_promotion(new_value)
        if isinstance(old, Rejected) or isinstance(current, Rejected):
            return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
        current_candidate_path = assets.parent / "candidate-v2.json"
        current_hash = digest(current_candidate_path)
        admitted = dual_candidate.admit_promotions(
            pair, old, current, base.build.candidate.sha256, current_hash
        )
        if isinstance(admitted, dual_candidate.Failure):
            return Rejected(Stage.QUALIFICATION, build_candidate.Failure.INVALID_RECORD)
        target_build = dual_catalog.Build(
            base.build.mainRevision,
            base.build.buildRevision,
            base.build.sourceTree,
            base.build.releaseTag,
            base.build.version,
            base.build.platformTargets,
            catalog.RecordReference("candidate-v2.json", current_hash),
            catalog.RecordReference("promotion-v2.json", digest(current_promotion_path)),
            pair.current.hostTargets,
        )
        files = tuple(
            PublicationAsset(assets / asset.name, asset.sha256, asset.bytes)
            for asset in pair.current.assets
        ) + (
            *base.assets[-2:],
            PublicationAsset(
                current_candidate_path, current_hash, current_candidate_path.stat().st_size
            ),
            PublicationAsset(
                current_promotion_path,
                target_build.promotion.sha256,
                current_promotion_path.stat().st_size,
            ),
        )
        if (
            len(files) != 28
            or len({asset.source.name for asset in files}) != 28
            or not dual_catalog.valid(target_build)
        ):
            return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
        return PreparedPublication(base.build, files, target_build)
    except (OSError, ValueError):
        return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)


def decode_promotion(value: dict[object, object]) -> build_candidate.Promotion | Rejected:
    # Decode scalar types before construction; bool must never become a run identity.
    names = ("schemaVersion", "buildRunId", "buildRunAttempt", "mainRunId", "mainRunAttempt")
    integers: list[int] = []
    for name in names:
        field = value.get(name)
        if type(field) is not int or field < 1:
            return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
        integers.append(field)
    names = (
        "repository",
        "buildRevision",
        "mergedRevision",
        "sourceTree",
        "version",
        "candidateSha256",
    )
    strings: list[str] = []
    for name in names:
        field = value.get(name)
        if not isinstance(field, str):
            return Rejected(Stage.INPUT, build_candidate.Failure.INVALID_RECORD)
        strings.append(field)
    return build_candidate.Promotion(
        integers[0],
        strings[0],
        strings[1],
        strings[2],
        strings[3],
        strings[4],
        integers[1],
        integers[2],
        integers[3],
        integers[4],
        strings[5],
    )


class RemotePublisher:
    def __init__(self, repository: str, incoming: catalog.Build, run: Invoke = invoke):
        self.repository = repository
        self.incoming = incoming
        self.run = run

    def api(
        self, method: str, route: str, request: GitRequest | None = None
    ) -> ApiDocument | ApiFailure:
        arguments = [
            "gh",
            "api",
            "--include",
            "--method",
            method,
            f"repos/{self.repository}/{route}",
        ]
        payload = None
        if request is not None:
            arguments.extend(["--input", "-"])
            payload = json.dumps(asdict(request)).encode()
        result = self.run(arguments, payload)
        if isinstance(result, catalog.Failure):
            return ApiFailure.UNAVAILABLE
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
            return ApiDocument(value)
        except (ValueError, UnicodeDecodeError):
            return ApiFailure.INVALID

    def release(self, prepared: PreparedPublication) -> catalog.CommitResult | catalog.Failure:
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
        route = f"releases/tags/{prepared.build.releaseTag}"
        current = self.api("GET", route)
        if current is ApiFailure.ABSENT:
            build = prepared.build
            result = self.run(
                [
                    "gh",
                    "release",
                    "create",
                    build.releaseTag,
                    *(str(asset.source) for asset in prepared.assets),
                    "--repo",
                    self.repository,
                    "--target",
                    build.buildRevision,
                    "--prerelease",
                    "--latest=false",
                    "--title",
                    f"Kast developer {build.version}",
                    "--notes",
                    f"Tested developer build from {build.buildRevision}, "
                    f"promoted after main {build.mainRevision}. "
                    "Candidate and promotion records, SBOM and checksums are included.",
                ],
                None,
            )
            if isinstance(result, catalog.Failure):
                return result
            current = self.api("GET", route)
        if not isinstance(current, ApiDocument):
            return catalog.Failure.REMOTE_UNAVAILABLE
        return admit_release(current.value, prepared)

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
        content = document.value.get("content")
        if (
            document.value.get("type") != "file"
            or document.value.get("encoding") != "base64"
            or not isinstance(content, str)
        ):
            return catalog.Failure.CATALOG_INVALID
        try:
            raw = base64.b64decode(content.replace("\n", ""), validate=True).decode("utf-8")
        except (ValueError, UnicodeDecodeError):
            return catalog.Failure.CATALOG_INVALID
        admitted = catalog.decode(raw)
        if isinstance(admitted, catalog.Failure):
            return admitted
        return catalog.Snapshot(head, tree_sha, admitted, absent)

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

    def snapshot_dual(self) -> dual_catalog.Snapshot | catalog.Failure:
        snapshot = self.snapshot()
        if isinstance(snapshot, catalog.Failure):
            return snapshot
        result = (
            self.api("GET", f"contents/builds-v2.json?ref={snapshot.head}")
            if not snapshot.absent
            else ApiFailure.ABSENT
        )
        if result is ApiFailure.ABSENT:
            current = dual_catalog.Catalog(2, ())
        elif isinstance(result, ApiDocument) and catalog.mapping(result.value):
            content = result.value.get("content")
            if (
                result.value.get("type") != "file"
                or result.value.get("encoding") != "base64"
                or not isinstance(content, str)
            ):
                return catalog.Failure.CATALOG_INVALID
            try:
                raw = base64.b64decode(content.replace("\n", ""), validate=True).decode("utf-8")
            except (ValueError, UnicodeDecodeError):
                return catalog.Failure.CATALOG_INVALID
            admitted = dual_catalog.decode(raw)
            if isinstance(admitted, catalog.Failure):
                return admitted
            current = admitted
        else:
            return catalog.Failure.REMOTE_UNAVAILABLE
        return dual_catalog.Snapshot(
            snapshot.head,
            snapshot.tree,
            dual_catalog.CatalogPair(snapshot.catalog, current),
            snapshot.absent,
        )

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
        return self.commit_files(
            snapshot,
            (
                GitTreeFile("builds.json", catalog.encode(document)),
                GitTreeFile("latest.txt", pointer),
            ),
        )

    def commit_dual(
        self,
        snapshot: dual_catalog.Snapshot,
        document: dual_catalog.CatalogPair,
        pointer: str,
    ) -> catalog.CommitResult | catalog.Failure:
        base = catalog.Snapshot(
            snapshot.head, snapshot.tree, snapshot.catalogs.legacy, snapshot.absent
        )
        return self.commit_files(
            base,
            (
                GitTreeFile("builds.json", catalog.encode(document.legacy)),
                GitTreeFile("builds-v2.json", dual_catalog.encode(document.current)),
                GitTreeFile("latest.txt", pointer),
            ),
        )

    def commit_files(
        self,
        snapshot: catalog.Snapshot,
        files: tuple[GitTreeFile, ...],
    ) -> catalog.CommitResult | catalog.Failure:
        tree = self.api(
            "POST",
            "git/trees",
            GitTreeRequest(
                snapshot.tree,
                files,
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


def response_sha(result: ApiDocument | ApiFailure) -> str | catalog.Failure:
    if not isinstance(result, ApiDocument) or not catalog.mapping(result.value):
        return catalog.Failure.REMOTE_UNAVAILABLE
    sha = result.value.get("sha")
    return (
        sha
        if isinstance(sha, str) and catalog.SHA.fullmatch(sha)
        else catalog.Failure.REMOTE_INVALID
    )


def admit_release(
    value: object, prepared: PreparedPublication
) -> catalog.CommitResult | catalog.Failure:
    if not catalog.mapping(value) or value.get("target_commitish") != prepared.build.buildRevision:
        return catalog.Failure.ASSET_MISMATCH
    if (
        value.get("tag_name") != prepared.build.releaseTag
        or value.get("draft") is not False
        or value.get("prerelease") is not True
    ):
        return catalog.Failure.ASSET_MISMATCH
    assets = value.get("assets")
    if not catalog.sequence(assets):
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
    return catalog.CommitResult.COMMITTED if actual == expected else catalog.Failure.ASSET_MISMATCH


def publish(
    prepared: PreparedPublication, remote: RemotePublisher
) -> catalog.CommitResult | Rejected:
    released = remote.release(prepared)
    if isinstance(released, catalog.Failure):
        return Rejected(Stage.IMMUTABLE_RELEASE, released)
    if prepared.paired is None:
        result = catalog.publish(prepared.build, remote.snapshot, remote.history, remote.commit)
    else:
        result = dual_catalog.publish(
            dual_catalog.PairedBuild(prepared.build, prepared.paired),
            remote.snapshot_dual,
            remote.history,
            remote.commit_dual,
        )
    return Rejected(Stage.CATALOG, result) if isinstance(result, catalog.Failure) else result


def main(arguments: list[str]) -> int:
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
    prepared = prepare_dual(
        repository,
        args.commit,
        args.version,
        args.assets_directory.resolve(),
        args.promotion_record.resolve(),
    )
    if isinstance(prepared, Rejected):
        print(json.dumps(asdict(prepared)), file=sys.stderr)
        return 1
    result = publish(prepared, RemotePublisher(repository, prepared.build))
    if isinstance(result, Rejected):
        print(json.dumps(asdict(result)), file=sys.stderr)
        return 1
    print(f"https://raw.githubusercontent.com/{repository}/{BRANCH}/builds.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
