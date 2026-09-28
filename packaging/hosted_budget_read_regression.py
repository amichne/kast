"""Installed independent budget grant checks; payloads stay in memory."""
from dataclasses import asdict, dataclass, field
from hosted_source_read_regression import SymbolAnchor, FirstPage, NoText
import re
from query_name_request import QueryInput, QueryRun, SymbolOutput, SymbolReferences, relation_query, walk_query


@dataclass(frozen=True)
class ElapsedBudget:
    maxElapsedMs: int = 5000


@dataclass(frozen=True)
class WorkBudget:
    maxWorkUnits: int = 100000


@dataclass(frozen=True)
class ResultsBudget:
    maxResults: int = 100000


@dataclass(frozen=True)
class BytesBudget:
    maxReturnedBytes: int = 10000000


Budget = ElapsedBudget | WorkBudget | ResultsBudget | BytesBudget
AXES = ('max_elapsed_ms', 'max_work_units', 'max_results', 'max_returned_bytes')


@dataclass(frozen=True)
class NoEntities:
    type: str = field(default='NONE', init=False)


@dataclass(frozen=True)
class BudgetSource:
    symbolRef: SymbolAnchor
    executionBudget: Budget
    region: str = 'FILE'
    entities: NoEntities = field(default_factory=NoEntities)
    text: NoText = field(default_factory=NoText)
    page: FirstPage = field(default_factory=FirstPage)


def run_budget_read_regression(replay):
    """Both surfaces share the existing oracle, authority, fixture and 256-case receipt bound."""
    for budget in (ElapsedBudget(), WorkBudget(), ResultsBudget(), BytesBudget()):
        axis = next(iter(asdict(budget)))
        cases = (
            ('query-symbols', 'query_symbols', QueryInput(QueryRun(SymbolReferences((replay.seeds['logger']['ref'],)),
                output=SymbolOutput(('NAME',)), executionBudget=budget))),
            ('source-read', 'read_source', BudgetSource(SymbolAnchor(replay.seeds['logger']['ref']), budget)),
            ('query-occurrences', 'query_symbols', relation_query(replay.seeds['helper']['ref'], 'callers', budget)),
            ('query-walk', 'query_symbols', walk_query(replay.seeds['helper']['ref'], budget=budget)),
        )
        for case, tool, request in cases:
            response = _invoke(replay, tool, request)
            replay.record('budget-' + axis + '-' + case, tool, {
                'semanticResult': response.get('status') in ('complete', 'qualified'),
                'sameLiveAuthority': response.get('live') == replay.live,
                'independentGrant': independent_grant(response, budget),
            }, response=response)


def _invoke(replay, tool, request):
    response = replay.transport.invoke(replay.surface, tool, asdict(request))
    replay.transport.validate(tool, response)
    return response


def independent_grant(response, budget):
    """The same-build schema checks report validity; this checks exact caller/default forwarding."""
    report = response.get('execution_budget', {})
    requested = {re.sub(r'(?<!^)(?=[A-Z])', '_', key).lower(): value for key, value in asdict(budget).items()}
    if set(report) != set(AXES):
        return False
    for axis in AXES:
        limit = report[axis]
        if axis in requested:
            if (limit.get('selection') != 'caller' or limit.get('requested') != requested[axis]
                    or type(limit.get('effective')) is not int or not 0 < limit['effective'] <= requested[axis]):
                return False
            if (limit['effective'] < requested[axis]) != bool(limit.get('clamping')):
                return False
        elif limit.get('selection') != 'configured_default' or limit.get('requested') is not None:
            return False
    return True


def progress_advances(previous, current):
    keys = ('checkpointSequence', 'totalReads', 'totalEdges', 'maximumDepthReached')
    return all(type(current.get(key)) is int and current[key] >= previous.get(key, 0) for key in keys)
