"""Installed enum-entry admission and member traversal under the production budget policy."""
from collections import Counter
from dataclasses import asdict, dataclass, field


@dataclass(frozen=True)
class EnumScope:
    package_name: str = field(default='reliability.discovery', init=False)
    include_subpackages: bool = field(default=False, init=False)
    source_set_names: tuple[str, ...] = field(default=('main',), init=False)


@dataclass(frozen=True)
class EnumClassSearch:
    class_name: str
    name_match: str
    scope: EnumScope = field(default_factory=EnumScope, init=False)


@dataclass(frozen=True)
class EnumFunctionSearch:
    function_name: str = field(default='act', init=False)
    name_match: str = field(default='exact', init=False)
    scope: EnumScope = field(default_factory=EnumScope, init=False)


@dataclass(frozen=True)
class AllEnumClasses:
    type: str = field(default='all_declarations', init=False)
    declaration_kinds: tuple[str, ...] = field(default=('class',), init=False)
    scope: EnumScope = field(default_factory=EnumScope, init=False)


@dataclass(frozen=True)
class EnumClassEnumeration:
    source: AllEnumClasses = field(default_factory=AllEnumClasses, init=False)
    steps: None = field(default=None, init=False)
    return_fields: None = field(default=None, init=False)


@dataclass(frozen=True)
class EnumMemberReferences:
    symbol_refs: tuple[str, ...]
    type: str = field(default='symbol_refs', init=False)


@dataclass(frozen=True)
class EnumMemberRoundtrip:
    source: EnumMemberReferences
    steps: None = field(default=None, init=False)
    return_fields: tuple[str, ...] = field(default=('name', 'signature'), init=False)


def run_enum_read_regression(replay):
    cases = (
        ('enum-entry-exact-exclusion', 'search_classes', EnumClassSearch('ACTIVE', 'exact'), ()),
        ('enum-entry-fuzzy-exclusion', 'search_classes', EnumClassSearch('Mode', 'fuzzy'), ('Mode',)),
        ('enum-entry-scoped-all-exclusion', 'query_symbols', EnumClassEnumeration(), ('Mode', 'Nested', 'Ordinary')),
        ('enum-entry-body-members', 'search_functions', EnumFunctionSearch(), ('act', 'act')),
    )
    for name, tool, request, expected in cases:
        response = replay.transport.invoke(replay.surface, tool, asdict(request))
        items = response.get('items', [])
        references = [item.get('symbol_ref') for item in items]
        replay.record(name, tool, {
            'complete': response.get('status') == 'complete',
            'exactEligibleNames': Counter(item.get('name') for item in items) == Counter(expected),
            'allCandidatesRefined': response.get('failures') == [] and all(references),
            'distinctDeclarations': len(set(references)) == len(expected),
            'sameLiveAuthority': response.get('live') == replay.live,
        }, len(items), response)

    _roundtrip_members(replay, response)


def _roundtrip_members(replay, discovery):
    items = discovery.get('items', [])
    references = tuple(item.get('symbol_ref') for item in items)
    if len(references) != 2 or not all(isinstance(value, str) and value for value in references):
        replay.record('enum-entry-member-reference-reuse', 'query_symbols', {'issuerAvailable': False})
        return
    response = replay.transport.invoke(replay.surface, 'query_symbols',
        asdict(EnumMemberRoundtrip(EnumMemberReferences(references))))
    exact = response.get('items', [])
    replay.record('enum-entry-member-reference-reuse', 'query_symbols', {
        'complete': response.get('status') == 'complete',
        'sameDeclarations': all(item.get('symbol_id') for item in items) and
            [item.get('symbol_id') for item in exact] == [item.get('symbol_id') for item in items],
        'sameReferences': tuple(item.get('symbol_ref') for item in exact) == references,
        'exactEligibleNames': [item.get('name') for item in exact] == ['act', 'act'],
        'allReferencesRestored': response.get('failures') == [] and len(exact) == 2,
        'sameLiveAuthority': response.get('live') == replay.live,
        'signaturesPreserved': all(item.get('signature') for item in items) and
            [item.get('signature') for item in exact] == [item.get('signature') for item in items],
    }, len(exact), response)
