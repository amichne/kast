"""Exact authored Kotlin call occurrences; no runtime dispatch is inferred."""
from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass, field
from enum import Enum
from query_name_request import (ExpandRelation, QueryInput, QueryResume, QueryRun, SymbolOutput,
    SymbolReferences, name_query, relation_query, occurrence_facts, occurrence_omissions,
    walk_query, walk_records, walk_observation)
from hosted_budget_read_regression import ResultsBudget


@dataclass(frozen=True)
class KotlinCallScope:
    package_name: str = field(default='fixture.calls', init=False)
    include_subpackages: bool = field(default=False, init=False)
    source_set_names: tuple[str, ...] = field(default=('main',), init=False)


class KotlinCallMeaning(str, Enum):
    CALLEES = 'callees'
    CALLERS = 'callers'


def _site(source, fragment, name, declaration):
    start = source.index(fragment) + fragment.index(name)
    return (source.index(declaration), start, start + len(name))


def _facts(response):
    return Counter((fact.get('target', {}).get('range', {}).get('startInclusive'),
                    fact.get('occurrence', {}).get('range', {}).get('startInclusive'),
                    fact.get('occurrence', {}).get('range', {}).get('endExclusive'))
                   for fact in occurrence_facts(response))


def _has_scoped_unsupported(omissions):
    return any(item.get('reason') == 'UNSUPPORTED_ITEM' and item.get('samples') for item in omissions)


def run_kotlin_call_regression(replay):
    """Requires the compiled ReadKotlinCalls.kt in the owned fixture root source set."""
    path = replay.fixture.workspace / 'src/main/kotlin/ReadKotlinCalls.kt'
    source = path.read_text()
    base = 'fun fetch(): String'
    cases = (
        ('outer', 'complete', (
            _site(source, 'val value = client.fetch()', 'fetch', base),
            _site(source, 'return value.adapt()', 'adapt', 'fun String.adapt()'))),
        ('integerExtension', 'complete', (
            _site(source, 'fun integerExtension(value: Int): String = value.adapt()', 'adapt', 'fun Int.adapt()'),)),
        ('repeated', 'complete', (
            _site(source, 'client.fetch() + client.fetch()', 'fetch', base),
            _site(source, '+ client.fetch()', 'fetch', base))),
        ('unused', 'complete', ()),
        ('callback', 'qualified', ()),
        ('qualified', 'qualified', (
            _site(source, 'return client.fetch()', 'fetch', base),)),
    )
    for name, status, expected in cases:
        discovery = replay.transport.invoke(replay.surface, 'query_symbols', asdict(name_query(name, ('function',), KotlinCallScope())))
        items = discovery.get('items', [])
        if discovery.get('status') != 'complete' or len(items) != 1 or not items[0].get('ref'):
            replay.record('kotlin-call-' + name, 'query_symbols', {'exactIssuerAvailable': False},
                          len(items), discovery)
            continue
        response = replay.transport.invoke(replay.surface, 'query_symbols',
                                           asdict(relation_query(items[0]['ref'])))
        facts = occurrence_facts(response)
        omissions = occurrence_omissions(response)
        replay.record('kotlin-call-' + name, 'query_symbols', {
            'expectedCoverage': response.get('status') == status,
            'exactOccurrencesAndEndpoints': _facts(response) == Counter(expected),
            'authoredStaticEvidence': all(fact.get('coverage') == 'exact-compiler-confirmed'
                and fact.get('provenance') == 'k2-authored-source'
                and fact.get('occurrence', {}).get('file') == str(path) for fact in facts),
            'scopedUnsupportedOwner': (status == 'complete' and omissions == []) or
                (status == 'qualified' and _has_scoped_unsupported(omissions)),
            'sameLiveAuthority': response.get('live') == replay.live,
        }, len(facts), response)
        if name == 'outer':
            inherited = [fact for fact in facts
                         if fact.get('target', {}).get('range', {}).get('startInclusive') == source.index(base)]
            if len(inherited) == 1 and inherited[0].get('target', {}).get('selector'):
                _inherited_callers(replay, source, inherited[0]['target']['selector'])
            else:
                replay.record('kotlin-call-inherited-callers', 'query_symbols', {'exactIssuerAvailable': False})

    run_inline_ownership_regression(replay, source, path)
    _run_extended_calls(replay, source, path)

