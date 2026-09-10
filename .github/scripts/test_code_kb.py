#!/usr/bin/env python3
"""Behavior tests for the repository knowledge-base validator."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


VALIDATOR = Path(__file__).with_name("code_kb.py")


class KnowledgeBaseValidatorTest(unittest.TestCase):
    def test_strict_check_rejects_broken_relative_link(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            knowledge.mkdir()
            (knowledge / "index.md").write_text(
                "---\nokf_version: 1.0\n---\n\n# Index\n\n[Concept](concept.md)\n",
                encoding="utf-8",
            )
            (knowledge / "concept.md").write_text(
                "---\ntype: Test Concept\n---\n\n# Concept\n\n[Missing](missing.md)\n",
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(VALIDATOR),
                    "check",
                    "--repo",
                    str(repository),
                    "--docs",
                    "knowledge",
                    "--strict",
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
                text=True,
            )

        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("link target does not exist: missing.md", result.stdout)


if __name__ == "__main__":
    unittest.main()
