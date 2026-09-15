#!/usr/bin/env python3
"""Independent request-shape and occurrence-oracle checks for the call fixture."""
from dataclasses import asdict, dataclass, replace
import unittest
from types import SimpleNamespace
from pathlib import Path
from contextlib import redirect_stderr
from io import StringIO

from hosted_kotlin_call_regression import (KotlinCallRead, KotlinCallSearch, _site, _has_scoped_unsupported,
    CallCycleTraversal, CallPageBudget, CallResume, CallObligation, _scoped_obligations, _drain_call_cycle,
    _cycle_observation, _call_observation, _emit_native_observation, FixtureEndpoint, EndpointCount,
    ObservedCallStatus)


@dataclass(frozen=True)
class Range:
    startInclusive: int = 1
    endExclusive: int = 6


@dataclass(frozen=True)
class Sample:
    file: str = '/fixture/Calls.kt'
    range: Range = Range()


@dataclass(frozen=True)
class Measurement:
    type: str = 'observed_on_page'
    items: int = 1


@dataclass(frozen=True)
class OmissionEvidence:
    reason: str = 'UNSUPPORTED_ITEM'
    measurement: Measurement = Measurement()
    samples: tuple[Sample, ...] = (Sample(),)


@dataclass(frozen=True)
class LimitedResponse:
    status: str = 'qualified'
    omissions: tuple[OmissionEvidence, ...] = (OmissionEvidence(),)


@dataclass(frozen=True)
class Progress:
    checkpointSequence: int = 1
    totalReads: int = 1
    totalEdges: int = 0
    maximumDepthReached: int = 0


@dataclass(frozen=True)
class Checkpoint:
    token: str = 'cursor'
    type: str = 'upstream'


@dataclass(frozen=True)
class Qualification:
    type: str = 'resumable'
    limitations: tuple[str, ...] = ('record-limit-reached',)
    relationLimitations: tuple[str, ...] = ()
    continuation: str = 'cursor'
    checkpoint: Checkpoint = Checkpoint()
    next_action: str = 'resume'


@dataclass(frozen=True)
class Page:
    status: str = 'qualified'
    live: str = 'same-test-authority'
    progress: Progress = Progress()
    qualification: Qualification = Qualification()


@dataclass(frozen=True)
class GraphNode:
    id: int
    range: Range
    proof: int
    selector: str = 'secret-node-handle'
    file: str = '/secret/workspace/Calls.kt'


@dataclass(frozen=True)
class GraphProof:
    id: int
    identity: str


@dataclass(frozen=True)
class GraphOccurrence:
    range: Range
    candidateSelector: str = 'secret-occurrence-handle'
    file: str = '/secret/workspace/Calls.kt'


@dataclass(frozen=True)
class GraphEdge:
    source: int
    target: int
    occurrence: GraphOccurrence
    depth: int = 1
    coverage: str = 'exact-compiler-confirmed'
    provenance: str = 'k2-authored-source'


@dataclass(frozen=True)
class Graph:
    nodes: tuple[GraphNode, ...]
    proofs: tuple[GraphProof, ...]
    edges: tuple[GraphEdge, ...]


@dataclass(frozen=True)
class GraphPage:
    graph: Graph


@dataclass(frozen=True)
class ObservedFact:
    target: GraphNode
    coverage: str = 'exact-compiler-confirmed'
    provenance: str = 'k2-authored-source'


@dataclass(frozen=True)
class ObservedFacts:
    relations: tuple[ObservedFact, ...]
    status: str = 'complete'
    omissions: tuple[OmissionEvidence, ...] = ()


