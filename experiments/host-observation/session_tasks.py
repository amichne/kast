# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["pydantic==2.14.1"]
# [tool.uv]
# no-build = true
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Archive local agent transcripts, summarize them, and validate task drafts mined from them.

Transcripts are private local records. The archive and summaries stay outside the
repository; only task drafts, which carry no transcript text, belong in Git.
A summary is text-derived observation. It grants no semantic authority.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from collections import Counter
from collections.abc import Callable, Iterable, Iterator, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Any, Literal, cast

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

MAX_TRANSCRIPT = 256 * 1024 * 1024
MAX_PROMPT_EXCERPT = 600
HERE = Path(__file__).resolve().parent
PARTICIPANT_SURFACE = HERE / "promptfoo" / "participant-surface.mjs"

Sha = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{40}$")]
RepoPath = Annotated[str, StringConstraints(pattern=r"^[^/\s][^\s]*[^/\s]$")]


class Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


# ---------------------------------------------------------------- archive


class SnapshotEntry(Frozen):
    """One captured version of one transcript. Live transcripts grow; each version is kept."""

    project: str
    relative_path: str
    sha256: Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
    size: int
    captured_at: datetime


class SnapshotReport(Frozen):
    archive: str
    captured: int
    unchanged: int


def transcript_paths(project: Path) -> Iterator[Path]:
    """Top-level transcripts and their subagent transcripts, in a stable order."""
    yield from sorted(project.glob("*.jsonl"))
    yield from sorted(project.glob("*/subagents/*.jsonl"))


def read_manifest(archive: Path) -> list[SnapshotEntry]:
    manifest = archive / "manifest.jsonl"
    if not manifest.exists():
        return []
    entries: list[SnapshotEntry] = []
    for number, line in enumerate(manifest.read_text().splitlines(), start=1):
        try:
            entries.append(SnapshotEntry.model_validate_json(line))
        except ValidationError as error:
            raise SystemExit(f"session-tasks: manifest line {number} rejected: {error}") from error
    return entries


def snapshot(projects_root: Path, match: str, archive: Path, now: datetime) -> SnapshotReport:
    """Copy each new transcript version into a content-addressed archive. Never overwrites."""
    archive.mkdir(mode=0o700, parents=True, exist_ok=True)
    blobs = archive / "blobs"
    blobs.mkdir(mode=0o700, exist_ok=True)
    known = {(e.project, e.relative_path, e.sha256) for e in read_manifest(archive)}
    captured = unchanged = 0
    lines: list[str] = []
    for project in sorted(p for p in projects_root.iterdir() if p.is_dir() and match in p.name):
        for path in transcript_paths(project):
            size = path.stat().st_size
            if size > MAX_TRANSCRIPT:
                raise SystemExit(f"session-tasks: {path.name} exceeds {MAX_TRANSCRIPT} bytes")
            data = path.read_bytes()  # One read: hash and copy describe the same bytes.
            digest = hashlib.sha256(data).hexdigest()
            relative = str(path.relative_to(project))
            if (project.name, relative, digest) in known:
                unchanged += 1
                continue
            blob = blobs / f"{digest}.jsonl"
            if not blob.exists():
                temporary = blobs / f".{digest}.partial"
                descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "wb") as output:
                    _ = output.write(data)
                temporary.rename(blob)
            entry = SnapshotEntry(
                project=project.name,
                relative_path=relative,
                sha256=digest,
                size=len(data),
                captured_at=now,
            )
            lines.append(entry.model_dump_json())
            known.add((project.name, relative, digest))
            captured += 1
    if lines:
        descriptor = os.open(
            archive / "manifest.jsonl", os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600
        )
        with os.fdopen(descriptor, "a") as manifest:
            _ = manifest.write("".join(line + "\n" for line in lines))
    return SnapshotReport(archive=str(archive), captured=captured, unchanged=unchanged)


def latest_versions(entries: Iterable[SnapshotEntry]) -> dict[tuple[str, str], SnapshotEntry]:
    latest: dict[tuple[str, str], SnapshotEntry] = {}
    for entry in entries:
        key = (entry.project, entry.relative_path)
        if key not in latest or entry.captured_at >= latest[key].captured_at:
            latest[key] = entry
    return latest


# ---------------------------------------------------------------- summary


