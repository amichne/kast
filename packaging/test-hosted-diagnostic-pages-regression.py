#!/usr/bin/env python3
"""Typed bounded-drain fixtures; no native host is used here."""
from dataclasses import asdict, dataclass
from types import SimpleNamespace
import unittest

from hosted_diagnostic_pages_regression import (
    DiagnosticDrainFailure, DiagnosticDrainRejected, DiagnosticDrained,
    DiagnosticRequest, drain_diagnostics, run_diagnostic_pages_regression,
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
