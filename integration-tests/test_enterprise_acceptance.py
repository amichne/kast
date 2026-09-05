#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
from contextlib import redirect_stdout
import io
import hashlib
import json
import os
import shutil
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).with_name("enterprise_acceptance.py")
SPEC = importlib.util.spec_from_file_location("enterprise_acceptance", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
enterprise_acceptance = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = enterprise_acceptance
SPEC.loader.exec_module(enterprise_acceptance)


class WorkspaceSourceSnapshotTest(unittest.TestCase):
    def test_snapshot_identity_preserves_the_existing_files_and_index_material(self):
        files = (("private.kt", "regular", 0o600, "a" * 64),)
        snapshot = enterprise_acceptance.WorkspaceSourceSnapshot(files, "100644 staged-identity 0\tprivate.kt\0")
        expected = hashlib.sha256(json.dumps({"files": files, "index": snapshot.index}, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        self.assertEqual(expected, snapshot.identity)

    def test_change_evidence_is_bounded_and_identifies_only_digests_and_finite_kinds(self):
        before = enterprise_acceptance.WorkspaceSourceSnapshot((
            ("private-content.kt", "regular", 0o600, "a" * 64),
            ("private-mode.kt", "regular", 0o600, "b" * 64),
            ("private-link", "symlink", 0o700, "private-target-before"),
            ("private-type", "regular", 0o600, "d" * 64),
            ("private-removed.kt", "regular", 0o600, "c" * 64),
        ), "private-index-before")
        after = enterprise_acceptance.WorkspaceSourceSnapshot((
            ("private-content.kt", "regular", 0o600, "e" * 64),
            ("private-mode.kt", "regular", 0o700, "b" * 64),
            ("private-link", "symlink", 0o700, "private-target-after"),
            ("private-type", "directory", 0o600, ""),
            *tuple((f"private-added-{index}.kt", "regular", 0o600, "f" * 64) for index in range(70)),
        ), "private-index-after")
        report = after.changes_since(before)
        self.assertEqual(75, report["changedPathCount"])
        self.assertEqual(64, report["retainedPathCount"])
        self.assertEqual(70, report["changeKindCounts"]["added"])
        for kind in ("removed", "file-type-changed", "mode-changed", "link-target-changed", "index-changed"):
            self.assertEqual(1, report["changeKindCounts"][kind])
        self.assertNotIn("private-", json.dumps(report))
        self.assertNotEqual(before.component_evidence(), after.component_evidence())

    def test_fixture_ignores_only_its_admitted_root_gradle_cache(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            workspace = root / "workspace"
            shutil.copytree(MODULE_PATH.parent.parent / "fixtures/enterprise-workspace", workspace)
            enterprise_acceptance.prepare_workspace_fixture(workspace)
            before = enterprise_acceptance.workspace_source_identity(workspace)
            cache = workspace / ".gradle/9.4.1/fileHashes/fileHashes.bin"
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_bytes(b"observed runtime cache")
            self.assertEqual(before, enterprise_acceptance.workspace_source_identity(workspace))
            cache.write_bytes(b"updated runtime cache")
            self.assertEqual(before, enterprise_acceptance.workspace_source_identity(workspace))
            for relative in ("Untracked.kt", "nested/.gradle/visible.bin"):
                path = workspace / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("must remain visible")
                self.assertNotEqual(before, enterprise_acceptance.workspace_source_identity(workspace))
                path.unlink()
            source = workspace / "build.gradle.kts"
            source.write_text(source.read_text() + "\n// staged source change\n")
            enterprise_acceptance.git(workspace, "add", "build.gradle.kts")
            self.assertNotEqual(before, enterprise_acceptance.workspace_source_identity(workspace))


class MutationAcceptanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name).resolve(strict=True)
        self.workspace = self.cache / "workspace"
        self.workspace.mkdir()
        (self.workspace / "target.kt").write_text("class Target\n")
        enterprise_acceptance.git(self.workspace, "init", "--quiet")
        enterprise_acceptance.git(self.workspace, "config", "user.name", "Acceptance Test")
        enterprise_acceptance.git(self.workspace, "config", "user.email", "acceptance@kast.invalid")
        enterprise_acceptance.git(self.workspace, "add", ".")
        enterprise_acceptance.git(self.workspace, "commit", "--quiet", "-m", "baseline")
        log = self.cache / "identity/log/idea.log"
        log.parent.mkdir(parents=True)
        log.write_text('Kast diagnostic compilation: {"stage":"exact-scope","status":"complete","errors":{"count":0,"factoryCodes":[],"withheldFactCount":0}}\n')
        self.marker = {
            "selector": "fresh-marker-selector", "kind": "function", "name": "enterpriseMutationMarker",
            "qualifiedIdentity": "enterprise.alpha.one.EnterpriseRouter.enterpriseMutationMarker",
            "file": str(self.workspace / "domains/alpha/one/src/main/kotlin/enterprise/alpha/one/Enterprise.kt"),
        }
        self.diagnostics = {"operation": "diagnostic.check", "status": "complete", "diagnostics": []}
        self.discovery = {"operation": "symbol.discover", "status": "complete", "items": [
            {"type": "declaration", "candidateSelector": "marker-candidate"},
        ]}
        self.inspection = {"operation": "symbol.inspect", "status": "complete", "symbol": self.marker}
        self.stale = {"operation": "change.apply", "status": "rejected", "reason": "content-changed"}
        self.stale_effect = lambda: None

    def exercise(self):
        acceptance = object.__new__(enterprise_acceptance.Acceptance)
        acceptance.maximum_reconciliation_seconds = 1
        acceptance.environment = {"KAST_CACHE_ROOT": str(self.cache)}
        acceptance.workspace = self.workspace
        responses = iter([
            {"status": "complete", "planIdentity": "recovery-plan"},
            {"operation": "change.recover", "status": "complete", "state": "restored"},
            {"status": "complete", "planIdentity": "pending-plan"},
            {"status": "complete", "planIdentity": "mutation-plan"},
            {"status": "complete", "receiptIdentity": "receipt"},
            self.diagnostics,
            {"status": "rejected", "reason": "exact-selector-stale"},
            self.discovery, self.inspection, self.stale,
        ])
        def command(*arguments, **_options):
            if arguments == ("change", "apply", "--plan", "pending-plan"):
                self.stale_effect()
            return next(responses)
        acceptance.command = mock.Mock(side_effect=command)
        self.output = io.StringIO()
        with redirect_stdout(self.output):
            acceptance.prove_generation_transition("router-selector", "old-selector")
        return acceptance.command

    def test_complete_error_free_mutation_proves_the_refreshed_exact_declaration(self) -> None:
        command = self.exercise()
        self.assertIn(mock.call("symbol", "discover", "--query", "enterpriseMutationMarker",
                                "--match", "exact-name", "--limit", "2"), command.call_args_list)
        self.assertIn(mock.call("symbol", "inspect", "--candidate", "marker-candidate"), command.call_args_list)
        self.assertEqual(mock.call("change", "apply", "--plan", "pending-plan"), command.call_args_list[-1])
        marker = [json.loads(line.split(": ", 1)[1]) for line in self.output.getvalue().splitlines()
                  if line.startswith("enterprise-mutation-marker: ")][-1]
        self.assertEqual("passed", marker["status"])
        self.assertEqual("expected-member", marker["placement"])
        self.assertEqual([], marker["mismatches"])
        stale = [json.loads(line.split(": ", 1)[1]) for line in self.output.getvalue().splitlines()
                 if line.startswith("enterprise-stale-plan: ")][-1]
        self.assertEqual("passed", stale["status"])
        self.assertEqual("content-changed", stale["reason"])

    def test_relative_file_text_cannot_substitute_the_canonical_workspace_file(self) -> None:
        self.marker["file"] = "domains/alpha/one/src/main/kotlin/enterprise/alpha/one/Enterprise.kt"
        with self.assertRaisesRegex(SystemExit, "exact mutation marker"):
            self.exercise()
        self.assertIn('"mismatches": ["file"]', self.output.getvalue())

    def test_top_level_placement_rejects_with_finite_source_free_evidence(self) -> None:
        self.marker["qualifiedIdentity"] = "enterprise.alpha.one.enterpriseMutationMarker"
        with self.assertRaisesRegex(SystemExit, "exact mutation marker"):
            self.exercise()
        self.assertIn('"placement": "top-level"', self.output.getvalue())
        self.assertNotIn(str(self.workspace), self.output.getvalue())
        self.assertNotIn(self.marker["qualifiedIdentity"], self.output.getvalue())

    def test_unknown_marker_values_are_projected_to_finite_mismatches(self) -> None:
        self.marker.update(name="private-source-payload", qualifiedIdentity="private-source-payload",
                           selector="", file="private-source-payload")
        with self.assertRaisesRegex(SystemExit, "exact mutation marker"):
            self.exercise()
        self.assertNotIn("private-source-payload", self.output.getvalue())
        self.assertIn('"placement": "other"', self.output.getvalue())

    def test_reported_post_mutation_errors_cannot_be_accepted(self) -> None:
        for severity in ("error", "ERROR"):
            with self.subTest(severity=severity):
                self.diagnostics["diagnostics"] = [{"severity": severity, "code": "UNRESOLVED_REFERENCE"}]
                with self.assertRaisesRegex(SystemExit, "complete error-free post-mutation diagnostics"):
                    self.exercise()

    def test_incomplete_or_malformed_post_mutation_diagnostics_cannot_be_accepted(self) -> None:
        for diagnostics in (
            {"operation": "diagnostic.check", "status": "qualified", "diagnostics": []},
            {"operation": "diagnostic.check", "status": "rejected", "diagnostics": []},
            {"operation": "diagnostic.check", "status": "complete"},
            {"operation": "diagnostic.check", "status": "complete", "diagnostics": [{}]},
            {"operation": "other", "status": "complete", "diagnostics": []},
        ):
            with self.subTest(diagnostics=diagnostics), self.assertRaises(SystemExit):
                self.diagnostics = diagnostics
                self.exercise()

    def test_marker_must_be_one_complete_discovery(self) -> None:
        for discovery in (
            {"operation": "symbol.discover", "status": "complete", "items": []},
            {"operation": "symbol.discover", "status": "qualified", "items": self.discovery["items"]},
            {"operation": "symbol.discover", "status": "complete", "items": self.discovery["items"] * 2},
        ):
            with self.subTest(discovery=discovery), self.assertRaisesRegex(SystemExit, "one complete mutation marker"):
                self.discovery = discovery
                self.exercise()

    def test_marker_inspection_cannot_substitute_another_declaration(self) -> None:
        for key, value in (("name", "wrongName"), ("qualifiedIdentity", "other.enterpriseMutationMarker"),
                           ("file", "other.kt"), ("kind", "classlike"), ("selector", "")):
            with self.subTest(key=key), self.assertRaisesRegex(SystemExit, "exact mutation marker"):
                self.inspection["symbol"] = {**self.marker, key: value}
                self.exercise()
        self.inspection = {"operation": "symbol.inspect", "status": "qualified", "symbol": self.marker}
        with self.assertRaisesRegex(SystemExit, "exact mutation marker"):
            self.exercise()

    def test_stale_plan_requires_its_specific_finite_rejection(self) -> None:
        for result in ({"operation": "change.apply", "status": "complete"},
                       {"operation": "change.apply", "status": "rejected", "reason": "plan-not-found"},
                       {"operation": "change.apply", "status": "rejected", "reason": "generation-stale"}):
            with self.subTest(result=result), self.assertRaisesRegex(SystemExit, "content-changed"):
                self.stale = result
                self.exercise()
            self.assertIn('"stage": "stale-plan"', self.output.getvalue())
            self.assertIn('"status": "rejected"', self.output.getvalue())

    def test_stale_plan_rejection_cannot_hide_a_repository_write(self) -> None:
        self.stale_effect = lambda: (self.workspace / "target.kt").write_text("class ChangedByStalePlan\n")
        with self.assertRaisesRegex(SystemExit, "stale mutation plan changed repository contents"):
            self.exercise()

    def test_stale_plan_rejection_cannot_hide_a_new_untracked_file(self) -> None:
        self.stale_effect = lambda: (self.workspace / "unexpected.kt").write_text("class Unexpected\n")
        with self.assertRaisesRegex(SystemExit, "stale mutation plan changed repository contents"):
            self.exercise()

    def test_stale_plan_rejection_cannot_change_only_the_index(self) -> None:
        (self.workspace / "target.kt").write_text("class AlreadyUnstaged\n")
        self.stale_effect = lambda: enterprise_acceptance.git(self.workspace, "add", "target.kt")
        with self.assertRaisesRegex(SystemExit, "stale mutation plan changed repository contents"):
            self.exercise()


class IsolatedAcceptanceHostTest(unittest.TestCase):
    def test_compiler_log_projection_preserves_counts_without_payloads(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            cache = Path(raw)
            log = cache / "identity/log/idea.log"
            log.parent.mkdir(parents=True)
            log.write_text('prefix Kast diagnostic compilation: {"stage":"exact-scope","status":"complete","errors":{"count":1,"factoryCodes":["UNRESOLVED_REFERENCE"],"withheldFactCount":0},"message":"sensitive payload"}\n')
            projected = enterprise_acceptance.compiler_log_evidence(cache)
            self.assertEqual(1, projected[0]["errorCount"])
            self.assertEqual(["UNRESOLVED_REFERENCE"], projected[0]["factoryCodes"])
            self.assertNotIn("sensitive payload", str(projected))

    def test_compiler_log_projection_rejects_unbounded_factory_payload(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            cache = Path(raw)
            log = cache / "identity/log/idea.log"
            log.parent.mkdir(parents=True)
            log.write_text('Kast diagnostic compilation: {"stage":"exact-scope","status":"complete","errors":{"count":1,"factoryCodes":["private token"],"withheldFactCount":0}}\n')
            with self.assertRaises(SystemExit):
                enterprise_acceptance.compiler_log_evidence(cache)

    def test_fixture_preparation_admits_the_pinned_wrapper_before_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            workspace = Path(raw)
            (workspace / "settings.gradle.kts").write_text('rootProject.name = "wrapper-proof"\n')
            enterprise_acceptance.prepare_workspace_fixture(workspace)
            root = MODULE_PATH.parents[1]
            for relative in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
                self.assertTrue((workspace / relative).is_file(), f"fixture omitted pinned wrapper authority: {relative}")
                self.assertEqual((root / relative).read_bytes(), (workspace / relative).read_bytes())
            self.assertEqual("", enterprise_acceptance.git(workspace, "status", "--porcelain"))

    def test_child_environment_replaces_ambient_state_with_isolated_authorities(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            outer = Path(raw)
            source_archive = outer / "semantic-runtime.zip"
            source_archive.write_bytes(b"runtime")
            host_root = outer / "host"
            host_root.mkdir()

            host = enterprise_acceptance.IsolatedAcceptanceHost.create(
                host_root,
                source_archive,
            )
            environment = host.child_environment(
                {
                    "HOME": "/ambient/home",
                    "CODEX_HOME": "/ambient/codex",
                    "JAVA_OPTS": "-Duser.home=/ambient/home",
                    "KAST_RUNTIME_DIRECTORY": "/ambient/runtime",
                    "PATH": "/usr/bin:/bin",
                    "JAVA_HOME": "/jdk",
                    "LANG": "en_US.UTF-8",
                    "SECRET_SENTINEL": "must-not-escape",
                }
            )

            self.assertEqual(str(host.home), environment["HOME"])
            self.assertEqual(str(host.codex_home), environment["CODEX_HOME"])
            self.assertEqual(f"-Duser.home={host.home}", environment["JAVA_OPTS"])
            self.assertEqual(str(host.runtime / "endpoints"), environment["KAST_RUNTIME_DIRECTORY"])
            self.assertEqual(str(host.runtime / "store"), environment["KAST_RUNTIME_STORE"])
            self.assertEqual(str(host.archive), environment["KAST_RUNTIME_ARCHIVE"])
            self.assertEqual(str(host.runtime / "intellij-caches"), environment["KAST_CACHE_ROOT"])
            self.assertEqual(str(host.temporary), environment["TMPDIR"])
            self.assertNotIn("SECRET_SENTINEL", environment)
            self.assertEqual(b"runtime", host.archive.read_bytes())
            for path in (
                host.home,
                host.codex_home,
                host.runtime,
                host.archive,
                host.app_server_control,
                host.temporary,
                host.workspace,
            ):
                self.assertTrue(path.resolve().is_relative_to(host.root))
            host.assert_confined()

    def test_cleanup_runs_after_passing_and_deliberately_failing_scenarios(self) -> None:
        for scenario_fails in (False, True):
            with self.subTest(scenario_fails=scenario_fails):
                events: list[str] = []

                class FakeAcceptance:
                    def prove_installed_surface(self, _bounds: object) -> None:
                        events.append("scenario")
                        if scenario_fails:
                            raise RuntimeError("deliberate acceptance failure")

                    def prove_workspace_write_scope(self) -> None:
                        events.append("workspace-scope")

                    def command(self, *argv: str) -> dict[str, str]:
                        self_outer.assertEqual(("stop",), argv)
                        events.append("stop-indexer")
                        return {
                            "command": "stop",
                            "status": "complete",
                            "runtime": "stopped",
                        }

                class FakeHost:
                    def retire_broker(self, _timeout_seconds: int) -> None:
                        events.append("retire-broker")

                    def assert_confined(self) -> None:
                        events.append("assert-confined")

                class FakeAmbient:
                    def assert_unchanged(self) -> None:
                        events.append("assert-ambient")

                self_outer = self
                if scenario_fails:
                    with self.assertRaisesRegex(RuntimeError, "deliberate"):
                        enterprise_acceptance.run_acceptance_scenario(
                            FakeAcceptance(), FakeHost(), {}, FakeAmbient(), 10
                        )
                    self.assertEqual(
                        [
                            "scenario",
                            "stop-indexer",
                            "retire-broker",
                            "assert-confined",
                            "assert-ambient",
                        ],
                        events,
                    )
                else:
                    enterprise_acceptance.run_acceptance_scenario(
                        FakeAcceptance(), FakeHost(), {}, FakeAmbient(), 10
                    )
                    self.assertEqual(
                        [
                            "scenario",
                            "workspace-scope",
                            "stop-indexer",
                            "retire-broker",
                            "assert-confined",
                            "assert-ambient",
                        ],
                        events,
                    )


if __name__ == "__main__":
    unittest.main()
