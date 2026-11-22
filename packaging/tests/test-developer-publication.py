#!/usr/bin/env python3
"""The real developer publisher must retain complete two-target evidence before discovery."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import shlex
import subprocess
import sys
import tempfile
import unittest
from dataclasses import asdict, dataclass, replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / ".github/scripts/release"))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / ".github/scripts/ci"))
import build_candidate as candidate
import developer_catalog as catalog
import publish_developer as publisher
from test_product_candidate import accepted_fixture, fixture

ROOT = Path(__file__).resolve().parents[2]
PUBLISHER = ROOT / ".github/scripts/release/publish_developer.py"
REPOSITORY = "amichne/kast"
VERSION = "0.0.123"
REVISION = "a" * 40
TAG = f"developer-v{VERSION}"
MERGED = REVISION
TREE = "b" * 40


@dataclass(frozen=True)
class Permissions:
    push: bool = True


@dataclass(frozen=True)
class Repository:
    full_name: str = REPOSITORY
    permissions: Permissions = Permissions()


@dataclass(frozen=True)
class Run:
    id: int = 123
    run_number: int = 123
    run_attempt: int = 1
    path: str = candidate.WORKFLOW
    event: str = "push"
    head_sha: str = REVISION
    head_branch: str = "main"
    status: str = "completed"
    conclusion: str = "success"
    repository: Repository = Repository()


@dataclass(frozen=True)
class GitTree:
    sha: str = TREE
    type: str = "commit"


@dataclass(frozen=True)
class GitCommit:
    sha: str
    tree: GitTree = GitTree()


@dataclass(frozen=True)
class GitReference:
    object: GitTree


@dataclass(frozen=True)
class ValidationEvidence:
    schemaVersion: int = 1
    outcome: str = "PRODUCT_VALIDATED"
    scope: str = "FULL"
    repository: str = REPOSITORY
    sourceRevision: str = MERGED
    sourceTree: str = TREE
    baseRevision: str = "e" * 40
    runId: int = 123
    runNumber: int = 123
    runAttempt: int = 1
    event: str = "push"


@dataclass(frozen=True)
class ValidationArtifact:
    name: str = f"ci-validation-outcome-{MERGED}-1"
    expired: bool = False


@dataclass(frozen=True)
class ArtifactListing:
    total_count: int
    artifacts: tuple[ValidationArtifact, ...]


@dataclass
class GhCall:
    arguments: list[str]
    stdout: str = ""
    exit_code: int = 0
    upload: bool = False
    retained_validation: str | None = None
    payload: str | None = None
    retained_files: tuple[str, ...] | None = None
    retained_payload: tuple[str, ...] = ()

    @classmethod
    def observation(
        cls, endpoint: str, document: Run | GitCommit | ArtifactListing | GitReference
    ) -> GhCall:
        return cls(
            ["api", "--include", endpoint],
            "HTTP/2.0 200 OK\r\n\r\n" + json.dumps(asdict(document)),
        )

    @classmethod
    def api(cls, method, endpoint, document, status=200, request=None):
        arguments = [
            "api",
            "--include",
            "--method",
            method,
            f"repos/{REPOSITORY}/{endpoint}".rstrip("/"),
        ]
        if request is not None:
            arguments.extend(("--input", "-"))
        return cls(
            arguments,
            f"HTTP/2.0 {status}\r\n\r\n"
            + json.dumps(
                [asdict(item) for item in document]
                if isinstance(document, tuple)
                else asdict(document)
            ),
            0 if status < 400 else 1,
            payload=json.dumps(asdict(request)) if request is not None else None,
        )


@dataclass(frozen=True)
class ReleaseDiscovery:
    id: int
    tag_name: str
    draft: bool


@dataclass(frozen=True)
class ReleaseAsset:
    name: str
    digest: str
    size: int
    state: str = "uploaded"


@dataclass(frozen=True)
class Release:
    tag_name: str
    target_commitish: str
    assets: tuple[ReleaseAsset, ...]
    draft: bool = False
    prerelease: bool = True
    id: int = 42
    upload_url: str = (
        f"https://uploads.github.com/repos/{REPOSITORY}/releases/42/assets{{?name,label}}"
    )


@dataclass(frozen=True)
class Contents:
    content: str
    type: str = "file"
    encoding: str = "base64"


@dataclass(frozen=True)
class ApiError:
    message: str = "Not Found"


@dataclass(frozen=True)
class Comparison:
    status: str


@dataclass
class GhScript:
    calls: list[GhCall]
    consumed: int = 0


@dataclass
class GhInvocation:
    arguments: list[str]


def scripted_gh(state: Path, calls: Path, violation: Path, arguments: list[str]) -> int:
    """Respond only to the case's finite external calls; never delegate to GitHub."""
    try:
        raw = json.loads(state.read_text())
        script = GhScript([GhCall(**call) for call in raw["calls"]], raw["consumed"])
        if script.consumed >= len(script.calls):
            raise AssertionError("unexpected extra GitHub call")
        expected = script.calls[script.consumed]
        if expected.upload:
            if arguments[:-1] != expected.arguments[:-1]:
                raise AssertionError("unexpected release upload command")
            source, observed = Path(expected.arguments[-1]), Path(arguments[-1])
            if source.name != observed.name or source.read_bytes() != observed.read_bytes():
                raise AssertionError("unexpected release upload bytes")
        elif expected.retained_validation is not None or expected.retained_files is not None:
            if arguments[:-1] != expected.arguments:
                raise AssertionError("unexpected validation download command")
            destination = Path(arguments[-1])
            if (
                destination.is_symlink()
                or not destination.is_dir()
                or destination.resolve().parent != Path(os.environ["TMPDIR"]).resolve()
            ):
                raise AssertionError("validation download must use an owned temporary directory")
            if expected.retained_validation is not None:
                (destination / "validation.json").write_bytes(
                    Path(expected.retained_validation).read_bytes()
                )
            for name in expected.retained_files or ():
                source = Path(name)
                (destination / source.name).write_bytes(source.read_bytes())
            if expected.retained_payload:
                payload = destination / "payload"
                payload.mkdir()
                for name in expected.retained_payload:
                    source = Path(name)
                    (payload / source.name).write_bytes(source.read_bytes())
        elif arguments != expected.arguments:
            raise AssertionError(
                f"unexpected GitHub command: {arguments!r}; expected {expected.arguments!r}"
            )
        if expected.payload is not None and json.loads(sys.stdin.read()) != json.loads(
            expected.payload
        ):
            raise AssertionError("unexpected GitHub request body")
        with calls.open("a") as log:
            log.write(json.dumps(asdict(GhInvocation(arguments))) + "\n")
        script.consumed += 1
        state.write_text(json.dumps(asdict(script)))
        sys.stdout.write(expected.stdout)
        return expected.exit_code
    except Exception as error:
        violation.write_text(f"{type(error).__name__}: {error}\n")
        return 97


