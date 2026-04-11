#!/usr/bin/env python3
"""Find the AGENTS.md files affected by a git diff surface."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

DIFF_FILTER = "ACDMRTUXB"


@dataclass(frozen=True)
class PathChange:
    path: str
    status: str
    source: str


def run_git(repo_root: Path, *args: str) -> bytes:
    result = subprocess.run(
        ["git", *args],
        cwd=repo_root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError(message or f"git {' '.join(args)} failed")
    return result.stdout


def resolve_repo_root(start_path: Path) -> Path:
    output = run_git(start_path, "rev-parse", "--show-toplevel")
    return Path(output.decode("utf-8", "replace").strip()).resolve()


def parse_name_status_z(payload: bytes, source: str) -> list[PathChange]:
    text = payload.decode("utf-8", "surrogateescape")
    tokens = [token for token in text.split("\0") if token]
    changes: list[PathChange] = []
    index = 0

    while index < len(tokens):
        status_token = tokens[index]
        index += 1
        kind = status_token[:1]
        if kind in {"R", "C"}:
            old_path = tokens[index]
            new_path = tokens[index + 1]
            index += 2
            changes.append(PathChange(path=old_path, status=f"{kind}-old", source=source))
            changes.append(PathChange(path=new_path, status=f"{kind}-new", source=source))
            continue
        path = tokens[index]
        index += 1
        changes.append(PathChange(path=path, status=kind, source=source))

    return changes


def collect_worktree_changes(repo_root: Path) -> list[PathChange]:
    changes: list[PathChange] = []
    diff_calls = (
        ("unstaged", ("diff", "--name-status", "-z", f"--diff-filter={DIFF_FILTER}")),
        (
            "staged",
            ("diff", "--cached", "--name-status", "-z", f"--diff-filter={DIFF_FILTER}"),
        ),
    )

    for source, args in diff_calls:
        payload = run_git(repo_root, *args)
        changes.extend(parse_name_status_z(payload, source))

    untracked = run_git(repo_root, "ls-files", "--others", "--exclude-standard", "-z")
    for token in untracked.decode("utf-8", "surrogateescape").split("\0"):
        if token:
            changes.append(PathChange(path=token, status="?", source="untracked"))

    return changes


def collect_range_changes(repo_root: Path, base: str, head: str) -> list[PathChange]:
    payload = run_git(
        repo_root,
        "diff",
        "--name-status",
        "-z",
        f"--diff-filter={DIFF_FILTER}",
        f"{base}...{head}",
    )
    return parse_name_status_z(payload, "range")


def agent_path_for_dir(directory: PurePosixPath) -> PurePosixPath:
    if str(directory) in {"", "."}:
        return PurePosixPath("AGENTS.md")
    return directory / "AGENTS.md"


def existing_agents_for_path(repo_root: Path, relative_path: str) -> list[str]:
    path = PurePosixPath(relative_path)
    candidates: list[PurePosixPath] = []
    seen: set[str] = set()

    if path.name == "AGENTS.md":
        key = str(path)
        seen.add(key)
        if (repo_root / path).is_file():
            candidates.append(path)

    directory = path.parent
    for current in [directory, *directory.parents]:
        candidate = agent_path_for_dir(current)
        key = str(candidate)
        if key in seen:
            continue
        seen.add(key)
        if (repo_root / candidate).is_file():
            candidates.append(candidate)

    return [str(candidate) for candidate in candidates]


def build_payload(
    repo_root: Path,
    changes: list[PathChange],
    mode: str,
    base: str | None,
    head: str | None,
) -> dict[str, object]:
    path_summary: dict[str, dict[str, set[str] | str]] = {}
    for change in changes:
        entry = path_summary.setdefault(
            change.path,
            {
                "path": change.path,
                "sources": set(),
                "statuses": set(),
            },
        )
        entry["sources"].add(change.source)
        entry["statuses"].add(change.status)

    agent_coverage: dict[str, set[str]] = defaultdict(set)
    for relative_path in path_summary:
        for agent_path in existing_agents_for_path(repo_root, relative_path):
            agent_coverage[agent_path].add(relative_path)

    changed_paths = [
        {
            "path": entry["path"],
            "sources": sorted(entry["sources"]),
            "statuses": sorted(entry["statuses"]),
        }
        for entry in path_summary.values()
    ]
    changed_paths.sort(key=lambda item: item["path"])

    agent_files = []
    for agent_path, covered_paths in sorted(
        agent_coverage.items(),
        key=lambda item: (item[0].count("/"), item[0]),
    ):
        parent = PurePosixPath(agent_path).parent
        scope = "." if str(parent) in {"", "."} else str(parent)
        agent_files.append(
            {
                "path": agent_path,
                "scope": scope,
                "covered_paths": sorted(covered_paths),
            }
        )

    return {
        "repo_root": str(repo_root),
        "mode": mode,
        "base": base,
        "head": head,
        "changed_paths": changed_paths,
        "agent_files": agent_files,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Find AGENTS.md files affected by git changes.",
    )
    parser.add_argument(
        "--repo",
        default=".",
        help="Path inside the target git repository. Defaults to the cwd.",
    )
    parser.add_argument(
        "--base",
        help="Base ref for range mode. When set, use git diff <base>...<head>.",
    )
    parser.add_argument(
        "--head",
        default="HEAD",
        help="Head ref for range mode. Defaults to HEAD.",
    )
    parser.add_argument(
        "--format",
        choices=("json", "paths"),
        default="json",
        help="Output JSON metadata or just the affected AGENTS.md paths.",
    )
    args = parser.parse_args()

    try:
        repo_root = resolve_repo_root(Path(args.repo).resolve())
        if args.base:
            changes = collect_range_changes(repo_root, args.base, args.head)
            mode = "range"
        else:
            changes = collect_worktree_changes(repo_root)
            mode = "worktree"
        payload = build_payload(repo_root, changes, mode, args.base, args.head)
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if args.format == "paths":
        for agent in payload["agent_files"]:
            print(agent["path"])
        return 0

    json.dump(payload, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
