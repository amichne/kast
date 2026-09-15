"""Disposable diagnostic-pages policy acceptance; bounded payloads stay in memory."""
from dataclasses import asdict, dataclass, replace
from enum import Enum
from hosted_read_transport import ReadTransportRejected


@dataclass(frozen=True)
class DiagnosticGrant:
    max_work_units: int = 10000
    max_elapsed_ms: int = 10000
    max_results: int = 1
    max_returned_bytes: int = 262144


@dataclass(frozen=True)
class LegacyDiagnosticRequest:
    relative_path: str = 'src/main/kotlin'
    max_diagnostics: int = 1


@dataclass(frozen=True)
class DiagnosticRequest:
    relative_path: str = 'src/main/kotlin'
    max_diagnostics: int = 1
    continuation: str | None = None
    execution_budget: DiagnosticGrant = DiagnosticGrant()


class DiagnosticDrainFailure(str, Enum):
    OUTCOME = 'OUTCOME'
    AUTHORITY = 'AUTHORITY'
    COVERAGE = 'COVERAGE'
    CHECKPOINT = 'CHECKPOINT'
    PAGE_BOUND = 'PAGE_BOUND'
    RECORD_BOUND = 'RECORD_BOUND'
    REPLAY = 'REPLAY'
    BUDGET_REPORT = 'BUDGET_REPORT'


@dataclass(frozen=True)
class DiagnosticDrained:
    pages: tuple[dict, ...]


@dataclass(frozen=True)
class DiagnosticDrainRejected:
    failure: DiagnosticDrainFailure
    differing_fields: tuple[str, ...] = ()


MAXIMUM_PAGES = 128
MAXIMUM_RECORDS = 1000


def _invoke(replay, request):
    response = replay.transport.invoke(replay.surface, 'check_diagnostics', asdict(request))
    replay.transport.validate('check_diagnostics', response)
    return response


def drain_diagnostics(replay, request, first, verify_replay=False):
    pages, seen, analyzed = [], set(), set()
    response = first
    for _ in range(MAXIMUM_PAGES):
        if response.get('live') != replay.live:
            return DiagnosticDrainRejected(DiagnosticDrainFailure.AUTHORITY)
        if response.get('status') not in ('complete', 'qualified'):
            return DiagnosticDrainRejected(DiagnosticDrainFailure.OUTCOME)
        if not _valid_report(response, request):
            return DiagnosticDrainRejected(DiagnosticDrainFailure.BUDGET_REPORT, ('execution_budget',))
        progress = response.get('progress', {})
        inventory = progress.get('inventory', {})
        current = progress.get('analyzedFiles', [])
        if (not isinstance(current, list) or current != sorted(set(current))
                or not analyzed.issubset(current)
                or inventory.get('type') not in ('enumerating', 'exhausted')
                or (inventory.get('type') == 'enumerating' and 'totalFiles' in inventory)):
            return DiagnosticDrainRejected(DiagnosticDrainFailure.COVERAGE)
        analyzed = set(current)
        pages.append(response)
        if sum(len(page.get('diagnostics', [])) for page in pages) > MAXIMUM_RECORDS:
            return DiagnosticDrainRejected(DiagnosticDrainFailure.RECORD_BOUND)
        if response['status'] == 'complete':
            if (inventory.get('type') != 'exhausted' or inventory.get('totalFiles') != len(analyzed)
                    or progress.get('stage') != 'finished' or progress.get('stop') != 'finished'):
                return DiagnosticDrainRejected(DiagnosticDrainFailure.COVERAGE)
            return DiagnosticDrained(tuple(pages))
        token = _continuation(response)
        if not isinstance(token, str) or not 1 <= len(token) <= 1048576 or token in seen:
            return DiagnosticDrainRejected(DiagnosticDrainFailure.CHECKPOINT)
        seen.add(token)
        request = replace(request, continuation=token)
        response = _invoke(replay, request)
        if verify_replay:
            repeated = _invoke(replay, request)
            if not _valid_report(repeated, request):
                return DiagnosticDrainRejected(DiagnosticDrainFailure.BUDGET_REPORT, ('execution_budget',))
            differing = _semantic_difference(response, repeated)
            if differing:
                return DiagnosticDrainRejected(DiagnosticDrainFailure.REPLAY, differing)
    return DiagnosticDrainRejected(DiagnosticDrainFailure.PAGE_BOUND)