def _inherited_callers(replay, source, reference):
    response = replay.transport.invoke(replay.surface, 'query_symbols',
                                       asdict(relation_query(reference, KotlinCallMeaning.CALLERS.value)))
    expected = Counter((
        _site(source, 'val value = client.fetch()', 'fetch', 'fun outer('),
        _site(source, 'client.fetch() + client.fetch()', 'fetch', 'fun repeated('),
        _site(source, '+ client.fetch()', 'fetch', 'fun repeated('),
        _site(source, 'return client.fetch()', 'fetch', 'fun qualified('),
    ))
    facts = occurrence_facts(response)
    actual = Counter((fact.get('source', {}).get('range', {}).get('startInclusive'),
                      fact.get('occurrence', {}).get('range', {}).get('startInclusive'),
                      fact.get('occurrence', {}).get('range', {}).get('endExclusive')) for fact in facts)
    replay.record('kotlin-call-inherited-callers', 'query_symbols', {
        'qualifiedNestedOwners': response.get('status') == 'qualified',
        'exactOccurrencesAndOwners': actual == expected,
        'sameStaticTarget': all(fact.get('target', {}).get('range', {}).get('startInclusive') ==
                               source.index('fun fetch(): String') for fact in facts),
        'sameLiveAuthority': response.get('live') == replay.live,
    }, len(facts), response)


@dataclass(frozen=True)
class CallPageBudget:
    max_results: int
    max_elapsed_ms: int = field(default=5000, init=False)


def cycle_query(reference, budget):
    return walk_query(reference, KotlinCallMeaning.CALLEES.value, maximum_depth=2, budget=budget)


@dataclass(frozen=True)
class CallObligation:
    start: int
    end: int
    reasons: tuple[str, ...]


def _obligation(source, fragment, name, reasons):
    start = source.index(fragment) + fragment.index(name)
    return CallObligation(start, start + len(name), reasons)


def _scoped_obligations(response, path, obligations):
    """Each unavailable call needs finite measured evidence at its own occurrence."""
    omissions = occurrence_omissions(response)
    if response.get('status') != 'qualified' or not omissions or not obligations:
        return False
    covered = set()
    for omission in omissions:
        measurement = omission.get('measurement', {})
        if (measurement.get('type') != 'observed_on_page'
                or type(measurement.get('items')) is not int or measurement['items'] < 1):
            return False
        for sample in omission.get('samples', []):
            bounds = sample.get('range', {})
            start, end = bounds.get('startInclusive'), bounds.get('endExclusive')
            matched = [index for index, obligation in enumerate(obligations)
                       if sample.get('file') == str(path) and omission.get('reason') in obligation.reasons
                       and type(start) is int and type(end) is int
                       and 0 <= obligation.start - start <= 8
                       and 0 <= end - obligation.end <= 64]
            if not matched:
                return False
            covered.update(matched)
    return covered == set(range(len(obligations)))


