"""Installed independent budget grants and retained traversal replay; payloads stay in memory."""
from dataclasses import asdict, dataclass, field, replace
import json

from hosted_read_requests import BreadthFirstStrategy, TraversalStart, TraversalResume
from hosted_source_read_regression import SymbolAnchor, FileRegion, FirstPage, NoText


@dataclass(frozen=True)
class ElapsedBudget:
    max_elapsed_ms: int = 5000


@dataclass(frozen=True)
class WorkBudget:
    max_work_units: int = 100000


@dataclass(frozen=True)
class ResultsBudget:
    max_results: int = 100000


@dataclass(frozen=True)
class BytesBudget:
    max_returned_bytes: int = 10000000


Budget = ElapsedBudget | WorkBudget | ResultsBudget | BytesBudget
AXES = ('max_elapsed_ms', 'max_work_units', 'max_results', 'max_returned_bytes')


@dataclass(frozen=True)
class ExactReferences:
    symbol_refs: tuple[str, ...]
    type: str = field(default='symbol_refs', init=False)


@dataclass(frozen=True)
class BudgetQuery:
    source: ExactReferences
    execution_budget: Budget
    steps: None = None
    return_fields: tuple[str, ...] = ('name',)


@dataclass(frozen=True)
class BudgetClassSearch:
    execution_budget: Budget
    class_name: str = 'ReadPageBudget'
    name_match: str = 'exact'
    scope: None = None


@dataclass(frozen=True)
class BudgetFunctionSearch:
    execution_budget: Budget
    function_name: str = 'pageItem00'
    name_match: str = 'exact'
    scope: None = None


@dataclass(frozen=True)
class NoEntities:
    type: str = field(default='none', init=False)


@dataclass(frozen=True)
class BudgetSource:
    anchor: SymbolAnchor
    execution_budget: Budget
    region: FileRegion = field(default_factory=FileRegion)
    entities: NoEntities = field(default_factory=NoEntities)
    text: NoText = field(default_factory=NoText)
    entityLimit: int = 100
    textByteLimit: int = 65536
    page: FirstPage = field(default_factory=FirstPage)


@dataclass(frozen=True)
class BudgetRelation:
    exactSelector: str
    execution_budget: Budget
    relation: str = 'callers'
    limit: int = 100
    position: TraversalStart = field(default_factory=TraversalStart)


@dataclass(frozen=True)
class BudgetTraversal:
    exactSelector: str
    execution_budget: Budget
    position: TraversalStart | TraversalResume = field(default_factory=TraversalStart)
    relation: str = 'callers'
    maximumDepth: int = 4
    maximumResults: int = 100
    strategy: BreadthFirstStrategy = field(default_factory=BreadthFirstStrategy)


def run_budget_read_regression(replay):
    """Both surfaces share the existing oracle, authority, fixture and 256-case receipt bound."""
    for budget in (ElapsedBudget(), WorkBudget(), ResultsBudget(), BytesBudget()):
        axis = next(iter(asdict(budget)))
        cases = (
            ('query_symbols', BudgetQuery(ExactReferences((replay.seeds['logger']['symbol_ref'],)), budget)),
            ('search_classes', BudgetClassSearch(budget)),
            ('search_functions', BudgetFunctionSearch(budget)),
            ('source_read', BudgetSource(SymbolAnchor(replay.seeds['logger']['symbol_ref']), budget)),
            ('semantic_query', BudgetRelation(replay.seeds['helper']['symbol_ref'], budget)),
            ('impact_analyze', BudgetTraversal(replay.seeds['helper']['symbol_ref'], budget)),
        )
        for tool, request in cases:
            response = _invoke(replay, tool, request)
            replay.record('budget-' + axis + '-' + tool, tool, {
                'semanticResult': response.get('status') in ('complete', 'qualified'),
                'sameLiveAuthority': response.get('live') == replay.live,
                'independentGrant': independent_grant(response, budget),
            }, response=response)
    _retained_traversal(replay, ResultsBudget(1), ResultsBudget(100))
    _retained_traversal(replay, BytesBudget(16384), BytesBudget(500000))


