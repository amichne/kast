"""Authored raw invocation counts, independent of native execution and timing thresholds."""
import copy
from dataclasses import asdict, dataclass, field
import unittest

import reproduce_semantic_queries as r
import test_semantic_comparison as fixtures


@dataclass(frozen=True)
class Root:
    type: str = field(default='root', init=False)


@dataclass(frozen=True)
class NotEntered:
    type: str = field(default='not-entered', init=False)


@dataclass(frozen=True)
class Entered:
    nanos: int
    type: str = field(default='entered', init=False)


@dataclass(frozen=True)
class NativeCallRow:
    call: str
    entered: int
    returned: int
    firstEntry: NotEntered | Entered
    parent: Root = field(default_factory=Root)
    cancelled: int = 0
    failed: int = 0
    unfinished: int = 0
    qualification: str = 'EXACT'
    durationNanos: int = 0


@dataclass(frozen=True)
class CallDocument:
    nativeCallVocabulary: list[str]
    nativeCalls: list[NativeCallRow]


class NativeCallAccountingTest(unittest.TestCase):
    def document(self, preparations, duration=0):
        return asdict(CallDocument(['CALLBACK_FACT_PREPARATION', 'REFERENCE_SEARCH'], [
            NativeCallRow('CALLBACK_FACT_PREPARATION', preparations, preparations,
                          Entered(0) if preparations else NotEntered(), durationNanos=duration),
            NativeCallRow('REFERENCE_SEARCH', 1, 1, Entered(0)),
        ]))

    def trial(self, preparations, duration=0):
        template = fixtures.SemanticComparisonTest().trial()
        call = template.calls[0]
        receipt = copy.deepcopy(call.diagnostics[0])
        receipt.update(self.document(preparations, duration), schemaVersion=7)
        observed = r.ReplayCall(call.action, call.request, call.process, call.response,
                                [receipt], call.phases, call.correlation)
        return r.finish_trial('exact-source', 0, False, [observed], 240, 200)

    def test_fewer_declared_calls_qualify_with_identical_semantics_and_unchanged_legacy_counters(self):
        result = r.compare_trials(self.trial(2), self.trial(1), same_artifact=False)
        self.assertIs(r.ComparisonState.EQUIVALENT, result.type)
        self.assertTrue(result.lessWork)
        self.assertEqual(-1, result.deltas['counters']['NATIVE_CALLS/CALLBACK_FACT_PREPARATION'])
        self.assertEqual(0, result.deltas['counters']['NATIVE_CALLS/REFERENCE_SEARCH'])
        self.assertEqual(0, result.deltas['counters']['COMPILER_REFINEMENTS/NONE'])

    def test_timing_changes_do_not_establish_fewer_calls(self):
        result = r.compare_trials(self.trial(1, 1000), self.trial(1, 1), same_artifact=False)
        self.assertIs(r.ComparisonState.EQUIVALENT, result.type)
        self.assertFalse(result.lessWork)

    def test_missing_old_or_changed_declared_measurements_are_not_zero(self):
        new, old = self.trial(0), fixtures.SemanticComparisonTest().trial()
        self.assertIs(r.ComparisonState.MISSING_EVIDENCE, r.compare_trials(old, new, False).type)
        receipt = copy.deepcopy(new.calls[0].diagnostics[0])
        receipt.pop('nativeCalls')
        self.assertEqual(r.UnavailableNativeCallCounts(r.NativeCallMeasurementFailure.MISSING),
                         r.native_call_counts(receipt))
        receipt = self.document(0)
        receipt['nativeCalls'].pop()
        self.assertEqual(r.UnavailableNativeCallCounts(r.NativeCallMeasurementFailure.INVALID),
                         r.native_call_counts(receipt))

    def test_inexact_interrupted_and_inconsistent_counts_fail_closed(self):
        cases = [
            ({'qualification': 'SATURATED'}, r.NativeCallMeasurementFailure.SATURATED),
            ({'returned': 0, 'unfinished': 1}, r.NativeCallMeasurementFailure.UNFINISHED),
            ({'returned': 0, 'failed': 1}, r.NativeCallMeasurementFailure.INTERRUPTED),
            ({'returned': 0, 'cancelled': 1}, r.NativeCallMeasurementFailure.INTERRUPTED),
            ({'returned': 2}, r.NativeCallMeasurementFailure.INVALID),
            ({'entered': True}, r.NativeCallMeasurementFailure.INVALID),
            ({'qualification': 'UNKNOWN'}, r.NativeCallMeasurementFailure.INVALID),
        ]
        for change, reason in cases:
            with self.subTest(change=change):
                receipt = self.document(1)
                receipt['nativeCalls'][0].update(change)
                self.assertEqual(r.UnavailableNativeCallCounts(reason), r.native_call_counts(receipt))

    def test_duplicate_rows_and_undeclared_parent_cannot_double_charge_or_hide_work(self):
        receipt = self.document(1)
        receipt['nativeCalls'].append(copy.deepcopy(receipt['nativeCalls'][0]))
        self.assertEqual(r.UnavailableNativeCallCounts(r.NativeCallMeasurementFailure.INVALID),
                         r.native_call_counts(receipt))
        receipt = self.document(1)
        receipt['nativeCalls'][0]['parent'] = {'type': 'call', 'call': 'UNDECLARED'}
        self.assertEqual(r.UnavailableNativeCallCounts(r.NativeCallMeasurementFailure.INVALID),
                         r.native_call_counts(receipt))


if __name__ == '__main__':
    unittest.main()
