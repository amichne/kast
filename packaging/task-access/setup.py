#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = []
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Explicitly resolve a new scaffold, then verify both project and script modes."""

from __future__ import annotations

import subprocess
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parent
    commands = (
        ("lock",),
        ("lock", "--script", "../../agent-tools/skills/kast/scripts/access.py"),
        ("lock", "--script", "../../agent-tools/skills/kast/scripts/server.py"),
        ("sync", "--locked"),
        (
            "run",
            "--locked",
            "ruff",
            "format",
            "--check",
            ".",
            "../../agent-tools/skills/kast/scripts",
        ),
        ("run", "--locked", "ruff", "check", ".", "../../agent-tools/skills/kast/scripts"),
        ("run", "--locked", "pyright"),
        ("run", "--locked", "pytest"),
        (
            "run",
            "--locked",
            "--script",
            "../../agent-tools/skills/kast/scripts/access.py",
            "--help",
        ),
    )
    for arguments in commands:
        try:
            result = subprocess.run(["uv", *arguments], cwd=root, check=False)
        except OSError:
            print(f"setup: stage={arguments[0]} outcome=uv-unavailable")
            return 2
        if result.returncode:
            print(f"setup: stage={arguments[0]} outcome=failed exit={result.returncode}")
            return result.returncode
    print("setup: outcome=verified; review and commit both dependency locks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
