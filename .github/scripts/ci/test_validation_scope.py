#!/usr/bin/env python3
"""Exact diffs select checks monotonically; uncertainty cannot omit verification."""

import io
import json
import os
import re
import subprocess
import sys
import tempfile
import tomllib
import unittest
from contextlib import redirect_stdout
from itertools import combinations
from pathlib import Path
from unittest.mock import patch
from urllib.parse import unquote

import yaml

import macos_reset_checks
import workflow_contract
import verification_plan
from validation_scope import (
    ALL_CHECKS,
    DOCUMENTATION_PATHS,
    PRODUCT_MODULES,
    Check,
    NativeCompilation,
    QUEUE_BRANCH_PREFIX,
    Reason,
    Selection,
    SCOPE_CHECKS,
    Scope,
    Stage,
    classify_diff,
    classify_paths,
    main,
    select,
)

DOCS = frozenset({Check.DOCUMENTATION})
PRODUCT = frozenset({Check.SOURCE, Check.PRODUCT, Check.PORTABLE})
SOURCE_ONLY = frozenset({Check.SOURCE, Check.PORTABLE})
PAGE = "docs/public/index.mdx"
SOURCE = "cli/src/main/kotlin/Management.kt"



def task_run(name: str) -> str:
    """The command a CI task runs; workflow steps call the task, and the task owns the command."""
    run = workflow_contract.tasks()[name]["run"]
    return run if isinstance(run, str) else " && ".join(run)


def composed_ci():
    """The composed step driver; its plans own the commands that composed tasks run."""
    import importlib.util

    if "composed_ci" not in sys.modules:
        path = Path(__file__).resolve().parents[3] / "packaging/internal/composition/composed_ci.py"
        spec = importlib.util.spec_from_file_location("composed_ci", path)
        module = importlib.util.module_from_spec(spec)
        sys.modules["composed_ci"] = module
        spec.loader.exec_module(module)
    return sys.modules["composed_ci"]


