"""Publisher effects preserve original qualification and guard both discovery files together."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from collections.abc import Sequence
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from unittest.mock import patch

from validation_outcome import Outcome, ProductValidated, SourceValidated, ValidationRecord
from validation_scope import Scope

import build_candidate as candidate
import developer_catalog as catalog
import publish_developer as publisher
import qualified_runtime_assets as runtime_assets
from test_developer_catalog import entry

REPOSITORY = "amichne/kast"
BUILD = "a" * 40
MAIN = "b" * 40
TREE = "c" * 40
VERSION = "0.0.123"
RUNTIME_TARGETS = ("linux-x86_64", "macos-aarch64")


@dataclass(frozen=True)
class RuntimeAssemblyReceipt:
    sourceRevision: str
    bundleSha256: str
    runtimeSha256: str
    controlSha256: str
    composition: catalog.product.NativeCompositionEvidence | None = None
    schemaVersion: int = 2
    qualificationStage: str = "COMPONENT_ASSEMBLY"
    qualification: str = "COMPLETE"


def runtime_fixture(
    root: Path, target: str, fault: str = "", *, version: str, revision: str
) -> None:
    artifact = root / f"kast-release-runtime-v{version}-{target}-{revision}"
    release = artifact / "composed-release"
    evidence = artifact / "composed-evidence"
    release.mkdir(parents=True)
    evidence.mkdir()
    name = f"kast-runtime-v{version}-{target}.zip"
    archive = release / name
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr(
            "manifest.json",
            json.dumps(
                {
                    "kind": "KAST_RUNTIME_V1",
                    "schemaVersion": 1,
                    "version": "0.51.2" if fault == "version" else version,
                    "platform": "linux-aarch64" if fault == "platform" else target,
                }
            ),
        )
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    _ = (release / f"{name}.sha256").write_text(
        f"{'0' * 64 if fault == 'checksum' else digest}  {name}\n"
    )
    _ = (evidence / "qualification.json").write_text(
        json.dumps(
            {
                "sourceRevision": "0" * 40 if fault == "source" else revision,
                "qualification": "NOT_RUN" if fault == "incomplete" else "COMPLETE",
                "containers": (
                    None
                    if fault == "containers"
                    else {
                        "runtimeSha256": "0" * 64 if fault == "digest" else digest,
                    }
                ),
            }
        )
    )
    if fault == "bytes":
        with archive.open("ab") as stream:
            _ = stream.write(b"changed after qualification")


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
    id: int = 731
    upload_url: str = (
        "https://uploads.github.com/repos/amichne/kast/releases/731/assets{?name,label}"
    )
    body: str = ""


@dataclass(frozen=True)
class DiscoveryRelease:
    id: int
    tag_name: str
    draft: bool


@dataclass(frozen=True)
class Permissions:
    push: bool = True


@dataclass(frozen=True)
class RepositoryAccess:
    full_name: str = REPOSITORY
    permissions: Permissions = Permissions()


@dataclass(frozen=True)
class Sha:
    sha: str


@dataclass(frozen=True)
class Ref:
    object: Sha


@dataclass(frozen=True)
class TagTarget:
    sha: str = BUILD
    type: str = "commit"


@dataclass(frozen=True)
class TagReference:
    object: TagTarget = TagTarget()


@dataclass(frozen=True)
class AnnotatedTag:
    sha: str
    object: TagTarget


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


type Response = (
    Release
    | RemoteAsset
    | Sha
    | Ref
    | Commit
    | Contents
    | Comparison
    | RepositoryAccess
    | TagReference
    | AnnotatedTag
    | tuple[Release | RemoteAsset | DiscoveryRelease, ...]
)


def response(document: Response, status: int = 200) -> publisher.ProcessResult:
    return publisher.ProcessResult(
        0 if status < 400 else 1,
        f"HTTP/2.0 {status}\r\nContent-Type: application/json\r\n\r\n".encode()
        + json.dumps(
            tuple(asdict(item) for item in document)
            if isinstance(document, tuple)
            else asdict(document)
        ).encode(),
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
        route = (
            expected.route
            if expected.route.startswith("https://")
            else f"repos/{REPOSITORY}/{expected.route}".rstrip("/")
        )
        if arguments[5] != route:
            raise AssertionError(f"unexpected route: {arguments}")
        if expected.route.startswith("releases?"):
            if tuple(arguments[6:]) != ("--jq", "[.[] | {id,tag_name,draft}]"):
                raise AssertionError(f"discovery projection missing: {arguments}")
        elif "--jq" in arguments:
            raise AssertionError(f"unexpected response projection: {arguments}")
        value: object = json.loads(payload) if payload is not None else None
        self.requests.append((arguments, value))
        return expected.result

    def consumed(self) -> None:
        if self.steps:
            raise AssertionError(f"unconsumed remote effects: {self.steps}")


def discovery(*releases: Release) -> tuple[Step, ...]:
    return (
        Step("GET", "", response(RepositoryAccess())),
        Step("GET", "releases?per_page=100&page=1", discovery_page(*releases)),
        *((Step("GET", "releases?per_page=100&page=2", response(())),) if releases else ()),
    )


def discovery_page(*releases: Release) -> publisher.ProcessResult:
    return response(
        tuple(DiscoveryRelease(item.id, item.tag_name, item.draft) for item in releases)
    )


def inspection(release: Release) -> tuple[Step, ...]:
    route = f"releases/{release.id}"
    return (
        Step("GET", route, response(release)),
        Step("GET", f"{route}/assets?per_page=100&page=1", response(release.assets)),
        *(
            (Step("GET", f"{route}/assets?per_page=100&page=2", response(())),)
            if release.assets
            else ()
        ),
    )


def tag_target(tag: str, *, published: bool = False) -> Step:
    return Step(
        "GET",
        f"git/ref/tags/{tag}",
        response(TagReference())
        if published
        else publisher.ProcessResult(1, b"HTTP/2.0 404\n\n{}"),
    )


def upload(asset: publisher.PublicationAsset, *, interrupted: bool = False) -> Step:
    return Step(
        "POST",
        f"https://uploads.github.com/repos/{REPOSITORY}/releases/731/assets?name="
        + asset.source.name,
        publisher.ProcessResult(1, b"")
        if interrupted
        else response(RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes), 201),
    )


class CandidateFixture(unittest.TestCase):
    def test_runtime_asset_admission_preserves_legacy_and_versioned_receipts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = "linux-x86_64"
            runtime_fixture(root, target, version=VERSION, revision=BUILD)
            artifact = root / f"kast-release-runtime-v{VERSION}-{target}-{BUILD}"
            name = f"kast-runtime-v{VERSION}-{target}.zip"
            archive, checksum = runtime_assets.admit_runtime(artifact, name, VERSION, target, BUILD)
            self.assertEqual(name, archive.name)
            self.assertEqual(name + ".sha256", checksum.name)
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            receipt = RuntimeAssemblyReceipt(BUILD, "a" * 64, digest, "b" * 64)
            path = artifact / "composed-evidence/qualification.json"
            _ = path.write_text(json.dumps(asdict(receipt)))
            self.assertEqual(
                (archive, checksum),
                runtime_assets.admit_runtime(artifact, name, VERSION, target, BUILD),
            )
            split = replace(
                receipt,
                runtimeSha256="e" * 64,
                composition=catalog.product.NativeCompositionEvidence(
                    "f" * 64, "e" * 64, "a" * 64, digest
                ),
            )
            _ = path.write_text(json.dumps(asdict(split)))
            self.assertEqual(
                (archive, checksum),
                runtime_assets.admit_runtime(artifact, name, VERSION, target, BUILD),
            )
            for invalid in (
                replace(receipt, schemaVersion=3),
                replace(receipt, qualificationStage="COMPONENT_NATIVE"),
                replace(receipt, runtimeSha256="c" * 64),
                replace(split, runtimeSha256="c" * 64),
            ):
                _ = path.write_text(json.dumps(asdict(invalid)))
                with self.subTest(receipt=invalid), self.assertRaises(ValueError):
                    _ = runtime_assets.admit_runtime(artifact, name, VERSION, target, BUILD)

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.bundle = Path(self.temporary.name) / "candidate"
        self.payload = self.bundle / "payload"
        self.payload.mkdir(parents=True)
        archives = (
            f"kast-control-v{VERSION}-macos-aarch64.tar.gz",
            f"kast-ide-hosted-v{VERSION}-idea-262.zip",
            f"kast-ide-hosted-v{VERSION}-idea-263.zip",
            f"kast-plugin-v{VERSION}.zip",
            f"kast-marketplace-v{VERSION}.zip",
        )
        names = archives + (
            f"kast-control-release-v{VERSION}.json",
            f"kast-host-release-v{VERSION}.json",
            f"kast-host-release-v{VERSION}-idea-263.json",
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
        assert isinstance(prepared.build, catalog.Build)
        self.assertEqual(BUILD, prepared.build.buildRevision)
        self.assertEqual(MAIN, prepared.build.mainRevision)
        self.assertEqual(publisher.digest(self.record), prepared.build.candidate.sha256)
        self.assertEqual(publisher.digest(self.promotion), prepared.build.promotion.sha256)

    def test_created_draft_survives_published_tag_404(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        created = False
        uploaded = False
        published = False

        def run(arguments: Sequence[str], payload: bytes | None) -> publisher.ProcessResult:
            nonlocal created, uploaded, published
            if tuple(arguments[:3]) == ("gh", "release", "create"):
                created = True
                return response(Release(prepared.build.releaseTag, BUILD, (), draft=True), 201)
            method, route = arguments[4], arguments[5]
            if "releases/tags/" in route:
                return publisher.ProcessResult(1, b"HTTP/2.0 404\n\n{}")
            if method == "GET" and route == (
                f"repos/{REPOSITORY}/git/ref/tags/{prepared.build.releaseTag}"
            ):
                return (
                    response(TagReference())
                    if published
                    else publisher.ProcessResult(1, b"HTTP/2.0 404\n\n{}")
                )
            if method == "GET" and route.rstrip("/") == f"repos/{REPOSITORY}":
                return response(RepositoryAccess())
            if method == "GET" and route == f"repos/{REPOSITORY}/releases?per_page=100&page=1":
                return response(())
            if method == "POST" and route == f"repos/{REPOSITORY}/releases":
                self.assertFalse(created)
                created = True
                return response(Release(prepared.build.releaseTag, BUILD, (), draft=True), 201)
            if method == "GET" and route == f"repos/{REPOSITORY}/releases/731":
                return response(
                    Release(
                        prepared.build.releaseTag,
                        BUILD,
                        assets if uploaded else (),
                        draft=not published,
                    )
                )
            if method == "GET" and "/releases/731/assets?" in route:
                return response(assets if uploaded and route.endswith("page=1") else ())
            if method == "POST" and route.startswith(
                f"https://uploads.github.com/repos/{REPOSITORY}/releases/731/assets?name="
            ):
                uploaded = True
                return response(assets[0], 201)
            if method == "PATCH" and route == f"repos/{REPOSITORY}/releases/731":
                published = True
                return response(Release(prepared.build.releaseTag, BUILD, assets))
            raise AssertionError(f"unexpected effect: {arguments}")

        result = publisher.RemotePublisher(REPOSITORY, prepared.build, run).release(prepared)
        self.assertNotIsInstance(result, catalog.Failure)
        self.assertTrue(published)

    def test_discovery_projection_precedes_output_guard_without_losing_pages(self) -> None:
        prepared = self.prepare()
        upstream = tuple(
            Release(f"other-{number}", BUILD, (), id=number, body="x" * 4096)
            for number in range(1, 81)
        )
        projected = tuple(DiscoveryRelease(item.id, item.tag_name, item.draft) for item in upstream)
        full_response = response(upstream).output
        projected_response = response(projected).output.replace(
            b"\r\n\r\n",
            b'\r\nLink: <https://api.github.com/repos/amichne/kast/releases?page=2>; rel="next"'
            b"\r\n\r\n",
        )
        self.assertGreater(len(full_response), publisher.MAXIMUM_API_BYTES)
        self.assertLess(len(projected_response), publisher.MAXIMUM_API_BYTES)
        self.assertEqual(80, len(projected))
        requests: list[tuple[str, ...]] = []

        def execute(
            arguments: Sequence[str],
            *,
            input: bytes | None,
            capture_output: bool,
            timeout: int,
            check: bool,
        ) -> subprocess.CompletedProcess[bytes]:
            self.assertEqual((None, True, 180, False), (input, capture_output, timeout, check))
            requests.append(tuple(arguments))
            route = arguments[5]
            if route == f"repos/{REPOSITORY}":
                data = response(RepositoryAccess()).output
            elif route == f"repos/{REPOSITORY}/releases?per_page=100&page=1":
                data = (
                    projected_response
                    if tuple(arguments[6:])
                    == (
                        "--jq",
                        "[.[] | {id,tag_name,draft}]",
                    )
                    else full_response
                )
            elif route == f"repos/{REPOSITORY}/releases?per_page=100&page=2":
                self.assertEqual(("--jq", "[.[] | {id,tag_name,draft}]"), tuple(arguments[6:]))
                data = response((DiscoveryRelease(919, prepared.build.releaseTag, True),)).output
            elif route == f"repos/{REPOSITORY}/releases?per_page=100&page=3":
                self.assertEqual(("--jq", "[.[] | {id,tag_name,draft}]"), tuple(arguments[6:]))
                data = response(()).output
            else:
                raise AssertionError(f"unexpected request: {arguments}")
            return subprocess.CompletedProcess(arguments, 0, data, b"")

        with patch.object(publisher.subprocess, "run", side_effect=execute):
            # The unprojected negative control still trips the real, unchanged guard.
            self.assertEqual(
                catalog.Failure.REMOTE_INVALID,
                publisher.invoke(
                    [
                        "gh",
                        "api",
                        "--include",
                        "--method",
                        "GET",
                        f"repos/{REPOSITORY}/releases?per_page=100&page=1",
                    ],
                    None,
                ),
            )
            self.assertEqual(
                publisher.ReleaseId(919),
                publisher.RemotePublisher(REPOSITORY, prepared.build).discover(
                    prepared.build.releaseTag
                ),
            )
        self.assertEqual(5, len(requests))

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
                script = Script(*discovery(document), *inspection(document))
                remote = publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
                self.assertEqual(
                    publisher.Rejected(
                        publisher.Stage.IMMUTABLE_RELEASE, catalog.Failure.ASSET_MISMATCH
                    ),
                    publisher.publish(prepared, remote),
                )
                script.consumed()

    def test_interrupted_draft_upload_resumes_only_missing_bytes_before_publication(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        partial = Release(prepared.build.releaseTag, BUILD, assets[:-1], draft=True)
        complete = replace(partial, assets=assets)
        script = Script(
            *discovery(partial),
            *inspection(partial),
            tag_target(partial.tag_name),
            upload(prepared.assets[-1], interrupted=True),
            *inspection(complete),
            Step("PATCH", "releases/731", response(replace(complete, draft=False))),
            *inspection(replace(complete, draft=False)),
            tag_target(partial.tag_name, published=True),
        )
        before = tuple(publisher.digest(asset.source) for asset in prepared.assets)
        remote = publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
        result = remote.release(prepared)
        self.assertEqual(
            publisher.PublishedRelease(
                publisher.ReleaseId(731), prepared, publisher.ReleaseEffect.RECONCILED
            ),
            result,
        )
        writes = [(args, value) for args, value in script.requests if args[4] != "GET"]
        self.assertEqual(2, len(writes))
        self.assertEqual(str(prepared.assets[-1].source), writes[0][0][-1])
        self.assertEqual(
            ("--header", "Content-Type: application/octet-stream", "--input"),
            tuple(writes[0][0][-4:-1]),
        )
        self.assertIsNone(writes[0][1])
        self.assertEqual({"draft": False, "make_latest": "false"}, writes[1][1])
        self.assertEqual(
            publisher.ReleaseEvent(
                publisher.ReleaseOperation.UPLOAD,
                publisher.ApiFailure.INVALID,
                publisher.ReleaseId(731),
            ),
            remote.evidence[0],
        )
        self.assertEqual(before, tuple(publisher.digest(asset.source) for asset in prepared.assets))
        script.consumed()

    def test_new_release_stays_draft_when_upload_is_incomplete_and_retry_finishes(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        empty = Release(prepared.build.releaseTag, BUILD, (), draft=True)
        partial = replace(empty, assets=assets[:1])
        complete = replace(empty, assets=assets)
        published = replace(complete, draft=False)
        script = Script(
            *discovery(),
            tag_target(empty.tag_name),
            Step("POST", "releases", response(empty, 201)),
            *inspection(empty),
            tag_target(empty.tag_name),
            upload(prepared.assets[0]),
            upload(prepared.assets[1], interrupted=True),
            *inspection(partial),
            *discovery(partial),
            *inspection(partial),
            tag_target(empty.tag_name),
            *(upload(asset) for asset in prepared.assets[1:]),
            *inspection(complete),
            Step("PATCH", "releases/731", response(published)),
            *inspection(published),
            tag_target(empty.tag_name, published=True),
            *discovery(published),
            *inspection(published),
            tag_target(empty.tag_name, published=True),
        )
        remote = publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
        self.assertEqual(catalog.Failure.REMOTE_UNAVAILABLE, remote.release(prepared))
        result = remote.release(prepared)
        self.assertEqual(
            publisher.PublishedRelease(
                publisher.ReleaseId(731), prepared, publisher.ReleaseEffect.RECONCILED
            ),
            result,
        )
        writes = [(args, value) for args, value in script.requests if args[4] != "GET"]
        self.assertEqual(1, sum(args[5].endswith("/releases") for args, _ in writes))
        self.assertEqual(
            [str(asset.source) for asset in prepared.assets[1:]],
            [args[-1] for args, _ in writes[3:-1]],
        )
        creation = writes[0][1]
        self.assertEqual(
            {
                "tag_name": f"developer-v{VERSION}",
                "target_commitish": BUILD,
                "name": f"Kast developer {VERSION}",
                "body": f"Retained build {VERSION} from {BUILD}. "
                "Publication does not rebuild or change archive bytes.",
                "prerelease": True,
                "draft": True,
                "make_latest": "false",
            },
            creation,
        )
        # A retry after publication performs no write at all.
        self.assertEqual(
            publisher.PublishedRelease(
                publisher.ReleaseId(731), prepared, publisher.ReleaseEffect.UNCHANGED
            ),
            remote.release(prepared),
        )
        self.assertEqual(len(writes), sum(args[4] != "GET" for args, _ in script.requests))
        script.consumed()

    def test_conflicting_draft_never_uploads_or_publishes(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        draft = Release(prepared.build.releaseTag, BUILD, assets, draft=True)
        for rejected in (
            replace(draft, target_commitish=MAIN),
            replace(draft, assets=(replace(assets[0], digest="sha256:" + "f" * 64),)),
            replace(draft, assets=(replace(assets[0], name="foreign.zip"),)),
            replace(draft, assets=(replace(assets[0], state="new"),)),
        ):
            with self.subTest(rejected=rejected):
                script = Script(*discovery(rejected), *inspection(rejected))
                self.assertEqual(
                    catalog.Failure.ASSET_MISMATCH,
                    publisher.RemotePublisher(REPOSITORY, prepared.build, script.run).release(
                        prepared
                    ),
                )
                script.consumed()

    def test_discovery_rejects_unproven_visibility_pagination_and_identity_before_writes(
        self,
    ) -> None:
        prepared = self.prepare()
        draft = Release(prepared.build.releaseTag, BUILD, (), draft=True)
        unavailable = publisher.ProcessResult(1, b"HTTP/2.0 503\n\n{}")
        cases = (
            (
                (Step("GET", "", response(RepositoryAccess(permissions=Permissions(False)))),),
                catalog.Failure.DRAFT_VISIBILITY_UNPROVEN,
            ),
            (
                (
                    Step("GET", "", response(RepositoryAccess())),
                    Step("GET", "releases?per_page=100&page=1", discovery_page(draft)),
                    Step("GET", "releases?per_page=100&page=2", unavailable),
                ),
                catalog.Failure.RELEASE_DISCOVERY_INCOMPLETE,
            ),
            (
                discovery(draft, replace(draft, id=732)),
                catalog.Failure.RELEASE_IDENTITY_CONFLICT,
            ),
            (
                discovery(draft, replace(draft, tag_name="other")),
                catalog.Failure.RELEASE_IDENTITY_CONFLICT,
            ),
            (discovery(replace(draft, id=0)), catalog.Failure.REMOTE_INVALID),
            (
                (
                    Step("GET", "", response(RepositoryAccess())),
                    Step(
                        "GET",
                        "releases?per_page=100&page=1",
                        publisher.ProcessResult(
                            0,
                            b'HTTP/2.0 200\n\n[{"tag_name":"other","draft":false}]',
                        ),
                    ),
                    Step("GET", "releases?per_page=100&page=2", response(())),
                ),
                catalog.Failure.REMOTE_INVALID,
            ),
            (
                (
                    Step("GET", "", response(RepositoryAccess())),
                    Step(
                        "GET",
                        "releases?per_page=100&page=1",
                        publisher.ProcessResult(
                            0,
                            b"HTTP/2.0 200\nLink: <https://api.github.com/repos/amichne/kast/"
                            b'releases?page=2>; rel="next"\n\n[]',
                        ),
                    ),
                ),
                catalog.Failure.RELEASE_DISCOVERY_INCOMPLETE,
            ),
            (
                (
                    Step("GET", "", response(RepositoryAccess())),
                    Step(
                        "GET",
                        "releases?per_page=100&page=1",
                        publisher.ProcessResult(
                            0,
                            b'HTTP/2.0 200\n\n[{"id":true,"tag_name":"other","draft":false}]',
                        ),
                    ),
                    Step("GET", "releases?per_page=100&page=2", response(())),
                ),
                catalog.Failure.REMOTE_INVALID,
            ),
        )
        for steps, failure in cases:
            with self.subTest(failure=failure, steps=steps):
                script = Script(*steps)
                self.assertEqual(
                    publisher.Rejected(publisher.Stage.IMMUTABLE_RELEASE, failure),
                    publisher.publish(
                        prepared, publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
                    ),
                )
                self.assertTrue(all(args[4] == "GET" for args, _ in script.requests))
                script.consumed()

    def test_lost_creation_response_discovers_draft_on_later_page_without_recreation(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        empty = Release(prepared.build.releaseTag, BUILD, (), draft=True)
        complete = replace(empty, assets=assets)
        published = replace(complete, draft=False)
        script = Script(
            *discovery(),
            tag_target(empty.tag_name),
            Step("POST", "releases", publisher.ProcessResult(1, b"HTTP/2.0 503\n\n{}")),
            Step("GET", "", response(RepositoryAccess())),
            Step(
                "GET",
                "releases?per_page=100&page=1",
                discovery_page(replace(empty, tag_name="unrelated", id=730)),
            ),
            Step("GET", "releases?per_page=100&page=2", discovery_page(empty)),
            Step("GET", "releases?per_page=100&page=3", response(())),
            *inspection(empty),
            tag_target(empty.tag_name),
            *(upload(asset) for asset in prepared.assets),
            *inspection(complete),
            Step("PATCH", "releases/731", response(published)),
            *inspection(published),
            tag_target(empty.tag_name, published=True),
        )
        remote = publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
        self.assertEqual(
            publisher.PublishedRelease(
                publisher.ReleaseId(731), prepared, publisher.ReleaseEffect.RECONCILED
            ),
            remote.release(prepared),
        )
        self.assertEqual(
            1,
            sum(args[4] == "POST" and args[5].endswith("/releases") for args, _ in script.requests),
        )
        self.assertEqual(
            publisher.ReleaseEvent(
                publisher.ReleaseOperation.CREATE, publisher.ApiFailure.UNAVAILABLE
            ),
            remote.evidence[0],
        )
        self.assertEqual(
            {"operation": "CREATE", "outcome": "UNAVAILABLE", "identity": None},
            json.loads(json.dumps(asdict(remote.evidence[0]))),
        )
        script.consumed()

    def test_identity_mismatch_and_foreign_upload_endpoint_reject_before_upload(self) -> None:
        prepared = self.prepare()
        draft = Release(prepared.build.releaseTag, BUILD, (), draft=True)
        for document, failure in (
            (replace(draft, id=732), catalog.Failure.RELEASE_IDENTITY_CONFLICT),
            (
                replace(draft, upload_url="https://example.com/assets"),
                catalog.Failure.REMOTE_INVALID,
            ),
        ):
            with self.subTest(document=document):
                script = Script(*discovery(draft), Step("GET", "releases/731", response(document)))
                self.assertEqual(
                    publisher.Rejected(publisher.Stage.IMMUTABLE_RELEASE, failure),
                    publisher.publish(
                        prepared, publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
                    ),
                )
                script.consumed()

    def test_lost_creation_response_with_proven_absence_does_not_retry_creation(self) -> None:
        prepared = self.prepare()
        script = Script(
            *discovery(),
            tag_target(prepared.build.releaseTag),
            Step("POST", "releases", publisher.ProcessResult(1, b"HTTP/2.0 503\n\n{}")),
            *discovery(),
        )
        remote = publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
        result = publisher.publish(prepared, remote)
        self.assertEqual(
            publisher.Rejected(
                publisher.Stage.IMMUTABLE_RELEASE,
                catalog.Failure.REMOTE_UNAVAILABLE,
                (
                    publisher.ReleaseEvent(
                        publisher.ReleaseOperation.CREATE,
                        publisher.ApiFailure.UNAVAILABLE,
                    ),
                ),
            ),
            result,
        )
        script.consumed()

    def test_final_draft_or_changed_identity_never_qualifies_catalog(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        complete = Release(prepared.build.releaseTag, BUILD, assets, draft=True)
        published = replace(complete, draft=False)
        for final, failure in (
            (complete, catalog.Failure.REMOTE_UNAVAILABLE),
            (replace(published, id=732), catalog.Failure.RELEASE_IDENTITY_CONFLICT),
            (replace(published, assets=assets[:-1]), catalog.Failure.ASSET_MISMATCH),
        ):
            with self.subTest(final=final):
                final_steps = (
                    (Step("GET", "releases/731", response(final)),)
                    if final.id != 731
                    else inspection(final)
                )
                script = Script(
                    *discovery(complete),
                    *inspection(complete),
                    tag_target(complete.tag_name),
                    Step("PATCH", "releases/731", response(published)),
                    *final_steps,
                )
                self.assertEqual(
                    publisher.Rejected(publisher.Stage.IMMUTABLE_RELEASE, failure),
                    publisher.publish(
                        prepared, publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
                    ),
                )
                script.consumed()

    def test_existing_wrong_tag_target_prevents_creation(self) -> None:
        prepared = self.prepare()
        script = Script(
            *discovery(),
            Step(
                "GET",
                f"git/ref/tags/{prepared.build.releaseTag}",
                response(
                    TagReference(TagTarget(MAIN)),
                ),
            ),
        )
        self.assertEqual(
            catalog.Failure.ASSET_MISMATCH,
            publisher.RemotePublisher(REPOSITORY, prepared.build, script.run).release(prepared),
        )
        script.consumed()

    def test_unavailable_final_inspection_records_stage_and_preserves_catalog(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        complete = Release(prepared.build.releaseTag, BUILD, assets, draft=True)
        unavailable = publisher.ProcessResult(1, b"HTTP/2.0 503\n\n{}")
        script = Script(
            *discovery(complete),
            *inspection(complete),
            tag_target(complete.tag_name),
            Step("PATCH", "releases/731", unavailable),
            Step("GET", "releases/731", unavailable),
        )
        self.assertEqual(
            publisher.Rejected(
                publisher.Stage.IMMUTABLE_RELEASE,
                catalog.Failure.REMOTE_UNAVAILABLE,
                (
                    publisher.ReleaseEvent(
                        publisher.ReleaseOperation.PUBLISH,
                        publisher.ApiFailure.UNAVAILABLE,
                        publisher.ReleaseId(731),
                    ),
                    publisher.ReleaseEvent(
                        publisher.ReleaseOperation.INSPECT,
                        publisher.ApiFailure.UNAVAILABLE,
                        publisher.ReleaseId(731),
                    ),
                ),
            ),
            publisher.publish(
                prepared, publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
            ),
        )
        script.consumed()

    def test_discovery_capacity_exhaustion_never_establishes_absence(self) -> None:
        prepared = self.prepare()
        unrelated = Release("other", BUILD, (), draft=True)
        script = Script(
            Step("GET", "", response(RepositoryAccess())),
            Step("GET", "releases?per_page=100&page=1", discovery_page(unrelated)),
        )
        with patch.object(publisher, "MAXIMUM_DISCOVERY_PAGES", 1):
            self.assertEqual(
                catalog.Failure.RELEASE_DISCOVERY_INCOMPLETE,
                publisher.RemotePublisher(REPOSITORY, prepared.build, script.run).release(prepared),
            )
        script.consumed()

    def test_published_release_and_matching_catalog_and_pointer_perform_zero_writes(self) -> None:
        prepared = self.prepare()
        assets = tuple(
            RemoteAsset(asset.source.name, f"sha256:{asset.sha256}", asset.bytes)
            for asset in prepared.assets
        )
        published = Release(prepared.build.releaseTag, BUILD, assets)
        document = catalog.Catalog(1, (prepared.build,))
        pointer = catalog.latest_pointer(document)
        script = Script(
            *discovery(published),
            *inspection(published),
            Step(
                "GET",
                f"git/ref/tags/{published.tag_name}",
                response(
                    TagReference(TagTarget(TREE, "tag")),
                ),
            ),
            Step("GET", f"git/tags/{TREE}", response(AnnotatedTag(TREE, TagTarget(BUILD)))),
            Step("GET", "git/ref/heads/developer-latest", response(Ref(Sha("c" * 40)))),
            Step("GET", "git/commits/" + "c" * 40, response(Commit("c" * 40, Sha("d" * 40)))),
            Step(
                "GET",
                "contents/builds.json?ref=" + "c" * 40,
                response(
                    Contents(
                        base64.b64encode(catalog.encode(document).encode()).decode(),
                    )
                ),
            ),
            Step(
                "GET",
                "contents/latest.txt?ref=" + "c" * 40,
                response(
                    Contents(
                        base64.b64encode(pointer.encode()).decode(),
                    )
                ),
            ),
            Step("GET", "git/ref/heads/main", response(Ref(Sha(MAIN)))),
            Step(
                "GET",
                f"compare/{MAIN}...{MAIN}?per_page=1&page=2",
                response(Comparison("identical")),
            ),
        )
        self.assertEqual(
            catalog.CommitResult.UNCHANGED,
            publisher.publish(
                prepared, publisher.RemotePublisher(REPOSITORY, prepared.build, script.run)
            ),
        )
        self.assertTrue(all(args[4] == "GET" for args, _ in script.requests))
        script.consumed()

    def run_stable_publisher(
        self, runtime_fault: str = ""
    ) -> tuple[subprocess.CompletedProcess[str], Path]:
        owned = Path(self.temporary.name) / (runtime_fault or "qualified")
        repository = owned / "repository"
        scripts = repository / ".github/scripts/release"
        scripts.mkdir(parents=True)
        for name in ("publish-release.sh", "admit-source.sh", "qualified_runtime_assets.py"):
            shutil.copy2(Path(__file__).with_name(name), scripts / name)
        home, commands = owned / "home", owned / "bin"
        home.mkdir()
        commands.mkdir()
        uploaded = owned / "uploaded.txt"
        gh = commands / "gh"
        gh.write_text(
            '#!/bin/sh\ncase "$1 $2" in\n'
            '"release view") test -f "$KAST_TEST_UPLOAD_LOG" ;;\n'
            '"release create") printf "%s\\n" "$@" > "$KAST_TEST_UPLOAD_LOG" ;;\n'
            "*) exit 90 ;;\nesac\n"
        )
        gh.chmod(0o700)
        mise = commands / "mise"
        _ = mise.write_text(
            f"#!{sys.executable}\n"
            "import os, sys\n"
            "assert sys.argv[1:8] == ['exec', '--', 'uv', 'run', '--locked', '--script', "
            "'.github/scripts/release/qualified_runtime_assets.py']\n"
            "os.execv(sys.executable, [sys.executable, *sys.argv[7:]])\n"
        )
        mise.chmod(0o700)
        environment = {
            "PATH": str(commands) + os.pathsep + os.defpath,
            "HOME": str(home),
            "LC_ALL": "C",
            "GH_TOKEN": "owned-fixture-token",
            "KAST_TEST_UPLOAD_LOG": str(uploaded),
        }

        def git(*arguments: str) -> str:
            return subprocess.check_output(
                ("git", *arguments),
                cwd=repository,
                env=environment,
                text=True,
                stderr=subprocess.PIPE,
            ).strip()

        _ = git("init", "--initial-branch=main")
        _ = git("add", ".")
        _ = git(
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "-m",
            "owned publication source",
        )
        revision = git("rev-parse", "HEAD")
        remote = owned / "remote.git"
        _ = git("clone", "--bare", str(repository), str(remote))
        _ = git("remote", "add", "origin", str(remote))
        runtimes = owned / "qualified runtimes"
        for target in RUNTIME_TARGETS:
            if target == "macos-aarch64" and runtime_fault == "missing":
                continue
            runtime_fixture(
                runtimes,
                target,
                runtime_fault if target == "macos-aarch64" else "",
                version=VERSION,
                revision=revision,
            )
        runtime_fixture(runtimes, "linux-aarch64", version=VERSION, revision=revision)
        result = subprocess.run(
            (
                "bash",
                str(scripts / "publish-release.sh"),
                "--release",
                f"v{VERSION}",
                "--commit",
                revision,
                "--assets-directory",
                str(self.payload),
                "--runtimes-directory",
                str(runtimes),
            ),
            cwd=repository,
            env=environment,
            text=True,
            capture_output=True,
            timeout=30,
        )
        return result, uploaded

    def test_legacy_asset_inventory_cannot_publish_a_complete_product(self) -> None:
        result, uploaded = self.run_stable_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("complete immutable build record required", result.stderr)
        self.assertFalse(uploaded.exists())

    def test_unqualified_runtime_rejects_before_release_creation(self) -> None:
        for fault, reason in (
            ("missing", "missing regular file"),
            ("source", "qualification receipt lacks COMPLETE for the release source"),
            ("incomplete", "qualification receipt lacks COMPLETE for the release source"),
            ("containers", "qualification receipt lacks container evidence"),
            ("digest", "published checksum differs from qualification digest"),
            ("checksum", "published checksum differs from qualification digest"),
            ("bytes", "runtime ZIP differs from qualification digest"),
            ("version", "runtime manifest differs from release version or target"),
            ("platform", "runtime manifest differs from release version or target"),
        ):
            with self.subTest(fault=fault):
                result, uploaded = self.run_stable_publisher(fault)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f"macos-aarch64: {reason}", result.stderr)
                self.assertFalse(uploaded.exists())

    def test_missing_control_record_rejects_before_release_creation(self) -> None:
        name = f"kast-control-release-v{VERSION}.json"
        alias = Path(self.temporary.name) / "payload-alias"
        alias.symlink_to(self.payload, target_is_directory=True)
        self.payload = alias
        (self.payload / name).unlink()
        result, uploaded = self.run_stable_publisher()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"missing release asset: {self.payload.resolve() / name}", result.stderr)
        self.assertFalse(uploaded.exists())


class RemoteCatalogTest(unittest.TestCase):
    def test_discovery_projection_cannot_hide_asset_fields_or_apply_to_writes(self) -> None:
        script = Script()
        remote = publisher.RemotePublisher(REPOSITORY, entry(1), script.run)
        for method, route in (
            ("GET", "releases/731/assets?per_page=100&page=1"),
            ("POST", "releases?per_page=100&page=1"),
            ("GET", "releases/731"),
        ):
            with self.subTest(method=method, route=route):
                self.assertEqual(
                    publisher.ApiFailure.INVALID,
                    remote.api(
                        method, route, projection=publisher.ResponseProjection.RELEASE_DISCOVERY
                    ),
                )
        script.consumed()

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
        pointer = catalog.latest_pointer(document)
        script = Script(
            Step("GET", "git/ref/heads/developer-latest", response(Ref(Sha("a" * 40)))),
            Step("GET", "git/commits/" + "a" * 40, response(Commit("a" * 40, Sha("b" * 40)))),
            Step(
                "GET",
                "contents/builds.json?ref=" + "a" * 40,
                response(Contents(base64.b64encode(catalog.encode(document).encode()).decode())),
            ),
            Step(
                "GET",
                "contents/latest.txt?ref=" + "a" * 40,
                response(Contents(base64.b64encode(pointer.encode()).decode())),
            ),
        )
        remote = publisher.RemotePublisher(REPOSITORY, entry(3), script.run)
        self.assertEqual(
            catalog.Snapshot("a" * 40, "b" * 40, document, False, pointer), remote.snapshot()
        )
        script.consumed()

    def test_main_ancestry_rejects_divergence_and_orders_without_job_time(self) -> None:
        script = Script(
            Step("GET", "git/ref/heads/main", response(Ref(Sha("f" * 40)))),
            Step(
                "GET",
                f"compare/{entry(1).mainRevision}..." + "f" * 40 + "?per_page=1&page=2",
                response(Comparison("ahead")),
            ),
            Step(
                "GET",
                f"compare/{entry(2).mainRevision}..." + "f" * 40 + "?per_page=1&page=2",
                response(Comparison("ahead")),
            ),
            Step(
                "GET",
                f"compare/{entry(1).mainRevision}...{entry(2).mainRevision}?per_page=1&page=2",
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
                f"compare/{entry(1).mainRevision}..." + "f" * 40 + "?per_page=1&page=2",
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

    def test_comparison_uses_page_status_without_file_or_commit_evidence(self) -> None:
        older = entry(1).mainRevision
        newer = entry(2).mainRevision
        for status in ("ahead", "behind", "identical", "diverged", "unknown"):
            with self.subTest(status=status):
                script = Script(
                    Step(
                        "GET",
                        f"compare/{older}...{newer}?per_page=1&page=2",
                        response(Comparison(status)),
                    ),
                )
                expected = (
                    status
                    if status in ("ahead", "behind", "identical")
                    else catalog.Failure.MAIN_HISTORY_UNPROVEN
                )
                self.assertEqual(
                    expected,
                    publisher.RemotePublisher(REPOSITORY, entry(2), script.run).comparison(
                        older, newer
                    ),
                )
                script.consumed()


if __name__ == "__main__":
    unittest.main()
