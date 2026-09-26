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
class TraversalRecordOutput:
    type: Literal['traversal_records'] = field(default='traversal_records', init=False)


@dataclass(frozen=True)
class BreadthFirstWalk:
    type: Literal['breadth_first'] = field(default='breadth_first', init=False)


@dataclass(frozen=True)
class Walk:
    relation: str
    maximum_depth: int
    strategy: BreadthFirstWalk = field(default_factory=BreadthFirstWalk)
    type: Literal['walk'] = field(default='walk', init=False)


@dataclass(frozen=True)
class ExpandRelation:
    relation: str
    type: Literal['expand_relation'] = field(default='expand_relation', init=False)


@dataclass(frozen=True)
class QueryRun(Generic[Source]):
    source: Source
    steps: tuple[object, ...] | None = None
    output: SymbolOutput | OccurrenceOutput | TraversalRecordOutput | None = None
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


def walk_query(reference, relation='callers', maximum_depth=4, budget=None):
    return QueryInput(QueryRun(SymbolReferences((reference,)), (Walk(relation, maximum_depth),),
                               TraversalRecordOutput(), budget))


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


def walk_records(response):
    items = response.get('items', [])
    if any(item.get('type') != 'traversal_record' for item in items):
        raise ValueError('query traversal output contained a non-traversal item')
    return [item['record'] for item in items]


def walk_observation(response):
    observations = response.get('walk_observations', [])
    if len(observations) != 1:
        raise ValueError('one query walk must retain one traversal observation')
    return observations[0]