def run_diagnostic_pages_regression(replay):
    """Caller selects the typed disposable diagnostic-pages policy before invoking this helper."""
    try:
        legacy = _invoke(replay, LegacyDiagnosticRequest())
    except ReadTransportRejected as failure:
        checks = {'boundedDrainCompleted': False,
            'legacyTransportObserved_' + failure.reason.value: True,
            'legacyActualBudgetReportPresent': False}
        if failure.provider_failure is not None:
            checks['legacyProviderObserved_' + failure.provider_failure.value] = True
        replay.record('diagnostic-same-basis-pages', 'check_diagnostics', checks, 0)
        return
    if legacy.get('status') != 'qualified':
        checks = {
            'legacyRequestReachedScopeCap': legacy.get('status') == 'rejected'
                and legacy.get('reason') == 'scope-limit-exceeded',
            'legacyRefusalHasNoContinuation': not _continuation(legacy),
            'boundedDrainCompleted': False,
        }
        checks.update(_legacy_refusal_observations(legacy))
        replay.record('diagnostic-same-basis-pages', 'check_diagnostics', checks, 0, legacy)
        return
    request = DiagnosticRequest()
    first = _invoke(replay, request)
    replayed = _invoke(replay, request)
    result = drain_diagnostics(replay, request, first)
    checks = {
        'firstEnumerationQualified': first.get('status') == 'qualified',
        'firstInventoryUnknown': first.get('progress', {}).get('inventory') == {'type': 'enumerating'},
        'fileCapStoppedEnumeration': first.get('progress', {}).get('stop') == 'enumeration_file_limit',
        'firstReplyReplayIdentical': not _semantic_difference(first, replayed),
        'firstReplayActualReportsValid': _valid_report(first, request) and _valid_report(replayed, request),
        'boundedDrainCompleted': isinstance(result, DiagnosticDrained),
    }
    checks.update({'firstReplayField_' + field: False for field in _semantic_difference(first, replayed)})
    count = 0
    if isinstance(result, DiagnosticDrained):
        final = result.pages[-1]
        coverage = final['progress']['analyzedFiles']
        checks['scopeExceedsOldHardCap'] = len(coverage) > 2
        checks['originalAuthorityEveryPage'] = all(page.get('live') == replay.live for page in result.pages)
        checks['everyPageHonorsDiagnosticLimit'] = all(len(page.get('diagnostics', [])) <= 1 for page in result.pages)
        count = sum(len(page.get('diagnostics', [])) for page in result.pages)
    else:
        checks['drain' + result.failure.value] = False
        checks.update({'drainField_' + field: False for field in result.differing_fields})
    checks.update(heavy_file_checks(replay))
    checks.update(independent_budget_checks(replay))
    replay.record('diagnostic-same-basis-pages', 'check_diagnostics', checks, count, first)


def _diagnostic_records(drained):
    return tuple((item['severity'], item['code'], item['message'],
        item['location']['file'], item['location']['range']['startInclusive'],
        item['location']['range']['endExclusive'])
        for page in drained.pages for item in page.get('diagnostics', []))