class KastCallKind(StrEnum):
    SEMANTIC = "SEMANTIC"  # query, source, result, diagnostic: answers about code
    REFERENCE = "REFERENCE"  # describe, help, skill: reads Kast's own documentation
    OPERATIONAL = "OPERATIONAL"  # version, status, stop, upgrade, install, workspace


class KastOutcome(StrEnum):
    COMPLETE = "COMPLETE"
    REJECTED = "REJECTED"
    ERROR = "ERROR"
    UNCLASSIFIED = "UNCLASSIFIED"


class KastExposure(StrEnum):
    NONE = "NONE"
    OPERATIONAL_ONLY = "OPERATIONAL_ONLY"
    SEMANTIC = "SEMANTIC"


SEMANTIC_COMMANDS = frozenset({"query", "source", "result", "diagnostic"})
REFERENCE_COMMANDS = frozenset({"describe", "skill", "--help", "help"})
KAST_COMMAND = re.compile(r"(?:^|[;&|(\s\"'`$])kast\s+(--?[a-z-]+|[a-z][a-z-]*)")


def kast_commands(command: str) -> list[KastCallKind]:
    kinds: list[KastCallKind] = []
    for match in KAST_COMMAND.finditer(command):
        word = match.group(1)
        if word in SEMANTIC_COMMANDS:
            kinds.append(KastCallKind.SEMANTIC)
        elif word in REFERENCE_COMMANDS:
            kinds.append(KastCallKind.REFERENCE)
        else:
            kinds.append(KastCallKind.OPERATIONAL)
    return kinds


def semantic_outcome(is_error: bool, text: str) -> KastOutcome:
    """Text-derived only: a pipeline can reshape Kast output before the agent sees it."""
    if re.search(r"\"(?:type|status)\"\s*:\s*\"rejected", text) or "COMPLETION_UNPROVEN" in text:
        return KastOutcome.REJECTED
    if re.search(r"\"(?:type|status)\"\s*:\s*\"complete\"", text):
        return KastOutcome.COMPLETE
    if is_error:
        return KastOutcome.ERROR
    return KastOutcome.UNCLASSIFIED


class Tokens(Frozen):
    input: int = 0
    output: int = 0
    cache_read: int = 0
    cache_write: int = 0


class KastUse(Frozen):
    semantic: int = 0
    reference: int = 0
    operational: int = 0
    mcp: int = 0
    skill_loads: int = 0
    semantic_outcomes: dict[KastOutcome, int] = Field(default_factory=dict[KastOutcome, int])
    exposure: KastExposure = KastExposure.NONE


class SessionSummary(Frozen):
    project: str
    relative_path: str
    transcript_sha256: str
    session_ids: tuple[str, ...]
    first_timestamp: datetime | None
    last_timestamp: datetime | None
    models: tuple[str, ...]
    efforts: tuple[str, ...]
    branches: tuple[str, ...]
    first_prompt: str | None
    human_prompts: int
    interruptions: int
    compactions: int
    tool_calls: dict[str, int]
    tokens: Tokens
    reported_cost_usd: float | None
    pull_requests: tuple[str, ...]
    subagents: int
    subagent_tokens: Tokens
    kast: KastUse
    malformed_lines: int
    control_eligible: bool
    inferred_base_commit: str | None = None
    also_recorded_in: tuple[str, ...] = ()


@dataclass
class Accumulator:
    session_ids: set[str] = field(default_factory=set[str])
    timestamps: list[datetime] = field(default_factory=list[datetime])
    models: set[str] = field(default_factory=set[str])
    efforts: set[str] = field(default_factory=set[str])
    branches: set[str] = field(default_factory=set[str])
    first_prompt: str | None = None
    human_prompts: int = 0
    interruptions: int = 0
    compactions: int = 0
    tools: Counter[str] = field(default_factory=Counter[str])
    tokens: Counter[str] = field(default_factory=Counter[str])
    cost: float | None = None
    pull_requests: set[str] = field(default_factory=set[str])
    kast: Counter[str] = field(default_factory=Counter[str])
    outcomes: Counter[KastOutcome] = field(default_factory=Counter[KastOutcome])
    pending_semantic: set[str] = field(default_factory=set[str])
    malformed: int = 0


def blocks(message: dict[str, Any]) -> list[dict[str, Any]]:
    content = message.get("content")
    if not isinstance(content, list):
        return []
    return [cast(dict[str, Any], b) for b in cast(list[Any], content) if isinstance(b, dict)]


