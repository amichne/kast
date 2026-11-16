"""Plan or run an isolated, non-publishing documentation refresh."""

from __future__ import annotations

import argparse
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
from collections.abc import Mapping
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal, Protocol, TypeGuard, assert_never


class Host(StrEnum):
    PI = "pi"
    CODEX = "codex"
    NATIVE = "native"


class Outcome(StrEnum):
    STATE_INSIDE_CHECKOUT = "state_inside_checkout"
    NOT_EXACT_TOP_LEVEL = "not_exact_top_level"
    MISSING_ORIGIN = "missing_origin"
    CONTENDED = "contended"
    UNAVAILABLE = "unavailable"
    FAILED = "failed"
    IO_FAILURE = "io_failure"
    LOG_FAILURE = "log_failure"
    WRONG_OPENWIKI_VERSION = "wrong_openwiki_version"
    MISSING_OPENWIKI_ENTRYPOINT = "missing_openwiki_entrypoint"
    MISSING_OR_INVALID = "missing_or_invalid"
    MISSING_HEAD = "missing_head"
    HEAD_CHANGED_OR_UNKNOWN = "head_changed_or_unknown"
    STATUS_FAILED = "status_failed"
    OUTSIDE_SCOPE = "outside_scope"
    COMPLETED_UNVERIFIED = "completed_unverified"
    CREDENTIAL_MISSING = "credential_missing"
    INTERRUPTED = "interrupted"
    SOURCE_CHANGED = "source_changed"
    NATIVE_STATE_UNAVAILABLE = "native_state_unavailable"
    CHECKPOINT_MISMATCH = "checkpoint_mismatch"
    NATIVE_INCOMPLETE = "native_incomplete"
    QUALIFICATION_FAILED = "qualification_failed"
    INTEGRATION_ELIGIBLE = "integration_eligible"
    NO_CHANGES = "no_changes"
    PENDING = "pending"


class Stage(StrEnum):
    REPOSITORY = "repository"
    LOCK = "lock"
    CLONE = "clone"
    FETCH = "fetch"
    WORKTREE = "worktree"
    BOOTSTRAP = "bootstrap"
    PREREQUISITES = "prerequisites"
    AGENT = "agent"
    SCOPE = "scope"
    NATIVE = "native"
    QUALIFICATION = "qualification"
    READINESS = "readiness"
    COMPLETE = "complete"


@dataclass(frozen=True)
class Command:
    argv: tuple[str, ...]
    cwd: str


@dataclass(frozen=True)
class Plan:
    repo: str
    state_dir: str
    host: Host
    model: str | None
    bootstrap: bool
    commands: tuple[Command, ...]
    source_revision: str | None = None
    resume: str | None = None


@dataclass(frozen=True, slots=True)
class SourceIdentity:
    revision: str
    fingerprint: str


class Checkpoint(StrEnum):
    ABSENT = "absent"
    ACTIVE = "active"
    MISMATCHED = "mismatched"


@dataclass(frozen=True, slots=True)
class NativeInspection:
    source: SourceIdentity
    checkpoint: Checkpoint
    complete: bool


@dataclass(frozen=True, slots=True)
class Qualification:
    source: SourceIdentity
    inputs_digest: str


class CredentialStatus(StrEnum):
    MISSING = "missing"
    PRESENT_UNVERIFIED = "present_unverified"
    HOST_MANAGED_UNVERIFIED = "host_managed_unverified"


@dataclass(frozen=True, slots=True)
class Readiness:
    credential: CredentialStatus
    authentication: Literal["not-assessed"] = "not-assessed"
    daily_schedule: Literal["blocked"] = "blocked"


def readiness(host: Host, environment: Mapping[str, str]) -> Readiness:
    # Presence is not authentication, model access, billing, or scheduler readiness.
    return Readiness(
        CredentialStatus.HOST_MANAGED_UNVERIFIED
        if host is not Host.NATIVE
        else CredentialStatus.PRESENT_UNVERIFIED
        if environment.get("OPENAI_API_KEY", "").strip()
        else CredentialStatus.MISSING
    )