def heavy_file_checks(replay):
    """The authored deprecated callable has three separate calls with the same warning text."""
    path = 'src/main/kotlin/ReadDiagnosticPages.kt'
    high_request = DiagnosticRequest(path, 1000, execution_budget=replace(DiagnosticGrant(), max_results=1000))
    low_request = DiagnosticRequest(path, 1000)
    high = drain_diagnostics(replay, high_request, _invoke(replay, high_request), verify_replay=True)
    low = drain_diagnostics(replay, low_request, _invoke(replay, low_request), verify_replay=True)
    semantic_request = DiagnosticRequest(path, 1)
    semantic = drain_diagnostics(replay, semantic_request, _invoke(replay, semantic_request))
    checks = {
        'heavySemanticLimitDrainCompleted': isinstance(semantic, DiagnosticDrained),
        'heavyHighDrainCompleted': isinstance(high, DiagnosticDrained),
        'heavySingleResultDrainCompleted': isinstance(low, DiagnosticDrained),
    }
    for label, result in (('heavyHigh', high), ('heavySingleResult', low), ('heavySemanticLimit', semantic)):
        if isinstance(result, DiagnosticDrainRejected):
            checks[label + 'Drain_' + result.failure.value] = False
            checks.update({label + 'Field_' + field: False for field in result.differing_fields})
    if not isinstance(high, DiagnosticDrained) or not isinstance(low, DiagnosticDrained):
        return checks
    expected, observed = _diagnostic_records(high), _diagnostic_records(low)
    deprecations = tuple(item for item in expected if item[1] == 'DEPRECATION')
    checks.update({
        'authoredThreeDeprecationOccurrences': len(deprecations) == 3,
        'sameMessageDifferentOccurrences': len({item[2] for item in deprecations}) == 1
            and len({item[3:] for item in deprecations}) == 3,
        'heavyOccurrenceOrderParity': expected == observed,
        'heavySemanticLimitParity': isinstance(semantic, DiagnosticDrained) and expected == _diagnostic_records(semantic),
        'heavyEachPageAtMostOneDiagnostic': all(len(page.get('diagnostics', [])) <= 1 for page in low.pages),
        'heavyCumulativeCountPreserved': low.pages[-1]['progress'].get('knownDiagnosticCount') == len(expected),
        'heavyOutputCoverageUnchanged': all(page['progress']['analyzedFiles']
            == low.pages[-1]['progress']['analyzedFiles'] for page in low.pages
            if page['progress'].get('stage') == 'output'),
        'heavyOutputStageObserved': any(page['progress'].get('stage') == 'output' for page in low.pages),
    })
    return checks


def _reported_limit(response, axis, requested):
    report = response.get('execution_budget', response.get('progress', {}).get('execution_budget', {}))
    limit = report.get(axis, {})
    return (limit.get('requested') == requested and isinstance(limit.get('effective'), int)
            and 0 < limit['effective'] <= requested)


