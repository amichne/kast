"""Validate the independent expected documents also asserted against Kotlin serialization."""
import copy
import json
from pathlib import Path
import unittest

import jsonschema

ROOT = Path(__file__).resolve().parents[2]


class SourceScopeFailureSchemaTest(unittest.TestCase):
    def setUp(self):
        schema = json.loads((ROOT / 'protocol/contract/src/main/resources/ide-hosted/hosted-query.schema.json').read_text())
        jsonschema.Draft202012Validator.check_schema(schema)
        self.validator = jsonschema.Draft202012Validator(schema)
        self.documents = [json.loads(path.read_text()) for path in sorted(
            (ROOT / 'workspace/intellij-read/src/test/resources/named-source-scope').glob('*.json'))]

    def test_all_closed_mapping_shapes_validate(self):
        self.assertEqual(7, len(self.documents))
        for document in self.documents:
            self.validator.validate(document)

    def test_missing_identity_unknown_reason_and_unbounded_identity_reject(self):
        for document in self.documents:
            bad = copy.deepcopy(document)
            del bad['detail']['module']
            self.assertFalse(self.validator.is_valid(bad))
            bad = copy.deepcopy(document)
            bad['detail']['module']['value'] = 'x' * 513
            self.assertFalse(self.validator.is_valid(bad))
            bad = copy.deepcopy(document)
            bad['detail']['extra'] = 'unbounded platform data'
            self.assertFalse(self.validator.is_valid(bad))
            if 'reason' in document['detail']:
                bad = copy.deepcopy(document)
                bad['detail']['reason'] = 'UNKNOWN_REASON'
                self.assertFalse(self.validator.is_valid(bad))


if __name__ == '__main__':
    unittest.main()
