#!/usr/bin/env python3
"""The real developer publisher must upload the complete admitted installation pair."""
from __future__ import annotations

import base64
from dataclasses import asdict, dataclass, replace
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
PUBLISHER = ROOT / ".github/scripts/release/publish-developer.sh"
REPOSITORY = "fixture/kast"
VERSION = "0.0.123"
REVISION = "a" * 40
TAG = f"developer-v{VERSION}"
MERGED = "b" * 40
TREE = "c" * 40
sys.path.insert(0, str(ROOT / ".github/scripts/release"))
import build_candidate as candidate


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
    head_sha: str = REVISION
    head_branch: str = "feature"
    status: str = "completed"
    conclusion: str = "success"
    repository: Repository = Repository()


@dataclass(frozen=True)
class GitTree:
    sha: str = TREE


@dataclass(frozen=True)
class GitCommit:
    sha: str
    tree: GitTree = GitTree()


@dataclass(frozen=True)
class GitReference:
    object: GitTree


@dataclass
class SbomProperty:
    name: str
    value: str


@dataclass
class SbomMetadata:
    properties: list[SbomProperty]


@dataclass
class Sbom:
    bomFormat: str
    metadata: SbomMetadata


@dataclass
class GhCall:
    arguments: list[str]
    stdout: str = ""
    exit_code: int = 0
    upload: bool = False


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
            # Upload paths are the production result under assertion, not a fixture-owned decision.
            observed_suffix = arguments.index("--repo", 3)
            expected_suffix = expected.arguments.index("--repo", 3)
            if (arguments[:3] != expected.arguments[:3]
                    or arguments[observed_suffix:] != expected.arguments[expected_suffix:]):
                raise AssertionError("unexpected release creation command")
        elif arguments != expected.arguments:
            raise AssertionError("unexpected GitHub command")
        with calls.open("a") as log:
            log.write(json.dumps(asdict(GhInvocation(arguments))) + "\n")
        script.consumed += 1
        state.write_text(json.dumps(asdict(script)))
        sys.stdout.write(expected.stdout)
        return expected.exit_code
    except Exception as error:
        violation.write_text(f"{type(error).__name__}: {error}\n")
        return 97


def asset_names() -> tuple[str, ...]:
    return (
        f"kast-control-v{VERSION}-macos-aarch64.tar.gz",
        f"kast-ide-hosted-v{VERSION}-idea-262.zip",
        f"kast-skill-v{VERSION}.zip",
        f"kast-plugin-v{VERSION}.zip",
        f"kast-marketplace-v{VERSION}.zip",
        f"kast-host-release-v{VERSION}.json",
        "host-installation.py",
        f"kast-hosted-catalog-v{VERSION}.json",
        f"kast-module-knowledge-v{VERSION}.json",
        f"kast-sbom-v{VERSION}.cdx.json",
    )


