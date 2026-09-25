"""Source paging regression through the installed operation; no source payload enters its receipt."""
from dataclasses import asdict, dataclass, field
from enum import Enum


class SourceShape(str, Enum):
    SYMBOL = 'symbol'
    FILE = 'file'
    MATCHING = 'matching'
    DECLARATION = 'declaration'
    ANY = 'any'
    NONE = 'none'
    FIRST = 'first'


@dataclass(frozen=True)
class SymbolAnchor:
    selector: str
    type: SourceShape = field(default=SourceShape.SYMBOL, init=False)


@dataclass(frozen=True)
class FileRegion:
    type: SourceShape = field(default=SourceShape.FILE, init=False)


@dataclass(frozen=True)
class AnyVisibility:
    type: SourceShape = field(default=SourceShape.ANY, init=False)


@dataclass(frozen=True)
class FunctionFilter:
    type: SourceShape = field(default=SourceShape.DECLARATION, init=False)
    kinds: tuple[str, ...] = field(default=('function',), init=False)
    visibility: AnyVisibility = field(default_factory=AnyVisibility, init=False)


@dataclass(frozen=True)
class DescendantFunctions:
    type: SourceShape = field(default=SourceShape.MATCHING, init=False)
    containment: str = field(default='descendants', init=False)
    filters: tuple[FunctionFilter, ...] = field(default_factory=lambda: (FunctionFilter(),), init=False)


@dataclass(frozen=True)
class NoText:
    type: SourceShape = field(default=SourceShape.NONE, init=False)


@dataclass(frozen=True)
class FirstPage:
    type: SourceShape = field(default=SourceShape.FIRST, init=False)


@dataclass(frozen=True)
class SourceExecutionBudget:
    max_elapsed_ms: int = 5000
    max_work_units: int = 100
    max_results: int = 1
    max_returned_bytes: int = 65536


@dataclass(frozen=True)
class SourceFunctionRequest:
    anchor: SymbolAnchor
    region: FileRegion = field(default_factory=FileRegion)
    entities: DescendantFunctions = field(default_factory=DescendantFunctions)
    text: NoText = field(default_factory=NoText)
    entityLimit: int = 1
    textByteLimit: int = 65536
    page: FirstPage = field(default_factory=FirstPage)
    execution_budget: SourceExecutionBudget = field(default_factory=SourceExecutionBudget)


def source_budget_anchor_query():
    from query_name_request import name_query
    return name_query('ReadPageBudget', ('class',))


def run_source_paging_regression(replay):
    _check_page(replay, 'source-stop-at-eligible-page', replay.seeds['logger']['ref'], 'loggerFunction')
    response = replay.transport.invoke(replay.surface, 'query_symbols', asdict(source_budget_anchor_query()))
    items = response.get('items', [])
    admitted = response.get('status') == 'complete' and len(items) == 1 and bool(items[0].get('ref'))
    replay.record('source-budget-fixture-anchor', 'query_symbols', {
        'exactAnchor': admitted, 'sameLiveAuthority': response.get('live') == replay.live,
    }, len(items), response)
    if admitted:
        _check_page(replay, 'source-stop-before-large-tail', items[0]['ref'], 'pageItem00')


def _check_page(replay, name, selector, expected_name):
    request = SourceFunctionRequest(SymbolAnchor(selector))
    response = replay.transport.invoke(replay.surface, 'source_read', asdict(request))
    qualification = response.get('qualification', {})
    entities = response.get('entities', [])
    continuation = qualification.get('continuation', {})
    progress = qualification.get('progress', {})
    replay.record(name, 'source_read', {
        'qualified': response.get('status') == 'qualified',
        'exactFirstEntity': [entity.get('name') for entity in entities] == [expected_name],
        'entityLimitOnly': qualification.get('limitations') == ['entity-limit-reached'],
        'upstreamContinuation': continuation.get('type') == 'available' and bool(continuation.get('continuation')),
        'explicitNativeProgress': (progress.get('type') == 'resumable'
            and progress.get('checkpoint', {}).get('type') == 'upstream'
            and progress.get('next_action') == 'resume'),
        'sameLiveAuthority': response.get('live') == replay.live,
        'effectiveWorkRetained': response.get('execution_budget', {}).get('max_work_units', {}).get('effective') == 100,
    }, len(entities), response)


class SourceLimitation(str, Enum):
    ENTITY_LIMIT = 'entity-limit-reached'
    TEXT_BYTES = 'text-byte-limit-reached'
    RETURNED_BYTES = 'returned-byte-limit-reached'
    WORK = 'work-limit-reached'
    TIME = 'time-limit-reached'
    DUMB_MODE = 'dumb-mode-transition'
    RESOLUTION = 'semantic-resolution-incomplete'
    UNSUPPORTED = 'unsupported-entity'
    PROVIDER = 'provider-failure'


class SourceContinuationKind(str, Enum):
    AVAILABLE = 'available'
    UNAVAILABLE = 'unavailable'


class SourceProgressKind(str, Enum):
    RESUMABLE = 'resumable'
    TERMINAL = 'terminal_incomplete'


@dataclass(frozen=True)
class ObservedSourceQualification:
    limitations: tuple[SourceLimitation, ...]
    continuation: SourceContinuationKind
    knownMinimumEntityCount: int
    progress: SourceProgressKind
    outcome: str = field(default='observed', init=False)


@dataclass(frozen=True)
class UnrecognizedSourceQualification:
    outcome: str = field(default='unrecognized', init=False)


def source_qualification_observation(qualification):
    try:
        if set(qualification) != {'limitations', 'continuation', 'knownMinimumEntityCount', 'progress'}:
            raise ValueError('SOURCE_QUALIFICATION_SHAPE')
        limits, continuation = qualification['limitations'], qualification['continuation']
        count = qualification['knownMinimumEntityCount']
        if (not isinstance(limits, list) or not 1 <= len(limits) <= len(SourceLimitation)
                or type(count) is not int or not 0 <= count <= 2**31 - 1
                or not isinstance(continuation, dict)):
            raise ValueError('SOURCE_QUALIFICATION_VALUES')
        admitted = tuple(SourceLimitation(value) for value in limits)
        if len(set(admitted)) != len(admitted):
            raise ValueError('SOURCE_QUALIFICATION_DUPLICATES')
        kind = SourceContinuationKind(continuation['type'])
        keys = {'type', 'continuation'} if kind is SourceContinuationKind.AVAILABLE else {'type'}
        if set(continuation) != keys:
            raise ValueError('SOURCE_QUALIFICATION_CONTINUATION')
        # The installed transport already validates the full schema. This receipt
        # records only the finite progress discriminator, never checkpoint payloads.
        progress = SourceProgressKind(qualification['progress']['type'])
        if ((progress is SourceProgressKind.RESUMABLE)
                != (kind is SourceContinuationKind.AVAILABLE)):
            raise ValueError('SOURCE_PROGRESS_AVAILABILITY')
        return asdict(ObservedSourceQualification(admitted, kind, count, progress))
    except (ValueError, TypeError, KeyError):
        return asdict(UnrecognizedSourceQualification())
