"""Publisher effects preserve original qualification and guard both discovery files together."""

from __future__ import annotations

import base64
import json
import tempfile
import unittest
from collections.abc import Sequence
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from unittest.mock import patch

import build_candidate as candidate
import developer_catalog as catalog
import publish_developer as publisher
from test_developer_catalog import entry
from validation_outcome import Outcome, ProductValidated, SourceValidated, ValidationRecord
from validation_scope import Scope

REPOSITORY = "amichne/kast"
BUILD = "a" * 40
MAIN = "b" * 40
TREE = "c" * 40
VERSION = "0.0.123"


@dataclass(frozen=True)
class Repository:
    full_name: str = REPOSITORY


@dataclass(frozen=True)
class Run:
    id: int = 123
    run_number: int = 123
    run_attempt: int = 1
    path: str = candidate.WORKFLOW
    event: str = "pull_request"
    head_sha: str = BUILD
    head_branch: str = "feature"
    status: str = "completed"
    conclusion: str = "success"
    repository: Repository = Repository()


@dataclass(frozen=True)
class Property:
    name: str
    value: str


@dataclass(frozen=True)
class Metadata:
    properties: tuple[Property, ...]


@dataclass(frozen=True)
class Sbom:
    bomFormat: str
    metadata: Metadata


@dataclass(frozen=True)
class RemoteAsset:
    name: str
    digest: str
    size: int
    state: str = "uploaded"


@dataclass(frozen=True)
class Release:
    tag_name: str
    target_commitish: str
    assets: tuple[RemoteAsset, ...]
    draft: bool = False
    prerelease: bool = True


@dataclass(frozen=True)
class Sha:
    sha: str


@dataclass(frozen=True)
class Ref:
    object: Sha


@dataclass(frozen=True)
class Commit:
    sha: str
    tree: Sha


@dataclass(frozen=True)
class Contents:
    content: str
    type: str = "file"
    encoding: str = "base64"


@dataclass(frozen=True)
class Comparison:
    status: str


type Response = Release | Sha | Ref | Commit | Contents | Comparison


def response(document: Response, status: int = 200) -> publisher.ProcessResult:
    return publisher.ProcessResult(
        0 if status < 400 else 1,
        f"HTTP/2.0 {status}\r\nContent-Type: application/json\r\n\r\n".encode()
        + json.dumps(asdict(document)).encode(),
    )


@dataclass(frozen=True)
class Step:
    method: str
    route: str
    result: publisher.ProcessResult


class Script:
    def __init__(self, *steps: Step):
        self.steps = list(steps)
        self.requests: list[tuple[Sequence[str], object]] = []

    def run(self, arguments: Sequence[str], payload: bytes | None) -> publisher.ProcessResult:
        if not self.steps:
            raise AssertionError(f"unexpected remote effect: {arguments}")
        expected = self.steps.pop(0)
        if tuple(arguments[:5]) != ("gh", "api", "--include", "--method", expected.method):
            raise AssertionError(f"unexpected method: {arguments}")
        if arguments[5] != f"repos/{REPOSITORY}/{expected.route}":
            raise AssertionError(f"unexpected route: {arguments}")
        value: object = json.loads(payload) if payload is not None else None
        self.requests.append((arguments, value))
        return expected.result

    def consumed(self) -> None:
        if self.steps:
            raise AssertionError(f"unconsumed remote effects: {self.steps}")


class CandidateFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.bundle = Path(self.temporary.name) / "candidate"
        self.payload = self.bundle / "payload"
        self.payload.mkdir(parents=True)
        archives = (
            f"kast-control-v{VERSION}-macos-aarch64.tar.gz",
            f"kast-ide-hosted-v{VERSION}-idea-262.zip",
            f"kast-plugin-v{VERSION}.zip",
            f"kast-marketplace-v{VERSION}.zip",
        )
        names = archives + (
            f"kast-control-release-v{VERSION}.json",
            f"kast-host-release-v{VERSION}.json",
            "host-installation.py",
            f"kast-hosted-catalog-v{VERSION}.json",
            f"kast-module-knowledge-v{VERSION}.json",
        )
        for name in names:
            (self.payload / name).write_bytes(name.encode())
        sbom = Sbom(
            "CycloneDX",
            Metadata(
                (
                    Property("kast:source-revision", BUILD),
                    *(
                        Property(
                            f"kast:archive:{name}",
                            "sha256:" + publisher.digest(self.payload / name),
                        )
                        for name in archives
                    ),
                )
            ),
        )
        (self.payload / f"kast-sbom-v{VERSION}.cdx.json").write_text(json.dumps(asdict(sbom)))
        for path in tuple(self.payload.iterdir()):
            path.with_name(path.name + ".sha256").write_text(
                f"{publisher.digest(path)}  {path.name}\n"
            )
        self.value = candidate.Candidate(
            1,
            REPOSITORY,
            BUILD,
            TREE,
            VERSION,
            123,
            123,
            1,
            "pull_request",
            "macos-aarch64",
            candidate.Toolchain("25.0.2", "Oracle Corporation", "d" * 64),
            tuple(
                candidate.Asset(path.name, publisher.digest(path), path.stat().st_size)
                for path in sorted(self.payload.iterdir())
            ),
        )
        self.record = self.bundle / "candidate.json"
        self.record.write_text(json.dumps(asdict(self.value)))
        self.promotion = Path(self.temporary.name) / "promotion.json"
        self.promotion.write_text(
            json.dumps(
                asdict(
                    candidate.Promotion(
                        1,
                        REPOSITORY,
                        BUILD,
                        MAIN,
                        TREE,
                        VERSION,
                        123,
                        1,
                        456,
                        1,
                        publisher.digest(self.record),
                    )
                )
            )
        )
        self.main_run = replace(Run(), id=456, event="push", head_sha=MAIN, head_branch="main")
        self.validation = ValidationRecord(
            1,
            Outcome.PRODUCT_VALIDATED,
            Scope.FULL,
            REPOSITORY,
            MAIN,
            TREE,
            BUILD,
            456,
            123,
            1,
            "push",
        )

    def prepare(self) -> publisher.PreparedPublication:
        with (
            patch.object(candidate, "observe", side_effect=[asdict(self.main_run), asdict(Run())]),
            patch.object(
                candidate, "admit_validation", return_value=ProductValidated(self.validation)
            ),
            patch.object(candidate, "tree", return_value=TREE),
        ):
            prepared = publisher.prepare(REPOSITORY, BUILD, VERSION, self.payload, self.promotion)
        assert isinstance(prepared, publisher.PreparedPublication)
        return prepared

    def test_tree_equivalent_candidate_keeps_original_build_identity(self) -> None:
        prepared = self.prepare()
        self.assertEqual(BUILD, prepared.build.buildRevision)
        self.assertEqual(MAIN, prepared.build.mainRevision)
        self.assertEqual(publisher.digest(self.record), prepared.build.candidate.sha256)
        self.assertEqual(publisher.digest(self.promotion), prepared.build.promotion.sha256)

    def test_source_only_receipt_cannot_strengthen_after_setting_change(self) -> None:
        source = SourceValidated(
            replace(self.validation, scope=Scope.SOURCE_FULL, outcome=Outcome.SOURCE_VALIDATED)
        )
        with (
            patch.dict("os.environ", {"KAST_NATIVE_COMPILATION": "true"}),
            patch.object(candidate, "observe", return_value=asdict(self.main_run)) as observe,
            patch.object(candidate, "admit_validation", return_value=source),
            patch.object(candidate, "tree", return_value=TREE),
        ):
            rejected = publisher.prepare(REPOSITORY, BUILD, VERSION, self.payload, self.promotion)
        self.assertEqual(
            publisher.Rejected(publisher.Stage.QUALIFICATION, candidate.Failure.INVALID_RECORD),
            rejected,
        )
        self.assertEqual(1, observe.call_count)

    def test_changed_local_asset_cannot_publish_under_the_admitted_digest(self) -> None:
        prepared = self.prepare()
        prepared.assets[0].source.write_bytes(b"different cache bytes")
        script = Script()
        remote = publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
        self.assertEqual(catalog.Failure.ASSET_MISMATCH, remote.release(prepared))
        script.consumed()

    def test_partial_or_changed_release_never_commits_the_catalog(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        valid = Release(prepared.build.releaseTag, BUILD, assets)
        for document in (
            replace(valid, assets=assets[:-1]),
            replace(valid, assets=(replace(assets[0], digest="sha256:" + "e" * 64), *assets[1:])),
            replace(valid, assets=(replace(assets[0], state="new"), *assets[1:])),
            replace(valid, assets=(replace(assets[0], size=assets[0].size + 1), *assets[1:])),
            replace(valid, target_commitish=MAIN),
        ):
            with self.subTest(document=document):
                script = Script(
                    Step("GET", f"releases/tags/{prepared.build.releaseTag}", response(document))
                )
                remote = publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
                self.assertEqual(
                    publisher.Rejected(
                        publisher.Stage.IMMUTABLE_RELEASE, catalog.Failure.ASSET_MISMATCH
                    ),
                    publisher.publish(prepared, remote),
                )
                script.consumed()


class RemoteCatalogTest(unittest.TestCase):
    def test_catalog_and_legacy_pointer_share_one_commit_and_nonforced_ref_update(self) -> None:
        value = entry(3)
        document = catalog.Catalog(1, (value,))
        snapshot = catalog.Snapshot("a" * 40, "b" * 40, catalog.Catalog(1, ()), False)
        script = Script(
            Step("POST", "git/trees", response(Sha("c" * 40), 201)),
            Step("POST", "git/commits", response(Sha("d" * 40), 201)),
            Step("PATCH", "git/refs/heads/developer-latest", response(Ref(Sha("d" * 40)))),
        )
        remote = publisher.RemotePublisher(REPOSITORY, value, script.run)
        self.assertEqual(
            catalog.CommitResult.COMMITTED,
            remote.commit(snapshot, document, catalog.latest_pointer(document)),
        )
        self.assertEqual(
            {
                "base_tree": "b" * 40,
                "tree": [
                    {
                        "path": "builds.json",
                        "content": catalog.encode(document),
                        "mode": "100644",
                        "type": "blob",
                    },
                    {
                        "path": "latest.txt",
                        "content": "developer-v0.0.3 0.0.3 " + "a" * 40 + "\n",
                        "mode": "100644",
                        "type": "blob",
                    },
                ],
            },
            script.requests[0][1],
        )
        self.assertEqual({"sha": "d" * 40, "force": False}, script.requests[2][1])
        script.consumed()

    def test_snapshot_reads_catalog_at_the_captured_ref(self) -> None:
        document = catalog.Catalog(1, (entry(2),))
        script = Script(
            Step("GET", "git/ref/heads/developer-latest", response(Ref(Sha("a" * 40)))),
            Step("GET", "git/commits/" + "a" * 40, response(Commit("a" * 40, Sha("b" * 40)))),
            Step(
                "GET",
                "contents/builds.json?ref=" + "a" * 40,
                response(Contents(base64.b64encode(catalog.encode(document).encode()).decode())),
            ),
        )
        remote = publisher.RemotePublisher(REPOSITORY, entry(3), script.run)
        self.assertEqual(catalog.Snapshot("a" * 40, "b" * 40, document, False), remote.snapshot())
        script.consumed()

    def test_main_ancestry_rejects_divergence_and_orders_without_job_time(self) -> None:
        script = Script(
            Step("GET", "git/ref/heads/main", response(Ref(Sha("f" * 40)))),
            Step(
                "GET",
                f"compare/{entry(1).mainRevision}..." + "f" * 40,
                response(Comparison("ahead")),
            ),
            Step(
                "GET",
                f"compare/{entry(2).mainRevision}..." + "f" * 40,
                response(Comparison("ahead")),
            ),
            Step(
                "GET",
                f"compare/{entry(1).mainRevision}...{entry(2).mainRevision}",
                response(Comparison("ahead")),
            ),
        )
        remote = publisher.RemotePublisher(REPOSITORY, entry(2), script.run)
        self.assertEqual(
            (entry(2).mainRevision, entry(1).mainRevision),
            remote.history((entry(1).mainRevision, entry(2).mainRevision)),
        )
        script.consumed()
        invalid = Script(
            Step("GET", "git/ref/heads/main", response(Ref(Sha("f" * 40)))),
            Step(
                "GET",
                f"compare/{entry(1).mainRevision}..." + "f" * 40,
                response(Comparison("diverged")),
            ),
        )
        self.assertEqual(
            catalog.Failure.MAIN_HISTORY_UNPROVEN,
            publisher.RemotePublisher(REPOSITORY, entry(1), invalid.run).history(
                (entry(1).mainRevision,)
            ),
        )
        invalid.consumed()


if __name__ == "__main__":
    unittest.main()