@dataclass(frozen=True)
class Result:
    stage: Stage
    outcome: Outcome
    worktree: str | None = None
    log: str | None = None
    source: SourceIdentity | None = None
    qualification: Qualification | None = None

    def __post_init__(self) -> None:
        if self.outcome is Outcome.INTEGRATION_ELIGIBLE:
            if (
                self.stage is not Stage.COMPLETE
                or self.qualification is None
                or self.qualification.source != self.source
            ):
                raise ValueError(
                    "Integration eligibility needs qualification for the frozen source"
                )
        elif self.qualification is not None:
            raise ValueError("Only an integration-eligible result carries qualification")


class Executor(Protocol):
    def __call__(self, command: Command, *, capture: bool = False) -> tuple[int, bytes]: ...


def execute(command: Command, *, capture: bool = False) -> tuple[int, bytes]:
    # Agent output may contain credentials or source; never retain it.
    try:
        with tempfile.TemporaryFile() as output:
            result = subprocess.run(
                command.argv,
                cwd=command.cwd,
                stdin=subprocess.DEVNULL,
                stdout=output if capture else subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
                timeout=1800,
            )
            _ = output.seek(0)
            data = output.read(1_048_577) if capture else b""
            if len(data) > 1_048_576:
                return 125, b""
            return result.returncode, data
    except subprocess.TimeoutExpired:
        return 124, b""
    except OSError:
        return 127, b""


def _mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def _record(value: object) -> TypeGuard[dict[str, object]]:
    return _mapping(value) and all(isinstance(key, str) for key in value)


def agent_command(host: Host, model: str | None, worktree: Path, prompt: str) -> Command:
    package = worktree / "docs/internal/tooling/markdown/node_modules/openwiki/dist"
    if host is Host.PI:
        args = (
            "pi",
            "--print",
            "--provider",
            "openai-codex",
            "--model",
            model or "gpt-6.1-terra",
            "--no-session",
            "--no-extensions",
            "--extension",
            str(package / "integrations/pi/openwiki.js"),
            "--",
            prompt,
        )
    elif host is Host.CODEX:
        cli = str(package / "cli/cli.js")
        # JSON string literals are valid TOML basic strings.
        args = (
            "codex",
            "exec",
            "--sandbox",
            "workspace-write",
            "--ephemeral",
            "-c",
            'mcp_servers.openwiki.command="node"',
            "-c",
            f'mcp_servers.openwiki.args=[{json.dumps(cli)},"mcp","--host","codex"]',
        )
        if model:
            args += ("--model", model)
        args += ("--", prompt)
    elif host is Host.NATIVE:
        # The locked CLI owns the same native lifecycle as the host integrations.
        args = ("node", str(package / "cli/cli.js"), "code", "--update", "--print")
        if model:
            args += ("--modelId", model)
    else:
        assert_never(host)
    return Command(args, str(worktree))


def make_plan(
    repo: Path,
    state: Path,
    host: Host,
    model: str | None,
    bootstrap: bool,
    source_revision: str | None = None,
    resume: str | None = None,
) -> Plan:
    repo, state = repo.resolve(), state.resolve()
    owned = state / (resume or "<run-id>")
    wt = owned / "worktree"
    commands = [
        Command(("git", "rev-parse", "--show-toplevel"), str(repo)),
        Command(("git", "remote", "get-url", "origin"), str(repo)),
        Command(
            (
                "git",
                "clone",
                "--no-checkout",
                "--origin",
                "origin",
                "<origin-url>",
                str(owned / "clone"),
            ),
            str(state),
        ),
        Command(("git", "fetch", "origin", "main"), str(owned / "clone")),
        Command(
            ("git", "worktree", "add", "--detach", str(wt), source_revision or "origin/main"),
            str(owned / "clone"),
        ),
    ]
    if resume is not None:
        commands = commands[:2]
    if bootstrap:
        commands += [
            Command(
                ("npm", "ci", "--ignore-scripts", "--engine-strict"),
                str(wt / "docs/internal/tooling/markdown"),
            ),
            Command(("uv", "sync", "--locked", "--project", "docs/internal/tooling"), str(wt)),
        ]
    commands.append(agent_command(host, model, wt, "<review prompt>"))
    return Plan(
        str(repo), str(state), host, model, bootstrap, tuple(commands), source_revision, resume
    )


