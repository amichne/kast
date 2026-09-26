"""Typed public declaration-name query used by installed acceptance fixtures."""
from dataclasses import dataclass, field
from typing import Generic, Literal, TypeVar


Source = TypeVar('Source')


@dataclass(frozen=True)
class NameSource:
    declaration_name: str
    name_match: str
    declaration_kinds: tuple[str, ...] | None
    scope: object | None
    type: str = field(default='search_declarations', init=False)


@dataclass(frozen=True)
class QueryRun(Generic[Source]):
    source: Source
    steps: tuple[object, ...] | None = None
    return_fields: tuple[str, ...] | None = ('name', 'location', 'signature')
    execution_budget: object | None = None
    action: Literal['run'] = field(default='run', init=False)


@dataclass(frozen=True)
class QueryInput(Generic[Source]):
    request: QueryRun[Source]


def name_query(name, kinds=None, scope=None, match='exact', budget=None):
    return QueryInput(QueryRun(NameSource(name, match, tuple(kinds) if kinds is not None else None, scope),
                               execution_budget=budget))
