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


def run_source_paging_regression(replay):
    request = SourceFunctionRequest(SymbolAnchor(replay.seeds['logger']['symbol_ref']))
    response = replay.transport.invoke(replay.surface, 'source_read', asdict(request))
    qualification = response.get('qualification', {})
    entities = response.get('entities', [])
    continuation = qualification.get('continuation', {})
    replay.record('source-stop-at-eligible-page', 'source_read', {
        'qualified': response.get('status') == 'qualified',
        'exactFirstEntity': [entity.get('name') for entity in entities] == ['loggerFunction'],
        'entityLimitOnly': qualification.get('limitations') == ['entity-limit-reached'],
        'upstreamContinuation': continuation.get('type') == 'available' and bool(continuation.get('continuation')),
        'sameLiveAuthority': response.get('live') == replay.live,
        'effectiveWorkRetained': response.get('execution_budget', {}).get('max_work_units', {}).get('effective') == 100,
    }, len(entities), response)
