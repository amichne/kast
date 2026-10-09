#!/usr/bin/env python3
"""Candidate admission preserves build identity and rejects unproven promotion."""
from dataclasses import asdict, dataclass, replace
import json
from pathlib import Path
import subprocess
import tempfile
import sys
import unittest
from unittest.mock import call, patch

import build_candidate as candidate

REPOSITORY = "amichne/kast"
BUILD = "a" * 40
MERGED = "b" * 40
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


def record(assets=()):
    return candidate.Candidate(1, REPOSITORY, BUILD, TREE, VERSION, 123, 123, 1, "pull_request",
                               "macos-aarch64", candidate.Toolchain("25.0.2", "Oracle Corporation", "d" * 64), assets)


class SourceAdmissionTest(unittest.TestCase):
    def test_equal_tree_preserves_original_build_commit_and_version(self):
        value = record()
        self.assertEqual(candidate.Admitted(value), candidate.refine(value, asdict(Run()), REPOSITORY, TREE))
        self.assertEqual(BUILD, value.sourceRevision)
        self.assertEqual(VERSION, value.version)

    def test_large_run_identity_preserves_installable_run_number_version(self):
        value = replace(record((candidate.Asset("asset", "e" * 64, 1),)), runId=37939600363)
        self.assertEqual(value, candidate.decode(asdict(value)))
        self.assertEqual(candidate.Admitted(value),
                         candidate.refine(value, asdict(replace(Run(), id=37939600363)), REPOSITORY, TREE))
        self.assertEqual("0.0.123", value.version)

    def test_unequal_tree_cannot_be_promoted(self):
        self.assertEqual(candidate.Failure.SOURCE_TREE_MISMATCH,
                         candidate.refine(record(), asdict(Run()), REPOSITORY, "e" * 40))

    def test_every_unproven_producer_rejects(self):
        for run in (replace(Run(), run_attempt=2), replace(Run(), id=124), replace(Run(), run_number=124),
                    replace(Run(), repository=Repository("foreign/kast")),
                    replace(Run(), path=".github/workflows/foreign.yml"),
                    replace(Run(), head_sha=MERGED), replace(Run(), event="push")):
            with self.subTest(run=run):
                self.assertEqual(candidate.Failure.WRONG_PRODUCER,
                                 candidate.refine(record(), asdict(run), REPOSITORY, TREE))
        for change in (replace(Run(), status="in_progress"), replace(Run(), conclusion="failure"),
                       replace(Run(), conclusion="cancelled")):
            with self.subTest(run=change):
                self.assertEqual(candidate.Failure.PRODUCER_NOT_SUCCESSFUL,
                                 candidate.refine(record(), asdict(change), REPOSITORY, TREE))

    def test_closed_record_shape_and_required_identity_are_encoded(self):
        value = record((candidate.Asset("asset", "e" * 64, 1),))
        encoded = json.loads(json.dumps(asdict(value)))
        self.assertEqual({"schemaVersion", "repository", "sourceRevision", "sourceTree", "version", "runId",
                          "runNumber", "runAttempt", "event", "platform", "toolchain", "assets"}, set(encoded))
        self.assertEqual(value, candidate.decode(encoded))
        for change in ({"schemaVersion": True}, {"runId": True}, {"runAttempt": 0}, {"runNumber": 2147483648}, {"runNumber": True}, {"version": "0.0.124"},
                       {"platform": "linux"}, {"event": "unknown"}, {"unknown": "value"},
                       {"sourceTree": "unknown"}, {"toolchain": {}}, {"assets": []},
                       {"assets": [asdict(candidate.Asset("../escape", "e" * 64, 1))]}):
            with self.subTest(change=change), self.assertRaises(candidate.Rejected) as rejection:
                candidate.decode(encoded | change)
            self.assertEqual(candidate.Failure.INVALID_RECORD, rejection.exception.failure)

    def test_incomplete_api_listing_is_never_missing_success(self):
        for document in ({}, {"total_count": True, "workflow_runs": []},
                         {"total_count": 1, "workflow_runs": []}):
            with self.subTest(document=document), self.assertRaises(candidate.Rejected):
                candidate.listing(document, "workflow_runs")


class PayloadAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.bundle = Path(self.temporary.name).resolve() / "candidate"
        payload = self.bundle / "payload"
        payload.mkdir(parents=True)
        names = (f"kast-control-v{VERSION}-macos-aarch64.tar.gz", f"kast-ide-hosted-v{VERSION}-idea-262.zip",
                 *(f"kast-{name}-v{VERSION}.zip" for name in ("skill", "plugin", "marketplace")),
                 f"kast-host-release-v{VERSION}.json", "host-installation.py", f"kast-hosted-catalog-v{VERSION}.json",
                 f"kast-module-knowledge-v{VERSION}.json", f"kast-sbom-v{VERSION}.cdx.json")
        for name in names[:-1]:
            (payload / name).write_bytes(name.encode())
        sbom = Sbom("CycloneDX", Metadata((Property("kast:source-revision", BUILD),
                     *(Property(f"kast:archive:{name}", "sha256:" + candidate.legacy.digest(payload / name))
                       for name in names[:5]))))
        (payload / names[-1]).write_text(json.dumps(asdict(sbom)))
        for name in names:
            (payload / (name + ".sha256")).write_text(f"{candidate.legacy.digest(payload / name)}  {name}\n")
        self.value = record(tuple(candidate.Asset(path.name, candidate.legacy.digest(path), path.stat().st_size)
                                  for path in sorted(payload.iterdir())))
        (self.bundle / "candidate.json").write_text(json.dumps(asdict(self.value)))

    def test_all_payload_bytes_match_the_retained_manifest(self):
        self.assertEqual(self.value, candidate.read(self.bundle))

    def test_changed_extra_and_symlink_payloads_reject(self):
        payload = self.bundle / "payload"
        asset = payload / self.value.assets[0].name
        asset.write_bytes(b"tampered")
        with self.assertRaises(candidate.Rejected) as rejection:
            candidate.read(self.bundle)
        self.assertEqual(candidate.Failure.PAYLOAD_MISMATCH, rejection.exception.failure)

    def test_manifest_digest_protects_even_a_rechecksummed_payload(self):
        payload = self.bundle / "payload"
        asset = payload / "host-installation.py"
        asset.write_bytes(b"tampered")
        (payload / "host-installation.py.sha256").write_text(f"{candidate.legacy.digest(asset)}  {asset.name}\n")
        with self.assertRaises(candidate.Rejected) as rejection:
            candidate.read(self.bundle)
        self.assertEqual(candidate.Failure.PAYLOAD_MISMATCH, rejection.exception.failure)

    def test_byte_preserving_promotion_records_both_source_identities(self):
        destination = Path(self.temporary.name) / "download"
        promotion = Path(self.temporary.name) / "promotion.json"
        main = replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main")
        original_hashes = {asset.name: asset.sha256 for asset in self.value.assets}
        with patch.object(candidate, "observe", side_effect=[asdict(main), asdict(Run())]) as observe, \
                patch.object(candidate, "download", return_value=self.value) as download, \
                patch.object(candidate, "tree", side_effect=[TREE, TREE]) as tree, \
                patch.object(candidate.legacy, "digest", return_value="f" * 64):
            admitted = candidate.promote(REPOSITORY, MERGED, 456, destination, promotion)
        self.assertEqual(self.value, admitted)
        self.assertEqual(original_hashes, {asset.name: asset.sha256 for asset in admitted.assets})
        saved = json.loads(promotion.read_text())
        self.assertEqual(BUILD, saved["buildRevision"])
        self.assertEqual(MERGED, saved["mergedRevision"])
        self.assertEqual(TREE, saved["sourceTree"])
        self.assertEqual(VERSION, saved["version"])
        self.assertEqual(2, observe.call_count)
        self.assertEqual(2, tree.call_count)
        download.assert_called_once_with(REPOSITORY, asdict(main), MERGED, destination)

    def test_failed_main_never_downloads_or_writes_a_promotion(self):
        with patch.object(candidate, "observe", return_value=asdict(replace(Run(), conclusion="failure"))), \
                patch.object(candidate, "download") as download, self.assertRaises(candidate.Rejected):
            candidate.promote(REPOSITORY, MERGED, 456, Path(self.temporary.name) / "download",
                              Path(self.temporary.name) / "promotion.json")
        download.assert_not_called()
        self.assertFalse((Path(self.temporary.name) / "promotion.json").exists())


