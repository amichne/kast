"""Owned native overflow qualification; epoch movement alone never proves a VFS overflow."""
from dataclasses import asdict, dataclass, field, replace
from enum import Enum
import hashlib
import json
import os
import stat
import subprocess

from hosted_change_acceptance import admitted_live, AcceptanceRejected
from hosted_diagnostic_pages_regression import (
    DiagnosticRequest, DiagnosticDrainFailure, DiagnosticDrained, drain_diagnostics,
)
from hosted_read_transport import ReadTransportRejected, ReadTransportFailure
from native_fixture_probe import NativeFixtureProbe, NativeFixtureProbeError
from query_name_request import name_query


class AuthoritySurface(str, Enum):
    CLI = 'cli'
    PROVIDER = 'provider'


def _source_bytes(path):
    info = path.lstat()
    _require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
             and 0 < info.st_size <= 1024 * 1024, OverflowFailure.OWNERSHIP)
    return path.read_bytes()


def _ready(probe, contents):
    expected = hashlib.sha256(contents).hexdigest()
    observed = probe.request('AWAIT_REOPEN_READY', expected, timeout=60)
    evidence = observed.get('evidence', {})
    _require(observed.get('outcome') == 'SETUP_READY'
             and evidence.get('savedSha256') == expected
             and evidence.get('documentSha256') == expected
             and evidence.get('documentState') == 'SAVED_COMMITTED', OverflowFailure.READINESS)


def _query_live(transport, surface, workspace):
    response, _ = transport.invoke_observed(surface.value, 'query_symbols',
                                            asdict(name_query('ReadPageBudget', ('class',))))
    items = response.get('items', [])
    _require(response.get('status') == 'complete' and len(items) == 1
             and isinstance(items[0].get('ref'), str) and items[0]['ref'].startswith('exact:'),
             OverflowFailure.AUTHORITY)
    return admitted_live(response.get('live'), workspace)


class OverflowOutcome(str, Enum):
    PASSED = 'passed'
    REJECTED = 'rejected'


class OverflowFailure(str, Enum):
    OWNERSHIP = 'OVERFLOW_OWNERSHIP_REJECTED'
    RECEIPT = 'OVERFLOW_RECEIPT_NOT_OBSERVED'
    LOG_CHANGED = 'OVERFLOW_LOG_CHANGED'
    LOG_BOUND = 'OVERFLOW_LOG_BOUND_EXCEEDED'
    EPOCH = 'OVERFLOW_EPOCH_NOT_MOVED'
    AUTHORITY = 'OVERFLOW_AUTHORITY_REJECTED'
    READINESS = 'OVERFLOW_READINESS_REJECTED'
    TRANSPORT = 'OVERFLOW_TRANSPORT_REJECTED'
    IO = 'OVERFLOW_IO_REJECTED'
    RESTORATION = 'OVERFLOW_RESTORATION_REJECTED'
    DIAGNOSTIC = 'OVERFLOW_DIAGNOSTIC_QUALIFICATION_REJECTED'


class OverflowRejected(ValueError):
    def __init__(self, failure):
        self.failure = failure
        super().__init__(failure.value)


@dataclass(frozen=True)
class OverflowReceipt:
    host: str
    outcome: str = field(default='relevance_unknown', init=False)
    reason: str = field(default='BATCH_LIMIT', init=False)
    event: str = field(default='kast_hosted_vfs', init=False)


class RestorationStage(str, Enum):
    SOURCE_GUARD = 'source-image-guard'
    OWNED_FILES = 'owned-file-restoration'
    READINESS = 'restored-readiness'
    SEARCH = 'restored-search'
    EPOCH_ADVANCE = 'restored-epoch-advance'
    BASIS_AGREEMENT = 'restored-surface-basis-agreement'
    QUERY = 'restored-query'
    COMPLETE = 'complete'


class DiagnosticPhase(str, Enum):
    ISSUED = 'before-overflow-cursor'
    STALE = 'old-cursor-after-revalidation'
    FILE = 'fresh-one-file-scan'
    DIRECTORY = 'fresh-directory-scan'


