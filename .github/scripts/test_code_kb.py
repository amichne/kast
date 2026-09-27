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
    def check_bundle(self, repository: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VALIDATOR), "check", "--repo", str(repository), "--docs", "knowledge", "--strict"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            text=True,
        )

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

    def test_every_cited_symbol_must_be_declared_in_its_own_source_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            source = repository / "src"
            knowledge.mkdir()
            source.mkdir()
            (knowledge / "index.md").write_text("# Index\n", encoding="utf-8")
            (source / "First.kt").write_text(
                'class Present\n// class CommentOnly\nval example = "class StringOnly"\n', encoding="utf-8"
            )
            (source / "Second.kt").write_text("class Elsewhere\n", encoding="utf-8")
            concept = knowledge / "concept.md"

            def cite(symbols: str) -> None:
                concept.write_text(
                    "---\ntype: Test Concept\ncode_sources:\n  - path: src/First.kt\n"
                    f"    symbols: [{symbols}]\n---\n\n# Concept\n",
                    encoding="utf-8",
                )

            for missing in ("Elsewhere", "CommentOnly", "StringOnly"):
                cite(f"Present, {missing}")
                result = self.check_bundle(repository)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn(f"code_sources symbol is not declared in src/First.kt: {missing}", result.stdout)

            cite("Present")
            result = self.check_bundle(repository)
            self.assertEqual(0, result.returncode, result.stdout)

    def test_orphaned_symbols_cannot_overwrite_the_previous_source_symbols(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            knowledge.mkdir()
            (knowledge / "index.md").write_text("# Index\n", encoding="utf-8")
            (repository / "source.py").write_text("class Present: pass\n", encoding="utf-8")
            (knowledge / "concept.md").write_text(
                "---\ntype: Test Concept\ncode_sources:\n  - path: source.py\n"
                "    symbols: [Present]\n    symbols: [Deleted]\n---\n# Concept\n", encoding="utf-8"
            )
            result = self.check_bundle(repository)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn("duplicate YAML list field: symbols", result.stdout)

    def test_all_pages_are_checked_and_invalid_kotlin_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            knowledge.mkdir()
            (knowledge / "index.md").write_text("# Index\n", encoding="utf-8")
            source = repository / "source.kt"
            source.write_text("internal class Present\n", encoding="utf-8")
            for page, symbol in (("one", "Present"), ("two", "Deleted")):
                (knowledge / f"{page}.md").write_text(
                    "---\ntype: Test Concept\ncode_sources:\n  - path: source.kt\n"
                    f"    symbols: [{symbol}]\n---\n# Concept\n", encoding="utf-8"
                )
            result = self.check_bundle(repository)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn("knowledge/two.md: code_sources symbol is not declared in source.kt: Deleted", result.stdout)
            source.write_text("class Present {", encoding="utf-8")
            result = self.check_bundle(repository)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn("INVALID_KOTLIN", result.stdout)

    def test_python_symbol_citation_uses_declarations(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            source = repository / "src"
            knowledge.mkdir()
            source.mkdir()
            (knowledge / "index.md").write_text("# Index\n", encoding="utf-8")
            (source / "module.py").write_text(
                'def actual():\n    return "def quoted():"\n', encoding="utf-8"
            )
            concept = knowledge / "concept.md"
            concept.write_text(
                "---\ntype: Test Concept\ncode_sources:\n  - path: src/module.py\n"
                "    symbols: [actual, quoted]\n---\n\n# Concept\n",
                encoding="utf-8",
            )
            result = self.check_bundle(repository)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn("code_sources symbol is not declared in src/module.py: quoted", result.stdout)


if __name__ == "__main__":
    unittest.main()
