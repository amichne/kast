#!/usr/bin/env python3
"""Behavior tests for the repository knowledge-base validator."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

VALIDATOR = Path(__file__).with_name("code_kb.py")


class KnowledgeBaseValidatorTest(unittest.TestCase):
    def test_unchanged_page_retains_only_proven_historical_source_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            knowledge.mkdir()
            (knowledge / "index.md").write_text("# Index\n")
            source = repository / "old-source.py"
            source.write_text("def owner(): pass\n")
            (repository / "old-document.md").write_text("# Document\n")
            (repository / "alias.py").symlink_to("old-source.py")
            folder = repository / "old-folder"
            folder.mkdir()
            (folder / "member.py").write_text("VALUE = 1\n")
            page = knowledge / "concept.md"
            page_bytes = b"---\ntype: Concept\n---\n# Owner\n[Source](../old-source.py)\n"
            page.write_bytes(page_bytes)
            git = shutil.which("git")
            self.assertIsNotNone(git)
            assert git is not None
            for arguments in (
                ["init", "-q"],
                ["add", "."],
                [
                    "-c",
                    "user.name=Fixture",
                    "-c",
                    "user.email=fixture@example.invalid",
                    "commit",
                    "-qm",
                    "Recorded source link",
                ],
            ):
                subprocess.run(
                    [git, "-C", str(repository), *arguments], check=True, capture_output=True
                )
            source.unlink()
            command = [
                sys.executable,
                str(VALIDATOR),
                "check-source-integrity",
                "--repo",
                str(repository),
                "--docs",
                "knowledge",
                "--strict",
                "--format",
                "json",
            ]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual([], report["issues"])
            self.assertEqual("unresolved", report["assessment"]["status"])
            self.assertEqual(1, len(report["assessment"]["issues"]))
            self.assertEqual(page_bytes, page.read_bytes())
            for extra in (
                b"Changed authored bytes.\n",
                b"[Unknown](../unknown.py)\n",
                b"[Document](../old-document.md)\n",
                b"[Alias](../alias.py)\n",
                b"[Escape](../../outside.py)\n",
            ):
                with self.subTest(extra=extra):
                    page.write_bytes(page_bytes + extra)
                    failed = subprocess.run(command, capture_output=True, text=True, check=False)
                    self.assertEqual(1, failed.returncode, failed.stdout + failed.stderr)
            (repository / "old-document.md").unlink()
            (repository / "alias.py").unlink()
            shutil.rmtree(folder)
            commit_page = [
                git,
                "-C",
                str(repository),
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "commit",
                "-qm",
                "Recorded page variant",
            ]
            for target in (
                "../unknown.py",
                "../old-document.md",
                "../alias.py",
                "../old-folder",
                "../../outside.py",
                str(source),
            ):
                with self.subTest(committed_target=target):
                    page.write_text(f"---\ntype: Concept\n---\n# Owner\n[Source]({target})\n")
                    subprocess.run([git, "-C", str(repository), "add", str(page)], check=True)
                    subprocess.run(commit_page, check=True, capture_output=True)
                    failed = subprocess.run(command, capture_output=True, text=True, check=False)
                    self.assertEqual(1, failed.returncode, failed.stdout + failed.stderr)
            page.write_bytes(page_bytes)
            subprocess.run([git, "-C", str(repository), "add", str(page)], check=True)
            subprocess.run(commit_page, check=True, capture_output=True)
            unchanged = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, unchanged.returncode, unchanged.stdout + unchanged.stderr)
            source.symlink_to("missing.py")
            unsafe = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(1, unsafe.returncode, unsafe.stdout + unsafe.stderr)
            source.unlink()
            head = subprocess.run(
                [git, "-C", str(repository), "rev-parse", "HEAD"], check=True, capture_output=True
            )
            shallow = repository / ".git/shallow"
            shallow.write_bytes(head.stdout)
            incomplete = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(1, incomplete.returncode, incomplete.stdout + incomplete.stderr)
            self.assertIn("history-unavailable", incomplete.stdout)
            shallow.unlink()
            shutil.rmtree(repository / ".git")
            unavailable = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(1, unavailable.returncode, unavailable.stdout + unavailable.stderr)

    def test_source_integrity_reports_deleted_declaration_without_accepting_currentness(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            knowledge.mkdir()
            (knowledge / "index.md").write_text("# Index\n")
            (repository / "owner.py").write_text("VALUE = 1\n")
            concept = knowledge / "concept.md"
            concept.write_text(
                "---\ntype: Concept\ncode_sources:\n  - path: owner.py\n    symbols: [Deleted]\n---\n# Owner\n"
            )
            command = [
                sys.executable,
                str(VALIDATOR),
                "check-source-integrity",
                "--repo",
                str(repository),
                "--docs",
                "knowledge",
                "--strict",
                "--format",
                "json",
            ]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual([], report["issues"])
            self.assertEqual("unresolved", report["assessment"]["status"])
            self.assertIn("Deleted", report["assessment"]["issues"][0]["message"])
            (repository / "owner.py").unlink()
            concept.write_text(concept.read_text() + "[Source](../owner.py)\n")
            missing = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, missing.returncode, missing.stdout + missing.stderr)
            self.assertEqual("unresolved", json.loads(missing.stdout)["assessment"]["status"])
            concept.write_text(concept.read_text() + "[Missing document](missing.md)\n")
            broken_link = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(1, broken_link.returncode, broken_link.stdout + broken_link.stderr)
            concept.write_text(concept.read_text().replace("[Deleted]", "[not-a-symbol]"))
            rejected = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(1, rejected.returncode, rejected.stdout + rejected.stderr)
            self.assertIn("identifier list", rejected.stdout)

    def test_documentation_consumer_rejects_missing_paths_without_declaration_authority(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            knowledge.mkdir()
            (knowledge / "index.md").write_text("# Index\n")
            source = repository / "example.kt"
            source.write_text("class Actual\n")
            (knowledge / "concept.md").write_text(
                "---\ntype: Concept\ncode_sources:\n  - path: example.kt\n    symbols: [NotExamined]\n"
                "---\n# Concept\n[Example](../example.kt)\n"
            )
            command = [
                sys.executable,
                str(VALIDATOR),
                "check-documentation-inputs",
                "--repo",
                str(repository),
                "--docs",
                "knowledge",
                "--strict",
                "--format",
                "json",
            ]
            passed = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, passed.returncode, passed.stdout + passed.stderr)
            report = json.loads(passed.stdout)
            self.assertEqual("DOCUMENTATION_INPUTS", report["evidence"])
            self.assertEqual([], report["issues"])
            source.unlink()
            failed = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(1, failed.returncode, failed.stdout + failed.stderr)
            self.assertEqual(
                {
                    "code_sources path does not exist: example.kt",
                    "link target does not exist: ../example.kt",
                },
                {issue["message"] for issue in json.loads(failed.stdout)["issues"]},
            )

    def test_openwiki_metadata_preserves_symbol_checks_and_source_impact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            knowledge.mkdir()
            (knowledge / "index.md").write_text("# Index\n", encoding="utf-8")
            (repository / "source.py").write_text("class Present: pass\n", encoding="utf-8")
            (repository / "owner.py").write_text("def owner(): pass\n", encoding="utf-8")
            (knowledge / "concept.md").write_text(
                "---\ntype: Test Concept\ntitle: Concept\n"
                "tags:\n  - python\n"
                "generated: { by: codex, at: '2026-10-01T00:00:00Z' }\n"
                "verified:\n  - by: openwiki/0.6.1\n    at: '2026-10-01T00:00:00Z'\n"
                "sources:\n  - id: owner\n    resource: repo://owner.py\n"
                "code_sources:\n  - path: source.py\n    symbols:\n      - Present\n"
                "---\n# Concept\n",
                encoding="utf-8",
            )
            result = self.check_bundle(repository)
            self.assertEqual(0, result.returncode, result.stdout)
            impact = subprocess.run(
                [
                    sys.executable,
                    str(VALIDATOR),
                    "impact",
                    "--repo",
                    str(repository),
                    "--docs",
                    "knowledge",
                    "--changed-file",
                    "owner.py",
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                check=False,
            )
            self.assertEqual(0, impact.returncode, impact.stdout)
            self.assertIn("knowledge/concept.md: owner.py", impact.stdout)

    def test_duplicate_frontmatter_cannot_replace_source_bindings(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            knowledge = repository / "knowledge"
            knowledge.mkdir()
            (knowledge / "index.md").write_text("# Index\n", encoding="utf-8")
            (knowledge / "concept.md").write_text(
                "---\ntype: Concept\ncode_sources:\n  - path: missing.py\n"
                "code_sources: []\n---\n# Concept\n",
                encoding="utf-8",
            )
            result = self.check_bundle(repository)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn("duplicate YAML", result.stdout)

    def check_bundle(self, repository: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
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
                'class Present\n// class CommentOnly\nval example = "class StringOnly"\n',
                encoding="utf-8",
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
                self.assertIn(
                    f"code_sources symbol is not declared in src/First.kt: {missing}", result.stdout
                )

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
                "    symbols: [Present]\n    symbols: [Deleted]\n---\n# Concept\n",
                encoding="utf-8",
            )
            result = self.check_bundle(repository)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn("duplicate YAML field: symbols", result.stdout)

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
                    f"    symbols: [{symbol}]\n---\n# Concept\n",
                    encoding="utf-8",
                )
            result = self.check_bundle(repository)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn(
                "knowledge/two.md: code_sources symbol is not declared in source.kt: Deleted",
                result.stdout,
            )
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
            self.assertIn(
                "code_sources symbol is not declared in src/module.py: quoted", result.stdout
            )


if __name__ == "__main__":
    unittest.main()
