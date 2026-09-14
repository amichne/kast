#!/usr/bin/env python3
"""Independent admission and graph equivalence checks for installed budget evidence."""
from dataclasses import asdict, dataclass, field, replace
import unittest

from hosted_budget_read_regression import (BudgetSource, BudgetTraversal, ResultsBudget, WorkBudget,
    graph_records, independent_grant)
from hosted_source_read_regression import SymbolAnchor


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
class Node:
    id: int
    selector: str
    proof: int


@dataclass(frozen=True)
class Proof:
    id: int
    identity: str


@dataclass(frozen=True)
class Edge:
    source: int
    target: int
    occurrence: str
    coverage: str = 'exact-compiler-confirmed'


@dataclass(frozen=True)
class Graph:
    nodes: tuple[Node, ...]
    proofs: tuple[Proof, ...]
    edges: tuple[Edge, ...]


@dataclass(frozen=True)
class GraphResponse:
    graph: Graph


class HostedBudgetReadRegressionTest(unittest.TestCase):
    def test_one_axis_request_omits_other_dimensions_without_null_or_default_substitution(self):
        for request in (BudgetSource(SymbolAnchor('admitted-selector'), ResultsBudget(1)),
                        BudgetTraversal('admitted-selector', ResultsBudget(1))):
            self.assertEqual({'max_results': 1}, asdict(request)['execution_budget'])

    def test_grant_checker_rejects_lost_request_unexplained_clamp_and_cross_axis_override(self):
        work = Limit('caller', 100000, effective=1000, clamping=('operator_ceiling',))
        report = Grant(max_work_units=work)
        self.assertTrue(independent_grant(asdict(BudgetResponse(report)), WorkBudget()))
        for invalid in (replace(report, max_work_units=replace(work, requested=99999)),
                        replace(report, max_work_units=replace(work, clamping=())),
                        replace(report, max_results=Limit('caller', 100))):
            self.assertFalse(independent_grant(asdict(BudgetResponse(invalid)), WorkBudget()))

    def test_graph_equivalence_retains_order_occurrence_and_proof_despite_page_local_ids(self):
        first = Graph((Node(0, 'a', 0), Node(1, 'b', 1)), (Proof(0, 'proof-a'), Proof(1, 'proof-b')),
                      (Edge(0, 1, 'call-1'), Edge(0, 1, 'call-2')))
        renumbered = Graph((Node(7, 'b', 4), Node(3, 'a', 9)), (Proof(9, 'proof-a'), Proof(4, 'proof-b')),
                          (Edge(3, 7, 'call-1'), Edge(3, 7, 'call-2')))
        expected = graph_records(asdict(GraphResponse(first)))
        self.assertEqual(expected, graph_records(asdict(GraphResponse(renumbered))))
        for changed in (replace(renumbered, edges=tuple(reversed(renumbered.edges))),
                        replace(renumbered, edges=(Edge(3, 7, 'wrong-occurrence'),) + renumbered.edges[1:]),
                        replace(renumbered, proofs=(Proof(9, 'wrong-proof'), Proof(4, 'proof-b')))):
            self.assertNotEqual(expected, graph_records(asdict(GraphResponse(changed))))


if __name__ == '__main__':
    unittest.main()