def _extended_call_cases(replay, source, path):
    """Check native-established static targets; deferred bodies retain their measured gaps."""
    checks, count = {}, 0
    unsupported = ('UNSUPPORTED_ITEM',)
    cases = (
        ('explicitInvoke', (_site(source, 'fetcher.invoke()', 'invoke', 'operator fun invoke()'),), ()),
        ('implicitInvoke', (), (_obligation(source, 'String = fetcher()', 'fetcher', unsupported),)),
        ('delegated', (_site(source, 'fun delegated(client: DelegatingClient): String = client.fetch()',
                             'fetch', 'fun fetch(): String'),), ()),
        ('localFunction', (), (_obligation(source, 'return nested()', 'nested', unsupported),)),
        ('sam', (_site(source, '= Fetcher {', 'Fetcher', 'fun interface Fetcher'),), (
            _obligation(source, 'Fetcher { client.fetch()', 'fetch', unsupported),)),
    )
    for name, expected, obligations in cases:
        discovery = replay.transport.invoke(replay.surface, 'query_symbols', asdict(name_query(name, ('function',), KotlinCallScope())))
        items = discovery.get('items', [])
        admitted = discovery.get('status') == 'complete' and len(items) == 1 and bool(items[0].get('ref'))
        checks[name + 'Issuer'] = admitted
        if not admitted:
            continue
        response = replay.transport.invoke(replay.surface, 'query_symbols', asdict(relation_query(items[0]['ref'])))
        count += len(occurrence_facts(response))
        _emit_native_observation(_call_observation(name, response, path, source))
        checks[name + 'ExactStaticFacts'] = _facts(response) == Counter(expected)
        checks[name + 'Coverage'] = (_scoped_obligations(response, path, obligations) if obligations else
            response.get('status') == 'complete' and occurrence_omissions(response) == [])
        checks[name + 'Authority'] = response.get('live') == replay.live
    return checks, count


def _cycle_edges(response):
    return tuple((record.get('depth'),
                  record.get('relation', {}).get('source', {}).get('range', {}).get('startInclusive'),
                  record.get('relation', {}).get('target', {}).get('range', {}).get('startInclusive'),
                  record.get('relation', {}).get('occurrence', {}).get('range', {}).get('startInclusive'),
                  record.get('relation', {}).get('occurrence', {}).get('range', {}).get('endExclusive'))
                 for record in walk_records(response))


def _drain_call_cycle(replay, request):
    from hosted_budget_read_regression import progress_advances
    from hosted_resume_budget_regression import Checkpoint, admit_progress
    pages, seen = [], set()
    for _ in range(12):
        response = replay.transport.invoke(replay.surface, 'query_symbols', asdict(request))
        observation = walk_observation(response)
        previous = walk_observation(pages[-1]).get('progress', {}) if pages else {}
        pages.append(response)
        if (response.get('live') != replay.live or not progress_advances(previous, observation.get('progress', {}))
                or len(walk_records(response)) > request.request.execution_budget.max_results):
            return pages, False
        if response.get('status') != 'qualified':
            return pages, False
        repeated = replay.transport.invoke(replay.surface, 'query_symbols', asdict(request))
        if not _same_cycle_page(response, repeated):
            return pages, False
        coverage = observation.get('coverage', {})
        if coverage.get('kind') == 'terminal_incomplete':
            return pages, (set(coverage.get('relation_limitations', [])) == {'unsupported-item'}
                           and 'one-hop-incomplete' in coverage.get('limitations', []))
        checkpoint = admit_progress('query_symbols', response)
        if not isinstance(checkpoint, Checkpoint) or checkpoint.token in seen:
            return pages, False
        seen.add(checkpoint.token)
        request = QueryInput(QueryResume(checkpoint.token, request.request.execution_budget))
    return pages, False


def _qualified_partial(pages, source):
    selectors = {record.get('relation', {}).get('target', {}).get('selector') for page in pages
                 for record in walk_records(page)
                 if record.get('relation', {}).get('target', {}).get('range', {}).get('startInclusive') ==
                     source.index('fun qualified(')}
    return any(partial.get('subject') in selectors and partial.get('depth') == 1
               and partial.get('scope') == 'page' and partial.get('remainder') == 'not_explored'
               and partial.get('limitations') == ['unsupported-item']
               for page in pages for partial in walk_observation(page).get('partial_expansions', []))


