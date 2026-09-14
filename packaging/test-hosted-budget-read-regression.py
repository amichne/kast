#!/usr/bin/env python3
"""Independent admission and graph equivalence checks for installed budget evidence."""
from dataclasses import asdict, dataclass, field, replace
import unittest

from hosted_budget_read_regression import (BudgetDeclarationSearch, BudgetSource, BudgetTraversal, ResultsBudget, WorkBudget,
    graph_records, independent_grant, progress_advances, traversal_checkpoint)
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


@dataclass(frozen=True)
class UpstreamCheckpoint:
    token: str = 'traversal:v1:issued-checkpoint'
    type: str = 'upstream'


@dataclass(frozen=True)
class RetainedCheckpoint:
    token: str = 'traversal-output:v1:issued-checkpoint'
    upstream: str = 'complete'
    type: str = 'retained_output'


@dataclass(frozen=True)
class Qualification:
    checkpoint: UpstreamCheckpoint | RetainedCheckpoint
    continuation: str
    type: str = 'resumable'
    next_action: str = 'resume'


@dataclass(frozen=True)
class QualifiedPage:
    qualification: Qualification
    status: str = 'qualified'


@dataclass(frozen=True)
class Progress:
    checkpointSequence: int
    totalReads: int
    totalEdges: int
    maximumDepthReached: int


class HostedBudgetReadRegressionTest(unittest.TestCase):
    def test_checkpoint_reader_accepts_upstream_and_retained_without_conflating_them(self):
        for checkpoint in (UpstreamCheckpoint(), RetainedCheckpoint(), RetainedCheckpoint(upstream='resumable')):
            qualification = Qualification(checkpoint, checkpoint.token)
            self.assertEqual(asdict(checkpoint), traversal_checkpoint(asdict(QualifiedPage(qualification))))
            self.assertIsNone(traversal_checkpoint(asdict(QualifiedPage(replace(qualification, continuation='foreign')))))
            self.assertIsNone(traversal_checkpoint(asdict(QualifiedPage(replace(qualification, next_action='unknown')))))

    def test_cumulative_progress_allows_retained_equality_and_rejects_each_regression(self):
        before = Progress(1, 2, 3, 1)
        self.assertTrue(progress_advances(asdict(before), asdict(before)))
        self.assertTrue(progress_advances(asdict(before), asdict(Progress(2, 3, 5, 2))))
        for after in (replace(before, checkpointSequence=0), replace(before, totalReads=1),
                      replace(before, totalEdges=2), replace(before, maximumDepthReached=0)):
            self.assertFalse(progress_advances(asdict(before), asdict(after)))

    def test_one_axis_request_omits_other_dimensions_without_null_or_default_substitution(self):
        for request in (BudgetSource(SymbolAnchor('admitted-selector'), ResultsBudget(1)),
                        BudgetTraversal('admitted-selector', ResultsBudget(1)),
                        BudgetDeclarationSearch(ResultsBudget(1))):
            self.assertEqual({'max_results': 1}, asdict(request)['execution_budget'])

    def test_declaration_search_retains_unrestricted_kind_and_exact_name_contract(self):
        self.assertEqual({'declaration_name': 'pageItem00', 'name_match': 'exact', 'scope': None,
                          'declaration_kinds': None, 'execution_budget': {'max_work_units': 100000}},
                         asdict(BudgetDeclarationSearch(WorkBudget())))

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
