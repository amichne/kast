#!/usr/bin/env python3
"""The real developer publisher must upload the complete admitted installation pair."""
from __future__ import annotations

import base64
from dataclasses import asdict, dataclass
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


def scripted_gh(arguments: list[str]) -> int:
    """Respond only to the case's finite external calls; never delegate to GitHub."""
    state = Path(os.environ["KAST_TEST_GH_SCRIPT"])
    violation = Path(os.environ["KAST_TEST_GH_VIOLATION"])
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
        with Path(os.environ["KAST_TEST_GH_CALLS"]).open("a") as log:
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
        self.assets = self.root / "candidate assets"
        self.assets.mkdir()
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
        self.script = self.root / "gh-script.json"
        self.calls = self.root / "gh-calls.jsonl"
        self.violation = self.root / "gh-violation"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        gh = self.bin / "gh"
        gh.write_text(f"#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(str(Path(__file__).resolve()))} --scripted-gh \"$@\"\n")
        gh.chmod(0o700)
        (self.root / "home").mkdir()
        (self.root / "tmp").mkdir()
        self.environment = {
            "PATH": os.pathsep.join((str(self.bin), str(Path(sys.executable).resolve().parent), os.defpath)),
            "HOME": str(self.root / "home"), "TMPDIR": str(self.root / "tmp"),
            "GH_TOKEN": "owned-fixture-token", "GITHUB_REPOSITORY": REPOSITORY,
            "KAST_TEST_GH_SCRIPT": str(self.script), "KAST_TEST_GH_CALLS": str(self.calls),
            "KAST_TEST_GH_VIOLATION": str(self.violation), "PYTHONDONTWRITEBYTECODE": "1", "LC_ALL": "C",
        }

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
             "--assets-directory", str(self.assets)],
            cwd=ROOT, env=self.environment, text=True, capture_output=True, timeout=30,
        )
        self.assertFalse(self.violation.exists(), self.violation.read_text() if self.violation.exists() else "")
        return result

    def assert_consumed(self, count: int):
        self.assertEqual(count, json.loads(self.script.read_text())["consumed"])

    def test_new_release_uploads_all_twenty_verified_assets_before_publishing_pointer(self):
        creation = GhCall(
            ["release", "create", TAG, *self.uploads, "--repo", REPOSITORY, "--target", REVISION,
             "--prerelease", "--latest=false", "--title", f"Kast developer {VERSION}",
             "--notes", f"Exact-source developer build from {REVISION}. The SBOM and checksums are included."],
            upload=True,
        )
        calls = [GhCall(["release", "view", TAG, "--repo", REPOSITORY], exit_code=1),
                 creation, *self.pointer_calls()]
        result = self.run_publisher(calls)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_consumed(len(calls))
        invocations = [GhInvocation(**json.loads(line)) for line in self.calls.read_text().splitlines()]
        actual = invocations[1].arguments
        self.assertEqual(self.uploads, actual[3:actual.index("--repo", 3)])
        self.assertEqual(20, len(self.uploads))
        self.assertEqual("release-candidate: admitted", result.stdout.splitlines()[0])
        self.assertIn(f"https://raw.githubusercontent.com/{REPOSITORY}/developer-latest/latest.txt", result.stdout)

    def test_existing_complete_release_is_admitted_before_pointer_update(self):
        published = "\n".join(sorted(f"{Path(path).name}\tsha256:{checksum(Path(path))}" for path in self.uploads)) + "\n"
        calls = [
            GhCall(["release", "view", TAG, "--repo", REPOSITORY]),
            GhCall(["release", "view", TAG, "--repo", REPOSITORY, "--json", "targetCommitish", "--jq", ".targetCommitish"],
                   REVISION + "\n"),
            GhCall(["release", "view", TAG, "--repo", REPOSITORY, "--json", "assets",
                    "--jq", ".assets[] | [.name, .digest] | @tsv"], published),
            *self.pointer_calls(),
        ]
        result = self.run_publisher(calls)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_consumed(len(calls))

    def test_incomplete_candidate_rejects_before_any_github_effect(self):
        (self.assets / f"kast-host-release-v{VERSION}.json").unlink()
        result = self.run_publisher([])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("candidate asset inventory does not match the release contract", result.stderr)
        self.assert_consumed(0)
        self.assertFalse(self.calls.exists())


if __name__ == "__main__":
    if sys.argv[1:2] == ["--scripted-gh"]:
        raise SystemExit(scripted_gh(sys.argv[2:]))
    unittest.main()
