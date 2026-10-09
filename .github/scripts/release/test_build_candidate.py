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

    def test_boolean_attempt_in_promotion_rejects_before_external_observation(self):
        promotion = Path(self.temporary.name) / "promotion.json"
        malformed = candidate.Promotion(1, REPOSITORY, BUILD, MERGED, TREE, VERSION, 123, True, 456, 1,
                                        candidate.legacy.digest(self.bundle / "candidate.json"))
        promotion.write_text(json.dumps(asdict(malformed)))
        with patch.object(candidate, "observe") as observe, self.assertRaises(candidate.Rejected) as rejection:
            candidate.verify_publication(REPOSITORY, BUILD, VERSION, self.bundle, promotion)
        observe.assert_not_called()
        self.assertEqual(candidate.Failure.INVALID_RECORD, rejection.exception.failure)

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



if __name__ == "__main__":
    unittest.main()
