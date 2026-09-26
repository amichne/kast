#!/usr/bin/env python3
"""Local parity-oracle and bounded-drain checks; these do not run a native host."""
from dataclasses import asdict, dataclass, field, replace
from types import SimpleNamespace
import unittest

from hosted_budget_read_regression import ResultsBudget
from hosted_resume_budget_regression import (Checkpoint, DrainRejected, Drained, ResumeFailure,
    ResumeSource, admit_progress, drain, payload_parity, resume_request)
from hosted_source_read_regression import SymbolAnchor
from query_name_request import QueryInput, QueryRun, SymbolReferences, SymbolOutput


@dataclass(frozen=True)
class Limit:
    selection: str = 'configured_default'
    requested: int | None = None
    effective: int = 100
    clamping: tuple[str, ...] = ()


@dataclass(frozen=True)
class Grant:
    max_elapsed_ms: Limit = field(default_factory=Limit)
    max_work_units: Limit = field(default_factory=Limit)
    max_results: Limit = field(default_factory=Limit)
    max_returned_bytes: Limit = field(default_factory=Limit)


@dataclass(frozen=True)
class Progress:
    checkpoint: 'Token'
    type: str = 'resumable'
    next_action: str = 'resume'


@dataclass(frozen=True)
class Token:
    token: str
    type: str = 'upstream'


@dataclass(frozen=True)
class Qualification:
    progress: Progress
    limitations: tuple[str, ...] = ()


@dataclass(frozen=True)
class Available:
    continuation: str
    type: str = 'available'


@dataclass(frozen=True)
class SourceQualification:
    progress: Progress
    continuation: Available


@dataclass(frozen=True)
class ProgressResponse:
    qualification: SourceQualification
    status: str = 'qualified'


@dataclass(frozen=True)
class Record:
    name: str
    occurrence: str = 'call-1'
    proof: str = 'compiler-proof'


@dataclass(frozen=True)
class Complete:
    execution_budget: Grant
    items: tuple[Record, ...] = ()
    status: str = field(default='complete', init=False)
    live: str = 'unchanged-authority'
    failures: tuple = ()


@dataclass(frozen=True)
class Qualified:
    execution_budget: Grant
    qualification: Qualification
    continuation: str
    items: tuple[Record, ...] = ()
    status: str = field(default='qualified', init=False)
    live: str = 'unchanged-authority'
    failures: tuple = ()


@dataclass(frozen=True)
class Text:
    text: str = 'saved source'
    type: str = 'returned'


@dataclass(frozen=True)
class Source:
    entities: tuple[Record, ...]
    snapshot: str = 'snapshot-proof'
    region: str = 'range-proof'
    text: Text = field(default_factory=Text)


@dataclass(frozen=True)
class OccurrenceRows:
    items: tuple['OccurrenceItem', ...]
    omissions: tuple = ()


@dataclass(frozen=True)
class OccurrenceItem:
    relation: Record
    type: str = field(default='occurrence', init=False)


@dataclass(frozen=True)
class Unmeasured:
    type: str = 'unmeasured_on_page'


@dataclass(frozen=True)
class Observed:
    items: int = 0
    type: str = 'observed_on_page'


@dataclass(frozen=True)
class Omission:
    measurement: Unmeasured | Observed = field(default_factory=Unmeasured)
    provider: str = 'INTELLIJ_REFERENCES_V2'
    reason: str = 'RESULT_LIMIT_REACHED'
    samples: tuple = ()
    remediation: str = 'INCREASE_READ_LIMIT'


@dataclass(frozen=True)
class AttributedOmission:
    evidence: Omission
    subject: str = 'exact:subject'
    relation: str = 'callers'


@dataclass(frozen=True)
class BudgetOccurrencePage:
    items: tuple[OccurrenceItem, ...]
    qualification: Qualification
    continuation: str = 'cursor'
    omissions: tuple[AttributedOmission, ...] = (AttributedOmission(Omission()),)
    status: str = 'qualified'


