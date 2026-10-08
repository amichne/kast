"""Checks independent receipt validation; synthetic receipts are not native proof."""
import copy
from pathlib import Path
import unittest

import immutable_callback_oracle as fixture
import immutable_try_qualification as qualification


class ImmutableTryQualificationTest(unittest.TestCase):
    root = Path(__file__).parent / 'immutable-callback-fixture'

    def case(self, name):
        return next(case for case in fixture.CASES if case.name == name)

    def transfers(self, case):
        result = []
        for branch in qualification.authored_branches(self.root, case):
            evidence = branch.evidence()
            result.append({'kind': 'BRANCH_ALTERNATIVE', 'evidence': evidence,
                           'source': {'enclosing': {'file': branch.enclosing.file},
                                      'range': {'start': branch.branch.range.startInclusive + 1,
                                                'end': branch.branch.range.endExclusive - 1}},
                           'target': {'enclosing': {'file': branch.enclosing.file},
                                      'range': evidence['try_range']}})
        return result

    def test_all_authored_try_and_catch_branches_require_exact_normal_witnesses(self):
        for name in ('direct-try', 'local-try', 'nested-try', 'factory-try'):
            with self.subTest(name=name):
                case = self.case(name)
                transfers = self.transfers(case)
                qualification.assert_try_witnesses(self.root, case, [{'relation_observations': transfers}])
                mutations = []
                missing = copy.deepcopy(transfers)
                missing.pop()
                mutations.append(missing)
                for field, value in (('condition', 'ABRUPT_COMPLETION'), ('alternative', {'type': 'CATCH_BODY', 'index': 7}),
                                     ('type', 'DIRECT')):
                    changed = copy.deepcopy(transfers)
                    changed[0]['evidence'][field] = value
                    mutations.append(changed)
                for changed in mutations:
                    with self.assertRaises(AssertionError):
                        qualification.assert_try_witnesses(self.root, case, [{'relation_observations': changed}])
                outside = copy.deepcopy(transfers)
                outside[0]['source']['range']['start'] = 0
                with self.assertRaises(AssertionError):
                    qualification.assert_try_witnesses(self.root, case, [{'relation_observations': outside}])

    def test_finally_and_abrupt_are_source_authored_rejections(self):
        for name in ('try-finally-control', 'factory-finally-control'):
            self.assertEqual(fixture.Rejected(fixture.Boundary.TRY_FINALLY), self.case(name).expected)
        self.assertEqual(fixture.Rejected(fixture.Boundary.TRY_ABRUPT), self.case('try-abrupt-control').expected)


if __name__ == '__main__': unittest.main()
