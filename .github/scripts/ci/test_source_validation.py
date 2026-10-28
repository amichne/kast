#!/usr/bin/env python3
"""Independent source obligations reject absent proofs and incidental assembly."""
import csv
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

import source_validation as source
import verification_plan
from source_validation import COMMAND, inspect_graph
from validation_scope import ALL_CHECKS, PRODUCT_MODULES, Reason, Selection

ROOT = Path(__file__).resolve().parents[3]


def expected_obligations():
    owners = re.findall(r'^\s*"(:[a-z-]+(?::[a-z-]+)?)",?$', (ROOT / "settings.gradle.kts").read_text(), re.MULTILINE)
    return {f"{owner}:{task}" for owner in owners for task in ("check", "scopedCheck", "test")} | {
        ":sourceValidationGate", ":check", ":build-logic:check", ":verifyJsonContracts", ":verifyKastArchitecture", ":verifyVerificationGraph",
        ":verifyConfigurationIngress", ":verifyKnowledgeBase", ":hostObservationTest", ":componentReleaseTest",
        ":verifyDocumentationGlossary", ":documentationToolingTest", ":verifyDocumentationMarkdown",
        ":verifyKotlinSemanticCoverage", ":verifyKotlinSemanticInputs", ":verifyKotlinSemanticInventory",
        ":cli:nativeTest", ":cli:queryDeliveryContractTest", ":cli:queryDeliverySchemaTest",
        ":cli:queryPresentationTest", ":cli:verifyMintlifyCallableReference", ":app-server:verifyPublicQueryGeneration",
        ":relation:intellij:semanticCoverageFixtureTest", ":runtime:hosted:semanticCoverageFixtureTest",
        ":symbol:intellij:semanticCoverageFixtureTest", ":relation:intellij:dependencyFixtureTest",
        ":runtime:hosted:dependencyFixtureTest", ":productGateVersionTest", ":installerFixtureTest",
        ":installationLifecycleTest", ":priorInstallationCleanupTest", ":installationRecoveryTest",
        ":installationSystemPythonTest", ":localInstallationTest", ":installerRemovalTest",
        ":testCheckoutInstaller", ":installerEntrypointTest",
    }


def graph_output(tasks):
    return "\n".join(f"{task} SKIPPED" for task in sorted(tasks))