def request(reference='exact:a'):
    return QueryInput(QueryRun(SymbolReferences((reference,)), output=SymbolOutput(('name',)),
                               execution_budget=ResultsBudget(100)))


def grant(value):
    return Grant(max_results=Limit(selection='caller', requested=value, effective=value))


def qualified(token, kind='upstream', value=100, items=(), action='resume'):
    return asdict(Qualified(grant(value), Qualification(Progress(Token(token, kind), next_action=action)), token, items))


class Transport:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.requests, self.validations = [], []

    def invoke(self, surface, tool, request):
        self.requests.append(request)
        return next(self.responses)

    def validate(self, tool, response):
        self.validations.append(response)


def replay(responses):
    return SimpleNamespace(live='unchanged-authority', surface='cli', transport=Transport(responses))


class HostedResumeBudgetRegressionTest(unittest.TestCase):
    def test_upstream_then_retained_checkpoint_advance_under_larger_grant(self):
        first = qualified('upstream', value=1, items=(Record('a'),))
        second = qualified('retained', kind='retained_output', items=(Record('b'),))
        last = asdict(Complete(grant(100), (Record('c'),)))
        runner = replay((second, last))
        query = request()
        observed = drain(runner, 'query_symbols', query, first, ResultsBudget(1))
        self.assertIsInstance(observed, Drained)
        self.assertEqual(['upstream', 'retained'], [r['request']['continuation'] for r in runner.transport.requests])
        self.assertTrue(all(r['request']['execution_budget'] == {'max_results': 100}
                            and r['request']['action'] == 'resume' for r in runner.transport.requests))
        self.assertEqual(2, len(runner.transport.validations))
        reference = Drained((asdict(Complete(grant(100), (Record('a'), Record('b'), Record('c')))),))
        self.assertTrue(payload_parity('query_symbols', observed, reference))

    def test_empty_upstream_page_uses_increase_action_without_manufacturing_records(self):
        first = qualified('empty', value=1, action='increase_execution_budget')
        last = asdict(Complete(grant(100), (Record('a'),)))
        result = drain(replay((last,)), 'query_symbols', request('a'),
                       first, ResultsBudget(1))
        self.assertIsInstance(result, Drained)
        self.assertEqual((), result.pages[0]['items'])

    def test_repeated_checkpoint_and_authority_or_grant_changes_fail_closed(self):
        first = qualified('same', value=1)
        query = request('a')
        for page, expected in (
                (qualified('same'), ResumeFailure.TOKEN_REPEATED),
                (asdict(replace(Complete(grant(100)), live='changed')), ResumeFailure.AUTHORITY_CHANGED),
                (asdict(Complete(grant(2))), ResumeFailure.GRANT_CHANGED)):
            self.assertEqual(DrainRejected(expected), drain(replay((page,)), 'query_symbols', query,
                                                           first, ResultsBudget(1)))

    def test_complete_low_page_compares_without_inventing_a_continuation(self):
        first = asdict(Complete(grant(1), (Record('a'),)))
        runner = replay(())
        result = drain(runner, 'query_symbols', request('a'),
                       first, ResultsBudget(1))
        self.assertEqual(Drained((first,)), result)
        self.assertEqual([], runner.transport.requests)

    def test_page_bound_stops_without_an_extra_request(self):
        runner = replay(tuple(qualified(str(i)) for i in range(1, 16)))
        query = request('a')
        self.assertEqual(DrainRejected(ResumeFailure.PAGE_BOUND),
                         drain(runner, 'query_symbols', query, qualified('0', value=1), ResultsBudget(1)))
        self.assertEqual(15, len(runner.transport.requests))

    def test_alias_disagreement_and_retained_budget_action_reject(self):
        for page in (qualified('x', 'retained_output', action='increase_execution_budget'),
                     asdict(Qualified(grant(100), Qualification(Progress(Token('canonical'))), 'wrong'))):
            self.assertEqual(DrainRejected(ResumeFailure.CHECKPOINT_REJECTED), admit_progress('query_symbols', page))
        source = ResumeSource(SymbolAnchor('symbol'), ResultsBudget(100))
        self.assertEqual({'continuation': 'issued', 'type': 'continue'},
                         asdict(resume_request(source, 'issued'))['page'])

    def test_source_progress_retains_its_available_continuation(self):
        source = ProgressResponse(SourceQualification(Progress(Token('source')), Available('source')))
        self.assertEqual(Checkpoint('source', 'upstream'), admit_progress('source_read', asdict(source)))
        changed = replace(source, qualification=replace(source.qualification, continuation=Available('wrong')))
        self.assertEqual(DrainRejected(ResumeFailure.CHECKPOINT_REJECTED), admit_progress('source_read', asdict(changed)))

    def test_relation_page_boundaries_preserve_full_occurrence_multiset_and_page_order(self):
        a, b = Record('a'), Record('b', 'call-2')
        reference = Drained((asdict(OccurrenceRows((OccurrenceItem(a), OccurrenceItem(b)))),))
        self.assertTrue(payload_parity('query_occurrences',
            Drained((asdict(OccurrenceRows((OccurrenceItem(b),))),
                     asdict(OccurrenceRows((OccurrenceItem(a),))))), reference))
        for changed in ((a,), (a, a), (a, b, b)):
            self.assertFalse(payload_parity('query_occurrences',
                Drained((asdict(OccurrenceRows(tuple(OccurrenceItem(item) for item in changed))),)), reference))

    def test_upstream_budget_omission_retains_unknown_work_until_complete_drain(self):
        a, b = Record('a'), Record('b', 'call-2')
        qualification = Qualification(Progress(Token('cursor')), ('relation-incomplete',))
        page = BudgetOccurrencePage((OccurrenceItem(a),), qualification)
        tail = asdict(OccurrenceRows((OccurrenceItem(b),)))
        reference = Drained((asdict(OccurrenceRows((OccurrenceItem(a), OccurrenceItem(b)))),))
        self.assertTrue(payload_parity('query_occurrences', Drained((asdict(page), tail)), reference))
        for changed in (replace(page, omissions=(AttributedOmission(Omission(Observed())),)),
                replace(page, qualification=replace(qualification, limitations=())),
                replace(page, omissions=(AttributedOmission(replace(Omission(), reason='UNRESOLVED_TARGET')),)),
                replace(page, omissions=(AttributedOmission(replace(Omission(), remediation='REPAIR_PROVIDER')),))):
            self.assertFalse(payload_parity('query_occurrences', Drained((asdict(changed), tail)), reference))

    def test_parity_rejects_lost_order_occurrence_proof_source_text_and_range(self):
        records = (Record('a'), Record('b', 'call-2'))
        reference = Drained((asdict(OccurrenceRows(tuple(OccurrenceItem(item) for item in records))),))
        for changed in (tuple(reversed(records)), (replace(records[0], occurrence='wrong'), records[1]),
                        (replace(records[0], proof='wrong'), records[1])):
            self.assertFalse(payload_parity('query_occurrences',
                Drained((asdict(OccurrenceRows(tuple(OccurrenceItem(item) for item in changed))),)), reference))
        source = Source(records)
        expected = Drained((asdict(source),))
        pages = Drained((asdict(replace(source, entities=records[:1])), asdict(replace(source, entities=records[1:]))))
        self.assertTrue(payload_parity('source_read', pages, expected))
        for changed in (replace(source, region='changed'), replace(source, text=Text('changed')),
                        replace(source, snapshot='changed'), replace(source, entities=tuple(reversed(records)))):
            self.assertFalse(payload_parity('source_read', Drained((asdict(changed),)), expected))


if __name__ == '__main__':
    unittest.main()
