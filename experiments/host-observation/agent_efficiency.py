#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["pydantic==2.12.3"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Coordinate paired native Pi trials inside the host-observation experiment."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import random
import subprocess
import sys
import time
from collections import defaultdict
from collections.abc import Mapping
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    TypeAdapter,
    ValidationError,
    model_validator,
)

from agent_efficiency_integrity import (
    OriginalFile,
    PreservationCleanupFailed,
    PreservationFailure,
    PreservationOutcome,
    PreservationRejected,
    PreservedDeletion,
    PreservedDelta,
    PreservedFile,
    WorkspaceSnapshot,
    read_journal,
)
from agent_efficiency_metrics import InvalidInput, Trial, compare, parse_trial
from agent_efficiency_subject import (
    AdmissionCode,
    GitSubject,
    Subject,
    SubjectAdmissionError,
    admit_subject,
    subject_digest,
)

HERE = Path(__file__).resolve().parent
REVISION = "5937ff1f46fbb0914c36226afb52426a4271c29c"
LEGACY_SUBJECT = GitSubject()


class Record(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)


class Replacement(Record):
    path: str
    before: str
    after: str


class Oracle(Record):
    answer: JsonValue
    sources: tuple[str, ...]
    qualification: str
    status: Literal["QUALIFIED", "UNQUALIFIED"] = "QUALIFIED"
    snippets: dict[str, tuple[str, ...]] = Field(default_factory=dict)
    file_contains: dict[str, str] = Field(default_factory=dict)
    commands: tuple[tuple[str, ...], ...] = ()


class ExactRestoration(Record):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    kind: Literal["exact_restoration"] = "exact_restoration"


class CommandEvidence(Record):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    qualification: Literal["CURATED_INDEPENDENT_COMMAND"] = "CURATED_INDEPENDENT_COMMAND"
    argv: tuple[str, ...] = Field(min_length=1)
    expected_exit: int = 0

    @model_validator(mode="after")
    def nonempty_arguments(self) -> CommandEvidence:
        if any(not argument for argument in self.argv):
            raise ValueError("EMPTY_COMMAND_ARGUMENT")
        return self


class CommandReceipt(Record):
    argv: tuple[str, ...]
    outcome: Literal["EXITED", "TIMEOUT", "START_FAILED"]
    expected_exit: int
    exit_code: int | None
    passed: bool
    stdout_sha256: str
    stderr_sha256: str
    stdout_bytes: int
    stderr_bytes: int


class ScopedRepair(Record):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    kind: Literal["scoped_repair"] = "scoped_repair"
    files: tuple[str, ...] = Field(min_length=1)
    commands: tuple[CommandEvidence, ...] = Field(min_length=1)


class SubjectCommitment(Record):
    subject: Subject
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    producer_workspace: str
    producer_revision: Literal["5937ff1f46fbb0914c36226afb52426a4271c29c"] = REVISION


class Task(Record):
    id: str = Field(pattern=r"^[a-z0-9.-]+$")
    base_revision: Literal["5937ff1f46fbb0914c36226afb52426a4271c29c"] = REVISION
    operations: tuple[str, ...] = ()
    family: Literal[
        "discovery",
        "resolution",
        "relationships",
        "traversal",
        "impact",
        "diagnostics",
        "source",
        "changes",
        "verification",
        "negative",
        "freshness",
    ]
    scope: Literal["file", "module", "workspace"]
    difficulty: Literal["easy", "medium", "hard"]
    prompt: str
    source_paths: tuple[str, ...]
    oracle: Oracle
    setup: tuple[Replacement, ...]
    allowed_changes: tuple[str, ...]
    postcondition: Annotated[ExactRestoration | ScopedRepair, Field(discriminator="kind")] = (
        ExactRestoration()
    )

    @model_validator(mode="after")
    def repair_scope(self) -> Task:
        if isinstance(self.postcondition, ScopedRepair):
            files = self.postcondition.files
            if len(set(files)) != len(files) or set(files) != set(self.allowed_changes):
                raise ValueError("REPAIR_SCOPE_MISMATCH")
            for relative in files:
                path = Path(relative)
                if (
                    path.is_absolute()
                    or path.as_posix() != relative
                    or any(part in ("", ".", "..") for part in relative.split("/"))
                ):
                    raise ValueError("REPAIR_SCOPE_PATH_INVALID")
            if any(item.path not in files for item in self.setup):
                raise ValueError("SETUP_OUTSIDE_MUTATION_SCOPE")
        return self


class FileOnboarding(Record):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)
    kind: Literal["file"] = "file"
    name: str = Field(pattern=r"^[a-z0-9][a-z0-9.-]*\.md$")
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


