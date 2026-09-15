"""Disposable diagnostic-pages policy acceptance; bounded payloads stay in memory."""
from dataclasses import asdict, dataclass, replace
from enum import Enum


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


@dataclass(frozen=True)
class DiagnosticDrained:
    pages: tuple[dict, ...]


@dataclass(frozen=True)
class DiagnosticDrainRejected:
    failure: DiagnosticDrainFailure


MAXIMUM_PAGES = 128
MAXIMUM_RECORDS = 1000


def _invoke(replay, request):
    response = replay.transport.invoke(replay.surface, 'check_diagnostics', asdict(request))
    replay.transport.validate('check_diagnostics', response)
    return response


def drain_diagnostics(replay, request, first):
    pages, seen, analyzed = [], set(), set()
    response = first
    for _ in range(MAXIMUM_PAGES):
        if response.get('live') != replay.live:
            return DiagnosticDrainRejected(DiagnosticDrainFailure.AUTHORITY)
        if response.get('status') not in ('complete', 'qualified'):
            return DiagnosticDrainRejected(DiagnosticDrainFailure.OUTCOME)
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
        token = response.get('qualification', {}).get('continuation')
        if not isinstance(token, str) or not 1 <= len(token) <= 1048576 or token in seen:
            return DiagnosticDrainRejected(DiagnosticDrainFailure.CHECKPOINT)
        seen.add(token)
        request = replace(request, continuation=token)
        response = _invoke(replay, request)
    return DiagnosticDrainRejected(DiagnosticDrainFailure.PAGE_BOUND)


def run_diagnostic_pages_regression(replay):
    """Caller selects the typed disposable diagnostic-pages policy before invoking this helper."""
    legacy = _invoke(replay, LegacyDiagnosticRequest())
    if legacy.get('status') != 'qualified':
        checks = {
            'legacyRequestReachedScopeCap': legacy.get('status') == 'rejected'
                and legacy.get('reason') == 'scope-limit-exceeded',
            'legacyRefusalHasNoContinuation': not legacy.get('qualification', {}).get('continuation'),
            'boundedDrainCompleted': False,
        }
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
        'firstReplyReplayIdentical': first == replayed,
        'boundedDrainCompleted': isinstance(result, DiagnosticDrained),
    }
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
    replay.record('diagnostic-same-basis-pages', 'check_diagnostics', checks, count, first)