def result_text(block: dict[str, Any]) -> str:
    content = block.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = cast(list[Any], content)
        return " ".join(
            str(cast(dict[str, Any], p).get("text", "")) for p in parts if isinstance(p, dict)
        )
    return ""


def parse_time(value: object) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def accumulate(lines: Iterable[str]) -> Accumulator:
    acc = Accumulator()
    for line in lines:
        try:
            raw: object = json.loads(line)
        except json.JSONDecodeError:
            acc.malformed += 1  # A live transcript can end mid-line.
            continue
        if not isinstance(raw, dict):
            acc.malformed += 1
            continue
        record = cast(dict[str, Any], raw)
        kind = record.get("type")
        if isinstance(record.get("sessionId"), str):
            acc.session_ids.add(record["sessionId"])
        if (stamp := parse_time(record.get("timestamp"))) is not None:
            acc.timestamps.append(stamp)
        if isinstance(record.get("gitBranch"), str) and record["gitBranch"]:
            acc.branches.add(record["gitBranch"])
        if isinstance(record.get("effort"), str):
            acc.efforts.add(record["effort"])
        if isinstance(record.get("totalCostUSD"), int | float):
            acc.cost = float(record["totalCostUSD"])
        if kind == "pr-link" and isinstance(record.get("prUrl"), str):
            acc.pull_requests.add(record["prUrl"])
        if kind == "system" and "compact" in str(record.get("subtype", "")):
            acc.compactions += 1
        message = record.get("message")
        if not isinstance(message, dict):
            continue
        message = cast(dict[str, Any], message)
        if kind == "user":
            content = message.get("content")
            if isinstance(content, str) and not record.get("isMeta"):
                if "[Request interrupted" in content:
                    acc.interruptions += 1
                elif not content.startswith("<"):
                    acc.human_prompts += 1
                    if acc.first_prompt is None:
                        acc.first_prompt = content[:MAX_PROMPT_EXCERPT]
            for block in blocks(message):
                if block.get("type") == "text" and "[Request interrupted" in str(
                    block.get("text", "")
                ):
                    acc.interruptions += 1
                if (
                    block.get("type") == "tool_result"
                    and block.get("tool_use_id") in acc.pending_semantic
                ):
                    acc.pending_semantic.discard(block["tool_use_id"])
                    acc.outcomes[
                        semantic_outcome(bool(block.get("is_error")), result_text(block))
                    ] += 1
        elif kind == "assistant":
            if isinstance(message.get("model"), str):
                acc.models.add(message["model"])
            usage = message.get("usage")
            if isinstance(usage, dict):
                usage = cast(dict[str, Any], usage)
                for source, target in (
                    ("input_tokens", "input"),
                    ("output_tokens", "output"),
                    ("cache_read_input_tokens", "cache_read"),
                    ("cache_creation_input_tokens", "cache_write"),
                ):
                    if isinstance(usage.get(source), int):
                        acc.tokens[target] += usage[source]
            for block in blocks(message):
                if block.get("type") != "tool_use":
                    continue
                name = str(block.get("name", ""))
                arguments = block.get("input")
                arguments = cast(dict[str, Any], arguments) if isinstance(arguments, dict) else {}
                acc.tools[name] += 1
                if "kast" in name.lower():
                    acc.kast["mcp"] += 1
                elif name == "Skill" and "kast" in str(arguments.get("skill", "")).lower():
                    acc.kast["skill_loads"] += 1
                elif name == "Bash":
                    kinds = kast_commands(str(arguments.get("command", "")))
                    for call in kinds:
                        acc.kast[call.value.lower()] += 1
                    if KastCallKind.SEMANTIC in kinds and isinstance(block.get("id"), str):
                        acc.pending_semantic.add(block["id"])
    return acc


def kast_use(acc: Accumulator) -> KastUse:
    semantic = acc.kast["semantic"] + acc.kast["mcp"] + acc.kast["skill_loads"]
    exposure = (
        KastExposure.SEMANTIC
        if semantic or acc.kast["reference"]
        else KastExposure.OPERATIONAL_ONLY
        if acc.kast["operational"]
        else KastExposure.NONE
    )
    outcomes = dict(acc.outcomes)
    if acc.pending_semantic:
        outcomes[KastOutcome.UNCLASSIFIED] = outcomes.get(KastOutcome.UNCLASSIFIED, 0) + len(
            acc.pending_semantic
        )
    return KastUse(
        semantic=acc.kast["semantic"],
        reference=acc.kast["reference"],
        operational=acc.kast["operational"],
        mcp=acc.kast["mcp"],
        skill_loads=acc.kast["skill_loads"],
        semantic_outcomes=outcomes,
        exposure=exposure,
    )


