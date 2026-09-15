#!/usr/bin/env python3
"""Independent request-shape and occurrence-oracle checks for the call fixture."""
from dataclasses import asdict, dataclass
import unittest

from hosted_kotlin_call_regression import KotlinCallRead, KotlinCallSearch, _site, _has_scoped_unsupported


@dataclass(frozen=True)
class Range:
    startInclusive: int = 1
    endExclusive: int = 6


@dataclass(frozen=True)
class Sample:
    file: str = '/fixture/Calls.kt'
    range: Range = Range()


@dataclass(frozen=True)
class OmissionEvidence:
    reason: str = 'UNSUPPORTED_ITEM'
    samples: tuple[Sample, ...] = (Sample(),)


class KotlinCallRegressionTest(unittest.TestCase):
    def test_scoped_omission_uses_authored_enum_spelling(self):
        self.assertTrue(_has_scoped_unsupported([asdict(OmissionEvidence())]))
        self.assertFalse(_has_scoped_unsupported([asdict(OmissionEvidence(samples=()))]))
        self.assertFalse(_has_scoped_unsupported([asdict(OmissionEvidence(reason='unsupported-item'))]))

    def test_fixed_request_shapes(self):
        self.assertEqual({'function_name': 'outer', 'name_match': 'exact',
                          'scope': {'package_name': 'fixture.calls', 'include_subpackages': False,
                                    'source_set_names': ('main',)}}, asdict(KotlinCallSearch('outer')))
        self.assertEqual({'exactSelector': 'issued-ref', 'relation': 'callees', 'limit': 100,
                          'position': {'type': 'start'}}, asdict(KotlinCallRead('issued-ref')))

    def test_occurrence_offsets_distinguish_repeated_calls(self):
        source = 'fun fetch() = 1\nfun repeat() = client.fetch() + client.fetch()'
        self.assertEqual((0, 38, 43), _site(source, 'client.fetch() + client.fetch()', 'fetch', 'fun fetch'))
        self.assertEqual((0, 55, 60), _site(source, '+ client.fetch()', 'fetch', 'fun fetch'))


if __name__ == '__main__':
    unittest.main()
