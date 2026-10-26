#!/usr/bin/env python3
"""The docs lane retains the parser, terminology tests, scope and generated-view checks."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from verify_documentation import commands


class DocumentationProofTest(unittest.TestCase):
    def test_stale_preview_rejects_when_the_interactive_view_matches(self):
        parity = next(command for command in commands(()) if command[:2] == ("git", "diff"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(("git", "init", "-q", directory), check=True)
            for path in ("docs/public/snippets/value-journey.jsx", "docs/public/images/value-journey.svg"):
                output = root / path
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text("generated\n")
            subprocess.run(("git", "add", "."), cwd=root, check=True)
            self.assertEqual(0, subprocess.run(parity, cwd=root, capture_output=True).returncode)
            (root / "docs/public/images/value-journey.svg").write_text("stale preview\n")
            self.assertEqual(1, subprocess.run(parity, cwd=root, capture_output=True).returncode)

    def test_navigation_and_snippet_inputs_keep_their_real_consumers(self):
        plan = commands(("AGENTS.md",))
        self.assertIn(("npm", "run", "build", "--prefix", "docs/tooling/value-journey"), plan)
        self.assertIn(("git", "diff", "--exit-code", "--", "docs/public/snippets/value-journey.jsx",
                       "docs/public/images/value-journey.svg"), plan)
        self.assertIn(("uv", "run", "--locked", "--project", "docs/tooling", "python", ".github/scripts/code_kb.py",
                       "check-documentation-inputs", "--repo", ".", "--docs", "openwiki", "--strict", "--format", "json"), plan)

    def test_glossary_proof_retains_all_consumed_prose_and_projection_checks(self):
        self.assertEqual(
            (
                "uv",
                "run",
                "--locked",
                "--project",
                "docs/tooling",
                "python",
                "docs/tooling/check_glossary.py",
                "docs/glossary.json",
                "README.md",
                "docs",
                "AGENTS.md",
                "cli/AGENTS.md",
                "--check-render",
                "docs/GLOSSARY.md",
                "--check-vale",
                "config/vale/styles/Kast",
            ),
            commands(("AGENTS.md", "cli/AGENTS.md"))[-1],
        )

    def test_parser_and_existing_documentation_tests_precede_prose_validation(self):
        plan = commands(("AGENTS.md",))
        self.assertEqual(
            ("npm", "ci", "--ignore-scripts", "--engine-strict", "--prefix", "docs/tooling/markdown"), plan[0]
        )
        self.assertEqual(("npm", "run", "check", "--prefix", "docs/tooling/markdown"), plan[1])
        self.assertEqual(("uv", "run", "--locked", "--project", "docs/tooling", "pytest", "docs/tooling"), plan[2])


if __name__ == "__main__":
    unittest.main()