def independent_budget_checks(replay):
    """Exercise one axis at a time; time/bytes observations do not imply forced exhaustion."""
    work = DiagnosticRequest(execution_budget=replace(DiagnosticGrant(), max_work_units=1))
    first = _invoke(replay, work)
    token = _continuation(first)
    checks = {
        'workOneReported': _reported_limit(first, 'max_work_units', 1),
        'workOneEnumerationStopObserved': first.get('progress', {}).get('stop') == 'enumeration_work_limit',
    }
    if isinstance(token, str):
        resumed = replace(work, continuation=token)
        stopped = _invoke(replay, resumed)
        checks['workPrefixNoProgressRejected'] = (stopped.get('status') == 'rejected'
            and stopped.get('reason') == 'enumeration-work-grant-too-small'
            and stopped.get('next_action') == 'increase_execution_budget'
            and _reported_limit(stopped, 'max_work_units', 1))
        larger = replace(resumed, execution_budget=DiagnosticGrant())
        checks['workIncreaseSameCheckpointDrained'] = isinstance(
            drain_diagnostics(replay, larger, _invoke(replay, larger)), DiagnosticDrained)
    else:
        checks['workPrefixNoProgressRejected'] = False
        checks['workIncreaseSameCheckpointDrained'] = False
    for axis, value in (('max_elapsed_ms', 1), ('max_returned_bytes', 2048)):
        request = DiagnosticRequest('src/main/kotlin/ReadDiagnosticPages.kt', 1000,
            execution_budget=replace(DiagnosticGrant(max_results=1000), **{axis: value}))
        label = 'timeOne' if axis == 'max_elapsed_ms' else 'bytes2048'
        try:
            response = _invoke(replay, request)
            for _ in range(8):
                token = _continuation(response)
                if not isinstance(token, str):
                    break
                request = replace(request, continuation=token)
                response = _invoke(replay, request)
        except ReadTransportRejected as failure:
            checks[label + 'Reported'] = False
            checks[label + 'TransportObserved_' + failure.reason.value] = True
            if failure.provider_failure is not None:
                checks[label + 'ProviderObserved_' + failure.provider_failure.value] = True
            continue
        checks[label + 'Reported'] = _reported_limit(response, axis, value)
        host_rejected = response.get('outcome') == 'rejected'
        checks[label + 'FiniteOutcome'] = host_rejected or response.get('status') in ('complete', 'qualified', 'rejected')
        if host_rejected:
            checks[label + 'HostBoundaryObserved'] = True
            checks[label + 'HostStage_' + response.get('stage', 'missing')] = response.get('stage') is not None
        # Name the actual terminal observation; a naturally completed request is not exhaustion evidence.
        observation = response.get('failure', response.get('reason', response.get('progress', {}).get('stop', 'missing')))
        checks[label + 'Observed_' + observation] = observation != 'missing'
    return checks


def _valid_report(response, request):
    return all(_reported_limit(response, axis, value)
        for axis, value in asdict(request.execution_budget).items())


def _semantic_difference(first, repeated):
    """Compare semantic page identity; preserve actual invocation reports on the original responses."""
    def differs(left, right, key):
        return key not in left or key not in right or left[key] != right[key]
    fields = [key for key in sorted(set(first) | set(repeated))
        if key not in ('execution_budget', 'progress') and differs(first, repeated, key)]
    first_progress, repeated_progress = first.get('progress'), repeated.get('progress')
    if isinstance(first_progress, dict) and isinstance(repeated_progress, dict):
        fields += ['progress.' + key for key in sorted(set(first_progress) | set(repeated_progress))
            if key != 'execution_budget' and differs(first_progress, repeated_progress, key)]
    elif ('progress' in first or 'progress' in repeated) and differs(first, repeated, 'progress'):
        fields.append('progress')
    # Only schema field names are retained, with a fixed aggregate bound; no diagnostic text or tokens.
    return tuple(fields[:16])


def _continuation(response):
    qualification = response.get('qualification')
    return qualification.get('continuation') if isinstance(qualification, dict) else None


def _legacy_refusal_observations(response):
    """Only schema-admitted finite codes and report-presence facts enter aggregated evidence."""
    boundary = ('canonical' if response.get('status') == 'rejected'
        else 'host-read' if response.get('outcome') == 'rejected'
        else 'endpoint' if response.get('type') == 'HOST_REJECTED' else 'unexpected-outcome')
    reason = response.get('reason', response.get('failure'))
    report = response.get('execution_budget', response.get('progress', {}).get('execution_budget'))
    checks = {'legacyBoundary_' + boundary: True, 'legacyActualBudgetReportPresent': isinstance(report, dict)}
    if isinstance(reason, str) and len(reason) <= 80:
        checks['legacyReason_' + reason] = True
    else:
        checks['legacyFiniteReasonObserved'] = False
    if isinstance(report, dict):
        checks['legacyConfiguredGrantReportValid'] = all(
            isinstance(report.get(axis), dict) and report[axis].get('requested') is None
            and isinstance(report[axis].get('effective'), int) and report[axis]['effective'] > 0
            for axis in ('max_work_units', 'max_elapsed_ms', 'max_results', 'max_returned_bytes'))
    return checks
