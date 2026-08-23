#!/usr/bin/env python3
"""Manage minimal inheritance guides for the current AGENTS.md policy."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path, PurePosixPath
from typing import Any

class ScaffoldFailure(RuntimeError):
    """Finite failure at the scaffold process boundary."""


def load_operations(root: Path) -> list[dict[str, Any]]:
    completed = subprocess.run(
        [
            "python3",
            str(root / ".github/scripts/agents_md_turn_refresh.py"),
            "status",
            "--repo",
            str(root),
        ],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise ScaffoldFailure(completed.stderr.strip() or "cannot read the turn guide queue")
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise ScaffoldFailure(f"turn guide queue is not JSON: {error}") from error
    operations = value.get("operations")
    if not isinstance(operations, list):
        raise ScaffoldFailure("turn guide queue has no operations list")
    return [item for item in operations if isinstance(item, dict)]


def nearest_owner_guide(
    guide: PurePosixPath,
    available: set[PurePosixPath],
) -> PurePosixPath:
    for parent in guide.parent.parents:
        candidate = parent / "AGENTS.md"
        if candidate in available:
            return candidate
    candidate = PurePosixPath("AGENTS.md")
    if candidate in available:
        return candidate
    raise ScaffoldFailure(f"{guide} has no owning ancestor guide")


def relative_link(guide: PurePosixPath, owner: PurePosixPath) -> str:
    return PurePosixPath(os.path.relpath(owner.as_posix(), guide.parent.as_posix())).as_posix()


def scope_description(directory: PurePosixPath) -> str:
    parts = directory.parts
    if "src" in parts:
        if "test" in parts:
            return "test sources and fixtures"
        if "main" in parts:
            return "production sources"
        return "source-set directories"
    if directory == PurePosixPath(".github/workflows"):
        return "GitHub Actions workflow definitions"
    if directory == PurePosixPath("gradle/architecture"):
        return "checked architecture policy inputs"
    return "files and child directories"


def title(directory: PurePosixPath) -> str:
    return f"# `{directory.as_posix()}` guide"


def render(guide: PurePosixPath, owner: PurePosixPath) -> str:
    directory = guide.parent
    link = relative_link(guide, owner)
    scope = scope_description(directory)
    return (
        f"{title(directory)}\n\n"
        f"This directory owns {scope} under `{directory.as_posix()}`. "
        f"Follow [the nearest owner guide]({link}) for boundaries, invariants, and verification.\n\n"
        "## Local scope\n\n"
        "- Keep changes within the parent guide's ownership.\n"
        "- Add local rules only when this directory gains a distinct durable boundary.\n"
    )


def is_generated_inheritance_guide(root: Path, guide: Path) -> bool:
    relative_guide = PurePosixPath(guide.relative_to(root).as_posix())
    if relative_guide == PurePosixPath("AGENTS.md"):
        return False
    content = guide.read_text(encoding="utf-8")
    for owner_directory in relative_guide.parent.parents:
        owner = owner_directory / "AGENTS.md"
        if content == render(relative_guide, owner):
            return True
    return False


def empty_owner_guides(root: Path) -> list[PurePosixPath]:
    targets: list[PurePosixPath] = []
    for guide in root.rglob("AGENTS.md"):
        if any(part in {".agent-turn", ".git"} for part in guide.parts):
            continue
        if not is_generated_inheritance_guide(root, guide):
            continue
        owned_entries = [
            entry
            for entry in guide.parent.iterdir()
            if entry.name != "AGENTS.md" and (entry.is_symlink() or not entry.is_dir())
        ]
        if not owned_entries:
            targets.append(PurePosixPath(guide.relative_to(root).as_posix()))
    return sorted(targets, key=lambda path: path.as_posix())


def prune_empty_owner_guides(root: Path, write: bool) -> int:
    targets = empty_owner_guides(root)
    for guide in targets:
        if write:
            (root / guide).unlink()
        else:
            print(guide.as_posix())
    return len(targets)


def scaffold(root: Path, write: bool, refresh: bool) -> int:
    operations = load_operations(root)
    creates = {
        PurePosixPath(str(item["guide"]))
        for item in operations
        if item.get("requiredOutcome") == "create"
    }
    existing_paths = [
        path
        for path in root.rglob("AGENTS.md")
        if ".git" not in path.parts and ".agent-turn" not in path.parts
    ]
    generated = {
        PurePosixPath(path.relative_to(root).as_posix())
        for path in existing_paths
        if is_generated_inheritance_guide(root, path)
    }
    owners = {
        PurePosixPath(path.relative_to(root).as_posix())
        for path in existing_paths
        if PurePosixPath(path.relative_to(root).as_posix()) not in generated
    }
    pending = creates - {PurePosixPath(path.relative_to(root).as_posix()) for path in existing_paths}
    targets = sorted(pending | (generated if refresh else set()), key=lambda path: path.as_posix())
    for guide in targets:
        owner = nearest_owner_guide(guide, owners)
        output = root / guide
        if write:
            output.write_text(render(guide, owner), encoding="utf-8")
        else:
            print(guide.as_posix())
    return len(targets)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--prune-empty-owners", action="store_true")
    args = parser.parse_args()
    try:
        root = args.repo.resolve()
        if args.prune_empty_owners:
            count = prune_empty_owner_guides(root, args.write)
        else:
            count = scaffold(root, args.write, args.refresh)
    except (OSError, ScaffoldFailure) as error:
        print(f"guide scaffold failed: {error}", file=sys.stderr)
        return 1
    if args.prune_empty_owners:
        action = "removed" if args.write else "pending"
        print(f"AGENTS.md empty-owner prune {action}: {count}")
    else:
        action = "created" if args.write else "pending"
        print(f"AGENTS.md scaffold {action}: {count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
