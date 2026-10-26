#!/usr/bin/env python3
"""Candidate admission preserves build identity and rejects unproven promotion."""
from contextlib import redirect_stderr
from dataclasses import asdict, dataclass, replace
import io
import json
from pathlib import Path
import subprocess
import tempfile
import sys
import unittest
from unittest.mock import call, patch

import build_candidate as candidate
from validation_outcome import DocumentationValidated, Outcome, ProductValidated, SourceValidated, ValidationRecord
from validation_scope import Scope

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


def validation_record(scope=Scope.FULL):
    return ValidationRecord(1, Outcome.DOCS_VALIDATED if scope is Scope.DOCUMENTATION else Outcome.PRODUCT_VALIDATED,
                            scope, REPOSITORY, MERGED, TREE, BUILD, 456, 123, 1, "push")


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
                 *(f"kast-{name}-v{VERSION}.zip" for name in ("plugin", "marketplace")),
                 f"kast-host-release-v{VERSION}.json", "host-installation.py", f"kast-hosted-catalog-v{VERSION}.json",
                 f"kast-module-knowledge-v{VERSION}.json", f"kast-sbom-v{VERSION}.cdx.json")
        for name in names[:-1]:
            (payload / name).write_bytes(name.encode())
        sbom = Sbom("CycloneDX", Metadata((Property("kast:source-revision", BUILD),
                     *(Property(f"kast:archive:{name}", "sha256:" + candidate.legacy.digest(payload / name))
                       for name in names[:4]))))
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
                patch.object(candidate, "admit_validation", return_value=ProductValidated(validation_record())), \
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

    def test_source_validation_rejects_before_candidate_download_or_promotion_record(self):
        destination = Path(self.temporary.name) / "source-download"
        promotion = Path(self.temporary.name) / "source-promotion.json"
        main = replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main")
        source = SourceValidated(replace(validation_record(), scope=Scope.SOURCE, outcome=Outcome.SOURCE_VALIDATED))
        with patch.object(candidate, "observe", side_effect=[asdict(main), asdict(Run())]), \
                patch.object(candidate, "admit_validation", return_value=source), \
                patch.object(candidate, "download", return_value=self.value) as download, \
                patch.object(candidate, "tree", return_value=TREE), \
                patch.object(candidate.legacy, "digest", return_value="f" * 64), \
                self.assertRaises(candidate.Rejected) as rejected:
            candidate.promote(REPOSITORY, MERGED, 456, destination, promotion)
        self.assertEqual("SOURCE_NOT_QUALIFIED", rejected.exception.failure.value)
        download.assert_not_called()
        self.assertFalse(promotion.exists())

    def test_boolean_attempt_in_promotion_rejects_before_external_observation(self):
        promotion = Path(self.temporary.name) / "promotion.json"
        malformed = candidate.Promotion(1, REPOSITORY, BUILD, MERGED, TREE, VERSION, 123, True, 456, 1,
                                        candidate.legacy.digest(self.bundle / "candidate.json"))
        promotion.write_text(json.dumps(asdict(malformed)))
        with patch.object(candidate, "observe") as observe, self.assertRaises(candidate.Rejected) as rejection:
            candidate.verify_publication(REPOSITORY, BUILD, VERSION, self.bundle, promotion)
        observe.assert_not_called()
        self.assertEqual(candidate.Failure.INVALID_RECORD, rejection.exception.failure)

    def test_docs_validation_cannot_authorize_a_forged_product_promotion(self):
        promotion = Path(self.temporary.name) / "promotion.json"
        value = candidate.Promotion(1, REPOSITORY, BUILD, MERGED, TREE, VERSION, 123, 1, 456, 1,
                                    candidate.legacy.digest(self.bundle / "candidate.json"))
        promotion.write_text(json.dumps(asdict(value)))
        main = replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main")
        with patch.object(candidate, "observe", return_value=asdict(main)), \
                patch.object(candidate, "tree", return_value=TREE), \
                patch.object(candidate, "admit_validation", return_value=DocumentationValidated(validation_record(Scope.DOCUMENTATION))), \
                self.assertRaises(candidate.Rejected) as rejection:
            candidate.verify_publication(REPOSITORY, BUILD, VERSION, self.bundle, promotion)
        self.assertEqual(candidate.Failure.INVALID_RECORD, rejection.exception.failure)

    def test_successful_main_without_validation_outcome_never_promotes(self):
        main = replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main")
        destination = Path(self.temporary.name) / "download"
        promotion = Path(self.temporary.name) / "promotion.json"
        with patch.object(candidate, "observe", side_effect=[asdict(main),
                asdict(Run()) | {"total_count": 0, "artifacts": []}, asdict(Run())]), \
                patch.object(candidate, "download", return_value=self.value), \
                patch.object(candidate, "tree", return_value=TREE), \
                patch.object(candidate.legacy, "digest", return_value="f" * 64), \
                self.assertRaises(candidate.Rejected):
            candidate.promote(REPOSITORY, MERGED, 456, destination, promotion)
        self.assertFalse(promotion.exists())

    def test_docs_main_has_no_candidate_download_promotion_record_or_binary_identity(self):
        main = replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main")
        destination = Path(self.temporary.name) / "download"
        promotion = Path(self.temporary.name) / "promotion.json"
        docs = DocumentationValidated(validation_record(Scope.DOCUMENTATION))
        with patch.object(candidate, "observe", return_value=asdict(main)), \
                patch.object(candidate, "tree", return_value=TREE), \
                patch.object(candidate, "admit_validation", return_value=docs), \
                patch.object(candidate, "download") as download:
            self.assertEqual(docs, candidate.promote(REPOSITORY, MERGED, 456, destination, promotion))
        download.assert_not_called()
        self.assertFalse(destination.exists())
        self.assertFalse(promotion.exists())

    def test_cli_docs_result_emits_only_explicit_no_publication(self):
        output = Path(self.temporary.name) / "github-output"
        arguments = ["build_candidate.py", "promote", "--repository", REPOSITORY, "--revision", MERGED,
                     "--run-id", "456", "--bundle", str(self.bundle), "--promotion-record", "unused",
                     "--github-output", str(output)]
        with patch.object(sys, "argv", arguments), patch.object(candidate, "promote", return_value=
                DocumentationValidated(validation_record(Scope.DOCUMENTATION))):
            candidate.main()
        self.assertEqual("publication=DOCS_VALIDATED\n", output.read_text())

    def test_failed_main_never_downloads_or_writes_a_promotion(self):
        with patch.object(candidate, "observe", return_value=asdict(replace(Run(), conclusion="failure"))), \
                patch.object(candidate, "download") as download, self.assertRaises(candidate.Rejected):
            candidate.promote(REPOSITORY, MERGED, 456, Path(self.temporary.name) / "download",
                              Path(self.temporary.name) / "promotion.json")
        download.assert_not_called()
        self.assertFalse((Path(self.temporary.name) / "promotion.json").exists())