class DiagnosticRefusal(str, Enum):
    UNAVAILABLE = 'continuation-unavailable'
    STALE = 'stale-continuation'
    ENUMERATION_INDEX_MODE_UNSUPPORTED = 'enumeration-index-mode-unsupported'
    EXECUTION_TIME_GRANT_TOO_SMALL = 'execution-time-grant-too-small'
    CONTINUATION_REQUEST_MISMATCH = 'continuation-request-mismatch'
    CONTINUATION_CAPACITY_EXCEEDED = 'continuation-capacity-exceeded'
    ENUMERATION_WORK_GRANT_TOO_SMALL = 'enumeration-work-grant-too-small'
    ENUMERATION_TIME_GRANT_TOO_SMALL = 'enumeration-time-grant-too-small'
    ENUMERATION_RETENTION_EXCEEDED = 'enumeration-retention-exceeded'
    COMPILER_UNIT_GRANT_TOO_SMALL = 'compiler-unit-grant-too-small'
    COMPILER_CONTRACT_VIOLATION = 'compiler-contract-violation'
    WORKSPACE_INDEX_UNAVAILABLE = 'workspace-index-unavailable'
    WORKSPACE_ROOT_MISMATCH = 'workspace-root-mismatch'
    STALE_GENERATION = 'stale-generation'
    OUTPUT_GRANT_TOO_SMALL = 'output-grant-too-small'
    WORKSPACE_NOT_READY = 'workspace-not-ready'
    SCOPE_REJECTED = 'scope-rejected'
    SCOPE_EMPTY = 'scope-empty'
    SCOPE_LIMIT_EXCEEDED = 'scope-limit-exceeded'
    SCOPE_UNAVAILABLE = 'scope-unavailable'


class DiagnosticMismatch(str, Enum):
    DOCUMENT = 'document'
    OPERATION = 'operation'
    STATUS = 'status'
    BASIS = 'basis'
    CURSOR = 'cursor'
    REFUSAL = 'refusal'
    RESTART = 'restart-guidance'
    HISTORICAL_OUTPUT = 'historical-output'


@dataclass(frozen=True)
class DiagnosticEvidence:
    phase: DiagnosticPhase
    surface: AuthoritySurface
    passed: bool
    schemaDigests: tuple[str, ...]
    mismatch: DiagnosticMismatch | None = None
    refusal: DiagnosticRefusal | None = None
    drainFailure: DiagnosticDrainFailure | None = None
    pages: int = 0
    analyzedFiles: int = 0
    diagnosticCount: int = 0


@dataclass
class _DiagnosticTransport:
    delegate: object
    digests: list[str]

    def invoke(self, surface, tool, request):
        result, digest = self.delegate.invoke_observed(surface, tool, request)
        self.digests.append(digest)
        return result

    def validate(self, tool, response):
        return self.delegate.validate(tool, response)


@dataclass(frozen=True)
class _DiagnosticReplay:
    transport: _DiagnosticTransport
    surface: str
    live: dict


def _diagnostic_evidence(surface, phase, response, digest, live):
    mismatch, refusal = None, None
    if not isinstance(response, dict):
        mismatch = DiagnosticMismatch.DOCUMENT
    elif response.get('operation') != 'diagnostic.check':
        mismatch = DiagnosticMismatch.OPERATION
    elif phase is DiagnosticPhase.ISSUED:
        token = response.get('qualification', {}).get('continuation')
        if response.get('status') != 'qualified':
            mismatch = DiagnosticMismatch.STATUS
        elif response.get('live') != live:
            mismatch = DiagnosticMismatch.BASIS
        elif not isinstance(token, str) or not 1 <= len(token) <= 1048576:
            mismatch = DiagnosticMismatch.CURSOR
    elif response.get('status') != 'rejected':
        mismatch = DiagnosticMismatch.STATUS
    else:
        try:
            refusal = DiagnosticRefusal(response.get('reason'))
        except (ValueError, TypeError):
            mismatch = DiagnosticMismatch.REFUSAL
        if mismatch is None and refusal not in (DiagnosticRefusal.UNAVAILABLE, DiagnosticRefusal.STALE):
            mismatch = DiagnosticMismatch.REFUSAL
        if mismatch is None and response.get('next_action') != 'restart_read':
            mismatch = DiagnosticMismatch.RESTART
        if mismatch is None and any(key in response for key in ('live', 'diagnostics', 'progress', 'qualification')):
            mismatch = DiagnosticMismatch.HISTORICAL_OUTPUT
    return DiagnosticEvidence(phase, surface, mismatch is None, (digest,), mismatch, refusal)


def _drain_fresh_diagnostics(transport, surface, live, phase):
    request = DiagnosticRequest('src/main/kotlin/Fixture.kt' if phase is DiagnosticPhase.FILE else 'src/main/kotlin')
    adapter = _DiagnosticTransport(transport, [])
    replay = _DiagnosticReplay(adapter, surface.value, live)
    first = adapter.invoke(surface.value, 'check_diagnostics', asdict(request))
    result = drain_diagnostics(replay, request, first)
    if not isinstance(result, DiagnosticDrained):
        return DiagnosticEvidence(phase, surface, False, tuple(adapter.digests), drainFailure=result.failure)
    return DiagnosticEvidence(phase, surface, True, tuple(adapter.digests), pages=len(result.pages),
        analyzedFiles=len(result.pages[-1]['progress']['analyzedFiles']),
        diagnosticCount=sum(len(page.get('diagnostics', [])) for page in result.pages))


