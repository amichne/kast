"""Prepare or examine pinned Git checkouts for the Promptfoo corpus."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

from pydantic import BaseModel, Field

GITHUB_REPOSITORY = re.compile(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")


class WorkspacePin(BaseModel):
    workspace: str = Field(pattern=r"^[a-z][a-z0-9-]*$")
    revision: str = Field(pattern=r"^[0-9a-f]{40}$")
    repository: str


def canonical_repository(repository: str) -> str:
    canonical = repository.removesuffix(".git")
    if not GITHUB_REPOSITORY.fullmatch(canonical):
        raise ValueError("expected an HTTPS GitHub repository URL")
    if any(part in {".", ".."} for part in canonical.split("/")[-2:]):
        raise ValueError("expected a GitHub owner and repository name")
    return canonical


def git(workspace: Path, *args: str) -> str:
    env = {name: value for name, value in os.environ.items() if not name.startswith("GIT_")}
    env.update(
        GIT_CONFIG_GLOBAL=os.devnull,
        GIT_CONFIG_NOSYSTEM="1",
        GIT_NO_LAZY_FETCH="1",
        GIT_OPTIONAL_LOCKS="0",
        GIT_TERMINAL_PROMPT="0",
    )
    command = ("git", "-c", f"core.hooksPath={os.devnull}", "-c", "core.fsmonitor=false", *args)
    result = subprocess.run(
        command,
        cwd=workspace,
        env=env,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode:
        raise ValueError(f"{workspace}: git {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def examine(workspace: Path, revision: str, repository: str) -> None:
    """Require a full, clean checkout at the given pin without updating it."""
    if workspace.is_symlink() or not workspace.is_dir():
        raise ValueError(f"{workspace}: expected a Git checkout directory")
    top_level = Path(git(workspace, "rev-parse", "--show-toplevel"))
    if top_level.resolve() != workspace.resolve():
        raise ValueError(f"{workspace}: expected the root of a Git checkout")
    origins = git(workspace, "config", "--local", "--get-all", "remote.origin.url").splitlines()
    if len(origins) != 1 or canonical_repository(origins[0]) != canonical_repository(repository):
        raise ValueError(f"{workspace}: origin differs from the catalog repository")
    actual = git(workspace, "rev-parse", "--verify", "HEAD")
    if actual != revision:
        raise ValueError(f"{workspace}: expected revision {revision}, got {actual}")
    entries = git(workspace, "ls-files", "-v", "-z").split("\0")
    if any(entry and (entry[0] == "S" or entry[0].islower()) for entry in entries):
        raise ValueError(f"{workspace}: index skips tracked contents")
    if git(
        workspace, "status", "--porcelain=v1", "--untracked-files=all", "--ignore-submodules=none"
    ):
        raise ValueError(f"{workspace}: checkout has changes")


def prepare(workspace: Path, pin: WorkspacePin) -> None:
    """Fetch the exact revision, then publish the examined checkout."""
    with TemporaryDirectory(dir=workspace.parent, prefix=f".{workspace.name}-") as temporary:
        staged = Path(temporary) / workspace.name
        staged.mkdir()
        git(staged, "init", "--quiet", "--template=")
        git(staged, "remote", "add", "origin", canonical_repository(pin.repository))
        git(
            staged,
            "fetch",
            "--quiet",
            "--depth=1",
            "--no-tags",
            "--no-recurse-submodules",
            "origin",
            pin.revision,
        )
        git(staged, "checkout", "--quiet", "--detach", "--no-recurse-submodules", pin.revision)
        examine(staged, pin.revision, pin.repository)
        workspace.mkdir()
        try:
            staged.rename(workspace)
        except OSError:
            workspace.rmdir()
            raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="parent directory of the corpus checkouts")
    parser.add_argument("--examine", action="store_true", help="inspect without fetching")
    args = parser.parse_args()
    root = Path(args.root)
    catalog_directory = Path(__file__).resolve().parent
    try:
        pins = [
            WorkspacePin.model_validate_json(catalog.read_bytes())
            for catalog in sorted(catalog_directory.glob("*.cases.json"))
        ]
        if not pins or len({pin.workspace for pin in pins}) != len(pins):
            raise ValueError("expected one catalog per workspace")
        missing: list[WorkspacePin] = []
        for pin in pins:
            canonical_repository(pin.repository)
            workspace = root / pin.workspace
            if args.examine or workspace.exists() or workspace.is_symlink():
                examine(workspace, pin.revision, pin.repository)
            else:
                missing.append(pin)
        if missing:
            root.mkdir(parents=True, exist_ok=True)
        for pin in missing:
            prepare(root / pin.workspace, pin)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")
    for pin in pins:
        print(f"{pin.workspace} {pin.revision}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
