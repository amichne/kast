#!/usr/bin/env python3
"""Typed bounded-drain fixtures; no native host is used here."""
from dataclasses import asdict, dataclass
from types import SimpleNamespace
import unittest
from hosted_read_transport import ReadTransportRejected

from hosted_diagnostic_pages_regression import (
    DiagnosticDrainFailure, DiagnosticDrainRejected, DiagnosticDrained,
    DiagnosticRequest, drain_diagnostics, run_diagnostic_pages_regression, heavy_file_checks, independent_budget_checks,
)


@dataclass(frozen=True)
class Enumerating:
    type: str = 'enumerating'


@dataclass(frozen=True)
class Exhausted:
    totalFiles: int = 3
    type: str = 'exhausted'


@dataclass(frozen=True)
class Progress:
    stage: str = 'enumeration'
    stop: str = 'enumeration_file_limit'
    inventory: Enumerating | Exhausted = Enumerating()
    analyzedFiles: tuple[str, ...] = ()
    knownDiagnosticCount: int = 0


@dataclass(frozen=True)
class Qualification:
    continuation: str = 'diagnostic:v1:first'


@dataclass(frozen=True)
class Page:
    progress: Progress = Progress()
    qualification: Qualification | None = Qualification()
    live: str = 'fixture-authority'
    status: str = 'qualified'
    diagnostics: tuple = ()


def document(page):
    import json
    return json.loads(json.dumps(asdict(page)))


@dataclass(frozen=True)
class Range:
    startInclusive: int
    endExclusive: int


@dataclass(frozen=True)
class Location:
    range: Range
    file: str = '/workspace/src/main/kotlin/ReadDiagnosticPages.kt'


@dataclass(frozen=True)
class Diagnostic:
    location: Location
    severity: str = 'warning'
    code: str = 'DEPRECATION'
    message: str = 'Diagnostic paging fixture'


