"""Decide whether a change requires the rendered Get started reader journey.

The documentation workflow runs this selector before the browser journey. It compares the
reader-path inputs between `BASE_SHA` and `HEAD_SHA`. A pull request compares from the merge base
(`base...head`); any other event compares the two revisions directly.

An unknown base, or a comparison that Git cannot complete, selects the journey. The selector
never skips the journey on missing evidence. Without `GITHUB_OUTPUT`, the decision goes to
standard output, so a local run with no base reports `run=true`.
"""

from __future__ import annotations

import os
import subprocess
from collections.abc import Callable, Mapping
from enum import StrEnum
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]

# Inputs that change what a reader sees on the Get started path, or how the journey runs.
PATHS = (
    "docs/public/docs.json",
    "docs/public/index.mdx",
    "docs/public/start.mdx",
    "docs/public/search.mdx",
    "docs/public/troubleshooting.mdx",
    "docs/public/style.css",
    "docs/public/sidebar-toggle.js",
    "docs/internal/tooling/rendered",
    ".github/tools/mintlify",
    ".github/workflows/docs.yml",
    "config/ci/tasks/docs.toml",
)

# `git diff --quiet` exits 0 when the paths are unchanged, 1 when they changed, and another
# status when it could not compare. Only 0 establishes that the journey can be skipped.
_UNCHANGED = 0


class Decision(StrEnum):
    RUN = "true"
    SKIP = "false"


def revisions(event: str, base: str, head: str) -> tuple[str, ...]:
    """The revision arguments that `git diff` compares for this event."""
    return (f"{base}...{head}",) if event == "pull_request" else (base, head)


def decide(event: str, base: str, head: str, diff: Callable[[tuple[str, ...]], int]) -> Decision:
    """Select the journey unless a completed comparison shows every reader input unchanged."""
    if not base:
        return Decision.RUN
    status = diff(("git", "diff", "--quiet", *revisions(event, base, head), "--", *PATHS))
    return Decision.SKIP if status == _UNCHANGED else Decision.RUN


def git_status(command: tuple[str, ...]) -> int:
    return subprocess.run(command, cwd=ROOT, check=False).returncode


def main(environment: Mapping[str, str] = os.environ) -> int:
    decision = decide(
        environment.get("GITHUB_EVENT_NAME", ""),
        environment.get("BASE_SHA", ""),
        environment.get("HEAD_SHA") or "HEAD",
        git_status,
    )
    line = f"run={decision.value}\n"
    output = environment.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a") as stream:
            stream.write(line)
    print(line, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
