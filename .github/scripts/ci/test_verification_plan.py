#!/usr/bin/env python3
"""The frozen owner matrix supplies expected obligations independently of the selector."""

import json
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path

import verification_plan as selection
from validation_scope import PRODUCT_MODULES

ROOT = Path(__file__).resolve().parents[3]
MODULES = frozenset(":" + path.replace("/", ":") for path in PRODUCT_MODULES)


class VerificationPlanTest(unittest.TestCase):
    def plan(self, *paths, root=ROOT):
        return selection.plan(
            root, MODULES, paths, "a" * 40, "b" * 40, "c" * 40, "d" * 40, "pull_request"
        )

    def test_leaf_contract_adapter_and_shared_resource_consumers(self):
        matrix = (
            (
                "query/service/src/main/kotlin/PipelineCheckpoint.kt",
                {":query:service", ":query:protocol", ":runtime:hosted", ":cli"},
            ),
            (
                "query/contract/src/main/kotlin/QueryRows.kt",
                {":query:contract", ":query:service", ":query:protocol", ":runtime:hosted", ":cli"},
            ),
            (
                "relation/intellij/src/main/kotlin/NativeValueImplementationDispatch.kt",
                {":relation:intellij", ":runtime:hosted", ":query:protocol", ":cli"},
            ),
        )
        for path, expected in matrix:
            with self.subTest(path=path):
                plan = self.plan(path)
                self.assertIsInstance(plan, selection.Plan)
                self.assertEqual(expected, set(plan.consumers))
                for owner in expected:
                    self.assertIn(owner + ":check", plan.tasks)
                    self.assertIn(owner + ":scopedCheck", plan.tasks)
                self.assertIn(":verifySelectedKotlinSemanticCoverage", plan.tasks)
                self.assertEqual(321 if ":relation:intellij" in expected else 106, len(plan.caseIds))
                self.assertEqual(37 if ":relation:intellij" in expected else 12, len(plan.classes))
                self.assertNotIn(":installedProductTest", plan.tasks)
                self.assertNotIn(":distribution:cli:nativeCompile", plan.tasks)
                self.assertNotIn(":topology:build:check", plan.tasks)

    def test_query_delivery_keeps_app_server_generation_consumers(self):
        plan = self.plan("cli/src/main/js/query-delivery.mjs")
        self.assertIsInstance(plan, selection.Plan)
        self.assertEqual(
            {":app-server", ":cli", ":query:protocol", ":runtime:hosted"},
            set(plan.consumers),
        )
        self.assertIn(":app-server:check", plan.tasks)
        self.assertIn(":app-server:scopedCheck", plan.tasks)
        self.assertEqual(106, len(plan.caseIds))
        self.assertNotIn(":workspace:intellij-read:check", plan.tasks)

    def test_hosted_failure_fixture_keeps_intellij_encoder_consumer(self):
        plan = self.plan("cli/src/test/resources/hosted-read-failure-encodings.json")
        self.assertIsInstance(plan, selection.Plan)
        self.assertIn(":cli", plan.owners)
        self.assertIn(":workspace:intellij-read", plan.consumers)
        self.assertIn(":workspace:intellij-read:check", plan.tasks)
        self.assertIn(":workspace:intellij-read:scopedCheck", plan.tasks)
        self.assertNotIn(":app-server:check", plan.tasks)

    def test_file_consumers_do_not_expand_neighboring_inputs(self):
        for path in (
            "cli/src/main/js/query-delivery.mjs.backup",
            "cli/src/main/js/unrelated.mjs",
            "cli/src/test/resources/hosted-read-failure-encodings.json.backup",
            "cli/src/test/resources/unrelated.json",
        ):
            with self.subTest(path=path):
                plan = self.plan(path)
                self.assertIsInstance(plan, selection.Plan)
                self.assertNotIn(":app-server", plan.consumers)
                self.assertNotIn(":workspace:intellij-read", plan.consumers)

    def test_kotlin_inputs_keep_source_guards_without_unrelated_prose_tooling(self):
        plan = self.plan("query/contract/src/main/kotlin/QueryRows.kt")
        self.assertIsInstance(plan, selection.Plan)
        self.assertLessEqual(
            {":verifyKastArchitecture", ":verifyJsonContracts", ":verifyConfigurationIngress",
             ":verifyKnowledgeBase", ":verifyVerificationGraph"},
            set(plan.tasks),
        )
        self.assertTrue(
            {":verifyDocumentationGlossary", ":verifyDocumentationMarkdown", ":documentationToolingTest"}
            .isdisjoint(plan.tasks)
        )
        for path in ("docs/tooling/markdown/parse.ts", "docs/glossary.json", "README.md"):
            self.assertEqual(selection.Failure.UNKNOWN_OWNER, self.plan(path))

    def test_shared_fixture_keeps_direct_and_exported_fixture_consumers(self):
        plan = self.plan("workspace/contract/src/testFixtures/kotlin/WorkspaceFixture.kt")
        self.assertIsInstance(plan, selection.Plan)
        self.assertLessEqual(
            {":workspace:contract", ":query:protocol", ":runtime:hosted", ":app-server", ":cli"},
            set(plan.consumers),
        )
        self.assertNotIn(":kernel", plan.consumers)

    def test_both_endpoints_and_deleted_owner_contribute_obligations(self):
        plan = self.plan(
            "query/service/src/main/kotlin/Old.kt", "source/service/src/main/kotlin/New.kt"
        )
        self.assertIsInstance(plan, selection.Plan)
        self.assertEqual((":query:service", ":source:service"), plan.owners)
        self.assertIn(":source:service:check", plan.tasks)
        self.assertIn(":query:protocol:check", plan.tasks)
        for path in (
            "unknown/source.kt",
            "query/service/src/../build.gradle.kts",
            "query/service/new-input.json",
            "query/service/src/unregistered/kotlin/Unknown.kt",
            "query/service/testData/unknown.json",
        ):
            self.assertEqual(selection.Failure.UNKNOWN_OWNER, self.plan(path))

    def test_wrong_source_basis_omitted_class_case_or_task_rejects_receipt(self):
        plan = self.plan("query/contract/src/main/kotlin/QueryRows.kt")
        self.assertIsInstance(plan, selection.Plan)
        document = json.loads(json.dumps(asdict(plan)))
        self.assertEqual(plan, selection.admit(document, plan))
        for key in (
            "sourceRevision",
            "sourceTree",
            "baseRevision",
            "comparisonBasis",
            "graphDigest",
            "manifestDigest",
        ):
            self.assertEqual(
                selection.Failure.IDENTITY_MISMATCH,
                selection.admit(document | {key: "e" * 40}, plan),
            )
        for key in ("classes", "caseIds", "testResultIds", "tasks", "consumers", "owners"):
            for changed in (document[key][1:], document[key] + document[key][:1], []):
                self.assertEqual(
                    selection.Failure.IDENTITY_MISMATCH,
                    selection.admit(document | {key: changed}, plan),
                )
        self.assertEqual(
            selection.Failure.IDENTITY_MISMATCH,
            selection.admit(document | {"schemaVersion": True}, plan),
        )

    def test_changed_declared_graph_input_cannot_authorize_omissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / selection.GRAPH).parent.mkdir(parents=True)
            raw = json.loads((ROOT / selection.GRAPH).read_text())
            for item in raw["inputs"]:
                target = root / item["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / item["path"]).read_bytes())
            (root / selection.GRAPH).write_text(json.dumps(raw))
            self.assertIsInstance(selection.graph(root, MODULES), selection.ConsumerGraph)
            (root / "build.gradle.kts").write_text("// changed dependency or task input\n")
            self.assertEqual(selection.Failure.STALE_GRAPH, selection.graph(root, MODULES))


if __name__ == "__main__":
    unittest.main()
