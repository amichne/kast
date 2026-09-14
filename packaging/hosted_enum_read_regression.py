"""Installed enum-entry admission and member traversal with bounded candidate capacity."""
from collections import Counter
from dataclasses import asdict, dataclass, field


@dataclass(frozen=True)
class EnumScope:
    package_name: str = field(default='reliability.discovery', init=False)
    include_subpackages: bool = field(default=False, init=False)
    source_set_names: tuple[str, ...] = field(default=('main',), init=False)


@dataclass(frozen=True)
class EnumBudget:
    max_elapsed_ms: int = field(default=5000, init=False)
    max_work_units: int = field(default=32, init=False)
    max_results: int = field(default=8, init=False)
    max_returned_bytes: int = field(default=65536, init=False)


@dataclass(frozen=True)
class EnumClassSearch:
    class_name: str
    name_match: str
    scope: EnumScope = field(default_factory=EnumScope, init=False)
    execution_budget: EnumBudget = field(default_factory=EnumBudget, init=False)


@dataclass(frozen=True)
class EnumFunctionSearch:
    function_name: str = field(default='act', init=False)
    name_match: str = field(default='exact', init=False)
    scope: EnumScope = field(default_factory=EnumScope, init=False)
    execution_budget: EnumBudget = field(default_factory=EnumBudget, init=False)


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
    execution_budget: EnumBudget = field(default_factory=EnumBudget, init=False)


def run_enum_read_regression(replay):
    cases = (
        ('enum-entry-exact-exclusion', 'search_classes', EnumClassSearch('ACTIVE', 'exact'), ()),
        ('enum-entry-fuzzy-exclusion', 'search_classes', EnumClassSearch('Mode', 'fuzzy'), ('Mode',)),
        ('enum-entry-scoped-all-capacity', 'query_symbols', EnumClassEnumeration(), ('Mode', 'Nested', 'Ordinary')),
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
            'boundedWorkRetained': response.get('execution_budget', {}).get('max_work_units', {}).get('effective') == 32,
        }, len(items), response)