class KotlinCallRegressionTest(unittest.TestCase):
    def test_scoped_omission_uses_authored_enum_spelling(self):
        self.assertTrue(_has_scoped_unsupported([asdict(OmissionEvidence())]))
        self.assertFalse(_has_scoped_unsupported([asdict(OmissionEvidence(samples=()))]))
        self.assertFalse(_has_scoped_unsupported([asdict(OmissionEvidence(reason='unsupported-item'))]))

    def test_cycle_observation_separates_order_proofs_and_handles(self):
        graph = Graph((GraphNode(0, Range(10, 15), 0), GraphNode(1, Range(20, 25), 1)),
                      (GraphProof(0, 'secret-proof-a'), GraphProof(1, 'secret-proof-b')),
                      (GraphEdge(0, 1, GraphOccurrence(Range(30, 35))),
                       GraphEdge(1, 0, GraphOccurrence(Range(40, 45)), depth=2)))
        high = [asdict(GraphPage(graph))]
        reordered = [asdict(GraphPage(replace(graph, edges=tuple(reversed(graph.edges)))))]
        order = _cycle_observation(high, reordered)
        self.assertFalse(order.edge_order_equal)
        self.assertTrue(order.edge_multiset_equal)
        self.assertTrue(order.compiler_identities_equal)
        changed = replace(graph, proofs=(GraphProof(0, 'different-proof'), graph.proofs[1]))
        proof = _cycle_observation(high, [asdict(GraphPage(changed))])
        self.assertFalse(proof.compiler_identities_equal)
        self.assertTrue(proof.node_selectors_equal)
        changed = replace(graph, nodes=(replace(graph.nodes[0], selector='another-handle'), graph.nodes[1]),
                          edges=(replace(graph.edges[0], occurrence=replace(graph.edges[0].occurrence,
                                 candidateSelector='another-occurrence')), graph.edges[1]))
        handles = _cycle_observation(high, [asdict(GraphPage(changed))])
        self.assertTrue(handles.compiler_identities_equal)
        self.assertFalse(handles.node_selectors_equal)
        self.assertFalse(handles.occurrence_selectors_equal)
        output = StringIO()
        with redirect_stderr(output):
            _emit_native_observation(handles)
        self.assertNotIn('secret', output.getvalue())
        self.assertNotIn('another', output.getvalue())
        self.assertIn('kast-authored-cycle-observation-v1', output.getvalue())

    def test_call_observation_reports_authored_target_without_payload(self):
        fixture = Path(__file__).resolve().parents[1] / 'experiments/host-observation/semantic-fixture/read-reliability/ReadKotlinCalls.kt'
        source = fixture.read_text()
        node = GraphNode(0, Range(source.index('fun fetch(): String'), source.index('fun fetch(): String') + 19),
                         0, file=str(fixture))
        observed = _call_observation('delegated', asdict(ObservedFacts((ObservedFact(node),))), fixture, source)
        self.assertEqual(ObservedCallStatus.COMPLETE, observed.status)
        self.assertEqual((EndpointCount(FixtureEndpoint.BASE_FETCH, 1),), observed.endpoints)
        self.assertEqual(1, observed.exact_authored_facts)
        output = StringIO()
        with redirect_stderr(output):
            _emit_native_observation(observed)
        self.assertNotIn(str(fixture), output.getvalue())
        self.assertNotIn('secret', output.getvalue())
        self.assertIn('base-fetch', output.getvalue())

    def test_cycle_drain_rejects_repeated_cursor_and_rejected_branch(self):
        request = CallCycleTraversal('issued-ref', CallPageBudget(1))
        for responses in ((Page(), Page()), (replace(Page(), status='rejected'),)):
            stream = iter(asdict(page) for page in responses)
            replay = SimpleNamespace(live='same-test-authority', surface='test',
                                     transport=SimpleNamespace(invoke=lambda *args: next(stream)))
            pages, valid = _drain_call_cycle(replay, request)
            self.assertFalse(valid)
            self.assertEqual(len(responses), len(pages))

    def test_scoped_obligations_fail_closed(self):
        obligation = CallObligation(1, 6, ('UNSUPPORTED_ITEM',))
        admitted = LimitedResponse()
        self.assertTrue(_scoped_obligations(asdict(admitted), '/fixture/Calls.kt', (obligation,)))
        for rejected in (
            replace(admitted, status='complete'), replace(admitted, status='rejected'),
            replace(admitted, omissions=()),
            replace(admitted, omissions=(OmissionEvidence(reason='PROVIDER_FAILURE'),)),
            replace(admitted, omissions=(OmissionEvidence(measurement=Measurement(items=0)),)),
            replace(admitted, omissions=(OmissionEvidence(samples=()),)),
        ):
            self.assertFalse(_scoped_obligations(asdict(rejected), '/fixture/Calls.kt', (obligation,)))
        self.assertFalse(_scoped_obligations(asdict(admitted), '/other/Calls.kt', (obligation,)))
        self.assertFalse(_scoped_obligations(asdict(admitted), '/fixture/Calls.kt',
                                           (obligation, CallObligation(50, 55, obligation.reasons))))

    def test_cycle_request_retains_semantic_limits_across_resume(self):
        request = CallCycleTraversal('issued-ref', CallPageBudget(1))
        expected = {'exactSelector': 'issued-ref', 'execution_budget': {'max_results': 1, 'max_elapsed_ms': 5000},
                    'position': {'type': 'start'}, 'relation': 'callees', 'maximumDepth': 2,
                    'maximumResults': 100, 'strategy': {'type': 'breadth_first'}}
        self.assertEqual(expected, asdict(request))
        expected['position'] = {'type': 'resume', 'continuation': 'issued-cursor'}
        self.assertEqual(expected, asdict(replace(request, position=CallResume('issued-cursor'))))

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