def _cycle_checks(replay, source):
    discovery = replay.transport.invoke(replay.surface, 'query_symbols', asdict(name_query('callCycleEntry', ('function',), KotlinCallScope())))
    items = discovery.get('items', [])
    if discovery.get('status') != 'complete' or len(items) != 1 or not items[0].get('ref'):
        return {'cycleIssuer': False}
    token = items[0]['ref']
    high, high_valid = _drain_call_cycle(replay, cycle_query(token, CallPageBudget(100)))
    low, low_valid = _drain_call_cycle(replay, cycle_query(token, CallPageBudget(1)))
    entry, peer, qualified, base = (source.index(prefix) for prefix in
        ('fun callCycleEntry(', 'fun callCyclePeer(', 'fun qualified(', 'fun fetch(): String'))
    expected = Counter((
        (1, entry, *_site(source, '    callCyclePeer(client)', 'callCyclePeer', 'fun callCyclePeer(')),
        (1, entry, *_site(source, 'return qualified(client)', 'qualified', 'fun qualified(')),
        (2, peer, *_site(source, 'String = callCycleEntry(client)', 'callCycleEntry', 'fun callCycleEntry(')),
        (2, qualified, *_site(source, 'return client.fetch()', 'fetch', 'fun fetch(): String')),
    ))
    observed = _cycle_observation(high, low)
    _emit_native_observation(observed)
    high_edges = tuple(edge for page in high for edge in _cycle_edges(page))
    low_edges = tuple(edge for page in low for edge in _cycle_edges(page))
    return {
        'cycleHighTerminalQualified': high_valid,
        'cycleLowTerminalQualified': low_valid,
        'cycleDepthTwoExactSiblingAndBackEdges': Counter(high_edges) == expected == Counter(low_edges),
        'cycleBoundedPagination': 1 < len(low) <= 12,
        'cycleQualifiedPartialRetained': _qualified_partial(high, source) and _qualified_partial(low, source),
        'cycleGrantInvariantCompilerProofs': observed.compiler_identities_equal,
        'cycleGrantInvariantFullRecords': _same_cycle_graph(high, low),
    }


def _run_extended_calls(replay, source, path):
    checks, count = _extended_call_cases(replay, source, path)
    checks.update(_cycle_checks(replay, source))
    replay.record('kotlin-call-extended-and-cycle', 'query_symbols', checks, count)


class CallObservationCase(str, Enum):
    EXPLICIT_INVOKE = 'explicit-invoke'
    IMPLICIT_INVOKE = 'implicit-invoke'
    DELEGATED = 'delegated'
    LOCAL_FUNCTION = 'local-function'
    SAM = 'sam'


class FixtureEndpoint(str, Enum):
    BASE_FETCH = 'base-fetch'
    FETCHER_INTERFACE = 'fetcher-interface'
    FETCHER_INVOKE = 'fetcher-invoke'
    DELEGATING_CLASS = 'delegating-class'
    CONCRETE_FETCH = 'concrete-fetch'
    OTHER_FIXTURE = 'other-fixture'
    OUTSIDE_FIXTURE = 'outside-fixture'
    UNAVAILABLE = 'unavailable'


class ObservedCallStatus(str, Enum):
    COMPLETE = 'complete'
    QUALIFIED = 'qualified'
    REJECTED = 'rejected'
    UNAVAILABLE = 'unavailable'


class ObservedOmissionReason(str, Enum):
    UNSUPPORTED_ITEM = 'UNSUPPORTED_ITEM'
    UNRESOLVED_TARGET = 'UNRESOLVED_TARGET'
    OTHER = 'other'


@dataclass(frozen=True)
class EndpointCount:
    endpoint: FixtureEndpoint
    count: int


@dataclass(frozen=True)
class OmissionCount:
    reason: ObservedOmissionReason
    measured: bool
    items: int
    samples: int
    samples_at_call: int
    samples_at_lambda: int


@dataclass(frozen=True)
class CallNativeObservation:
    case: CallObservationCase
    status: ObservedCallStatus
    facts: int
    exact_authored_facts: int
    endpoints: tuple[EndpointCount, ...]
    omissions: tuple[OmissionCount, ...]
    saturated: bool
    event: str = field(default='kast-authored-call-observation-v1', init=False)