@dataclass(frozen=True)
class OverflowReport:
    outcome: OverflowOutcome = OverflowOutcome.REJECTED
    failure: OverflowFailure | None = None
    restorationFailure: OverflowFailure | None = None
    restorationStage: RestorationStage | None = None
    restorationSurface: AuthoritySurface | None = None
    restorationObservedEpoch: int | None = None
    restorationTransportFailure: ReadTransportFailure | None = None
    diagnosticObservations: tuple[DiagnosticEvidence, ...] = ()
    receipt: OverflowReceipt | None = None
    beforeEpoch: int | None = None
    overflowEpoch: int | None = None
    restoredEpoch: int | None = None
    filesCreated: int = 0
    filesRestored: bool = False
    readinessTransitions: int = 0
    schemaVersion: int = 1
    evidence: str = 'native-post-burst-host-correlated-vfs-receipt-and-diagnostics'


def _require(condition, failure):
    if not condition:
        raise OverflowRejected(failure)


class OverflowLogWindow:
    """Bounded suffix on one owned inode, beginning strictly after the pre-burst offset."""
    MAX_BYTES = 16 * 1024 * 1024

    def __init__(self, path):
        self.path = path

    def __enter__(self):
        _require(self.path.resolve(strict=True) == self.path, OverflowFailure.OWNERSHIP)
        descriptor = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW)
        self.stream = os.fdopen(descriptor, 'rb')
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            self.stream.close()
            raise OverflowRejected(OverflowFailure.OWNERSHIP)
        self.identity = info.st_dev, info.st_ino
        self.offset = self.stream.seek(0, os.SEEK_END)
        self.partial = False
        if self.offset:
            self.stream.seek(self.offset - 1)
            self.partial = self.stream.read(1) != b'\n'
        self.stream.seek(self.offset)
        return self

    def __exit__(self, *_):
        self.stream.close()

    def observe(self, host):
        info = self.path.lstat()
        _require(stat.S_ISREG(info.st_mode) and (info.st_dev, info.st_ino) == self.identity
                 and info.st_size >= self.offset, OverflowFailure.LOG_CHANGED)
        raw = self.stream.read(self.MAX_BYTES + 1)
        _require(len(raw) <= self.MAX_BYTES, OverflowFailure.LOG_BOUND)
        lines = raw.split(b'\n')
        if self.partial:
            lines = lines[1:]
        for line in lines[:-1]:
            if b'kast_hosted_vfs' not in line:
                continue
            start = line.find(b'{')
            try:
                document = json.loads(line[start:]) if start >= 0 else None
            except (ValueError, UnicodeError):
                raise OverflowRejected(OverflowFailure.RECEIPT) from None
            if document == asdict(OverflowReceipt(host)):
                return OverflowReceipt(host)
        raise OverflowRejected(OverflowFailure.RECEIPT)


@dataclass(frozen=True)
class OwnedBurstFile:
    path: Path
    device: int
    inode: int
    digest: str


def _create_burst(directory, owned):
    _require(directory.resolve(strict=True) == directory and directory.is_dir(), OverflowFailure.OWNERSHIP)
    for index in range(3):
        path = directory / f'KastOverflowAcceptance{index}.kt'
        contents = f'package fixture\ninternal class KastOverflowAcceptance{index}\n'.encode()
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            info = os.fstat(output.fileno())
            owned.append(OwnedBurstFile(path, info.st_dev, info.st_ino, hashlib.sha256(contents).hexdigest()))
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())


def _restore_burst(owned):
    # Guard the complete set before deleting anything. A changed/partial file is retained and reported.
    for entry in owned:
        info = entry.path.lstat()
        _require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                 and (info.st_dev, info.st_ino) == (entry.device, entry.inode)
                 and hashlib.sha256(_source_bytes(entry.path)).hexdigest() == entry.digest,
                 OverflowFailure.RESTORATION)
    for entry in owned:
        entry.path.unlink()


