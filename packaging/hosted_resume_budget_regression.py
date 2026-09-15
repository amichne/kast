"""Bounded installed query/source/relation grant parity; payloads remain in memory.

A low page may finish. Otherwise only its issued canonical checkpoint advances
under the larger grant. This helper never claims a wall-clock cutoff occurred.
"""
from collections import Counter
from dataclasses import asdict, dataclass, field, replace
from enum import Enum

from hosted_budget_read_regression import (BudgetQuery, BudgetRelation, Budget, ExactReferences,
    ElapsedBudget, WorkBudget, ResultsBudget, BytesBudget, independent_grant)
from hosted_read_requests import TraversalResume
from hosted_source_read_regression import (SymbolAnchor, FileRegion, FirstPage, DescendantFunctions)


@dataclass(frozen=True)
class ResumeQuery(BudgetQuery):
    continuation: str | None = None


@dataclass(frozen=True)
class CompleteText:
    type: str = field(default='complete', init=False)


@dataclass(frozen=True)
class SourceResume:
    continuation: str
    type: str = field(default='continue', init=False)


@dataclass(frozen=True)
class ResumeSource:
    anchor: SymbolAnchor
    execution_budget: Budget
    region: FileRegion = field(default_factory=FileRegion)
    entities: DescendantFunctions = field(default_factory=DescendantFunctions)
    text: CompleteText = field(default_factory=CompleteText)
    entityLimit: int = 128
    textByteLimit: int = 65536
    page: FirstPage | SourceResume = field(default_factory=FirstPage)


class ResumeFailure(str, Enum):
    NON_RESUMABLE = 'NON_RESUMABLE'
    CHECKPOINT_REJECTED = 'CHECKPOINT_REJECTED'
    TOKEN_REPEATED = 'TOKEN_REPEATED'
    PAGE_BOUND = 'PAGE_BOUND'
    AUTHORITY_CHANGED = 'AUTHORITY_CHANGED'
    GRANT_CHANGED = 'GRANT_CHANGED'
    RESULT_BOUND = 'RESULT_BOUND'


@dataclass(frozen=True)
class Finished:
    pass


@dataclass(frozen=True)
class Checkpoint:
    token: str
    kind: str


@dataclass(frozen=True)
class DrainRejected:
    failure: ResumeFailure


@dataclass(frozen=True)
class Drained:
    pages: tuple[dict, ...]


MAXIMUM_PAGES = 16
MAXIMUM_RECORDS = 1000


def admit_progress(tool, response):
    """Schema validation precedes this semantic check of progress and compatibility aliases."""
    if response.get('status') == 'complete' and 'qualification' not in response:
        return Finished()
    qualification = response.get('qualification', {})
    if response.get('status') != 'qualified' or not isinstance(qualification, dict):
        return DrainRejected(ResumeFailure.NON_RESUMABLE)
    progress = qualification if tool == 'read_relations' else qualification.get('progress', {})
    if not isinstance(progress, dict) or progress.get('type') != 'resumable':
        return DrainRejected(ResumeFailure.NON_RESUMABLE)
    checkpoint = progress.get('checkpoint', {})
    if not isinstance(checkpoint, dict):
        return DrainRejected(ResumeFailure.CHECKPOINT_REJECTED)
    token, kind = checkpoint.get('token'), checkpoint.get('type')
    action = progress.get('next_action')
    if (kind not in ('upstream', 'retained_output') or action not in ('resume', 'increase_execution_budget')
            or (kind == 'retained_output' and action != 'resume')
            or not isinstance(token, str) or not 1 <= len(token) <= 1048576):
        return DrainRejected(ResumeFailure.CHECKPOINT_REJECTED)
    if tool == 'query_symbols':
        alias = response.get('continuation')
    elif tool == 'source_read':
        legacy = qualification.get('continuation', {})
        alias = legacy.get('continuation') if legacy.get('type') == 'available' else None
    else:
        alias = qualification.get('continuation')
    if alias != token:
        return DrainRejected(ResumeFailure.CHECKPOINT_REJECTED)
    return Checkpoint(token, kind)