@dataclass(frozen=True)
class CycleNativeObservation:
    high_pages: int
    low_pages: int
    high_edges: int
    low_edges: int
    edge_order_equal: bool
    edge_multiset_equal: bool
    compiler_identities_equal: bool
    node_selectors_equal: bool
    occurrence_selectors_equal: bool
    node_fields_equal: bool
    edge_fields_equal: bool
    event: str = field(default='kast-authored-cycle-observation-v1', init=False)


def _fixture_endpoint(target, path, source):
    if not isinstance(target.get('range'), dict):
        return FixtureEndpoint.UNAVAILABLE
    if target.get('file') != str(path):
        return FixtureEndpoint.OUTSIDE_FIXTURE
    prefixes = (
        ('fun fetch(): String', FixtureEndpoint.BASE_FETCH),
        ('fun interface Fetcher', FixtureEndpoint.FETCHER_INTERFACE),
        ('operator fun invoke()', FixtureEndpoint.FETCHER_INVOKE),
        ('class DelegatingClient', FixtureEndpoint.DELEGATING_CLASS),
        ('override fun fetch()', FixtureEndpoint.CONCRETE_FETCH),
    )
    return next((kind for prefix, kind in prefixes
                 if target['range'].get('startInclusive') == source.index(prefix)), FixtureEndpoint.OTHER_FIXTURE)


def _sample_at(samples, obligation, path):
    return sum(sample.get('file') == str(path)
               and type(sample.get('range', {}).get('startInclusive')) is int
               and type(sample.get('range', {}).get('endExclusive')) is int
               and 0 <= obligation.start - sample['range']['startInclusive'] <= 8
               and 0 <= sample['range']['endExclusive'] - obligation.end <= 64 for sample in samples)


def _call_observation(name, response, path, source):
    cases = {'explicitInvoke': CallObservationCase.EXPLICIT_INVOKE,
             'implicitInvoke': CallObservationCase.IMPLICIT_INVOKE, 'delegated': CallObservationCase.DELEGATED,
             'localFunction': CallObservationCase.LOCAL_FUNCTION, 'sam': CallObservationCase.SAM}
    fragments = {'explicitInvoke': ('fetcher.invoke()', 'invoke'),
                 'implicitInvoke': ('String = fetcher()', 'fetcher'),
                 'delegated': ('fun delegated(client: DelegatingClient): String = client.fetch()', 'fetch'),
                 'localFunction': ('return nested()', 'nested'), 'sam': ('= Fetcher {', 'Fetcher')}
    call = _obligation(source, *fragments[name], ())
    nested = _obligation(source, 'Fetcher { client.fetch()', 'fetch', ())
    facts, omissions = occurrence_facts(response), occurrence_omissions(response)
    endpoints = Counter(_fixture_endpoint(fact.get('target', {}), path, source) for fact in facts[:100])
    observed = []
    for omission in omissions[:8]:
        samples = omission.get('samples', [])[:3]
        measurement = omission.get('measurement', {})
        items = measurement.get('items')
        observed.append(OmissionCount(
            next((reason for reason in ObservedOmissionReason if reason.value == omission.get('reason')),
                 ObservedOmissionReason.OTHER),
            measurement.get('type') == 'observed_on_page',
            min(items, 100) if type(items) is int and items >= 0 else 0, len(samples),
            _sample_at(samples, call, path), _sample_at(samples, nested, path) if name == 'sam' else 0))
    return CallNativeObservation(cases[name],
        next((status for status in ObservedCallStatus if status.value == response.get('status')),
             ObservedCallStatus.UNAVAILABLE), min(len(facts), 100),
        sum(fact.get('coverage') == 'exact-compiler-confirmed' and fact.get('provenance') == 'k2-authored-source'
            for fact in facts[:100]),
        tuple(EndpointCount(kind, endpoints[kind]) for kind in FixtureEndpoint if endpoints[kind]), tuple(observed),
        len(facts) > 100 or len(omissions) > 8 or any(
            len(omission.get('samples', [])) > 3 or
            (type(omission.get('measurement', {}).get('items')) is int
             and omission['measurement']['items'] > 100) for omission in omissions[:8]))