def checksum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class DeveloperPublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kast-developer-publisher-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.bundle = self.root / "candidate assets"
        self.assets = self.bundle / "payload"
        self.assets.mkdir(parents=True)
        names = asset_names()
        for name in names[:-1]:
            (self.assets / name).write_bytes(name.encode())
        properties = [SbomProperty("kast:source-revision", REVISION)]
        properties.extend(SbomProperty(f"kast:archive:{name}", f"sha256:{checksum(self.assets / name)}")
                          for name in names[:5])
        (self.assets / names[-1]).write_text(json.dumps(asdict(Sbom("CycloneDX", SbomMetadata(properties)))))
        for name in names:
            (self.assets / f"{name}.sha256").write_text(f"{checksum(self.assets / name)}  {name}\n")
        self.uploads = [str(self.assets / asset) for name in names for asset in (name, name + ".sha256")]
        value = candidate.Candidate(1, REPOSITORY, REVISION, TREE, VERSION, 123, 123, 1, "pull_request",
                                    "macos-aarch64", candidate.Toolchain("25.0.2", "Oracle Corporation", "d" * 64),
                                    tuple(candidate.Asset(path.name, checksum(path), path.stat().st_size)
                                          for path in sorted(self.assets.iterdir())))
        self.identity = self.bundle / "candidate.json"
        self.identity.write_text(json.dumps(asdict(value)))
        self.promotion = self.root / "promotion.json"
        self.promotion.write_text(json.dumps(asdict(candidate.Promotion(1, REPOSITORY, REVISION, MERGED, TREE,
                                  VERSION, 123, 1, 456, 1, checksum(self.identity)))))
        self.uploads.extend((str(self.identity), str(self.promotion)))
        self.script = self.root / "gh-script.json"
        self.calls = self.root / "gh-calls.jsonl"
        self.violation = self.root / "gh-violation"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        gh = self.bin / "gh"
        command = [sys.executable, str(Path(__file__).resolve()), "--scripted-gh",
                   str(self.script), str(self.calls), str(self.violation)]
        gh.write_text("#!/bin/sh\nexec " + shlex.join(command) + ' "$@"\n')
        gh.chmod(0o700)
        (self.root / "home").mkdir()
        (self.root / "tmp").mkdir()
        self.environment = {
            "PATH": os.pathsep.join((str(self.bin), str(Path(sys.executable).resolve().parent), os.defpath)),
            "HOME": str(self.root / "home"), "TMPDIR": str(self.root / "tmp"),
            "GH_TOKEN": "owned-fixture-token", "GITHUB_REPOSITORY": REPOSITORY,
            "PYTHONDONTWRITEBYTECODE": "1", "LC_ALL": "C",
        }

    def admission_calls(self) -> list[GhCall]:
        main = replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main")
        return [
            GhCall(["api", f"repos/{REPOSITORY}/actions/runs/456"], json.dumps(asdict(main))),
            GhCall(["api", f"repos/{REPOSITORY}/actions/runs/123"], json.dumps(asdict(Run()))),
            GhCall(["api", f"repos/{REPOSITORY}/git/commits/{MERGED}"], json.dumps(asdict(GitCommit(MERGED)))),
            GhCall(["api", f"repos/{REPOSITORY}/git/commits/{REVISION}"], json.dumps(asdict(GitCommit(REVISION)))),
        ]

    def published_calls(self, damaged: bool = False) -> list[GhCall]:
        published = "\n".join(sorted(f"{Path(path).name}\tsha256:{checksum(Path(path))}" for path in self.uploads)) + "\n"
        return [
            GhCall(["release", "view", TAG, "--repo", REPOSITORY, "--json", "targetCommitish", "--jq", ".targetCommitish"],
                   REVISION + "\n"),
            GhCall(["release", "view", TAG, "--repo", REPOSITORY, "--json", "assets",
                    "--jq", ".assets[] | [.name, .digest] | @tsv"], "tampered" if damaged else published),
        ]

    def current_main_call(self, current: bool = True) -> GhCall:
        return GhCall(["api", f"repos/{REPOSITORY}/git/ref/heads/main"],
                      json.dumps(asdict(GitReference(GitTree(MERGED if current else REVISION)))))

    def pointer_calls(self) -> list[GhCall]:
        pointer = base64.b64encode(f"{TAG} {VERSION} {REVISION}\n".encode()).decode()
        return [
            GhCall(["api", f"repos/{REPOSITORY}/git/ref/heads/developer-latest"]),
            GhCall(["api", f"repos/{REPOSITORY}/contents/latest.txt?ref=developer-latest", "--jq", ".sha"],
                   "prior-pointer\n"),
            GhCall(["api", "--method", "PUT", f"repos/{REPOSITORY}/contents/latest.txt",
                    "-f", f"message=chore(distribution): point developer latest to {TAG}",
                    "-f", f"content={pointer}", "-f", "branch=developer-latest", "-f", "sha=prior-pointer"]),
            GhCall(["release", "view", TAG, "--repo", REPOSITORY,
                    "--json", "tagName,targetCommitish,url,assets"]),
        ]

    def run_publisher(self, calls: list[GhCall]) -> subprocess.CompletedProcess:
        self.script.write_text(json.dumps(asdict(GhScript(calls))))
        result = subprocess.run(
            ["bash", str(PUBLISHER), "--version", VERSION, "--commit", REVISION,
             "--assets-directory", str(self.assets), "--promotion-record", str(self.promotion)],
            cwd=ROOT, env=self.environment, text=True, capture_output=True, timeout=30,
        )
        self.assertFalse(self.violation.exists(), self.violation.read_text() if self.violation.exists() else "")
        return result

    def assert_consumed(self, count: int):
        self.assertEqual(count, json.loads(self.script.read_text())["consumed"])

    def test_new_release_uploads_payload_and_provenance_before_publishing_pointer(self):
        creation = GhCall(
            ["release", "create", TAG, *self.uploads, "--repo", REPOSITORY, "--target", REVISION,
             "--prerelease", "--latest=false", "--title", f"Kast developer {VERSION}",
             "--notes", f"Tested developer build from {REVISION}, promoted after main {MERGED}. Candidate and promotion records, SBOM and checksums are included."],
            upload=True,
        )
        calls = [*self.admission_calls(), GhCall(["release", "view", TAG, "--repo", REPOSITORY], exit_code=1),
                 creation, *self.published_calls(), self.current_main_call(), *self.pointer_calls()]
        result = self.run_publisher(calls)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_consumed(len(calls))
        invocations = [GhInvocation(**json.loads(line)) for line in self.calls.read_text().splitlines()]
        actual = invocations[len(self.admission_calls()) + 1].arguments
        self.assertEqual(self.uploads, actual[3:actual.index("--repo", 3)])
        self.assertEqual(22, len(self.uploads))
        self.assertEqual("release-candidate: admitted", result.stdout.splitlines()[0])
        self.assertIn(f"https://raw.githubusercontent.com/{REPOSITORY}/developer-latest/latest.txt", result.stdout)

    def test_existing_complete_release_is_admitted_before_pointer_update(self):
        calls = [*self.admission_calls(), GhCall(["release", "view", TAG, "--repo", REPOSITORY]),
                 *self.published_calls(), self.current_main_call(), *self.pointer_calls()]
        result = self.run_publisher(calls)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_consumed(len(calls))

    def test_incomplete_candidate_rejects_before_any_github_effect(self):
        (self.assets / f"kast-host-release-v{VERSION}.json").unlink()
        result = self.run_publisher([])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("build-candidate: PAYLOAD_MISMATCH", result.stderr)
        self.assert_consumed(0)
        self.assertFalse(self.calls.exists())

    def test_delayed_publication_preserves_latest(self):
        calls = [*self.admission_calls(), GhCall(["release", "view", TAG, "--repo", REPOSITORY]),
                 *self.published_calls(), self.current_main_call(current=False)]
        result = self.run_publisher(calls)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("latest retained", result.stdout)
        self.assert_consumed(len(calls))

    def test_remote_digest_mismatch_rejects_before_pointer_observation(self):
        calls = [*self.admission_calls(), GhCall(["release", "view", TAG, "--repo", REPOSITORY]),
                 *self.published_calls(damaged=True)]
        result = self.run_publisher(calls)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("developer assets differ", result.stderr)
        self.assert_consumed(len(calls))


if __name__ == "__main__":
    if sys.argv[1:2] == ["--scripted-gh"]:
        raise SystemExit(scripted_gh(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), sys.argv[5:]))
    unittest.main()
