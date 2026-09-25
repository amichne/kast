"""Typed public declaration-name query used by installed acceptance fixtures."""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class NameSource:
    declaration_name: str
    name_match: str
    declaration_kinds: tuple[str, ...] | None
    scope: object | None
    type: str = field(default='search_declarations', init=False)


@dataclass(frozen=True)
class NameQuery:
    source: NameSource
    steps: None = None
    return_fields: tuple[str, ...] = ('name', 'location', 'signature')
    execution_budget: object | None = None


def name_query(name, kinds=None, scope=None, match='exact', budget=None):
    return NameQuery(NameSource(name, match, tuple(kinds) if kinds is not None else None, scope),
                     execution_budget=budget)