def run_vfs_overflow_regression(isolation, fixture, transport, live):
    """Prove a native overflow receipt, query epoch movement, diagnostics, and owned restoration."""
    report, successor = OverflowReport(), None
    owned = []
    source = None
    original = None
    probe = None
    workspace = fixture.workspace
    try:
        _require(workspace == isolation.root / 'workspace' and workspace.resolve() == workspace
                 and stat.S_IMODE(isolation.root.stat().st_mode) == 0o700, OverflowFailure.OWNERSHIP)
        source = workspace / 'src/main/kotlin/Fixture.kt'
        original = _source_bytes(source)
        current = admitted_live(live, workspace)
        probe = NativeFixtureProbe(isolation.root, workspace)
        report = replace(report, beforeEpoch=current['epoch'])
        issued = {}
        for surface in AuthoritySurface:
            observed = _query_live(transport, surface, workspace)
            _require(observed == current, OverflowFailure.EPOCH)
            diagnostic_request = DiagnosticRequest()
            diagnostic, digest = transport.invoke_observed(surface.value, 'check_diagnostics',
                                                           asdict(diagnostic_request))
            evidence = _diagnostic_evidence(surface, DiagnosticPhase.ISSUED, diagnostic, digest, current)
            report = replace(report, diagnosticObservations=report.diagnosticObservations + (evidence,))
            _require(evidence.passed, OverflowFailure.DIAGNOSTIC)
            issued[surface] = replace(diagnostic_request,
                                      continuation=diagnostic['qualification']['continuation'])
        with OverflowLogWindow(isolation.root / 'ide/log/idea.log') as window:
            _create_burst(source.parent, owned)
            report = replace(report, filesCreated=len(owned))
            _require(_source_bytes(source) == original, OverflowFailure.OWNERSHIP)
            _ready(probe, original)
            report = replace(report, readinessTransitions=1, receipt=window.observe(current['host']))
        moved = None
        for surface in AuthoritySurface:
            observed = _query_live(transport, surface, workspace)
            _require(observed['epoch'] > current['epoch'] and (moved is None or observed == moved),
                     OverflowFailure.EPOCH)
            moved = observed
            report = replace(report, overflowEpoch=observed['epoch'])
            diagnostic, digest = transport.invoke_observed(surface.value, 'check_diagnostics',
                                                           asdict(issued[surface]))
            evidence = _diagnostic_evidence(surface, DiagnosticPhase.STALE, diagnostic, digest, observed)
            report = replace(report, diagnosticObservations=report.diagnosticObservations + (evidence,))
            _require(evidence.passed, OverflowFailure.DIAGNOSTIC)
            for phase in (DiagnosticPhase.FILE, DiagnosticPhase.DIRECTORY):
                evidence = _drain_fresh_diagnostics(transport, surface, observed, phase)
                report = replace(report, diagnosticObservations=report.diagnosticObservations + (evidence,))
                _require(evidence.passed, OverflowFailure.DIAGNOSTIC)
        report = replace(report, outcome=OverflowOutcome.PASSED)
    except OverflowRejected as error:
        report = replace(report, failure=error.failure)
    except NativeFixtureProbeError:
        report = replace(report, failure=OverflowFailure.READINESS)
    except (ReadTransportRejected, AcceptanceRejected):
        report = replace(report, failure=OverflowFailure.TRANSPORT)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        report = replace(report, failure=OverflowFailure.IO)
    finally:
        if owned:
            try:
                report = replace(report, restorationStage=RestorationStage.SOURCE_GUARD)
                _require(_source_bytes(source) == original, OverflowFailure.RESTORATION)
                report = replace(report, restorationStage=RestorationStage.OWNED_FILES)
                _restore_burst(owned)
                report = replace(report, restorationStage=RestorationStage.READINESS)
                _ready(probe, original)
                report = replace(report, filesRestored=True,
                                 readinessTransitions=report.readinessTransitions + 1)
                for surface in AuthoritySurface:
                    report = replace(report, restorationStage=RestorationStage.QUERY,
                                     restorationSurface=surface)
                    observed = _query_live(transport, surface, workspace)
                    report = replace(report, restorationObservedEpoch=observed['epoch'],
                                     restorationStage=RestorationStage.EPOCH_ADVANCE)
                    _require(observed['epoch'] > (report.overflowEpoch or report.beforeEpoch),
                             OverflowFailure.EPOCH)
                    report = replace(report, restorationStage=RestorationStage.BASIS_AGREEMENT)
                    _require(successor is None or observed == successor, OverflowFailure.EPOCH)
                    successor = observed
                report = replace(report, restoredEpoch=successor['epoch'],
                                 restorationStage=RestorationStage.COMPLETE)
            except (OverflowRejected, NativeFixtureProbeError, ReadTransportRejected,
                    AcceptanceRejected, OSError, ValueError, KeyError, TypeError,
                    subprocess.SubprocessError) as error:
                cause = (error.failure if isinstance(error, OverflowRejected) else
                         OverflowFailure.TRANSPORT if isinstance(error, (ReadTransportRejected, AcceptanceRejected)) else
                         OverflowFailure.READINESS if isinstance(error, NativeFixtureProbeError) else OverflowFailure.IO)
                report = replace(report, failure=report.failure or OverflowFailure.RESTORATION,
                                 restorationFailure=cause,
                                 restorationTransportFailure=error.reason if isinstance(error, ReadTransportRejected) else None)
                successor = None
        if report.failure is not None or not report.filesRestored:
            report = replace(report, outcome=OverflowOutcome.REJECTED)
    return report, successor
