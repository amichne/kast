"""Installed independent budget grants and retained traversal replay; payloads stay in memory."""
from dataclasses import asdict, dataclass, field, replace
from collections import Counter
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
class NoEntities:
    type: str = field(default='none', init=False)


@dataclass(frozen=True)
class BudgetSource:
    anchor: SymbolAnchor
    execution_budget: Budget
    region: FileRegion = field(default_factory=FileRegion)
    entities: NoEntities = field(default_factory=NoEntities)
    text: NoText = field(default_factory=NoText)
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
            ('query_symbols', BudgetQuery(ExactReferences((replay.seeds['logger']['ref'],)), budget)),
            ('source_read', BudgetSource(SymbolAnchor(replay.seeds['logger']['ref']), budget)),
            ('read_relations', BudgetRelation(replay.seeds['helper']['ref'], budget)),
            ('traverse_relations', BudgetTraversal(replay.seeds['helper']['ref'], budget)),
        )
        for tool, request in cases:
            response = _invoke(replay, tool, request)
            replay.record('budget-' + axis + '-' + tool, tool, {
                'semanticResult': response.get('status') in ('complete', 'qualified'),
                'sameLiveAuthority': response.get('live') == replay.live,
                'independentGrant': independent_grant(response, budget),
            }, response=response)
    _retained_traversal(replay, ElapsedBudget(1000), ElapsedBudget(3000))
    _retained_traversal(replay, WorkBudget(100), WorkBudget(100000))
    _retained_traversal(replay, ResultsBudget(1), ResultsBudget(100))
    _retained_traversal(replay, BytesBudget(8192), BytesBudget(500000))


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


@dataclass(frozen=True)
class TraversalDrain:
    pages: tuple[dict, ...]
    complete: bool
    checkpointsAdvance: bool
    progressMonotonic: bool
    sameLiveAuthority: bool
    grantsRetained: bool

    @property
    def valid(self):
        return (self.complete and self.checkpointsAdvance and self.progressMonotonic
                and self.sameLiveAuthority and self.grantsRetained)


def traversal_checkpoint(response):
    qualification = response.get('qualification', {})
    checkpoint = qualification.get('checkpoint', {})
    token = checkpoint.get('token')
    if (response.get('status') != 'qualified' or qualification.get('type') != 'resumable'
            or checkpoint.get('type') not in ('upstream', 'retained_output')
            or qualification.get('next_action') not in ('resume', 'increase_execution_budget')
            or not isinstance(token, str) or not 1 <= len(token) <= 1048576
            or qualification.get('continuation') != token):
        return None
    if (checkpoint['type'] == 'retained_output'
            and checkpoint.get('upstream') not in ('complete', 'resumable', 'terminal_incomplete')):
        return None
    return checkpoint


def progress_advances(previous, current):
    keys = ('checkpointSequence', 'totalReads', 'totalEdges', 'maximumDepthReached')
    return all(type(current.get(key)) is int and current[key] >= previous.get(key, 0) for key in keys)


def _drain_traversal(replay, request, larger, first=None):
    pages, seen = [], set()
    advancing, monotonic, same_live, grants, complete = True, True, True, True, False
    for index in range(16):
        response = first if index == 0 and first is not None else _invoke(replay, 'traverse_relations', request)
        previous = pages[-1].get('progress', {}) if pages else {}
        monotonic = monotonic and progress_advances(previous, response.get('progress', {}))
        same_live = same_live and response.get('live') == replay.live
        grants = grants and independent_grant(response, request.execution_budget)
        pages.append(response)
        if response.get('status') == 'complete':
            complete = 'qualification' not in response
            break
        checkpoint = traversal_checkpoint(response)
        if checkpoint is None or checkpoint['token'] in seen:
            advancing = False
            break
        seen.add(checkpoint['token'])
        request = replace(request, position=TraversalResume(checkpoint['token']), execution_budget=larger)
    return TraversalDrain(tuple(pages), complete, advancing, monotonic, same_live, grants)