class DiagnosticPagesTest(unittest.TestCase):
    def replay(self, *pages):
        pending = iter(document(page) for page in pages)
        return SimpleNamespace(live='fixture-authority', surface='provider', transport=SimpleNamespace(
            invoke=lambda *_: next(pending), validate=lambda *_: None))

    def test_legacy_scope_cap_reaches_domain_before_new_fields(self):
        @dataclass(frozen=True)
        class Refused:
            status: str = 'rejected'
            reason: str = 'scope-limit-exceeded'
        requests, rows = [], []
        def invoke(_surface, _tool, request):
            requests.append(request)
            return asdict(Refused())
        replay = SimpleNamespace(surface='provider', transport=SimpleNamespace(
            invoke=invoke, validate=lambda *_: None), record=lambda *row: rows.append(row))
        run_diagnostic_pages_regression(replay)
        self.assertEqual(1, len(requests))
        self.assertEqual({'relative_path', 'max_diagnostics'}, set(requests[0]))
        self.assertTrue(rows[0][2]['legacyRequestReachedScopeCap'])
        self.assertFalse(rows[0][2]['boundedDrainCompleted'])

    def test_heavy_file_preserves_repeated_messages_and_distinct_occurrences(self):
        files = ('/workspace/src/main/kotlin/ReadDiagnosticPages.kt',)
        records = tuple(Diagnostic(Location(Range(index, index + 1))) for index in range(3))
        output = Progress('output', 'output_pending', Exhausted(1), files, 3)
        finished = Progress('finished', 'finished', Exhausted(1), files, 3)
        high = Page(finished, None, status='complete', diagnostics=records)
        first = Page(output, Qualification('first'), diagnostics=records[:1])
        second = Page(output, Qualification('second'), diagnostics=records[1:2])
        last = Page(finished, None, status='complete', diagnostics=records[2:])
        checks = heavy_file_checks(self.replay(high, first, second, second, last, last))
        self.assertTrue(all(checks.values()), checks)

    def test_independent_limits_preserve_reports_and_name_actual_stops(self):
        @dataclass(frozen=True)
        class Limit:
            requested: int
            effective: int
        @dataclass(frozen=True)
        class Report:
            max_work_units: Limit = Limit(1, 1)
            max_elapsed_ms: Limit = Limit(1, 1)
            max_returned_bytes: Limit = Limit(2048, 2048)
        @dataclass(frozen=True)
        class Refused:
            reason: str
            execution_budget: Report = Report()
            status: str = 'rejected'
            next_action: str = 'increase_execution_budget'
        @dataclass(frozen=True)
        class BudgetProgress:
            stop: str = 'enumeration_work_limit'
            execution_budget: Report = Report()
        first = Page(progress=BudgetProgress())
        last = Page(Progress('finished', 'finished', Exhausted(1), ('A.kt',)), None, status='complete')
        replay = self.replay(first, Refused('enumeration-work-grant-too-small'), last,
            Refused('execution-time-grant-too-small'), Refused('output-grant-too-small'))
        checks = independent_budget_checks(replay)
        self.assertTrue(all(checks.values()), checks)
        self.assertIn('timeOneObserved_execution-time-grant-too-small', checks)
        self.assertIn('bytes2048Observed_output-grant-too-small', checks)

    def test_low_axis_transport_failure_is_recorded_without_claiming_budget_proof(self):
        first = document(Page(progress=Progress(stop='enumeration_work_limit')))
        pending = iter((first, document(Page(status='rejected')),
            document(Page(Progress('finished', 'finished', Exhausted(1), ('A.kt',)), None, status='complete'))))
        def invoke(*_):
            try:
                return next(pending)
            except StopIteration:
                raise ReadTransportRejected('READ_CLI_TOOL_REJECTED') from None
        replay = SimpleNamespace(live='fixture-authority', surface='cli',
            transport=SimpleNamespace(invoke=invoke, validate=lambda *_: None))
        checks = independent_budget_checks(replay)
        self.assertFalse(checks['timeOneReported'])
        self.assertFalse(checks['bytes2048Reported'])
        self.assertTrue(checks['timeOneTransportObserved_READ_CLI_TOOL_REJECTED'])
        self.assertTrue(checks['bytes2048TransportObserved_READ_CLI_TOOL_REJECTED'])

    def test_enumeration_then_exact_complete_coverage(self):
        last = Page(Progress('finished', 'finished', Exhausted(), ('A.kt', 'B.kt', 'C.kt')), None, status='complete')
        result = drain_diagnostics(self.replay(last), DiagnosticRequest(), document(Page()))
        self.assertIsInstance(result, DiagnosticDrained)
        self.assertEqual(2, len(result.pages))

    def test_partial_empty_page_is_not_clean(self):
        partial = Page(status='complete')
        result = drain_diagnostics(self.replay(), DiagnosticRequest(), document(partial))
        self.assertEqual(DiagnosticDrainRejected(DiagnosticDrainFailure.COVERAGE), result)

    def test_unchanged_cursor_is_finite_failure(self):
        result = drain_diagnostics(self.replay(Page()), DiagnosticRequest(), document(Page()))
        self.assertEqual(DiagnosticDrainRejected(DiagnosticDrainFailure.CHECKPOINT), result)

    def test_basis_movement_rejects(self):
        result = drain_diagnostics(self.replay(), DiagnosticRequest(), document(Page(live='moved')))
        self.assertEqual(DiagnosticDrainRejected(DiagnosticDrainFailure.AUTHORITY), result)

    def test_exact_total_cannot_understate_analyzed_files(self):
        last = Page(Progress('finished', 'finished', Exhausted(2), ('A.kt', 'B.kt', 'C.kt')), None, status='complete')
        result = drain_diagnostics(self.replay(), DiagnosticRequest(), document(last))
        self.assertEqual(DiagnosticDrainRejected(DiagnosticDrainFailure.COVERAGE), result)


if __name__ == '__main__':
    unittest.main()
