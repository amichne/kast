#!/usr/bin/env python3
"""Independent admission checks for installed query and source budgets."""
from dataclasses import asdict, dataclass, field, replace
import unittest

from hosted_budget_read_regression import BudgetSource, ResultsBudget, WorkBudget, independent_grant, progress_advances
from hosted_source_read_regression import SymbolAnchor
from query_name_request import name_query, walk_query


@dataclass(frozen=True)
class Limit:
    selection: str = 'configured_default'
    requested: int | None = None
    configuredDefault: int = 100
    operatorCeiling: int = 1000
    effective: int = 100
    clamping: tuple[str, ...] = ()


@dataclass(frozen=True)
class Grant:
    max_elapsed_ms: Limit = field(default_factory=Limit)
    max_work_units: Limit = field(default_factory=Limit)
    max_results: Limit = field(default_factory=Limit)
    max_returned_bytes: Limit = field(default_factory=Limit)


@dataclass(frozen=True)
class BudgetResponse:
    execution_budget: Grant


@dataclass(frozen=True)
class Progress:
    checkpointSequence: int
    totalReads: int
    totalEdges: int
    maximumDepthReached: int


class HostedBudgetReadRegressionTest(unittest.TestCase):
    def test_cumulative_walk_progress_allows_retained_equality_and_rejects_regressions(self):
        before = Progress(1, 2, 3, 1)
        self.assertTrue(progress_advances(asdict(before), asdict(before)))
        self.assertTrue(progress_advances(asdict(before), asdict(Progress(2, 3, 5, 2))))
        for after in (replace(before, checkpointSequence=0), replace(before, totalReads=1),
                      replace(before, totalEdges=2), replace(before, maximumDepthReached=0)):
            self.assertFalse(progress_advances(asdict(before), asdict(after)))

    def test_one_axis_request_omits_other_dimensions_without_null_or_default_substitution(self):
        for request in (BudgetSource(SymbolAnchor('admitted-selector'), ResultsBudget(1)),
                        walk_query('admitted-selector', budget=ResultsBudget(1))):
            payload = asdict(request)
            budget = payload.get('execution_budget', payload.get('request', {}).get('execution_budget'))
            self.assertEqual({'max_results': 1}, budget)
        self.assertEqual({'max_results': 1},
                         asdict(name_query('pageItem00', budget=ResultsBudget(1)))['request']['execution_budget'])

    def test_declaration_search_retains_unrestricted_kind_and_exact_name_contract(self):
        self.assertEqual({'request': {'action': 'run',
                          'source': {'type': 'search_declarations', 'declaration_name': 'pageItem00',
                                     'name_match': 'exact', 'scope': None, 'declaration_kinds': None},
                          'steps': None, 'output': {'type': 'symbols', 'fields': ('name', 'location', 'signature')},
                          'execution_budget': {'max_work_units': 100000}}},
                         asdict(name_query('pageItem00', budget=WorkBudget())))

    def test_grant_checker_rejects_lost_request_unexplained_clamp_and_cross_axis_override(self):
        work = Limit('caller', 100000, effective=1000, clamping=('operator_ceiling',))
        report = Grant(max_work_units=work)
        self.assertTrue(independent_grant(asdict(BudgetResponse(report)), WorkBudget()))
        for invalid in (replace(report, max_work_units=replace(work, requested=99999)),
                        replace(report, max_work_units=replace(work, clamping=())),
                        replace(report, max_results=Limit('caller', 100))):
            self.assertFalse(independent_grant(asdict(BudgetResponse(invalid)), WorkBudget()))


if __name__ == '__main__':
    unittest.main()
