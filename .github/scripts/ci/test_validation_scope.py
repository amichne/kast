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

import verification_plan
from validation_scope import (
    ALL_CHECKS,
    DOCUMENTATION_PATHS,
    PRODUCT_MODULES,
    Check,
    NativeCompilation,
    Reason,
    Selection,
    SCOPE_CHECKS,
    Scope,
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


class ValidationScopeTest(unittest.TestCase):
    def test_native_toggle_preserves_all_other_obligations_for_each_scope(self):
        for scope, checks in SCOPE_CHECKS.items():
            selected = Selection(checks, Reason.CHANGED_INPUTS)
            with self.subTest(scope=scope):
                self.assertIs(selected, selected.with_native_compilation(NativeCompilation.ENABLED))
                disabled = selected.with_native_compilation(NativeCompilation.DISABLED)
                self.assertEqual(checks - {Check.PRODUCT, Check.GATE_GRAPH}, disabled.checks)
                self.assertEqual(disabled.checks, SCOPE_CHECKS[disabled.scope])
                self.assertNotIn(disabled.scope, (Scope.FULL, Scope.PRODUCT))

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
                "source=run\nproduct=skip\nportable=run\ndocumentation=run\ngate_graph=skip\nscope=SOURCE_FULL\n",
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
            "docs/tooling",
            "gradle/plugins",
            "config",
            "build-policy",
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
            "docs/documentation-structure.md", "docs/hosted-read-configuration.md",
            "docs/static-query-execution.md", "docs/okf-public.json",
            "docs/tooling/okf_public.py", "docs/tooling/test_okf_public.py",
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
            "experiments/host-observation/HOSTED_ENDPOINT.md",
            "experiments/host-observation/PI_EVALUATION.md",
            "experiments/host-observation/SEMANTIC_REPRODUCTION.md",
            "experiments/representation-impact/README.md",
        )
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(DOCS, classify_paths((path,)))
        self.assertEqual(DOCS, classify_paths(paths + (PAGE,)))

    def test_documentation_neighbors_cannot_inherit_reviewed_ownership(self):
        for path in (
            ".github/scripts/ci/new.py", "docs/tooling/new.py",
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
                     "docs/public/snippets/value-journey.jsx", "docs/tooling/value-journey/src/trace.ts"):
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
            "packaging/test-install-local.py",
            "packaging/portable.Dockerfile",
            "docs/tooling/check_glossary.py",
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
                "source=skip\nproduct=skip\nportable=skip\ndocumentation=run\ngate_graph=skip\nscope=DOCUMENTATION\n",
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
        self.write("docs/tooling/okf_public.py", "# documentation projector\n")
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

    def test_source_pr_defers_candidate_but_main_push_keeps_qualification(self):
        self.write("query/service/src/main/kotlin/QueryService.kt", "class QueryService\n")
        head = self.commit("source change")
        self.assertEqual(SOURCE_ONLY, select("pull_request", self.base, head, self.root).checks)
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
    def test_native_toolchain_pins_and_reports_do_not_require_oracle(self):
        root = Path(__file__).resolve().parents[3]
        pins = json.loads((root / "packaging/cloud/pins.json").read_text())
        url = unquote(pins["artifacts"]["graalvm"]["url"])
        self.assertTrue(url.startswith("https://github.com/bell-sw/LibericaNIK/releases/"))
        release = re.search(r"bellsoft-liberica-vm-openjdk([\d.+]+)-([\d.+]+)-linux-amd64.tar.gz$", url)
        self.assertIsNotNone(release)
        jdk, nik = release.groups()
        mise = tomllib.loads((root / "mise.toml").read_text())
        self.assertEqual(f"liberica-nik-openjdk{jdk.split('.')[0]}-{nik}", mise["tools"]["java"])
        self.assertEqual(f"liberica-nik-{nik}", pins["artifacts"]["graalvm"]["directory"])
        producers = []
        for path in (root / ".github/workflows").glob("*.yml"):
            for distribution, version in re.findall(
                r"(?m)^\s+distribution: (\S+)\n\s+java-version: ([^\n]+)", path.read_text()
            ):
                with self.subTest(workflow=path.name, distribution=distribution):
                    self.assertEqual("liberica-nik", distribution)
                    self.assertEqual(jdk, version.strip("'\""))
                producers.append(path.name)
        self.assertTrue(producers)
        arguments = (root / "cli/native-image.args").read_text().splitlines()
        self.assertNotIn("--emit", arguments)
        self.assertIn("-H:+GenerateEmbeddedResourcesFile", arguments)

    def test_candidate_retention_routes_reuse_to_linux_before_fresh_mac_selection(self):
        workflow = (Path(__file__).resolve().parents[2] / "workflows/ci.yml").read_text()
        product = workflow.split("\n  kotlin:\n", 1)[1]
        product = re.split(r"\n  (?=\S)", product, maxsplit=1)[0]
        # Bind the workflow's explicit expression, as with the other inventory guards.
        # Hosted execution must separately establish which runner actually ran it.
        runner = next(line.strip() for line in product.splitlines() if "runs-on:" in line)
        self.assertEqual(
            "runs-on: ${{ needs.source-work.outputs.candidate == 'REUSED' && 'ubuntu-latest' || "
            'fromJSON(\'["self-hosted", "macOS", "ARM64", "kast-main"]\') }}',
            runner,
        )
        self.assertIn(
            "needs.source-work.outputs.candidate == 'REUSED' || "
            "(needs.native.result == 'success' && needs.assembly.result == 'success')",
            product,
        )
        for step in (
            "Exercise the assembled Mac product",
            "Seal tested component candidate identity",
        ):
            with self.subTest(step=step):
                self.assertIn(
                    f"- name: {step}\n        if: needs.source-work.outputs.candidate != 'REUSED'\n",
                    product,
                )
        self.assertIn("name: ci-build-candidate-", product)
        self.assertNotIn("name: ci-product-candidate-", product)

    def test_self_hosted_native_producers_have_no_hosted_runner_fallback(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        # The composed target matrix has its own explicit hosted runner contract below.
        # Stable promotion consumes retained bytes and is no longer a native producer.
        for filename, job in (("ci.yml", "native"), ("release-component.yml", "release")):
            with self.subTest(workflow=filename, job=job):
                body = (workflows / filename).read_text().split(f"\n  {job}:\n", 1)[1]
                body = re.split(r"\n  (?=\S)", body, maxsplit=1)[0]
                runner = next(line.strip() for line in body.splitlines() if "runs-on:" in line)
                self.assertEqual("runs-on: [self-hosted, macOS, ARM64, kast-main]", runner)

    def test_native_compilation_is_main_only_before_scope_selection(self):
        workflow = (Path(__file__).resolve().parents[2] / "workflows/ci.yml").read_text()
        self.assertIn(
            "KAST_NATIVE_COMPILATION: ${{ vars.KAST_MACOS_CHECKS == 'true' && "
            "github.ref == 'refs/heads/main' && "
            "(github.event_name == 'push' || github.event_name == 'workflow_dispatch') && "
            "(vars.KAST_NATIVE_COMPILATION || 'true') || 'false' }}",
            workflow,
        )

    def test_every_mac_runner_requires_explicit_platform_opt_in(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        guarded = set()
        for path in workflows.glob("*.yml"):
            for job, body in re.findall(
                r"(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)", path.read_text()
            ):
                runner = re.search(r"^    runs-on: (.+)$", body, re.M)
                if runner is None or "macos" not in runner[1].lower():
                    continue
                with self.subTest(workflow=path.name, job=job):
                    condition = re.search(r"^    if: (.+)$", body, re.M)
                    self.assertIsNotNone(condition)
                    self.assertIn("vars.KAST_MACOS_CHECKS == 'true'", condition[1])
                guarded.add((path.name, job))
        self.assertEqual(
            {("ci.yml", "native"), ("ci.yml", "kotlin"), ("release-component.yml", "release")},
            guarded,
        )
        ci = (workflows / "ci.yml").read_text()
        complete = ci.split("\n  composed-products:\n", 1)[1]
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
            jobs.index("Install and qualify the exact candidate"),
        )

    def test_isolated_candidate_source_guards_share_the_assembly_invocation(self):
        workflow = (
            Path(__file__).resolve().parents[2] / "workflows/composed-linux.yml"
        ).read_text()
        assembly = workflow.split("- name: Assemble matching Control, Host, and bootstrap\n", 1)[1]
        assembly = assembly.split("\n      - name:", 1)[0]
        self.assertIn(
            "if: inputs.release_target != 'macos-aarch64' || (inputs.complete_product && !inputs.reuse_ci_release)",
            assembly,
        )
        self.assertIn("assembleRelease :runtime:hosted:headlessBootstrapPlugin", assembly)
        self.assertIn(
            "${{ matrix.target == 'linux-x86_64' && 'verifyJsonContracts verifyKastArchitecture' || '' }}",
            assembly,
        )
        bootstrap = workflow.split("- name: Assemble bootstrap for the reused candidate\n", 1)[1]
        bootstrap = bootstrap.split("\n      - name:", 1)[0]
        self.assertIn("(!inputs.complete_product || inputs.reuse_ci_release)", bootstrap)
        self.assertIn(":runtime:hosted:headlessBootstrapPlugin", bootstrap)
        self.assertNotIn("assembleRelease", bootstrap)

    def test_complete_two_target_qualification_precedes_preview_publication(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        ci = (workflows / "ci.yml").read_text()
        jobs = dict(
            re.findall(r"(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)", ci.split("\njobs:\n", 1)[1])
        )
        native, joined, required = (
            jobs[name] for name in ("composed-products", "sealed-product", "required")
        )
        self.assertIn("needs: scope", native)
        self.assertIn("uses: ./.github/workflows/composed-linux.yml", native)
        self.assertIn("complete_product: true", native)
        self.assertIn("version: 0.0.${{ github.run_number }}", native)
        self.assertIn("release_target: linux-x86_64", native)
        mac = jobs["composed-macos"]
        self.assertIn("needs: [scope, source-work, assembly]", mac)
        self.assertIn("!cancelled()", mac)
        self.assertIn("needs.source-work.result == 'success'", mac)
        self.assertIn(
            "(needs.source-work.outputs.candidate == 'REUSED' || needs.assembly.result == 'success')",
            mac,
        )
        self.assertIn("reuse_ci_release: ${{ needs.source-work.outputs.candidate != 'REUSED' }}", mac)
        self.assertIn("uses: ./.github/workflows/composed-linux.yml", mac)
        self.assertIn("complete_product: true", mac)
        self.assertIn("release_target: macos-aarch64", mac)
        self.assertIn("needs: [scope, source-work, composed-products, composed-macos]", joined)
        self.assertNotIn("always()", joined)
        self.assertNotIn("run-id:", joined)
        for target in ("linux-x86_64", "macos-aarch64"):
            with self.subTest(target=target):
                self.assertIn(
                    f"name: kast-qualified-product-{target}-"
                    "${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}",
                    joined,
                )
                self.assertIn(f"path: build/product-inputs/{target}", joined)
        self.assertIn("product_producer.py seal", joined)
        self.assertNotIn("name: ci-product-candidate-", joined)
        accepted = jobs["complete-product"]
        self.assertIn("needs: [sealed-product, installed-product]", accepted)
        self.assertNotIn("always()", accepted)
        self.assertIn("product_producer.py accept", accepted)
        for target in ("linux-x86_64", "macos-aarch64"):
            self.assertIn(
                f"name: kast-installed-product-{target}-"
                "${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}", accepted
            )
        installed = jobs["installed-product"]
        self.assertIn("needs: sealed-product", installed)
        self.assertIn("runner: ubuntu-24.04", installed)
        self.assertIn("runner: macos-15", installed)
        self.assertIn("product_lifecycle_probe.py", installed)
        self.assertIn("KAST_ACCEPTANCE_BASELINE_RECORD_SHA256", installed)
        self.assertIn(
            "name: ci-product-candidate-${{ github.sha }}-${{ github.run_attempt }}", accepted
        )
        self.assertIn(
            "needs: [scope, source, documentation, portable, kotlin, complete-product]", required
        )
        self.assertIn("if: needs.scope.outputs.product == 'run'", required)
        self.assertIn("COMPLETE_PRODUCT_RESULT: ${{ needs.complete-product.result }}", required)
        self.assertLess(
            required.index('test "$COMPLETE_PRODUCT_RESULT" = success'),
            required.index("validation_outcome.py"),
        )
        candidate = (workflows / "composed-linux.yml").read_text()
        reused = candidate.split("- name: Download the exact CI release\n", 1)[1]
        reused = reused.split("- name: Assemble bootstrap", 1)[0]
        self.assertIn("inputs.reuse_ci_release && inputs.release_target == 'macos-aarch64'", reused)
        self.assertIn("name: assembled-release-${{ github.run_id }}-${{ github.run_attempt }}", reused)
        self.assertNotIn("run-id:", reused)
        self.assertIn("native_handoff.py restore --version", reused)
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
        self.assertIn('--version "$BUNDLE_VERSION" --source-revision "$GITHUB_SHA"', candidate)
        self.assertIn("--receipt build/composed-evidence/qualification.json", candidate)
        self.assertLess(
            candidate.index("packaging/composed_release.py qualify"),
            candidate.index("product_producer.py target"),
        )
        self.assertLess(
            candidate.index("product_producer.py target"),
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
        self.assertIn("build_candidate.py promote-product", preview)
        self.assertIn('--revision "$SOURCE_SHA"', preview)
        self.assertIn('--run-id "$MAIN_RUN_ID" --run-attempt "$MAIN_RUN_ATTEMPT"', preview)
        self.assertIn("if: steps.candidate.outputs.publication == 'PUBLISH'", preview)
        self.assertLess(
            preview.index("build_candidate.py promote-product"),
            preview.index("publish_developer.py product"),
        )
        for producer in (native, joined, candidate, required, preview):
            self.assertNotIn("continue-on-error:", producer)
        for consumer in (joined, preview):
            for build_command in ("./gradlew", "assembleRelease", "composed_release.py assemble"):
                self.assertNotIn(build_command, consumer)

    def test_stable_promotion_only_maps_exact_retained_preview_bytes(self):
        release = (Path(__file__).resolve().parents[2] / "workflows/release.yml").read_text()
        jobs = release.split("\njobs:\n", 1)[1]
        self.assertEqual(["publish"], re.findall(r"^  ([\w-]+):$", jobs, re.M))
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
        actions = re.findall(r"(?m)^\s+uses: (\S+)|^\s+- uses: (\S+)", jobs)
        self.assertEqual(1, len(actions))
        self.assertTrue((actions[0][0] or actions[0][1]).startswith("actions/checkout@"))
        self.assertEqual(1, len(re.findall(r"(?m)^\s+(?:- )?run:", jobs)))
        commands = re.findall(r"(?m)^        run: >-\n((?:          [^\n]+\n?)+)", jobs)
        self.assertEqual(1, len(commands))
        self.assertEqual(
            "python3 .github/scripts/release/publish_developer.py stable "
            '--source-tag "$SOURCE_TAG" --record-sha256 "$RECORD_SHA256" '
            '--public-version "$PUBLIC_VERSION"',
            " ".join(commands[0].split()),
        )

    def test_validation_jobs_own_shared_cache_population(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        self.assertFalse((workflows / "gradle-cache.yml").exists())
        workflow = (workflows / "ci.yml").read_text()
        for job in ("source-work", "native"):
            body = workflow.split(f"\n  {job}:\n", 1)[1]
            body = re.split(r"\n  (?=\S)", body, maxsplit=1)[0]
            self.assertIn("uses: gradle/actions/setup-gradle@", body)
            self.assertIn("cache-read-only: ${{ !(github.ref == 'refs/heads/main'", body)
        for case in ("NativeResetListenerTest", "NativeResetPeerAcceptanceTest", "ResetLaunchObservationTest"):
            self.assertIn(f"--tests '*{case}'", workflow)

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
        native = workflow.split("\n  composed-products:\n", 1)[1]
        native = re.split(r"\n  (?=\S)", native, maxsplit=1)[0]
        for prerequisite in (
            "needs.scope.outputs.product == 'run'",
            "vars.KAST_MACOS_CHECKS == 'true'",
            "vars.KAST_NATIVE_COMPILATION != 'false'",
            "github.ref == 'refs/heads/main'",
            "github.event_name == 'push' || github.event_name == 'workflow_dispatch'",
        ):
            self.assertIn(prerequisite, native)
        # Already qualified immutable bytes remain promotable without enabling new builds.
        stable = (workflows / "release.yml").read_text()
        self.assertNotIn("vars.KAST_MACOS_CHECKS", stable)
        self.assertNotIn("vars.KAST_NATIVE_COMPILATION", stable)
        self.assertIn("publish_developer.py stable", stable)
        self.assertIn('--record-sha256 "$RECORD_SHA256"', stable)

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
                declaration = re.fullmatch(r"\s*- path: (docs/public/[A-Za-z0-9_./-]+)\s*", line)
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