def allowed(path: str) -> bool:
    return (
        path.startswith("docs/public/")
        or path == "docs/okf-public.json"
        or path.startswith("openwiki/")
        or path.startswith("docs/internal/tooling/knowledge-refresh/")
    )


# Read-only projection of the pinned producer's parsers and completion checks.
# Never repair, begin, finalize, or write native state from this inspection.
NATIVE_INSPECTION = """
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
const root = process.cwd();
const load = name => import(pathToFileURL(resolve(root,
  'docs/internal/tooling/markdown/node_modules/openwiki/dist', name)).href);
const {OpenWikiIgnore} = await load('agent/openwiki-ignore.js');
const {createRepositorySourceSnapshot, createRunContext} = await load('agent/utils.js');
const {readRepositoryRunState} = await load('generation/run-state.js');
const {readRepositoryPageManifest, getCurrentRepositoryPageCompletion} =
  await load('generation/page-manifest.js');
const {ClaimsStore} = await load('claims/brains/code/store.js');
const ignore = await OpenWikiIgnore.load(root);
const source = await createRepositorySourceSnapshot(root, ignore);
const run = await readRepositoryRunState(root);
const checkpoint = run === null ? 'absent' :
  run.targetGitHead === source.gitHead && run.sourceFingerprint === source.fingerprint
    ? 'active' : 'mismatched';
const metadata = (await createRunContext(root, 'repository')).lastUpdate;
const store = new ClaimsStore(root);
const pages = await store.discoverPages();
const sidecars = await store.discoverSidecarPages();
const manifest = await readRepositoryPageManifest(root);
let complete = checkpoint === 'absent' && metadata?.status === 'complete'
  && metadata.gitHead === source.gitHead && pages.length > 0
  && sidecars.every(page => pages.includes(page));
for (const page of pages) {
  // Sparse reconciliation preserves older checkpoints for untouched pages.
  // Recheck their exact durable bytes, not a fabricated current-source stamp.
  const entry = manifest.pages[page];
  if (!entry || !await getCurrentRepositoryPageCompletion(root, page, entry)) complete = false;
}
const after = await createRepositorySourceSnapshot(root, await OpenWikiIgnore.load(root));
if (after.gitHead !== source.gitHead || after.fingerprint !== source.fingerprint)
  throw new Error('Source changed during native inspection');
process.stdout.write(JSON.stringify({revision: source.gitHead,
  fingerprint: source.fingerprint, checkpoint, complete}));
"""


def inspect_native(wt: Path, executor: Executor) -> NativeInspection | Outcome:
    code, data = executor(
        Command(("node", "--input-type=module", "--eval", NATIVE_INSPECTION), str(wt)), capture=True
    )
    if code:
        return Outcome.NATIVE_STATE_UNAVAILABLE
    try:
        value: object = json.loads(data)
    except ValueError:
        return Outcome.NATIVE_STATE_UNAVAILABLE
    if not _record(value) or set(value) != {"revision", "fingerprint", "checkpoint", "complete"}:
        return Outcome.NATIVE_STATE_UNAVAILABLE
    revision, fingerprint, checkpoint, complete = (
        value["revision"],
        value["fingerprint"],
        value["checkpoint"],
        value["complete"],
    )
    if (
        not isinstance(revision, str)
        or not re.fullmatch(r"[0-9a-f]{40}", revision)
        or not isinstance(fingerprint, str)
        or not re.fullmatch(r"sha256:[0-9a-f]{64}", fingerprint)
        or not isinstance(checkpoint, str)
        or checkpoint not in {item.value for item in Checkpoint}
        or not isinstance(complete, bool)
    ):
        return Outcome.NATIVE_STATE_UNAVAILABLE
    return NativeInspection(SourceIdentity(revision, fingerprint), Checkpoint(checkpoint), complete)


