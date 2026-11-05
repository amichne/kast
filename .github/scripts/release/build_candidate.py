#!/usr/bin/env python3
"""Retain one tested payload and prove its source before byte-preserving promotion."""
from __future__ import annotations

import argparse
import base64
import hashlib
from dataclasses import asdict, dataclass
from enum import Enum
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlencode
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import host_evidence

SPEC = importlib.util.spec_from_file_location("ci_candidate", Path(__file__).with_name("ci-candidate.py"))
legacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(legacy)
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
WORKFLOW = ".github/workflows/ci.yml"
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / ".github/scripts/ci"))
import validation_outcome as validation
import host_targets


class CandidateState(str, Enum):
    REUSED = "REUSED"
    MISSING = "MISSING"


class PublicationState(str, Enum):
    PUBLISH = "PUBLISH"
    DOCS_VALIDATED = "DOCS_VALIDATED"


class MainState(str, Enum):
    CURRENT = "CURRENT"
    SUPERSEDED = "SUPERSEDED"


class ObservationStage(str, Enum):
    ASSOCIATED_PULLS = "ASSOCIATED_PULLS"
    SOURCE_TREE = "SOURCE_TREE"
    WORKFLOW_RUNS = "WORKFLOW_RUNS"
    RUN_ARTIFACTS = "RUN_ARTIFACTS"
    WORKFLOW_RUN = "WORKFLOW_RUN"
    MAIN_HEAD = "MAIN_HEAD"
    SOURCE_PROFILE = "SOURCE_PROFILE"
    SOURCE_RESOURCES = "SOURCE_RESOURCES"


class ObservationOutcome(str, Enum):
    OBSERVED = "OBSERVED"
    HTTP_REJECTED = "HTTP_REJECTED"
    PROCESS_FAILED = "PROCESS_FAILED"
    TIMED_OUT = "TIMED_OUT"
    UNAVAILABLE = "UNAVAILABLE"
    INVALID_RESPONSE = "INVALID_RESPONSE"


@dataclass(frozen=True)
class ObservationEvidence:
    stage: ObservationStage
    outcome: ObservationOutcome
    attempt: int
    httpStatus: int | None


class Failure(str, Enum):
    SOURCE_NOT_QUALIFIED = "SOURCE_NOT_QUALIFIED"
    INVALID_IDENTITY = "INVALID_IDENTITY"
    INVALID_RECORD = "INVALID_RECORD"
    INCOMPLETE_OBSERVATION = "INCOMPLETE_OBSERVATION"
    PRODUCER_NOT_SUCCESSFUL = "PRODUCER_NOT_SUCCESSFUL"
    WRONG_PRODUCER = "WRONG_PRODUCER"
    SOURCE_TREE_MISMATCH = "SOURCE_TREE_MISMATCH"
    PAYLOAD_MISMATCH = "PAYLOAD_MISMATCH"
    DESTINATION_NOT_EMPTY = "DESTINATION_NOT_EMPTY"
    OBSERVATION_FAILED = "OBSERVATION_FAILED"
    DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    GATE_NOT_COMPLETE = "GATE_NOT_COMPLETE"
    SELECTION_MISMATCH = "SELECTION_MISMATCH"
    CHECKOUT_MISMATCH = "CHECKOUT_MISMATCH"


class Rejected(Exception):
    def __init__(self, failure: Failure):
        self.failure = failure
        super().__init__(failure.value)


@dataclass(frozen=True)
class Asset:
    name: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class Toolchain:
    javaVersion: str
    vendor: str
    releaseSha256: str


@dataclass(frozen=True)
class Candidate:
    schemaVersion: int
    repository: str
    sourceRevision: str
    sourceTree: str
    version: str
    runId: int
    runNumber: int
    runAttempt: int
    event: str
    platform: str
    toolchain: Toolchain
    assets: tuple[Asset, ...]


@dataclass(frozen=True)
class Promotion:
    schemaVersion: int
    repository: str
    buildRevision: str
    mergedRevision: str
    sourceTree: str
    version: str
    buildRunId: int
    buildRunAttempt: int
    mainRunId: int
    mainRunAttempt: int
    candidateSha256: str


@dataclass(frozen=True)
class Admitted:
    candidate: Candidate