def tokens(counter: Counter[str]) -> Tokens:
    return Tokens(
        input=counter["input"],
        output=counter["output"],
        cache_read=counter["cache_read"],
        cache_write=counter["cache_write"],
    )


BaseResolver = Callable[[datetime], str | None]


def summarize(archive: Path, resolve_base: BaseResolver | None) -> list[SessionSummary]:
    latest = latest_versions(read_manifest(archive))
    subagents: dict[tuple[str, str], list[SnapshotEntry]] = {}
    for (project, relative), entry in latest.items():
        parts = Path(relative).parts
        if len(parts) == 3 and parts[1] == "subagents":
            subagents.setdefault((project, parts[0]), []).append(entry)

    def lines(entry: SnapshotEntry) -> list[str]:
        data = (archive / "blobs" / f"{entry.sha256}.jsonl").read_bytes()
        if hashlib.sha256(data).hexdigest() != entry.sha256:
            raise SystemExit(
                f"session-tasks: archived blob {entry.sha256} does not match its digest"
            )
        return data.decode(errors="replace").splitlines()

    summaries: list[SessionSummary] = []
    for (project, relative), entry in sorted(latest.items()):
        if len(Path(relative).parts) != 1:
            continue
        acc = accumulate(lines(entry))
        children = subagents.get((project, Path(relative).stem), [])
        child_tokens: Counter[str] = Counter()
        for child in children:
            child_tokens.update(accumulate(lines(child)).tokens)
        use = kast_use(acc)
        first = min(acc.timestamps) if acc.timestamps else None
        summaries.append(
            SessionSummary(
                project=project,
                relative_path=relative,
                transcript_sha256=entry.sha256,
                session_ids=tuple(sorted(acc.session_ids)),
                first_timestamp=first,
                last_timestamp=max(acc.timestamps) if acc.timestamps else None,
                models=tuple(sorted(acc.models)),
                efforts=tuple(sorted(acc.efforts)),
                branches=tuple(sorted(acc.branches)),
                first_prompt=acc.first_prompt,
                human_prompts=acc.human_prompts,
                interruptions=acc.interruptions,
                compactions=acc.compactions,
                tool_calls=dict(acc.tools.most_common()),
                tokens=tokens(acc.tokens),
                reported_cost_usd=acc.cost,
                pull_requests=tuple(sorted(acc.pull_requests)),
                subagents=len(children),
                subagent_tokens=tokens(child_tokens),
                kast=use,
                malformed_lines=acc.malformed,
                control_eligible=use.exposure is KastExposure.NONE and bool(acc.tools),
                inferred_base_commit=resolve_base(first) if resolve_base and first else None,
            )
        )
    by_session: dict[str, list[SessionSummary]] = {}
    for summary in summaries:
        for session in summary.session_ids:
            by_session.setdefault(session, []).append(summary)
    return [
        s.model_copy(
            update={
                "also_recorded_in": tuple(
                    sorted(
                        {o.project for sid in s.session_ids for o in by_session[sid] if o is not s}
                    )
                )
            }
        )
        for s in summaries
    ]


# ---------------------------------------------------------------- task drafts


class SourceSession(Frozen):
    session_id: str
    project: str
    observed_at: datetime
    evidence: tuple[str, ...] = Field(min_length=1)


class PathDerivation(Frozen):
    """Reproducible text derivation of the expected answer. `git grep -l -E` at the base commit."""

    pattern: str
    pathspecs: tuple[str, ...] = Field(min_length=1)
    exclude_paths: tuple[RepoPath, ...] = ()


class ExactPaths(Frozen):
    type: Literal["exact_paths"]
    expected_paths: tuple[RepoPath, ...] = Field(min_length=1)
    derivation: PathDerivation


class Command(Frozen):
    argv: tuple[str, ...] = Field(min_length=1)
    pythonpath: tuple[RepoPath, ...] = ()