def _invoke(replay, tool, request):
    response = replay.transport.invoke(replay.surface, tool, asdict(request))
    replay.transport.validate(tool, response)
    return response


def independent_grant(response, budget):
    """The same-build schema checks report validity; this checks exact caller/default forwarding."""
    report, requested = response.get('execution_budget', {}), asdict(budget)
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


def encoded_payload_bytes(response):
    """Compact projected payload size, distinct from the authoritative hosted wire frame size."""
    return len(json.dumps(response, ensure_ascii=False, separators=(',', ':')).encode())


def _retained_traversal(replay, low, large):
    tool = 'impact_analyze'
    start = BudgetTraversal(replay.seeds['helper']['symbol_ref'], large)
    baseline = _invoke(replay, tool, start)
    first = _invoke(replay, tool, replace(start, execution_budget=low))
    qualification = first.get('qualification', {})
    checkpoint = qualification.get('checkpoint', {})
    token = checkpoint.get('token')
    retained = (first.get('status') == 'qualified' and checkpoint.get('type') == 'retained_output'
                and checkpoint.get('upstream') == 'complete' and qualification.get('next_action') == 'resume'
                and isinstance(token, str) and qualification.get('continuation') == token)
    axis = next(iter(asdict(low)))
    checks = {
        'ampleBaselineComplete': baseline.get('status') == 'complete',
        'retainedCompleteSuffix': retained,
        'independentLowGrant': independent_grant(first, low),
        'sameLiveAuthority': first.get('live') == baseline.get('live') == replay.live,
        'cumulativeProgressRetained': first.get('progress') == baseline.get('progress'),
        'partialExpansionsRetained': first.get('partialExpansions') == baseline.get('partialExpansions'),
    }
    if retained:
        resume = replace(start, position=TraversalResume(token), execution_budget=low)
        child = _invoke(replay, tool, resume)
        repeated = _invoke(replay, tool, resume)
        suffix = _invoke(replay, tool, replace(resume, execution_budget=large))
        checks.update({
            'identicalTokenReplay': child == repeated,
            'largerGrantComplete': suffix.get('status') == 'complete' and independent_grant(suffix, large),
            'orderedGraphAndProofIdentity': graph_records(first) + graph_records(suffix) == graph_records(baseline),
            'suffixCumulativeProgressRetained': suffix.get('progress') == baseline.get('progress'),
            'suffixPartialExpansionsRetained': suffix.get('partialExpansions') == baseline.get('partialExpansions'),
            'suffixSameLiveAuthority': suffix.get('live') == replay.live,
        })
        if isinstance(low, BytesBudget):
            checks['projectedPayloadFits'] = all(encoded_payload_bytes(value) <= low.max_returned_bytes
                                                for value in (first, child, repeated))
    replay.record('budget-retained-traversal-' + axis, tool, checks,
                  len(first.get('graph', {}).get('edges', [])), first)


def graph_records(response):
    """Compare ordered edges with full node/occurrence/proof meaning, independent of page-local table indices."""
    graph = response.get('graph', {})
    proofs = {proof['id']: proof['identity'] for proof in graph.get('proofs', [])}
    nodes = {node['id']: (tuple((key, _freeze(value)) for key, value in sorted(node.items())
                              if key not in ('id', 'proof')), proofs[node['proof']])
             for node in graph.get('nodes', [])}
    return tuple((nodes[edge['source']], nodes[edge['target']],
                  tuple((key, _freeze(value)) for key, value in sorted(edge.items())
                        if key not in ('source', 'target')))
                 for edge in graph.get('edges', []))


def _freeze(value):
    if isinstance(value, dict):
        return tuple((key, _freeze(item)) for key, item in sorted(value.items()))
    if isinstance(value, list):
        return tuple(_freeze(item) for item in value)
    return value