def identity(repository: str, revision: str) -> None:
    if not REPOSITORY.fullmatch(repository) or not SHA.fullmatch(revision) or revision == "0" * 40:
        raise Rejected(Failure.INVALID_IDENTITY)


def positive(value) -> bool:
    return type(value) is int and value > 0


def unique_object(pairs):
    document = dict(pairs)
    if len(document) != len(pairs):
        raise Rejected(Failure.INVALID_RECORD)
    return document


def decode(document: object) -> Candidate:
    try:
        if not isinstance(document, dict) or set(document) != set(Candidate.__dataclass_fields__):
            raise ValueError()
        toolchain = Toolchain(**document["toolchain"])
        assets = tuple(Asset(**asset) for asset in document["assets"])
        candidate = Candidate(**(document | {"toolchain": toolchain, "assets": assets}))
        identity(candidate.repository, candidate.sourceRevision)
        if (type(candidate.schemaVersion) is not int or candidate.schemaVersion != 1
                or not SHA.fullmatch(candidate.sourceTree) or candidate.sourceTree == "0" * 40
                or not re.fullmatch(r"0\.0\.[1-9][0-9]*", candidate.version)
                or not positive(candidate.runId) or not positive(candidate.runAttempt)
                or not positive(candidate.runNumber) or candidate.runNumber > 2147483647
                or candidate.version != f"0.0.{candidate.runNumber}"
                or candidate.event not in {"pull_request", "merge_group", "push", "workflow_dispatch"}
                or candidate.platform != "macos-aarch64"
                or not DIGEST.fullmatch(toolchain.releaseSha256)
                or not all(isinstance(value, str) and 0 < len(value) <= 160
                           for value in (toolchain.javaVersion, toolchain.vendor))
                or not assets or len(assets) > 32 or len({asset.name for asset in assets}) != len(assets)):
            raise ValueError()
        for asset in assets:
            if (not re.fullmatch(r"[A-Za-z0-9_.-]{1,160}", asset.name)
                    or asset.name in {".", ".."} or not DIGEST.fullmatch(asset.sha256)
                    or not positive(asset.bytes) or asset.bytes > 256 * 1024 * 1024):
                raise ValueError()
        return candidate
    except (TypeError, ValueError, KeyError, Rejected):
        raise Rejected(Failure.INVALID_RECORD) from None


def refine(candidate: Candidate, run: dict, repository: str, tree: str) -> Admitted | Failure:
    """Pure admission: a successful producer and equal source tree retain build identity."""
    if (not isinstance(run, dict) or not isinstance(run.get("repository"), dict)
            or run["repository"].get("full_name") != repository
            or run.get("path") != WORKFLOW or not positive(run.get("id"))
            or not positive(run.get("run_number")) or not positive(run.get("run_attempt"))
            or run.get("id") != candidate.runId
            or run.get("run_number") != candidate.runNumber
            or run.get("run_attempt") != candidate.runAttempt
            or run.get("event") != candidate.event or run.get("head_sha") != candidate.sourceRevision
            or candidate.repository != repository):
        return Failure.WRONG_PRODUCER
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        return Failure.PRODUCER_NOT_SUCCESSFUL
    if candidate.sourceTree != tree:
        return Failure.SOURCE_TREE_MISMATCH
    return Admitted(candidate)


def execute(command: list[str], failure: Failure, **kwargs) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(command, check=False, timeout=180, **kwargs)
    except (OSError, subprocess.TimeoutExpired):
        raise Rejected(failure) from None
    if result.returncode:
        raise Rejected(failure)
    return result


