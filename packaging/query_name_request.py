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
class SymbolReferences:
    symbol_refs: tuple[str, ...]
    type: Literal['symbol_refs'] = field(default='symbol_refs', init=False)


@dataclass(frozen=True)
class SymbolOutput:
    fields: tuple[str, ...]
    type: Literal['symbols'] = field(default='symbols', init=False)


@dataclass(frozen=True)
class OccurrenceOutput:
    type: Literal['occurrences'] = field(default='occurrences', init=False)


@dataclass(frozen=True)
class ExpandRelation:
    relation: str
    type: Literal['expand_relation'] = field(default='expand_relation', init=False)


@dataclass(frozen=True)
class QueryRun(Generic[Source]):
    source: Source
    steps: tuple[object, ...] | None = None
    output: SymbolOutput | OccurrenceOutput | None = None
    execution_budget: object | None = None
    action: Literal['run'] = field(default='run', init=False)


@dataclass(frozen=True)
class QueryResume:
    continuation: str
    execution_budget: object | None = None
    action: Literal['resume'] = field(default='resume', init=False)


@dataclass(frozen=True)
class QueryInput(Generic[Source]):
    request: QueryRun[Source] | QueryResume


def name_query(name, kinds=None, scope=None, match='exact', budget=None):
    return QueryInput(QueryRun(NameSource(name, match, tuple(kinds) if kinds is not None else None, scope),
                               output=SymbolOutput(('name', 'location', 'signature')), execution_budget=budget))


def relation_query(reference, relation='callees', budget=None):
    return QueryInput(QueryRun(SymbolReferences((reference,)), (ExpandRelation(relation),),
                               OccurrenceOutput(), budget))


def occurrence_facts(response):
    items = response.get('items', [])
    if any(item.get('type') != 'occurrence' for item in items):
        raise ValueError('query occurrence output contained a non-occurrence item')
    return [item['relation'] for item in items]


def occurrence_omissions(response):
    omissions = response.get('omissions', [])
    if any(not item.get('subject') or not item.get('relation') for item in omissions):
        raise ValueError('query occurrence omission lacked subject or relation')
    return [item['evidence'] for item in omissions]
