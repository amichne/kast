#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("topology_identity_diagnostic.py")
SPEC = importlib.util.spec_from_file_location("topology_identity_diagnostic", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
topology_identity_diagnostic = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = topology_identity_diagnostic
SPEC.loader.exec_module(topology_identity_diagnostic)


class TopologyIdentityDiagnosticTest(unittest.TestCase):
    def test_isolated_fixture_installs_the_tracked_repository_wrapper(self) -> None:
        expected = Path(__file__).parents[1] / "gradle" / "wrapper" / "gradle-wrapper.jar"
        with tempfile.TemporaryDirectory() as raw:
            workspace = Path(raw)

            topology_identity_diagnostic.install_gradle_wrapper(workspace)

            installed = workspace / "gradle" / "wrapper" / "gradle-wrapper.jar"
            self.assertEqual(expected.read_bytes(), installed.read_bytes())

    def test_hostile_workspace_idea_is_bounded_and_detects_any_rewrite(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            workspace = Path(raw) / "workspace"
            workspace.mkdir()

            evidence = topology_identity_diagnostic.install_hostile_workspace_idea(
                workspace
            )

            document = evidence.document()
            self.assertEqual("ignored-generated-state", document["authority"])
            self.assertEqual(
                {
                    ".idea/compiler.xml": "invalid-jps-bytecode-target",
                    ".idea/gradle.xml": "wrong-linked-project-root-and-gradle-jvm",
                    ".idea/kotlinc.xml": "unavailable-kotlin-jps-plugin-version",
                    ".idea/misc.xml": "invalid-language-level-and-project-jdk",
                    ".idea/workspace.xml": "disabled-external-system-auto-reload",
                },
                {item["path"]: item["risk"] for item in document["files"]},
            )
            evidence.assert_unchanged()
            (workspace / ".idea" / "workspace.xml").write_text(
                "rewritten\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                topology_identity_diagnostic.DiagnosticEvidenceError,
                "changed",
            ):
                evidence.assert_unchanged()

    def test_semantic_project_store_must_be_unique_and_outside_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            runtime_socket_directory = root / "runtime-sockets"
            workspace = root / "workspace"
            project_store = (
                runtime_socket_directory
                / "sidecar.sock.state"
                / "intellij-project-test"
            )
            idea = project_store / ".idea"
            idea.mkdir(parents=True)
            workspace.mkdir()
            (idea / "gradle.xml").write_text("private\n", encoding="utf-8")

            observation = topology_identity_diagnostic.observe_semantic_project_store(
                runtime_socket_directory,
                workspace,
            )

            self.assertEqual("runtime-owned", observation["authority"])
            self.assertTrue(observation["fresh"])
            self.assertTrue(observation["workspaceDisjoint"])
            self.assertEqual(["gradle.xml"], observation["generatedFiles"])
            self.assertEqual("all", observation["autoReload"])

    def test_successful_run_removes_stale_mismatch_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            report = Path(raw) / "topology-identity-diagnostic.json"
            mismatch = report.with_name("topology-identity-mismatch.json")
            mismatch.write_text("stale\n", encoding="utf-8")

            topology_identity_diagnostic.write_report(
                report,
                {"status": "complete", "topologyOutcome": "published"},
            )

            self.assertTrue(report.is_file())
            self.assertFalse(mismatch.exists())

    def test_otlp_trace_recovers_candidate_count_and_complete_mismatch(self) -> None:
        document = {
            "resourceSpans": [
                {
                    "scopeSpans": [
                        {
                            "spans": [
                                {
                                    "name": "kast.topology.candidates.enumeration",
                                    "attributes": [attribute("io.github.amichne.kast.file.count", 10)],
                                },
                                {
                                    "name": "kast.topology.extraction",
                                    "events": [
                                        {
                                            "name": "kast.topology.identity.mismatch",
                                            "attributes": mismatch_attributes(),
                                        }
                                    ],
                                },
                            ]
                        }
                    ]
                }
            ]
        }
        with tempfile.TemporaryDirectory() as raw:
            trace = Path(raw) / "traces.jsonl"
            trace.write_text(json.dumps(document) + "\n", encoding="utf-8")

            evidence = topology_identity_diagnostic.read_diagnostic_trace(trace)

        self.assertEqual(10, evidence.candidate_count)
        self.assertEqual("computed", evidence.mismatch.cache_disposition)
        self.assertEqual("reference_target", evidence.mismatch.stage)
        self.assertEqual("probes/ImplicitPrimaryConstructor.kt", evidence.mismatch.source_file)
        self.assertEqual((42, 63), evidence.mismatch.source_occurrence)
        self.assertEqual((0, 39), evidence.mismatch.target_declaration)
        self.assertEqual("role_mismatch", evidence.mismatch.reason)
        self.assertEqual("role_mismatch", evidence.mismatch.document()["reason"])
        self.assertNotIn("registryProjection", evidence.mismatch.document())

    def test_binding_reason_is_closed_and_independent_of_rendering(self) -> None:
        for reason in topology_identity_diagnostic.BindingFailure:
            attrs = topology_identity_diagnostic.decode_attributes(mismatch_attributes())
            attrs["io.github.amichne.kast.topology.binding.reason"] = reason.value
            evidence = topology_identity_diagnostic.mismatch_from_attributes(attrs)
            self.assertEqual(reason.value, topology_identity_diagnostic.classify_mismatch(evidence))
        for invalid in ("", "invented-reason", True, 3):
            attrs["io.github.amichne.kast.topology.binding.reason"] = invalid
            with self.assertRaises(topology_identity_diagnostic.DiagnosticEvidenceError):
                topology_identity_diagnostic.mismatch_from_attributes(attrs)

    def test_cold_run_rejects_reused_or_duplicate_mismatch_evidence(self) -> None:
        reused = mismatch_attributes()
        replace_attribute(
            reused,
            "io.github.amichne.kast.topology.cache.disposition",
            "reused",
        )
        with tempfile.TemporaryDirectory() as raw:
            trace = Path(raw) / "traces.jsonl"
            trace.write_text(
                json.dumps(otlp_document(10, [reused])) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                topology_identity_diagnostic.DiagnosticEvidenceError,
                "computed",
            ):
                topology_identity_diagnostic.read_diagnostic_trace(trace)

            trace.write_text(
                json.dumps(
                    otlp_document(
                        10,
                        [mismatch_attributes(), mismatch_attributes()],
                    )
                )
                + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                topology_identity_diagnostic.DiagnosticEvidenceError,
                "exactly one",
            ):
                topology_identity_diagnostic.read_diagnostic_trace(trace)

    def test_fixture_keeps_each_requested_probe_independently_localizable(self) -> None:
        fixture = Path(__file__).parents[1] / "fixtures" / "topology-identity-workspace"
        sources = sorted(fixture.glob("src/main/kotlin/diagnostic/identity/*.kt"))
        self.assertEqual(
            {
                "ClassReference.kt",
                "DirectOverride.kt",
                "ExplicitPrimaryConstructor.kt",
                "ExtensionFunction.kt",
                "ExtensionProperty.kt",
                "GenericFunction.kt",
                "GenericMemberSubstitution.kt",
                "GenericOverrideSubstitution.kt",
                "ImplicitPrimaryConstructor.kt",
                "PropertyReference.kt",
                "SecondaryConstructor.kt",
                "TypeAliasReference.kt",
            },
            {source.name for source in sources},
        )
        self.assertEqual(
            {
                "class-reference",
                "direct-override",
                "explicit-primary-constructor",
                "extension-function",
                "extension-property",
                "generic-function",
                "implicit-primary-constructor",
                "property-reference",
                "secondary-constructor",
                "type-alias-reference",
            },
            {probe.name for probe in topology_identity_diagnostic.PROBE_EXPECTATIONS},
        )


def otlp_document(candidate_count: int, mismatches: list[list[dict[str, object]]]) -> dict:
    return {
        "resourceSpans": [
            {
                "scopeSpans": [
                    {
                        "spans": [
                            {
                                "name": "kast.topology.candidates.enumeration",
                                "attributes": [
                                    attribute("io.github.amichne.kast.file.count", candidate_count)
                                ],
                            },
                            {
                                "name": "kast.topology.extraction",
                                "events": [
                                    {
                                        "name": "kast.topology.identity.mismatch",
                                        "attributes": values,
                                    }
                                    for values in mismatches
                                ],
                            },
                        ]
                    }
                ]
            }
        ]
    }


def mismatch_attributes() -> list[dict[str, object]]:
    values: dict[str, object] = {
        "io.github.amichne.kast.topology.identity.stage": "reference_target",
        "io.github.amichne.kast.topology.cache.disposition": "computed",
        "io.github.amichne.kast.source.file": "probes/ImplicitPrimaryConstructor.kt",
        "io.github.amichne.kast.source.occurrence.start": 42,
        "io.github.amichne.kast.source.occurrence.end": 63,
        "io.github.amichne.kast.target.file": "probes/ImplicitPrimaryConstructor.kt",
        "io.github.amichne.kast.target.declaration.start": 0,
        "io.github.amichne.kast.target.declaration.end": 39,
        "io.github.amichne.kast.topology.binding.reason": "role_mismatch",
    }
    return [attribute(key, value) for key, value in values.items()]


def replace_attribute(values: list[dict[str, object]], key: str, value: object) -> None:
    for index, candidate in enumerate(values):
        if candidate.get("key") == key:
            values[index] = attribute(key, value)
            return
    raise AssertionError(f"attribute not found: {key}")


def attribute(key: str, value: object) -> dict[str, object]:
    if isinstance(value, bool):
        encoded: dict[str, object] = {"boolValue": value}
    elif isinstance(value, int):
        encoded = {"intValue": str(value)}
    elif isinstance(value, list):
        encoded = {
            "arrayValue": {
                "values": [{"stringValue": item} for item in value],
            }
        }
    else:
        encoded = {"stringValue": value}
    return {"key": key, "value": encoded}


if __name__ == "__main__":
    unittest.main()