def qualify(wt: Path, source: SourceIdentity, executor: Executor) -> Qualification | Outcome:
    """Consume the existing publication owner, never infer admission from agent exit."""
    if not (wt / "docs/internal/tooling/publication.py").is_file():
        return Outcome.COMPLETED_UNVERIFIED
    command = (
        "uv",
        "run",
        "--locked",
        "--project",
        "docs/internal/tooling",
        "python",
        "docs/internal/tooling/publication.py",
        "--root",
        str(wt),
    )
    code, before = executor(Command((*command, "--snapshot"), str(wt)), capture=True)
    if code:
        return Outcome.COMPLETED_UNVERIFIED
    code, report = executor(Command(command, str(wt)), capture=True)
    if code:
        return Outcome.QUALIFICATION_FAILED if code == 1 else Outcome.COMPLETED_UNVERIFIED
    after_code, after = executor(Command((*command, "--snapshot"), str(wt)), capture=True)
    if after_code:
        return Outcome.COMPLETED_UNVERIFIED
    try:
        initial: object = json.loads(before)
        current: object = json.loads(after)
        result: object = json.loads(report)
    except ValueError:
        return Outcome.COMPLETED_UNVERIFIED
    if not _record(initial) or not _record(current) or not _record(result):
        return Outcome.COMPLETED_UNVERIFIED
    digest = initial.get("inputsDigest")
    if (
        initial != current
        or initial.get("type") != "DOCUMENTATION_INPUTS"
        or initial.get("sourceRevision") != source.revision
        or not isinstance(digest, str)
        or not re.fullmatch(r"[0-9a-f]{64}", digest)
        or result.get("type") != "DOCUMENTATION_PUBLICATION"
        or result.get("producer") != "kast-documentation-publication-v1"
        or result.get("decision") != "accepted"
        or result.get("sourceRevision") != source.revision
        or result.get("inputsDigest") != digest
        or result.get("inputs") != initial.get("inputs")
    ):
        return Outcome.QUALIFICATION_FAILED
    return Qualification(source, digest)


def record_result(log: Path, result: Result) -> Result:
    """Atomically preserve source identity before effects and replace the final outcome."""
    pending_path: Path | None = None
    recorded = result
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=log.parent, delete=False
        ) as pending:
            pending_path = Path(pending.name)
            _ = pending.write(json.dumps(asdict(result)) + "\n")
        pending_path.replace(log)
    except OSError:
        recorded = Result(Stage.LOCK, Outcome.LOG_FAILURE, result.worktree, str(log), result.source)
    finally:
        if pending_path is not None:
            try:
                pending_path.unlink(missing_ok=True)
            except OSError:
                recorded = Result(
                    Stage.LOCK, Outcome.LOG_FAILURE, result.worktree, str(log), result.source
                )
    return recorded


