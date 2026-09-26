#!/usr/bin/env python3
"""Independent request-shape and occurrence-oracle checks for the call fixture."""
from dataclasses import asdict, dataclass, field, replace
import unittest
import json
from types import SimpleNamespace
from pathlib import Path
from contextlib import redirect_stderr
from io import StringIO

from hosted_kotlin_call_regression import (KotlinCallScope, _site, _has_scoped_unsupported,
    CallPageBudget, CallObligation, cycle_query, _scoped_obligations, _drain_call_cycle,
    _inline_key, _cycle_observation, _call_observation, _emit_native_observation, FixtureEndpoint, EndpointCount,
    ObservedCallStatus, _extended_call_cases, _same_cycle_graph, _same_cycle_page)
from query_name_request import BreadthFirstWalk, QueryInput, QueryResume, name_query, relation_query


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
    provider: str = 'INTELLIJ_CALLEES_V2'
    remediation: str = 'USE_SUPPORTED_DECLARATIONS'
    reason: str = 'UNSUPPORTED_ITEM'
    measurement: Measurement = Measurement()
    samples: tuple[Sample, ...] = (Sample(),)


@dataclass(frozen=True)
class AttributedOmission:
    evidence: OmissionEvidence
    subject: str = 'exact:subject'
    relation: str = 'callees'


@dataclass(frozen=True)
class LimitedResponse:
    status: str = 'qualified'
    omissions: tuple[AttributedOmission, ...] = (AttributedOmission(OmissionEvidence()),)


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
class QueryProgress:
    checkpoint: Checkpoint = Checkpoint()
    type: str = 'resumable'
    next_action: str = 'resume'


@dataclass(frozen=True)
class Qualification:
    progress: QueryProgress = QueryProgress()
    limitations: tuple[str, ...] = ('record-limit-reached',)


@dataclass(frozen=True)
class WalkEndpoint:
    range: Range
    compilerEvidence: 'WalkCompilerEvidence'
    selector: str = 'secret-node-handle'
    file: str = '/secret/workspace/Calls.kt'


@dataclass(frozen=True)
class WalkCompilerEvidence:
    identity: str


@dataclass(frozen=True)
class CallOccurrence:
    range: Range
    candidateSelector: str = 'secret-occurrence-handle'
    file: str = '/secret/workspace/Calls.kt'


@dataclass(frozen=True)
class WalkRelation:
    source: WalkEndpoint
    target: WalkEndpoint
    occurrence: CallOccurrence
    coverage: str = 'exact-compiler-confirmed'
    provenance: str = 'k2-authored-source'


@dataclass(frozen=True)
class WalkRecord:
    relation: WalkRelation
    depth: int = 1


@dataclass(frozen=True)
class WalkItem:
    record: WalkRecord
    ref: str = 'secret-node-handle'
    type: str = field(default='traversal_record', init=False)


@dataclass(frozen=True)
class WalkCoverage:
    kind: str = 'resumable'
    limitations: tuple[str, ...] = ('record-limit-reached',)
    relation_limitations: tuple[str, ...] = ()


@dataclass(frozen=True)
class WalkObservation:
    progress: Progress = Progress()
    coverage: WalkCoverage = WalkCoverage()
    subject: str = 'secret-node-handle'
    relation: str = 'callees'
    maximum_depth: int = 2
    expanded_frontier: int = 1
    strategy: BreadthFirstWalk = BreadthFirstWalk()
    partial_expansions: tuple = ()


@dataclass(frozen=True)
class WalkPage:
    items: tuple[WalkItem, ...] = ()
    walk_observations: tuple[WalkObservation, ...] = (WalkObservation(),)
    live: str = 'same-test-authority'
    status: str = 'qualified'
    qualification: Qualification = Qualification()
    continuation: str = 'cursor'


@dataclass(frozen=True)
class NoReceiver:
    type: str = 'absent'


@dataclass(frozen=True)
class FunctionSignature:
    qualifiedIdentity: str = 'fixture.calls.BaseClient.fetch'
    receiver: NoReceiver = NoReceiver()
    contextReceivers: tuple[str, ...] = ()
    valueParameters: tuple[str, ...] = ()
    typeParameterCount: int = 0
    type: str = 'function'


@dataclass(frozen=True)
class ClassSignature:
    qualifiedIdentity: str = 'fixture.calls.Fetcher'
    type: str = 'class-like'


