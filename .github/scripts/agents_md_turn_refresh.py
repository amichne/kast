#!/usr/bin/env python3
"""Enforce turn-scoped AGENTS.md review for repo-local Codex hooks."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import subprocess
import sys
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path, PurePosixPath
from typing import Any

from scaffold_agents_md_turn_guides import is_generated_inheritance_guide


STATE_VERSION = 1
IGNORED_PARTS = frozenset({".agent-turn", ".git"})


class RequiredOutcome(str, Enum):
    CREATE = "create"
    UPDATE_OR_UNCHANGED = "update-or-unchanged"
    REMOVE = "remove"


@dataclass(frozen=True)
class GuideOperation:
    directory: str
    guide: str
    requiredOutcome: str
    changedPaths: tuple[str, ...]

    def to_json(self) -> dict[str, Any]:
        value = asdict(self)
        value["changedPaths"] = list(self.changedPaths)
        return value

    def token(self) -> str:
        encoded = json.dumps(self.to_json(), sort_keys=True).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()


class RepositoryFailure(RuntimeError):
    pass


class Repository:
    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        result = self.git("rev-parse", "--show-toplevel")
        discovered = Path(result.stdout.decode().strip()).resolve()
        if discovered != self.root:
            raise RepositoryFailure(
                f"--repo must name the canonical Git root: expected {discovered}, got {self.root}"
            )

    def git(self, *arguments: str) -> subprocess.CompletedProcess[bytes]:
        result = subprocess.run(
            ["git", "-C", str(self.root), *arguments],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if result.returncode != 0:
            message = result.stderr.decode("utf-8", errors="replace").strip()
            raise RepositoryFailure(f"git {' '.join(arguments)} failed: {message}")
        return result

    def state_file(self) -> Path:
        raw = self.git("rev-parse", "--git-path", "codex-hooks/agents-md-turn-refresh.json")
        path = Path(raw.stdout.decode().strip())
        return path if path.is_absolute() else (self.root / path).resolve()

    def head(self) -> str | None:
        result = subprocess.run(
            ["git", "-C", str(self.root), "rev-parse", "--verify", "HEAD"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        return result.stdout.decode().strip() if result.returncode == 0 else None

    def status(self) -> dict[str, dict[str, str | None]]:
        raw = self.git("status", "--porcelain=v1", "-z", "--untracked-files=all").stdout
        fields = raw.decode("utf-8", errors="surrogateescape").split("\0")
        entries: dict[str, dict[str, str | None]] = {}
        index = 0
        while index < len(fields):
            field = fields[index]
            index += 1
            if not field:
                continue
            status = field[:2]
            path = field[3:]
            if included(path):
                entries[path] = {"status": status, "fingerprint": fingerprint(self.root / path)}
            if ("R" in status or "C" in status) and index < len(fields):
                original = fields[index]
                index += 1
                if included(original):
                    entries[original] = {
                        "status": status,
                        "fingerprint": fingerprint(self.root / original),
                    }
        return entries

    def changed_commits(self, baseline_head: str | None) -> set[str]:
        current_head = self.head()
        if baseline_head is None or current_head is None or baseline_head == current_head:
            return set()
        raw = self.git("diff", "--name-only", "-z", baseline_head, current_head).stdout
        return {
            path
            for path in raw.decode("utf-8", errors="surrogateescape").split("\0")
            if included(path)
        }

    def guide_fingerprints(self) -> dict[str, str]:
        guides: dict[str, str] = {}
        for guide in self.root.rglob("AGENTS.md"):
            relative = guide.relative_to(self.root).as_posix()
            if included(relative):
                value = fingerprint(guide)
                if value is not None:
                    guides[relative] = value
        return guides


def included(relative: str) -> bool:
    return bool(relative) and not any(part in IGNORED_PARTS for part in PurePosixPath(relative).parts)


def fingerprint(path: Path) -> str | None:
    try:
        if path.is_symlink():
            payload = os.readlink(path).encode("utf-8", errors="surrogateescape")
        elif path.is_file():
            payload = path.read_bytes()
        else:
            return None
    except FileNotFoundError:
        return None
    return hashlib.sha256(payload).hexdigest()


def load_state(repository: Repository) -> dict[str, Any]:
    path = repository.state_file()
    if not path.exists():
        raise RepositoryFailure("turn state is missing; run the start command at UserPromptSubmit")
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise RepositoryFailure(f"turn state is unreadable: {error}") from error
    if state.get("version") != STATE_VERSION or state.get("repositoryRoot") != str(repository.root):
        raise RepositoryFailure("turn state does not match this repository or schema version")
    required_mappings = ("baselineStatus", "baselineGuides", "resolutions")
    if any(not isinstance(state.get(key), dict) for key in required_mappings):
        raise RepositoryFailure("turn state is missing a required mapping")
    return state


def write_state(repository: Repository, state: dict[str, Any]) -> None:
    path = repository.state_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def start(repository: Repository) -> int:
    state = {
        "version": STATE_VERSION,
        "repositoryRoot": str(repository.root),
        "baselineHead": repository.head(),
        "baselineStatus": repository.status(),
        "baselineGuides": repository.guide_fingerprints(),
        "resolutions": {},
    }
    write_state(repository, state)
    return 0


def turn_paths(repository: Repository, state: dict[str, Any]) -> set[str]:
    baseline = state["baselineStatus"]
    current = repository.status()
    changed = repository.changed_commits(state.get("baselineHead"))
    for path, observation in current.items():
        if baseline.get(path) != observation:
            changed.add(path)
    return {path for path in changed if included(path)}


def guide_path(directory: PurePosixPath) -> str:
    return "AGENTS.md" if directory == PurePosixPath(".") else f"{directory.as_posix()}/AGENTS.md"


def operation_for(
    repository: Repository,
    directory: PurePosixPath,
    changed_paths: set[str],
) -> GuideOperation | None:
    absolute = repository.root if directory == PurePosixPath(".") else repository.root / directory
    if not absolute.is_dir():
        return None
    if absolute.is_symlink():
        raise RepositoryFailure(f"affected directory is a symlink: {directory.as_posix()}")
    guide = absolute / "AGENTS.md"
    other_entries = [entry for entry in absolute.iterdir() if entry.name != "AGENTS.md"]
    owned_entries = [
        entry for entry in other_entries if entry.is_symlink() or not entry.is_dir()
    ]
    generated_without_owned_files = (
        guide.is_file()
        and not owned_entries
        and is_generated_inheritance_guide(repository.root, guide)
    )
    if (
        guide.is_file()
        and directory != PurePosixPath(".")
        and (not other_entries or generated_without_owned_files)
    ):
        outcome = RequiredOutcome.REMOVE
    elif guide.is_file():
        outcome = RequiredOutcome.UPDATE_OR_UNCHANGED
    elif owned_entries:
        outcome = RequiredOutcome.CREATE
    else:
        return None
    prefix = "" if directory == PurePosixPath(".") else f"{directory.as_posix()}/"
    covered = tuple(sorted(path for path in changed_paths if not prefix or path.startswith(prefix)))
    return GuideOperation(
        directory=directory.as_posix(),
        guide=guide_path(directory),
        requiredOutcome=outcome.value,
        changedPaths=covered,
    )


def directory_levels(changed_paths: set[str]) -> dict[PurePosixPath, int]:
    levels: dict[PurePosixPath, int] = {}
    for path in changed_paths:
        directory = PurePosixPath(path).parent
        distance = 0
        for ancestor in (directory, *directory.parents):
            levels[ancestor] = max(levels.get(ancestor, 0), distance)
            distance += 1
    return levels


def operations(repository: Repository, state: dict[str, Any]) -> list[GuideOperation]:
    changed_paths = turn_paths(repository, state)
    levels = directory_levels(changed_paths)
    ordered = sorted(levels, key=lambda item: (levels[item], item.as_posix()))
    return [
        operation
        for directory in ordered
        if (operation := operation_for(repository, directory, changed_paths)) is not None
    ]


def filesystem_satisfies(
    repository: Repository,
    state: dict[str, Any],
    operation: GuideOperation,
) -> bool:
    baseline = state["baselineGuides"].get(operation.guide)
    current = fingerprint(repository.root / operation.guide)
    if operation.requiredOutcome == RequiredOutcome.CREATE.value:
        return baseline is None and current is not None
    if operation.requiredOutcome == RequiredOutcome.REMOVE.value:
        return current is None
    return current is not None and baseline != current


def resolution_satisfies(state: dict[str, Any], operation: GuideOperation) -> bool:
    resolution = state.get("resolutions", {}).get(operation.guide, {})
    return (
        operation.requiredOutcome == RequiredOutcome.UPDATE_OR_UNCHANGED.value
        and resolution.get("outcome") == "unchanged"
        and resolution.get("operationToken") == operation.token()
    )


def pending_operations(repository: Repository, state: dict[str, Any]) -> list[GuideOperation]:
    return [
        operation
        for operation in operations(repository, state)
        if not filesystem_satisfies(repository, state, operation)
        and not resolution_satisfies(state, operation)
    ]


def instruction(pending: list[GuideOperation]) -> str:
    lines = [
        "AGENTS.md review is required before this turn can finish.",
        "Process the operations in order. Preserve correct guidance and add only durable local facts.",
    ]
    for operation in pending:
        lines.append(f"- {operation.requiredOutcome}: {operation.guide}")
        for changed_path in operation.changedPaths[:8]:
            lines.append(f"  covers {changed_path}")
        if operation.requiredOutcome == RequiredOutcome.UPDATE_OR_UNCHANGED.value:
            lines.append(
                "  If no edit is needed, run: "
                f"python3 .github/scripts/agents_md_turn_refresh.py resolve --repo . "
                f"--guide {shlex.quote(operation.guide)} --outcome unchanged"
            )
    return "\n".join(lines)


def stop(repository: Repository) -> int:
    state = load_state(repository)
    pending = pending_operations(repository, state)
    message = instruction(pending) if pending else "AGENTS.md review complete for this turn."
    payload = {
        "schemaVersion": STATE_VERSION,
        "systemMessage": message,
        "operations": [operation.to_json() for operation in pending],
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    if pending:
        print(message, file=sys.stderr)
        return 1
    return 0


def resolve(repository: Repository, guide: str | None, outcome: str | None) -> int:
    if guide is None or outcome != "unchanged":
        raise RepositoryFailure("resolve requires --guide PATH --outcome unchanged")
    normalized = PurePosixPath(guide).as_posix()
    state = load_state(repository)
    candidates = {operation.guide: operation for operation in operations(repository, state)}
    operation = candidates.get(normalized)
    if operation is None:
        raise RepositoryFailure(f"guide is not in the current turn work queue: {normalized}")
    if operation.requiredOutcome != RequiredOutcome.UPDATE_OR_UNCHANGED.value:
        raise RepositoryFailure(
            f"{normalized} requires {operation.requiredOutcome}; it cannot resolve as unchanged"
        )
    if fingerprint(repository.root / normalized) is None:
        raise RepositoryFailure(f"cannot preserve missing guide as unchanged: {normalized}")
    state.setdefault("resolutions", {})[normalized] = {
        "outcome": "unchanged",
        "operationToken": operation.token(),
    }
    write_state(repository, state)
    return 0


def status(repository: Repository) -> int:
    state = load_state(repository)
    print(
        json.dumps(
            {"operations": [operation.to_json() for operation in pending_operations(repository, state)]},
            indent=2,
            sort_keys=True,
        )
    )
    return 0


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "stop", "resolve", "status"))
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--guide")
    parser.add_argument("--outcome", choices=("unchanged",))
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    try:
        repository = Repository(arguments.repo)
        handlers = {
            "start": lambda: start(repository),
            "stop": lambda: stop(repository),
            "resolve": lambda: resolve(repository, arguments.guide, arguments.outcome),
            "status": lambda: status(repository),
        }
        return handlers[arguments.command]()
    except RepositoryFailure as error:
        print(f"agents-md-turn-refresh: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