def observe(endpoint: str, stage: ObservationStage):
    for attempt in range(1, 4):
        status = None
        retry = False
        try:
            result = subprocess.run(["gh", "api", "--include", endpoint], check=False, timeout=180,
                                    capture_output=True, text=True)
            headers, separator, payload = result.stdout.replace("\r\n", "\n").partition("\n\n")
            match = re.match(r"HTTP/\S+ ([1-5][0-9]{2})\b", headers)
            status = int(match[1]) if match else None
            if status is None or not separator:
                outcome = ObservationOutcome.PROCESS_FAILED if result.returncode else ObservationOutcome.INVALID_RESPONSE
            elif result.returncode or not 200 <= status < 300:
                outcome = ObservationOutcome.HTTP_REJECTED
                retry = status in {408, 429} or 500 <= status < 600
            else:
                try:
                    document = json.loads(payload)
                    outcome = ObservationOutcome.OBSERVED
                except ValueError:
                    outcome = ObservationOutcome.INVALID_RESPONSE
        except subprocess.TimeoutExpired:
            outcome = ObservationOutcome.TIMED_OUT
            retry = True
        except OSError:
            outcome = ObservationOutcome.UNAVAILABLE
        # Do not retain response bodies, headers, endpoints or process stderr.
        print(json.dumps(asdict(ObservationEvidence(stage, outcome, attempt, status))), file=sys.stderr, flush=True)
        if outcome is ObservationOutcome.OBSERVED:
            return document
        if outcome is ObservationOutcome.INVALID_RESPONSE:
            raise Rejected(Failure.INCOMPLETE_OBSERVATION)
        if not retry or attempt == 3:
            raise Rejected(Failure.OBSERVATION_FAILED)
        time.sleep(attempt)


def listing(document: dict, field: str) -> tuple[dict, ...]:
    if (not isinstance(document, dict) or type(document.get("total_count")) is not int
            or not isinstance(document.get(field), list)
            or document["total_count"] != len(document[field])
            or any(not isinstance(value, dict) for value in document[field])):
        raise Rejected(Failure.INCOMPLETE_OBSERVATION)
    return tuple(document[field])


def tree(repository: str, revision: str) -> str:
    document = observe(f"repos/{repository}/git/commits/{revision}", ObservationStage.SOURCE_TREE)
    value = document.get("tree", {}).get("sha") if isinstance(document, dict) else None
    if not isinstance(value, str) or not SHA.fullmatch(value) or document.get("sha") != revision:
        raise Rejected(Failure.INCOMPLETE_OBSERVATION)
    return value


def read(bundle: Path) -> Candidate:
    record = bundle / "candidate.json"
    if (bundle.is_symlink() or not bundle.is_dir() or {path.name for path in bundle.iterdir()} != {"candidate.json", "payload"}
            or record.is_symlink() or not record.is_file() or record.stat().st_size > 65536):
        raise Rejected(Failure.INVALID_RECORD)
    try:
        candidate = decode(json.loads(record.read_text(), object_pairs_hook=unique_object))
    except (OSError, ValueError):
        raise Rejected(Failure.INVALID_RECORD) from None
    payload = bundle / "payload"
    try:
        legacy.validate(payload, candidate.version, candidate.sourceRevision)
        observed = tuple(Asset(path.name, legacy.digest(path), path.stat().st_size)
                         for path in sorted(payload.iterdir()))
    except (legacy.CandidateError, OSError):
        raise Rejected(Failure.PAYLOAD_MISMATCH) from None
    if observed != candidate.assets:
        raise Rejected(Failure.PAYLOAD_MISMATCH)
    return candidate


def source_bytes(repository: str, revision: str, path: str, stage: ObservationStage) -> bytes:
    identity(repository, revision)
    document = observe(
        f"repos/{repository}/contents/{path}?ref={revision}", stage,
    )
    if (not isinstance(document, dict) or document.get("type") != "file"
            or document.get("encoding") != "base64" or not isinstance(document.get("content"), str)
            or len(document["content"]) > 90_000
            or type(document.get("size")) is not int or not 1 <= document["size"] <= 65536):
        raise Rejected(Failure.INVALID_RECORD)
    try:
        raw = base64.b64decode(document["content"].replace("\n", ""), validate=True)
        if len(raw) != document["size"]:
            raise ValueError()
    except (ValueError, UnicodeError, RecursionError):
        raise Rejected(Failure.INVALID_RECORD) from None
    return raw


def source_profile(repository: str, revision: str) -> host_targets.Profile:
    """Read target pins from immutable build source, never from a newer publisher checkout."""
    raw = source_bytes(repository, revision, "config/intellij-targets.json", ObservationStage.SOURCE_PROFILE)
    try:
        profile = host_targets.decode_profile(json.loads(raw, object_pairs_hook=unique_object))
    except (ValueError, UnicodeError, RecursionError):
        raise Rejected(Failure.INVALID_RECORD) from None
    if isinstance(profile, host_targets.Failure):
        raise Rejected(Failure.INVALID_RECORD)
    return profile


