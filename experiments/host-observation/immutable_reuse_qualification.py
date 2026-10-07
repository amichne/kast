"""Independent equality and work gates for exact-source native reuse trials.

This module admits already collected schema-validated public responses. It never
creates a cache hit or substitutes target-set equality for complete evidence.
"""
from dataclasses import dataclass
from enum import Enum


class ReuseFailure(str, Enum):
    INCOMPLETE = 'INCOMPLETE'
    STALE_EVIDENCE = 'STALE_EVIDENCE'
    UNPROVEN_HANDLE = 'UNPROVEN_HANDLE'
    DIFFERENT_SEMANTICS = 'DIFFERENT_SEMANTICS'
    MISSING_COUNTER = 'MISSING_COUNTER'
    STORE_NOT_EXERCISED = 'STORE_NOT_EXERCISED'


class ReuseRejected(ValueError):
    def __init__(self, cause):
        self.cause = cause
        super().__init__(cause.value)


def require(value, cause):
    if not value: raise ReuseRejected(cause)


@dataclass(frozen=True)
class SemanticAnswer:
    root: str
    content_view: str
    version: int
    coverage: dict
    items: tuple
    relations: tuple
    failures: tuple
    omissions: tuple


def semantic_answer(pages):
    require(bool(pages), ReuseFailure.INCOMPLETE)
    basis = pages[-1]['live']
    required = ('root', 'host', 'epoch', 'contentView', 'version')
    require(all(key in basis for key in required), ReuseFailure.STALE_EVIDENCE)
    handles = {}
    subject_identity = None
    tokens = {group['subject']['token'] for page in pages for group in page['relation_observations']
              if 'subject' in group}
    if tokens:
        require(len(tokens) == 1, ReuseFailure.UNPROVEN_HANDLE)
        questions = [page.get('question') for page in pages]
        require(all(question == questions[0] for question in questions), ReuseFailure.UNPROVEN_HANDLE)
        question = questions[0] or {}
        origin, steps = question.get('from', {}), question.get('steps', [])
        require(origin.get('type') == 'location' and len(steps) == 1 and steps[0].get('type') == 'related'
                and steps[0].get('relation') in ('callers', 'callees'), ReuseFailure.UNPROVEN_HANDLE)
        endpoint = 'source' if steps[0]['relation'] == 'callees' else 'target'
        for page in pages:
            for row in page['items']:
                relation = row.get('relation', {})
                if relation.get('meaning') != steps[0]['relation']: continue
                target = relation.get(endpoint, {})
                location = target.get('range', {})
                if (target.get('file') != basis['root'] + '/' + origin['file'] or
                    not location.get('startInclusive', -1) <= origin['offset'] < location.get('endExclusive', -1)): continue
                identity = {'signature': target['compilerEvidence']['signature'],
                            'file': target['file'], 'range': location, 'question': question}
                require(subject_identity is None or subject_identity == identity, ReuseFailure.UNPROVEN_HANDLE)
                subject_identity = identity
        require(subject_identity is not None, ReuseFailure.UNPROVEN_HANDLE)
    def collect(value):
        if isinstance(value, list):
            for child in value: collect(child)
        elif isinstance(value, dict):
            signature = value.get('signature') or value.get('compilerEvidence', {}).get('signature')
            location = value.get('location') or ({'file': value['file'], 'range': value['range']}
                                               if 'file' in value and 'range' in value else None)
            if signature and location:
                for key in ('ref', 'selector'):
                    if key in value:
                        identity = {'signature': signature, 'location': location}
                        require(value[key] not in handles or handles[value[key]] == identity, ReuseFailure.UNPROVEN_HANDLE)
                        handles[value[key]] = identity
            for child in value.values(): collect(child)
    for page in pages: collect(page)
    def normalize(value):
        if isinstance(value, list): return [normalize(child) for child in value]
        if not isinstance(value, dict): return value
        result = {}
        for key, child in value.items():
            if key == 'subject' and isinstance(child, dict) and set(child) == {'token'}:
                require(child['token'] in tokens and subject_identity is not None, ReuseFailure.UNPROVEN_HANDLE)
                result[key] = subject_identity
            elif key == 'basis':
                require(child.get('type') == 'LIVE' and all(child.get(part) == basis[part]
                    for part in ('root', 'host', 'epoch', 'contentView')) and child.get('referenceVersion') == basis['version'], ReuseFailure.STALE_EVIDENCE)
                # Native read authority must be current before opaque generation fields can be normalized.
                result[key] = {part: data for part, data in child.items() if part not in ('host', 'epoch')}
            elif key == 'candidateSelector':
                require(isinstance(child, str) and 'file' in value and 'range' in value, ReuseFailure.UNPROVEN_HANDLE)
                result[key] = {'file': value['file'], 'range': value['range']}
            elif key in ('ref', 'selector'):
                require(isinstance(child, str) and child in handles, ReuseFailure.UNPROVEN_HANDLE)
                result[key] = normalize(handles[child])
            elif key == 'row_id':
                require(isinstance(child, str) and child.startswith('result-row:v1:') and value.get('type') == 'occurrence'
                        and 'relation' in value, ReuseFailure.UNPROVEN_HANDLE)
                result[key] = 'QUALIFIED_OCCURRENCE_ROW'
            else: result[key] = normalize(child)
        return result
    rows, relations, failures, omissions = [], [], [], []
    for page in pages:
        require(page['status'] == 'complete' and page['live'] == basis and page['coverage']['exhaustive'], ReuseFailure.INCOMPLETE)
        require(not page.get('continuation') and not page.get('next_cursor'), ReuseFailure.INCOMPLETE)
        rows.extend(normalize(page['items']))
        relations.extend(normalize(page['relation_observations']))
        failures.extend(normalize(page['failures']))
        omissions.extend(normalize(page['omissions']))
    return SemanticAnswer(basis['root'], basis['contentView'], basis['version'], normalize(pages[-1]['coverage']),
                          tuple(rows), tuple(relations), tuple(failures), tuple(omissions))


def assert_equal_answers(reused_pages, fresh_pages):
    reused, fresh = semantic_answer(reused_pages), semantic_answer(fresh_pages)
    require(reused == fresh, ReuseFailure.DIFFERENT_SEMANTICS)
    return reused


def assert_partition_activity(counters, *, reused=False, extracted=False, invalidated=False, family='', dependency_rejections=0):
    prefix = 'SEMANTIC_FACT_' + (family + '_' if family else 'PARTITIONS_')
    for suffix, positive in (('REUSED', reused), ('EXTRACTED', extracted), ('INVALIDATED', invalidated)):
        name = prefix + suffix
        require(name in counters and type(counters[name]) is int and counters[name] >= 0, ReuseFailure.MISSING_COUNTER)
        require(counters[name] > 0 if positive else counters[name] == 0, ReuseFailure.STORE_NOT_EXERCISED)
    require(type(counters.get('SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS')) is int and counters['SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS'] > 0, ReuseFailure.STORE_NOT_EXERCISED)
    require(counters.get('SEMANTIC_FACT_DEPENDENCY_REJECTIONS') == dependency_rejections, ReuseFailure.STORE_NOT_EXERCISED)