def run(plan: Plan, executor: Executor = execute) -> Result:
    repo, state = Path(plan.repo), Path(plan.state_dir)
    if state == repo or repo in state.parents:
        return Result(Stage.REPOSITORY, Outcome.STATE_INSIDE_CHECKOUT)
    code, top = executor(Command(("git", "rev-parse", "--show-toplevel"), str(repo)), capture=True)
    if code or Path(os.fsdecode(top.strip())).resolve() != repo:
        return Result(Stage.REPOSITORY, Outcome.NOT_EXACT_TOP_LEVEL)
    code, remote = executor(
        Command(("git", "remote", "get-url", "origin"), str(repo)), capture=True
    )
    if code or not remote.strip() or b"\n" in remote.strip():
        return Result(Stage.REPOSITORY, Outcome.MISSING_ORIGIN)
    if (
        plan.host is Host.NATIVE
        and readiness(plan.host, os.environ).credential is CredentialStatus.MISSING
    ):
        return Result(Stage.READINESS, Outcome.CREDENTIAL_MISSING)
    if plan.resume is not None and not re.fullmatch(r"[0-9a-f]{32}", plan.resume):
        return Result(Stage.NATIVE, Outcome.CHECKPOINT_MISMATCH)
    try:
        state.mkdir(parents=True, exist_ok=True)
        lock = state / "lock"
        lock.mkdir()
    except FileExistsError:
        return Result(Stage.LOCK, Outcome.CONTENDED)
    except OSError:
        return Result(Stage.LOCK, Outcome.UNAVAILABLE)
    owned = state / (plan.resume or uuid.uuid4().hex)
    wt = owned / "worktree"
    log = owned / "result.json"
    result = Result(Stage.LOCK, Outcome.UNAVAILABLE)
    persist_result = plan.resume is None
    try:
        if plan.resume is not None:
            # result.json already owns orchestration identity; native .run.json owns the queue.
            try:
                if owned.is_symlink() or wt.is_symlink():
                    return Result(Stage.NATIVE, Outcome.CHECKPOINT_MISMATCH, str(wt), str(log))
                saved: object = json.loads(log.read_text(encoding="utf-8"))
                if (
                    not _record(saved)
                    or saved.get("worktree") != str(wt)
                    or not _record(saved.get("source"))
                ):
                    return Result(Stage.NATIVE, Outcome.CHECKPOINT_MISMATCH, str(wt), str(log))
                recorded = saved["source"]
                if not _record(recorded):
                    return Result(Stage.NATIVE, Outcome.CHECKPOINT_MISMATCH, str(wt), str(log))
                revision, fingerprint = recorded.get("revision"), recorded.get("fingerprint")
                if (
                    not isinstance(revision, str)
                    or not re.fullmatch(r"[0-9a-f]{40}", revision)
                    or not isinstance(fingerprint, str)
                    or not re.fullmatch(r"sha256:[0-9a-f]{64}", fingerprint)
                ):
                    return Result(Stage.NATIVE, Outcome.CHECKPOINT_MISMATCH, str(wt), str(log))
                expected = SourceIdentity(revision, fingerprint)
            except (OSError, ValueError):
                return Result(Stage.NATIVE, Outcome.CHECKPOINT_MISMATCH, str(wt), str(log))
            result = run_worktree(plan, wt, log, executor, expected)
            persist_result = (
                result.source == expected and result.outcome is not Outcome.CHECKPOINT_MISMATCH
            )
        else:
            owned.mkdir()
            clone = owned / "clone"
            commands = (
                (
                    Stage.CLONE,
                    Command(
                        (
                            "git",
                            "clone",
                            "--no-checkout",
                            "--origin",
                            "origin",
                            os.fsdecode(remote.strip()),
                            str(clone),
                        ),
                        str(owned),
                    ),
                ),
                (Stage.FETCH, Command(("git", "fetch", "origin", "main"), str(clone))),
                (
                    Stage.WORKTREE,
                    Command(
                        (
                            "git",
                            "worktree",
                            "add",
                            "--detach",
                            str(wt),
                            plan.source_revision or "origin/main",
                        ),
                        str(clone),
                    ),
                ),
            )
            for stage, command in commands:
                if executor(command)[0]:
                    result = Result(stage, Outcome.FAILED, str(wt), str(log))
                    break
            else:
                # An interrupted inspection must not erase the source journal
                # written before the host effect. Resume depends on that identity.
                persist_result = False
                result = run_worktree(plan, wt, log, executor)
                persist_result = True
    except KeyboardInterrupt:
        persist_result = False
        result = Result(Stage.NATIVE, Outcome.INTERRUPTED, str(wt), str(log))
    except OSError:
        result = Result(Stage.LOCK, Outcome.IO_FAILURE, str(wt), str(log))
    finally:
        if owned.exists() and persist_result:
            result = record_result(log, result)
        lock.rmdir()
    return result