def source_resources(repository: str, revision: str) -> host_evidence.SourceResources:
    import host_evidence
    return host_evidence.SourceResources(*(
        "sha256:" + hashlib.sha256(source_bytes(repository, revision, path, ObservationStage.SOURCE_RESOURCES)).hexdigest()
        for path in (host_evidence.QUERY_SCHEMA_PATH, host_evidence.OPERATION_REGISTRY_PATH)
    ))


def read_retained(bundle: Path, expected_profile: host_targets.Profile | None = None,
                  expected_resources: host_evidence.SourceResources | None = None) -> Candidate:
    """Keep the historical reader strict; admit paired bundles at a separate boundary."""
    if not (bundle / "candidate-v2.json").exists() and not (bundle / "candidate-v2.json").is_symlink():
        return read(bundle)
    import dual_candidate
    import host_evidence
    pair = dual_candidate.read(bundle)
    if isinstance(pair, dual_candidate.Failure):
        raise Rejected(Failure.PAYLOAD_MISMATCH)
    profile = expected_profile if expected_profile is not None else source_profile(
        pair.current.repository, pair.current.sourceRevision,
    )
    resources = expected_resources if expected_resources is not None else source_resources(
        pair.current.repository, pair.current.sourceRevision,
    )
    if (isinstance(host_evidence.admit(bundle / "payload", pair.current, profile, resources), dual_candidate.Failure)
            or isinstance(dual_candidate.admit_sbom(bundle / "payload", pair.current), dual_candidate.Failure)):
        raise Rejected(Failure.PAYLOAD_MISMATCH)
    return pair.legacy


def empty(bundle: Path) -> None:
    if bundle.is_symlink() or (bundle.exists() and (not bundle.is_dir() or any(bundle.iterdir()))):
        raise Rejected(Failure.DESTINATION_NOT_EMPTY)


def download(repository: str, run: dict, revision: str, bundle: Path) -> Candidate:
    empty(bundle)
    name = f"ci-build-candidate-{revision}-{run['run_attempt']}"
    artifacts = listing(observe(f"repos/{repository}/actions/runs/{run['id']}/artifacts?per_page=100", ObservationStage.RUN_ARTIFACTS), "artifacts")
    matches = tuple(artifact for artifact in artifacts if artifact.get("name") == name and artifact.get("expired") is False)
    if len(matches) != 1:
        raise Rejected(Failure.INVALID_RECORD)
    bundle.mkdir(parents=True, exist_ok=True)
    execute(["gh", "run", "download", str(run["id"]), "--repo", repository, "--name", name,
             "--dir", str(bundle)], Failure.DOWNLOAD_FAILED)
    return read_retained(bundle)


def reusable_run(run: dict, repository: str, revision: str) -> bool:
    return (isinstance(run, dict) and isinstance(run.get("repository"), dict)
            and run["repository"].get("full_name") == repository and run.get("path") == WORKFLOW
            and run.get("head_sha") == revision and run.get("status") == "completed"
            and run.get("conclusion") == "success" and positive(run.get("id")) and positive(run.get("run_attempt")))


def reuse(repository: str, revision: str, bundle: Path) -> CandidateState:
    """Only same-repository merged PRs authorize pre-merge payload reuse."""
    identity(repository, revision)
    empty(bundle)
    pulls = observe(f"repos/{repository}/commits/{revision}/pulls?per_page=100", ObservationStage.ASSOCIATED_PULLS)
    if not isinstance(pulls, list) or len(pulls) >= 100 or any(not isinstance(pull, dict) for pull in pulls):
        raise Rejected(Failure.INCOMPLETE_OBSERVATION)
    sources = tuple(pull["head"]["sha"] for pull in pulls
                    if pull.get("merged_at") and pull.get("merge_commit_sha") == revision
                    and isinstance(pull.get("head"), dict)
                    and isinstance(pull["head"].get("repo"), dict)
                    and pull["head"]["repo"].get("full_name") == repository)
    expected_tree = tree(repository, revision)
    for source in sources:
        identity(repository, source)
        query = urlencode({"head_sha": source, "event": "pull_request", "per_page": 100})
        runs = listing(observe(f"repos/{repository}/actions/workflows/ci.yml/runs?{query}", ObservationStage.WORKFLOW_RUNS), "workflow_runs")
        for run in sorted(runs, key=lambda value: value.get("id", 0), reverse=True):
            if not reusable_run(run, repository, source):
                continue
            artifacts = listing(observe(f"repos/{repository}/actions/runs/{run['id']}/artifacts?per_page=100", ObservationStage.RUN_ARTIFACTS), "artifacts")
            if not any(item.get("name") == f"ci-build-candidate-{source}-{run['run_attempt']}" and item.get("expired") is False for item in artifacts):
                continue
            # A changed integration tree authorizes a fresh build, never promotion.
            if tree(repository, source) != expected_tree:
                continue
            candidate = download(repository, run, source, bundle)
            admitted = refine(candidate, run, repository, expected_tree)
            if isinstance(admitted, Failure):
                raise Rejected(admitted)
            return CandidateState.REUSED
    return CandidateState.MISSING