class EffectBoundaryTest(unittest.TestCase):
    def test_absent_or_foreign_pr_requires_fresh_build_without_download(self):
        for pulls in ([], [{"merged_at": "now", "merge_commit_sha": MERGED,
                           "head": {"sha": BUILD, "repo": {"full_name": "foreign/kast"}}}]):
            with self.subTest(pulls=pulls), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(candidate, "observe", return_value=pulls) as observe, \
                    patch.object(candidate, "tree", return_value=TREE), \
                    patch.object(candidate, "download") as download:
                bundle = Path(temporary) / "bundle"
                self.assertEqual("MISSING", candidate.reuse(REPOSITORY, MERGED, bundle))
                observe.assert_called_once()
                download.assert_not_called()
                self.assertFalse(bundle.exists())

    def test_successful_same_tree_pr_reuses_only_its_exact_artifact(self):
        run = asdict(Run())
        pulls = [{"merged_at": "now", "merge_commit_sha": MERGED,
                  "head": {"sha": BUILD, "repo": {"full_name": REPOSITORY}}}]
        for actual_tree, expected in ((TREE, "REUSED"), ("e" * 40, "MISSING")):
            with self.subTest(tree=actual_tree), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(candidate, "observe", side_effect=[pulls,
                        {"total_count": 1, "workflow_runs": [run]},
                        {"total_count": 1, "artifacts": [{"name": f"ci-build-candidate-{BUILD}-1", "expired": False}]}]) as observe, \
                    patch.object(candidate, "tree", side_effect=[TREE, actual_tree]) as tree, \
                    patch.object(candidate, "download", return_value=record()) as download:
                bundle = Path(temporary) / "bundle"
                self.assertEqual(expected, candidate.reuse(REPOSITORY, MERGED, bundle))
                self.assertEqual(3, observe.call_count)
                self.assertEqual(2, tree.call_count)
                if expected == "REUSED":
                    download.assert_called_once_with(REPOSITORY, run, BUILD, bundle)
                else:
                    download.assert_not_called()

    def test_failed_or_incomplete_observation_is_finite_failure(self):
        for result in (subprocess.CompletedProcess([], 1, "", ""),
                       subprocess.CompletedProcess([], 0, "{", "")):
            with self.subTest(result=result), patch.object(candidate.subprocess, "run", return_value=result), \
                    self.assertRaises(candidate.Rejected) as rejection:
                candidate.observe("repos/amichne/kast")
            self.assertIn(rejection.exception.failure, {candidate.Failure.OBSERVATION_FAILED,
                                                        candidate.Failure.INCOMPLETE_OBSERVATION})

    def test_delayed_publication_cannot_rewind_latest(self):
        for observed, expected in ((MERGED, "CURRENT"), (BUILD, "SUPERSEDED")):
            with self.subTest(observed=observed), patch.object(candidate, "observe", return_value={"object": {"sha": observed}}):
                self.assertEqual(expected, candidate.current_main(REPOSITORY, MERGED))



@dataclass(frozen=True)
class ScriptedCall:
    argv: tuple[str, ...]
    stdout: str = ""
    exitCode: int = 0