@dataclass(frozen=True, slots=True)
class _LoadedBrief:
    text: str


@dataclass(frozen=True, slots=True)
class _BriefRejected:
    reason: Literal["BRIEF_DIGEST_MISMATCH", "BRIEF_SOURCE_UNAVAILABLE", "BRIEF_INVALID_UTF8"]


def _admit_brief_bytes(
    descriptor: FileOnboarding, contents: bytes
) -> _LoadedBrief | _BriefRejected:
    if hashlib.sha256(contents).hexdigest() != descriptor.sha256:
        return _BriefRejected("BRIEF_DIGEST_MISMATCH")
    try:
        return _LoadedBrief(contents.decode("utf-8"))
    except UnicodeDecodeError:
        return _BriefRejected("BRIEF_INVALID_UTF8")


def _read_brief(folder: Path, descriptor: FileOnboarding) -> _LoadedBrief | _BriefRejected:
    source = folder / descriptor.name
    try:
        if folder.is_symlink() or source.is_symlink() or not source.is_file():
            return _BriefRejected("BRIEF_SOURCE_UNAVAILABLE")
        contents = source.read_bytes()
    except OSError:
        return _BriefRejected("BRIEF_SOURCE_UNAVAILABLE")
    return _admit_brief_bytes(descriptor, contents)


class Configuration(Record):
    name: str = Field(pattern=r"^[a-z0-9-]+$")
    exposure: Literal["codemode", "deferred", "direct"]
    onboarding: Literal["canonical", "none"] | FileOnboarding
    skills: tuple[str, ...] = ()
    hypothesis: str
    maximum_requests: int = Field(default=20, ge=1, le=100)
    maximum_tools: int = Field(default=40, ge=1)
    wall_seconds: int = Field(default=180, ge=1, le=600)
    maximum_reported_tokens: int = Field(default=150000, ge=1)
    maximum_payload_bytes: int = Field(default=1048576, ge=1)
    maximum_output_tokens: int = Field(default=6000, ge=1, le=16000)


class Environment(Record):
    workspace: str
    producer_workspace: str = str(HERE.parent.parent)
    subject: Subject = GitSubject()
    pi_package: str
    pi_version: str
    auth: str
    mcp_command: str
    model: Literal["gpt-6.1-sol"] = "gpt-6.1-sol"
    provider: Literal["openai-codex"] = "openai-codex"
    effort: Literal["high"] = "high"
    cache_policy: Literal["WARM_SHARED_COUNTERBALANCED"] = "WARM_SHARED_COUNTERBALANCED"


class SourceFileDocument(Record):
    kind: Literal["FILE"] = "FILE"
    relative_path: str
    byte_count: int = Field(ge=0)
    mode: int = Field(ge=0, le=0o7777)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    artifact_path: str = Field(pattern=r"^source-artifacts/[0-9]{8}\.source$")


class SourceDeletionDocument(Record):
    kind: Literal["DELETION"] = "DELETION"
    relative_path: str


class PreservedSourceDocument(Record):
    kind: Literal["PRESERVED"] = "PRESERVED"
    entries: tuple[
        Annotated[SourceFileDocument | SourceDeletionDocument, Field(discriminator="kind")], ...
    ]


class UnchangedSourceDocument(Record):
    kind: Literal["UNCHANGED"] = "UNCHANGED"


class RejectedSourceDocument(Record):
    kind: Literal["REJECTED"] = "REJECTED"
    reason: PreservationFailure


class SourceCleanupFailedDocument(Record):
    kind: Literal["CLEANUP_FAILED"] = "CLEANUP_FAILED"
    reason: PreservationFailure
    directory: Literal["source-artifacts"] = "source-artifacts"


class SourceOverlayDocument(Record):
    version: Literal[1] = 1
    subject_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    qualification: Literal["SEQUENTIAL_POST_ORACLE_FILES"] = "SEQUENTIAL_POST_ORACLE_FILES"
    maximum_files: Literal[64] = 64
    maximum_bytes: Literal[4194304] = 4194304
    outcome: Annotated[
        PreservedSourceDocument
        | UnchangedSourceDocument
        | RejectedSourceDocument
        | SourceCleanupFailedDocument,
        Field(discriminator="kind"),
    ]