def _retained_traversal(replay, low, large):
    tool = 'traverse_relations'
    start = BudgetTraversal(replay.seeds['helper']['ref'], large)
    baseline = _drain_traversal(replay, start, large)
    low_start = replace(start, execution_budget=low)
    first = _invoke(replay, tool, low_start)
    changed = _drain_traversal(replay, low_start, large, first)
    axis = next(iter(asdict(low)))
    baseline_records = tuple(record for page in baseline.pages for record in graph_records(page))
    changed_records = tuple(record for page in changed.pages for record in graph_records(page))
    last, reference = changed.pages[-1], baseline.pages[-1]
    checks = {
        'ampleBaselineComplete': baseline.valid,
        'lowBudgetCompleteOrResumable': ((isinstance(low, (ElapsedBudget, WorkBudget))
            and first.get('status') == 'complete' and 'qualification' not in first)
            or traversal_checkpoint(first) is not None),
        'effectiveAllowanceIncreased': (baseline.pages[0].get('execution_budget', {}).get(axis, {}).get('effective', 0)
            > first.get('execution_budget', {}).get(axis, {}).get('effective', 0)),
        'largerGrantComplete': changed.valid,
        'graphOccurrenceAndProofIdentity': Counter(changed_records) == Counter(baseline_records),
        'finalEdgeProgress': last.get('progress', {}).get('totalEdges') == reference.get('progress', {}).get('totalEdges'),
        'semanticDepthUnchanged': (last.get('progress', {}).get('maximumDepthReached')
                                   == reference.get('progress', {}).get('maximumDepthReached')),
        'terminalPartialExpansionsPreserved': last.get('partialExpansions') == reference.get('partialExpansions'),
    }
    if isinstance(low, ResultsBudget):
        checks['pageResultBound'] = len(first.get('graph', {}).get('edges', [])) <= low.max_results
    if isinstance(low, BytesBudget):
        checks['projectedPayloadFits'] = encoded_payload_bytes(first) <= low.max_returned_bytes
        checks.update(_retained_result_replay(replay, start, first))
    replay.record('budget-traversal-' + axis, tool, checks,
                  len(first.get('graph', {}).get('edges', [])), first)


def _retained_result_replay(replay, start, first):
    # A result-only native page may checkpoint upstream work. Reshape only a
    # genuinely retained byte suffix; a one-record suffix needs no child token.
    checkpoint = traversal_checkpoint(first)
    retained = checkpoint is not None and checkpoint['type'] == 'retained_output'
    checks = {'byteRetainedSuffixAvailable': retained}
    if not retained:
        return checks
    resume = replace(start, position=TraversalResume(checkpoint['token']), execution_budget=ResultsBudget(1))
    child = _invoke(replay, 'traverse_relations', resume)
    repeated = _invoke(replay, 'traverse_relations', resume)
    ample = replace(resume, execution_budget=ResultsBudget(100))
    full, full_valid = _detached_suffix(replay, ample)
    reference = full[-1]
    full_records = tuple(record for page in full for record in graph_records(page))
    child_checkpoint = traversal_checkpoint(child)
    child_retained = child_checkpoint is not None and child_checkpoint['type'] == 'retained_output'
    checks.update({
        'identicalRetainedTokenReplay': child == repeated,
        'retainedResultGrant': independent_grant(child, ResultsBudget(1)),
        'oneRetainedResult': len(child.get('graph', {}).get('edges', [])) == 1,
        'largerDetachedSuffixDrained': full_valid,
        'retainedProgressUnchanged': child.get('progress') == reference.get('progress') == first.get('progress'),
        'retainedPartialExpansionsUnchanged': (child.get('partialExpansions') == reference.get('partialExpansions')
                                              == first.get('partialExpansions')),
        'retainedAuthorityUnchanged': child.get('live') == reference.get('live') == replay.live,
    })
    if len(full_records) == 1 and not child_retained:
        checks.update({
            'singleRecordSuffixChildReshapeUnexercised': True,
            'singleRecordOrderAndProofIdentity': graph_records(child) == full_records,
            'upstreamCoverageAndActionRestored': child.get('qualification') == reference.get('qualification'),
        })
        return checks
    checks['multipleRecordSuffixChildIssued'] = len(full_records) >= 2 and child_retained
    if child_retained:
        tail_request = replace(ample, position=TraversalResume(child_checkpoint['token']))
        tail, tail_valid = _detached_suffix(replay, tail_request)
        tail_records = tuple(record for page in tail for record in graph_records(page))
        checks.update({
            'retainedChildCoverage': child_checkpoint['upstream'] == checkpoint['upstream'],
            'largerRetainedSuffixDrained': tail_valid,
            'retainedOrderAndProofIdentity': graph_records(child) + tail_records == full_records,
            'upstreamCoverageAndActionRestored': tail[-1].get('qualification') == reference.get('qualification'),
            'retainedTailProgress': tail[-1].get('progress') == reference.get('progress'),
        })
    return checks


def _detached_suffix(replay, request):
    """Drain byte refits only; an upstream checkpoint belongs to later semantic work."""
    pages, seen = [], set()
    for _ in range(16):
        response = _invoke(replay, 'traverse_relations', request)
        pages.append(response)
        if (response.get('live') != replay.live or not independent_grant(response, request.execution_budget)
                or response.get('progress') != pages[0].get('progress')
                or response.get('partialExpansions') != pages[0].get('partialExpansions')):
            return tuple(pages), False
        qualification = response.get('qualification', {})
        if response.get('status') == 'complete' and 'qualification' not in response:
            return tuple(pages), True
        if response.get('status') == 'qualified' and qualification.get('type') == 'terminal_incomplete':
            return tuple(pages), True
        checkpoint = traversal_checkpoint(response)
        if checkpoint is None:
            return tuple(pages), False
        if checkpoint['type'] == 'upstream':
            return tuple(pages), True
        if checkpoint['token'] in seen:
            return tuple(pages), False
        seen.add(checkpoint['token'])
        request = replace(request, position=TraversalResume(checkpoint['token']))
    return tuple(pages), False


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