class PublisherBoundaryTest(unittest.TestCase):
    setUp = PayloadAdmissionTest.setUp

    def publish(self, *, current=True, damaged=False, existing=True):
        import base64
        import os
        root = Path(self.temporary.name).resolve()
        record = root / "promotion.json"
        promotion = candidate.Promotion(1, REPOSITORY, BUILD, MERGED, TREE, VERSION, 123, 1, 456, 1,
                                         candidate.legacy.digest(self.bundle / "candidate.json"))
        record.write_text(json.dumps(asdict(promotion), indent=2) + "\n")
        tag = f"developer-v{VERSION}"
        view = ("release", "view", tag, "--repo", REPOSITORY)
        main = replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main")
        expected = [
            ScriptedCall(("api", f"repos/{REPOSITORY}/actions/runs/456"), json.dumps(asdict(main))),
            ScriptedCall(("api", f"repos/{REPOSITORY}/actions/runs/123"), json.dumps(asdict(Run()))),
            ScriptedCall(("api", f"repos/{REPOSITORY}/git/commits/{MERGED}"), json.dumps({"sha": MERGED, "tree": {"sha": TREE}})),
            ScriptedCall(("api", f"repos/{REPOSITORY}/git/commits/{BUILD}"), json.dumps({"sha": BUILD, "tree": {"sha": TREE}})),
            ScriptedCall(view, "{}", 0 if existing else 1),
        ]
        payload = self.bundle / "payload"
        names = (f"kast-control-v{VERSION}-macos-aarch64.tar.gz", f"kast-ide-hosted-v{VERSION}-idea-262.zip",
                 *(f"kast-{name}-v{VERSION}.zip" for name in ("skill", "plugin", "marketplace")),
                 f"kast-host-release-v{VERSION}.json", "host-installation.py", f"kast-hosted-catalog-v{VERSION}.json",
                 f"kast-module-knowledge-v{VERSION}.json", f"kast-sbom-v{VERSION}.cdx.json")
        files = [path for name in names for path in (payload / name, payload / (name + ".sha256"))]
        files.extend((self.bundle / "candidate.json", record))
        if not existing:
            expected.append(ScriptedCall(("release", "create", tag, *(str(path) for path in files),
                "--repo", REPOSITORY, "--target", BUILD, "--prerelease", "--latest=false", "--title", f"Kast developer {VERSION}",
                "--notes", f"Tested developer build from {BUILD}, promoted after main {MERGED}. Candidate and promotion records, SBOM and checksums are included.")))
        inventory = "\n".join(sorted(f"{path.name}\tsha256:{candidate.legacy.digest(path)}" for path in files))
        expected.extend((ScriptedCall(view + ("--json", "targetCommitish", "--jq", ".targetCommitish"), BUILD),
                         ScriptedCall(view + ("--json", "assets", "--jq", ".assets[] | [.name, .digest] | @tsv"),
                                      "tampered" if damaged else inventory)))
        if not damaged:
            expected.append(ScriptedCall(("api", f"repos/{REPOSITORY}/git/ref/heads/main"),
                                         json.dumps({"object": {"sha": MERGED if current else BUILD}})))
            if current:
                pointer = base64.b64encode(f"{tag} {VERSION} {BUILD}\n".encode()).decode()
                expected.extend((
                    ScriptedCall(("api", f"repos/{REPOSITORY}/git/ref/heads/developer-latest"), "{}"),
                    ScriptedCall(("api", f"repos/{REPOSITORY}/contents/latest.txt?ref=developer-latest", "--jq", ".sha"), "e" * 40),
                    ScriptedCall(("api", "--method", "PUT", f"repos/{REPOSITORY}/contents/latest.txt", "-f",
                                  f"message=chore(distribution): point developer latest to {tag}", "-f", f"content={pointer}",
                                  "-f", "branch=developer-latest", "-f", "sha=" + "e" * 40), "{}"),
                    ScriptedCall(view + ("--json", "tagName,targetCommitish,url,assets"), "{}"),
                ))
        script = root / "gh"
        script.write_text(f'''#!{sys.executable}
import json, pathlib, sys
root = pathlib.Path({str(root)!r})
calls = json.loads((root / "calls.json").read_text())
index = int((root / "index").read_text())
if index >= len(calls) or sys.argv[1:] != calls[index]["argv"]:
    (root / "violation").write_text(str(sys.argv[1:]))
    raise SystemExit(99)
(root / "index").write_text(str(index + 1))
print(calls[index]["stdout"])
raise SystemExit(calls[index]["exitCode"])
''')
        script.chmod(0o755)
        (root / "calls.json").write_text(json.dumps([asdict(item) for item in expected]))
        (root / "index").write_text("0")
        environment = os.environ | {"PATH": str(root) + os.pathsep + os.environ["PATH"],
                                     "GH_TOKEN": "fixture", "GITHUB_REPOSITORY": REPOSITORY}
        result = subprocess.run(["bash", str(candidate.ROOT / ".github/scripts/release/publish-developer.sh"),
                                 "--version", VERSION, "--commit", BUILD, "--assets-directory", str(payload),
                                 "--promotion-record", str(record)], env=environment, capture_output=True, text=True, timeout=30)
        self.assertFalse((root / "violation").exists(), (root / "violation").read_text() if (root / "violation").exists() else result.stderr)
        self.assertEqual(len(expected), int((root / "index").read_text()), result.stdout + result.stderr)
        return result

    def test_existing_publication_retries_without_rebuilding_or_reuploading(self):
        result = self.publish()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_fresh_publication_verifies_uploaded_digests_before_advancing_latest(self):
        result = self.publish(existing=False)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_delayed_publication_preserves_the_latest_pointer(self):
        result = self.publish(current=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("latest retained", result.stdout)

    def test_wrong_remote_digest_blocks_the_pointer(self):
        result = self.publish(damaged=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("developer assets differ", result.stderr)


if __name__ == "__main__":
    unittest.main()