def source_overlay_document(
    outcome: PreservationOutcome, owned: Path, subject: Subject
) -> SourceOverlayDocument:
    match outcome:
        case PreservedDelta(_, entries):
            projected: list[SourceFileDocument | SourceDeletionDocument] = []
            for entry in entries:
                match entry:
                    case PreservedFile(relative, size, mode, sha, artifact):
                        projected.append(
                            SourceFileDocument(
                                relative_path=relative,
                                byte_count=size,
                                mode=mode,
                                sha256=sha,
                                artifact_path=artifact.relative_to(owned).as_posix(),
                            )
                        )
                    case PreservedDeletion(relative):
                        projected.append(SourceDeletionDocument(relative_path=relative))
            value = PreservedSourceDocument(entries=tuple(projected))
        case PreservationRejected(reason):
            value = RejectedSourceDocument(reason=reason)
        case PreservationCleanupFailed(rejection, _):
            value = SourceCleanupFailedDocument(reason=rejection.reason)
    return SourceOverlayDocument(subject_sha256=subject_digest(subject), outcome=value)


class SessionReport(Record):
    version: int
    outcome: str
    wallSeconds: float
    setupSeconds: float
    provider: str
    model: str
    effort: str
    arm: Literal["control", "treatment"]
    requests: int
    toolCalls: int
    retries: int
    compactions: int
    inputTokens: int
    outputTokens: int
    cacheReadTokens: int
    cacheWriteTokens: int
    totalTokens: int
    payloadBytes: int
    toolOutputBytes: int
    catalogCostUsd: float | None
    billedCostUsd: float | None
    costQualification: str
    usageQualification: str
    outputCapQualification: str
    planSha256: str
    piPackageSha256: str
    mcpExecutableSha256: str | None
    onboardingSha256: str


class Verdict(Record):
    correct: bool
    reason: Literal[
        "CORRECT",
        "ANSWER_MISMATCH",
        "ANSWER_UNAVAILABLE",
        "FILE_MISMATCH",
        "FORBIDDEN_CHANGE",
        "COMMAND_FAILED",
        "UNQUALIFIED_ORACLE",
        "RUN_FAILED",
    ]


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def corpus_digest(tasks: tuple[Task, ...]) -> str:
    # Legacy commitments predate the explicit exact-restoration default.
    encoded = [
        task.model_dump(mode="json", exclude={"postcondition"})
        if isinstance(task.postcondition, ExactRestoration)
        else task.model_dump(mode="json")
        for task in tasks
    ]
    return digest(
        json.dumps(encoded, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    )


def save(path: Path, value: object) -> None:
    with path.open("x", encoding="utf-8") as stream:
        path.chmod(0o600)
        _ = stream.write(json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + "\n")


def git(root: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments], cwd=root, capture_output=True, text=True, check=True
    ).stdout.strip()


