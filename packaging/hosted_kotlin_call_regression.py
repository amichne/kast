"""Exact authored Kotlin call occurrences; no runtime dispatch is inferred."""
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
                (status == 'qualified' and any(item.get('reason') == 'unsupported-item'
                    and item.get('samples') for item in omissions)),
            'sameLiveAuthority': response.get('live') == replay.live,
        }, len(facts), response)
        if name == 'outer':
            inherited = [fact for fact in facts
                         if fact.get('target', {}).get('range', {}).get('startInclusive') == source.index(base)]
            if len(inherited) == 1 and inherited[0].get('target', {}).get('selector'):
                _inherited_callers(replay, source, inherited[0]['target']['selector'])
            else:
                replay.record('kotlin-call-inherited-callers', 'read_relations', {'exactIssuerAvailable': False})


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