@dataclass(frozen=True)
class CompilerEvidence:
    signature: FunctionSignature | ClassSignature = FunctionSignature()
    identity: str = 'canonical-signature-sha256-v1|' + 'a' * 64


@dataclass(frozen=True)
class NativeSymbol:
    range: Range
    file: str
    selector: str = 'secret-symbol-handle'
    kind: str = 'function'
    name: str = 'fetch'
    qualifiedIdentity: str = 'fixture.calls.BaseClient.fetch'
    compilerEvidence: CompilerEvidence = CompilerEvidence()


@dataclass(frozen=True)
class ObservedFact:
    target: NativeSymbol
    occurrence: CallOccurrence = CallOccurrence(Range())
    coverage: str = 'exact-compiler-confirmed'
    provenance: str = 'k2-authored-source'
    meaning: str = 'callees'
    source: NativeSymbol = NativeSymbol(Range(100, 150), '/fixture/Calls.kt')


@dataclass(frozen=True)
class ObservedFacts:
    items: tuple['OccurrenceItem', ...]
    live: str = 'same-test-authority'
    status: str = 'complete'
    omissions: tuple[AttributedOmission, ...] = ()


@dataclass(frozen=True)
class OccurrenceItem:
    relation: ObservedFact
    type: str = 'occurrence'


def observed(*facts, status='complete', omissions=()):
    return ObservedFacts(tuple(OccurrenceItem(fact) for fact in facts), status=status,
                         omissions=tuple(AttributedOmission(evidence) for evidence in omissions))


@dataclass(frozen=True)
class SearchItem:
    ref: str


@dataclass(frozen=True)
class SearchResponse:
    items: tuple[SearchItem, ...]
    status: str = 'complete'