def scoped_path(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if Path(relative).is_absolute() or not path.is_relative_to(root.resolve()) or path == root:
        raise ValueError("PATH_OUTSIDE_WORKSPACE")
    return path


def split_tasks(tasks: tuple[Task, ...], seed: int) -> tuple[tuple[Task, ...], tuple[Task, ...]]:
    if len(tasks) < 120 or len({task.id for task in tasks}) != len(tasks):
        raise ValueError("CORPUS_NEEDS_120_UNIQUE_TASKS")
    groups: dict[str, list[Task]] = defaultdict(list)
    for task in tasks:
        groups[task.family].append(task)
    if len(groups) != 11 or min(map(len, groups.values())) < 4:
        raise ValueError("OPERATION_COVERAGE_MISSING")
    rng = random.Random(seed)
    development: list[Task] = []
    holdout: list[Task] = []
    # Stratify family first, then order by scope/difficulty before a randomized
    # round-robin allocation. The report preserves the actual joint counts.
    for family in sorted(groups):
        ordered = sorted(groups[family], key=lambda task: (task.scope, task.difficulty, task.id))
        offset = rng.randrange(4)
        for index, task in enumerate(ordered):
            (holdout if (index + offset) % 4 == 0 else development).append(task)
    return tuple(development), tuple(holdout)


def adjudicate(answer_text: str, oracle: Oracle) -> Verdict:
    if oracle.status == "UNQUALIFIED" or oracle.qualification.startswith("UNQUALIFIED"):
        return Verdict(correct=False, reason="UNQUALIFIED_ORACLE")
    try:
        raw: object = json.loads(answer_text)
    except (ValueError, TypeError):
        return Verdict(correct=False, reason="ANSWER_UNAVAILABLE")
    # Strict JSON equality rejects extra unreviewed claims outside the answer.
    if not isinstance(raw, dict):
        return Verdict(correct=False, reason="ANSWER_MISMATCH")
    try:
        answer = TypeAdapter(dict[str, JsonValue]).validate_python(raw)
    except ValidationError:
        return Verdict(correct=False, reason="ANSWER_MISMATCH")
    if set(answer) != {"answer"}:
        return Verdict(correct=False, reason="ANSWER_MISMATCH")
    if json.dumps(answer["answer"], sort_keys=True) != json.dumps(oracle.answer, sort_keys=True):
        return Verdict(correct=False, reason="ANSWER_MISMATCH")
    return Verdict(correct=True, reason="CORRECT")


def prepare(root: Path, output: Path, curator: Path, environment: Environment) -> None:
    if root != Path(environment.workspace).resolve():
        raise SubjectAdmissionError(AdmissionCode.IDENTITY_MISMATCH)
    if git(Path(environment.producer_workspace), "rev-parse", "HEAD") != REVISION:
        raise ValueError("PRODUCER_REVISION_MISMATCH")
    admit_subject(Path(environment.workspace), environment.subject)
    commitment = SubjectCommitment(
        subject=environment.subject,
        sha256=subject_digest(environment.subject),
        producer_workspace=str(Path(environment.producer_workspace).resolve()),
    )
    spec = importlib.util.spec_from_file_location("independent_corpus", curator)
    if spec is None or spec.loader is None:
        raise ValueError("CURATOR_UNAVAILABLE")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    tasks = TypeAdapter(tuple[Task, ...]).validate_json(json.dumps(module.make_corpus(root)))
    for task in tasks:
        for relative in (*task.source_paths, *task.allowed_changes, *task.oracle.file_contains):
            if not scoped_path(root, relative).is_file():
                raise ValueError("CORPUS_SOURCE_MISSING")
        for replacement in task.setup:
            if scoped_path(root, replacement.path).read_text().count(replacement.before) != 1:
                raise ValueError("SETUP_PREIMAGE_MISMATCH")
        for relative, snippets in task.oracle.snippets.items():
            source = scoped_path(root, relative).read_text()
            if not all(snippet in source for snippet in snippets):
                raise ValueError("ORACLE_SOURCE_SNIPPET_MISMATCH")
    development, holdout = split_tasks(tasks, 535644)
    output.mkdir(mode=0o700)
    vault = output / "vault"
    vault.mkdir(mode=0o700)
    encoded = TypeAdapter(tuple[Task, ...]).dump_json(tasks)
    save(vault / "corpus.json", json.loads(encoded))
    save(vault / "holdout.json", [task.model_dump(mode="json") for task in holdout])
    save(output / "development.json", [task.model_dump(mode="json") for task in development])
    save(output / "environment.json", environment.model_dump(mode="json"))
    sources = {
        relative: digest(scoped_path(root, relative).read_bytes())
        for task in tasks
        for relative in task.source_paths
    }
    save(vault / "sources.json", sources)
    _ = (vault / "curator.py").write_bytes(curator.read_bytes())
    (vault / "curator.py").chmod(0o600)
    strata: dict[str, int] = defaultdict(int)
    for task in tasks:
        strata[f"{task.family}/{task.scope}/{task.difficulty}"] += 1
    save(
        output / "manifest.json",
        {
            "revision": REVISION,
            "subjectAdmission": commitment.model_dump(mode="json"),
            "corpusSha256": corpus_digest(tasks),
            "corpusIdentityFormat": "CANONICAL_JSON_V1",
            "sourcesSha256": digest((vault / "sources.json").read_bytes()),
            "curatorSha256": digest(curator.read_bytes()),
            "total": len(tasks),
            "development": len(development),
            "holdout": len(holdout),
            "strata": strata,
            "repositoryScaleQualification": "ONE_REAL_REPOSITORY_SCOPE_IS_NOT_REPOSITORY_SCALE",
            "holdoutDeniedPaths": [
                str(vault.resolve()),
                str((output / "development.json").resolve()),
                str(curator.resolve()),
                str(Path(importlib.util.cache_from_source(str(curator))).resolve()),
            ],
        },
    )


def sandbox_policy(denied: tuple[Path, ...]) -> str:
    # JSON string quoting is valid for the string literals in this profile.
    return "(version 1)(allow default)" + "".join(
        f"(deny file-read* file-write* (subpath {json.dumps(str(path.resolve()))}))"
        for path in denied
    )


def examine_sandbox(policy: str, denied: Path) -> None:
    result = subprocess.run(
        ["/usr/bin/sandbox-exec", "-p", policy, "/bin/cat", str(denied)],
        capture_output=True,
        check=False,
    )
    if result.returncode == 0 or result.stdout or b"Operation not permitted" not in result.stderr:
        raise ValueError("HOLDOUT_ISOLATION_UNPROVEN")


def onboarding(producer: Path, config: Configuration, workspace: Path | None = None) -> str:
    references = producer / "agent-tools/references"
    match config.onboarding:
        case "none":
            return ""
        case FileOnboarding() as descriptor:
            match _read_brief(HERE / "agent-efficiency-configs", descriptor):
                case _LoadedBrief(text):
                    brief = text
                case _BriefRejected(reason):
                    # Invalid experimental input stops before the provider call.
                    raise ValueError(reason)
        case "canonical":
            brief = (references / "onboarding.md").read_text()
    brief += (
        f"\nWorkspace: {json.dumps(str(workspace if workspace is not None else producer))}"
        "\nQuery schema: "
        f"{json.dumps(str(references / 'query-schema.json'))}\nQuery examples: "
        f"{json.dumps(str(references / 'query-examples.json'))}"
    )
    return brief


def _run_trial(
    task: Task,
    arm: Literal["control", "treatment"],
    repeat: int,
    config: Configuration,
    env: Environment,
    output: Path,
    policy: str,
    snapshot: WorkspaceSnapshot,
) -> Trial:
    root = Path(env.workspace)
    owned = output / f"{task.id}-{repeat}-{arm}"
    owned.mkdir(mode=0o700)
    originals = frozenset(task.allowed_changes)
    for replacement in task.setup:
        if replacement.path not in originals:
            raise ValueError("SETUP_OUTSIDE_MUTATION_SCOPE")
        source = scoped_path(root, replacement.path)
        if source.read_text().count(replacement.before) != 1:
            raise ValueError("SETUP_PREIMAGE_MISMATCH")
        _ = source.write_text(source.read_text().replace(replacement.before, replacement.after, 1))
    plan = {
        "version": 1,
        "workspaceRoot": env.workspace,
        "outputRoot": str(owned / "session"),
        "piPackageRoot": env.pi_package,
        "piVersion": env.pi_version,
        "authPath": env.auth,
        "provider": env.provider,
        "model": env.model,
        "effort": env.effort,
        "arm": arm,
        "exposure": config.exposure,
        "mcpCommand": env.mcp_command,
        "onboarding": onboarding(Path(env.producer_workspace), config, root)
        if arm == "treatment"
        else "",
        "skills": list(config.skills) if arm == "treatment" else [],
        "prompt": task.prompt + '\nReturn only JSON {"answer": <the requested JSON value>}. '
        "Use source evidence. Keep the declared scope. "
        "Do not inspect benchmark files or oracle data. "
        "For enum entries, return the entry name without the enum type. "
        "For qualified operations, include the declaring type and omit the package. "
        "For a signature, copy the source spelling exactly.",
        "maximumRequests": config.maximum_requests,
        "maximumReportedTokens": config.maximum_reported_tokens,
        "maximumPayloadBytes": config.maximum_payload_bytes,
        "maximumOutputTokens": config.maximum_output_tokens,
        "maximumTools": config.maximum_tools,
        "wallSeconds": config.wall_seconds,
    }
    plan_path = owned / "plan.json"
    save(plan_path, plan)
    started = time.monotonic()
    process = subprocess.run(
        [
            "/usr/bin/sandbox-exec",
            "-p",
            policy,
            "node",
            str(HERE / "agent_efficiency_session.mjs"),
            "--run",
            str(plan_path),
        ],
        capture_output=True,
        text=True,
        check=False,
        timeout=config.wall_seconds + 20,
    )
    elapsed = time.monotonic() - started
    save(
        owned / "process.json",
        {
            "exitCode": process.returncode,
            "stdout": process.stdout,
            "stderr": process.stderr,
            "wallSeconds": elapsed,
        },
    )
    report_path = Path(str(plan_path) + ".result.json")
    raw: dict[str, JsonValue] = (
        TypeAdapter(dict[str, JsonValue]).validate_json(report_path.read_bytes())
        if report_path.exists()
        else {}
    )
    report = SessionReport.model_validate(raw["report"]) if raw.get("report") else None
    answer_path = owned / "session/answer.json"
    answer = (
        TypeAdapter(dict[str, str]).validate_json(answer_path.read_bytes())["text"]
        if answer_path.exists()
        else ""
    )
    verdict = adjudicate(answer, task.oracle)
    if report is None or report.outcome != "COMPLETED" or raw.get("cancellationRequested"):
        verdict = Verdict(correct=False, reason="RUN_FAILED")
    verdict = examine_postcondition(task, root, snapshot, verdict, owned / "oracle-commands.json")
    delta = snapshot.examine()
    if delta.changed or delta.created:
        preserved = snapshot.preserve_delta(
            owned / "source-artifacts", maximum_files=64, maximum_bytes=4194304
        )
        overlay = source_overlay_document(preserved, owned, env.subject)
        if not isinstance(preserved, PreservedDelta) and verdict.correct:
            verdict = Verdict(correct=False, reason="SOURCE_ARTIFACTS_UNAVAILABLE")
    else:
        overlay = SourceOverlayDocument(
            subject_sha256=subject_digest(env.subject), outcome=UnchangedSourceDocument()
        )
    save(owned / "source-overlay.json", overlay.model_dump(mode="json"))
    elapsed = time.monotonic() - started
    save(
        owned / "verdict.json",
        {
            **verdict.model_dump(),
            "oracleSha256": digest(task.oracle.model_dump_json().encode()),
            "protectedFiles": len(snapshot.files),
            "auditQualification": "EXACT_ANSWER_AND_FILE_ORACLE_ONLY",
        },
    )
    save(owned / "workspace-delta.json", asdict(delta))
    # Billed dollars are unavailable for subscription inference. Preserve
    # that fact. A separate catalog-price comparison remains diagnostic.
    partial = read_journal(owned / "session/journal.jsonl")
    save(owned / "usage-evidence.json", asdict(partial))
    trial = Trial(
        task.id,
        task.family,
        arm,
        repeat,
        verdict.correct,
        None,
        None,
        elapsed,
        report.billedCostUsd if report else None,
        report.inputTokens + report.cacheReadTokens + report.cacheWriteTokens
        if report
        else partial.input_tokens,
        report.outputTokens if report else partial.output_tokens,
        report.cacheReadTokens if report else partial.cache_read_tokens,
        report.toolCalls if report else partial.tool_calls,
    )
    save(owned / "trial.json", asdict(trial))
    save(
        owned / "catalog-trial.json",
        asdict(replace(trial, cost_usd=report.catalogCostUsd if report else None)),
    )
    return trial


def examine_postcondition(
    task: Task,
    root: Path,
    snapshot: WorkspaceSnapshot,
    verdict: Verdict,
    evidence_path: Path | None = None,
) -> Verdict:
    # Scope rejection dominates command or textual evidence, including commands
    # that themselves write outside the curated repair scope.
    delta = snapshot.examine()
    if (set(delta.changed) | set(delta.created)) - set(task.allowed_changes):
        return Verdict(correct=False, reason="FORBIDDEN_CHANGE")
    for relative, expected in task.oracle.file_contains.items():
        path = scoped_path(root, relative)
        if verdict.correct and (not path.is_file() or expected not in path.read_text()):
            verdict = Verdict(correct=False, reason="FILE_MISMATCH")
    if isinstance(task.postcondition, ExactRestoration):
        for replacement in task.setup:
            prior = snapshot.files[replacement.path]
            if not isinstance(prior, OriginalFile):
                raise ValueError("REGULAR_SETUP_FILE_NEEDED")
            path = scoped_path(root, replacement.path)
            if verdict.correct and (not path.is_file() or path.read_bytes() != prior.contents):
                verdict = Verdict(correct=False, reason="FILE_MISMATCH")
    commands = tuple(CommandEvidence(argv=command) for command in task.oracle.commands)
    if isinstance(task.postcondition, ScopedRepair):
        commands += task.postcondition.commands
    receipts: list[CommandReceipt] = []
    for command in commands:
        try:
            checked = subprocess.run(
                command.argv, cwd=root, capture_output=True, check=False, timeout=180
            )
            passed = checked.returncode == command.expected_exit
            outcome = "EXITED"
            exit_code = checked.returncode
            stdout, stderr = checked.stdout, checked.stderr
        except subprocess.TimeoutExpired as error:
            passed = False
            outcome, exit_code = "TIMEOUT", None
            stdout = error.stdout if isinstance(error.stdout, bytes) else b""
            stderr = error.stderr if isinstance(error.stderr, bytes) else b""
        except OSError:
            passed = False
            outcome, exit_code, stdout, stderr = "START_FAILED", None, b"", b""
        receipts.append(
            CommandReceipt(
                argv=command.argv,
                outcome=outcome,
                expected_exit=command.expected_exit,
                exit_code=exit_code,
                passed=passed,
                stdout_sha256=digest(stdout),
                stderr_sha256=digest(stderr),
                stdout_bytes=len(stdout),
                stderr_bytes=len(stderr),
            )
        )
        if not passed and verdict.correct:
            verdict = Verdict(correct=False, reason="COMMAND_FAILED")
    if evidence_path is not None:
        save(evidence_path, [receipt.model_dump(mode="json") for receipt in receipts])
    delta = snapshot.examine()
    if (set(delta.changed) | set(delta.created)) - set(task.allowed_changes):
        return Verdict(correct=False, reason="FORBIDDEN_CHANGE")
    return verdict


def run_trial(
    task: Task,
    arm: Literal["control", "treatment"],
    repeat: int,
    config: Configuration,
    env: Environment,
    output: Path,
    policy: str,
) -> Trial:
    if not isinstance(env.subject, GitSubject):
        admit_subject(Path(env.workspace), env.subject)
    snapshot = WorkspaceSnapshot.capture(Path(env.workspace))
    try:
        return _run_trial(task, arm, repeat, config, env, output, policy, snapshot)
    finally:
        snapshot.restore()


def examine_prepared_inputs(
    benchmark: Path,
    workspace: Path,
    selected: tuple[Task, ...],
    manifest: Mapping[str, JsonValue],
    subject: Subject = LEGACY_SUBJECT,
    producer: Path | None = None,
) -> None:
    if "subjectAdmission" in manifest:
        commitment = SubjectCommitment.model_validate_json(json.dumps(manifest["subjectAdmission"]))
        if (
            commitment.subject != subject
            or commitment.sha256 != subject_digest(subject)
            or (producer is not None and commitment.producer_workspace != str(producer.resolve()))
        ):
            raise SubjectAdmissionError(AdmissionCode.IDENTITY_MISMATCH)
        admit_subject(workspace, subject)
    elif not isinstance(subject, GitSubject) or subject.revision != REVISION:
        raise SubjectAdmissionError(AdmissionCode.IDENTITY_MISMATCH)
    elif git(workspace, "rev-parse", "HEAD") != REVISION:
        raise ValueError("SOURCE_REVISION_MISMATCH")
    if manifest.get("revision") != REVISION:
        raise ValueError("SOURCE_REVISION_MISMATCH")
    corpus = TypeAdapter(tuple[Task, ...]).validate_json(
        (benchmark / "vault/corpus.json").read_bytes()
    )
    if corpus_digest(corpus) != manifest.get("corpusSha256"):
        raise ValueError("CORPUS_COMMITMENT_MISMATCH")
    committed = {task.id: task for task in corpus}
    if any(committed.get(task.id) != task for task in selected):
        raise ValueError("TASK_COMMITMENT_MISMATCH")
    source_file = benchmark / "vault/sources.json"
    if digest(source_file.read_bytes()) != manifest.get("sourcesSha256"):
        raise ValueError("SOURCE_COMMITMENT_MISMATCH")
    sources = TypeAdapter(dict[str, str]).validate_json(source_file.read_bytes())
    for task in selected:
        for relative in task.source_paths:
            if digest(scoped_path(workspace, relative).read_bytes()) != sources.get(relative):
                raise ValueError("SOURCE_CONTENT_MISMATCH")


def execute(
    root: Path,
    config_path: Path,
    output: Path,
    task_ids: tuple[str, ...],
    repeats: int,
    holdout: bool,
) -> None:
    env = Environment.model_validate_json((root / "environment.json").read_bytes())
    config = Configuration.model_validate_json(config_path.read_bytes())
    corpus_path = root / "vault/holdout.json" if holdout else root / "development.json"
    tasks = TypeAdapter(tuple[Task, ...]).validate_json(corpus_path.read_bytes())
    selected = tuple(task for task in tasks if not task_ids or task.id in task_ids)
    if not selected or (task_ids and set(task_ids) != {task.id for task in selected}):
        raise ValueError("UNKNOWN_TASK_SELECTION")
    manifest = TypeAdapter(dict[str, JsonValue]).validate_json(
        (root / "manifest.json").read_bytes()
    )
    producer = Path(env.producer_workspace)
    if git(producer, "rev-parse", "HEAD") != REVISION:
        raise ValueError("PRODUCER_REVISION_MISMATCH")
    examine_prepared_inputs(root, Path(env.workspace), selected, manifest, env.subject, producer)
    denied = TypeAdapter(tuple[str, ...]).validate_python(manifest["holdoutDeniedPaths"])
    # Relocated archives keep their original custody restrictions and protect
    # the current copies before the OS denial experiment or participant startup.
    policy = sandbox_policy(
        (root / "vault", root / "development.json", *(Path(item) for item in denied))
    )
    examine_sandbox(policy, root / "vault/corpus.json")
    output.mkdir(mode=0o700)
    controller_source = output / "controller-source"
    controller_source.mkdir(mode=0o700)
    for name in (
        "agent_efficiency.py",
        "agent_efficiency_session.mjs",
        "agent_efficiency_limits.mjs",
        "agent_efficiency_audit.mjs",
        "agent_efficiency_subject.py",
        "agent_efficiency_metrics.py",
        "agent_efficiency_integrity.py",
        "agent_efficiency_tokens.py",
        "agent_efficiency_timing.py",
        "pi_evaluation_controller.mjs",
    ):
        saved = controller_source / name
        _ = saved.write_bytes((HERE / name).read_bytes())
        saved.chmod(0o600)
    save(
        output / "run-manifest.json",
        {
            "revision": git(producer, "rev-parse", "HEAD"),
            "subjectAdmission": SubjectCommitment(
                subject=env.subject,
                sha256=subject_digest(env.subject),
                producer_workspace=str(producer.resolve()),
            ).model_dump(mode="json"),
            "configuration": config.model_dump(mode="json"),
            "configurationSha256": digest(config_path.read_bytes()),
            "corpusSha256": manifest["corpusSha256"],
            "preparedManifestSha256": digest((root / "manifest.json").read_bytes()),
            "split": "holdout" if holdout else "development",
            "tasks": [task.id for task in selected],
            "repeats": repeats,
            "environment": env.model_dump(mode="json"),
            "holdoutIsolation": "MACOS_SANDBOX_DENIAL_EXAMINED",
            "sessionAdapterSha256": digest((HERE / "agent_efficiency_session.mjs").read_bytes()),
            "coordinatorSha256": digest(Path(__file__).read_bytes()),
            "metricOwnerSha256": digest((HERE / "agent_efficiency_metrics.py").read_bytes()),
            "costQualification": "BILLED_DOLLARS_UNAVAILABLE",
            "semanticAuditQualification": "INDEPENDENT_AUDIT_PENDING",
        },
    )
    trials: list[Trial] = []
    for repeat in range(repeats):
        for index, task in enumerate(selected):
            arms: tuple[Literal["control", "treatment"], ...] = (
                ("control", "treatment") if (index + repeat) % 2 == 0 else ("treatment", "control")
            )
            for arm in arms:
                trial = run_trial(task, arm, repeat, config, env, output, policy)
                trials.append(trial)
                print(
                    json.dumps(
                        {
                            "task": task.id,
                            "arm": arm,
                            "correct": trial.correct,
                            "seconds": trial.wall_seconds,
                        }
                    ),
                    flush=True,
                )
    report = compare(trials, expected_task_ids=tuple(task.id for task in selected))
    save(output / "comparison.json", report)
    catalog: list[Trial] = []
    for trial in trials:
        file = output / f"{trial.task_id}-{trial.repeat}-{trial.arm}" / "catalog-trial.json"
        value: object = json.loads(file.read_bytes())
        admitted = parse_trial(value)
        if isinstance(admitted, InvalidInput):
            raise ValueError(admitted.reason)
        catalog.append(admitted.trial)
    save(
        output / "catalog-comparison.json",
        {
            "qualification": "CATALOG_ESTIMATES_NOT_BILLED_DOLLARS",
            "comparison": compare(catalog, expected_task_ids=tuple(task.id for task in selected)),
        },
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    prep = subs.add_parser("prepare")
    _ = prep.add_argument("--workspace", type=Path, required=True)
    _ = prep.add_argument("--output", type=Path, required=True)
    _ = prep.add_argument("--curator", type=Path, required=True)
    _ = prep.add_argument("--environment", type=Path, required=True)
    run = subs.add_parser("run")
    _ = run.add_argument("--benchmark", type=Path, required=True)
    _ = run.add_argument("--config", type=Path, required=True)
    _ = run.add_argument("--output", type=Path, required=True)
    _ = run.add_argument("--tasks", default="")
    _ = run.add_argument("--repeats", type=int, default=1)
    _ = run.add_argument("--holdout", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            env = Environment.model_validate_json(args.environment.read_bytes())
            prepare(args.workspace.resolve(), args.output.resolve(), args.curator.resolve(), env)
        else:
            if args.repeats < 1:
                raise ValueError("POSITIVE_REPEAT_COUNT_NEEDED")
            execute(
                args.benchmark.resolve(),
                args.config.resolve(),
                args.output.resolve(),
                tuple(item for item in args.tasks.split(",") if item),
                args.repeats,
                args.holdout,
            )
    except (ValueError, ValidationError, OSError, subprocess.SubprocessError) as error:
        print(
            json.dumps(
                {
                    "type": "EXPERIMENT_FAILED",
                    "reason": type(error).__name__,
                    "detail": str(error)[:1000],
                }
            ),
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