def checksum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class DeveloperPublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kast-developer-publisher-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.bundle = self.root / "candidate assets"
        self.assets = self.bundle / "payload"
        self.record = fixture(self.bundle)
        # Scripted acceptance tests publication admission, not native execution.
        accepted_fixture(self.bundle, self.record)
        self.acceptances = (
            self.assets / "acceptance-linux-x86_64.json",
            self.assets / "acceptance-macos-aarch64.json",
        )
        self.identity = self.bundle / "build-record.json"
        self.validation = self.root / "validation.json"
        self.validation.write_text(json.dumps(asdict(ValidationEvidence())))
        self.uploads = [str(self.assets / asset.name) for asset in self.record.assets()]
        self.uploads.extend(str(path) for path in self.acceptances)
        self.uploads.append(str(self.identity))
        self.script = self.root / "gh-script.json"
        self.calls = self.root / "gh-calls.jsonl"
        self.violation = self.root / "gh-violation"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        gh = self.bin / "gh"
        command = [
            sys.executable,
            str(Path(__file__).resolve()),
            "--scripted-gh",
            str(self.script),
            str(self.calls),
            str(self.violation),
        ]
        gh.write_text("#!/bin/sh\nexec " + shlex.join(command) + ' "$@"\n')
        gh.chmod(0o700)
        (self.root / "home").mkdir()
        (self.root / "tmp").mkdir()
        self.environment = {
            "PATH": os.pathsep.join(
                (str(self.bin), str(Path(sys.executable).resolve().parent), os.defpath)
            ),
            "HOME": str(self.root / "home"),
            "TMPDIR": str(self.root / "tmp"),
            "GH_TOKEN": "owned-fixture-token",
            "GITHUB_REPOSITORY": REPOSITORY,
            "PYTHONDONTWRITEBYTECODE": "1",
            "LC_ALL": "C",
        }

    def admission_calls(self) -> list[GhCall]:
        native = replace(Run(), id=456, run_attempt=2, path=".github/workflows/composed-linux.yml")
        # Admission observes each producer's run at its final attempt, whose success
        # covers jobs a rerun of failed jobs kept from an earlier attempt.
        calls = [
            GhCall.observation(f"repos/{REPOSITORY}/actions/runs/123", Run()),
            GhCall.observation(
                f"repos/{REPOSITORY}/actions/runs/123/artifacts?per_page=100",
                ArtifactListing(1, (ValidationArtifact(),)),
            ),
            GhCall(
                [
                    "run",
                    "download",
                    "123",
                    "--repo",
                    REPOSITORY,
                    "--name",
                    ValidationArtifact().name,
                    "--dir",
                ],
                retained_validation=str(self.validation),
            ),
            GhCall.observation(f"repos/{REPOSITORY}/actions/runs/456", native),
            GhCall.observation(f"repos/{REPOSITORY}/actions/runs/456", native),
            GhCall.observation(f"repos/{REPOSITORY}/git/commits/{REVISION}", GitCommit(REVISION)),
        ]
        retained = [(123, f"ci-product-candidate-{REVISION}-1", (str(self.identity),))]
        for target in self.record.targets:
            retained.append(
                (
                    456,
                    f"kast-qualified-product-{target.target}-{REVISION}-456-2",
                    tuple(
                        str(self.assets / asset.name)
                        for asset in (
                            target.bundle,
                            target.runtime,
                            target.qualification.receipt,
                        )
                    ),
                )
            )
        for run, name, files in retained:
            calls.extend(
                (
                    GhCall.observation(
                        f"repos/{REPOSITORY}/actions/runs/{run}/artifacts?per_page=100",
                        ArtifactListing(1, (ValidationArtifact(name),)),
                    ),
                    GhCall(
                        [
                            "run",
                            "download",
                            str(run),
                            "--repo",
                            REPOSITORY,
                            "--name",
                            name,
                            "--dir",
                        ],
                        retained_files=files,
                        retained_payload=tuple(str(path) for path in self.acceptances)
                        if run == 123
                        else (),
                    ),
                )
            )
        return calls

    def release_assets(self) -> tuple[ReleaseAsset, ...]:
        return tuple(
            ReleaseAsset(Path(path).name, f"sha256:{checksum(Path(path))}", Path(path).stat().st_size)
            for path in self.uploads
        )

    def discovery_calls(self, releases: tuple[Release, ...]) -> list[GhCall]:
        calls = [
            GhCall.api("GET", "", Repository()),
            GhCall.api("GET", "releases?per_page=100&page=1",
                       tuple(ReleaseDiscovery(item.id, item.tag_name, item.draft) for item in releases)),
            *([GhCall.api("GET", "releases?per_page=100&page=2", ())] if releases else []),
        ]
        for call in calls[1:]:
            call.arguments.extend(("--jq", "[.[] | {id,tag_name,draft}]"))
        return calls

    def inspection_calls(self, release: Release) -> list[GhCall]:
        return [
            GhCall.api("GET", "releases/42", release),
            GhCall.api("GET", "releases/42/assets?per_page=100&page=1", release.assets),
            *(
                [GhCall.api("GET", "releases/42/assets?per_page=100&page=2", ())]
                if release.assets
                else []
            ),
        ]

    def published_calls(self, damaged: bool = False) -> list[GhCall]:
        assets = self.release_assets()
        if damaged:
            assets = (replace(assets[0], digest="sha256:" + "f" * 64), *assets[1:])
        release = Release(TAG, REVISION, assets)
        return [
            *self.discovery_calls((release,)),
            *self.inspection_calls(release),
            *(
                []
                if damaged
                else [GhCall.api("GET", f"git/ref/tags/{TAG}", GitReference(GitTree(REVISION)))]
            ),
        ]

    def pointer_calls(self, delayed=False) -> list[GhCall]:
        incoming = catalog.ProductBuild(MERGED, TAG, self.record, checksum(self.identity))
        newer_record = replace(
            self.record,
            sourceRevision="f" * 40,
            version="0.0.124",
            targets=tuple(
                replace(
                    target,
                    bundle=replace(
                        target.bundle,
                        name=target.bundle.name.replace(VERSION, "0.0.124"),
                    ),
                    runtime=replace(
                        target.runtime,
                        name=target.runtime.name.replace(VERSION, "0.0.124"),
                    ),
                )
                for target in self.record.targets
            ),
        )
        newer = catalog.ProductBuild("f" * 40, "developer-v0.0.124", newer_record, "3" * 64)
        prior = catalog.Catalog(2, (newer,) if delayed else ())
        merged = catalog.Catalog(2, (newer, incoming) if delayed else (incoming,))
        head, next_tree, next_commit = "7" * 40, "8" * 40, "9" * 40
        calls = [
            GhCall.api("GET", "git/ref/heads/developer-latest", GitReference(GitTree(head))),
            GhCall.api("GET", f"git/commits/{head}", GitCommit(head)),
            GhCall.api(
                "GET",
                f"contents/builds.json?ref={head}",
                Contents(base64.b64encode(catalog.encode(prior).encode()).decode()),
            ),
            GhCall.api(
                "GET",
                f"contents/latest.txt?ref={head}",
                Contents(base64.b64encode(catalog.latest_pointer(prior).encode()).decode())
                if delayed
                else ApiError(),
                status=200 if delayed else 404,
            ),
            GhCall.api(
                "GET",
                "git/ref/heads/main",
                GitReference(GitTree(newer.mainRevision if delayed else MERGED)),
            ),
        ]
        if delayed:
            calls.extend(
                (
                    GhCall.api(
                        "GET",
                        f"compare/{newer.mainRevision}...{newer.mainRevision}?per_page=1&page=2",
                        Comparison("identical"),
                    ),
                    GhCall.api(
                        "GET",
                        f"compare/{MERGED}...{newer.mainRevision}?per_page=1&page=2",
                        Comparison("ahead"),
                    ),
                    GhCall.api(
                        "GET",
                        f"compare/{newer.mainRevision}...{MERGED}?per_page=1&page=2",
                        Comparison("behind"),
                    ),
                )
            )
        else:
            calls.append(
                GhCall.api(
                    "GET",
                    f"compare/{MERGED}...{MERGED}?per_page=1&page=2",
                    Comparison("identical"),
                )
            )
        calls.extend(
            (
                GhCall.api(
                    "POST",
                    "git/trees",
                    GitTree(next_tree),
                    status=201,
                    request=publisher.GitTreeRequest(
                        TREE,
                        (
                            publisher.GitTreeFile("builds.json", catalog.encode(merged)),
                            publisher.GitTreeFile("latest.txt", catalog.latest_pointer(merged)),
                        ),
                    ),
                ),
                GhCall.api(
                    "POST",
                    "git/commits",
                    GitTree(next_commit),
                    status=201,
                    request=publisher.GitCommitRequest(
                        f"chore(distribution): admit {MERGED} to published main builds",
                        next_tree,
                        (head,),
                    ),
                ),
                GhCall.api(
                    "PATCH",
                    "git/refs/heads/developer-latest",
                    GitReference(GitTree(next_commit)),
                    request=publisher.GitRefUpdate(next_commit),
                ),
            )
        )
        return calls

    def run_publisher(self, calls: list[GhCall]) -> subprocess.CompletedProcess:
        self.script.write_text(json.dumps(asdict(GhScript(calls))))
        result = subprocess.run(
            [
                sys.executable,
                str(PUBLISHER),
                "product",
                "--directory",
                str(self.bundle),
            ],
            cwd=ROOT,
            env=self.environment,
            text=True,
            capture_output=True,
            timeout=30,
        )
        self.assertFalse(
            self.violation.exists(),
            self.violation.read_text() if self.violation.exists() else "",
        )
        return result

    def assert_consumed(self, count: int):
        self.assertEqual(count, json.loads(self.script.read_text())["consumed"])

    def test_new_release_uploads_payload_and_provenance_before_publishing_pointer(self):
        empty = Release(TAG, REVISION, (), draft=True)
        complete = replace(empty, assets=self.release_assets())
        published = replace(complete, draft=False)
        creation = GhCall.api(
            "POST",
            "releases",
            empty,
            status=201,
            request=publisher.ReleaseCreation(
                TAG,
                REVISION,
                f"Kast developer {VERSION}",
                f"Retained build {VERSION} from {REVISION}. "
                "Publication does not rebuild or change archive bytes.",
                True,
            ),
        )
        uploads = [
            GhCall(
                [
                    "api", "--include", "--method", "POST",
                    f"https://uploads.github.com/repos/{REPOSITORY}/releases/42/assets"
                    f"?name={Path(path).name}",
                    "--header", "Content-Type: application/octet-stream", "--input", path,
                ],
                "HTTP/2.0 201\r\n\r\n" + json.dumps(asdict(asset)),
                upload=True,
            )
            for path, asset in zip(self.uploads, complete.assets, strict=True)
        ]
        calls = [
            *self.admission_calls(),
            *self.discovery_calls(()),
            GhCall.api("GET", f"git/ref/tags/{TAG}", ApiError(), status=404),
            creation,
            *self.inspection_calls(empty),
            GhCall.api("GET", f"git/ref/tags/{TAG}", ApiError(), status=404),
            *uploads,
            *self.inspection_calls(complete),
            GhCall.api(
                "PATCH",
                "releases/42",
                published,
                request=publisher.ReleasePublication(publisher.LatestPolicy.FALSE),
            ),
            *self.inspection_calls(published),
            GhCall.api("GET", f"git/ref/tags/{TAG}", GitReference(GitTree(REVISION))),
            *self.pointer_calls(),
        ]
        result = self.run_publisher(calls)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_consumed(len(calls))
        invocations = [
            GhInvocation(**json.loads(line)) for line in self.calls.read_text().splitlines()
        ]
        actual = [call.arguments[-1] for call in invocations if "--header" in call.arguments]
        self.assertEqual(self.uploads, actual)
        self.assertEqual(9, len(self.uploads))
        self.assertIn(
            f"https://raw.githubusercontent.com/{REPOSITORY}/developer-latest/builds.json",
            result.stdout,
        )

    def test_existing_complete_release_is_admitted_before_pointer_update(self):
        calls = [
            *self.admission_calls(),
            *self.published_calls(),
            *self.pointer_calls(),
        ]
        result = self.run_publisher(calls)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_consumed(len(calls))

    def test_incomplete_candidate_rejects_before_any_github_effect(self):
        (self.assets / self.record.targets[0].runtime.name).unlink()
        result = self.run_publisher([])
        self.assertNotEqual(0, result.returncode)
        self.assertIn('"reason": "PAYLOAD_MISMATCH"', result.stderr)
        self.assert_consumed(0)
        self.assertFalse(self.calls.exists())

    def test_missing_target_acceptance_rejects_before_any_github_effect(self):
        for path, reason in (
            (self.acceptances[1], "PAYLOAD_MISMATCH"),
            (self.acceptances[0], "GATE_NOT_COMPLETE"),
        ):
            with self.subTest(reason=reason):
                path.unlink()
                result = self.run_publisher([])
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f'"reason": "{reason}"', result.stderr)
                self.assert_consumed(0)
                self.assertFalse(self.calls.exists())

    def test_missing_validation_rejects_before_release_or_pointer_effects(self):
        calls = self.admission_calls()[:1] + [
            GhCall.observation(
                f"repos/{REPOSITORY}/actions/runs/123/artifacts?per_page=100",
                ArtifactListing(0, ()),
            ),
        ]
        result = self.run_publisher(calls)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('"reason": "INVALID_RECORD"', result.stderr)
        self.assert_consumed(len(calls))

    def test_wrong_validation_attempt_rejects_before_release_or_pointer_effects(self):
        self.validation.write_text(json.dumps(asdict(replace(ValidationEvidence(), runAttempt=2))))
        calls = self.admission_calls()[:3]
        result = self.run_publisher(calls)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('"reason": "WRONG_PRODUCER"', result.stderr)
        self.assert_consumed(len(calls))

    def test_delayed_publication_preserves_latest(self):
        calls = [
            *self.admission_calls(),
            *self.published_calls(),
            *self.pointer_calls(delayed=True),
        ]
        result = self.run_publisher(calls)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("builds.json", result.stdout)
        self.assert_consumed(len(calls))

    def test_remote_digest_mismatch_rejects_before_pointer_observation(self):
        calls = [*self.admission_calls(), *self.published_calls(damaged=True)]
        result = self.run_publisher(calls)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('"reason": "ASSET_MISMATCH"', result.stderr)
        self.assert_consumed(len(calls))


if __name__ == "__main__":
    if sys.argv[1:2] == ["--scripted-gh"]:
        raise SystemExit(
            scripted_gh(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), sys.argv[5:])
        )
    unittest.main()
