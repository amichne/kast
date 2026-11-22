"""Authored fixture intent stays source-text evidence; it never mints live handles."""
from dataclasses import asdict
import json
from pathlib import Path
import unittest

import fixture_intent as intent

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


class FixtureIntentTest(unittest.TestCase):
    def test_authored_fixture_manifest_records_text_intent_and_never_live_handles(self):
        source = REPO / 'fixtures/semantic-fixture/value-flow/RepresentationImpactFixture.kt'
        self.assertIsInstance(intent.verify_text(source), intent.SourceTextVerified)
        expected = json.loads((HERE / 'fixture-intent.expected.json').read_text())
        self.assertEqual(expected, json.loads(json.dumps(asdict(intent.manifest(source)))))
        self.assertEqual(4, len(expected['seedAnchors']))
        self.assertEqual(15, len(expected['namedUses']))
        self.assertEqual('SOURCE_TEXT_INTENT', expected['type'])
        self.assertNotIn('exactSymbol', json.dumps(expected))


if __name__ == '__main__':
    unittest.main()