def _cycle_components(pages):
    from hosted_resume_budget_regression import _freeze
    components = tuple(Counter() for _ in range(5))
    for page in pages:
        for key, record in zip(_cycle_edges(page), walk_records(page), strict=True):
            relation = record['relation']
            source, target = relation['source'], relation['target']
            components[0][(key, source.get('compilerEvidence', {}).get('identity'),
                           target.get('compilerEvidence', {}).get('identity'))] += 1
            components[1][(key, source.get('selector'), target.get('selector'))] += 1
            components[2][(key, relation.get('occurrence', {}).get('candidateSelector'))] += 1
            components[3][(key, _freeze({k: v for k, v in source.items() if k != 'selector'}),
                           _freeze({k: v for k, v in target.items() if k != 'selector'}))] += 1
            components[4][(key, _freeze({k: v for k, v in relation.items()
                                         if k not in ('source', 'target', 'occurrence')}),
                           _freeze({k: v for k, v in relation.get('occurrence', {}).items()
                                    if k != 'candidateSelector'}))] += 1
    return components


def _cycle_observation(high, low):
    left = tuple(edge for page in high for edge in _cycle_edges(page))
    right = tuple(edge for page in low for edge in _cycle_edges(page))
    comparisons = tuple(a == b for a, b in zip(_cycle_components(high), _cycle_components(low), strict=True))
    return CycleNativeObservation(min(len(high), 12), min(len(low), 12), min(len(left), 100), min(len(right), 100),
                                  left == right, Counter(left) == Counter(right), *comparisons)


def _emit_native_observation(observation: CallNativeObservation | CycleNativeObservation):
    """Only these fixed typed enums/counts/booleans leave the in-memory native responses."""
    import json
    import sys
    print(json.dumps(asdict(observation), separators=(',', ':')), file=sys.stderr, flush=True)


def _same_cycle_page(first, repeated):
    """The contract sorts each admitted page; replay must preserve that page's ordered records."""
    return (first.get('status') == repeated.get('status') and first.get('live') == repeated.get('live')
            and walk_records(first) == walk_records(repeated)
            and walk_observation(first) == walk_observation(repeated)
            and first.get('qualification') == repeated.get('qualification'))


def _same_cycle_graph(high, low):
    """Grant partitioning may reorder pages; preserve every exact record and its multiplicity."""
    from hosted_resume_budget_regression import _freeze
    return Counter(_freeze(record) for page in high for record in walk_records(page)) == Counter(
        _freeze(record) for page in low for record in walk_records(page))


def _inline_sites(source, name):
    start = source.index('fun ' + name + '(')
    end = source.find('\n', start)
    fragment = source[start:end if end >= 0 else len(source)]
    sites, offset = [], 0
    while (offset := fragment.find('inlineTarget()', offset)) >= 0:
        sites.append((start, start + offset, start + offset + len('inlineTarget')))
        offset += len('inlineTarget')
    return sites


def _drain_inline(replay, selector, meaning, limit):
    budget = ResultsBudget(limit)
    request = relation_query(selector, meaning.value, budget)
    pages, tokens = [], set()
    for _ in range(32):
        response = replay.transport.invoke(replay.surface, 'query_symbols', asdict(request))
        pages.append(response)
        token = response.get('continuation')
        if not token:
            return pages
        if token in tokens:
            raise ValueError('Inline query continuation did not advance')
        tokens.add(token)
        request = QueryInput(QueryResume(token, budget))
    raise ValueError('Inline query occurrence pages did not drain')


def _inline_key(fact):
    def endpoint(key):
        value = fact.get(key, {})
        return (value.get('file'), value.get('range', {}).get('startInclusive'),
                value.get('range', {}).get('endExclusive'), value.get('compilerEvidence', {}).get('identity'))
    occurrence = fact.get('occurrence', {})
    bounds = occurrence.get('range', {})
    return (endpoint('source'), endpoint('target'), occurrence.get('file'),
            bounds.get('startInclusive'), bounds.get('endExclusive'), fact.get('coverage'), fact.get('provenance'))


