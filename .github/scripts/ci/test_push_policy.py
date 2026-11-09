"""Retired Kast dispatchers permit pushes without hidden local qualification."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

# The dispatcher installed by .githooks/install.sh before retirement.
LEGACY_DISPATCHER = """#!/usr/bin/env bash
# kast-pre-push-dispatch
set -euo pipefail
repo_root="$(git rev-parse --show-toplevel)"
hook="${repo_root}/.githooks/pre-push"
[[ -x "${hook}" ]] || exit 0
exec "${hook}" "$@"
"""


class PushPolicyTest(unittest.TestCase):
    def test_legacy_dispatcher_permits_dirty_head_and_other_committed_refs(self) -> None:
        for other_ref in (False, True):
            with self.subTest(other_ref=other_ref):
                self.push_case(other_ref)

    def push_case(self, other_ref: bool) -> None:
        with tempfile.TemporaryDirectory(prefix="kast push policy ") as directory:
            owned = Path(directory).resolve()
            checkout = owned / "checkout"
            checkout.mkdir()
            commands = owned / "bin"
            commands.mkdir()
            marker = owned / "unexpected-validation"
            mise = commands / "mise"
            _ = mise.write_text(f'#!/bin/sh\ntouch "{marker}"\nexit 97\n')
            mise.chmod(0o755)
            environment = {
                "PATH": str(commands) + os.pathsep + os.defpath,
                "HOME": str(owned),
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_TERMINAL_PROMPT": "0",
            }

            def git(*arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
                return subprocess.run(
                    ("git", *arguments),
                    cwd=checkout,
                    env=environment,
                    capture_output=True,
                    text=True,
                    check=check,
                )

            _ = git("init", "-q")
            source = checkout / "source"
            _ = source.write_text("committed\n")
            _ = git("add", "source")
            _ = git(
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "commit",
                "-qm",
                "initial",
            )
            expected = git("rev-parse", "HEAD").stdout.strip()
            _ = git("branch", "candidate")
            if other_ref:
                _ = source.write_text("later commit\n")
                _ = git("add", "source")
                _ = git(
                    "-c",
                    "user.name=Fixture",
                    "-c",
                    "user.email=fixture@example.invalid",
                    "commit",
                    "-qm",
                    "later",
                )
            _ = source.write_text("uncommitted user work\n")
            hook = checkout / ".git/hooks/pre-push"
            _ = hook.write_text(LEGACY_DISPATCHER)
            hook.chmod(0o755)
            current_hook = ROOT / ".githooks/pre-push"
            if current_hook.exists():
                destination = checkout / ".githooks/pre-push"
                destination.parent.mkdir()
                _ = shutil.copy2(current_hook, destination)
            remote = owned / "remote.git"
            _ = git("init", "--bare", "-q", str(remote))
            pushed = git("push", str(remote), "candidate:refs/heads/candidate", check=False)
            self.assertEqual(0, pushed.returncode, pushed.stderr)
            self.assertEqual(
                expected,
                git("--git-dir", str(remote), "rev-parse", "refs/heads/candidate").stdout.strip(),
            )
            self.assertEqual("uncommitted user work\n", source.read_text())
            self.assertFalse(marker.exists(), "push started local qualification")
            self.assertEqual(LEGACY_DISPATCHER, hook.read_text())


if __name__ == "__main__":
    _ = unittest.main()