class EffectBoundaryTest(unittest.TestCase):
    def test_retained_outcome_is_bound_to_exact_main_artifact_and_attempt(self):
        main = asdict(replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main"))
        expected_name = f"ci-validation-outcome-{MERGED}-1"
        def download(command, *arguments, **keywords):
            self.assertEqual(["gh", "run", "download", "456", "--repo", REPOSITORY,
                              "--name", expected_name, "--dir"], command[:-1])
            (Path(command[-1]) / "validation.json").write_text(json.dumps(asdict(validation_record(Scope.DOCUMENTATION))))
        with patch.object(candidate, "observe", return_value={"total_count": 1, "artifacts": [
                {"name": expected_name, "expired": False}]}), patch.object(candidate, "execute", side_effect=download):
            self.assertEqual(DocumentationValidated(validation_record(Scope.DOCUMENTATION)),
                             candidate.admit_validation(REPOSITORY, MERGED, main, TREE))

    def test_missing_wrong_attempt_expired_duplicate_and_docs_with_candidate_reject(self):
        main = asdict(replace(Run(), id=456, event="push", head_sha=MERGED, head_branch="main"))
        artifact = {"name": f"ci-validation-outcome-{MERGED}-1", "expired": False}
        cases = ([], [artifact | {"name": f"ci-validation-outcome-{MERGED}-2"}],
                 [artifact | {"expired": True}], [artifact, artifact],
                 [artifact, {"name": f"ci-build-candidate-{MERGED}-1", "expired": False}])
        for artifacts in cases:
            with self.subTest(artifacts=artifacts), patch.object(candidate, "observe", return_value={
                    "total_count": len(artifacts), "artifacts": artifacts}), \
                    patch.object(candidate, "execute") as execute, \
                    patch.object(candidate.validation, "read", return_value=validation_record(Scope.DOCUMENTATION)), \
                    patch.object(candidate.Path, "iterdir", return_value=iter([Path("validation.json")])), \
                    self.assertRaises(candidate.Rejected):
                candidate.admit_validation(REPOSITORY, MERGED, main, TREE)
            if len(artifacts) != 2 or artifacts[0] == artifacts[1]:
                execute.assert_not_called()

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

    def test_transient_api_failure_retries_and_retains_bounded_stage_evidence(self):
        responses = [subprocess.CompletedProcess([], 1, "HTTP/2.0 503 Service Unavailable\r\n\r\nsecret body", "secret error"),
                     subprocess.CompletedProcess([], 0, "HTTP/2.0 200 OK\r\n\r\n" + json.dumps(asdict(Run())), "")]
        evidence = io.StringIO()
        with patch.object(candidate.subprocess, "run", side_effect=responses) as execute, \
                patch.object(candidate.time, "sleep"), redirect_stderr(evidence):
            self.assertEqual(asdict(Run()), candidate.observe("repos/amichne/kast/actions/runs/123",
                                                            candidate.ObservationStage.WORKFLOW_RUN))
        self.assertEqual(len(responses), execute.call_count)
        self.assertEqual([
            {"stage": "WORKFLOW_RUN", "outcome": "HTTP_REJECTED", "attempt": 1, "httpStatus": 503},
            {"stage": "WORKFLOW_RUN", "outcome": "OBSERVED", "attempt": 2, "httpStatus": 200},
        ], [json.loads(line) for line in evidence.getvalue().splitlines()])

    def test_api_rejection_never_becomes_a_missing_candidate(self):
        for status, attempts in ((403, 1), (404, 1), (429, 3), (502, 3)):
            responses = [subprocess.CompletedProcess([], 1, f"HTTP/2.0 {status} Rejected\n\nsecret", "secret")] * attempts
            evidence = io.StringIO()
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(candidate.subprocess, "run", side_effect=responses) as execute, \
                    patch.object(candidate.time, "sleep"), redirect_stderr(evidence), \
                    self.assertRaises(candidate.Rejected) as rejection:
                candidate.reuse(REPOSITORY, MERGED, Path(temporary) / "bundle")
            self.assertEqual(candidate.Failure.OBSERVATION_FAILED, rejection.exception.failure)
            self.assertEqual(attempts, execute.call_count)
            self.assertEqual([
                {"stage": "ASSOCIATED_PULLS", "outcome": "HTTP_REJECTED", "attempt": attempt, "httpStatus": status}
                for attempt in range(1, attempts + 1)
            ], [json.loads(line) for line in evidence.getvalue().splitlines()])

    def test_timeout_recovers_but_unavailable_process_and_invalid_response_reject(self):
        success = subprocess.CompletedProcess([], 0, "HTTP/2.0 200 OK\n\n" + json.dumps(asdict(Run())), "")
        evidence = io.StringIO()
        with patch.object(candidate.subprocess, "run", side_effect=[subprocess.TimeoutExpired("gh", 180), success]) as execute, \
                patch.object(candidate.time, "sleep"), redirect_stderr(evidence):
            self.assertEqual(asdict(Run()), candidate.observe("unused", candidate.ObservationStage.WORKFLOW_RUN))
        self.assertEqual(2, execute.call_count)
        self.assertEqual("TIMED_OUT", json.loads(evidence.getvalue().splitlines()[0])["outcome"])
        for response, failure, outcome in (
                (OSError("secret"), candidate.Failure.OBSERVATION_FAILED, "UNAVAILABLE"),
                (subprocess.CompletedProcess([], 1, "", "secret"),
                 candidate.Failure.OBSERVATION_FAILED, "PROCESS_FAILED"),
                (subprocess.CompletedProcess([], 0, "HTTP/2.0 200 OK\n\n{", ""),
                 candidate.Failure.INCOMPLETE_OBSERVATION, "INVALID_RESPONSE"),
                (subprocess.CompletedProcess([], 0, "{}", ""),
                 candidate.Failure.INCOMPLETE_OBSERVATION, "INVALID_RESPONSE")):
            evidence = io.StringIO()
            with self.subTest(outcome=outcome), patch.object(candidate.subprocess, "run", side_effect=[response]) as execute, \
                    redirect_stderr(evidence), self.assertRaises(candidate.Rejected) as rejection:
                candidate.observe("unused", candidate.ObservationStage.WORKFLOW_RUN)
            self.assertEqual(failure, rejection.exception.failure)
            self.assertEqual(1, execute.call_count)
            self.assertEqual(outcome, json.loads(evidence.getvalue())["outcome"])
            self.assertNotIn("secret", evidence.getvalue())

    def test_delayed_publication_cannot_rewind_latest(self):
        for observed, expected in ((MERGED, "CURRENT"), (BUILD, "SUPERSEDED")):
            with self.subTest(observed=observed), patch.object(candidate, "observe", return_value={"object": {"sha": observed}}):
                self.assertEqual(expected, candidate.current_main(REPOSITORY, MERGED))



if __name__ == "__main__":
    unittest.main()
