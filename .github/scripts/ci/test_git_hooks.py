"""Retiring Kast verification must preserve foreign hooks and symlink targets."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / ".githooks/uninstall.sh"


class RetiredHookTest(unittest.TestCase):
    def test_legacy_dispatch_target_needs_no_build_tools(self):
        result = subprocess.run(
            ["/bin/sh", str(SCRIPT.with_name("pre-push"))],
            env={"PATH": "/unavailable"}, input=b"uncommitted or unrelated ref\n",
            capture_output=True, check=False,
        )
        self.assertEqual(0, result.returncode)
        self.assertEqual(b"", result.stdout + result.stderr)

    def test_only_owned_regular_hooks_are_removed(self):
        for kind in ("absent", "owned", "foreign", "symlink"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                environment = {
                    "PATH": os.defpath,
                    "HOME": str(root),
                    "GIT_CONFIG_GLOBAL": os.devnull,
                    "GIT_CONFIG_NOSYSTEM": "1",
                }
                subprocess.run(["git", "init", "-q", str(root)], env=environment, check=True)
                hook = root / ".git/hooks/pre-push"
                owned = b"#!/bin/sh\n# kast-pre-push-dispatch\nexit 0\n"
                foreign = root / "foreign-hook"
                foreign.write_bytes(owned)
                if kind == "owned":
                    hook.write_bytes(owned)
                elif kind == "foreign":
                    hook.write_bytes(b"#!/bin/sh\nexit 17\n")
                elif kind == "symlink":
                    hook.symlink_to(foreign)
                before = hook.read_bytes() if hook.exists() else None
                for _ in range(2):
                    subprocess.run(
                        ["bash", str(SCRIPT)], cwd=root, env=environment,
                        stdout=subprocess.DEVNULL, check=True,
                    )
                    if kind in {"absent", "owned"}:
                        self.assertFalse(hook.exists())
                    else:
                        self.assertEqual(before, hook.read_bytes())
                        self.assertEqual(kind == "symlink", hook.is_symlink())
                    self.assertEqual(owned, foreign.read_bytes())


if __name__ == "__main__":
    unittest.main()
