"""Typed fixed requests for the replay comparison corpus; public projection stays at this boundary."""
from dataclasses import asdict, dataclass, field
from enum import Enum


class WorkloadProfile(str, Enum):
    RELIABILITY_FIXTURE = 'RELIABILITY_FIXTURE'
    KAST_SOURCE = 'KAST_SOURCE'


class Workload(str, Enum):
    EXACT_SOURCE = 'exact-source'
    EXACT_NEGATIVE = 'exact-negative'
    DENSE_REFERENCES = 'dense-references'
    SCOPED_ALL = 'scoped-all'


class DeclarationKind(str, Enum):
    CLASS = 'CLASS'
    FUNCTION = 'FUNCTION'


class SymbolField(str, Enum):
    NAME = 'NAME'
    LOCATION = 'LOCATION'
    SIGNATURE = 'SIGNATURE'
    SOURCE = 'SOURCE'


@dataclass(frozen=True)
class ExecutionBudget:
    maxElapsedMs: int
    maxWorkUnits: int = 100000
    maxResults: int = 20
    maxReturnedBytes: int = 49152


@dataclass(frozen=True)
class DirectoryScope:
    relativeDirectoryPath: str
    sourceSetNames: list[str] = field(default_factory=lambda: ['main'])
    includeSubdirectories: bool = True
    type: str = field(default='DIRECTORY', init=False)


@dataclass(frozen=True)
class ExactDeclarationSearch:
    declarationName: str
    scope: DirectoryScope
    declarationKinds: list[DeclarationKind] = field(default_factory=lambda: [DeclarationKind.CLASS])
    nameMatch: str = field(default='EXACT', init=False)
    type: str = field(default='SEARCH_DECLARATIONS', init=False)


@dataclass(frozen=True)
class AllFunctions:
    scope: DirectoryScope
    declarationKinds: list[DeclarationKind] = field(default_factory=lambda: [DeclarationKind.FUNCTION])
    type: str = field(default='ALL_DECLARATIONS', init=False)


@dataclass(frozen=True)
class References:
    relation: str = field(default='REFERENCES', init=False)
    type: str = field(default='EXPAND_RELATION', init=False)


@dataclass(frozen=True)
class PublicVisibility:
    values: list[str] = field(default_factory=lambda: ['PUBLIC'])
    type: str = field(default='VISIBILITY', init=False)


@dataclass(frozen=True)
class WherePublic:
    predicate: PublicVisibility = field(default_factory=PublicVisibility)
    type: str = field(default='WHERE', init=False)


@dataclass(frozen=True)
class Symbols:
    fields: list[SymbolField]
    type: str = field(default='SYMBOLS', init=False)


@dataclass(frozen=True)
class Occurrences:
    type: str = field(default='OCCURRENCES', init=False)


@dataclass(frozen=True)
class Run:
    source: ExactDeclarationSearch | AllFunctions
    steps: list[References | WherePublic]
    output: Symbols | Occurrences
    executionBudget: ExecutionBudget
    type: str = field(default='RUN', init=False)


@dataclass(frozen=True)
class Resume:
    continuation: str
    executionBudget: ExecutionBudget
    type: str = field(default='RESUME', init=False)


@dataclass(frozen=True)
class ReadResult:
    result: str
    cursor: int
    output: Symbols | Occurrences
    executionBudget: ExecutionBudget
    evidence_cursor: int
    type: str = field(default='READ_RESULT', init=False)


@dataclass(frozen=True)
class Query:
    request: Run | Resume | ReadResult
    verbose: bool = True


KAST_DIRECTORY = 'query/contract/src/main/kotlin'
KAST_SCOPED_DIRECTORY = 'kernel/src/main/kotlin'
NEGATIVE_DECLARATION_NAME = 'KastReplayNegativeDeclaration9C22D9'


def budget(profile):
    if not isinstance(profile, WorkloadProfile): raise ValueError('UNSUPPORTED_WORKLOAD_PROFILE')
    return ExecutionBudget(10000 if profile == WorkloadProfile.KAST_SOURCE else 2000)


def comparison_requests(profile=WorkloadProfile.RELIABILITY_FIXTURE):
    grant = budget(profile)
    production = profile == WorkloadProfile.KAST_SOURCE
    selected = DirectoryScope(KAST_DIRECTORY if production else 'logging/src/main/kotlin')
    functions = DirectoryScope(KAST_SCOPED_DIRECTORY) if production else selected
    fields = [SymbolField.NAME, SymbolField.LOCATION, SymbolField.SIGNATURE]
    requests = {
        Workload.EXACT_SOURCE: Run(ExactDeclarationSearch('QueryPlanSyntax' if production else 'FixtureLogger', selected),
                                  [], Symbols([*fields, SymbolField.SOURCE]), grant),
        Workload.EXACT_NEGATIVE: Run(ExactDeclarationSearch(NEGATIVE_DECLARATION_NAME, selected),
                                    [], Symbols(fields.copy()), grant),
        Workload.DENSE_REFERENCES: Run(ExactDeclarationSearch('QueryStepSyntax' if production else 'DenseReferenceTarget', selected),
                                      [References()], Occurrences(), grant),
        Workload.SCOPED_ALL: Run(AllFunctions(functions), [WherePublic()], Symbols(fields.copy()), grant),
    }
    return {workload.value: asdict(Query(request)) for workload, request in requests.items()}
