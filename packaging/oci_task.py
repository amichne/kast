#!/usr/bin/env python3
"""Start a command in one selected workspace in a fresh Kast environment."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from composed_bundle import BundleFailure, BundleRejected, trust_installed_workspace
from managed_headless import Rejected


def main(
    runtime_root: Path = Path("/opt/kast/runtime"), workspace_root: Path = Path("/workspace")
) -> int:
    parser = argparse.ArgumentParser(prog="kast-task", description=__doc__)
    _ = parser.add_argument(
        "--runtime-root", type=Path, default=runtime_root, help="Installed composed runtime"
    )
    _ = parser.add_argument(
        "--workspace-root",
        type=Path,
        default=workspace_root,
        help="Canonical parent directory for task workspaces",
    )
    _ = parser.add_argument("workspace", type=Path)
    _ = parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    workspace: Path = args.workspace
    command: list[str] = args.command or ["bash"]
    if not command[0]:
        parser.error("Expected a command name")
    runtime_root = args.runtime_root
    workspace_root = args.workspace_root
    try:
        if (
            workspace_root.resolve() != workspace_root
            or not workspace_root.is_dir()
            or workspace == workspace_root
            or not workspace.is_relative_to(workspace_root)
        ):
            print(f"kast-task: {BundleFailure.UNSAFE_PATH}", file=sys.stderr)
            return 2
        grant = trust_installed_workspace(runtime_root, workspace)
        if isinstance(grant, (BundleRejected, Rejected)):
            print(f"kast-task: {grant.reason}", file=sys.stderr)
            return 2
        os.chdir(grant.workspace)
    except (OSError, ValueError):
        print("kast-task: WORKSPACE_SETUP_FAILED", file=sys.stderr)
        return 2
    try:
        os.execvp(command[0], command)
    except FileNotFoundError:
        print("kast-task: COMMAND_NOT_FOUND", file=sys.stderr)
        return 127
    except OSError:
        print("kast-task: COMMAND_START_FAILED", file=sys.stderr)
        return 126


if __name__ == "__main__":
    raise SystemExit(main())
