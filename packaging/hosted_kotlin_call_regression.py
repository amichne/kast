"""Exact authored Kotlin call occurrences; no runtime dispatch is inferred."""
from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass, field
from enum import Enum


@dataclass(frozen=True)
class KotlinCallScope:
    package_name: str = field(default='fixture.calls', init=False)
    include_subpackages: bool = field(default=False, init=False)
    source_set_names: tuple[str, ...] = field(default=('main',), init=False)


@dataclass(frozen=True)
class KotlinCallSearch:
    function_name: str
    name_match: str = field(default='exact', init=False)
    scope: KotlinCallScope = field(default_factory=KotlinCallScope, init=False)


@dataclass(frozen=True)
class CallStart:
    type: str = field(default='start', init=False)


class KotlinCallMeaning(str, Enum):
    CALLEES = 'callees'
    CALLERS = 'callers'


@dataclass(frozen=True)
class KotlinCallRead:
    exactSelector: str
    relation: KotlinCallMeaning = KotlinCallMeaning.CALLEES
    limit: int = field(default=100, init=False)
    position: CallStart = field(default_factory=CallStart, init=False)


def _site(source, fragment, name, declaration):
    start = source.index(fragment) + fragment.index(name)
    return (source.index(declaration), start, start + len(name))


def _facts(response):
    return Counter((fact.get('target', {}).get('range', {}).get('startInclusive'),
                    fact.get('occurrence', {}).get('range', {}).get('startInclusive'),
                    fact.get('occurrence', {}).get('range', {}).get('endExclusive'))
                   for fact in response.get('relations', []))


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
        discovery = replay.transport.invoke(replay.surface, 'search_functions', asdict(KotlinCallSearch(name)))
        items = discovery.get('items', [])
        if discovery.get('status') != 'complete' or len(items) != 1 or not items[0].get('ref'):
            replay.record('kotlin-call-' + name, 'search_functions', {'exactIssuerAvailable': False},
                          len(items), discovery)
            continue
        response = replay.transport.invoke(replay.surface, 'read_relations',
                                           asdict(KotlinCallRead(items[0]['ref'])))
        facts = response.get('relations', [])
        omissions = response.get('omissions', [])
        replay.record('kotlin-call-' + name, 'read_relations', {
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
                replay.record('kotlin-call-inherited-callers', 'read_relations', {'exactIssuerAvailable': False})

    _run_extended_calls(replay, source, path)

def _inherited_callers(replay, source, reference):
    response = replay.transport.invoke(replay.surface, 'read_relations',
                                       asdict(KotlinCallRead(reference, KotlinCallMeaning.CALLERS)))
    expected = Counter((
        _site(source, 'val value = client.fetch()', 'fetch', 'fun outer('),
        _site(source, 'client.fetch() + client.fetch()', 'fetch', 'fun repeated('),
        _site(source, '+ client.fetch()', 'fetch', 'fun repeated('),
        _site(source, 'return client.fetch()', 'fetch', 'fun qualified('),
    ))
    facts = response.get('relations', [])
    actual = Counter((fact.get('source', {}).get('range', {}).get('startInclusive'),
                      fact.get('occurrence', {}).get('range', {}).get('startInclusive'),
                      fact.get('occurrence', {}).get('range', {}).get('endExclusive')) for fact in facts)
    replay.record('kotlin-call-inherited-callers', 'read_relations', {
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


@dataclass(frozen=True)
class CallCycleTraversal:
    exactSelector: str
    execution_budget: CallPageBudget
    position: CallStart | CallResume = field(default_factory=CallStart)
    relation: KotlinCallMeaning = field(default=KotlinCallMeaning.CALLEES, init=False)
    maximumDepth: int = field(default=2, init=False)
    maximumResults: int = field(default=100, init=False)
    strategy: 'CallBreadthFirst' = field(default_factory=lambda: CallBreadthFirst(), init=False)


@dataclass(frozen=True)
class CallResume:
    continuation: str
    type: str = field(default='resume', init=False)


@dataclass(frozen=True)
class CallBreadthFirst:
    type: str = field(default='breadth_first', init=False)


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
    omissions = response.get('omissions', [])
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
    """Unavailable synthetic targets are qualified emptiness, never successful absence."""
    checks, count = {}, 0
    unsupported = ('UNSUPPORTED_ITEM',)
    synthetic = ('UNRESOLVED_TARGET', 'UNSUPPORTED_ITEM')
    cases = (
        ('explicitInvoke', (_site(source, 'fetcher.invoke()', 'invoke', 'operator fun invoke()'),), ()),
        ('implicitInvoke', (), (_obligation(source, 'String = fetcher()', 'fetcher', unsupported),)),
        ('delegated', (), (_obligation(source, 'fun delegated(client: DelegatingClient): String = client.fetch()', 'fetch', synthetic),)),
        ('localFunction', (), (_obligation(source, 'return nested()', 'nested', unsupported),)),
        ('sam', (), (
            _obligation(source, '= Fetcher {', 'Fetcher', synthetic),
            _obligation(source, 'Fetcher { client.fetch()', 'fetch', unsupported))),
    )
    for name, expected, obligations in cases:
        discovery = replay.transport.invoke(replay.surface, 'search_functions', asdict(KotlinCallSearch(name)))
        items = discovery.get('items', [])
        admitted = discovery.get('status') == 'complete' and len(items) == 1 and bool(items[0].get('ref'))
        checks[name + 'Issuer'] = admitted
        if not admitted:
            continue
        response = replay.transport.invoke(replay.surface, 'read_relations', asdict(KotlinCallRead(items[0]['ref'])))
        count += len(response.get('relations', []))
        checks[name + 'ExactStaticFacts'] = _facts(response) == Counter(expected)
        checks[name + 'Coverage'] = (_scoped_obligations(response, path, obligations) if obligations else
            response.get('status') == 'complete' and response.get('omissions') == [])
        checks[name + 'Authority'] = response.get('live') == replay.live
    return checks, count


def _cycle_edges(response):
    graph = response.get('graph', {})
    nodes = {node.get('id'): node for node in graph.get('nodes', [])}
    return tuple((edge.get('depth'), nodes.get(edge.get('source'), {}).get('range', {}).get('startInclusive'),
                  nodes.get(edge.get('target'), {}).get('range', {}).get('startInclusive'),
                  edge.get('occurrence', {}).get('range', {}).get('startInclusive'),
                  edge.get('occurrence', {}).get('range', {}).get('endExclusive'))
                 for edge in graph.get('edges', []))


def _drain_call_cycle(replay, request):
    from dataclasses import replace
    from hosted_budget_read_regression import traversal_checkpoint, progress_advances
    pages, seen = [], set()
    for _ in range(12):
        response = replay.transport.invoke(replay.surface, 'traverse_relations', asdict(request))
        previous = pages[-1].get('progress', {}) if pages else {}
        pages.append(response)
        if (response.get('live') != replay.live or not progress_advances(previous, response.get('progress', {}))
                or len(response.get('graph', {}).get('edges', [])) > request.execution_budget.max_results):
            return pages, False
        qualification = response.get('qualification', {})
        if response.get('status') == 'qualified' and qualification.get('type') == 'terminal_incomplete':
            return pages, (set(qualification.get('relationLimitations', [])) == {'unsupported-item'}
                           and 'one-hop-incomplete' in qualification.get('limitations', []))
        checkpoint = traversal_checkpoint(response)
        if checkpoint is None or checkpoint['token'] in seen:
            return pages, False
        seen.add(checkpoint['token'])
        request = replace(request, position=CallResume(checkpoint['token']))
    return pages, False


def _qualified_partial(pages, source):
    selectors = {node.get('selector') for page in pages for node in page.get('graph', {}).get('nodes', [])
                 if node.get('range', {}).get('startInclusive') == source.index('fun qualified(')}
    return any(partial.get('subject') in selectors and partial.get('depth') == 1
               and partial.get('scope') == 'page' and partial.get('remainder') == 'not_explored'
               and partial.get('limitations') == ['unsupported-item']
               for page in pages for partial in page.get('partialExpansions', []))


def _cycle_checks(replay, source):
    from hosted_budget_read_regression import graph_records
    discovery = replay.transport.invoke(replay.surface, 'search_functions', asdict(KotlinCallSearch('callCycleEntry')))
    items = discovery.get('items', [])
    if discovery.get('status') != 'complete' or len(items) != 1 or not items[0].get('ref'):
        return {'cycleIssuer': False}
    token = items[0]['ref']
    high, high_valid = _drain_call_cycle(replay, CallCycleTraversal(token, CallPageBudget(100)))
    low, low_valid = _drain_call_cycle(replay, CallCycleTraversal(token, CallPageBudget(1)))
    entry, peer, qualified, base = (source.index(prefix) for prefix in
        ('fun callCycleEntry(', 'fun callCyclePeer(', 'fun qualified(', 'fun fetch(): String'))
    expected = Counter((
        (1, entry, *_site(source, '    callCyclePeer(client)', 'callCyclePeer', 'fun callCyclePeer(')),
        (1, entry, *_site(source, 'return qualified(client)', 'qualified', 'fun qualified(')),
        (2, peer, *_site(source, 'String = callCycleEntry(client)', 'callCycleEntry', 'fun callCycleEntry(')),
        (2, qualified, *_site(source, 'return client.fetch()', 'fetch', 'fun fetch(): String')),
    ))
    high_edges = tuple(edge for page in high for edge in _cycle_edges(page))
    low_edges = tuple(edge for page in low for edge in _cycle_edges(page))
    return {
        'cycleHighTerminalQualified': high_valid,
        'cycleLowTerminalQualified': low_valid,
        'cycleDepthTwoExactSiblingAndBackEdges': Counter(high_edges) == expected == Counter(low_edges),
        'cycleBoundedPagination': 1 < len(low) <= 12,
        'cycleQualifiedPartialRetained': _qualified_partial(high, source) and _qualified_partial(low, source),
        'cycleGrantInvariantCompilerProofs': tuple(record for page in high for record in graph_records(page)) ==
            tuple(record for page in low for record in graph_records(page)),
    }


def _run_extended_calls(replay, source, path):
    checks, count = _extended_call_cases(replay, source, path)
    checks.update(_cycle_checks(replay, source))
    replay.record('kotlin-call-extended-and-cycle', 'read_relations+traverse_relations', checks, count)
