#!/usr/bin/env python3
"""Create minimal inheritance guides required by the current AGENTS.md turn queue."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path, PurePosixPath
from typing import Any

SCAFFOLD_MARKER = "Add local rules only when this directory gains a distinct durable boundary."


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
        if SCAFFOLD_MARKER in path.read_text(encoding="utf-8")
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
    args = parser.parse_args()
    try:
        root = args.repo.resolve()
        count = scaffold(root, args.write, args.refresh)
    except (OSError, ScaffoldFailure) as error:
        print(f"guide scaffold failed: {error}", file=sys.stderr)
        return 1
    action = "created" if args.write else "pending"
    print(f"AGENTS.md scaffold {action}: {count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
