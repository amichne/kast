"""Check build storage and report known generated outputs without deleting files."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

OUTPUTS = (
    "build", ".gradle", "runtime/hosted/build", "distribution/cli/build",
    ".github/tools/mintlify/node_modules", "docs/tooling/rendered/node_modules",
)


@dataclass(frozen=True)
class BuildOutput:
    owner: str
    path: str
    allocated_bytes: int
    activity: str


def check(paths: list[Path], minimum: int) -> int:
    volumes = [(path.resolve(strict=True), shutil.disk_usage(path).free) for path in paths]
    ready = all(free >= minimum for _, free in volumes)
    print(json.dumps({
        "status": "READY" if ready else "LOW_SPACE",
        "minimumBytes": minimum,
        "volumes": [{"path": str(path), "freeBytes": free} for path, free in volumes],
    }))
    return 0 if ready else 1


def observed_working_directories() -> set[Path] | None:
    executable = shutil.which("lsof")
    if executable is None:
        return None
    try:
        result = subprocess.run(
            [executable, "-a", "-d", "cwd", "-Fpn"],
            cwd="/", capture_output=True, text=True, timeout=20, check=False,
        )
    except subprocess.TimeoutExpired:
        return None
    if result.returncode or result.stderr:
        return None
    directories: set[Path] = set()
    inspected_pid = f"p{os.getpid()}"
    is_inventory = False
    for line in result.stdout.splitlines():
        if line.startswith("p"):
            is_inventory = line == inspected_pid
        elif line.startswith("n/") and not is_inventory:
            directories.add(Path(line[1:]).resolve())
    return directories


def inventory(repo: Path, minimum: int) -> int:
    worktrees = subprocess.check_output(
        ["git", "-C", str(repo), "worktree", "list", "--porcelain", "-z"], text=True, timeout=15
    )
    directories = observed_working_directories()
    outputs: list[BuildOutput] = []
    unavailable: list[str] = []
    for field in worktrees.split("\0"):
        if not field.startswith("worktree "):
            continue
        owner = Path(field.removeprefix("worktree ")).resolve()
        if not owner.is_dir():
            unavailable.append(str(owner))
            continue
        activity = (
            "unknown" if directories is None else
            "observed-active" if any(path.is_relative_to(owner) for path in directories) else
            "not-observed"
        )
        for relative in OUTPUTS:
            path = owner / relative
            if not path.is_dir() or path.resolve() != path:
                continue
            try:
                result = subprocess.check_output(
                    ["du", "-sk", str(path)], text=True, timeout=30, stderr=subprocess.PIPE
                )
                size = int(result.split(maxsplit=1)[0]) * 1024
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
                unavailable.append(str(path))
                continue
            if size >= minimum:
                outputs.append(BuildOutput(str(owner), str(path), size, activity))
    print(json.dumps({
        "status": "PARTIAL" if unavailable else "COMPLETE",
        "outputs": [asdict(row) for row in sorted(outputs, key=lambda row: -row.allocated_bytes)],
        "unavailable": unavailable,
    }))
    return 1 if unavailable else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preflight = commands.add_parser("check")
    _ = preflight.add_argument("--minimum-bytes", required=True, type=int)
    _ = preflight.add_argument("paths", nargs="+", type=Path)
    report = commands.add_parser("inventory")
    _ = report.add_argument("--repo", type=Path, default=Path.cwd())
    _ = report.add_argument("--minimum-bytes", type=int, default=1 << 30)
    args = parser.parse_args()
    if args.minimum_bytes < 1:
        parser.error("--minimum-bytes must be positive")
    try:
        if args.command == "check":
            return check(args.paths, args.minimum_bytes)
        return inventory(args.repo, args.minimum_bytes)
    except (OSError, subprocess.SubprocessError) as error:
        print(json.dumps({"status": "UNAVAILABLE", "error": str(error)[:500]}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
