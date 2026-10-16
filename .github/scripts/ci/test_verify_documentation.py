#!/usr/bin/env python3
"""The docs lane retains the parser, terminology tests, scope and generated-view checks."""

import unittest

from verify_documentation import commands


class DocumentationProofTest(unittest.TestCase):
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
