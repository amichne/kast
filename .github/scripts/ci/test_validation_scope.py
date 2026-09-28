#!/usr/bin/env python3
"""Proof that only independent public docs edits bypass the Kotlin product gate."""
from pathlib import Path
from contextlib import redirect_stdout
import io
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from validation_scope import Reason, Scope, classify_paths, main, select


class ValidationScopeTest(unittest.TestCase):
    def test_public_page_and_navigation_edits_use_docs_validation(self):
        self.assertEqual(
            Scope.PUBLIC_DOCS,
            classify_paths((
                "docs/public/reference/api.mdx",
                "docs/public/concepts/architecture.mdx",
                "docs/public/docs.json",
            )),
        )

    def test_product_inputs_keep_full_gate(self):
        for path in (
            "docs/public/start.mdx",
            "docs/public/reference/compatibility.mdx",
            "docs/public/reference/callables.openapi.json",
            "docs/public/images/observer.png",
            "knowledge/modules/distribution.md",
            "distribution/cli/src/main/kotlin/Management.kt",
            ".github/workflows/ci.yml",
        ):
            with self.subTest(path=path):
                self.assertEqual(Scope.PRODUCT, classify_paths(("docs/public/reference/api.mdx", path)))

    def test_empty_or_unrecognized_diff_keeps_full_gate(self):
        for paths in ((), ("docs/public/AGENTS.md",), ("docs/public/reference/api.mdx\nother",)):
            with self.subTest(paths=paths):
                self.assertEqual(Scope.PRODUCT, classify_paths(paths))

    def test_non_pr_or_unavailable_diff_keeps_full_gate(self):
        sha = "a" * 40
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(Reason.NOT_PULL_REQUEST, select("push", "", sha, root).reason)
            self.assertEqual(Reason.INVALID_REVISION, select("pull_request", "missing", sha, root).reason)
            self.assertEqual(Reason.DIFF_UNAVAILABLE, select("pull_request", sha, sha, root).reason)

    def test_pr_diff_uses_exact_commits_and_includes_deletions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*arguments):
                return subprocess.run(["git", *arguments], cwd=root, check=True,
                                      stdout=subprocess.PIPE, text=True).stdout.strip()

            git("init", "-q")
            git("config", "user.email", "ci@example.invalid")
            git("config", "user.name", "CI")
            page = root / "docs/public/reference/api.mdx"
            page.parent.mkdir(parents=True)
            page.write_text("# API\n")
            git("add", ".")
            git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            page.unlink()
            git("add", "-A")
            git("commit", "-qm", "delete page")
            head = git("rev-parse", "HEAD")
            self.assertEqual(Scope.PUBLIC_DOCS, select("pull_request", base, head, root).scope)
            self.assertEqual(Reason.CHECKOUT_MISMATCH, select("pull_request", head, base, root).reason)

    def test_workflow_output_projects_full_gate_for_main_push(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            with patch.object(sys, "argv", ["validation_scope.py", "--event", "push",
                                             "--github-output", str(output)]), redirect_stdout(io.StringIO()):
                main()
            self.assertEqual("validation=product\n", output.read_text())


if __name__ == "__main__":
    unittest.main()
