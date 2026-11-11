"""Plan or run an isolated, non-publishing documentation refresh."""

from __future__ import annotations

import argparse
import json
import os
import plistlib
import shutil
import subprocess
import sys
import tempfile
import uuid
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Protocol, TypeGuard, assert_never


class Host(StrEnum):
    PI = "pi"
    CODEX = "codex"


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


@dataclass(frozen=True)
class Result:
    stage: Stage
    outcome: Outcome
    worktree: str | None = None
    log: str | None = None


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
                return 1, b""
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
    package = worktree / "docs/tooling/markdown/node_modules/openwiki/dist"
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
    else:
        assert_never(host)
    return Command(args, str(worktree))


def make_plan(repo: Path, state: Path, host: Host, model: str | None, bootstrap: bool) -> Plan:
    repo, state = repo.resolve(), state.resolve()
    owned = state / "<run-id>"
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
            ("git", "worktree", "add", "--detach", str(wt), "origin/main"), str(owned / "clone")
        ),
    ]
    if bootstrap:
        commands += [
            Command(
                ("npm", "ci", "--ignore-scripts", "--engine-strict"),
                str(wt / "docs/tooling/markdown"),
            ),
            Command(("uv", "sync", "--locked", "--project", "docs/tooling"), str(wt)),
        ]
    commands.append(agent_command(host, model, wt, "<review prompt>"))
    return Plan(str(repo), str(state), host, model, bootstrap, tuple(commands))


def allowed(path: str) -> bool:
    return (
        path.startswith("docs/public/")
        or path == "docs/okf-public.json"
        or path.startswith("openwiki/")
        or path.startswith("docs/tooling/knowledge-refresh/")
    )


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
    try:
        state.mkdir(parents=True, exist_ok=True)
        lock = state / "lock"
        lock.mkdir()
    except FileExistsError:
        return Result(Stage.LOCK, Outcome.CONTENDED)
    except OSError:
        return Result(Stage.LOCK, Outcome.UNAVAILABLE)
    owned = state / uuid.uuid4().hex
    wt = owned / "worktree"
    log = owned / "result.json"
    result = Result(Stage.LOCK, Outcome.UNAVAILABLE)
    try:
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
                Command(("git", "worktree", "add", "--detach", str(wt), "origin/main"), str(clone)),
            ),
        )
        for stage, command in commands:
            if executor(command)[0]:
                result = Result(stage, Outcome.FAILED, str(wt), str(log))
                break
        else:
            result = run_worktree(plan, wt, log, executor)
    except OSError:
        result = Result(Stage.LOCK, Outcome.IO_FAILURE, str(wt), str(log))
    finally:
        if owned.exists():
            try:
                _ = log.write_text(json.dumps(asdict(result)) + "\n", encoding="utf-8")
            except OSError:
                result = Result(Stage.LOCK, Outcome.LOG_FAILURE, str(wt), str(log))
        lock.rmdir()
    return result


def run_worktree(plan: Plan, wt: Path, log: Path, executor: Executor) -> Result:
    def fail(stage: Stage, outcome: Outcome) -> Result:
        return Result(stage, outcome, str(wt), str(log))

    if plan.bootstrap:
        for command in (
            Command(
                ("npm", "ci", "--ignore-scripts", "--engine-strict"),
                str(wt / "docs/tooling/markdown"),
            ),
            Command(("uv", "sync", "--locked", "--project", "docs/tooling"), str(wt)),
        ):
            if executor(command)[0]:
                return fail(Stage.BOOTSTRAP, Outcome.FAILED)
    package = wt / "docs/tooling/markdown/node_modules/openwiki"
    try:
        metadata: object = json.loads((package / "package.json").read_text(encoding="utf-8"))
        if not _record(metadata) or metadata.get("version") != "0.9.0":
            return fail(Stage.PREREQUISITES, Outcome.WRONG_OPENWIKI_VERSION)
        if (
            not (package / "dist/cli/cli.js").is_file()
            or not (package / "dist/integrations/pi/openwiki.js").is_file()
        ):
            return fail(Stage.PREREQUISITES, Outcome.MISSING_OPENWIKI_ENTRYPOINT)
        prompt = (wt / "docs/tooling/knowledge-refresh-prompt.md").read_text(encoding="utf-8")
    except (OSError, ValueError):
        return fail(Stage.PREREQUISITES, Outcome.MISSING_OR_INVALID)
    code, initial = executor(Command(("git", "rev-parse", "HEAD"), str(wt)), capture=True)
    if code or not initial.strip():
        return fail(Stage.PREREQUISITES, Outcome.MISSING_HEAD)
    agent_code = executor(agent_command(plan.host, plan.model, wt, prompt))[0]
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
    if agent_code:
        return fail(Stage.AGENT, Outcome.FAILED)
    return fail(Stage.COMPLETE, Outcome.COMPLETED_UNVERIFIED)


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
        str(repo / "docs/tooling"),
        "python",
        str(repo / "docs/tooling/knowledge_refresh.py"),
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
    _ = parser.add_argument("--host", required=True, choices=("pi", "codex"))
    _ = parser.add_argument("--model")
    _ = parser.add_argument("--bootstrap", action="store_true")
    mode = parser.add_mutually_exclusive_group()
    _ = mode.add_argument("--plan", action="store_true")
    _ = mode.add_argument("--run", action="store_true")
    _ = mode.add_argument(
        "--launchd-plan",
        action="store_true",
        help="Print a daily launchd recipe without installing it",
    )
    args = parser.parse_args()
    plan = make_plan(args.repo, args.state_dir, Host(args.host), args.model, args.bootstrap)
    if args.launchd_plan:
        mise = shutil.which("mise")
        if mise is None:
            parser.error("--launchd-plan requires mise on PATH")
        sys.stdout.buffer.write(launchd_document(plan, Path(mise)))
        return 0
    if not args.run:
        print(json.dumps(asdict(plan)))
        return 0
    result = run(plan)
    print(json.dumps(asdict(result)))
    return 0 if result.stage == Stage.COMPLETE else 1


if __name__ == "__main__":
    sys.exit(main())