def run_inline_ownership_regression(replay, source, path, *, unresolved_path=None):
    """K2 acceptance oracle; Python-only tests do not establish ownership admission."""
    positive = ('stdlibInline', 'explicitInline', 'nestedInline', 'repeatedInline', 'mixedInline')
    negative = ('returnedInline', 'storedInline', 'callbackInline', 'homonymousInline',
                'noinlineBoundary', 'crossinlineBoundary', 'unsupportedOuter', 'localInline')
    target_start = source.index('fun inlineTarget(')
    target_selector, forward, expected_callers = None, [], []
    unresolved_source = unresolved_path.read_text() if unresolved_path is not None else ''
    unresolved = ('unresolvedMapping', 'ambiguousMapping') if unresolved_path is not None else ()
    for name in positive + negative + unresolved:
        occurrence_source, occurrence_path = (unresolved_source, unresolved_path) if name in unresolved else (source, path)
        discovered = replay.transport.invoke(replay.surface, 'query_symbols', asdict(name_query(name, ('function',), KotlinCallScope())))
        items = discovered.get('items', [])
        if discovered.get('status') != 'complete' or len(items) != 1 or not items[0].get('ref'):
            replay.record('inline-' + name, 'query_symbols', {'exactIssuerAvailable': False})
            continue
        high = _drain_inline(replay, items[0]['ref'], KotlinCallMeaning.CALLEES, 100)
        low = _drain_inline(replay, items[0]['ref'], KotlinCallMeaning.CALLEES, 1)
        facts = [fact for page in high for fact in occurrence_facts(page)]
        inner = [fact for fact in facts if fact.get('target', {}).get('range', {}).get('startInclusive') == target_start]
        sites = _inline_sites(occurrence_source, name)
        supported = sites[-1:] if name == 'mixedInline' else sites if name in positive else []
        expected_callers.extend(supported)
        actual = [(fact.get('source', {}).get('range', {}).get('startInclusive'),
                   fact.get('occurrence', {}).get('range', {}).get('startInclusive'),
                   fact.get('occurrence', {}).get('range', {}).get('endExclusive')) for fact in inner]
        omissions = [omission for page in high for omission in occurrence_omissions(page)]
        deferred = name in negative + unresolved or name == 'mixedInline'
        owner_reason = 'UNRESOLVED_TARGET' if name in unresolved else 'UNSUPPORTED_ITEM'
        # Local named functions retain their owner, even if no detached local endpoint is supported.
        obligation_sites = sites[:1] if deferred and name != 'localInline' else []
        checks = {
            'exactInnerOccurrenceAndOwner': Counter(actual) == Counter(supported),
            'unsupportedOwnershipEvidence': not deferred or any(omission.get('reason') == owner_reason
                and omission.get('measurement', {}).get('type') == 'observed_on_page' and omission.get('samples')
                for omission in omissions),
            'supportedSitesHaveNoOmission': all(not (sample.get('file') == str(occurrence_path)
                and sample.get('range', {}).get('startInclusive', -1) <= site[1]
                and sample.get('range', {}).get('endExclusive', -1) >= site[2])
                for site in supported for omission in omissions for sample in omission.get('samples', [])),
            'deferredSitesHaveOmissions': all(any(sample.get('file') == str(occurrence_path)
                and sample.get('range', {}).get('startInclusive', -1) <= site[1]
                and sample.get('range', {}).get('endExclusive', -1) >= site[2]
                for omission in omissions for sample in omission.get('samples', [])) for site in obligation_sites),
            'paginationPreservesExactOccurrences': Counter(map(_inline_key, facts)) == Counter(
                _inline_key(fact) for page in low for fact in occurrence_facts(page)),
            'sameAuthority': all(page.get('live') == replay.live for page in high + low),
            'qualifiedOmissions': not deferred or high[-1].get('status') == 'qualified',
            'authoredExactEvidence': all(fact.get('coverage') == 'exact-compiler-confirmed'
                and fact.get('provenance') == 'k2-authored-source'
                and fact.get('source', {}).get('qualifiedIdentity') == 'fixture.calls.' + name
                and fact.get('target', {}).get('qualifiedIdentity') == 'fixture.calls.inlineTarget'
                and all(fact.get(end, {}).get('compilerEvidence', {}).get('identity', '').startswith('canonical-signature-sha256-v1|')
                    for end in ('source', 'target')) for fact in inner),
        }
        replay.record('inline-' + name, 'query_symbols', checks, len(inner), high[-1])
        if name == 'stdlibInline':
            run_inline_composition(replay, items[0]['ref'])
        forward.extend(inner)
        if inner:
            target_selector = inner[0].get('target', {}).get('selector')
    if target_selector is None:
        discovered = replay.transport.invoke(replay.surface, 'query_symbols', asdict(name_query('inlineTarget', ('function',), KotlinCallScope())))
        items = discovered.get('items', [])
        target_selector = items[0].get('ref') if len(items) == 1 else None
    if target_selector:
        high = _drain_inline(replay, target_selector, KotlinCallMeaning.CALLERS, 100)
        low = _drain_inline(replay, target_selector, KotlinCallMeaning.CALLERS, 1)
        facts = [fact for page in high for fact in occurrence_facts(page)]
        actual = [(fact.get('source', {}).get('range', {}).get('startInclusive'),
                   fact.get('occurrence', {}).get('range', {}).get('startInclusive'),
                   fact.get('occurrence', {}).get('range', {}).get('endExclusive')) for fact in facts]
        replay.record('inline-inverse-parity', 'query_symbols', {
            'expectedOwnersAndOccurrences': Counter(actual) == Counter(expected_callers),
            'forwardInverseParity': Counter(map(_inline_key, forward)) == Counter(map(_inline_key, facts)),
            'paginationPreservesExactOccurrences': Counter(map(_inline_key, facts)) == Counter(
                _inline_key(fact) for page in low for fact in occurrence_facts(page)),
            'retainedUnsupportedEvidence': _has_scoped_unsupported([omission for page in high for omission in occurrence_omissions(page)]),
            'sameAuthority': all(page.get('live') == replay.live for page in high + low),
        }, len(facts), high[-1])