class ValidationScopeTest(unittest.TestCase):
    def test_native_toggle_preserves_all_other_obligations_for_each_scope(self):
        for scope, checks in SCOPE_CHECKS.items():
            selected = Selection(checks, Reason.CHANGED_INPUTS)
            with self.subTest(scope=scope):
                self.assertIs(selected, selected.with_native_compilation(NativeCompilation.ENABLED))
                disabled = selected.with_native_compilation(NativeCompilation.DISABLED)
                self.assertEqual(checks - {Check.PRODUCT}, disabled.checks)
                self.assertEqual(disabled.checks, SCOPE_CHECKS[disabled.scope])
                self.assertNotIn(disabled.scope, (Scope.FULL, Scope.PRODUCT))

    def test_only_a_landing_run_keeps_the_product_gate(self):
        for event, head_ref, stage in (
            ("pull_request", "ci/some-change", Stage.REVIEW),
            ("pull_request", "", Stage.REVIEW),
            ("pull_request", "x/mergify/merge-queue/1", Stage.REVIEW),
            ("pull_request", "mergify/merge-queue/3f9c1a2b7e", Stage.LANDING),
            ("push", "", Stage.LANDING),
            ("workflow_dispatch", "", Stage.LANDING),
            ("merge_group", "", Stage.LANDING),
        ):
            with self.subTest(event=event, head_ref=head_ref):
                self.assertIs(stage, Stage.of(event, head_ref))
        for scope, checks in SCOPE_CHECKS.items():
            selected = Selection(checks, Reason.CHANGED_INPUTS)
            with self.subTest(scope=scope):
                self.assertIs(selected, selected.at_stage(Stage.LANDING))
                review = selected.at_stage(Stage.REVIEW)
                self.assertEqual(checks - {Check.PRODUCT}, review.checks)
                self.assertEqual(review.checks, SCOPE_CHECKS[review.scope])

    def test_review_run_reports_source_scope_and_queue_run_reports_product_scope(self):
        for head_ref, expected in (
            ("ci/some-change", "source=run\nproduct=skip\nportable=run\ndocumentation=run\nscope=SOURCE_FULL\n"),
            ("mergify/merge-queue/1", "source=run\nproduct=run\nportable=run\ndocumentation=run\nscope=FULL\n"),
        ):
            with self.subTest(head_ref=head_ref), tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary) / "outputs"
                with (
                    patch.dict(os.environ, {"KAST_NATIVE_COMPILATION": "true", "GITHUB_HEAD_REF": head_ref}),
                    patch("validation_scope.select", return_value=Selection(ALL_CHECKS, Reason.CHANGED_INPUTS)),
                    patch.object(sys, "argv", ["validation_scope.py", "--event", "pull_request",
                                              "--github-output", str(output)]),
                    redirect_stdout(io.StringIO()),
                ):
                    main()
                self.assertEqual(expected, output.read_text())

    def test_pull_requests_never_report_the_check_that_main_requires(self):
        root = Path(__file__).resolve().parents[3]
        workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
        names = [job.get("name", key) for key, job in workflow["jobs"].items()]
        required = [name for name in names if "CI required checks" in name]
        # A skipped job counts as a success, so the name itself must differ on a pull request.
        self.assertEqual(
            [
                "${{ github.event_name == 'pull_request' && "
                f"!startsWith(github.head_ref, '{QUEUE_BRANCH_PREFIX}') && "
                "'Pull request checks' || 'CI required checks' }}"
            ],
            required,
        )
        self.assertIn("Source feedback", names)
        rules = yaml.safe_load((root / ".mergify.yml").read_text())
        (rule,) = rules["queue_rules"]
        self.assertEqual(QUEUE_BRANCH_PREFIX, rule["queue_branch_prefix"])
        self.assertIn("check-success = Source feedback", rule["queue_conditions"])
        self.assertIn("check-success = CI required checks", rule["merge_conditions"])
        # Equal conditions would let Mergify merge on the pull request's own checks.
        self.assertNotEqual(rule["queue_conditions"], rule["merge_conditions"])
        self.assertEqual("merge", rule["branch_protection_injection_mode"])

    def test_superseded_runs_release_their_mac_jobs(self):
        root = Path(__file__).resolve().parents[3]
        workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
        for job in ("kotlin", "composed-macos"):
            with self.subTest(job=job):
                condition = workflow["jobs"][job]["if"]
                self.assertIn("!cancelled()", condition)
                self.assertNotIn("always()", condition)

    def test_disabled_native_compilation_keeps_every_source_check(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "outputs"
            with (
                patch.dict(os.environ, {"KAST_NATIVE_COMPILATION": "false"}),
                patch("validation_scope.select", return_value=Selection(ALL_CHECKS, Reason.FULL_EVENT)),
                patch.object(sys, "argv", ["validation_scope.py", "--event", "workflow_dispatch",
                                          "--github-output", str(output)]),
                redirect_stdout(io.StringIO()),
            ):
                main()
            self.assertEqual(
                "source=run\nproduct=skip\nportable=run\ndocumentation=run\nscope=SOURCE_FULL\n",
                output.read_text(),
            )

    def test_unknown_native_toggle_rejects_before_selection_or_output(self):
        for value in ("", "False", "TRUE", "0", "typo"):
            with tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary) / "outputs"
                with (
                    self.subTest(value=value),
                    patch.dict(os.environ, {"KAST_NATIVE_COMPILATION": value}),
                    patch("validation_scope.select", return_value=Selection(ALL_CHECKS, Reason.FULL_EVENT)) as select_checks,
                    patch.object(sys, "argv", ["validation_scope.py", "--event", "workflow_dispatch",
                                              "--github-output", str(output)]),
                    redirect_stdout(io.StringIO()),
                    self.assertRaises(ValueError),
                ):
                    main()
                select_checks.assert_not_called()
                self.assertFalse(output.exists())

    def test_kotlin_shaped_paths_cannot_bypass_protected_or_unknown_roots(self):
        for prefix in (
            ".github/tools",
            ".github/scripts",
            ".agents/tooling",
            "build-logic",
            "packaging/helper",
            "docs/internal/tooling",
            "gradle/plugins",
            "config",
            "distribution/release",
            "experiments",
            "fixtures",
            "new-module",
            "cli/nested",
        ):
            for source_set in ("main", "test"):
                path = f"{prefix}/src/{source_set}/kotlin/Probe.kt"
                with self.subTest(path=path):
                    self.assertEqual(ALL_CHECKS, classify_paths((path,)))

    def test_raw_diff_admits_only_complete_regular_file_observations(self):
        for modes, hashes, status in (
            ("100644 100644", "a" * 40 + " " + "b" * 40, "M"),
            ("000000 100644", "0" * 40 + " " + "b" * 40, "A"),
            ("100644 000000", "a" * 40 + " " + "0" * 40, "D"),
        ):
            raw = f":{modes} {hashes} {status}\0{PAGE}\0".encode()
            with self.subTest(status=status):
                self.assertEqual(DOCS, classify_diff(raw))
                self.assertEqual(ALL_CHECKS, classify_diff(raw[:-1]))
        for raw in (
            b"",
            b"\0",
            b"unknown\0path\0",
            b"header\0path\0extra\0",
            f":000000 100644 {'a' * 40} {'b' * 40} M\0{PAGE}\0".encode(),
            f":100644 120000 {'a' * 40} {'b' * 40} T\0{PAGE}\0".encode(),
            f":100644 100644 {'a' * 40} {'b' * 40} R100\0{PAGE}\0{PAGE}\0".encode(),
        ):
            self.assertEqual(ALL_CHECKS, classify_diff(raw))

    def test_audited_independent_pages_select_only_documentation(self):
        self.assertEqual(DOCS, classify_paths((PAGE, "docs/public/explanation/query-lifecycle.mdx")))

    def test_reader_journey_inputs_keep_their_documentation_consumers_without_a_product(self):
        paths = (
            ".github/scripts/ci/README.md", "README.md", "app-server/README.md",
            "app-server/docs/public-query-contract.md", "docs/development.md",
            "docs/internal/documentation-structure.md", "docs/hosted-read-configuration.md",
            "docs/static-query-execution.md", "docs/okf-public.json",
            "docs/internal/tooling/okf_public.py", "docs/internal/tooling/test_okf_public.py",
            "docs/public/reference/AGENTS.md", "docs/public/start.mdx",
            "docs/public/reference/installation.mdx", "docs/public/reference/compatibility.mdx",
            "docs/public/explanation/value-journey-evidence.mdx",
            "config/vale/readability/audit.py", "config/vale/readability/audit.py.lock",
            "config/vale/readability/test_audit.py", "config/vale/readability/pytest.ini",
            "config/vale/readability/pyrightconfig.json", "config/vale/readability/prose.ini",
            "config/vale/readability/styles/Prose/Complexity.yml",
            "config/vale/readability/starter-compatibility/styles/config/vocabularies/Docs/accept.txt",
            "config/vale/styles/config/vocabularies/Kast/accept.txt",
            "config/vale/styles/config/vocabularies/KastNames/accept.txt",
            "docs/reviews/readability-audit/README.md",
            "docs/reviews/readability-audit/after/prose.json",
            "docs/reviews/readability-audit/after/prose.stderr.txt",
            "experiments/host-observation/README.md",
            "experiments/host-observation/EVALUATION_CHECKLIST.md",
            "experiments/representation-impact/README.md",
        )
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(DOCS, classify_paths((path,)))
        self.assertEqual(DOCS, classify_paths(paths + (PAGE,)))

    def test_documentation_neighbors_cannot_inherit_reviewed_ownership(self):
        for path in (
            ".github/scripts/ci/new.py", "docs/internal/tooling/new.py",
            "config/vale/readability/new.py", "config/vale/readability/src/main/kotlin/Probe.kt",
            "docs/reviews/readability-audit/build.gradle.kts",
            "experiments/host-observation/protocol.py", "experiments/host-observation/new.md",
            "config/vale/styles/Kast/Terminology.yml",
        ):
            with self.subTest(path=path):
                self.assertEqual(ALL_CHECKS, classify_paths((path,)))

    def test_openwiki_inputs_retain_knowledge_base_validation(self):
        for path in (
            "docs/public/reference/change.mdx",
        ):
            with self.subTest(path=path):
                self.assertEqual(ALL_CHECKS, classify_paths((path,)))

    def test_documentation_consumers_cover_navigation_and_generated_examples(self):
        for path in ("docs/public/docs.json", "docs/public/style.css", "docs/public/explanation/value-journey.mdx",
                     "docs/public/reference/responses.mdx", "docs/public/reference/symbols.mdx", "docs/public/search.mdx",
                     "docs/public/snippets/value-journey.jsx", "docs/internal/tooling/value-journey/src/trace.ts"):
            self.assertEqual(DOCS, classify_paths((path,)))

    def test_generated_callable_schema_keeps_product_and_portable_checks(self):
        for path in (
            "docs/public/reference/callables.openapi.json",
        ):
            with self.subTest(path=path):
                self.assertEqual(ALL_CHECKS, classify_paths((path,)))

    def test_product_sources_and_packaging_do_not_need_mintlify_or_graph_inspection(self):
        for path in (SOURCE, "distribution/cli/src/main/kotlin/Command.kt"):
            with self.subTest(path=path):
                self.assertEqual(PRODUCT, classify_paths((path,)))

    def test_source_checks_keep_semantic_adapters_and_contracts_without_candidate_assembly(self):
        for path in (
            "query/contract/src/main/kotlin/Query.kt",
            "query/protocol/src/test/kotlin/QueryTest.kt",
            "relation/intellij/src/main/kotlin/Adapter.kt",
            "runtime/hosted/src/main/kotlin/Hosted.kt",
            "source/service/src/main/kotlin/SourceService.kt",
        ):
            self.assertEqual(SOURCE_ONLY, classify_paths((path,)))

    def test_native_and_installation_inputs_keep_qualification(self):
        for path in (
            "kernel/src/main/kotlin/Outcome.kt",
            "evidence/sqlite/src/main/kotlin/Database.kt",
            "change/recovery/src/main/kotlin/Recovery.kt",
            "relation/contract/src/main/kotlin/Binding.kt",
            "cli/src/main/kotlin/installation/Install.kt",
        ):
            self.assertEqual(PRODUCT, classify_paths((path,)))

    def test_build_wiring_and_unclassified_paths_keep_every_check(self):
        for path in (
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradle/libs.versions.toml",
            "build-logic/src/main/kotlin/Convention.kt",
            "distribution/release/run_product_gate.py",
            ".github/workflows/ci.yml",
            ".github/scripts/ci/validation_scope.py",
            "packaging/new.gradle.kts",
            "knowledge/modules/distribution.md",
            "docs/public/images/observer.png",
            "new-config.json",
            "docs/public/reference/new-page.mdx",
            "install.sh",
            "packaging/tests/test-install-local.py",
            "packaging/portable.Dockerfile",
            "docs/internal/tooling/check_glossary.py",
            "docs/glossary.json",
        ):
            with self.subTest(path=path):
                self.assertEqual(ALL_CHECKS, classify_paths((path,)))

    def test_empty_and_unusual_paths_do_not_authorize_skips(self):
        for paths in (
            (),
            ("docs/public/AGENTS.md",),
            ("docs/public/a\nfile.mdx",),
            ("docs/public/../page.mdx",),
            ("/docs/public/reference/api.mdx",),
            ("docs//public/reference/api.mdx",),
            ("docs/public/reference/api.mdx/",),
            ("docs/public/reference\\api.mdx",),
            ("docs/public/\N{LATIN SMALL LETTER E WITH ACUTE}.mdx",),
        ):
            with self.subTest(paths=paths):
                self.assertEqual(ALL_CHECKS, classify_paths(paths))

    def test_mixed_changes_cannot_remove_a_required_check(self):
        paths = (PAGE, SOURCE, "install.sh", "build.gradle.kts", "new-config.json")
        for left, right in combinations(paths, 2):
            with self.subTest(left=left, right=right):
                expected = ALL_CHECKS
                self.assertEqual(expected, classify_paths((left, right)))
        self.assertEqual(ALL_CHECKS, classify_paths((SOURCE, PAGE)))

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
            self.assertEqual(
                Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE), select("push", "a" * 40, "b" * 40, Path(directory))
            )
        with patch("validation_scope.subprocess.run", side_effect=OSError("unavailable")):
            self.assertEqual(Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE), select("pull_request", "a" * 40, "b" * 40))

    def test_workflow_outputs_are_explicit_and_preserve_the_input_revisions(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            arguments = [
                "validation_scope.py",
                "--event",
                "push",
                "--base",
                "a" * 40,
                "--head",
                "b" * 40,
                "--github-output",
                str(output),
            ]
            with (
                patch.object(sys, "argv", arguments),
                redirect_stdout(io.StringIO()),
                patch("validation_scope.select", return_value=Selection(DOCS, Reason.CHANGED_INPUTS)) as choose,
            ):
                main()
            choose.assert_called_once_with("push", "a" * 40, "b" * 40)
            self.assertEqual(
                "source=skip\nproduct=skip\nportable=skip\ndocumentation=run\nscope=DOCUMENTATION\n",
                output.read_text(),
            )


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
        return subprocess.run(
            ["git", *arguments], cwd=self.root, check=True, capture_output=True, text=True
        ).stdout.strip()

    def write(self, path, text):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def test_collected_roots_bind_real_diff_plans_and_reject_stale_graphs(self):
        repository = Path(__file__).resolve().parents[3]
        graph = json.loads((repository / verification_plan.GRAPH).read_text())
        for path in [item["path"] for item in graph["inputs"]] + [verification_plan.GRAPH, verification_plan.MANIFEST]:
            self.write(path, (repository / path).read_text())
        fixture = "workspace/contract/src/testFixtures/kotlin/WorkspaceFixture.kt"
        adapter = "relation/intellij/src/nativeTest/kotlin/ReceiverTest.kt"
        self.write(fixture, "class WorkspaceFixture\n")
        self.write(adapter, "class ReceiverTest\n")
        base = self.commit("collected roots")
        self.git("rm", fixture)
        self.write(adapter, "class RevisedReceiverTest\n")
        head = self.commit("fixture deletion and adapter case")
        result = select("pull_request", base, head, self.root, affected=True)
        self.assertEqual(PRODUCT, result.checks)
        self.assertIsInstance(result.plan, verification_plan.Plan)
        self.assertEqual(tuple(sorted((fixture, adapter))), result.plan.changedPaths)
        self.assertIn(":app-server", result.plan.consumers)
        self.assertIn(":relation:intellij", result.plan.owners)
        self.assertEqual(self.git("rev-parse", "HEAD^{tree}"), result.plan.sourceTree)
        self.assertEqual(base, result.plan.comparisonBasis)
        self.write("build.gradle.kts", "// changed graph declaration\n")
        stale = self.commit("stale graph")
        result = select("pull_request", head, stale, self.root, affected=True)
        self.assertEqual(ALL_CHECKS, result.checks)
        self.assertEqual(verification_plan.Failure.STALE_GRAPH, result.reason)
        self.assertIsNone(result.plan)

    def test_plan_rejection_preserves_the_finite_failure_in_scope_diagnostics(self):
        self.write(SOURCE, "class Management\n")
        head = self.commit("product source")
        for failure in verification_plan.Failure:
            with self.subTest(failure=failure), patch("verification_plan.plan", return_value=failure):
                self.assertEqual(
                    Selection(ALL_CHECKS, failure),
                    select("pull_request", self.base, head, self.root, affected=True),
                )

    def test_readme_and_documentation_tooling_bypass_module_planning_for_prs_and_pushes(self):
        self.write("README.md", "# Reader journey\n")
        self.write("docs/internal/tooling/okf_public.py", "# documentation projector\n")
        self.write("config/vale/readability/test_audit.py", "# audit contract\n")
        head = self.commit("documentation consumers")
        for event in ("pull_request", "push"):
            with self.subTest(event=event):
                result = select(event, self.base, head, self.root, affected=True)
                self.assertEqual(Selection(DOCS, Reason.CHANGED_INPUTS), result)
        self.write("install.sh", "# installation effect\n")
        mixed = self.commit("installer change")
        self.assertEqual(ALL_CHECKS, select("pull_request", self.base, mixed, self.root, affected=True).checks)

    def test_docs_only_push_uses_the_whole_range(self):
        self.write(PAGE, "# Revised API\n")
        self.commit("first page edit")
        self.write("docs/public/reference/search.mdx", "# Other\n")
        head = self.commit("second page edit")
        self.assertEqual(DOCS, select("push", self.base, head, self.root).checks)

    def test_source_pr_and_main_both_need_product_qualification(self):
        self.write("query/service/src/main/kotlin/QueryService.kt", "class QueryService\n")
        head = self.commit("source change")
        self.assertEqual(PRODUCT, select("pull_request", self.base, head, self.root).checks)
        self.assertEqual(PRODUCT, select("push", self.base, head, self.root).checks)

    def test_push_includes_product_changes_before_the_final_docs_commit(self):
        self.write(SOURCE, "class Management\n")
        self.commit("product edit")
        self.write(PAGE, "# Revised API\n")
        head = self.commit("docs edit")
        self.assertEqual(ALL_CHECKS, select("push", self.base, head, self.root).checks)

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
        self.assertEqual(ALL_CHECKS, select("push", base, head, self.root).checks)

    def test_type_changes_and_symlink_page_additions_keep_every_check(self):
        (self.root / PAGE).unlink()
        (self.root / PAGE).symlink_to("search.mdx")
        self.write("docs/public/reference/search.mdx", "# Search\n")
        head = self.commit("change page to symlink")
        self.assertEqual(ALL_CHECKS, select("push", self.base, head, self.root).checks)

    def test_deleting_an_openwiki_target_and_its_navigation_keeps_every_check(self):
        page = "docs/public/reference/change.mdx"
        self.write(page, "# Declaration change\n")
        self.write("docs/public/docs.json", '{"pages": ["reference/change"]}\n')
        base = self.commit("linked page and navigation")
        (self.root / page).unlink()
        self.write("docs/public/docs.json", '{"pages": []}\n')
        head = self.commit("delete linked page and its navigation")
        self.assertEqual(ALL_CHECKS, select("pull_request", base, head, self.root).checks)

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


class SourceInventoryTest(unittest.TestCase):
    def test_one_jdk_pin_serves_mise_cloud_and_every_workflow(self):
        root = Path(__file__).resolve().parents[3]
        pins = json.loads((root / "packaging/cloud/pins.json").read_text())
        self.assertNotIn("graalvm", pins["artifacts"])
        url = unquote(pins["artifacts"]["jdk"]["url"])
        self.assertTrue(url.startswith("https://github.com/adoptium/temurin25-binaries/releases/"))
        release = re.search(r"/jdk-(\d+\.\d+\.\d+)\+(\d+)/", url)
        self.assertIsNotNone(release)
        version, build = release.groups()
        mise = tomllib.loads((root / "mise.toml").read_text())
        self.assertEqual(f"temurin-{version}+{build}.0.LTS", mise["tools"]["java"])
        self.assertNotIn("GRAALVM_HOME", mise.get("env", {}))
        producers = []
        for path in (*(root / ".github/workflows").glob("*.yml"), *(root / ".github/actions").glob("*/action.yml")):
            for distribution, java in re.findall(
                r"(?m)^\s+distribution: (\S+)\n\s+java-version: ([^\n]+)", path.read_text()
            ):
                with self.subTest(workflow=path.name, distribution=distribution):
                    self.assertEqual("temurin", distribution)
                    self.assertEqual(f"{version}+{build}", java.strip("'\""))
                producers.append(path.name)
        self.assertTrue(producers)

    def test_mac_product_job_requires_fresh_assembly_and_retains_no_component_candidate(self):
        workflow = (Path(__file__).resolve().parents[2] / "workflows/ci.yml").read_text()
        product = workflow.split("\n  kotlin:\n", 1)[1]
        product = re.split(r"\n  (?=\S)", product, maxsplit=1)[0]
        self.assertIn("    runs-on: ${{ fromJSON(needs.kotlin-runner.outputs.labels) }}\n", product)
        self.assertIn("    needs: [scope, assembly, kotlin-runner]\n", product)
        self.assertNotIn("needs.source-work", product)
        self.assertIn("needs.assembly.result == 'success' && needs.kotlin-runner.result == 'success'", product)
        self.assertIn("- name: Exercise the exact assembled Mac Control product\n", product)
        self.assertNotIn("REUSED", workflow)
        self.assertNotIn("ci-build-candidate-", workflow)
        self.assertNotIn("build_candidate.py seal", workflow)
        self.assertNotIn("build_candidate.py reuse", workflow)

    def test_only_guarded_main_jobs_may_prefer_the_local_mac(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        workflow = (workflows / "ci.yml").read_text()
        # The composed target matrix has its own explicit hosted runner contract below.
        # Stable promotion consumes retained bytes and is no longer a native producer.
        body = (workflows / "release-component.yml").read_text().split("\n  release:\n", 1)[1]
        body = re.split(r"\n  (?=\S)", body, maxsplit=1)[0]
        self.assertIn("    runs-on: macos-15\n", body)
        # self_hosted_guard.py admits exactly these ci.yml jobs on the local runner.
        for job, selector in (("kotlin", "kotlin-runner"),):
            with self.subTest(job=job):
                body = workflow.split(f"\n  {job}:\n", 1)[1]
                body = re.split(r"\n  (?=\S)", body, maxsplit=1)[0]
                self.assertIn(f"    runs-on: ${{{{ fromJSON(needs.{selector}.outputs.labels) }}}}\n", body)
                self.assertIn("uses: ./.github/actions/local-python", body)
                self.assertIn("cache-disabled: ${{ runner.environment == 'self-hosted' }}", body)
                selection = workflow.split(f"\n  {selector}:\n", 1)[1]
                selection = re.split(r"\n  (?=\S)", selection, maxsplit=1)[0]
                self.assertIn("if: github.ref == 'refs/heads/main'", selection)
                self.assertIn('steps.select.outputs.labels || \'"macos-15"\'', selection)
                self.assertIn("run: mise run ci:mac-runner", selection)
                self.assertEqual("python3 .github/scripts/ci/mac_runner.py", task_run("ci:mac-runner"))
        self.assertEqual(1, workflow.count("fromJSON(needs."))
        for path in workflows.glob("*.yml"):
            with self.subTest(workflow=path.name):
                self.assertNotIn("[self-hosted", path.read_text())

    def test_assembly_needs_no_native_input_and_sealing_keeps_source_proof(self):
        workflow = (Path(__file__).resolve().parents[2] / "workflows/ci.yml").read_text()
        for retired in ("native-inputs", "native-runner", "native"):
            self.assertNotIn(f"\n  {retired}:\n", workflow)
        assembly = re.split(r"\n  (?=\S)", workflow.split("\n  assembly:\n", 1)[1], maxsplit=1)[0]
        self.assertIn("    needs: scope\n", assembly)
        self.assertIn("run: mise run ci:assembly", assembly)
        self.assertIn("release_handoff.py assemble --version", task_run("ci:assembly"))
        # Mac qualification, sealing, and installed acceptance run beside source validation.
        # The joined candidate and the required aggregate keep the complete source proof.
        for job in ("kotlin", "composed-macos", "composed-linux", "sealed-product"):
            body = re.split(r"\n  (?=\S)", workflow.split(f"\n  {job}:\n", 1)[1], maxsplit=1)[0]
            self.assertNotIn("needs.source-work", body)
            self.assertNotRegex(body, r"(?m)^    needs: \[[^\]]*source-work")
        required = re.split(r"\n  (?=\S)", workflow.split("\n  required:\n", 1)[1], maxsplit=1)[0]
        self.assertIn(
            "    needs: [scope, source, documentation, portable, kotlin, complete-product]\n",
            required,
        )
        sealed = re.split(r"\n  (?=\S)", workflow.split("\n  sealed-product:\n", 1)[1], maxsplit=1)[0]
        self.assertIn("    needs: [scope, composed-macos, composed-linux]\n", sealed)
        joined = re.split(r"\n  (?=\S)", workflow.split("\n  complete-product:\n", 1)[1], maxsplit=1)[0]
        self.assertIn(
            "    needs: [source-work, sealed-product, installed-macos-upgrade, installed-macos-fresh, "
            "installed-linux-upgrade, installed-linux-fresh]\n",
            joined,
        )

    def test_queue_and_main_use_the_same_mac_gate(self):
        workflow = (Path(__file__).resolve().parents[2] / "workflows/ci.yml").read_text()
        native = next(line for line in workflow.splitlines() if line.startswith("  KAST_NATIVE_COMPILATION:"))
        self.assertNotIn("github.event_name", native)
        self.assertNotIn("github.ref", native)
        for job in ("assembly", "kotlin", "composed-macos", "composed-linux"):
            body = workflow.split(f"\n  {job}:\n", 1)[1]
            body = re.split(r"\n  (?=\S)", body, maxsplit=1)[0]
            self.assertIn("needs.scope.outputs.product == 'run'", body)
            self.assertNotIn("github.event_name != 'pull_request'", body)
            self.assertNotIn("github.event_name == 'push' || github.event_name == 'workflow_dispatch') &&\n", body)

    def test_ci_preserves_running_main_runs_and_cancels_superseded_pr_runs(self):
        workflow = (Path(__file__).resolve().parents[2] / "workflows/ci.yml").read_text()
        concurrency = workflow.split("\nconcurrency:\n", 1)[1].split("\njobs:\n", 1)[0]
        self.assertIn("cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}", concurrency)
        self.assertIn("github.event.pull_request.number || github.ref", concurrency)

    def test_every_mac_runner_requires_explicit_platform_opt_in(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        guarded = set()
        for path in workflows.glob("*.yml"):
            for job, body in re.findall(
                r"(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)", path.read_text()
            ):
                runner = re.search(r"^    runs-on: (.+)$", body, re.M)
                selected = runner is not None and "fromJSON(needs." in runner[1]
                if runner is None or ("macos" not in runner[1].lower() and not selected):
                    continue
                with self.subTest(workflow=path.name, job=job):
                    condition = re.search(r"^    if: (.+)$", body, re.M)
                    self.assertIsNotNone(condition)
                    self.assertIn("vars.KAST_MACOS_CHECKS == 'true'", condition[1])
                guarded.add((path.name, job))
        self.assertEqual(
            {
                ("ci.yml", "kotlin"),
                ("installed-acceptance.yml", "acceptance"),
                ("release-component.yml", "release"),
            },
            guarded,
        )
        ci = (workflows / "ci.yml").read_text()
        complete = ci.split("\n  composed-macos:\n", 1)[1]
        complete = re.split(r"\n  (?=\S)", complete, maxsplit=1)[0]
        self.assertIn("vars.KAST_MACOS_CHECKS == 'true'", complete)
        self.assertIn("uses: ./.github/workflows/composed-linux.yml", complete)
        self.assertIn("complete_product: true", complete)

    def test_isolated_candidate_matrix_gates_only_the_mac_runner(self):
        path = Path(__file__).resolve().parents[2] / "workflows/composed-linux.yml"
        body = path.read_text().split("\n  candidate:\n", 1)[1]
        self.assertIn("runs-on: ${{ matrix.runner }}", body)
        self.assertIn('{"target":"linux-x86_64","runner":"ubuntu-24.04"', body)
        self.assertIn('{"target":"macos-aarch64","runner":"macos-15"', body)
        self.assertIn("vars.KAST_MACOS_CHECKS == 'true'", body)
        self.assertNotIn("linux-aarch64", body)
        self.assertNotIn("macos-x86_64", body)

    def test_isolated_candidates_keep_one_job_per_target(self):
        workflow = (
            Path(__file__).resolve().parents[2] / "workflows/composed-linux.yml"
        ).read_text()
        jobs = workflow.split("\njobs:\n", 1)[1]
        self.assertEqual(["candidate"], re.findall(r"^  ([\w-]+):$", jobs, re.M))
        self.assertEqual(1, jobs.count("matrix:"))
        self.assertEqual(1, jobs.count("ref: ${{ github.sha }}"))
        reuse = jobs.split("- name: Download exact macOS release candidate\n", 1)[1]
        reuse = reuse.split("\n      - name:", 1)[0]
        self.assertIn("if: inputs.release_target == 'macos-aarch64'", reuse)
        self.assertIn("name: release-candidate-v", reuse)
        self.assertNotIn("run-id:", reuse)
        self.assertLess(
            jobs.index("Retain the assembled candidate"),
            jobs.index("Verify the exact component assembly"),
        )

    def test_isolated_candidate_source_guards_share_the_assembly_invocation(self):
        workflow = (
            Path(__file__).resolve().parents[2] / "workflows/composed-linux.yml"
        ).read_text()
        assembly = workflow.split("- name: Assemble matching Control, Host, and bootstrap\n", 1)[1]
        assembly = assembly.split("\n      - name:", 1)[0]
        self.assertIn(
            "if: inputs.release_target != 'macos-aarch64'\n",
            assembly,
        )
        self.assertIn("TARGET: ${{ matrix.target }}", assembly)
        self.assertIn('run: mise run composed:release --target "$TARGET"', assembly)
        self.assertIn("composed_ci.py release ", task_run("composed:release"))
        # The driver adds the source guards for linux-x86_64 only.
        ci = composed_ci()
        release = ci.Release("1.2.3", "a" * 40)
        tasks = ("assembleRelease", ":runtime:hosted:headlessBootstrapPlugin")
        guards = ("verifyJsonContracts", "verifyKastArchitecture")
        for target, expected in (
            (ci.Target.LINUX_X86_64, (*tasks, *guards)),
            (ci.Target.MACOS_AARCH64, tasks),
        ):
            with self.subTest(target=target):
                (command,) = ci.release_plan(target, release).commands
                self.assertEqual("./gradlew", command.argv[0])
                self.assertEqual(expected, command.argv[-len(expected):])
                self.assertEqual(len(expected), len(set(command.argv) & {*tasks, *guards}))
        bootstrap = workflow.split("- name: Assemble bootstrap for the retained macOS release\n", 1)[1]
        bootstrap = bootstrap.split("\n      - name:", 1)[0]
        self.assertIn("if: inputs.release_target == 'macos-aarch64' && !inputs.complete_product\n", bootstrap)
        self.assertIn("run: mise run composed:bootstrap\n", bootstrap)
        self.assertIn("composed_ci.py bootstrap ", task_run("composed:bootstrap"))
        (command,) = ci.bootstrap_plan(release).commands
        self.assertEqual(":runtime:hosted:headlessBootstrapPlugin", command.argv[-1])
        self.assertNotIn("assembleRelease", command.argv)

    def test_complete_mac_qualification_precedes_preview_publication(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        ci = (workflows / "ci.yml").read_text()
        jobs = dict(
            re.findall(r"(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)", ci.split("\njobs:\n", 1)[1])
        )
        linux, both = jobs["composed-linux"], jobs["sealed-product"]
        self.assertIn("needs: scope\n", linux)
        self.assertIn("uses: ./.github/workflows/composed-linux.yml", linux)
        self.assertIn("complete_product: true", linux)
        self.assertIn("release_target: linux-x86_64", linux)
        self.assertNotIn("assembled_artifact_id", linux)
        # The sealed record joins the exact producers of both targets.
        self.assertIn("name: Seal both product targets without rebuilding\n", both)
        self.assertIn("needs: [scope, composed-macos, composed-linux]", both)
        self.assertNotIn("always()", both)
        for target, producer in (("macos-aarch64", "composed-macos"), ("linux-x86_64", "composed-linux")):
            self.assertIn(
                f"name: kast-qualified-product-{target}-"
                "${{ github.sha }}-${{ github.run_id }}-${{ needs." + producer + ".outputs.run_attempt }}",
                both,
            )
            self.assertIn(f"path: build/product-inputs/{target}\n", both)
        acceptance = (workflows / "installed-acceptance.yml").read_text()
        joined, required = jobs["sealed-product"], jobs["required"]
        mac = jobs["composed-macos"]
        self.assertIn("needs: [scope, assembly]", mac)
        self.assertIn("!cancelled()", mac)
        self.assertNotIn("needs.source-work", mac)
        self.assertIn("needs.assembly.result == 'success' &&", mac)
        self.assertNotIn("reuse_ci_release", mac)
        self.assertIn("uses: ./.github/workflows/composed-linux.yml", mac)
        self.assertIn("complete_product: true", mac)
        self.assertIn("release_target: macos-aarch64", mac)
        self.assertNotIn("run-id:", joined)
        # A rerun of failed jobs copies successful producers with their outputs, so every
        # product handoff names its producer's attempt, never the consumer's.
        for producer in (jobs["sealed-product"], acceptance):
            self.assertIn("outputs:\n      run_attempt: ${{ github.run_attempt }}", producer)
        self.assertIn("value: ${{ jobs.acceptance.outputs.run_attempt }}", acceptance)
        self.assertIn("name: ci-product-sealed-${{ github.sha }}-${{ github.run_attempt }}", joined)
        self.assertIn("run: mise run ci:seal\n", joined)
        self.assertIn("product_producer.py seal", task_run("ci:seal"))
        self.assertNotIn("name: ci-product-candidate-", joined)
        accepted = jobs["complete-product"]
        self.assertNotIn("always()", accepted)
        self.assertIn("run: mise run ci:join", accepted)
        self.assertIn("product_producer.py accept", task_run("ci:join"))
        self.assertIn(
            "name: ci-product-sealed-${{ github.sha }}-${{ needs.sealed-product.outputs.run_attempt }}",
            accepted,
        )
        # Each part runs once per target, and the join names each one's own producing attempt.
        for target in ("macos-aarch64", "linux-x86_64"):
            for part in ("upgrade", "fresh"):
                job = f"installed-{target.split('-')[0]}-{part}"
                caller = jobs[job]
                self.assertIn("needs: sealed-product\n", caller)
                self.assertIn("uses: ./.github/workflows/installed-acceptance.yml\n", caller)
                self.assertIn(f"      target: {target}\n      part: {part}\n", caller)
                self.assertIn(
                    "sealed_run_attempt: ${{ needs.sealed-product.outputs.run_attempt }}\n", caller
                )
                self.assertIn(
                    f"name: kast-installed-{part}-{target}-"
                    "${{ github.sha }}-${{ github.run_id }}-"
                    "${{ needs." + job + ".outputs.run_attempt }}",
                    accepted,
                )
        self.assertEqual(4, ci.count("uses: ./.github/workflows/installed-acceptance.yml"))
        self.assertIn(
            "name: kast-installed-${{ inputs.part }}-${{ inputs.target }}-"
            "${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}",
            acceptance,
        )
        self.assertIn(
            "path: build/acceptance-${{ inputs.target }}-${{ inputs.part }}.part.json", acceptance
        )
        installed = acceptance
        self.assertIn(
            "name: ci-product-sealed-${{ github.sha }}-${{ inputs.sealed_run_attempt }}", installed
        )
        self.assertIn(
            "runs-on: ${{ inputs.target == 'macos-aarch64' && 'macos-15' || 'ubuntu-24.04' }}", installed
        )
        self.assertIn("run: mise run ci:acceptance ", installed)
        self.assertIn('--part "$PART" ', installed)
        self.assertIn("product_lifecycle_probe.py", task_run("ci:acceptance"))
        # The baseline is a checked-in pin, read into step outputs before any download.
        self.assertIn("run: mise run ci:acceptance-baseline", installed)
        self.assertNotIn("KAST_ACCEPTANCE_BASELINE", installed)
        # One explicit accepted artifact: no fallback may choose another one.
        baseline = installed.index(
            "name: ci-product-candidate-${{ steps.baseline.outputs.revision }}"
        )
        self.assertEqual(1, installed.count("run-id: ${{ steps.baseline.outputs.run_id }}"))
        self.assertNotIn("continue-on-error", installed)
        self.assertLess(installed.index("run: mise run ci:acceptance-baseline"), baseline)
        self.assertLess(baseline, installed.index("run: mise run ci:acceptance-inputs"))
        self.assertIn("acceptance_inputs.py admit", task_run("ci:acceptance-inputs"))
        # The candidate carries its sealed record's attempt, so admission derives its name.
        self.assertIn(
            "name: ci-product-candidate-${{ github.sha }}-${{ needs.sealed-product.outputs.run_attempt }}",
            accepted,
        )
        reusable = (workflows / "composed-linux.yml").read_text()
        self.assertIn("value: ${{ jobs.candidate.outputs.run_attempt }}", reusable)
        self.assertIn("outputs:\n      run_attempt: ${{ github.run_attempt }}", reusable)
        self.assertIn(
            "needs: [scope, source, documentation, portable, kotlin, complete-product]",
            required,
        )
        self.assertEqual(1, required.count("if: needs.scope.outputs.product == 'run'"))
        self.assertIn("COMPLETE_PRODUCT_RESULT: ${{ needs.complete-product.result }}", required)
        gate = 'mise run ci:require-result "$COMPLETE_PRODUCT_RESULT" success'
        self.assertLess(required.index(gate), required.index("run: mise run ci:outcome"))
        self.assertIn("validation_outcome.py", task_run("ci:outcome"))
        candidate = (workflows / "composed-linux.yml").read_text()
        reused = candidate.split("- name: Download the exact CI release\n", 1)[1]
        reused = reused.split("- name: Assemble bootstrap", 1)[0]
        self.assertIn("inputs.complete_product && inputs.release_target == 'macos-aarch64'", reused)
        self.assertIn("artifact-ids: ${{ inputs.assembled_artifact_id || '0' }}", reused)
        self.assertIn("assembled_artifact_id: ${{ needs.assembly.outputs.artifact_id }}", mac)
        for producer, consumer in (("assembly", "kotlin"),):
            self.assertIn("artifact_id: ${{ steps.handoff.outputs.artifact-id }}", jobs[producer])
            self.assertIn(f"artifact-ids: ${{{{ needs.{producer}.outputs.artifact_id || '0' }}}}", jobs[consumer])
        self.assertNotIn("run-id:", reused)
        self.assertIn("run: mise run composed:restore\n", reused)
        self.assertEqual(
            'uv run --locked --script .github/scripts/ci/release_handoff.py restore'
            ' --version "${BUNDLE_VERSION-0.0.0}"',
            task_run("composed:restore"),
        )
        self.assertIn("if: inputs.release_target != '' && !inputs.complete_product", candidate)
        assembled = candidate.split("- name: Retain the assembled candidate\n", 1)[1]
        assembled = assembled.split("\n      - name:", 1)[0]
        self.assertIn("if: inputs.release_target == '' || inputs.complete_product", assembled)
        self.assertIn("if-no-files-found: error", assembled)
        artifact = candidate.split("- name: Retain target-local qualified product evidence\n", 1)[1]
        artifact = artifact.split("\n      - name:", 1)[0]
        self.assertNotIn("always()", artifact)
        self.assertIn("if-no-files-found: error", artifact)
        self.assertIn(
            "name: kast-qualified-product-${{ matrix.target }}-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}",
            artifact,
        )
        for item in (
            "kast-composed-setup-v${{ inputs.version }}-${{ matrix.target }}.tar.gz",
            "kast-runtime-v${{ inputs.version }}-${{ matrix.target }}.zip",
            "qualification-${{ matrix.target }}.json",
            "target-record-${{ matrix.target }}.json",
        ):
            self.assertIn(f"build/composed-release/{item}", artifact)
        # Workflow-supplied identity reaches every step that builds or qualifies the product.
        identity = '--version "${BUNDLE_VERSION-0.0.0}" --source-revision "${GITHUB_SHA:-$(git rev-parse HEAD)}"'
        for task in (
            "composed:bootstrap",
            "composed:release",
            "composed:assemble",
            "composed:qualify",
            "composed:coordinator",
        ):
            with self.subTest(task=task):
                self.assertIn(identity, task_run(task))
        self.assertIn(
            "--receipt build/composed-evidence/qualification.json", task_run("composed:seal-target")
        )
        self.assertIn("product_producer.py target ", task_run("composed:seal-target"))
        self.assertIn("composed_ci.py qualify ", task_run("composed:qualify"))
        ci = composed_ci()
        qualify = ci.qualification_plan(
            ci.Target.MACOS_AARCH64,
            "/opt/python",
            ci.Release("1.2.3", "a" * 40),
            ("b" * 64, "c" * 64),
            Path("/work"),
            Path("/scratch"),
        ).commands[-1]
        self.assertEqual(
            ("packaging/internal/composition/composed_release.py", "qualify"), qualify.argv[4:6]
        )
        self.assertLess(
            candidate.index('run: mise run composed:qualify --target "$TARGET"'),
            candidate.index('run: mise run composed:seal-target --target "$TARGET"'),
        )
        self.assertLess(
            candidate.index('run: mise run composed:seal-target --target "$TARGET"'),
            candidate.index("Retain target-local qualified product evidence"),
        )
        preview = (workflows / "developer-release.yml").read_text()
        self.assertIn("workflows: [CI]", preview)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", preview)
        self.assertIn("ref: ${{ github.sha }}", preview)
        self.assertIn(
            "SOURCE_SHA: ${{ github.event.workflow_run.head_sha || inputs.source_revision }}",
            preview,
        )
        self.assertIn("MAIN_RUN_ID: ${{ github.event.workflow_run.id || inputs.run_id }}", preview)
        self.assertIn(
            "MAIN_RUN_ATTEMPT: ${{ github.event.workflow_run.run_attempt || inputs.run_attempt }}",
            preview,
        )
        candidate_command = task_run("release:candidate")
        self.assertIn("build_candidate.py promote-product", candidate_command)
        self.assertIn('--revision "$SOURCE_SHA"', candidate_command)
        self.assertIn('--run-id "$MAIN_RUN_ID" --run-attempt "$MAIN_RUN_ATTEMPT"', candidate_command)
        self.assertIn("if: steps.candidate.outputs.publication == 'PUBLISH'", preview)
        self.assertLess(
            preview.index("mise run release:candidate"),
            preview.index("mise run release:preview"),
        )
        for producer in (mac, joined, candidate, required, preview):
            self.assertNotIn("continue-on-error:", producer)
        for consumer in (joined, preview, candidate_command, task_run("release:preview")):
            for build_command in ("./gradlew", "assembleRelease", "composed_release.py assemble"):
                self.assertNotIn(build_command, consumer)

    def test_stable_promotion_only_maps_exact_retained_preview_bytes(self):
        release = (Path(__file__).resolve().parents[2] / "workflows/release.yml").read_text()
        jobs = release.split("\njobs:\n", 1)[1]
        self.assertEqual(["publish", "marketplace"], re.findall(r"^  ([\w-]+):$", jobs, re.M))
        # The marketplace projects the promoted release afterwards; it never builds or repackages.
        marketplace = yaml.safe_load(release)["jobs"]["marketplace"]
        self.assertEqual("publish", marketplace["needs"])
        self.assertEqual("./.github/workflows/publish-marketplace.yml", marketplace["uses"])
        self.assertEqual(
            {"public_version": "${{ inputs.public_version }}"}, marketplace["with"]
        )
        self.assertIn(
            "publish_marketplace.py --public-version", task_run("release:marketplace")
        )
        for build_command in ("./gradlew", "assembleRelease", "composed_release.py"):
            self.assertNotIn(build_command, task_run("release:marketplace"))
        self.assertIn("if: github.ref == 'refs/heads/main'", jobs)
        self.assertIn("runs-on: ubuntu-24.04", jobs)
        self.assertIn("ref: ${{ github.sha }}", jobs)
        self.assertIn("persist-credentials: false", jobs)
        for name in ("source_tag", "record_sha256", "public_version"):
            declaration = release.split(f"      {name}:\n", 1)[1].split("\n\n", 1)[0]
            declaration = re.split(r"\n      (?=\S)", declaration, maxsplit=1)[0]
            self.assertIn("required: true", declaration)
        for binding in (
            "SOURCE_TAG: ${{ inputs.source_tag }}",
            "RECORD_SHA256: ${{ inputs.record_sha256 }}",
            "PUBLIC_VERSION: ${{ inputs.public_version }}",
        ):
            self.assertIn(binding, jobs)
        # An exact step/command allowlist excludes new compilation, repackaging,
        # reusable producers and dependency/tool resolution, not just known strings.
        steps = yaml.safe_load(release)["jobs"]["publish"]["steps"]
        self.assertEqual(3, len(steps))
        self.assertTrue(steps[0]["uses"].startswith("actions/checkout@"))
        self.assertTrue(steps[1]["uses"].startswith("jdx/mise-action@"))
        self.assertEqual({"install": False}, steps[1]["with"])
        self.assertEqual("mise run release:stable", steps[2]["run"])
        self.assertEqual(
            "python3 .github/scripts/release/publish_developer.py stable "
            '--source-tag "$SOURCE_TAG" --record-sha256 "$RECORD_SHA256" '
            '--public-version "$PUBLIC_VERSION"',
            task_run("release:stable"),
        )

    def test_only_main_writes_caches_and_a_dispatched_seed_shares_the_source_key(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        # Pull request entries are unreadable by other pull requests and evict main's shared entries.
        trusted = "cache-read-only: ${{ github.ref != 'refs/heads/main' }}"
        self.assertNotIn("head.repo.full_name != github.repository }}", (workflows / "ci.yml").read_text())
        self.assertNotIn("head.repo.full_name != github.repository }}", (workflows / "docs.yml").read_text())
        key = (
            "GRADLE_BUILD_ACTION_CACHE_KEY_JOB: source-work\n"
            "          GRADLE_BUILD_ACTION_CACHE_KEY_JOB_INSTANCE: linux-source\n"
        )
        seed = (workflows / "gradle-cache.yml").read_text()
        self.assertIn("  workflow_dispatch:\n", seed)
        self.assertNotIn("pull_request", seed)
        self.assertIn(key, seed)
        self.assertIn("cache-read-only: false", seed)
        self.assertIn("run: mise run ci:source\n", seed)
        self.assertEqual("python3 .github/scripts/ci/source_validation.py", task_run("ci:source"))
        docs = (workflows / "docs.yml").read_text()
        self.assertIn("uses: gradle/actions/setup-gradle@", docs)
        self.assertIn(trusted, docs)
        workflow = (workflows / "ci.yml").read_text()
        self.assertNotIn("refs/heads/main' && (github.event_name", workflow)
        for job in ("source-work", "assembly", "kotlin"):
            body = workflow.split(f"\n  {job}:\n", 1)[1]
            body = re.split(r"\n  (?=\S)", body, maxsplit=1)[0]
            self.assertIn("uses: gradle/actions/setup-gradle@", body)
            self.assertIn(trusted, body)
            if job == "source-work":
                self.assertIn(key, body)
        self.assertIn("run: mise run ci:mac-reset", body)
        self.assertEqual("uv run --locked --script .github/scripts/ci/macos_reset_checks.py", task_run("ci:mac-reset"))
        command = macos_reset_checks.COMMAND
        self.assertIn("--max-workers=1", command)
        self.assertIn(":distribution:cli:test", command)
        self.assertIn("--info", command)
        self.assertIn("--no-daemon", command)
        self.assertEqual(
            ("*NativeResetListenerTest", "*NativeResetPeerAcceptanceTest", "*ResetLaunchObservationTest"),
            tuple(value for option, value in zip(command, command[1:]) if option == "--tests"),
        )

    def test_every_ci_gradle_command_with_two_workers_configures_sequentially(self):
        # Gradle's parallel project configuration can stall. One worker or the isolated-projects
        # diagnostics select sequential configuration; `--no-parallel` does not.
        sequential = "-Dorg.gradle.isolated-projects.diagnostics=true"
        commands = {
            name: " ".join(task["run"] if isinstance(task["run"], list) else [task["run"]])
            for name, task in workflow_contract.tasks().items()
            if "--max-workers" in str(task.get("run", ""))
        }
        self.assertEqual({"release:build-component"}, set(commands))
        for name, command in commands.items():
            with self.subTest(task=name):
                self.assertIn(sequential, command)
        self.assertIn("--max-workers=1", composed_ci().GRADLE)

    def test_mac_disable_preserves_linux_checks_and_blocks_new_preview_products(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        workflow = (workflows / "ci.yml").read_text()
        self.assertIn("KAST_MACOS_CHECKS: ${{ vars.KAST_MACOS_CHECKS || 'false' }}", workflow)
        for job in ("scope", "source-work", "source", "portable", "source-feedback"):
            body = workflow.split(f"\n  {job}:\n", 1)[1]
            body = re.split(r"\n  (?=\S)", body, maxsplit=1)[0]
            self.assertNotIn("vars.KAST_MACOS_CHECKS", body)
        promotion = (workflows / "developer-release.yml").read_text()
        self.assertIn("vars.KAST_MACOS_CHECKS == 'true' &&", promotion)
        for filename in ("ci.yml", "release-component.yml", "developer-release.yml"):
            self.assertIn(
                "vars.KAST_NATIVE_COMPILATION != 'false'", (workflows / filename).read_text()
            )
        native = workflow.split("\n  composed-macos:\n", 1)[1]
        native = re.split(r"\n  (?=\S)", native, maxsplit=1)[0]
        for prerequisite in (
            "needs.scope.outputs.product == 'run'",
            "vars.KAST_MACOS_CHECKS == 'true'",
            "vars.KAST_NATIVE_COMPILATION != 'false'",
        ):
            self.assertIn(prerequisite, native)
        # Already qualified immutable bytes remain promotable without enabling new builds.
        stable = (workflows / "release.yml").read_text()
        self.assertNotIn("vars.KAST_MACOS_CHECKS", stable)
        self.assertNotIn("vars.KAST_NATIVE_COMPILATION", stable)
        self.assertIn("mise run release:stable", stable)
        self.assertIn('--record-sha256 "$RECORD_SHA256"', task_run("release:stable"))

    def test_frozen_pr1130_diff_keeps_full_checks_after_canonical_dependencies_expand(self):
        # Exact changed-path inventory for a96e7a07...d02373e8 (PR #1130).
        inventory = Path(__file__).parent / "fixtures/pr1130-paths.txt"
        paths = tuple(inventory.read_text().splitlines())
        self.assertEqual(113, len(paths))
        self.assertEqual(113, len(set(paths)))
        self.assertFalse(any(path.endswith((".kt", ".kts")) for path in paths))
        self.assertEqual(ALL_CHECKS, classify_paths(paths))
        raw = b"".join(
            f":100644 100644 {'a' * 40} {'b' * 40} M\0{path}\0".encode() for path in paths
        )
        self.assertEqual(ALL_CHECKS, classify_diff(raw))
        for unexpected in (SOURCE, "install.sh", "settings.gradle.kts", ".github/workflows/ci.yml"):
            self.assertEqual(ALL_CHECKS, classify_paths(paths + (unexpected,)))

    def test_reconciled_public_contracts_keep_full_checks(self):
        for page in ("api", "callback-observations", "lifecycles", "limits", "program-tracing"):
            with self.subTest(page=page):
                self.assertEqual(ALL_CHECKS, classify_paths((f"docs/public/reference/{page}.mdx",)))

    def test_documentation_allowlist_excludes_every_openwiki_referenced_path(self):
        root = Path(__file__).resolve().parents[3]
        self.assert_openwiki_dependencies_excluded(root)

    def test_new_openwiki_dependencies_and_unsupported_forms_cannot_pass_the_scope_gate(self):
        for reference in (
            "[guide](../../docs/public/index.mdx#anchor)",
            "[guide](../../docs/public/%69ndex.mdx)",
            '<a href="../../docs/public/index.mdx">guide</a>',
            "[guide][api]\n[api]: ../../docs/public/index.mdx",
            "resource: repo://docs/public/index.mdx",
        ):
            with self.subTest(reference=reference), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                concept = root / "openwiki/flows/test.md"
                concept.parent.mkdir(parents=True)
                concept.write_text(reference)
                page = root / PAGE
                page.parent.mkdir(parents=True)
                page.write_text("# API\n")
                with self.assertRaises(AssertionError):
                    self.assert_openwiki_dependencies_excluded(root)

    def assert_openwiki_dependencies_excluded(self, root):
        for concept in (root / "openwiki").rglob("*.md"):
            for number, line in enumerate(unquote(concept.read_text()).splitlines(), 1):
                if "docs/public" not in line and ".mdx" not in line:
                    continue
                location = f"{concept.relative_to(root)}:{number}"
                # Audit the current literal source-path and inline-link convention.
                # Unsupported reference forms require review, rather than silently
                # expanding the docs-only closure through a permissive parser.
                # Literal paths: `- path:` code sources and `resource: repo://` page sources.
                declaration = re.fullmatch(
                    r"\s*(?:- path: |resource: repo://)(docs/public/[A-Za-z0-9_./-]+)\s*", line
                )
                if declaration:
                    targets = [(root / declaration.group(1)).resolve()]
                    remainder = ""
                else:
                    links = list(
                        re.finditer(r"\[[^]]+\]\(((?:\.\./)+docs/public/[A-Za-z0-9_./-]+)(?:#[A-Za-z0-9_-]+)?\)", line)
                    )
                    targets = [(concept.parent / link.group(1)).resolve() for link in links]
                    remainder = line
                    for link in reversed(links):
                        remainder = remainder[: link.start()] + remainder[link.end() :]
                self.assertTrue(
                    targets, f"{location}: unsupported public-doc reference form; review the scope boundary"
                )
                self.assertNotRegex(
                    remainder, r"docs/public|\.mdx", f"{location}: unsupported public-doc reference form"
                )
                for target in targets:
                    self.assertTrue(target.is_file(), f"{location}: OpenWiki dependency no longer exists")
                    path = target.relative_to(root).as_posix()
                    self.assertNotIn(
                        path,
                        DOCUMENTATION_PATHS,
                        f"{location}: OpenWiki dependencies require the full knowledge-base gate",
                    )

    def test_product_source_roots_are_bound_to_the_explicit_gradle_module_inventory(self):
        settings = (Path(__file__).resolve().parents[3] / "settings.gradle.kts").read_text()
        # Bind this repository's explicit literal declaration, not arbitrary Gradle syntax.
        declaration = re.search(
            r'val cleanSlateProjects = listOf\(\s*((?:"(?::[a-z0-9-]+)+"\s*,\s*)+)\)\s*'
            r"include\(\*cleanSlateProjects\.toTypedArray\(\)\)",
            settings,
        )
        self.assertIsNotNone(declaration, "unsupported module inventory declaration; review the scope boundary")
        declared = frozenset(
            path[1:].replace(":", "/") for path in re.findall(r'"(:[a-z0-9:-]+)"', declaration.group(1))
        )
        self.assertLessEqual(PRODUCT_MODULES, declared)


if __name__ == "__main__":
    unittest.main()
