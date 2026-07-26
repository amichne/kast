"""Proof-carrying benchmark capture provenance."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import tempfile
from contextlib import closing
from dataclasses import dataclass
from enum import Enum
from pathlib import Path


CAPTURE_SCHEMA_VERSION = 3
PROVENANCE_SCHEMA_VERSION = 1
GRAPHIFY_QUERY_CONFIGURATION = {
    "budgetTokens": 1200,
    "defaultTraversal": "bfs",
    "pathTraversal": "dfs",
}
GRAPHIFY_PROCESS_ENVIRONMENT = {
    "base": "empty",
    "set": {
        "PYTHONHASHSEED": "0",
        "PYTHONNOUSERSITE": "1",
        "PYTHONSAFEPATH": "1",
    },
    "remove": [],
    "isolatedHomeAndWorkingDirectory": True,
}
CORPUS_INPUT_EXCLUDED_DIRECTORIES = {
    ".git",
    ".gradle",
    "build",
    "graphify-out",
    "target",
}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class ProvenanceFailureCode(str, Enum):
    KAST_CAPTURE_DOCUMENT_MISMATCH = "KAST_CAPTURE_DOCUMENT_MISMATCH"
    GRAPHIFY_CAPTURE_DOCUMENT_MISMATCH = "GRAPHIFY_CAPTURE_DOCUMENT_MISMATCH"
    CAPTURE_SCHEMA_MISMATCH = "CAPTURE_SCHEMA_MISMATCH"
    KAST_CAPTURE_SCHEMA_UNSUPPORTED = "KAST_CAPTURE_SCHEMA_UNSUPPORTED"
    GRAPHIFY_CAPTURE_SCHEMA_UNSUPPORTED = "GRAPHIFY_CAPTURE_SCHEMA_UNSUPPORTED"
    KAST_PROVENANCE_RECEIPT_MISSING = "KAST_PROVENANCE_RECEIPT_MISSING"
    GRAPHIFY_PROVENANCE_RECEIPT_MISSING = "GRAPHIFY_PROVENANCE_RECEIPT_MISSING"
    KAST_BENCHMARK_IDENTITY_MISMATCH = "KAST_BENCHMARK_IDENTITY_MISMATCH"
    GRAPHIFY_BENCHMARK_IDENTITY_MISMATCH = "GRAPHIFY_BENCHMARK_IDENTITY_MISMATCH"
    KAST_CAPTURED_WORKLOAD_MISMATCH = "KAST_CAPTURED_WORKLOAD_MISMATCH"
    GRAPHIFY_CAPTURED_WORKLOAD_MISMATCH = "GRAPHIFY_CAPTURED_WORKLOAD_MISMATCH"
    KAST_BUILD_RECEIPT_MISSING = "KAST_BUILD_RECEIPT_MISSING"
    KAST_BUILD_SOURCE_MISMATCH = "KAST_BUILD_SOURCE_MISMATCH"
    KAST_BUILD_AUTHORITY_MISMATCH = "KAST_BUILD_AUTHORITY_MISMATCH"
    KAST_BUILD_BINARY_MISMATCH = "KAST_BUILD_BINARY_MISMATCH"
    KAST_EXECUTION_IDENTITY_MISMATCH = "KAST_EXECUTION_IDENTITY_MISMATCH"
    GRAPHIFY_GRAPH_RECEIPT_MISSING = "GRAPHIFY_GRAPH_RECEIPT_MISSING"
    GRAPHIFY_GRAPH_MISMATCH = "GRAPHIFY_GRAPH_MISMATCH"
    GRAPHIFY_EXECUTABLE_MISMATCH = "GRAPHIFY_EXECUTABLE_MISMATCH"
    GRAPHIFY_CONFIGURATION_MISMATCH = "GRAPHIFY_CONFIGURATION_MISMATCH"


@dataclass(frozen=True, slots=True)
class BenchmarkIdentity:
    manifest_sha256: str
    corpus_commit: str
    questions_sha256: str
    rubric_sha256: str
    question_ids: tuple[str, ...]

    def capture(self) -> dict:
        return {
            "manifestSha256": self.manifest_sha256,
            "corpusCommit": self.corpus_commit,
            "questionsSha256": self.questions_sha256,
            "rubricSha256": self.rubric_sha256,
        }


@dataclass(frozen=True, slots=True)
class KastArtifactIdentity:
    source_commit: str
    source_tree_sha256: str
    binary_sha256: str
    workspace_root: str
    graph_generation: int
    coverage_sha256: str
    source_index_sha256: str
    corpus_inputs_sha256: str


@dataclass(frozen=True, slots=True)
class GraphifyArtifactIdentity:
    corpus_commit: str
    graph_sha256: str
    environment_sha256: str


@dataclass(frozen=True, slots=True)
class ProvenanceFailure:
    code: ProvenanceFailureCode
    system: str
    field: str
    expected: object
    actual: object

    def document(self) -> dict:
        return {
            "code": self.code.value,
            "system": self.system,
            "field": self.field,
            "expected": self.expected,
            "actual": self.actual,
        }


@dataclass(frozen=True, slots=True)
class AdmittedProvenance:
    benchmark: BenchmarkIdentity
    kast: KastArtifactIdentity
    graphify: GraphifyArtifactIdentity
    kast_document_sha256: str
    graphify_document_sha256: str

    def document(self) -> dict:
        return {
            "admitted": True,
            "failures": [],
            "benchmark": self.benchmark.capture(),
            "kast": {
                "sourceCommit": self.kast.source_commit,
                "sourceTreeSha256": self.kast.source_tree_sha256,
                "binarySha256": self.kast.binary_sha256,
                "workspaceRoot": self.kast.workspace_root,
                "graphGeneration": self.kast.graph_generation,
                "coverageSha256": self.kast.coverage_sha256,
                "sourceIndexSha256": self.kast.source_index_sha256,
                "corpusInputsSha256": self.kast.corpus_inputs_sha256,
            },
            "graphify": {
                "corpusCommit": self.graphify.corpus_commit,
                "graphSha256": self.graphify.graph_sha256,
                "environmentSha256": self.graphify.environment_sha256,
            },
            "captureDocuments": {
                "kastSha256": self.kast_document_sha256,
                "graphifySha256": self.graphify_document_sha256,
            },
        }


@dataclass(frozen=True, slots=True)
class RejectedProvenance:
    benchmark: BenchmarkIdentity
    failures: tuple[ProvenanceFailure, ...]
    kast_document_sha256: str
    graphify_document_sha256: str

    def document(self) -> dict:
        return {
            "admitted": False,
            "benchmark": self.benchmark.capture(),
            "failures": [failure.document() for failure in self.failures],
            "captureDocuments": {
                "kastSha256": self.kast_document_sha256,
                "graphifySha256": self.graphify_document_sha256,
            },
        }


Admission = AdmittedProvenance | RejectedProvenance


class ProvenanceError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        details: dict | None = None,
        help: str | None = None,
    ):
        super().__init__(message)
        self.code = code
        self.details = details or {}
        self.help = help

    def document(self) -> dict:
        error = {
            "code": self.code,
            "message": str(self),
        }
        if self.details:
            error["details"] = self.details
        if self.help:
            error["help"] = self.help
        return {"error": error}


@dataclass(frozen=True, slots=True)
class BenchmarkSnapshot:
    manifest: dict
    questions: tuple[dict, ...]
    identity: BenchmarkIdentity


def load_benchmark_snapshot(
    manifest_path: Path,
    questions_path: Path,
    rubric_path: Path,
) -> BenchmarkSnapshot:
    manifest_bytes = manifest_path.read_bytes()
    questions_bytes = questions_path.read_bytes()
    rubric_bytes = rubric_path.read_bytes()
    try:
        manifest = json.loads(manifest_bytes)
        questions = tuple(
            json.loads(line)
            for line in questions_bytes.decode().splitlines()
            if line.strip()
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProvenanceError(
            "BENCHMARK_INPUT_INVALID",
            f"Frozen benchmark input is invalid: {error}",
        ) from error
    if (
        not isinstance(manifest, dict)
        or not all(isinstance(question, dict) for question in questions)
        or not isinstance(manifest.get("corpus"), dict)
        or not isinstance(manifest["corpus"].get("commit"), str)
        or not all(isinstance(question.get("id"), str) for question in questions)
    ):
        raise ProvenanceError(
            "BENCHMARK_INPUT_INVALID",
            "Frozen benchmark inputs do not have the required object shape.",
        )
    identity = BenchmarkIdentity(
        manifest_sha256=sha256_bytes(manifest_bytes),
        corpus_commit=manifest["corpus"]["commit"],
        questions_sha256=sha256_bytes(questions_bytes),
        rubric_sha256=sha256_bytes(rubric_bytes),
        question_ids=tuple(question["id"] for question in questions),
    )
    return BenchmarkSnapshot(manifest, questions, identity)


def capture_provenance(identity: BenchmarkIdentity, artifact: dict) -> dict:
    return {
        "schemaVersion": PROVENANCE_SCHEMA_VERSION,
        "benchmark": identity.capture(),
        "artifact": artifact,
    }


def document_sha256(document) -> str:
    return sha256_bytes(
        json.dumps(document, sort_keys=True, separators=(",", ":")).encode()
    )


def _failure(
    failures,
    code,
    system,
    field,
    expected,
    actual,
):
    failures.append(
        ProvenanceFailure(code, system, field, expected, actual)
    )


def _receipt(document):
    receipt = document.get("provenance")
    return receipt if isinstance(receipt, dict) else None


def _artifact(document):
    receipt = _receipt(document)
    artifact = receipt and receipt.get("artifact")
    return artifact if isinstance(artifact, dict) else None


def _valid_sha256(value) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _mapping(value) -> dict:
    return value if isinstance(value, dict) else {}


def _tree_sha256(root: Path, paths) -> str:
    entries = []
    for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            entries.append(("link", relative, os.readlink(path)))
        elif path.is_file():
            entries.append(("file", relative, sha256_file(path)))
    return document_sha256(entries)


def source_tree_sha256(root: Path) -> str:
    process = subprocess.run(
        [
            "git",
            "-C",
            str(root),
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
            "--",
            "cli-rs",
        ],
        capture_output=True,
        check=False,
    )
    if process.returncode:
        raise ProvenanceError(
            "KAST_SOURCE_UNAVAILABLE",
            "Kast source-tree identity could not be read.",
            details={"returnCode": process.returncode},
        )
    paths = [
        root / path.decode()
        for path in process.stdout.split(b"\0")
        if path
    ]
    return _tree_sha256(root, paths)


def _is_corpus_context_input(relative: Path) -> bool:
    path = relative.as_posix()
    return (
        path.endswith(".md")
        or path.endswith(".gradle.kts")
        or path.endswith(".schema.json")
        or path.endswith(".rs")
        or (
            relative.parent == Path(".github/workflows")
            and relative.suffix in {".yaml", ".yml"}
        )
    )


def corpus_input_identity(corpus: Path) -> dict:
    try:
        root = corpus.resolve(strict=True)
    except OSError as error:
        raise ProvenanceError(
            "KAST_CORPUS_INPUT_UNAVAILABLE",
            f"Frozen corpus cannot be resolved: {corpus}",
        ) from error
    tracked = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--cached", "-z"],
        capture_output=True,
        check=False,
    )
    if tracked.returncode:
        raise ProvenanceError(
            "KAST_CORPUS_INPUT_UNAVAILABLE",
            "Frozen corpus tracked inputs could not be read.",
            details={"returnCode": tracked.returncode},
        )
    tracked_inputs = {
        Path(os.fsdecode(value))
        for value in tracked.stdout.split(b"\0")
        if value and _is_corpus_context_input(Path(os.fsdecode(value)))
    }
    actual_inputs = set()
    for directory, names, files in os.walk(root):
        current = Path(directory)
        retained = []
        for name in names:
            candidate = current / name
            if name in CORPUS_INPUT_EXCLUDED_DIRECTORIES:
                continue
            if candidate.is_symlink():
                raise ProvenanceError(
                    "KAST_CORPUS_INPUT_SYMLINK",
                    f"Frozen corpus context traversal reaches a symlink: {candidate}",
                )
            retained.append(name)
        names[:] = retained
        for name in files:
            path = current / name
            relative = path.relative_to(root)
            if not _is_corpus_context_input(relative):
                continue
            if path.is_symlink() or not path.is_file():
                raise ProvenanceError(
                    "KAST_CORPUS_INPUT_SYMLINK",
                    f"Frozen corpus context input is not a regular file: {path}",
                )
            actual_inputs.add(relative)
    if actual_inputs != tracked_inputs:
        raise ProvenanceError(
            "KAST_CORPUS_INPUT_UNTRACKED",
            "Every live repository-context input must be tracked by the frozen commit.",
            details={
                "missing": sorted(path.as_posix() for path in tracked_inputs - actual_inputs),
                "untracked": sorted(path.as_posix() for path in actual_inputs - tracked_inputs),
            },
        )
    paths = [root / relative for relative in actual_inputs]
    return {
        "files": len(paths),
        "sha256": _tree_sha256(root, paths),
    }


def corpus_input_authority(manifest: dict) -> dict:
    authority = _mapping(_mapping(manifest.get("corpus")).get("contextInputs"))
    if (
        not isinstance(authority.get("files"), int)
        or isinstance(authority.get("files"), bool)
        or authority["files"] < 1
        or not _valid_sha256(authority.get("sha256"))
    ):
        raise ProvenanceError(
            "KAST_CORPUS_INPUT_AUTHORITY_INVALID",
            "The manifest does not contain a complete live corpus-input authority.",
        )
    return authority


def validate_corpus_inputs(manifest: dict, corpus: Path) -> dict:
    expected = corpus_input_authority(manifest)
    actual = corpus_input_identity(corpus)
    if actual != expected:
        raise ProvenanceError(
            "KAST_CORPUS_INPUT_IDENTITY_MISMATCH",
            "Live repository-context inputs do not match the frozen source state.",
            details={"expected": expected, "actual": actual},
        )
    return actual


def _tool_authority_valid(tool) -> bool:
    return (
        isinstance(tool, dict)
        and _valid_sha256(tool.get("executableSha256"))
        and isinstance(tool.get("versionOutput"), str)
        and bool(tool["versionOutput"])
    )


def _process_environment_valid(policy) -> bool:
    return (
        isinstance(policy, dict)
        and policy.get("base") in {"ambient", "empty"}
        and isinstance(policy.get("set"), dict)
        and all(
            isinstance(key, str) and isinstance(value, str)
            for key, value in policy["set"].items()
        )
        and isinstance(policy.get("remove"), list)
        and all(isinstance(key, str) for key in policy["remove"])
        and not set(policy["set"]).intersection(policy["remove"])
        and isinstance(policy.get("isolatedHomeAndWorkingDirectory"), bool)
        and (
            not policy["isolatedHomeAndWorkingDirectory"]
            or policy["base"] == "empty"
        )
    )


def process_environment(
    policy: dict,
    isolation_root: Path | None = None,
) -> dict:
    if not _process_environment_valid(policy):
        raise ProvenanceError(
            "PROCESS_ENVIRONMENT_POLICY_INVALID",
            "Capture process environment policy is incomplete.",
        )
    isolated = policy["isolatedHomeAndWorkingDirectory"]
    if isolated != (isolation_root is not None):
        raise ProvenanceError(
            "PROCESS_ENVIRONMENT_ISOLATION_INVALID",
            "Capture environment isolation does not match its frozen policy.",
        )
    environment = {} if policy["base"] == "empty" else os.environ.copy()
    for key in policy["remove"]:
        environment.pop(key, None)
    environment.update(policy["set"])
    if isolation_root is not None:
        root = str(isolation_root.resolve(strict=True))
        environment.update({"HOME": root, "TMPDIR": root})
    return environment


def kast_capture_authority(manifest: dict) -> dict:
    authority = (
        _mapping(_mapping(manifest.get("systems")).get("kast")).get("capture")
    )
    if (
        not isinstance(authority, dict)
        or authority.get("schemaVersion") != PROVENANCE_SCHEMA_VERSION
        or not _valid_sha256(authority.get("sourceTreeSha256"))
        or not _valid_sha256(authority.get("binarySha256"))
        or not _tool_authority_valid(authority.get("cargo"))
        or not _tool_authority_valid(authority.get("rustc"))
        or not isinstance(authority.get("sourceIndexPath"), str)
        or not Path(authority["sourceIndexPath"]).is_absolute()
        or not _valid_sha256(authority.get("sourceIndexSha256"))
        or not _process_environment_valid(authority.get("processEnvironment"))
        or not Path(
            authority["processEnvironment"]["set"].get("KAST_HOME", "")
        ).is_absolute()
    ):
        raise ProvenanceError(
            "KAST_CAPTURE_AUTHORITY_INVALID",
            "The manifest does not contain a complete Kast capture authority.",
        )
    return authority


def graphify_capture_authority(manifest: dict) -> dict:
    authority = (
        _mapping(_mapping(manifest.get("systems")).get("graphify")).get("capture")
    )
    if (
        not isinstance(authority, dict)
        or authority.get("schemaVersion") != PROVENANCE_SCHEMA_VERSION
        or not _valid_sha256(authority.get("executableSha256"))
        or not _valid_sha256(authority.get("runtimeSha256"))
        or not _valid_sha256(authority.get("environmentSha256"))
        or not isinstance(authority.get("versionOutput"), str)
        or not authority["versionOutput"]
        or authority.get("query") != GRAPHIFY_QUERY_CONFIGURATION
        or authority.get("processEnvironment") != GRAPHIFY_PROCESS_ENVIRONMENT
    ):
        raise ProvenanceError(
            "GRAPHIFY_CAPTURE_AUTHORITY_INVALID",
            "The manifest does not contain a complete Graphify capture authority.",
        )
    return authority


def _record_ids(document):
    records = document.get("results")
    if not isinstance(records, list) or not all(
        isinstance(record, dict) for record in records
    ):
        return ()
    return tuple(
        record.get("id") if isinstance(record.get("id"), str) else None
        for record in records
    )


def _valid_kast_build_command(command, binary_path) -> bool:
    if (
        not isinstance(command, list)
        or len(command) != 10
        or not isinstance(binary_path, str)
    ):
        return False
    cargo, action, manifest_flag, manifest, *arguments = command
    if (
        not all(isinstance(value, str) for value in command)
        or not Path(cargo).is_absolute()
        or action != "build"
        or manifest_flag != "--manifest-path"
        or not Path(manifest).is_absolute()
        or not Path(manifest).as_posix().endswith("/cli-rs/Cargo.toml")
        or arguments[:5] != ["--locked", "--release", "--bin", "kast", "--target-dir"]
        or not Path(arguments[5]).is_absolute()
    ):
        return False
    executable = "kast.exe" if os.name == "nt" else "kast"
    return Path(binary_path) == Path(arguments[5]) / "release" / executable


def _kast_execution(document):
    records = document.get("results")
    if not isinstance(records, list):
        return None
    roots = set()
    generations = set()
    coverage_entries = []
    for record in records:
        if not isinstance(record, dict):
            return None
        result = _mapping(_mapping(record.get("response")).get("result"))
        workspace = _mapping(result.get("workspaceIdentity"))
        root = workspace.get("canonicalRoot")
        generation = result.get("graphGeneration")
        inventory_generation = result.get("inventoryGeneration")
        result_generation = result.get("generation")
        coverage = _mapping(result.get("coverage"))
        counts = [
            coverage.get(field)
            for field in ("accounted", "excluded", "failed", "indexed", "stale", "total")
        ]
        if (
            not isinstance(root, str)
            or not Path(root).is_absolute()
            or not isinstance(generation, int)
            or isinstance(generation, bool)
            or generation < 0
            or inventory_generation != generation
            or result_generation != generation
            or coverage.get("eligibilityProven") is not True
            or coverage.get("complete") is not True
            or coverage.get("limitations") != []
            or coverage.get("pendingUpdateCount") != 0
            or coverage.get("stale") != 0
            or coverage.get("failed") != 0
            or not all(
                isinstance(value, int) and not isinstance(value, bool) and value >= 0
                for value in counts
            )
            or coverage["accounted"] != coverage["total"]
            or (
                coverage["indexed"]
                + coverage["excluded"]
                + coverage["failed"]
                + coverage["stale"]
                != coverage["total"]
            )
        ):
            return None
        roots.add(root)
        generations.add(generation)
        coverage_entries.append(
            {"id": record.get("id"), "coverage": coverage}
        )
    if len(roots) != 1 or len(generations) != 1:
        return None
    return {
        "workspaceRoot": roots.pop(),
        "graphGeneration": generations.pop(),
        "coverageSha256": document_sha256(coverage_entries),
    }


def validate_kast_execution(document: dict, expected_root: Path) -> dict:
    execution = _kast_execution(document)
    if execution is None or execution["workspaceRoot"] != str(expected_root):
        raise ProvenanceError(
            "KAST_EXECUTION_IDENTITY_MISMATCH",
            "Kast results do not attest one stable exact-root graph generation.",
            details={
                "expectedWorkspaceRoot": str(expected_root),
                "actual": execution,
            },
        )
    return execution


def admit_captures(
    expected: BenchmarkIdentity,
    manifest: dict,
    kast_document: dict,
    graphify_document: dict,
    kast_source_tree: str,
    kast_source_index_sha256: str | None,
) -> Admission:
    if not isinstance(kast_document, dict) or not isinstance(graphify_document, dict):
        raise ProvenanceError(
            "CAPTURE_DOCUMENT_INVALID",
            "Benchmark captures must be JSON objects.",
        )
    kast_document_sha256 = document_sha256(kast_document)
    graphify_document_sha256 = document_sha256(graphify_document)
    kast_authority = kast_capture_authority(manifest)
    graphify_authority = graphify_capture_authority(manifest)
    corpus_inputs = corpus_input_authority(manifest)
    failures = []
    kast_schema = kast_document.get("schemaVersion")
    graphify_schema = graphify_document.get("schemaVersion")
    if kast_schema != graphify_schema:
        _failure(
            failures,
            ProvenanceFailureCode.CAPTURE_SCHEMA_MISMATCH,
            "benchmark",
            "schemaVersion",
            kast_schema,
            graphify_schema,
        )
    for system, document, schema_code in (
        (
            "kast",
            kast_document,
            ProvenanceFailureCode.KAST_CAPTURE_SCHEMA_UNSUPPORTED,
        ),
        (
            "graphify",
            graphify_document,
            ProvenanceFailureCode.GRAPHIFY_CAPTURE_SCHEMA_UNSUPPORTED,
        ),
    ):
        if document.get("schemaVersion") != CAPTURE_SCHEMA_VERSION:
            _failure(
                failures,
                schema_code,
                system,
                "schemaVersion",
                CAPTURE_SCHEMA_VERSION,
                document.get("schemaVersion"),
            )

    expected_benchmark = expected.capture()
    for system, document, missing_code, mismatch_code in (
        (
            "kast",
            kast_document,
            ProvenanceFailureCode.KAST_PROVENANCE_RECEIPT_MISSING,
            ProvenanceFailureCode.KAST_BENCHMARK_IDENTITY_MISMATCH,
        ),
        (
            "graphify",
            graphify_document,
            ProvenanceFailureCode.GRAPHIFY_PROVENANCE_RECEIPT_MISSING,
            ProvenanceFailureCode.GRAPHIFY_BENCHMARK_IDENTITY_MISMATCH,
        ),
    ):
        receipt = _receipt(document)
        if (
            receipt is None
            or receipt.get("schemaVersion") != PROVENANCE_SCHEMA_VERSION
            or not isinstance(receipt.get("benchmark"), dict)
        ):
            _failure(
                failures,
                missing_code,
                system,
                "provenance",
                PROVENANCE_SCHEMA_VERSION,
                receipt,
            )
            continue
        for field, expected_value in expected_benchmark.items():
            actual = receipt["benchmark"].get(field)
            if actual != expected_value:
                _failure(
                    failures,
                    mismatch_code,
                    system,
                    f"provenance.benchmark.{field}",
                    expected_value,
                    actual,
                )

    for system, document, code in (
        (
            "kast",
            kast_document,
            ProvenanceFailureCode.KAST_CAPTURED_WORKLOAD_MISMATCH,
        ),
        (
            "graphify",
            graphify_document,
            ProvenanceFailureCode.GRAPHIFY_CAPTURED_WORKLOAD_MISMATCH,
        ),
    ):
        actual_ids = _record_ids(document)
        declared_questions = (
            _mapping(_mapping(document.get("summary")).get("questions")).get("total")
            if system == "kast"
            else _mapping(_mapping(document.get("graphify")).get("questions")).get("total")
        )
        if (
            actual_ids != expected.question_ids
            or len(set(actual_ids)) != len(actual_ids)
            or declared_questions != len(expected.question_ids)
        ):
            _failure(
                failures,
                code,
                system,
                "results[].id and declared question total",
                list(expected.question_ids),
                {
                    "ids": list(actual_ids),
                    "declaredQuestions": declared_questions,
                },
            )

    if kast_document.get("corpusCommit") != expected.corpus_commit:
        _failure(
            failures,
            ProvenanceFailureCode.KAST_BENCHMARK_IDENTITY_MISMATCH,
            "kast",
            "corpusCommit",
            expected.corpus_commit,
            kast_document.get("corpusCommit"),
        )

    kast_artifact = _artifact(kast_document)
    if not kast_artifact or kast_artifact.get("kind") != "KAST_BUILD":
        _failure(
            failures,
            ProvenanceFailureCode.KAST_BUILD_RECEIPT_MISSING,
            "kast",
            "provenance.artifact.kind",
            "KAST_BUILD",
            kast_artifact and kast_artifact.get("kind"),
        )
    else:
        source_commit = kast_artifact.get("sourceCommit")
        if (
            not isinstance(source_commit, str)
            or len(source_commit) != 40
            or not all(character in "0123456789abcdef" for character in source_commit)
            or source_commit != kast_document.get("implementationCommit")
            or kast_artifact.get("sourceStatus") != "CLEAN"
        ):
            _failure(
                failures,
                ProvenanceFailureCode.KAST_BUILD_SOURCE_MISMATCH,
                "kast",
                "provenance.artifact.sourceCommit",
                kast_document.get("implementationCommit"),
                source_commit,
            )
        if (
            kast_artifact.get("sourceTreeSha256")
            != kast_authority["sourceTreeSha256"]
            or kast_artifact.get("sourceTreeSha256") != kast_source_tree
        ):
            _failure(
                failures,
                ProvenanceFailureCode.KAST_BUILD_AUTHORITY_MISMATCH,
                "kast",
                "provenance.artifact.sourceTreeSha256",
                kast_authority["sourceTreeSha256"],
                kast_artifact.get("sourceTreeSha256"),
            )
        recorded_builder = _mapping(kast_artifact.get("builder"))
        for tool in ("cargo", "rustc"):
            recorded_tool = _mapping(recorded_builder.get(tool))
            expected_tool = kast_authority[tool]
            actual_tool = {
                "executableSha256": recorded_tool.get("executableSha256"),
                "versionOutput": recorded_tool.get("versionOutput"),
            }
            if actual_tool != expected_tool:
                _failure(
                    failures,
                    ProvenanceFailureCode.KAST_BUILD_AUTHORITY_MISMATCH,
                    "kast",
                    f"provenance.artifact.builder.{tool}",
                    expected_tool,
                    actual_tool,
                )
        process_environment_policy = kast_artifact.get("processEnvironment")
        source_index_path = kast_artifact.get("sourceIndexPath")
        if (
            process_environment_policy != kast_authority["processEnvironment"]
            or source_index_path != kast_authority["sourceIndexPath"]
            or kast_artifact.get("sourceIndexSha256")
            != kast_authority["sourceIndexSha256"]
            or kast_artifact.get("sourceIndexSha256")
            != kast_source_index_sha256
            or kast_document.get("sourceIndexPath") != source_index_path
            or kast_document.get("sourceIndexSha256")
            != kast_artifact.get("sourceIndexSha256")
        ):
            _failure(
                failures,
                ProvenanceFailureCode.KAST_BUILD_AUTHORITY_MISMATCH,
                "kast",
                "provenance.artifact source-index authority",
                {
                    "processEnvironment": kast_authority["processEnvironment"],
                    "sourceIndexPath": kast_authority["sourceIndexPath"],
                    "sourceIndexSha256": kast_authority["sourceIndexSha256"],
                    "currentSourceIndexSha256": kast_source_index_sha256,
                },
                {
                    "processEnvironment": process_environment_policy,
                    "sourceIndexPath": source_index_path,
                    "sourceIndexSha256": kast_artifact.get(
                        "sourceIndexSha256"
                    ),
                },
            )
        if (
            kast_artifact.get("corpusInputs") != corpus_inputs
            or kast_document.get("corpusInputs") != corpus_inputs
        ):
            _failure(
                failures,
                ProvenanceFailureCode.KAST_BUILD_AUTHORITY_MISMATCH,
                "kast",
                "provenance.artifact.corpusInputs",
                corpus_inputs,
                {
                    "artifact": kast_artifact.get("corpusInputs"),
                    "capture": kast_document.get("corpusInputs"),
                },
            )
        binary_sha256 = kast_artifact.get("binarySha256")
        command = kast_artifact.get("buildCommand")
        if (
            binary_sha256 != kast_authority["binarySha256"]
            or kast_artifact.get("binaryPath") != kast_document.get("kastBinary")
            or not _valid_kast_build_command(
                command,
                kast_artifact.get("binaryPath"),
            )
            or command[0] != _mapping(recorded_builder.get("cargo")).get(
                "executablePath"
            )
        ):
            _failure(
                failures,
                ProvenanceFailureCode.KAST_BUILD_BINARY_MISMATCH,
                "kast",
                "provenance.artifact.binarySha256",
                "a harness-built locked release Kast binary",
                binary_sha256,
            )
        execution = _kast_execution(kast_document)
        recorded_execution = {
            "workspaceRoot": kast_artifact.get("workspaceRoot"),
            "graphGeneration": kast_artifact.get("graphGeneration"),
            "coverageSha256": kast_artifact.get("coverageSha256"),
        }
        top_execution = {
            "workspaceRoot": kast_document.get("workspaceRoot"),
            "graphGeneration": kast_document.get("graphGeneration"),
            "coverageSha256": kast_document.get("coverageSha256"),
        }
        if (
            execution is None
            or recorded_execution != execution
            or top_execution != execution
        ):
            _failure(
                failures,
                ProvenanceFailureCode.KAST_EXECUTION_IDENTITY_MISMATCH,
                "kast",
                "workspaceRoot and graphGeneration",
                execution,
                {
                    "artifact": recorded_execution,
                    "capture": top_execution,
                },
            )

    graphify_artifact = _artifact(graphify_document)
    expected_graphify = _mapping(
        _mapping(_mapping(manifest.get("systems")).get("graphify")).get(
            "finalRebuild"
        )
    )
    if (
        not graphify_artifact
        or graphify_artifact.get("kind") != "GRAPHIFY_GRAPH"
    ):
        _failure(
            failures,
            ProvenanceFailureCode.GRAPHIFY_GRAPH_RECEIPT_MISSING,
            "graphify",
            "provenance.artifact.kind",
            "GRAPHIFY_GRAPH",
            graphify_artifact and graphify_artifact.get("kind"),
        )
    else:
        graphify_metadata = _mapping(graphify_document.get("graphify"))
        graph_fields = {
            "corpusCommit": expected.corpus_commit,
            "graphSha256": expected_graphify["graphSha256"],
            "directed": expected_graphify["directed"],
            "nodes": expected_graphify["nodes"],
            "edges": expected_graphify["edges"],
        }
        for field, expected_value in graph_fields.items():
            artifact_value = graphify_artifact.get(field)
            metadata_value = (
                graphify_document.get("corpusCommit")
                if field == "corpusCommit"
                else graphify_metadata.get(field)
            )
            if (
                artifact_value != expected_value
                or metadata_value != expected_value
            ):
                _failure(
                    failures,
                    ProvenanceFailureCode.GRAPHIFY_GRAPH_MISMATCH,
                    "graphify",
                    f"provenance.artifact.{field}",
                    expected_value,
                    artifact_value,
                )
        if (
            graphify_artifact.get("executableSha256")
            != graphify_authority["executableSha256"]
            or graphify_artifact.get("runtimeSha256")
            != graphify_authority["runtimeSha256"]
            or graphify_artifact.get("environmentSha256")
            != graphify_authority["environmentSha256"]
            or graphify_artifact.get("versionOutput")
            != graphify_authority["versionOutput"]
            or graphify_metadata.get("version")
            != graphify_authority["versionOutput"]
        ):
            _failure(
                failures,
                ProvenanceFailureCode.GRAPHIFY_EXECUTABLE_MISMATCH,
                "graphify",
                "provenance.artifact executable/runtime/environment identity",
                {
                    key: graphify_authority[key]
                    for key in (
                        "executableSha256",
                        "runtimeSha256",
                        "environmentSha256",
                        "versionOutput",
                    )
                },
                {
                    key: graphify_artifact.get(key)
                    for key in (
                        "executableSha256",
                        "runtimeSha256",
                        "environmentSha256",
                        "versionOutput",
                    )
                },
            )
        if graphify_artifact.get("query") != graphify_authority["query"]:
            _failure(
                failures,
                ProvenanceFailureCode.GRAPHIFY_CONFIGURATION_MISMATCH,
                "graphify",
                "provenance.artifact.query",
                graphify_authority["query"],
                graphify_artifact.get("query"),
            )
        if (
            graphify_artifact.get("processEnvironment")
            != graphify_authority["processEnvironment"]
        ):
            _failure(
                failures,
                ProvenanceFailureCode.GRAPHIFY_CONFIGURATION_MISMATCH,
                "graphify",
                "provenance.artifact.processEnvironment",
                graphify_authority["processEnvironment"],
                graphify_artifact.get("processEnvironment"),
            )

    if failures:
        return RejectedProvenance(
            expected,
            tuple(failures),
            kast_document_sha256,
            graphify_document_sha256,
        )
    return AdmittedProvenance(
        benchmark=expected,
        kast=KastArtifactIdentity(
            source_commit=kast_artifact["sourceCommit"],
            source_tree_sha256=kast_artifact["sourceTreeSha256"],
            binary_sha256=kast_artifact["binarySha256"],
            workspace_root=kast_artifact["workspaceRoot"],
            graph_generation=kast_artifact["graphGeneration"],
            coverage_sha256=kast_artifact["coverageSha256"],
            source_index_sha256=kast_artifact["sourceIndexSha256"],
            corpus_inputs_sha256=kast_artifact["corpusInputs"]["sha256"],
        ),
        graphify=GraphifyArtifactIdentity(
            corpus_commit=graphify_artifact["corpusCommit"],
            graph_sha256=graphify_artifact["graphSha256"],
            environment_sha256=graphify_artifact["environmentSha256"],
        ),
        kast_document_sha256=kast_document_sha256,
        graphify_document_sha256=graphify_document_sha256,
    )


@dataclass(frozen=True, slots=True)
class KastBuildReceipt:
    source_commit: str
    source_tree_sha256: str
    builder: dict
    command: tuple[str, ...]
    binary_path: Path
    binary_sha256: str

    def artifact(
        self,
        execution: dict,
        source_index: dict,
        corpus_inputs: dict,
        process_environment_policy: dict,
    ) -> dict:
        return {
            "kind": "KAST_BUILD",
            "sourceCommit": self.source_commit,
            "sourceTreeSha256": self.source_tree_sha256,
            "sourceStatus": "CLEAN",
            "builder": self.builder,
            "buildCommand": list(self.command),
            "binaryPath": str(self.binary_path),
            "binarySha256": self.binary_sha256,
            "processEnvironment": process_environment_policy,
            "corpusInputs": corpus_inputs,
            **source_index,
            **execution,
        }


def _git(root: Path, *args) -> str:
    process = subprocess.run(
        ["git", "-C", str(root), *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if process.returncode:
        raise ProvenanceError(
            "KAST_SOURCE_UNAVAILABLE",
            "Kast source identity could not be read.",
            details={"returnCode": process.returncode},
            help="Run the benchmark from a Git checkout.",
        )
    return process.stdout.strip()


def _resolve_executable(
    command: str,
    unavailable_code: str,
    *,
    resolve_symlinks: bool = True,
) -> Path:
    resolved = shutil.which(command)
    if resolved is None:
        candidate = Path(command).expanduser()
        resolved = str(candidate.absolute()) if candidate.is_file() else None
    if resolved is None:
        raise ProvenanceError(
            unavailable_code,
            f"Required executable is unavailable: {command}",
        )
    path = Path(resolved).absolute()
    return path.resolve() if resolve_symlinks else path


def _tool_identity(command: str, unavailable_code: str) -> dict:
    executable = _resolve_executable(
        command,
        unavailable_code,
        resolve_symlinks=False,
    )
    version = subprocess.run(
        [str(executable), "--version", "--verbose"],
        capture_output=True,
        text=True,
        check=False,
    )
    version_output = version.stdout.strip()
    if version.returncode or not version_output:
        raise ProvenanceError(
            f"{unavailable_code}_VERSION",
            f"Toolchain identity could not be read: {executable}",
            details={"returnCode": version.returncode},
        )
    return {
        "executablePath": str(executable),
        "executableSha256": sha256_file(executable),
        "versionOutput": version_output,
    }


def _builder_identity(cargo: str, rustc: str) -> dict:
    return {
        "cargo": _tool_identity(cargo, "KAST_CARGO_UNAVAILABLE"),
        "rustc": _tool_identity(rustc, "KAST_RUSTC_UNAVAILABLE"),
    }


def source_index_identity(
    kast: Path,
    workspace_root: Path,
    environment: dict,
) -> dict:
    paths = subprocess.run(
        [
            str(kast),
            "--output",
            "json",
            "developer",
            "inspect",
            "paths",
            "--workspace-root",
            str(workspace_root),
        ],
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    try:
        document = json.loads(paths.stdout)
    except json.JSONDecodeError as error:
        raise ProvenanceError(
            "KAST_SOURCE_INDEX_IDENTITY_UNAVAILABLE",
            "Kast path resolution did not return structured evidence.",
            details={"returnCode": paths.returncode},
        ) from error
    config_files = document.get("configFiles") if isinstance(document, dict) else None
    workspace_config = next(
        (
            item.get("path")
            for item in config_files or ()
            if isinstance(item, dict)
            and item.get("scope") == "workspace"
            and isinstance(item.get("path"), str)
        ),
        None,
    )
    if paths.returncode or workspace_config is None:
        raise ProvenanceError(
            "KAST_SOURCE_INDEX_IDENTITY_UNAVAILABLE",
            "Kast workspace state path could not be resolved.",
            details={"returnCode": paths.returncode},
        )
    try:
        state_root = Path(environment["KAST_HOME"]).resolve(strict=True)
        database = (
            Path(workspace_config).parent / "cache/source-index.db"
        ).resolve(strict=True)
    except (KeyError, OSError) as error:
        raise ProvenanceError(
            "KAST_SOURCE_INDEX_IDENTITY_UNAVAILABLE",
            "Kast source-index authority cannot be resolved.",
        ) from error
    if not database.is_file() or not database.is_relative_to(state_root):
        raise ProvenanceError(
            "KAST_SOURCE_INDEX_IDENTITY_UNAVAILABLE",
            "Kast source index is outside the pinned state root.",
        )
    return {
        "sourceIndexPath": str(database),
        "sourceIndexSha256": sqlite_snapshot_sha256(database),
    }


def validate_source_index_identity(authority: dict, identity: dict) -> dict:
    expected = {
        "sourceIndexPath": authority["sourceIndexPath"],
        "sourceIndexSha256": authority["sourceIndexSha256"],
    }
    if identity != expected:
        raise ProvenanceError(
            "KAST_SOURCE_INDEX_IDENTITY_MISMATCH",
            "Kast source index does not match the frozen capture authority.",
            details={"expected": expected, "actual": identity},
        )
    return identity


def sqlite_snapshot_sha256(database: Path) -> str:
    try:
        with tempfile.TemporaryDirectory(prefix="kast-source-index-proof-") as directory:
            snapshot = Path(directory) / "source-index.db"
            with (
                closing(
                    sqlite3.connect(f"{database.as_uri()}?mode=ro", uri=True)
                ) as source,
                closing(sqlite3.connect(snapshot)) as target,
            ):
                source.backup(target)
            return sha256_file(snapshot)
    except sqlite3.Error as error:
        raise ProvenanceError(
            "KAST_SOURCE_INDEX_IDENTITY_UNAVAILABLE",
            f"Kast source-index snapshot failed: {error}",
        ) from error


def current_source_index_sha256(
    kast_document: dict,
    manifest: dict,
) -> str | None:
    authority = kast_capture_authority(manifest)
    artifact = _artifact(kast_document)
    path = artifact and artifact.get("sourceIndexPath")
    if not isinstance(path, str) or path != authority["sourceIndexPath"]:
        return None
    try:
        state_root = Path(
            authority["processEnvironment"]["set"]["KAST_HOME"]
        ).resolve(strict=True)
        database = Path(path).resolve(strict=True)
        expected = Path(authority["sourceIndexPath"]).resolve(strict=True)
    except OSError:
        return None
    if (
        database != expected
        or not database.is_file()
        or not database.is_relative_to(state_root)
    ):
        return None
    return sqlite_snapshot_sha256(database)


def build_kast_release(
    root: Path,
    target_directory: Path,
    authority: dict,
    cargo: str = "cargo",
    rustc: str = "rustc",
) -> KastBuildReceipt:
    source_commit = _git(root, "rev-parse", "HEAD")
    if _git(root, "status", "--porcelain", "--untracked-files=all"):
        raise ProvenanceError(
            "KAST_SOURCE_DIRTY",
            "Kast benchmark source must be clean before the release build.",
            help="Commit or stash source changes, then rerun the benchmark.",
        )
    source_tree = source_tree_sha256(root)
    if source_tree != authority["sourceTreeSha256"]:
        raise ProvenanceError(
            "KAST_SOURCE_IDENTITY_MISMATCH",
            "Kast cli-rs source does not match the frozen capture authority.",
            details={
                "expected": authority["sourceTreeSha256"],
                "actual": source_tree,
            },
        )
    builder = _builder_identity(cargo, rustc)
    for tool in ("cargo", "rustc"):
        actual = {
            key: builder[tool][key]
            for key in ("executableSha256", "versionOutput")
        }
        if actual != authority[tool]:
            raise ProvenanceError(
                "KAST_TOOLCHAIN_IDENTITY_MISMATCH",
                f"Kast {tool} does not match the frozen capture authority.",
                details={"tool": tool, "expected": authority[tool], "actual": actual},
            )
    cargo_path = Path(builder["cargo"]["executablePath"])
    command = (
        str(cargo_path),
        "build",
        "--manifest-path",
        str(root / "cli-rs/Cargo.toml"),
        "--locked",
        "--release",
        "--bin",
        "kast",
        "--target-dir",
        str(target_directory),
    )
    environment = os.environ.copy()
    for variable in (
        "CARGO_ENCODED_RUSTFLAGS",
        "RUSTC_WRAPPER",
        "RUSTC_WORKSPACE_WRAPPER",
        "RUSTFLAGS",
    ):
        environment.pop(variable, None)
    environment["RUSTC"] = builder["rustc"]["executablePath"]
    process = subprocess.run(
        command,
        cwd=root,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    if process.returncode:
        raise ProvenanceError(
            "KAST_RELEASE_BUILD_FAILED",
            "The isolated locked Kast release build failed.",
            details={"returnCode": process.returncode},
            help="Run the recorded Cargo build command locally for diagnostics.",
        )
    executable = "kast.exe" if os.name == "nt" else "kast"
    binary_path = (target_directory / "release" / executable).resolve()
    if not binary_path.is_file():
        raise ProvenanceError(
            "KAST_RELEASE_BINARY_MISSING",
            "The isolated release build did not produce the Kast executable.",
        )
    binary_sha256 = sha256_file(binary_path)
    if binary_sha256 != authority["binarySha256"]:
        raise ProvenanceError(
            "KAST_RELEASE_BINARY_IDENTITY_MISMATCH",
            "The release build does not match the frozen Kast binary identity.",
            details={
                "expected": authority["binarySha256"],
                "actual": binary_sha256,
            },
        )
    return KastBuildReceipt(
        source_commit=source_commit,
        source_tree_sha256=source_tree,
        builder=builder,
        command=command,
        binary_path=binary_path,
        binary_sha256=binary_sha256,
    )


def verify_kast_build_receipt(root: Path, receipt: KastBuildReceipt) -> None:
    actual_commit = _git(root, "rev-parse", "HEAD")
    dirty = _git(root, "status", "--porcelain", "--untracked-files=all")
    actual_source_tree_sha256 = source_tree_sha256(root)
    actual_builder = _builder_identity(
        receipt.builder["cargo"]["executablePath"],
        receipt.builder["rustc"]["executablePath"],
    )
    actual_binary_sha256 = (
        sha256_file(receipt.binary_path) if receipt.binary_path.is_file() else None
    )
    if (
        actual_commit != receipt.source_commit
        or dirty
        or actual_source_tree_sha256 != receipt.source_tree_sha256
        or actual_builder != receipt.builder
        or actual_binary_sha256 != receipt.binary_sha256
    ):
        raise ProvenanceError(
            "KAST_BUILD_RECEIPT_STALE",
            "Kast source state or executed binary changed during benchmark capture.",
            details={
                "expectedSourceCommit": receipt.source_commit,
                "actualSourceCommit": actual_commit,
                "expectedSourceTreeSha256": receipt.source_tree_sha256,
                "actualSourceTreeSha256": actual_source_tree_sha256,
                "expectedBinarySha256": receipt.binary_sha256,
                "actualBinarySha256": actual_binary_sha256,
            },
            help="Discard the partial capture and rerun from a clean source state.",
        )


@dataclass(frozen=True, slots=True)
class GraphifyCapture:
    executable: Path
    executable_sha256: str
    runtime: Path
    runtime_sha256: str
    environment_root: Path | None
    environment_sha256: str
    version_output: str
    source_graph: Path
    graph_bytes: bytes
    graph_sha256: str
    directed: bool
    nodes: int
    edges: int
    corpus_commit: str

    def artifact(self) -> dict:
        return {
            "kind": "GRAPHIFY_GRAPH",
            "corpusCommit": self.corpus_commit,
            "sourceGraphPath": str(self.source_graph),
            "graphSha256": self.graph_sha256,
            "directed": self.directed,
            "nodes": self.nodes,
            "edges": self.edges,
            "executablePath": str(self.executable),
            "executableSha256": self.executable_sha256,
            "runtimePath": str(self.runtime),
            "runtimeSha256": self.runtime_sha256,
            "environmentRoot": (
                str(self.environment_root) if self.environment_root else None
            ),
            "environmentSha256": self.environment_sha256,
            "versionOutput": self.version_output,
            "query": GRAPHIFY_QUERY_CONFIGURATION,
            "processEnvironment": GRAPHIFY_PROCESS_ENVIRONMENT,
        }


def _identity_mismatch(field: str, expected, actual) -> ProvenanceError:
    return ProvenanceError(
        "GRAPHIFY_BASELINE_IDENTITY_MISMATCH",
        "Graphify capture does not match the frozen manifest.",
        details={"field": field, "expected": expected, "actual": actual},
        help="Rebuild the frozen Graphify baseline and explicitly update its manifest provenance.",
    )


def _graphify_environment(executable: Path) -> tuple[Path, str, Path | None, str]:
    try:
        with executable.open("rb") as source:
            first_line = source.readline().decode().strip()
    except UnicodeDecodeError:
        first_line = ""
    if first_line.startswith("#!") and " " not in first_line[2:]:
        interpreter = Path(first_line[2:]).absolute()
        environment_root = interpreter.parent.parent
        if (environment_root / "pyvenv.cfg").is_file():
            paths = [
                path
                for path in environment_root.rglob("*")
                if "__pycache__" not in path.parts
                and path.suffix not in {".pyc", ".pyo"}
            ]
            return (
                interpreter,
                sha256_file(interpreter.resolve()),
                environment_root,
                _tree_sha256(environment_root, paths),
            )
    return executable, sha256_file(executable), None, sha256_file(executable)


def validate_graphify_capture(
    manifest: dict,
    graph_path: Path,
    graphify: str,
) -> GraphifyCapture:
    if not graph_path.is_file():
        raise ProvenanceError(
            "GRAPHIFY_BASELINE_UNAVAILABLE",
            f"Graphify graph is missing: {graph_path}",
        )
    graph_bytes = graph_path.read_bytes()
    graph_sha256 = sha256_bytes(graph_bytes)
    expected_graph = _mapping(
        _mapping(_mapping(manifest.get("systems")).get("graphify")).get(
            "finalRebuild"
        )
    )
    if expected_graph.get("corpusCommit") != _mapping(manifest.get("corpus")).get(
        "commit"
    ):
        raise _identity_mismatch(
            "corpusCommit",
            _mapping(manifest.get("corpus")).get("commit"),
            expected_graph.get("corpusCommit"),
        )
    if graph_sha256 != expected_graph["graphSha256"]:
        raise _identity_mismatch(
            "graphSha256",
            expected_graph["graphSha256"],
            graph_sha256,
        )
    try:
        graph = json.loads(graph_bytes)
    except json.JSONDecodeError as error:
        raise ProvenanceError(
            "GRAPHIFY_MANIFEST_INVALID",
            f"Frozen Graphify graph is not valid JSON: {error}",
        ) from error
    if not isinstance(graph, dict):
        raise ProvenanceError(
            "GRAPHIFY_GRAPH_INVALID",
            "Frozen Graphify graph must be a JSON object.",
        )
    nodes = graph.get("nodes")
    links = graph.get("links")
    if not isinstance(nodes, list) or not isinstance(links, list):
        raise ProvenanceError(
            "GRAPHIFY_GRAPH_INVALID",
            "Frozen Graphify graph must contain node and link arrays.",
        )
    observed = {
        "directed": graph.get("directed", False),
        "nodes": len(nodes),
        "edges": len(links),
    }
    for field, actual in observed.items():
        if actual != expected_graph[field]:
            raise _identity_mismatch(field, expected_graph[field], actual)

    expected_capture = graphify_capture_authority(manifest)
    executable = _resolve_executable(graphify, "GRAPHIFY_BASELINE_UNAVAILABLE")
    executable_sha256 = sha256_file(executable)
    if executable_sha256 != expected_capture.get("executableSha256"):
        raise _identity_mismatch(
            "executableSha256",
            expected_capture.get("executableSha256"),
            executable_sha256,
        )
    runtime, runtime_sha256, environment_root, environment_sha256 = (
        _graphify_environment(executable)
    )
    for field, expected_value, actual in (
        ("runtimeSha256", expected_capture["runtimeSha256"], runtime_sha256),
        (
            "environmentSha256",
            expected_capture["environmentSha256"],
            environment_sha256,
        ),
    ):
        if actual != expected_value:
            raise _identity_mismatch(field, expected_value, actual)
    with tempfile.TemporaryDirectory(prefix="graphify-version-proof-") as directory:
        isolation_root = Path(directory)
        version = subprocess.run(
            [str(executable), "--version"],
            cwd=isolation_root,
            env=process_environment(
                GRAPHIFY_PROCESS_ENVIRONMENT,
                isolation_root,
            ),
            capture_output=True,
            text=True,
            check=False,
        )
    version_output = version.stdout.strip()
    if version.returncode or not version_output:
        raise ProvenanceError(
            "GRAPHIFY_VERSION_CHECK_FAILED",
            "Graphify version identity could not be read.",
            details={"returnCode": version.returncode},
        )
    if version_output != expected_capture.get("versionOutput"):
        raise _identity_mismatch(
            "versionOutput",
            expected_capture.get("versionOutput"),
            version_output,
        )
    if expected_capture.get("query") != GRAPHIFY_QUERY_CONFIGURATION:
        raise _identity_mismatch(
            "query",
            expected_capture.get("query"),
            GRAPHIFY_QUERY_CONFIGURATION,
        )
    return GraphifyCapture(
        executable=executable,
        executable_sha256=executable_sha256,
        runtime=runtime,
        runtime_sha256=runtime_sha256,
        environment_root=environment_root,
        environment_sha256=environment_sha256,
        version_output=version_output,
        source_graph=graph_path.resolve(),
        graph_bytes=graph_bytes,
        graph_sha256=graph_sha256,
        directed=observed["directed"],
        nodes=observed["nodes"],
        edges=observed["edges"],
        corpus_commit=expected_graph["corpusCommit"],
    )


def verify_graphify_capture(capture: GraphifyCapture, graph_path: Path) -> None:
    actual_executable_sha256 = (
        sha256_file(capture.executable) if capture.executable.is_file() else None
    )
    actual_graph_sha256 = sha256_file(graph_path) if graph_path.is_file() else None
    (
        actual_runtime,
        actual_runtime_sha256,
        actual_environment_root,
        actual_environment_sha256,
    ) = _graphify_environment(capture.executable)
    if (
        actual_executable_sha256 != capture.executable_sha256
        or actual_runtime != capture.runtime
        or actual_runtime_sha256 != capture.runtime_sha256
        or actual_environment_root != capture.environment_root
        or actual_environment_sha256 != capture.environment_sha256
        or actual_graph_sha256 != capture.graph_sha256
    ):
        raise ProvenanceError(
            "GRAPHIFY_CAPTURE_RECEIPT_STALE",
            "Graphify executable or graph changed during benchmark capture.",
            details={
                "expectedExecutableSha256": capture.executable_sha256,
                "actualExecutableSha256": actual_executable_sha256,
                "expectedRuntimeSha256": capture.runtime_sha256,
                "actualRuntimeSha256": actual_runtime_sha256,
                "expectedEnvironmentSha256": capture.environment_sha256,
                "actualEnvironmentSha256": actual_environment_sha256,
                "expectedGraphSha256": capture.graph_sha256,
                "actualGraphSha256": actual_graph_sha256,
            },
            help="Discard the partial capture and rerun from the frozen baseline.",
        )
