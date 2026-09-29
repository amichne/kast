#!/usr/bin/env python3
"""Exact diffs select checks monotonically; uncertainty cannot omit verification."""
from contextlib import redirect_stdout
from itertools import combinations
from pathlib import Path
import io
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from validation_scope import ALL_CHECKS, Check, Reason, Selection, classify_paths, main, select

DOCS = frozenset({Check.DOCUMENTATION})
PRODUCT = frozenset({Check.PRODUCT, Check.PORTABLE})
PAGE = "docs/public/reference/api.mdx"
SOURCE = "cli/src/main/kotlin/Management.kt"


class ValidationScopeTest(unittest.TestCase):
    def test_independent_pages_and_navigation_require_only_documentation(self):
        self.assertEqual(DOCS, classify_paths((PAGE, "docs/public/concepts/architecture.mdx", "docs/public/docs.json")))

    def test_product_linked_docs_keep_product_and_portable_checks(self):
        for path in ("docs/public/start.mdx", "docs/public/reference/compatibility.mdx",
                     "docs/public/reference/callables.openapi.json"):
            with self.subTest(path=path):
                self.assertEqual(PRODUCT | DOCS, classify_paths((path,)))

    def test_product_sources_and_packaging_do_not_need_mintlify_or_graph_inspection(self):
        for path in (SOURCE, "query/protocol/src/test/kotlin/QueryTest.kt", "packaging/test-install-local.py",
                     "packaging/portable.Dockerfile", "install.sh", "README.md"):
            with self.subTest(path=path):
                self.assertEqual(PRODUCT, classify_paths((path,)))

    def test_build_wiring_and_unclassified_paths_keep_every_check(self):
        for path in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew",
                     "gradle/libs.versions.toml", "build-logic/src/main/kotlin/Convention.kt",
                     "distribution/release/run_product_gate.py", ".github/workflows/ci.yml",
                     ".github/scripts/ci/validation_scope.py", "packaging/new.gradle.kts",
                     "knowledge/modules/distribution.md", "docs/public/images/observer.png",
                     "new-config.json"):
            with self.subTest(path=path):
                self.assertEqual(ALL_CHECKS, classify_paths((path,)))

    def test_empty_and_unusual_paths_do_not_authorize_skips(self):
        for paths in ((), ("docs/public/AGENTS.md",), ("docs/public/a\nfile.mdx",),
                      ("docs/public/../page.mdx",), ("docs/public/\N{LATIN SMALL LETTER E WITH ACUTE}.mdx",)):
            with self.subTest(paths=paths):
                self.assertEqual(ALL_CHECKS, classify_paths(paths))

    def test_mixed_changes_cannot_remove_a_required_check(self):
        paths = (PAGE, SOURCE, "install.sh", "build.gradle.kts", "new-config.json")
        for left, right in combinations(paths, 2):
            with self.subTest(left=left, right=right):
                expected = classify_paths((left,)) | classify_paths((right,))
                self.assertEqual(expected, classify_paths((left, right)))
        self.assertEqual(PRODUCT | DOCS, classify_paths((SOURCE, PAGE)))

    def test_full_events_and_invalid_revisions_keep_every_check(self):
        for event, base, head, reason in (
            ("workflow_dispatch", "a" * 40, "b" * 40, Reason.FULL_EVENT),
            ("unknown", "a" * 40, "b" * 40, Reason.FULL_EVENT),
            ("push", "", "b" * 40, Reason.INVALID_REVISION),
            ("push", "0" * 40, "b" * 40, Reason.INVALID_REVISION),
            ("pull_request", "missing", "b" * 40, Reason.INVALID_REVISION),
        ):
            with self.subTest(event=event, base=base):
                self.assertEqual(Selection(ALL_CHECKS, reason), select(event, base, head))

    def test_unavailable_git_observation_keeps_every_check(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE),
                             select("push", "a" * 40, "b" * 40, Path(directory)))
        with patch("validation_scope.subprocess.run", side_effect=OSError("unavailable")):
            self.assertEqual(Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE),
                             select("pull_request", "a" * 40, "b" * 40))

    def test_workflow_outputs_are_explicit_and_preserve_the_input_revisions(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            arguments = ["validation_scope.py", "--event", "push", "--base", "a" * 40,
                         "--head", "b" * 40, "--github-output", str(output)]
            with patch.object(sys, "argv", arguments), redirect_stdout(io.StringIO()), \
                    patch("validation_scope.select", return_value=Selection(DOCS, Reason.CHANGED_INPUTS)) as choose:
                main()
            choose.assert_called_once_with("push", "a" * 40, "b" * 40)
            self.assertEqual("product=skip\nportable=skip\ndocumentation=run\ngate_graph=skip\n", output.read_text())


class ExactDiffTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git("init", "-q")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI")
        self.write(PAGE, "# API\n")
        self.base = self.commit("base")

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], cwd=self.root, check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout.strip()

    def write(self, path, text):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def test_docs_only_push_skips_product_for_the_whole_range(self):
        self.write(PAGE, "# Revised API\n")
        self.commit("first page edit")
        self.write("docs/public/reference/other.mdx", "# Other\n")
        head = self.commit("second page edit")
        self.assertEqual(DOCS, select("push", self.base, head, self.root).checks)

    def test_push_includes_product_changes_before_the_final_docs_commit(self):
        self.write(SOURCE, "class Management\n")
        self.commit("product edit")
        self.write(PAGE, "# Revised API\n")
        head = self.commit("docs edit")
        self.assertEqual(PRODUCT | DOCS, select("push", self.base, head, self.root).checks)

    def test_pr_uses_merge_base_while_push_compares_exact_endpoints(self):
        self.write("build.gradle.kts", "// unrelated base branch change\n")
        advanced_base = self.commit("base advanced")
        self.git("checkout", "--detach", self.base)
        self.write(PAGE, "# Feature docs\n")
        head = self.commit("feature")
        self.assertEqual(DOCS, select("pull_request", advanced_base, head, self.root).checks)
        self.assertEqual(ALL_CHECKS, select("push", advanced_base, head, self.root).checks)

    def test_deletions_and_both_sides_of_renames_remain_visible(self):
        (self.root / PAGE).unlink()
        head = self.commit("delete page")
        self.assertEqual(DOCS, select("pull_request", self.base, head, self.root).checks)
        self.write("install.sh", "same content\n")
        base = self.commit("installer")
        self.git("mv", "install.sh", PAGE)
        head = self.commit("move installer into docs")
        self.assertEqual(PRODUCT | DOCS, select("push", base, head, self.root).checks)

    def test_checkout_mismatch_missing_base_and_empty_diff_do_not_skip_checks(self):
        self.write(PAGE, "# Updated\n")
        head = self.commit("edit")
        for base, candidate, reason in (
            (head, self.base, Reason.CHECKOUT_MISMATCH),
            ("f" * 40, head, Reason.DIFF_UNAVAILABLE),
            (head, head, Reason.NO_CHANGES),
        ):
            with self.subTest(reason=reason):
                self.assertEqual(Selection(ALL_CHECKS, reason), select("push", base, candidate, self.root))


if __name__ == "__main__":
    unittest.main()
