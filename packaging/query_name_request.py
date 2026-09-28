"""Typed public declaration-name query used by installed acceptance fixtures."""
from dataclasses import dataclass, field
from typing import Generic, Literal, TypeVar


Source = TypeVar('Source')


@dataclass(frozen=True)
class NameSource:
    declarationName: str
    nameMatch: str
    declarationKinds: tuple[str, ...] | None
    scope: object | None
    type: str = field(default='SEARCH_DECLARATIONS', init=False)


@dataclass(frozen=True)
class SymbolReferences:
    symbolRefs: tuple[str, ...]
    type: Literal['SYMBOL_REFS'] = field(default='SYMBOL_REFS', init=False)


@dataclass(frozen=True)
class SymbolOutput:
    fields: tuple[str, ...]
    type: Literal['SYMBOLS'] = field(default='SYMBOLS', init=False)


@dataclass(frozen=True)
class OccurrenceOutput:
    type: Literal['OCCURRENCES'] = field(default='OCCURRENCES', init=False)


@dataclass(frozen=True)
class TraversalRecordOutput:
    type: Literal['TRAVERSAL_RECORDS'] = field(default='TRAVERSAL_RECORDS', init=False)


@dataclass(frozen=True)
class BreadthFirstWalk:
    type: Literal['BREADTH_FIRST'] = field(default='BREADTH_FIRST', init=False)


@dataclass(frozen=True)
class Walk:
    relation: str
    maximumDepth: int
    strategy: BreadthFirstWalk = field(default_factory=BreadthFirstWalk)
    type: Literal['WALK'] = field(default='WALK', init=False)


@dataclass(frozen=True)
class ExpandRelation:
    relation: str
    type: Literal['EXPAND_RELATION'] = field(default='EXPAND_RELATION', init=False)


@dataclass(frozen=True)
class QueryRun(Generic[Source]):
    source: Source
    steps: tuple[object, ...] | None = None
    output: SymbolOutput | OccurrenceOutput | TraversalRecordOutput | None = None
    executionBudget: object | None = None
    type: Literal['RUN'] = field(default='RUN', init=False)


@dataclass(frozen=True)
class QueryResume:
    continuation: str
    executionBudget: object | None = None
    type: Literal['RESUME'] = field(default='RESUME', init=False)


@dataclass(frozen=True)
class QueryInput(Generic[Source]):
    request: QueryRun[Source] | QueryResume


def name_query(name, kinds=None, scope=None, match='exact', budget=None):
    return QueryInput(QueryRun(NameSource(name, match.upper(), tuple(kind.upper() for kind in kinds) if kinds is not None else None, scope),
                               output=SymbolOutput(('NAME', 'LOCATION', 'SIGNATURE')), executionBudget=budget))


def relation_query(reference, relation='callees', budget=None):
    return QueryInput(QueryRun(SymbolReferences((reference,)), (ExpandRelation(relation.upper()),),
                               OccurrenceOutput(), budget))


def walk_query(reference, relation='callers', maximum_depth=4, budget=None):
    return QueryInput(QueryRun(SymbolReferences((reference,)), (Walk(relation.upper(), maximum_depth),),
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