class KotlinCallRegressionTest(unittest.TestCase):
    def test_inline_relation_resume_shape_and_canonical_parity_key(self):
        request = QueryInput(QueryResume('next'))
        self.assertEqual({'request': {'action': 'resume', 'continuation': 'next',
                                      'execution_budget': None}}, asdict(request))
        fact = ObservedFact(NativeSymbol(Range(), '/fixture/Calls.kt'))
        inverse = replace(fact, meaning='callers', target=replace(fact.target, selector='new-handle'))
        self.assertEqual(_inline_key(asdict(fact)), _inline_key(asdict(inverse)))
        changed = replace(fact, target=replace(fact.target,
                          compilerEvidence=CompilerEvidence(identity='canonical-signature-sha256-v1|' + 'b' * 64)))
        self.assertNotEqual(_inline_key(asdict(fact)), _inline_key(asdict(changed)))

    def test_scoped_omission_uses_authored_enum_spelling(self):
        self.assertTrue(_has_scoped_unsupported([asdict(OmissionEvidence())]))
        self.assertFalse(_has_scoped_unsupported([asdict(OmissionEvidence(samples=()))]))
        self.assertFalse(_has_scoped_unsupported([asdict(OmissionEvidence(reason='unsupported-item'))]))

    def test_native_established_delegated_and_sam_shapes(self):
        fixture = Path(__file__).resolve().parents[1] / 'experiments/host-observation/semantic-fixture/read-reliability/ReadKotlinCalls.kt'
        source = fixture.read_text()
        def fact(declaration, call, prefix, name):
            start = source.index(call) + len(prefix)
            target = NativeSymbol(Range(source.index(declaration), source.index(declaration) + len(declaration)),
                                  file=str(fixture))
            if declaration == 'operator fun invoke()':
                target = replace(target, name='invoke', qualifiedIdentity='fixture.calls.Fetcher.invoke',
                    compilerEvidence=CompilerEvidence(FunctionSignature('fixture.calls.Fetcher.invoke')))
            if declaration == 'fun interface Fetcher':
                target = replace(target, kind='classlike', name='Fetcher', qualifiedIdentity='fixture.calls.Fetcher',
                                 compilerEvidence=CompilerEvidence(ClassSignature()))
            return ObservedFact(target, CallOccurrence(Range(start, start + len(name)), file=str(fixture)))
        def omitted(call, prefix, name):
            start = source.index(call) + len(prefix)
            return OmissionEvidence(samples=(Sample(str(fixture), Range(start, start + len(name))),))
        responses = {
            'explicitInvoke': observed(fact('operator fun invoke()', 'fetcher.invoke()', 'fetcher.', 'invoke')),
            'implicitInvoke': observed(status='qualified',
                omissions=(omitted('String = fetcher()', 'String = ', 'fetcher'),)),
            'localFunction': observed(status='qualified',
                omissions=(omitted('return nested()', 'return ', 'nested'),)),
            'delegated': observed(fact('fun fetch(): String',
                'fun delegated(client: DelegatingClient): String = client.fetch()',
                'fun delegated(client: DelegatingClient): String = client.', 'fetch')),
            'sam': observed(fact('fun interface Fetcher', '= Fetcher {', '= ', 'Fetcher'),
                status='qualified', omissions=(omitted('Fetcher { client.fetch()', 'Fetcher { client.', 'fetch'),)),
        }
        def invoke(surface, tool, request):
            if request['request']['source']['type'] == 'search_declarations':
                return json.loads(json.dumps(asdict(SearchResponse((SearchItem(request['request']['source']['declaration_name']),)))))
            return json.loads(json.dumps(asdict(responses[request['request']['source']['symbol_refs'][0]])))
        replay = SimpleNamespace(live='same-test-authority', surface='test', transport=SimpleNamespace(invoke=invoke))
        with redirect_stderr(StringIO()):
            checks, count = _extended_call_cases(replay, source, fixture)
        self.assertTrue(all(checks.values()), checks)
        self.assertEqual(3, count)
        responses['sam'] = replace(responses['sam'], omissions=())
        responses['delegated'] = replace(responses['delegated'], items=())
        with redirect_stderr(StringIO()):
            rejected, _ = _extended_call_cases(replay, source, fixture)
        self.assertFalse(rejected['samCoverage'])
        self.assertFalse(rejected['delegatedExactStaticFacts'])

    def test_cycle_observation_separates_order_proofs_and_handles(self):
        first = WalkEndpoint(Range(10, 15), WalkCompilerEvidence('secret-proof-a'))
        second = WalkEndpoint(Range(20, 25), WalkCompilerEvidence('secret-proof-b'))
        a = WalkRecord(WalkRelation(first, second, CallOccurrence(Range(30, 35))))
        b = WalkRecord(WalkRelation(second, first, CallOccurrence(Range(40, 45))), depth=2)
        high = [asdict(WalkPage((WalkItem(a), WalkItem(b))))]
        reordered = [asdict(WalkPage((WalkItem(b), WalkItem(a))))]
        order = _cycle_observation(high, reordered)
        self.assertFalse(order.edge_order_equal)
        self.assertTrue(order.edge_multiset_equal)
        self.assertTrue(order.compiler_identities_equal)
        self.assertTrue(_same_cycle_graph(high, reordered))
        self.assertTrue(_same_cycle_page(high[0], high[0]))
        self.assertFalse(_same_cycle_page(high[0], reordered[0]))
        partitioned = [asdict(WalkPage((WalkItem(record),))) for record in (b, a)]
        self.assertTrue(_same_cycle_graph(high, partitioned))
        self.assertFalse(_same_cycle_graph(high, partitioned[:1]))
        proof_changed = replace(a, relation=replace(a.relation, source=replace(first,
                                compilerEvidence=WalkCompilerEvidence('different-proof'))))
        changed_page = [asdict(WalkPage((WalkItem(proof_changed), WalkItem(b))))]
        proof = _cycle_observation(high, changed_page)
        self.assertFalse(proof.compiler_identities_equal)
        self.assertFalse(_same_cycle_graph(high, changed_page))
        self.assertTrue(proof.node_selectors_equal)
        handles_changed = replace(a, relation=replace(a.relation,
                                  source=replace(first, selector='another-handle'),
                                  occurrence=replace(a.relation.occurrence,
                                                     candidateSelector='another-occurrence')))
        handle_page = [asdict(WalkPage((WalkItem(handles_changed), WalkItem(b))))]
        handles = _cycle_observation(high, handle_page)
        self.assertTrue(handles.compiler_identities_equal)
        self.assertFalse(handles.node_selectors_equal)
        self.assertFalse(handles.occurrence_selectors_equal)
        self.assertFalse(_same_cycle_graph(high, handle_page))
        output = StringIO()
        with redirect_stderr(output):
            _emit_native_observation(handles)
        self.assertNotIn('secret', output.getvalue())
        self.assertNotIn('another', output.getvalue())
        self.assertIn('kast-authored-cycle-observation-v1', output.getvalue())

    def test_call_observation_reports_authored_target_without_payload(self):
        fixture = Path(__file__).resolve().parents[1] / 'experiments/host-observation/semantic-fixture/read-reliability/ReadKotlinCalls.kt'
        source = fixture.read_text()
        node = NativeSymbol(Range(source.index('fun fetch(): String'), source.index('fun fetch(): String') + 19),
                            file=str(fixture))
        observed_value = _call_observation('delegated', asdict(observed(ObservedFact(node))), fixture, source)
        self.assertEqual(ObservedCallStatus.COMPLETE, observed_value.status)
        self.assertEqual((EndpointCount(FixtureEndpoint.BASE_FETCH, 1),), observed_value.endpoints)
        self.assertEqual(1, observed_value.exact_authored_facts)
        output = StringIO()
        with redirect_stderr(output):
            _emit_native_observation(observed_value)
        self.assertNotIn(str(fixture), output.getvalue())
        self.assertNotIn('secret', output.getvalue())
        self.assertIn('base-fetch', output.getvalue())

    def test_cycle_drain_rejects_repeated_cursor_and_rejected_branch(self):
        request = cycle_query('issued-ref', CallPageBudget(1))
        for responses in ((WalkPage(), WalkPage()), (replace(WalkPage(), status='rejected'),)):
            stream = iter(asdict(page) for page in responses for _ in range(2))
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
            replace(admitted, omissions=(AttributedOmission(OmissionEvidence(reason='PROVIDER_FAILURE')),)),
            replace(admitted, omissions=(AttributedOmission(OmissionEvidence(measurement=Measurement(items=0))),)),
            replace(admitted, omissions=(AttributedOmission(OmissionEvidence(samples=())),)),
        ):
            self.assertFalse(_scoped_obligations(asdict(rejected), '/fixture/Calls.kt', (obligation,)))
        self.assertFalse(_scoped_obligations(asdict(admitted), '/other/Calls.kt', (obligation,)))
        self.assertFalse(_scoped_obligations(asdict(admitted), '/fixture/Calls.kt',
                                           (obligation, CallObligation(50, 55, obligation.reasons))))

    def test_cycle_request_retains_semantic_limits_across_resume(self):
        request = cycle_query('issued-ref', CallPageBudget(1))
        expected = {'request': {'action': 'run', 'source': {'type': 'symbol_refs', 'symbol_refs': ('issued-ref',)},
                    'steps': ({'type': 'walk', 'relation': 'callees', 'maximum_depth': 2,
                               'strategy': {'type': 'breadth_first'}},),
                    'output': {'type': 'traversal_records'},
                    'execution_budget': {'max_results': 1, 'max_elapsed_ms': 5000}}}
        self.assertEqual(expected, asdict(request))
        self.assertEqual({'request': {'action': 'resume', 'continuation': 'issued-cursor',
                                     'execution_budget': {'max_results': 1, 'max_elapsed_ms': 5000}}},
                         asdict(QueryInput(QueryResume('issued-cursor', CallPageBudget(1)))))

    def test_fixed_request_shapes(self):
        self.assertEqual({'request': {'action': 'run',
                          'source': {'type': 'search_declarations', 'declaration_name': 'outer',
                                     'name_match': 'exact', 'declaration_kinds': ('function',),
                                     'scope': {'package_name': 'fixture.calls', 'include_subpackages': False,
                                               'source_set_names': ('main',)}},
                          'steps': None, 'output': {'fields': ('name', 'location', 'signature'), 'type': 'symbols'},
                          'execution_budget': None}},
                         asdict(name_query('outer', ('function',), KotlinCallScope())))
        self.assertEqual({'request': {'action': 'run', 'source': {'symbol_refs': ('issued-ref',),
                          'type': 'symbol_refs'}, 'steps': ({'relation': 'callees', 'type': 'expand_relation'},),
                          'output': {'type': 'occurrences'}, 'execution_budget': None}},
                         asdict(relation_query('issued-ref')))

    def test_occurrence_offsets_distinguish_repeated_calls(self):
        source = 'fun fetch() = 1\nfun repeat() = client.fetch() + client.fetch()'
        self.assertEqual((0, 38, 43), _site(source, 'client.fetch() + client.fetch()', 'fetch', 'fun fetch'))
        self.assertEqual((0, 55, 60), _site(source, '+ client.fetch()', 'fetch', 'fun fetch'))


if __name__ == '__main__':
    unittest.main()