def resume_request(request, token):
    if isinstance(request, ResumeQuery):
        return replace(request, continuation=token)
    if isinstance(request, ResumeSource):
        return replace(request, page=SourceResume(token))
    if isinstance(request, BudgetRelation):
        return replace(request, position=TraversalResume(token))
    raise TypeError('UNSUPPORTED_RESUME_REQUEST')


def _invoke(replay, tool, request):
    response = replay.transport.invoke(replay.surface, tool, asdict(request))
    replay.transport.validate(tool, response)
    return response


def drain(replay, tool, request, first, initial_budget):
    """Retain at most sixteen schema-admitted pages, following both checkpoint owners."""
    pages, seen, response, budget = [], set(), first, initial_budget
    for _ in range(MAXIMUM_PAGES):
        if response.get('live') != replay.live:
            return DrainRejected(ResumeFailure.AUTHORITY_CHANGED)
        if not independent_grant(response, budget):
            return DrainRejected(ResumeFailure.GRANT_CHANGED)
        pages.append(response)
        if sum(len(records(tool, page)) for page in pages) > MAXIMUM_RECORDS:
            return DrainRejected(ResumeFailure.RESULT_BOUND)
        progress = admit_progress(tool, response)
        if isinstance(progress, Finished):
            return Drained(tuple(pages))
        if isinstance(progress, DrainRejected):
            return progress
        if progress.token in seen:
            return DrainRejected(ResumeFailure.TOKEN_REPEATED)
        seen.add(progress.token)
        request = resume_request(request, progress.token)
        budget = request.execution_budget
        if len(pages) < MAXIMUM_PAGES:
            response = _invoke(replay, tool, request)
    return DrainRejected(ResumeFailure.PAGE_BOUND)


def _freeze(value):
    if isinstance(value, dict):
        return tuple((key, _freeze(item)) for key, item in sorted(value.items()))
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(item) for item in value)
    return value


def records(tool, response):
    key = {'query_symbols': 'items', 'source_read': 'entities', 'read_relations': 'relations'}[tool]
    return tuple(_freeze(record) for record in response.get(key, []))


def record_parity(tool, observed, baseline):
    """Keep declaration order; relation pages retain individual occurrence and compiler evidence."""
    if not isinstance(observed, Drained) or not isinstance(baseline, Drained):
        return False
    actual = tuple(item for page in observed.pages for item in records(tool, page))
    expected = tuple(item for page in baseline.pages for item in records(tool, page))
    if tool == 'read_relations':
        # Native provider cursors use file/range order; RelationBatch sorts each
        # admitted page by endpoint fingerprint. Grant changes can change page
        # boundaries, but must preserve every full occurrence, including duplicates.
        if Counter(actual) != Counter(expected):
            return False
        positions = {record: index for index, record in enumerate(expected)}
        if any(tuple(positions[item] for item in records(tool, page)) !=
               tuple(sorted(positions[item] for item in records(tool, page))) for page in observed.pages):
            return False
    elif actual != expected:
        return False
    return True


def coverage_parity(tool, observed, baseline):
    if not isinstance(observed, Drained) or not isinstance(baseline, Drained):
        return False
    pages = observed.pages + baseline.pages
    if tool == 'source_read':
        original = baseline.pages[0]
        return all(page.get('snapshot') == original.get('snapshot')
                   and page.get('region') == original.get('region')
                   and page.get('text') == original.get('text') for page in pages)
    if tool == 'read_relations':
        return relation_coverage_parity(observed, baseline)
    return all(page.get('failures') == baseline.pages[0].get('failures') for page in pages)


def relation_coverage_parity(observed, baseline):
    # An upstream budget stop correctly reports unmeasured remaining work on
    # that page. It is not an observed missing occurrence in the final drain.
    expected = baseline.pages[0].get('omissions')
    temporary = {'RESULT_LIMIT_REACHED', 'BYTE_LIMIT_REACHED', 'TIME_LIMIT_REACHED', 'WORK_LIMIT_REACHED'}
    for page in observed.pages:
        permanent = []
        for omission in page.get('omissions', []):
            if omission.get('reason') not in temporary:
                permanent.append(omission)
                continue
            qualification = page.get('qualification', {})
            if (not isinstance(admit_progress('read_relations', page), Checkpoint)
                    or omission['reason'].lower().replace('_', '-') not in qualification.get('limitations', [])
                    or omission.get('measurement') != {'type': 'unmeasured_on_page'}
                    or _freeze(omission.get('samples')) != () or omission.get('remediation') != 'INCREASE_READ_LIMIT'):
                return False
        if _freeze(permanent) != _freeze(expected):
            return False
    return observed.pages[-1].get('omissions') == baseline.pages[-1].get('omissions')