class SourceValidationTest(unittest.TestCase):
    def setUp(self):
        enabled = patch.dict(os.environ, {"KAST_SEMANTIC_FIXTURES": "true"})
        enabled.start()
        self.addCleanup(enabled.stop)

    def test_semantic_fixtures_default_to_disabled_and_accept_explicit_opt_in(self):
        expected = (
            ":relation:intellij:semanticCoverageFixtureTest",
            ":runtime:hosted:semanticCoverageFixtureTest",
            ":symbol:intellij:semanticCoverageFixtureTest",
            ":verifyKotlinSemanticCoverage",
            ":verifyKotlinSemanticInventory",
            ":verifySelectedKotlinSemanticCoverage",
        )
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(expected, source.skipped_semantic_tasks(ROOT))
        with patch.dict(os.environ, {"KAST_SEMANTIC_FIXTURES": "false"}):
            self.assertEqual(expected, source.skipped_semantic_tasks(ROOT))
        self.assertEqual((), source.skipped_semantic_tasks(ROOT))
        for value in ("", "False", "TRUE", "0", "typo"):
            with self.subTest(value=value), patch.dict(os.environ, {"KAST_SEMANTIC_FIXTURES": value}):
                with self.assertRaisesRegex(ValueError, "must be true or false"):
                    source.skipped_semantic_tasks(ROOT)

    def test_disabled_fixtures_keep_other_obligations_and_record_skips(self):
        with patch.dict(os.environ, {"KAST_SEMANTIC_FIXTURES": "false"}):
            skipped = source.skipped_semantic_tasks(ROOT)
            required = expected_obligations() - set(skipped)
            with (
                tempfile.TemporaryDirectory() as temporary,
                patch.dict(os.environ, {"JAVA_HOME": "/source-jdk"}),
                patch.object(source, "source_identity", return_value=("a" * 40, "b" * 40)),
                patch.object(source.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess([], 0, "preflight passed"),
                    subprocess.CompletedProcess([], 0, graph_output(required)),
                ]),
                patch.object(source.subprocess, "Popen") as executed,
                redirect_stdout(io.StringIO()),
            ):
                executed.return_value.stdout = io.StringIO("")
                executed.return_value.wait.return_value = 0
                destination = Path(temporary)
                evidence = source.run(ROOT, destination)
                report = json.loads((destination / "source-execution.json").read_text())
            self.assertEqual(source.Outcome.SOURCE_VALIDATED, evidence.outcome)
            self.assertEqual(list(skipped), report["skippedTasks"])
            self.assertEqual(2, report["schemaVersion"])
            self.assertIn(":verifyKotlinSemanticInputs", evidence.graph.tasks)
            self.assertIn(":relation:intellij:dependencyFixtureTest", evidence.graph.tasks)
            for task in skipped:
                with self.subTest(unexpected=task):
                    self.assertFalse(inspect_graph(graph_output(required | {task}), frozenset(required), skipped).accepted)
            for task in required:
                with self.subTest(missing=task):
                    self.assertFalse(inspect_graph(graph_output(required - {task}), frozenset(required)).accepted)

    def test_disabled_task_inventory_rejects_empty_duplicate_and_invalid_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory = root / "config/ci/semantic-fixture-tasks.txt"
            inventory.parent.mkdir(parents=True)
            for text in ("", ":owner:task\n:owner:task\n", "--exclude-task :owner:task\n"):
                with self.subTest(text=text), patch.dict(os.environ, {"KAST_SEMANTIC_FIXTURES": "false"}):
                    inventory.write_text(text)
                    with self.assertRaisesRegex(ValueError, "Invalid semantic fixture task inventory"):
                        source.skipped_semantic_tasks(root)

    def test_affected_entry_point_keeps_the_full_fallback_for_every_workflow_event(self):
        for event in ("pull_request", "push", "merge_group", "workflow_dispatch"):
            with (
                self.subTest(event=event),
                patch.object(sys, "argv", ["source_validation.py", "--affected", "--event", event,
                                           "--base", "b" * 40]),
                patch.dict(os.environ, {"CI_VERIFICATION_PLAN": ""}),
                patch.object(source, "source_identity", return_value=("a" * 40, "c" * 40)),
                patch.object(source, "select", return_value=Selection(ALL_CHECKS, Reason.FULL_EVENT)) as select,
                patch.object(source, "run", return_value=Mock(outcome=source.Outcome.SOURCE_VALIDATED,
                                                              exitCode=0)) as run,
                redirect_stdout(io.StringIO()),
                self.assertRaises(SystemExit) as result,
            ):
                source.main()
            self.assertEqual(0, result.exception.code)
            select.assert_called_once_with(event, "b" * 40, "a" * 40, affected=True)
            run.assert_called_once_with(ROOT, ROOT / "build/reports/ci/source", None)

    def test_selected_command_keeps_complete_owner_population_and_manifest_fields(self):
        plan = verification_plan.plan(
            ROOT, frozenset(":" + path.replace("/", ":") for path in PRODUCT_MODULES),
            ("query/contract/src/main/kotlin/QueryRows.kt",),
            "a" * 40, "b" * 40, "c" * 40, "d" * 40, "pull_request",
        )
        self.assertIsInstance(plan, verification_plan.Plan)
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            command = source.selected_command(ROOT, destination, plan)
            with (destination / "selected-cases.tsv").open() as selected:
                actual = list(csv.DictReader(selected, delimiter="\t"))
        with (ROOT / verification_plan.MANIFEST).open() as manifest:
            expected = [row for row in csv.DictReader(manifest, delimiter="\t")
                        if row["verification_owner"] == ":runtime:hosted"]
        self.assertTrue(expected)
        self.assertEqual(expected, actual)
        self.assertIn("-PkastSemanticOwners=:runtime:hosted", command)
        self.assertIn(":verifySelectedKotlinSemanticCoverage", command)
        self.assertNotIn("sourceValidationGate", command)
        required = source.obligations(plan)
        self.assertIn(":cli:queryDeliveryContractTest", required)
        self.assertNotIn(":symbol:intellij:semanticCoverageFixtureTest", required)
        self.assertNotIn(":relation:intellij:semanticCoverageFixtureTest", required)

    def test_direct_file_consumers_keep_full_owner_obligations_and_cases(self):
        matrix = (
            ("cli/src/main/js/query-delivery.mjs", {":app-server:check", ":app-server:test",
              ":app-server:verifyPublicQueryGeneration"}, {":runtime:hosted"}),
            ("cli/src/main/js/query-delivery-contract.mjs", {":app-server:check", ":app-server:test",
              ":app-server:verifyPublicQueryGeneration"}, {":runtime:hosted"}),
            ("cli/src/main/js/query-presentation.mjs", {":app-server:check", ":app-server:test",
              ":app-server:verifyPublicQueryGeneration"}, {":runtime:hosted"}),
            ("cli/src/main/js/query-session.mjs", {":app-server:check", ":app-server:test",
              ":app-server:verifyPublicQueryGeneration"}, {":runtime:hosted"}),
            ("cli/src/main/resources/query-delivery.schema.json",
             {":app-server:check", ":app-server:test", ":app-server:verifyPublicQueryGeneration"}, set()),
            ("cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt",
             {":app-server:check", ":app-server:test", ":app-server:verifyPublicQueryGeneration"}, set()),
            ("cli/src/test/resources/hosted-read-failure-encodings.json",
             {":workspace:intellij-read:check", ":workspace:intellij-read:test"},
             {":symbol:intellij", ":relation:intellij", ":runtime:hosted"}),
        )
        with (ROOT / verification_plan.MANIFEST).open() as manifest:
            full_population = list(csv.DictReader(manifest, delimiter="\t"))
        for path, required, semantic_owners in matrix:
            with self.subTest(path=path):
                plan = verification_plan.plan(
                    ROOT, frozenset(":" + path.replace("/", ":") for path in PRODUCT_MODULES),
                    (path,), "a" * 40, "b" * 40, "c" * 40, "d" * 40, "pull_request",
                )
                self.assertIsInstance(plan, verification_plan.Plan)
                self.assertLessEqual(required, source.obligations(plan))
                with tempfile.TemporaryDirectory() as temporary:
                    destination = Path(temporary)
                    source.selected_command(ROOT, destination, plan)
                    selected_path = destination / "selected-cases.tsv"
                    if semantic_owners:
                        with selected_path.open() as selected:
                            actual = list(csv.DictReader(selected, delimiter="\t"))
                    else:
                        self.assertFalse(selected_path.exists())
                        actual = []
                expected = [row for row in full_population
                            if row["verification_owner"] in semantic_owners]
                self.assertEqual(expected, actual)

    def test_process_failure_and_source_change_never_emit_success(self):
        identity = ("a" * 40, "b" * 40)
        for status, after, expected in (
            (0, identity, source.Outcome.SOURCE_VALIDATED),
            (1, identity, source.Outcome.PROCESS_FAILED),
            (0, ("c" * 40, "d" * 40), source.Outcome.SOURCE_CHANGED),
            (0, subprocess.CalledProcessError(1, "admit-source"), source.Outcome.SOURCE_CHANGED),
        ):
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary)
                process = Mock(stdout=io.StringIO("Dependency fixture compiler invocation: :owner:fixture\n"))
                process.wait.return_value = status
                with (
                    patch.dict(os.environ, {"JAVA_HOME": "/source-jdk"}),
                    patch.object(source, "source_identity", side_effect=[identity, after]) as admitted,
                    patch.object(source.subprocess, "run", side_effect=[
                        subprocess.CompletedProcess([], 0, "preflight passed"),
                        subprocess.CompletedProcess([], 0, graph_output(expected_obligations())),
                    ]) as planned,
                    patch.object(source.subprocess, "Popen", return_value=process) as executed,
                    redirect_stdout(io.StringIO()),
                ):
                    evidence = source.run(ROOT, destination)
                self.assertEqual(expected, evidence.outcome)
                self.assertEqual(1, evidence.compilerInvocations)
                self.assertEqual(expected is source.Outcome.SOURCE_VALIDATED, evidence.exitCode == 0)
                self.assertEqual(2, admitted.call_count)
                self.assertEqual(2, planned.call_count)
                self.assertEqual([source.PREFLIGHT, [*COMMAND, "--dry-run"]],
                                 [call.args[0] for call in planned.call_args_list])
                executed.assert_called_once_with(COMMAND, cwd=ROOT, text=True,
                                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                process.wait.assert_called_once_with()
                self.assertEqual(expected.value, json.loads((destination / "source-execution.json").read_text())["outcome"])

    def test_rejected_graph_never_starts_source_execution(self):
        with (
            patch.dict(os.environ, {"JAVA_HOME": "/source-jdk"}),
            tempfile.TemporaryDirectory() as temporary,
            patch.object(source, "source_identity", return_value=("a" * 40, "b" * 40)),
            patch.object(source.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "BUILD SUCCESSFUL")),
            patch.object(source.subprocess, "Popen") as executed,
        ):
            evidence = source.run(ROOT, Path(temporary))
        executed.assert_not_called()
        self.assertEqual(source.Outcome.GRAPH_REJECTED, evidence.outcome)
        self.assertNotEqual(0, evidence.exitCode)

    def test_failed_offline_preflight_never_starts_gradle_execution(self):
        def run(command, **_):
            if command == source.PREFLIGHT:
                return subprocess.CompletedProcess(command, 2, "offline suite failed")
            self.assertEqual([*COMMAND, "--dry-run"], command)
            return subprocess.CompletedProcess(command, 0, graph_output(expected_obligations()))

        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.dict(os.environ, {"JAVA_HOME": "/source-jdk"}),
            patch.object(source, "source_identity", return_value=("a" * 40, "b" * 40)),
            patch.object(source.subprocess, "run", side_effect=run) as commands,
            patch.object(source.subprocess, "Popen") as executed,
        ):
            destination = Path(temporary)
            evidence = source.run(ROOT, destination)
            self.assertEqual("offline suite failed", (destination / "preflight.log").read_text())
            self.assertFalse((destination / "task-graph.log").exists())
            report = json.loads((destination / "source-execution.json").read_text())
        self.assertEqual("PROCESS_FAILED", report["outcome"])
        self.assertEqual([], report["graph"]["tasks"])
        self.assertEqual(sorted(expected_obligations()), report["graph"]["missing"])
        executed.assert_not_called()
        self.assertEqual([source.PREFLIGHT], [call.args[0] for call in commands.call_args_list])
        self.assertEqual(source.Outcome.PROCESS_FAILED, evidence.outcome)
        self.assertEqual(2, evidence.exitCode)

    def test_source_obligations_include_every_owner_and_specialized_check(self):
        self.assertTrue(inspect_graph(graph_output(expected_obligations())).accepted)
        for omitted in expected_obligations():
            with self.subTest(omitted=omitted):
                graph = inspect_graph(graph_output(expected_obligations() - {omitted}))
                self.assertFalse(graph.accepted)
                self.assertIn(omitted, graph.missing)

    def test_native_compilation_staging_and_release_assembly_reject(self):
        for task in (
            ":distribution:cli:nativeCompile", ":distribution:cli:nativeTestCompile", ":stageKastControlProduct",
            ":installedProductTest", ":assembleRelease", ":assembleKastControlDist",
            ":runtime:hosted:hostedPlugin", ":app-server:verifyReleaseRuntimeAdmission", ":generateHostReleaseRecord",
        ):
            with self.subTest(task=task):
                graph = inspect_graph(graph_output(expected_obligations() | {task}))
                self.assertFalse(graph.accepted)
                self.assertEqual((task,), graph.forbidden)

    def test_empty_or_incidental_output_never_admits_a_source_gate(self):
        for output in ("", "BUILD SUCCESSFUL", "requested :sourceValidationGate SKIPPED"):
            self.assertFalse(inspect_graph(output).accepted)

    def test_run_numbers_cannot_change_source_version_arguments(self):
        for number in ("1", "999999"):
            with patch.dict(os.environ, {"GITHUB_RUN_NUMBER": number}):
                self.assertIn("-Pversion=0.0.0", COMMAND)
                self.assertIn("-PcontrolVersion=0.0.0", COMMAND)
                self.assertIn("-PhostedPluginVersion=0.0.0", COMMAND)
                self.assertNotIn("assembleRelease", COMMAND)
                self.assertNotIn("productBuildGate", COMMAND)


if __name__ == "__main__":
    unittest.main()
