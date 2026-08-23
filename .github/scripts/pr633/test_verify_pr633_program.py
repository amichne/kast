#!/usr/bin/env python3
"""Focused standard-library tests for the PR 633 program verifier."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_pr633_program as verifier


class VerifyPr633ProgramTest(unittest.TestCase):
    def clean_slate_graph(self) -> dict:
        root = Path(__file__).resolve().parents[3]
        return json.loads((root / "kast-clean-slate-task-graph.json").read_text(encoding="utf-8"))

    def test_installed_artifact_is_schema_valid_and_stops_at_gate_070(self) -> None:
        root = Path(__file__).resolve().parents[3]

        result = verifier.verify_artifact(root)

        self.assertEqual("GATE-070", result["gates"][-1])
        self.assertNotIn("GATE-080", result["gates"])

        program = json.loads((root / "gradle/pr633/kast-pr633-program.json").read_text())
        encoded = json.dumps(program, sort_keys=True)
        self.assertNotIn(":protocol:registry:generateOperationRegistry", encoded)
        self.assertEqual(3, encoded.count(":protocol:wire:generateOperationRegistry"))

    def test_installed_authorities_include_clean_slate_topology_order(self) -> None:
        root = Path(__file__).resolve().parents[3]

        result = verifier.verify_authorities(root)

        self.assertEqual(5, result["authorities"])

    def test_clean_slate_graph_rejects_dependency_and_semantic_drift(self) -> None:
        graph = self.clean_slate_graph()
        verifier.verify_clean_slate_graph(graph)

        for task_id in ("KCS-012", "KCS-015", "KCS-021"):
            with self.subTest(task_id=task_id):
                changed = deepcopy(graph)
                task = next(item for item in changed["tasks"] if item["id"] == task_id)
                task["dependsOn"].remove("KCS-023")
                with self.assertRaises(verifier.VerificationFailure):
                    verifier.verify_clean_slate_graph(changed)

        changed = deepcopy(graph)
        changed["semantics"]["traversal.run"]["implicitBuild"] = True
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_clean_slate_graph(changed)

    def test_clean_slate_graph_rejects_dependency_drift_for_every_task(self) -> None:
        graph = self.clean_slate_graph()

        for task in graph["tasks"]:
            if not task["dependsOn"]:
                continue
            with self.subTest(task_id=task["id"]):
                changed = deepcopy(graph)
                changed_task = next(item for item in changed["tasks"] if item["id"] == task["id"])
                changed_task["dependsOn"] = changed_task["dependsOn"][1:]
                with self.assertRaises(verifier.VerificationFailure):
                    verifier.verify_clean_slate_graph(changed)

    def test_clean_slate_graph_rejects_duplicate_unknown_and_cyclic_tasks(self) -> None:
        graph = self.clean_slate_graph()

        duplicate = deepcopy(graph)
        duplicate["tasks"][1]["id"] = "KCS-001"
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_clean_slate_graph(duplicate)

        unknown = deepcopy(graph)
        unknown["tasks"][0]["dependsOn"] = ["KCS-999"]
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_clean_slate_graph(unknown)

        cyclic = deepcopy(graph)
        cyclic["tasks"][0]["dependsOn"] = ["KCS-022"]
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_clean_slate_graph(cyclic)

    def test_marked_plan_operation_order_is_exact(self) -> None:
        root = Path(__file__).resolve().parents[3]
        plan = (root / "kast-clean-slate-plan.md").read_text(encoding="utf-8")
        self.assertEqual(verifier.OPERATION_IDS, verifier.marked_plan_operations(plan))

        reordered = plan.replace("workspace.inspect\ntopology.build", "topology.build\nworkspace.inspect")
        self.assertNotEqual(verifier.OPERATION_IDS, verifier.marked_plan_operations(reordered))

    def test_only_topology_build_is_admitted_in_pr633(self) -> None:
        verifier.verify_topology_operation_ids(["workspace.inspect", "topology.build"])

        for forbidden in verifier.FORBIDDEN_FOLLOW_ON_OPERATION_IDS:
            with self.subTest(forbidden=forbidden):
                with self.assertRaises(verifier.VerificationFailure):
                    verifier.verify_topology_operation_ids(["topology.build", forbidden])

    def test_ci_artifact_rejects_another_head(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "gate.json"
            report.write_text(
                json.dumps(
                    {
                        "gateId": "GATE-060",
                        "headSha": "a" * 40,
                        "status": "passed",
                        "dependencyEvidence": {"GATE-050": "digest"},
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaises(verifier.VerificationFailure):
                verifier.verify_ci_artifact(report, "b" * 40)

    def test_schema_validator_rejects_duplicate_operation_ids(self) -> None:
        schema = {
            "type": "array",
            "uniqueItems": True,
            "items": {"type": "string"},
        }

        with self.assertRaises(verifier.VerificationFailure):
            verifier.validate_schema(["topology.build", "topology.build"], schema, schema)

    def test_lifecycle_schema_rejects_obsolete_report_shape(self) -> None:
        root = Path(__file__).resolve().parents[3]
        schema = json.loads(
            (root / "gradle/pr633/schemas/topology-installed-lifecycle.schema.json").read_text(
                encoding="utf-8",
            )
        )
        obsolete = {
            "schemaVersion": 1,
            "kind": "kast-pr633-installed-topology-lifecycle",
            "staleTopology": {},
        }

        with self.assertRaises(verifier.VerificationFailure):
            verifier.validate_schema(obsolete, schema, schema)

    def test_program_rejects_dangling_repository_source(self) -> None:
        root = Path(__file__).resolve().parents[3]
        program = json.loads((root / "gradle/pr633/kast-pr633-program.json").read_text())
        source = next(item for item in program["sourceLedger"] if item["revision"] == "repository-path")
        source["location"] = "missing/program-authority.txt"

        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_repository_sources(program, root)

    def test_program_rejects_unknown_invariant_enforcer(self) -> None:
        root = Path(__file__).resolve().parents[3]
        program = json.loads((root / "gradle/pr633/kast-pr633-program.json").read_text())
        program["invariants"][0]["enforcedBy"] = ["missingVerificationTask"]

        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_invariant_enforcers(program, root)

    def test_ci_reuse_rejects_a_second_heavy_gate_invocation(self) -> None:
        root = Path(__file__).resolve().parents[3]
        workflow = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        verifier.verify_ci_reuse(workflow)

        duplicated = workflow.replace(
            "      - name: Materialize exact-head GATE-070 evidence",
            "      - run: packaging/pr633-final-gate.sh\n"
            "      - name: Materialize exact-head GATE-070 evidence",
        )
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_ci_reuse(duplicated)

    def test_gate_check_bindings_are_exact(self) -> None:
        root = Path(__file__).resolve().parents[3]
        program = json.loads((root / "gradle/pr633/kast-pr633-program.json").read_text())
        verifier.verify_gate_check_bindings(program, root)

        changed = deepcopy(program)
        changed["gates"][0]["checkIds"] = ["unbound-check"]
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_gate_check_bindings(changed, root)

        changed = deepcopy(program)
        changed["gates"][0]["checks"].append("unbound description")
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_gate_check_bindings(changed, root)

        changed = deepcopy(program)
        gate_060 = next(gate for gate in changed["gates"] if gate["id"] == "GATE-060")
        git_diff_index = gate_060["checkIds"].index("git-diff-check")
        gate_060["checks"][git_diff_index] = "git diff --check"
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_gate_check_bindings(changed, root)

    def test_gate_authorities_reject_removed_heavy_proofs(self) -> None:
        root = Path(__file__).resolve().parents[3]
        topology = (root / "build-logic/src/main/kotlin/conventions/pr633-topology.gradle.kts").read_text()
        delivery = (root / "build-logic/src/main/kotlin/conventions/pr633-delivery.gradle.kts").read_text()
        topology_block = topology.split("val topologyInstalledProductAcceptance", 1)[1].split("reportFile.set", 1)[0]
        topology_contract_block = topology.split("val topologyContractAcceptance", 1)[1].split(
            "reportFile.set", 1,
        )[0]
        delivery_block = delivery.split("val pr633MergeCandidateAcceptance", 1)[1].split("reportFile.set", 1)[0]

        verifier.verify_gate_authority_tokens("GATE-040", topology_contract_block)
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_gate_authority_tokens(
                "GATE-030", topology_block.replace("verifyTopologyTraversalNoK2,", ""),
            )
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_gate_authority_tokens(
                "GATE-040", topology_contract_block.replace("verifyGraphIndexInternal,", ""),
            )
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_gate_authority_tokens(
                "GATE-060", delivery_block.replace("allSubprojectTests,", ""),
            )

    def test_terminal_gate_rejects_hidden_heavy_dependency(self) -> None:
        root = Path(__file__).resolve().parents[3]
        delivery = (root / "build-logic/src/main/kotlin/conventions/pr633-delivery.gradle.kts").read_text()
        terminal = delivery.split('tasks.register<WritePr633GateEvidenceTask>("pr633ExactHeadCiAcceptance")', 1)[1]
        terminal = terminal.split("reportFile.set", 1)[0]
        verifier.verify_gate_authority_tokens("GATE-070", terminal)

        hidden_heavy_edge = terminal.replace(
            'dependsOn("verifyPr633ProgramArtifacts")',
            'dependsOn("verifyPr633ProgramArtifacts", pr633MergeCandidateAcceptance)',
        )
        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_gate_authority_tokens("GATE-070", hidden_heavy_edge)

    def test_topology_api_zero_budget_inventory_is_exact(self) -> None:
        root = Path(__file__).resolve().parents[3]
        source = (root / "build-logic/src/main/kotlin/conventions/pr633-topology.gradle.kts").read_text()
        verifier.verify_topology_api_zero_budget_policy(source)

        with self.assertRaises(verifier.VerificationFailure):
            verifier.verify_topology_api_zero_budget_policy(
                source.replace('            "TopologyPath",\n', ""),
            )

    def test_no_rust_product_rejects_each_retained_surface(self) -> None:
        verifier.verify_no_rust_paths(["kernel/src/main/kotlin/Kernel.kt"])

        for path in (
            "Cargo.toml", "nested/Cargo.lock", "cli-rs/src/main.rs", "bin/kastctl",
            "target/release/kast", "release/kast-macos.tar.gz", "bin/kastctl.exe",
        ):
            with self.subTest(path=path):
                with self.assertRaises(verifier.VerificationFailure):
                    verifier.verify_no_rust_paths([path])


if __name__ == "__main__":
    unittest.main()
