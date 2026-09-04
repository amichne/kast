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
        self.assertEqual("classlike", evidence.mismatch.registry.kind)
        self.assertEqual("constructor", evidence.mismatch.live.kind)
        self.assertEqual("diagnostic.ImplicitPrimaryTarget", evidence.mismatch.registry.qualified_identity)
        self.assertEqual(
            ["kind", "qualified_identity", "signature_kind", "identity"],
            evidence.mismatch.delta,
        )
        artifact = evidence.mismatch.document()
        self.assertEqual(
            "class-like",
            artifact["registryProjection"]["canonicalSignature"]["kind"],
        )
        self.assertEqual(
            "function",
            artifact["liveProjection"]["canonicalSignature"]["kind"],
        )

    def test_diagnostic_artifact_decodes_structured_canonical_signature(self) -> None:
        encoding = canonical_encoding(
            "canonical-signature-v1",
            "function",
            "diagnostic.sample",
            "receiver-present",
            "kotlin.String",
            "1",
            "diagnostic.Context",
            "2",
            "kotlin.Int",
            "kotlin.Long",
            "1",
        )

        signature = topology_identity_diagnostic.canonical_signature_document(encoding)

        self.assertEqual("function", signature["kind"])
        self.assertEqual(
            {"state": "present", "type": "kotlin.String"},
            signature["receiver"],
        )
        self.assertEqual(["diagnostic.Context"], signature["contextReceivers"])
        self.assertEqual(["kotlin.Int", "kotlin.Long"], signature["valueParameters"])
        self.assertEqual(1, signature["typeParameterCount"])
        self.assertEqual(encoding, signature["encoding"])

    def test_location_shared_by_different_symbol_kinds_is_classified_mechanically(self) -> None:
        mismatch = topology_identity_diagnostic.mismatch_from_attributes(
            topology_identity_diagnostic.decode_attributes(mismatch_attributes())
        )

        self.assertEqual(
            "semantic-location-key-collision",
            topology_identity_diagnostic.classify_mismatch(mismatch),
        )

    def test_projection_decision_table_is_total_for_retained_evidence(self) -> None:
        cases = (
            (
                {
                    "io.github.amichne.kast.live.symbol.kind": "classlike",
                    "io.github.amichne.kast.live.qualified.identity": "diagnostic.Other",
                    "io.github.amichne.kast.live.signature": classlike_signature(
                        "diagnostic.Other"
                    ),
                    "io.github.amichne.kast.qualified.identity.same": False,
                    "io.github.amichne.kast.projection.delta": [
                        "qualified_identity",
                        "identity",
                    ],
                },
                "target-mapping-or-qualified-identity-projection-defect",
            ),
            (
                {
                    "io.github.amichne.kast.registry.symbol.kind": "function",
                    "io.github.amichne.kast.live.symbol.kind": "function",
                    "io.github.amichne.kast.live.qualified.identity": "diagnostic.ImplicitPrimaryTarget",
                    "io.github.amichne.kast.registry.signature": function_signature(
                        "diagnostic.ImplicitPrimaryTarget",
                        "kotlin.String",
                    ),
                    "io.github.amichne.kast.live.signature": function_signature(
                        "diagnostic.ImplicitPrimaryTarget",
                        "kotlin.Int",
                    ),
                    "io.github.amichne.kast.qualified.identity.same": True,
                    "io.github.amichne.kast.projection.delta": [
                        "value_parameters",
                        "identity",
                    ],
                },
                "canonical-signature-projection-defect",
            ),
            (
                {
                    "io.github.amichne.kast.live.symbol.kind": "classlike",
                    "io.github.amichne.kast.live.qualified.identity": "diagnostic.ImplicitPrimaryTarget",
                    "io.github.amichne.kast.live.signature": classlike_signature(
                        "diagnostic.ImplicitPrimaryTarget"
                    ),
                    "io.github.amichne.kast.qualified.identity.same": True,
                    "io.github.amichne.kast.signature.same": True,
                    "io.github.amichne.kast.projection.delta": ["identity"],
                },
                "compiler-identity-encoding-defect",
            ),
        )
        for replacements, expected in cases:
            with self.subTest(expected=expected):
                attributes = mismatch_attributes()
                for key, value in replacements.items():
                    replace_attribute(attributes, key, value)
                mismatch = topology_identity_diagnostic.mismatch_from_attributes(
                    topology_identity_diagnostic.decode_attributes(attributes)
                )
                self.assertEqual(
                    expected,
                    topology_identity_diagnostic.classify_mismatch(mismatch),
                )

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
        "io.github.amichne.kast.registry.symbol.kind": "classlike",
        "io.github.amichne.kast.live.symbol.kind": "constructor",
        "io.github.amichne.kast.registry.qualified.identity": "diagnostic.ImplicitPrimaryTarget",
        "io.github.amichne.kast.live.qualified.identity": "diagnostic.ImplicitPrimaryTarget.<init>",
        "io.github.amichne.kast.registry.identity": "registry-identity",
        "io.github.amichne.kast.live.identity": "live-identity",
        "io.github.amichne.kast.qualified.identity.same": False,
        "io.github.amichne.kast.signature.same": False,
        "io.github.amichne.kast.registry.signature": classlike_signature(
            "diagnostic.ImplicitPrimaryTarget"
        ),
        "io.github.amichne.kast.live.signature": function_signature(
            "diagnostic.ImplicitPrimaryTarget.<init>"
        ),
        "io.github.amichne.kast.projection.delta": [
            "kind",
            "qualified_identity",
            "signature_kind",
            "identity",
        ],
        "io.github.amichne.kast.live.symbol.runtime.kind": "KaConstructorSymbol",
        "io.github.amichne.kast.psi.declaration.runtime.kind": "KtClass",
    }
    return [attribute(key, value) for key, value in values.items()]


def canonical_encoding(*fields: str) -> str:
    return "".join(f"{len(field.encode('utf-8'))}:{field}" for field in fields)


def classlike_signature(qualified_identity: str) -> str:
    return canonical_encoding(
        "canonical-signature-v1",
        "class-like",
        qualified_identity,
    )


def function_signature(
    qualified_identity: str,
    value_parameter: str | None = None,
) -> str:
    value_parameters = () if value_parameter is None else (value_parameter,)
    return canonical_encoding(
        "canonical-signature-v1",
        "function",
        qualified_identity,
        "receiver-absent",
        "0",
        str(len(value_parameters)),
        *value_parameters,
        "0",
    )


def replace_attribute(values: list[dict[str, object]], key: str, value: object) -> None:
    replacement = attribute(key, value)
    for index, candidate in enumerate(values):
        if candidate.get("key") == key:
            values[index] = replacement
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