def seal(repository: str, revision: str, run_id: int, number: int, attempt: int, event: str, bundle: Path) -> Candidate:
    identity(repository, revision)
    if not positive(run_id) or not positive(number) or number > 2147483647 or not positive(attempt) or platform.system() != "Darwin" or platform.machine() != "arm64":
        raise Rejected(Failure.UNSUPPORTED_PLATFORM)
    empty(bundle)
    execute(["bash", ".github/scripts/release/admit-source.sh", "--repository-root", str(ROOT),
             "--expected-source-revision", revision], Failure.INVALID_IDENTITY, cwd=ROOT)
    version = f"0.0.{number}"
    payload = ROOT / f"build/release/v{version}"
    import dual_candidate
    import host_evidence
    import host_targets
    import developer_catalog
    profile = host_targets.decode_profile(dual_candidate.read_json(ROOT / "config/intellij-targets.json"))
    if isinstance(profile, host_targets.Failure):
        raise Rejected(Failure.INVALID_RECORD)
    if {path.name for path in payload.iterdir()} != set(dual_candidate.names(version, True)):
        raise Rejected(Failure.PAYLOAD_MISMATCH)
    release = Path(os.environ["JAVA_HOME"]) / "release"
    properties = dict(re.findall(r'^([A-Z_]+)="([^\n"]*)"$', release.read_text(), re.MULTILINE))
    local_tree = execute(["git", "rev-parse", "HEAD^{tree}"], Failure.OBSERVATION_FAILED,
                         cwd=ROOT, text=True, capture_output=True).stdout.strip()
    current = dual_candidate.Candidate(2, repository, revision, local_tree, version, run_id, number, attempt, event,
                          "macos-aarch64", Toolchain(properties["JAVA_VERSION"], properties["IMPLEMENTOR"], legacy.digest(release)),
                          tuple(Asset(path.name, legacy.digest(path), path.stat().st_size) for path in sorted(payload.iterdir())),
                          tuple(host_targets.HostTarget(target.intellijReleaseLine, target.ideBuild, target.kotlinPluginBuild,
                                developer_catalog.RecordReference(host_targets.record_name(version, target.intellijReleaseLine),
                                    legacy.digest(payload / host_targets.record_name(version, target.intellijReleaseLine))))
                                for target in profile.targets))
    candidate = dual_candidate.projection(current)
    decode(asdict(candidate))
    resources = host_evidence.source_resources(ROOT)
    if (isinstance(dual_candidate.admit_pair(candidate, current), dual_candidate.Failure)
            or isinstance(host_evidence.admit(payload, current, profile, resources), dual_candidate.Failure)
            or isinstance(dual_candidate.admit_sbom(payload, current), dual_candidate.Failure)):
        raise Rejected(Failure.PAYLOAD_MISMATCH)
    bundle.mkdir(parents=True, exist_ok=True)
    shutil.copytree(payload, bundle / "payload")
    (bundle / "candidate.json").write_text(json.dumps(asdict(candidate), indent=2) + "\n")
    (bundle / "candidate-v2.json").write_text(json.dumps(asdict(current), indent=2) + "\n")
    read_retained(bundle, profile, resources)
    return candidate


