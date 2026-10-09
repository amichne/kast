"""Actual TRACE requests must satisfy the current contract; no native qualification is inferred."""
import hashlib
import json
from pathlib import Path
import unittest

import jsonschema


ROOT = Path(__file__).resolve().parents[2]
SUITE = ROOT / 'experiments/host-observation/trace-completeness'


class TraceRequestContractTest(unittest.TestCase):
    def test_active_requests_bind_current_schema_and_original_grants(self):
        request_file = SUITE / 'requests-manifest.json'
        schema_file = ROOT / 'app-server/src/main/resources/io/github/amichne/kast/appserver/query/query_symbols.parameters.json'
        manifest = json.loads(request_file.read_text())
        validator = jsonschema.Draft202012Validator(json.loads(schema_file.read_text()))
        self.assertEqual(hashlib.sha256(request_file.read_bytes()).hexdigest(),
                         (SUITE / 'requests-manifest.sha256').read_text().strip())
        self.assertEqual(hashlib.sha256(schema_file.read_bytes()).hexdigest(), manifest['inputSchemaSha256'])
        self.assertEqual(hashlib.sha256((SUITE / 'manifest.json').read_bytes()).hexdigest(),
                         manifest['fixtureManifestSha256'])
        self.assertEqual(11, len(manifest['requests']))
        self.assertEqual(11, len({entry['label'] for entry in manifest['requests']}))
        questions = (
            ('A.TRACE', 'Attributes', 'attributes', 'CLASS', 'TRACE', 'src'),
            ('B.TRACE', 'FlowCollector', 'flow', 'CLASS', 'TRACE', 'src'),
            ('A.IMPLEMENTATIONS', 'Attributes', 'attributes', 'CLASS', 'IMPLEMENTATIONS', 'src'),
            ('B.IMPLEMENTATIONS', 'FlowCollector', 'flow', 'CLASS', 'IMPLEMENTATIONS', 'src'),
            ('B.OVERRIDES', None, 'flow', None, 'OVERRIDES', 'src'),
            ('A.library.CALLEES', 'take', 'attributes', 'FUNCTION', 'CALLEES', 'src/main/kotlin/fixture/attributes'),
            ('A.helper.CALLEES', 'helperRemoval', 'attributes', 'FUNCTION', 'CALLEES', 'src/main/kotlin/fixture/attributes'),
            ('B.named.CALLERS', 'consume', 'flow', 'FUNCTION', 'CALLERS', 'src/main/kotlin/fixture/flow'),
            ('N.stored.CALLEES', 'storedControl', 'shadow', 'FUNCTION', 'CALLEES', 'src/main/kotlin/fixture/shadow'),
            ('N.conditional.CALLEES', 'conditionalControl', 'shadow', 'FUNCTION', 'CALLEES', 'src/main/kotlin/fixture/shadow'),
            ('N.spring.IMPLEMENTATIONS', 'ArticleRepository', 'spring', 'CLASS', 'IMPLEMENTATIONS', 'src'),
        )
        grant = {'maxElapsedMs': 15000, 'maxWorkUnits': 100000,
                 'maxResults': 100, 'maxReturnedBytes': 524288}
        for entry, (label, name, package, kind, operation, directory) in zip(manifest['requests'], questions):
            with self.subTest(label=entry['label']):
                self.assertEqual(label, entry['label'])
                validator.validate(entry['payload'])
                request = entry['payload']['request']
                self.assertNotIn('completion', request)
                self.assertEqual('RUN', request['type'])
                self.assertEqual('RETAIN', request['retention'])
                self.assertEqual(grant, request['executionBudget'])
                source = ({'type': 'SEARCH_DECLARATIONS', 'declarationName': name,
                           'declarationKinds': [kind], 'scope': {
                               'type': 'PACKAGE', 'packageName': 'fixture.' + package,
                               'sourceSetNames': ['main', 'test'], 'includeSubpackages': False}}
                          if name else {'type': 'AT_LOCATION',
                                        'file': 'src/main/kotlin/fixture/flow/FlowCollector.kt', 'offset': 990})
                self.assertEqual(source, request['source'])
                step = {'type': 'TRACE' if operation == 'TRACE' else 'EXPAND_RELATION',
                        'expansionScope': {'type': 'SOURCE_DOMAIN', 'sourceSets': ['main', 'test'],
                                           'sourcePolicy': 'PRODUCTION_AND_TEST',
                                           'generatedSources': 'EXCLUDE', 'directory': directory}}
                if operation != 'TRACE':
                    step['relation'] = operation
                steps = [step]
                if operation in ('IMPLEMENTATIONS', 'OVERRIDES'):
                    steps.append({'type': 'DISTINCT_SYMBOLS'})
                self.assertEqual(steps, request['steps'])
                self.assertEqual({'type': 'SYMBOLS', 'fields': ['NAME', 'LOCATION', 'SIGNATURE']},
                                 request['output'])