def run_worktree(
    plan: Plan, wt: Path, log: Path, executor: Executor, expected: SourceIdentity | None = None
) -> Result:
    source: SourceIdentity | None = None

    def fail(stage: Stage, outcome: Outcome) -> Result:
        return Result(stage, outcome, str(wt), str(log), source)

    if plan.bootstrap:
        for command in (
            Command(
                ("npm", "ci", "--ignore-scripts", "--engine-strict"),
                str(wt / "docs/internal/tooling/markdown"),
            ),
            Command(("uv", "sync", "--locked", "--project", "docs/internal/tooling"), str(wt)),
        ):
            if executor(command)[0]:
                return fail(Stage.BOOTSTRAP, Outcome.FAILED)
    package = wt / "docs/internal/tooling/markdown/node_modules/openwiki"
    try:
        metadata: object = json.loads((package / "package.json").read_text(encoding="utf-8"))
        if not _record(metadata) or metadata.get("version") != "0.9.0":
            return fail(Stage.PREREQUISITES, Outcome.WRONG_OPENWIKI_VERSION)
        if (
            not (package / "dist/cli/cli.js").is_file()
            or not (package / "dist/integrations/pi/openwiki.js").is_file()
        ):
            return fail(Stage.PREREQUISITES, Outcome.MISSING_OPENWIKI_ENTRYPOINT)
        prompt = (wt / "docs/internal/tooling/knowledge-refresh-prompt.md").read_text(
            encoding="utf-8"
        )
    except (OSError, ValueError):
        return fail(Stage.PREREQUISITES, Outcome.MISSING_OR_INVALID)
    code, initial = executor(Command(("git", "rev-parse", "HEAD"), str(wt)), capture=True)
    if code or not initial.strip():
        return fail(Stage.PREREQUISITES, Outcome.MISSING_HEAD)
    before = inspect_native(wt, executor)
    if isinstance(before, Outcome):
        return fail(Stage.NATIVE, before)
    source = before.source
    if (
        source.revision != os.fsdecode(initial.strip())
        or (plan.source_revision is not None and source.revision != plan.source_revision)
        or (expected is not None and source != expected)
        or before.checkpoint is Checkpoint.MISMATCHED
    ):
        return fail(Stage.NATIVE, Outcome.CHECKPOINT_MISMATCH)
    pending = record_result(log, fail(Stage.AGENT, Outcome.PENDING))
    if pending.outcome is Outcome.LOG_FAILURE:
        return pending
    try:
        agent_code = (
            0
            if expected is not None and before.complete
            else executor(agent_command(plan.host, plan.model, wt, prompt))[0]
        )
    except KeyboardInterrupt:
        return fail(Stage.AGENT, Outcome.INTERRUPTED)
    code, head = executor(Command(("git", "rev-parse", "HEAD"), str(wt)), capture=True)
    if code or head != initial:
        return fail(Stage.SCOPE, Outcome.HEAD_CHANGED_OR_UNKNOWN)
    code, status = executor(
        Command(
            (
                "git",
                "-c",
                "status.renames=false",
                "status",
                "--porcelain=v1",
                "-z",
                "--untracked-files=all",
            ),
            str(wt),
        ),
        capture=True,
    )
    if code:
        return fail(Stage.SCOPE, Outcome.STATUS_FAILED)
    entries = status.split(b"\0")
    if any(len(entry) < 4 or not allowed(os.fsdecode(entry[3:])) for entry in entries if entry):
        return fail(Stage.SCOPE, Outcome.OUTSIDE_SCOPE)
    after = inspect_native(wt, executor)
    if isinstance(after, Outcome):
        return fail(Stage.NATIVE, after)
    if after.source != source:
        return fail(Stage.SCOPE, Outcome.SOURCE_CHANGED)
    if after.checkpoint is Checkpoint.MISMATCHED:
        return fail(Stage.NATIVE, Outcome.CHECKPOINT_MISMATCH)
    if agent_code:
        return fail(
            Stage.AGENT,
            Outcome.INTERRUPTED if agent_code in {-2, -15, 124, 130, 143} else Outcome.FAILED,
        )
    if after.checkpoint is Checkpoint.ACTIVE:
        return fail(Stage.NATIVE, Outcome.NATIVE_INCOMPLETE)
    if not after.complete:
        return fail(Stage.COMPLETE, Outcome.COMPLETED_UNVERIFIED)
    changed = {os.fsdecode(entry[3:]) for entry in entries if entry}
    if changed <= {"openwiki/.last-update.json", "openwiki/.page-manifest.json"}:
        return fail(Stage.COMPLETE, Outcome.NO_CHANGES)
    qualification = qualify(wt, source, executor)
    if isinstance(qualification, Outcome):
        return fail(Stage.QUALIFICATION, qualification)
    final = inspect_native(wt, executor)
    if isinstance(final, Outcome):
        return fail(Stage.NATIVE, final)
    if final.source != source:
        return fail(Stage.SCOPE, Outcome.SOURCE_CHANGED)
    if final.checkpoint is not Checkpoint.ABSENT or not final.complete:
        return fail(Stage.NATIVE, Outcome.NATIVE_INCOMPLETE)
    return Result(
        Stage.COMPLETE, Outcome.INTEGRATION_ELIGIBLE, str(wt), str(log), source, qualification
    )