def admit_validation(repository: str, revision: str, run: dict, expected_tree: str):
    name = f"ci-validation-outcome-{revision}-{run['run_attempt']}"
    artifacts = listing(observe(f"repos/{repository}/actions/runs/{run['id']}/artifacts?per_page=100", ObservationStage.RUN_ARTIFACTS), "artifacts")
    matches = tuple(artifact for artifact in artifacts if artifact.get("name") == name and artifact.get("expired") is False)
    if len(matches) != 1:
        raise Rejected(Failure.INVALID_RECORD)
    with tempfile.TemporaryDirectory(prefix="kast-validation-") as temporary:
        destination = Path(temporary)
        execute(["gh", "run", "download", str(run["id"]), "--repo", repository, "--name", name,
                 "--dir", str(destination)], Failure.DOWNLOAD_FAILED)
        if {path.name for path in destination.iterdir()} != {"validation.json"}:
            raise Rejected(Failure.INVALID_RECORD)
        outcome = validation.read(destination / "validation.json")
    if isinstance(outcome, validation.Failure):
        raise Rejected(Failure(outcome.value))
    admitted = validation.admit(outcome, run, repository, revision, expected_tree)
    if isinstance(admitted, validation.Failure):
        raise Rejected(Failure(admitted.value))
    if isinstance(admitted, validation.DocumentationValidated) and any(
        artifact.get("expired") is False and str(artifact.get("name", "")).startswith("ci-build-candidate-")
        for artifact in artifacts
    ):
        raise Rejected(Failure.INVALID_RECORD)
    return admitted


def promote(repository: str, revision: str, main_run_id: int, bundle: Path, record: Path) -> Candidate | validation.DocumentationValidated:
    identity(repository, revision)
    if not positive(main_run_id):
        raise Rejected(Failure.INVALID_IDENTITY)
    run = observe(f"repos/{repository}/actions/runs/{main_run_id}", ObservationStage.WORKFLOW_RUN)
    if not reusable_run(run, repository, revision) or run.get("event") != "push" or run.get("head_branch") != "main":
        raise Rejected(Failure.PRODUCER_NOT_SUCCESSFUL)
    expected_tree = tree(repository, revision)
    outcome = admit_validation(repository, revision, run, expected_tree)
    if isinstance(outcome, validation.DocumentationValidated):
        empty(bundle)
        if record.exists() or record.is_symlink():
            raise Rejected(Failure.DESTINATION_NOT_EMPTY)
        return outcome
    if isinstance(outcome, validation.SourceValidated):
        raise Rejected(Failure.SOURCE_NOT_QUALIFIED)
    if not isinstance(outcome, validation.ProductValidated):
        raise Rejected(Failure.INVALID_RECORD)
    candidate = download(repository, run, revision, bundle)
    producer = observe(f"repos/{repository}/actions/runs/{candidate.runId}", ObservationStage.WORKFLOW_RUN)
    admitted = refine(candidate, producer, repository, expected_tree)
    if isinstance(admitted, Failure):
        raise Rejected(admitted)
    if tree(repository, candidate.sourceRevision) != candidate.sourceTree:
        raise Rejected(Failure.SOURCE_TREE_MISMATCH)
    promotion = Promotion(1, repository, candidate.sourceRevision, revision, candidate.sourceTree, candidate.version,
                          candidate.runId, candidate.runAttempt, main_run_id, run["run_attempt"],
                          legacy.digest(bundle / "candidate.json"))
    record.parent.mkdir(parents=True, exist_ok=True)
    record.write_text(json.dumps(asdict(promotion), indent=2) + "\n")
    if (bundle / "candidate-v2.json").exists():
        from dataclasses import replace
        current = replace(promotion, schemaVersion=2, candidateSha256=legacy.digest(bundle / "candidate-v2.json"))
        record.with_name("promotion-v2.json").write_text(json.dumps(asdict(current), indent=2) + "\n")
    return candidate