def run_inline_composition(replay, selector):
    query = replay.transport.invoke(replay.surface, 'query_symbols',
                                    asdict(QueryInput(QueryRun(SymbolReferences((selector,)),
                                        (ExpandRelation('callees'), ExpandRelation('callees')),
                                        SymbolOutput(('name', 'location', 'signature')), CallPageBudget(100)))))
    items = query.get('items', [])
    connections = [fact for item in items for fact in item.get('connections', [])]
    chain = Counter((fact.get('source', {}).get('qualifiedIdentity'), fact.get('target', {}).get('qualifiedIdentity'))
                    for fact in connections)
    expected = Counter((('fixture.calls.stdlibInline', 'fixture.calls.inlineTarget'),
                        ('fixture.calls.inlineTarget', 'fixture.calls.inlineLeaf')))
    traversal = replay.transport.invoke(replay.surface, 'query_symbols',
                                        asdict(cycle_query(selector, CallPageBudget(100))))
    records = walk_records(traversal)
    observation = walk_observation(traversal)
    replay.record('inline-two-hop-composition', 'query_symbols', {
        'completeQuery': query.get('status') == 'complete',
        'exactLeaf': len(items) == 1 and items[0].get('signature', {}).get('qualifiedIdentity') == 'fixture.calls.inlineLeaf',
        'bothConnections': chain == expected,
        'bothTraversalEdges': Counter((record['relation']['source'].get('qualifiedIdentity'),
                                      record['relation']['target'].get('qualifiedIdentity')) for record in records) == expected,
        'witnessedDepthTwo': observation.get('progress', {}).get('maximumDepthReached') == 2,
        'retainedDepthCutoff': observation.get('coverage', {}).get('limitations') == ['depth-limit-reached'],
        'sameAuthority': query.get('live') == traversal.get('live') == replay.live,
    }, len(items), query)