def launchd_document(plan: Plan, mise: Path) -> bytes:
    """Return a daily user-agent recipe; never install or load it implicitly."""
    repo = Path(plan.repo)
    command = [
        str(mise),
        "exec",
        "--",
        "uv",
        "run",
        "--locked",
        "--project",
        str(repo / "docs/internal/tooling"),
        "python",
        str(repo / "docs/internal/tooling/knowledge_refresh.py"),
        "--repo",
        plan.repo,
        "--state-dir",
        plan.state_dir,
        "--host",
        plan.host.value,
        "--bootstrap",
        "--run",
    ]
    if plan.model:
        command += ["--model", plan.model]
    # plistlib owns XML escaping; this is an OS launchd property-list boundary.
    return plistlib.dumps(
        {
            "Label": "io.github.amichne.kast.knowledge-refresh",
            "ProgramArguments": command,
            "WorkingDirectory": plan.repo,
            "StartCalendarInterval": {"Hour": 9, "Minute": 0},
            "ProcessType": "Background",
        }
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    _ = parser.add_argument("--repo", required=True, type=Path)
    _ = parser.add_argument("--state-dir", required=True, type=Path)
    _ = parser.add_argument("--host", required=True, choices=tuple(Host))
    _ = parser.add_argument("--model")
    _ = parser.add_argument("--bootstrap", action="store_true")
    _ = parser.add_argument("--source-revision", help="Exact fetched commit for a fresh candidate")
    _ = parser.add_argument(
        "--resume", help="Resume an owned run ID without fetching or replacing its worktree"
    )
    mode = parser.add_mutually_exclusive_group()
    _ = mode.add_argument("--plan", action="store_true")
    _ = mode.add_argument("--run", action="store_true")
    _ = mode.add_argument(
        "--readiness",
        action="store_true",
        help="Report credential presence without authentication or secret values",
    )
    _ = mode.add_argument(
        "--launchd-plan",
        action="store_true",
        help="Print a daily launchd recipe without installing it",
    )
    args = parser.parse_args()
    if args.source_revision is not None and not re.fullmatch(r"[0-9a-f]{40}", args.source_revision):
        parser.error("--source-revision needs a full lowercase Git commit identity")
    if args.resume is not None and not re.fullmatch(r"[0-9a-f]{32}", args.resume):
        parser.error("--resume needs the owned 32-character run ID")
    if args.readiness:
        report = readiness(Host(args.host), os.environ)
        print(json.dumps(asdict(report)))
        return 1 if report.credential is CredentialStatus.MISSING else 0
    plan = make_plan(
        args.repo,
        args.state_dir,
        Host(args.host),
        args.model,
        args.bootstrap,
        args.source_revision,
        args.resume,
    )
    if args.launchd_plan:
        mise = shutil.which("mise")
        if mise is None:
            parser.error("--launchd-plan requires mise on PATH")
        _ = sys.stdout.buffer.write(launchd_document(plan, Path(mise)))
        return 0
    if not args.run:
        print(json.dumps(asdict(plan)))
        return 0
    result = run(plan)
    print(json.dumps(asdict(result)))
    return (
        130
        if result.outcome is Outcome.INTERRUPTED
        else 0
        if result.outcome in {Outcome.INTEGRATION_ELIGIBLE, Outcome.NO_CHANGES}
        else 1
    )


if __name__ == "__main__":
    sys.exit(main())
