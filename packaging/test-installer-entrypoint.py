#!/usr/bin/env python3
"""Public installer invocation contract; no network or machine state changes."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
INSTALLER = ROOT / "install.sh"
DOCUMENTS = (
    ROOT / "README.md",
    ROOT / "docs/public/start.mdx",
    ROOT / "docs/public/reference/compatibility.mdx",
)
CANONICAL_PREFIX = '/bin/bash -c "$(curl -fsSL '


class InstallerEntrypointTest(unittest.TestCase):
    def test_documented_remote_invocations_use_bash_c(self):
        for document in DOCUMENTS:
            text = document.read_text()
            with self.subTest(document=document.relative_to(ROOT)):
                self.assertNotIn("| bash", text)
                self.assertNotIn("| \\\n  bash", text)
                for line in text.splitlines():
                    if "raw.githubusercontent.com/amichne/kast" in line:
                        command = line.lstrip(" \t")
                        self.assertTrue(command.startswith(CANONICAL_PREFIX), line)

    def test_double_dash_delivers_help_to_downloaded_script(self):
        with tempfile.TemporaryDirectory(prefix="kast-installer-entrypoint-") as directory:
            environment = {
                "HOME": directory,
                "PATH": "/usr/bin:/bin",
                "NO_COLOR": "1",
            }
            result = subprocess.run(
                ["/bin/bash", "-c", INSTALLER.read_text(), "--", "--help"],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                timeout=10,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Usage:", result.stdout)


if __name__ == "__main__":
    unittest.main()
