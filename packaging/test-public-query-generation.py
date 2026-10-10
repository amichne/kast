#!/usr/bin/env python3
"""Focused rejection checks for the authored public-tool generator."""

import copy
import importlib.util
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GENERATOR = ROOT / "packaging/generate-public-query.py"
SCHEMA = ROOT / "app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json"
module_spec = importlib.util.spec_from_file_location("public_query_generator", GENERATOR)
generator = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(generator)


class PublicQueryGenerationTest(unittest.TestCase):
    def setUp(self):
        self.authority = json.loads(SCHEMA.read_text())

    def test_adapter_versions_follow_the_same_authority(self):
        self.authority["contractVersion"] = 37
        generated = generator.render_tools(self.authority)
        for relative in ("copilot/extension.mjs", "pi/extension.ts"):
            self.assertIn("const PUBLIC_TOOL_CONTRACT_VERSION = 37;", generated[ROOT / relative])

    def test_adapter_failures_project_the_runtime_enum_owner(self):
        owner = (ROOT / "cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt").read_text()
        values = re.search(r"internal enum class ToolRpcFailure \{([^}]+)\}", owner).group(1)
        expected = [entry.strip() for entry in values.split(",") if entry.strip()]
        generated = generator.render_tools(self.authority)
        for relative in ("copilot/extension.mjs", "pi/extension.ts"):
            block = re.search(r"const TOOL_RPC_FAILURES = \[(.*?)\]", generated[ROOT / relative], re.DOTALL).group(1)
            self.assertEqual(expected, re.findall(r'"([A-Z_]+)"', block))

    def test_portable_delivery_is_one_embedded_source_for_single_file_adapters(self):
        owner = (ROOT / "cli/src/main/js/query-delivery.mjs").read_text().rstrip()
        generated = generator.render_tools(self.authority)
        for relative in ("copilot/extension.mjs", "pi/extension.ts"):
            source = generated[ROOT / relative]
            self.assertIn(owner, source)
            self.assertEqual(1, source.count("async function awaitQueryDelivery("))
            self.assertNotIn("from \"../", source)

    def test_word_discovery_is_a_closed_scoped_source(self):
        source = self.authority["$defs"]["TextSource"]
        self.assertEqual(["SEARCH_TEXT"], source["properties"]["type"]["enum"])
        self.assertEqual(["type", "word"], source["required"])
        self.assertFalse(source["additionalProperties"])
        self.assertEqual("^[A-Za-z_][A-Za-z0-9_]*$", source["properties"]["word"]["pattern"])
        self.assertEqual(256, source["properties"]["word"]["maxLength"])
        self.assertEqual("#/$defs/Scope", source["properties"]["scope"]["$ref"])
        references = [branch.get("$ref") for branch in self.authority["$defs"]["Source"]["anyOf"]]
        self.assertIn("#/$defs/TextSource", references)

    def test_generated_description_lines_bound_escaped_literal_width(self):
        description = '"\\' * 100
        self.authority["tools"][0]["description"] = description
        self.authority["supportTools"][0]["description"] = description
        generated = generator.render_tools(self.authority)
        literals = generator.kotlin_description_literals(description).split(' +\n            ')
        self.assertEqual(description, ''.join(json.loads(literal) for literal in literals))
        for identity in ("PublicToolIdentity", "SupportToolIdentity"):
            source = generated[ROOT / f"protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/{identity}.kt"]
            with self.subTest(identity=identity):
                self.assertTrue(all(len(line) <= 120 for line in source.splitlines()), source)

    def test_generated_discovery_documents_retain_one_owner(self):
        generated = generator.render_tools(self.authority)
        discovery = generated[ROOT / "app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt"]
        ingress = generated[ROOT / "app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDocuments.kt"]
        for name in ("DirectoryScope", "PackageScope", "LocationSource", "SearchSource", "TextSource", "AllSource"):
            declaration = f"internal data class PublicTool{name}("
            self.assertEqual(1, discovery.count(declaration))
            self.assertNotIn(declaration, ingress)
        self.assertLessEqual(len(discovery.splitlines()), 400)
        self.assertLessEqual(len(ingress.splitlines()), 400)

    def test_trace_documents_retain_one_generated_owner(self):
        generated = generator.render_tools(self.authority)
        trace = generated[ROOT / "app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolTrace.kt"]
        ingress = generated[ROOT / "app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDocuments.kt"]
        self.assertEqual(1, trace.count("internal data class PublicToolTrace("))
        self.assertNotIn("internal data class PublicToolTrace(", ingress)
        self.assertLessEqual(len(trace.splitlines()), 400)

    def test_requested_site_optional_collection_emits_formatter_stable_layout(self):
        generated = generator.render_tools(self.authority)
        source = generated[ROOT / "app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolImpactDocuments.kt"]
        self.assertIn("val requestedSites:\n        BoundedProtocolList<io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument>? =\n        null,", source)
        self.assertTrue(all(len(line) <= 120 for line in source.splitlines()), source)

    def test_evidence_cursor_reuses_its_canonical_scalar(self):
        generated = generator.render_tools(self.authority)
        source = generated[ROOT / "app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDocuments.kt"]
        self.assertIn('import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor', source)
        self.assertIn('@SerialName("evidence_cursor")', source)
        self.assertIn('val evidenceCursor: QueryEvidenceCursor? = null,', source)
        self.assertNotIn('class PublicToolEvidenceCursor', source)
        cursor = self.authority['$defs']['EvidenceCursor']
        self.assertEqual(['integer', 'null'], cursor['type'])
        self.assertEqual((0, 1000000), (cursor['minimum'], cursor['maximum']))

    def test_missing_and_invalid_control_defaults_reject(self):
        for replacement in (None, 0, 1001):
            with self.subTest(replacement=replacement):
                authority = copy.deepcopy(self.authority)
                depth = authority["$defs"]["WalkDepth"]
                if replacement is None:
                    del depth["default"]
                else:
                    depth["default"] = replacement
                with self.assertRaisesRegex(ValueError, "default"):
                    generator.validate_authority(authority)

    def test_conflicting_requiredness_rejects(self):
        self.authority["$defs"]["Walk"]["required"].append("maximumDepth")
        with self.assertRaisesRegex(ValueError, "permit omission"):
            generator.validate_authority(self.authority)

    def test_unresolved_reference_rejects(self):
        self.authority["$defs"]["Walk"]["properties"]["maximumDepth"]["$ref"] = "#/$defs/NoSuchDepth"
        with self.assertRaisesRegex(ValueError, "unresolved"):
            generator.validate_authority(self.authority)

    def test_unbound_advertised_tool_rejects(self):
        self.authority["tools"].append({"name": "unknown", "operation": "unknown.run", "schema": {}})
        with self.assertRaisesRegex(ValueError, "Unbound advertised tool"):
            generator.validate_authority(self.authority)

    def test_unbound_support_tool_rejects(self):
        self.authority["supportTools"][0]["binding"] = "UnknownRequest"
        with self.assertRaisesRegex(ValueError, "Unbound support tool"):
            generator.validate_authority(self.authority)


if __name__ == "__main__":
    unittest.main()