def payload_parity(tool, observed, baseline):
    return record_parity(tool, observed, baseline) and coverage_parity(tool, observed, baseline)


def _authored_baseline(replay, tool, result):
    if not isinstance(result, Drained):
        return False
    if tool == 'query_symbols':
        items = tuple(item for page in result.pages for item in page.get('items', []))
        return tuple(item.get('ref') for item in items) == tuple(
            replay.seeds[key]['ref'] for key in ('logger', 'helper'))
    if tool == 'read_relations':
        relations = tuple(item for page in result.pages for item in page.get('relations', []))
        return (Counter(item.get('source', {}).get('qualifiedIdentity') for item in relations)
                == Counter(replay.fixture.oracle['helperCallers'])
                and all(item.get('coverage') == 'exact-compiler-confirmed'
                        and item.get('provenance') == 'k2-authored-source' for item in relations))
    return any(page.get('entities') for page in result.pages)


def _effective(response, budget):
    axis = next(iter(asdict(budget)))
    return response.get('execution_budget', {}).get(axis, {}).get('effective', 0)


def run_resume_budget_regression(replay):
    """Twelve receipt cases per surface; internal pages never consume receipt rows."""
    requests = (
        ('query_symbols', ResumeQuery(ExactReferences(tuple(replay.seeds[key]['ref']
            for key in ('logger', 'helper'))), ResultsBudget(),
            return_fields=('name', 'location', 'signature'))),
        ('source_read', ResumeSource(SymbolAnchor(replay.seeds['logger']['ref']), ResultsBudget())),
        ('read_relations', BudgetRelation(replay.seeds['helper']['ref'], ResultsBudget())),
    )
    for low, large in ((ElapsedBudget(1000), ElapsedBudget(3000)),
                       (WorkBudget(100), WorkBudget(100000)),
                       (ResultsBudget(1), ResultsBudget(100)),
                       (BytesBudget(32768), BytesBudget(500000))):
        for tool, request in requests:
            _case(replay, tool, request, low, large)


def _case(replay, tool, request, low, large):
    ample = replace(request, execution_budget=large)
    baseline_first = _invoke(replay, tool, ample)
    baseline = drain(replay, tool, ample, baseline_first, large)
    first = _invoke(replay, tool, replace(request, execution_budget=low))
    observed = drain(replay, tool, ample, first, low)
    checks = {
        'exhaustiveBaseline': isinstance(baseline, Drained),
        'authoredBaseline': _authored_baseline(replay, tool, baseline),
        'lowPageCompleteOrDrained': isinstance(observed, Drained),
        'independentLowGrant': independent_grant(first, low),
        'independentLargerGrant': independent_grant(baseline_first, large),
        'effectiveAllowanceIncreased': _effective(baseline_first, large) > _effective(first, low),
        'recordIdentityAndRequiredOrder': record_parity(tool, observed, baseline),
        'pageCoveragePreserved': coverage_parity(tool, observed, baseline),
    }
    if tool == 'source_read' and isinstance(baseline, Drained):
        path = replay.fixture.workspace / replay.fixture.oracle['declarations']['logger'][0]
        checks['exactSavedText'] = all(page.get('text', {}).get('type') == 'returned'
            and page['text'].get('text') == path.read_text() for page in baseline.pages)
    for name, result in (('baseline', baseline), ('resume', observed)):
        if isinstance(result, DrainRejected):
            # Finite failure names are receipt assertion keys; no token or payload is recorded.
            checks[name + result.failure.value] = False
    count = sum(len(records(tool, page)) for page in observed.pages) if isinstance(observed, Drained) else 0
    replay.record('budget-resume-' + tool + '-' + next(iter(asdict(low))), tool, checks, count, first)
