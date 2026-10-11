"""Independent JSON Schema admission for the authored outer delivery contract."""
import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator
from query_delivery_contract import SCHEMA, render_contract

ROOT = Path(__file__).resolve().parents[1]
STOPS = ['DELIVERED', 'CANCELLED', 'TIME_LIMIT', 'PAGE_LIMIT', 'BYTE_LIMIT',
         'DELIVERY_UNAVAILABLE', 'BUDGET_INCREASE_REQUIRED', 'MALFORMED_PAGE',
         'IDENTITY_MISMATCH', 'NON_ADVANCING']


class QueryDeliveryContractTest(unittest.TestCase):
    def setUp(self):
        self.schema = json.loads(SCHEMA.read_text())
        Draft202012Validator.check_schema(self.schema)
        self.validator = Draft202012Validator(self.schema)
        captured = json.loads((ROOT / 'experiments/host-observation/pi-fixtures/query-delivery-cases.json').read_text())
        self.initial = captured['cases']['complete']['result']['initial']

    def envelope(self, stop):
        return {'type': 'query_delivery', 'initial': self.initial, 'pages': [],
                'delivery': {'stop': stop, 'rpc_count': 1, 'request_bytes': 7, 'response_bytes': 11, 'result': None}}

    def test_every_stop_and_null_initial_alternative(self):
        for stop in STOPS:
            with self.subTest(stop=stop):
                value = self.envelope(stop)
                self.assertTrue(self.validator.is_valid(value))
                value['initial'] = None
                value['delivery']['original_outcome'] = 'complete'
                self.assertEqual(stop == 'BYTE_LIMIT', self.validator.is_valid(value))
        for original in ['complete', 'qualified', 'rejected_document', 'rejected']:
            value = self.envelope('BYTE_LIMIT')
            value['initial'] = None
            value['delivery']['original_outcome'] = original
            self.assertTrue(self.validator.is_valid(value))

    def test_unknown_extra_and_unbounded_shapes_are_excluded(self):
        mutations = [lambda v: v.update(invented=True),
                     lambda v: v['delivery'].update(invented=True),
                     lambda v: v['delivery'].update(stop='UNKNOWN'),
                     lambda v: v['delivery'].update(rpc_count=65),
                     lambda v: v['delivery'].update(response_bytes=2 ** 53),
                     lambda v: v['delivery'].update(original_outcome='complete'),
                     lambda v: v.update(initial=None),
                     lambda v: v.update(pages=[None])]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                value = copy.deepcopy(self.envelope('DELIVERED'))
                mutate(value)
                self.assertFalse(self.validator.is_valid(value))

    def test_projection_rejects_unimplemented_schema_assertions_and_foreign_refs(self):
        for change in [{'not': {}}, {'$ref': 'https://example.invalid/schema'}]:
            schema = copy.deepcopy(self.schema)
            schema['oneOf'][0]['properties']['initial'].update(change)
            with self.assertRaises(ValueError):
                render_contract(schema)

    def test_projection_rejects_unimplemented_schema_forms(self):
        for form in ['schema-valued-additional-properties', 'boolean-node', 'object-union', 'array-union', 'ref-assertion-sibling', 'oneof-assertion-sibling']:
            with self.subTest(form=form):
                schema = copy.deepcopy(self.schema)
                branch = schema['oneOf'][0]
                if form == 'schema-valued-additional-properties':
                    branch['additionalProperties'] = {'not': {}}
                elif form == 'boolean-node':
                    branch['properties']['initial'] = False
                elif form == 'object-union':
                    branch['properties']['initial'] = {
                        'type': ['object', 'null'], 'required': ['type'],
                        'properties': {'type': {'enum': ['complete']}}, 'additionalProperties': False,
                    }
                elif form == 'array-union':
                    branch['properties']['pages']['type'] = ['array', 'null']
                elif form == 'ref-assertion-sibling':
                    branch['properties']['initial']['const'] = None
                else:
                    schema['additionalProperties'] = False
                Draft202012Validator.check_schema(schema)
                with self.assertRaises(ValueError):
                    render_contract(schema)


if __name__ == '__main__':
    unittest.main()
