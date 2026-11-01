#!/usr/bin/env python3
"""Focused rejection checks for the authored public-tool generator."""

import copy
import hashlib
import importlib.util
import json
import re
import unittest
from unittest.mock import patch
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

    def test_detail_selector_reuses_typed_authority_in_both_catalogs(self):
        outputs = generator.render_tools(self.authority)
        for name in ('query_symbols.parameters.json', 'query_symbols.common.parameters.json'):
            definitions = json.loads(outputs[generator.RESOURCES / name])['$defs']
            detail = definitions['ReadResultAction']['properties']['detail']
            selector = definitions[detail['anyOf'][0]['$ref'].split('/')[-1]]
            self.assertEqual({'result', 'section', 'target', 'ordinal', 'kind', 'sha256', 'offset'}, set(selector['required']))
            result = definitions[selector['properties']['result']['$ref'].split('/')[-1]]
            self.assertEqual(46, result['minLength'])
            self.assertEqual('^[0-9a-f]{64}$', selector['properties']['sha256']['pattern'])

    def test_common_catalog_is_one_closed_generated_query_projection(self):
        outputs = generator.render_tools(self.authority)
        schema = json.loads(outputs[generator.RESOURCES / 'query_symbols.common.parameters.json'])
        definitions = schema['$defs']
        self.assertEqual(['SEARCH_DECLARATIONS', 'AT_LOCATION', 'SYMBOL_REFS'],
                         [definitions.get(value.get('$ref', '').split('/')[-1], value)['properties']['type']['enum'][0]
                          for value in definitions['Source']['anyOf']])
        self.assertEqual(['EXPAND_RELATION'], [definitions[value['$ref'].split('/')[-1]]['properties']['type']['enum'][0]
                         for value in definitions['Step']['anyOf']])
        self.assertEqual({'maxResults', 'maxReturnedBytes'}, set(definitions['ExecutionBudget']['properties']))
        self.assertEqual('Query minimum: 4096 UTF-8 bytes.',
                         definitions['ExecutionBudget']['properties']['maxReturnedBytes']['description'])
        self.assertEqual(['RUNNER_CONTROLLED', None],
                         definitions['Run']['properties']['executionMode']['anyOf'][0]['enum'])
        self.assertNotIn('ImpactDeclarationReference', definitions)
        self.assertNotIn('AllSource', definitions)
        self.assertLessEqual(len(json.dumps(schema, separators=(',', ':')).encode()), 10_000)
        catalog = json.loads(outputs[generator.RESOURCES / 'tools.common.app-server.json'])
        self.assertEqual(1, sum(tool['name'] == 'query_symbols' for tool in catalog['tools']))
        self.assertEqual(schema, catalog['tools'][0]['inputSchema'])

    def test_single_use_leaf_substitution_preserves_exact_constraints_and_shared_nodes(self):
        source = {
            'type': 'object', 'properties': {'one': {'$ref': '#/$defs/Only'},
                'first': {'$ref': '#/$defs/Shared'}, 'second': {'$ref': '#/$defs/Shared'}},
            'required': ['one'], 'additionalProperties': False,
            '$defs': {'Only': {'type': 'string', 'minLength': 2, 'maxLength': 4, 'pattern': '^[a-z]+$'},
                      'Shared': {'type': 'integer', 'minimum': 1, 'maximum': 3}},
        }
        expected = copy.deepcopy(source)
        expected['properties']['one'] = {'type': 'string', 'minLength': 2, 'maxLength': 4, 'pattern': '^[a-z]+$'}
        del expected['$defs']['Only']
        self.assertEqual(expected, generator.inline_single_use_schema_leaves(source))
        self.assertIn('Only', source['$defs'], 'The generator must not mutate its authority')

    def test_single_use_leaf_substitution_rejects_sibling_constraints(self):
        source = {'properties': {'value': {'$ref': '#/$defs/Only', 'maxLength': 1}},
                  '$defs': {'Only': {'type': 'string', 'minLength': 2}}}
        with self.assertRaisesRegex(ValueError, 'sibling constraints'):
            generator.inline_single_use_schema_leaves(source)

    def test_common_single_use_leaves_preserve_the_entire_expanded_contract(self):
        for strict in (False, True):
            with self.subTest(strict=strict):
                with patch.object(generator, 'inline_single_use_schema_leaves', side_effect=copy.deepcopy):
                    before = generator.common_query_schema(self.authority, strict=strict, minimum_bytes=4096)
                after = generator.common_query_schema(self.authority, strict=strict, minimum_bytes=4096)
                def expand(node, definitions):
                    if isinstance(node, list):
                        return [expand(child, definitions) for child in node]
                    if isinstance(node, dict):
                        if '$ref' in node:
                            self.assertEqual({'$ref'}, set(node), 'No sibling constraints can disappear')
                            return expand(definitions[node['$ref'][8:]], definitions)
                        return {key: expand(child, definitions) for key, child in node.items() if key != '$defs'}
                    return node
                self.assertEqual(expand(before, before['$defs']), expand(after, after['$defs']))
                self.assertLess(len(json.dumps(after)), len(json.dumps(before)))
                self.assertLessEqual(len(json.dumps(after, separators=(',', ':')).encode()), 10_000)
                self.assertEqual({'Directory', 'Package', 'ExactRefs', 'RowCursor', 'EvidenceCursor', 'OutputRef',
                                  'Location', 'ImpactWorkspaceDomain', 'ImpactRetainedDomain', 'ImpactSourceDomain',
                                  'SourceRef', 'FullText', 'NoText', 'TextWindow', 'TextExcerpt', 'ExecutionMode', 'Section'},
                                 before['$defs'].keys() - after['$defs'].keys())

    def test_focused_topics_are_bounded_and_use_authored_examples(self):
        outputs = generator.render_tools(self.authority)
        root = ROOT / 'agent-tools/references/query-topics'
        for name in ('declarations', 'exact-selection', 'relations', 'trace', 'retained-evidence', 'source-reads'):
            topic = outputs[root / (name + '.md')]
            self.assertLessEqual(len(topic.encode('utf-8')), 4096, name)
            self.assertIn('Legal next actions:', topic)
            self.assertIn('Accepted references:', topic)
            self.assertIn('Returned capabilities:', topic)
            for request in re.findall(r'```json\n(.*?)\n```', topic, re.DOTALL):
                self.assertIn(json.loads(request), [example['value'] for example in self.authority['tools'][0]['examples'].values()])
        index = outputs[ROOT / 'agent-tools/references/query-topics.md']
        self.assertLessEqual(len(index.encode('utf-8')), 4096)
        self.assertIn('advanced/', index)

    def test_focused_topics_follow_canonical_enums_and_defaults(self):
        self.authority['$defs']['SearchSource']['properties']['nameMatch']['enum'] = ['EXACT', 'NEW_MATCH', None]
        self.authority['$defs']['SearchSource']['properties']['nameMatch']['default'] = 'NEW_MATCH'
        topic = generator.render_tools(self.authority)[ROOT / 'agent-tools/references/query-topics/declarations.md']
        self.assertIn('NEW_MATCH', topic)
        self.assertIn('default: `"NEW_MATCH"`', topic)

    def test_onboarding_reference_preserves_schema_and_interface_query(self):
        generated = generator.render_tools(self.authority)
        root = ROOT / "agent-tools/references"
        self.assertTrue(root / "query-schema.json" in generated, "missing generated onboarding schema")
        self.assertEqual(
            json.loads(generated[generator.RESOURCES / "query_symbols.parameters.json"]),
            json.loads(generated[root / "query-schema.json"]),
        )
        examples = json.loads(generated[root / "query-examples.json"])
        interface = next(example for example in examples if example["name"] == "scopedInterfaceDeclaration")
        source = interface["arguments"]["request"]["source"]
        self.assertEqual(["CLASS"], source["declarationKinds"])
        self.assertEqual("OrderRepository", source["declarationName"])
        self.assertEqual("PACKAGE", source["scope"]["type"])
        self.assertIn("INTERFACE", self.authority["tools"][0]["invalidExamples"]["interfaceIsNotAKind"]["value"]["request"]["source"]["declarationKinds"])

    def test_advanced_reference_enum_values_follow_the_authority(self):
        self.authority["$defs"]["SearchSource"]["properties"]["nameMatch"]["enum"] = ["EXACT", "NEW_MATCH", None]
        generated = generator.render_tools(self.authority)
        schema = json.loads(generated[ROOT / "agent-tools/references/query-schema.json"])
        self.assertEqual(["EXACT", "NEW_MATCH", None], schema["$defs"]["SearchSource"]["properties"]["nameMatch"]["enum"])
        brief = generated[ROOT / "agent-tools/references/onboarding.md"]
        self.assertIn("Use `find`", brief)
        self.assertNotIn("EXACT|FUZZY", brief)

    def test_adapter_versions_follow_the_same_authority(self):
        self.authority["contractVersion"] = 37
        generated = generator.render_tools(self.authority)
        for relative in ("copilot/extension.mjs", "pi/extension.ts"):
            self.assertIn("const PUBLIC_TOOL_CONTRACT_VERSION = 37;", generated[ROOT / relative])

    def test_workflow_description_projects_to_registry_and_provider_catalogs(self):
        # Mutate metadata independently of parameter and response schemas.
        tool = self.authority["tools"][0]
        description = 'Workflow "scope" \\ unchanged identities and qualified stops.'
        tool["description"] = description
        generated = generator.render_tools(self.authority)
        registry = generated[ROOT / "protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt"]
        self.assertIn(generator.kotlin_description_literals(description), registry)
        app_server = json.loads(generated[generator.RESOURCES / "tools.app-server.json"])
        projected = next(item for item in app_server["tools"] if item["name"] == tool["name"])
        self.assertEqual(description, projected["description"])
        responses = json.loads(generated[generator.RESOURCES / "tools.responses.json"])
        projected = next(item for item in responses if item["name"] == "kast_" + tool["name"])
        self.assertEqual(description, projected["description"])
        self.assertEqual(
            json.loads((generator.RESOURCES / "query_symbols.parameters.json").read_text()),
            json.loads(generated[generator.RESOURCES / "query_symbols.parameters.json"]),
        )

    def test_query_parameters_share_impact_identity_and_range_schemas(self):
        generated = generator.render_tools(self.authority)
        parameters = json.loads(generated[generator.RESOURCES / "query_symbols.parameters.json"])
        # Contract 17 adds required terminal variants and their output applicability to contract 16.
        self.assertLessEqual(len(json.dumps(parameters, separators=(",", ":"))), 52_000)
        definitions = parameters["$defs"]
        self.assertIn('Query minimum: 4096 UTF-8 bytes.',
                      definitions['ExecutionBudget']['properties']['maxReturnedBytes']['description'])
        self.assertEqual(
            {"$ref": "#/$defs/ImpactDeclarationReference"},
            definitions["ImpactDeclaration"]["properties"]["declaration"],
        )
        self.assertEqual(
            {"$ref": "#/$defs/ImpactSourceRange"},
            definitions["ImpactProducer"]["properties"]["anchor"],
        )

    def test_pi_exposure_projects_the_authored_loading_policy(self):
        self.authority["tools"][0]["deferLoading"] = True
        generated = generator.render_tools(self.authority)
        source = generated[ROOT / "pi/extension.ts"]
        match = re.search(r"const DEFERRED_TOOL_NAMES: readonly string\[\] = (\[.*?\]);", source, re.DOTALL)
        self.assertIsNotNone(match)
        self.assertEqual(
            [tool["name"] for tool in self.authority["tools"] if tool["deferLoading"]],
            json.loads(match.group(1)),
        )

    def test_shared_refs_preserve_the_expanded_query_contract(self):
        # Expanded schemas pin constraints, defaults, budget guidance, and required fields.
        expected = {
            "query_symbols.parameters.json": "b0079a6fa5dbe4c165d669cb6ccb16e07c40002a3cb7ea90a565f84c0474f07f",
            "query_symbols.openai-parameters.json": "ae25a7e63910db67d123da1b9cc23e91ed853d4f88dfa5574ca37360ec1581aa",
        }
        generated = generator.render_tools(self.authority)
        for name, digest in expected.items():
            schema = json.loads(generated[generator.RESOURCES / name])
            definitions = schema["$defs"]
            # Optional detail syntax and three source-backed descriptions form the intentional delta.
            # Remove that exact delta to keep the prior complete grammar pinned byte-for-byte.
            definitions['ReadResultAction']['properties'].pop('detail')
            if 'openai' in name:
                definitions['ReadResultAction']['required'].remove('detail')
            definitions['ReferenceSource']['properties']['symbolRefs']['items']['description'] = (
                'Opaque exact-symbol reference returned by Kast. Copy the token verbatim; '
                'do not construct, edit, or substitute a candidate reference.'
            )
            definitions['EvidenceCursor']['description'] = (
                "Optional offset in the retained result's evidence. It is independent of the row presentation cursor "
                "and performs no semantic execution. Omission or null preserves the existing evidence presentation."
            )
            definitions['ExecutionBudget']['properties']['maxReturnedBytes']['description'] = (
                'Encoded response allowance in bytes. Omission uses KAST_READ_SEMANTIC_RETURNED_BYTES.'
            )

            def expand(node):
                if isinstance(node, list):
                    return [expand(value) for value in node]
                if not isinstance(node, dict):
                    return node
                if "$ref" in node:
                    return expand(definitions[node["$ref"][len("#/$defs/"):]])
                return {key: expand(value) for key, value in node.items() if key != "$defs"}

            encoded = json.dumps(expand(schema), sort_keys=True, separators=(",", ":"))
            self.assertEqual(digest, hashlib.sha256(encoded.encode()).hexdigest(), name)

    def test_unknown_loading_policy_rejects(self):
        for policy in (None, "deferred", 1):
            with self.subTest(policy=policy):
                self.authority["tools"][0]["deferLoading"] = policy
                with self.assertRaisesRegex(ValueError, "loading policy"):
                    generator.validate_authority(self.authority)

    def test_terminal_grammar_is_closed_in_both_schema_profiles(self):
        generated = generator.render_tools(self.authority)
        for name in ("query_symbols.parameters.json", "query_symbols.openai-parameters.json"):
            definitions = json.loads(generated[generator.RESOURCES / name])["$defs"]
            run = definitions["RunAction"]
            self.assertIn("terminalStep", run["required"])
            self.assertEqual({"$ref": "#/$defs/TerminalStep"}, run["properties"]["terminalStep"])
            self.assertNotIn("output", run["properties"])
            self.assertEqual(
                [{"$ref": "#/$defs/Emit"}, {"$ref": "#/$defs/Trace"}],
                definitions["TerminalStep"]["anyOf"],
            )
            self.assertNotIn({"$ref": "#/$defs/Trace"}, definitions["Step"]["anyOf"])
            for variant, keys in (("Emit", {"type", "output"}), ("Trace", {"type", "output", "expansionScope"})):
                node = definitions[variant]
                self.assertEqual(keys, set(node["properties"]))
                self.assertIs(False, node["additionalProperties"])
                self.assertIn("type", node["required"])
                self.assertEqual([variant.upper()], node["properties"]["type"]["enum"])
                if "openai" in name:
                    self.assertEqual(keys, set(node["required"]))
            self.assertEqual([{"$ref": "#/$defs/SymbolsOutput"}], definitions["TraceOutput"]["anyOf"])

    def test_row_compatibility_reference_projects_authored_facts(self):
        self.authority["rowCompatibility"][0]["result"] = "Preserved symbols"
        generated = generator.render_tools(self.authority)
        table = generated[ROOT / "agent-tools/references/query-row-compatibility.md"]
        self.assertIn("| WHERE, DISTINCT_SYMBOLS | Symbols | Preserved symbols |", table)

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

    def test_invocation_session_has_one_embedded_owner(self):
        owner = (ROOT / "cli/src/main/js/query-session.mjs").read_text().rstrip()
        generated = generator.render_tools(self.authority)
        for relative in ("copilot/extension.mjs", "pi/extension.ts"):
            source = generated[ROOT / relative]
            self.assertIn(owner, source)
            self.assertEqual(1, source.count("function createQuerySession("))

    def test_importable_presentation_reuses_the_embedded_owner_exactly(self):
        generated = generator.render_tools(self.authority)
        owner = generated[ROOT / "cli/src/main/js/query-delivery.mjs"].rstrip()
        companion = generated[ROOT / "cli/src/main/js/query-presentation.mjs"]
        self.assertEqual(
            '// Generated importable presentation; owned by cli/src/main/js/query-delivery.mjs.\n' +
            owner + '\nexport { queryPresentation };\n', companion)

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
        for name in ("Trace", "Emit"):
            declaration = f"internal data class PublicTool{name}("
            self.assertEqual(1, trace.count(declaration))
            self.assertNotIn(declaration, ingress)
        self.assertLessEqual(len(trace.splitlines()), 400)

    def test_source_documents_and_line_defaults_retain_their_schema_owner(self):
        self.authority['$defs']['SourceTextWindow']['properties']['beforeLines']['default'] = 7
        generated = generator.render_tools(self.authority)
        source = generated[ROOT / 'app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolSourceDocuments.kt']
        ingress = generated[ROOT / 'app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDocuments.kt']
        self.assertIn('const val sourceBeforeLines = 7', ingress)
        for name in ('ReadSourceAction', 'SourceTextComplete', 'SourceTextNone', 'SourceTextWindow', 'SourceTextExcerpt'):
            declaration = 'internal data ' + ('object' if name in ('SourceTextComplete', 'SourceTextNone') else 'class') + ' PublicTool' + name
            self.assertEqual(1, source.count(declaration))
            self.assertNotIn(declaration, ingress)
        self.assertLessEqual(len(source.splitlines()), 400)

    def test_requested_site_optional_collection_emits_formatter_stable_layout(self):
        generated = generator.render_tools(self.authority)
        source = generated[ROOT / "app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolImpactDocuments.kt"]
        self.assertIn("val requestedSites:\n        BoundedProtocolList<io.github.amichne.kast.protocol.contract.ImpactRequestedValueSiteDocument>? =\n        null,", source)
        self.assertNotIn("BoundedProtocolList<io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument>", source)
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