def verify_publication(repository: str, revision: str, version: str, bundle: Path, record: Path) -> Promotion:
    candidate = read_retained(bundle)
    if record.is_symlink() or not record.is_file() or record.stat().st_size > 4096:
        raise Rejected(Failure.INVALID_RECORD)
    try:
        document = json.loads(record.read_text(), object_pairs_hook=unique_object)
        promotion = Promotion(**document)
    except (TypeError, ValueError):
        raise Rejected(Failure.INVALID_RECORD) from None
    if (type(promotion.schemaVersion) is not int or promotion.schemaVersion != 1
            or promotion.repository != repository or candidate.repository != repository
            or promotion.buildRevision != revision or candidate.sourceRevision != revision
            or promotion.version != version or candidate.version != version
            or promotion.sourceTree != candidate.sourceTree
            or not positive(promotion.buildRunId) or not positive(promotion.buildRunAttempt)
            or promotion.buildRunId != candidate.runId or promotion.buildRunAttempt != candidate.runAttempt
            or promotion.candidateSha256 != legacy.digest(bundle / "candidate.json")
            or not positive(promotion.mainRunId) or not positive(promotion.mainRunAttempt)):
        raise Rejected(Failure.INVALID_RECORD)
    identity(repository, promotion.mergedRevision)
    main_run = observe(f"repos/{repository}/actions/runs/{promotion.mainRunId}", ObservationStage.WORKFLOW_RUN)
    if (not reusable_run(main_run, repository, promotion.mergedRevision)
            or main_run.get("event") != "push" or main_run.get("head_branch") != "main"
            or main_run.get("run_attempt") != promotion.mainRunAttempt):
        raise Rejected(Failure.PRODUCER_NOT_SUCCESSFUL)
    expected_tree = tree(repository, promotion.mergedRevision)
    if not isinstance(admit_validation(repository, promotion.mergedRevision, main_run, expected_tree), validation.ProductValidated):
        raise Rejected(Failure.INVALID_RECORD)
    producer = observe(f"repos/{repository}/actions/runs/{candidate.runId}", ObservationStage.WORKFLOW_RUN)
    admitted = refine(candidate, producer, repository, expected_tree)
    if isinstance(admitted, Failure):
        raise Rejected(admitted)
    if tree(repository, candidate.sourceRevision) != candidate.sourceTree:
        raise Rejected(Failure.SOURCE_TREE_MISMATCH)
    return promotion


def current_main(repository: str, revision: str) -> MainState:
    identity(repository, revision)
    document = observe(f"repos/{repository}/git/ref/heads/main", ObservationStage.MAIN_HEAD)
    observed = document.get("object", {}).get("sha") if isinstance(document, dict) else None
    if not isinstance(observed, str) or not SHA.fullmatch(observed):
        raise Rejected(Failure.INCOMPLETE_OBSERVATION)
    return MainState.CURRENT if observed == revision else MainState.SUPERSEDED


def output(path: str | None, **values: str) -> None:
    if path:
        with Path(path).open("a") as destination:
            for name, value in values.items():
                destination.write(f"{name}={value}\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("seal", "reuse", "promote", "verify-publication", "current-main"))
    parser.add_argument("--repository", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--run-number", type=int)
    parser.add_argument("--run-attempt", type=int)
    parser.add_argument("--event")
    parser.add_argument("--version")
    parser.add_argument("--promotion-record", type=Path)
    parser.add_argument("--github-output")
    args = parser.parse_args()
    try:
        if args.action == "current-main":
            print(current_main(args.repository, args.revision).value)
            return
        if args.bundle is None:
            raise Rejected(Failure.INVALID_IDENTITY)
        if args.action == "verify-publication":
            if args.promotion_record is None:
                raise Rejected(Failure.INVALID_IDENTITY)
            promotion = verify_publication(args.repository, args.revision, args.version, args.bundle, args.promotion_record)
            print(promotion.mergedRevision)
            return
        if args.action == "reuse":
            state = reuse(args.repository, args.revision, args.bundle)
            output(args.github_output, candidate=state.value)
            print(f"build-candidate: {state.value}")
        else:
            if args.action == "seal":
                candidate = seal(args.repository, args.revision, args.run_id, args.run_number, args.run_attempt, args.event, args.bundle)
            else:
                if args.promotion_record is None:
                    raise Rejected(Failure.INVALID_IDENTITY)
                candidate = promote(args.repository, args.revision, args.run_id, args.bundle, args.promotion_record)
            if isinstance(candidate, validation.DocumentationValidated):
                output(args.github_output, publication=PublicationState.DOCS_VALIDATED.value)
                print("build-candidate: DOCS_VALIDATED; no product publication")
            else:
                output(args.github_output, publication=PublicationState.PUBLISH.value,
                       version=candidate.version, source_revision=candidate.sourceRevision)
                print("build-candidate: ADMITTED")
    except Rejected as rejection:
        raise SystemExit(f"build-candidate: {rejection.failure.value}") from None
    except (OSError, KeyError, TypeError, ValueError, AttributeError, legacy.CandidateError):
        raise SystemExit(f"build-candidate: {Failure.INVALID_RECORD.value}") from None


if __name__ == "__main__":
    main()