class ReferenceCommit(Frozen):
    """The change that landed. Hidden paths are overlaid from it before the commands run."""

    type: Literal["reference_commit"]
    reference_commit: Sha
    hidden_paths: tuple[RepoPath, ...] = ()
    absent_symbols: tuple[str, ...] = ()
    absent_pathspecs: tuple[str, ...] = ("*/src/main/*.kt",)
    commands: tuple[Command, ...] = Field(min_length=1)


class TaskDraft(Frozen):
    schema_version: Literal[1]
    id: Annotated[str, StringConstraints(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$")]
    status: Literal["DRAFT"]
    kind: Literal["answer", "edit"]
    base_commit: Sha
    prompt: str = Field(min_length=1)
    source: SourceSession
    kast_relevance: Literal["EXPECTED", "NOT_EXPECTED"]
    allowed_changed_paths: tuple[RepoPath, ...] = ()
    oracle: Annotated[ExactPaths | ReferenceCommit, Field(discriminator="type")]
    open_questions: tuple[str, ...] = ()


class Git:
    """The only effect boundary for repository facts."""

    def __init__(self, repository: Path) -> None:
        self.repository = repository

    def run(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ("git", "-C", str(self.repository), *arguments),
            capture_output=True,
            text=True,
            check=False,
        )

    def has_commit(self, sha: str) -> bool:
        return self.run("cat-file", "-e", f"{sha}^{{commit}}").returncode == 0

    def has_path(self, sha: str, path: str) -> bool:
        return self.run("cat-file", "-e", f"{sha}:{path}").returncode == 0

    def is_ancestor(self, ancestor: str, descendant: str) -> bool:
        return self.run("merge-base", "--is-ancestor", ancestor, descendant).returncode == 0

    def grep_paths(self, sha: str, pattern: str, pathspecs: Sequence[str]) -> set[str]:
        result = self.run("grep", "-l", "-E", pattern, sha, "--", *pathspecs)
        if result.returncode not in (0, 1):
            raise SystemExit(f"session-tasks: git grep failed: {result.stderr.strip()}")
        return {line.removeprefix(f"{sha}:") for line in result.stdout.splitlines()}

    def changed_paths(self, base: str, head: str) -> set[str]:
        return set(self.run("diff", "--name-only", base, head).stdout.splitlines())

    def symbol_present(self, sha: str, symbol: str, pathspecs: Sequence[str]) -> bool:
        return self.run("grep", "-q", "-w", "-F", symbol, sha, "--", *pathspecs).returncode == 0

    def main_at(self, instant: datetime) -> str | None:
        result = self.run("rev-list", "-1", f"--before={instant.isoformat()}", "origin/main")
        return result.stdout.strip() or None


MeasurementTerms = Callable[[str], list[str]]


def node_measurement_terms(text: str) -> list[str]:
    """Delegate blinding vocabulary to its single owner, participant-surface.mjs."""
    script = (
        "import { measurementTerms } from " + json.dumps(PARTICIPANT_SURFACE.as_uri()) + ";"
        "console.log(JSON.stringify(measurementTerms(process.argv[1])));"
    )
    result = subprocess.run(
        ("node", "--input-type=module", "-e", script, text),
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"session-tasks: blinding check unavailable: {result.stderr.strip()}")
    return cast(list[str], json.loads(result.stdout))


FindingCode = Literal[
    "SCHEMA",
    "BLINDING",
    "BASE_MISSING",
    "PATH_MISSING",
    "ANSWER_MISMATCH",
    "REFERENCE_MISSING",
    "REFERENCE_NOT_DESCENDANT",
    "REFERENCE_OUT_OF_SCOPE",
    "SYMBOL_PRESENT_AT_REFERENCE",
    "SYMBOL_ABSENT_AT_BASE",
]


class Finding(Frozen):
    task: str
    code: FindingCode
    detail: str


def check_task(task: TaskDraft, git: Git, terms: MeasurementTerms) -> list[Finding]:
    findings: list[Finding] = []

    def fail(code: FindingCode, detail: str) -> None:
        findings.append(Finding(task=task.id, code=code, detail=detail))

    if found := terms(task.prompt):
        fail("BLINDING", "prompt names the measurement: " + ", ".join(found))
    if not git.has_commit(task.base_commit):
        fail("BASE_MISSING", task.base_commit)
        return findings
    for path in task.allowed_changed_paths:
        if not git.has_path(task.base_commit, path):
            fail("PATH_MISSING", f"allowed path absent at base: {path}")
    match task.oracle:
        case ExactPaths(expected_paths=expected, derivation=derivation):
            for path in expected:
                if not git.has_path(task.base_commit, path):
                    fail("PATH_MISSING", f"expected path absent at base: {path}")
            derived = git.grep_paths(task.base_commit, derivation.pattern, derivation.pathspecs)
            derived -= set(derivation.exclude_paths)
            if derived != set(expected):
                fail("ANSWER_MISMATCH", f"derived {sorted(derived)} != expected {sorted(expected)}")
        case ReferenceCommit(
            reference_commit=reference,
            hidden_paths=hidden,
            absent_symbols=absent,
            absent_pathspecs=scope,
        ):
            if not git.has_commit(reference):
                fail("REFERENCE_MISSING", reference)
                return findings
            if not git.is_ancestor(task.base_commit, reference):
                fail(
                    "REFERENCE_NOT_DESCENDANT",
                    f"{task.base_commit} is not an ancestor of {reference}",
                )
            for path in hidden:
                if not git.has_path(reference, path):
                    fail("PATH_MISSING", f"hidden path absent at reference: {path}")
            outside = git.changed_paths(task.base_commit, reference) - set(
                task.allowed_changed_paths
            )
            outside -= set(hidden)
            if outside:
                fail(
                    "REFERENCE_OUT_OF_SCOPE",
                    "reference changes unlisted paths: " + ", ".join(sorted(outside)),
                )
            for symbol in absent:
                if not git.symbol_present(task.base_commit, symbol, scope):
                    fail("SYMBOL_ABSENT_AT_BASE", symbol)
                if git.symbol_present(reference, symbol, scope):
                    fail("SYMBOL_PRESENT_AT_REFERENCE", symbol)
    return findings


def load_task(path: Path) -> TaskDraft | Finding:
    try:
        task = TaskDraft.model_validate_json(path.read_text())
    except ValidationError as error:
        return Finding(task=path.stem, code="SCHEMA", detail=str(error))
    if task.id != path.stem:
        return Finding(
            task=path.stem, code="SCHEMA", detail=f"id {task.id} does not match file name"
        )
    return task


# ---------------------------------------------------------------- command line


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="session_tasks.py", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    take = commands.add_parser("snapshot", help="archive new transcript versions")
    take.add_argument("--projects-root", type=Path, default=Path.home() / ".claude" / "projects")
    take.add_argument("--match", default="kast", help="substring of project directory names")
    take.add_argument("--archive", type=Path, required=True)
    summary = commands.add_parser(
        "summarize", help="summarize the latest archived version of each session"
    )
    summary.add_argument("--archive", type=Path, required=True)
    summary.add_argument(
        "--repository", type=Path, help="infer the origin/main commit at each session start"
    )
    summary.add_argument("--output", type=Path, required=True)
    validate = commands.add_parser("validate", help="check task drafts against the repository")
    validate.add_argument("--repository", type=Path, required=True)
    validate.add_argument("tasks", type=Path, nargs="+")
    arguments = parser.parse_args(argv)

    match arguments.command:
        case "snapshot":
            report = snapshot(
                arguments.projects_root,
                arguments.match,
                arguments.archive.resolve(),
                datetime.now(UTC),
            )
            print(report.model_dump_json())
            return 0
        case "summarize":
            git = Git(arguments.repository) if arguments.repository else None
            rows = summarize(arguments.archive.resolve(), git.main_at if git else None)
            descriptor = os.open(arguments.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as output:
                _ = output.write(
                    json.dumps([r.model_dump(mode="json") for r in rows], indent=1) + "\n"
                )
            eligible = sum(r.control_eligible for r in rows)
            print(json.dumps({"sessions": len(rows), "control_eligible": eligible}))
            return 0
        case _:
            git = Git(arguments.repository)
            findings: list[Finding] = []
            for path in arguments.tasks:
                loaded = load_task(path)
                findings.extend(
                    [loaded]
                    if isinstance(loaded, Finding)
                    else check_task(loaded, git, node_measurement_terms)
                )
            for finding in findings:
                print(finding.model_dump_json(), file=sys.stderr)
            print(json.dumps({"tasks": len(arguments.tasks), "findings": len(findings)}))
            return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
