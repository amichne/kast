"""Owned native overflow qualification; epoch movement alone never proves a VFS overflow."""
from dataclasses import asdict, dataclass, field, replace
from enum import Enum
from pathlib import Path
import hashlib
import json
import os
import stat
import subprocess

from hosted_authority_read_regression import (
    _AuthorityReplay, _ready, _source_bytes, AuthorityCase, AuthorityCaseName,
    AuthorityFailure, AuthorityRefusal, AuthorityRejected, AuthoritySurface, ContinueSourcePage,
    InspectionStage, InspectionMismatch, InspectionRefusal, InspectionObservation,
    InspectionWireRejected, InspectionUnknownRefusal, InspectionContractRejected, InspectionTransportRejected,
)
from hosted_change_acceptance import admitted_live, AcceptanceRejected
from hosted_diagnostic_pages_regression import (
    DiagnosticRequest, DiagnosticDrainFailure, DiagnosticDrained, drain_diagnostics,
)
from hosted_source_read_regression import SymbolAnchor
from hosted_raw_symbol_regression import DiscoverRequest, CandidateTarget, InspectRequest
from hosted_compact_source_regression import FormattedSourceRequest, SourceAnchor
from hosted_read_transport import ReadTransportRejected, ReadTransportFailure
from native_fixture_probe import NativeFixtureProbe, NativeFixtureProbeError


class OverflowOutcome(str, Enum):
    PASSED = 'passed'
    REJECTED = 'rejected'


class OverflowFailure(str, Enum):
    OWNERSHIP = 'OVERFLOW_OWNERSHIP_REJECTED'
    RECEIPT = 'OVERFLOW_RECEIPT_NOT_OBSERVED'
    LOG_CHANGED = 'OVERFLOW_LOG_CHANGED'
    LOG_BOUND = 'OVERFLOW_LOG_BOUND_EXCEEDED'
    EPOCH = 'OVERFLOW_EPOCH_NOT_MOVED'
    STRICT = 'OVERFLOW_STRICT_REFUSAL_MISSING'
    AUTHORITY = 'OVERFLOW_AUTHORITY_REJECTED'
    READINESS = 'OVERFLOW_READINESS_REJECTED'
    TRANSPORT = 'OVERFLOW_TRANSPORT_REJECTED'
    IO = 'OVERFLOW_IO_REJECTED'
    RESTORATION = 'OVERFLOW_RESTORATION_REJECTED'
    DIAGNOSTIC = 'OVERFLOW_DIAGNOSTIC_QUALIFICATION_REJECTED'
    REFERENCE = 'OVERFLOW_REFERENCE_QUALIFICATION_REJECTED'


class OverflowRejected(ValueError):
    def __init__(self, failure):
        self.failure = failure
        super().__init__(failure.value)


@dataclass(frozen=True)
class StrictTarget:
    selector: str
    type: str = field(default='exact', init=False)


@dataclass(frozen=True)
class StrictRequest:
    target: StrictTarget


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
    SOURCE_PAGE = 'restored-source-page'
    COMPLETE = 'complete'


@dataclass(frozen=True)
class StrictObservation:
    surface: AuthoritySurface
    schemaDigest: str | None
    observation: InspectionObservation


def _strict_observation(response):
    stage = InspectionStage.STRICT
    if not isinstance(response, dict):
        return InspectionContractRejected(stage, InspectionMismatch.DOCUMENT)
    if response.get('operation') != 'symbol.inspect':
        return InspectionContractRejected(stage, InspectionMismatch.OPERATION)
    if response.get('status') != 'rejected':
        return InspectionContractRejected(stage, InspectionMismatch.STATUS)
    # Both native surfaces consume CanonicalSymbolCliDocuments / rejection.cliName(),
    # whose exact transformation is name.lowercase().replace('_', '-'). This is not wire JSON.
    reasons = {reason.value: reason for reason in InspectionRefusal}
    refusal = reasons.get(response.get('reason')) if isinstance(response.get('reason'), str) else None
    return (InspectionWireRejected(stage, refusal) if refusal is not None
            else InspectionUnknownRefusal(stage))


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


class ReferenceFamily(str, Enum):
    CANDIDATE = 'candidate'
    SOURCE = 'source'


class ReferencePhase(str, Enum):
    DISCOVER = 'candidate-discovery'
    CANDIDATE_CURRENT = 'current-candidate-inspection'
    SOURCE_ISSUE = 'compact-source-issuance'
    SOURCE_CURRENT = 'current-source-anchor-read'
    OLD_CANDIDATE = 'old-candidate-refusal'
    OLD_SOURCE = 'old-source-anchor-refusal'


class SourceReferenceRefusal(str, Enum):
    WRONG_FAMILY = 'wrong-family'
    MALFORMED = 'malformed'
    INVALID_PAYLOAD_ENCODING = 'invalid-payload-encoding'
    PAYLOAD_DIGEST_MISMATCH = 'payload-digest-mismatch'
    INVALID_DOCUMENT = 'invalid-document'
    FOREIGN_WORKSPACE = 'foreign-workspace'
    INCOMPATIBLE_AUTHORITY = 'incompatible-authority'
    STALE_AUTHORITY = 'stale-authority'
    UNSUPPORTED_VERSION = 'unsupported-version'
    LIVE_AUTHORITY_REQUIRED = 'live-authority-required'
    UNAVAILABLE = 'unavailable'
    TOKEN_TOO_LONG = 'token-too-long'
    SNAPSHOT_REJECTED = 'snapshot-rejected'
    SELECTOR_REJECTED = 'selector-rejected'
    SELECTOR_TOO_DEEP = 'selector-too-deep'


@dataclass(frozen=True)
class ReferenceEvidence:
    family: ReferenceFamily
    surface: AuthoritySurface
    passed: bool
    schemaDigest: str
    refusal: InspectionRefusal | SourceReferenceRefusal | None = None
    mismatch: InspectionMismatch | None = None


def _reference_refusal(family, surface, response, digest):
    mismatch, refusal = None, None
    operation = 'symbol.inspect' if family is ReferenceFamily.CANDIDATE else 'source.read'
    if not isinstance(response, dict):
        mismatch = InspectionMismatch.DOCUMENT
    elif response.get('operation') != operation:
        mismatch = InspectionMismatch.OPERATION
    elif response.get('status') != 'rejected':
        mismatch = InspectionMismatch.STATUS
    else:
        raw = response.get('reason')
        try:
            if family is ReferenceFamily.CANDIDATE:
                refusal = InspectionRefusal(raw)
            elif isinstance(raw, dict) and set(raw) == {'type', 'role', 'reason'} and \
                    raw['type'] == 'reference-rejected' and raw['role'] == 'source':
                refusal = SourceReferenceRefusal(raw['reason'])
        except (ValueError, TypeError):
            pass
    expected = (InspectionRefusal.CANDIDATE_STALE if family is ReferenceFamily.CANDIDATE
                else SourceReferenceRefusal.SNAPSHOT_REJECTED)
    return ReferenceEvidence(family, surface, mismatch is None and refusal is expected, digest, refusal, mismatch)


@dataclass(frozen=True)
class OverflowReport:
    outcome: OverflowOutcome = OverflowOutcome.REJECTED
    failure: OverflowFailure | None = None
    authorityFailure: AuthorityFailure | None = None
    restorationFailure: OverflowFailure | None = None
    restorationStage: RestorationStage | None = None
    restorationSurface: AuthoritySurface | None = None
    restorationObservedEpoch: int | None = None
    restorationAuthorityFailure: AuthorityFailure | None = None
    restorationTransportFailure: ReadTransportFailure | None = None
    strictObservations: tuple[StrictObservation, ...] = ()
    diagnosticObservations: tuple[DiagnosticEvidence, ...] = ()
    referenceObservations: tuple[ReferenceEvidence, ...] = ()
    referencePhase: ReferencePhase | None = None
    referenceSurface: AuthoritySurface | None = None
    receipt: OverflowReceipt | None = None
    cases: tuple[AuthorityCase, ...] = ()
    strictSchemaDigests: tuple[str, ...] = ()
    beforeEpoch: int | None = None
    overflowEpoch: int | None = None
    restoredEpoch: int | None = None
    filesCreated: int = 0
    filesRestored: bool = False
    readinessTransitions: int = 0
    schemaVersion: int = 1
    evidence: str = 'native-post-burst-host-correlated-vfs-receipt'


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
    """Return (bounded report, restored successor live or None), with no native success inferred."""
    report, replay, successor = OverflowReport(), None, None
    owned = []
    try:
        workspace = fixture.workspace
        _require(workspace == isolation.root / 'workspace' and workspace.resolve() == workspace
                 and stat.S_IMODE(isolation.root.stat().st_mode) == 0o700, OverflowFailure.OWNERSHIP)
        source = workspace / 'src/main/kotlin/Fixture.kt'
        original = _source_bytes(source)
        current = admitted_live(live, workspace)
        replay = _AuthorityReplay(transport, workspace, current)
        probe = NativeFixtureProbe(isolation.root, workspace)
        report = replace(report, beforeEpoch=current['epoch'])
        issued = {}
        issued_references = {}
        for surface in AuthoritySurface:
            request, observed = replay.search(surface)
            _require(observed == current, OverflowFailure.EPOCH)
            token = replay.page(AuthorityCaseName.ISSUED, surface, request, current, 'pageItem00')
            report = replace(report, referencePhase=ReferencePhase.DISCOVER, referenceSurface=surface)
            candidates, _ = replay.call(surface, 'symbol_lookup', DiscoverRequest())
            items = candidates.get('items', [])
            candidate = items[0] if len(items) == 1 else {}
            _require(candidates.get('status') == 'complete' and candidate.get('name') == 'NativeChangeTarget'
                     and candidate.get('file') == str(source) and candidate.get('type') == 'declaration'
                     and isinstance(candidate.get('candidateSelector'), str) and bool(candidate['candidateSelector']),
                     OverflowFailure.REFERENCE)
            candidate_request = InspectRequest(CandidateTarget(candidate['candidateSelector']))
            report = replace(report, referencePhase=ReferencePhase.CANDIDATE_CURRENT)
            inspected, _ = replay.call(surface, 'symbol_inspect', candidate_request)
            _require(inspected.get('status') == 'complete' and inspected.get('live') == current,
                     OverflowFailure.REFERENCE)
            compact_request = FormattedSourceRequest(request.anchor)
            report = replace(report, referencePhase=ReferencePhase.SOURCE_ISSUE)
            compact, _ = replay.call(surface, 'source_read', compact_request)
            structures = [section for section in compact.get('content', []) if section.get('type') == 'structure']
            structure = structures[0] if len(structures) == 1 else {}
            table = structure.get('selections', [])
            _require(compact.get('status') in ('complete', 'qualified')
                     and structure.get('snapshot', {}).get('live') == current and bool(table)
                     and isinstance(table[0].get('selector'), str) and bool(table[0]['selector']),
                     OverflowFailure.REFERENCE)
            source_request = replace(compact_request, anchor=SourceAnchor(table[0]['selector']))
            report = replace(report, referencePhase=ReferencePhase.SOURCE_CURRENT)
            restored, _ = replay.call(surface, 'source_read', source_request)
            _require(restored.get('status') in ('complete', 'qualified')
                     and any(section.get('type') == 'structure'
                             and section.get('snapshot', {}).get('live') == current
                             for section in restored.get('content', [])), OverflowFailure.REFERENCE)
            issued_references[surface] = candidate_request, source_request
            diagnostic_request = DiagnosticRequest()
            diagnostic, digest = replay.call(surface, 'check_diagnostics', diagnostic_request)
            evidence = _diagnostic_evidence(surface, DiagnosticPhase.ISSUED, diagnostic, digest, current)
            report = replace(report, diagnosticObservations=report.diagnosticObservations + (evidence,))
            _require(evidence.passed, OverflowFailure.DIAGNOSTIC)
            issued[surface] = request, token, replace(diagnostic_request,
                continuation=diagnostic['qualification']['continuation'])
        with OverflowLogWindow(isolation.root / 'ide/log/idea.log') as window:
            _create_burst(source.parent, owned)
            report = replace(report, filesCreated=len(owned))
            _require(_source_bytes(source) == original, OverflowFailure.OWNERSHIP)
            _ready(probe, original)
            report = replace(report, readinessTransitions=1, receipt=window.observe(current['host']))
        moved, strict_digests = None, []
        for surface in AuthoritySurface:
            fresh, observed = replay.search(surface)
            _require(observed['epoch'] > current['epoch'] and (moved is None or observed == moved),
                     OverflowFailure.EPOCH)
            moved = observed
            report = replace(report, overflowEpoch=observed['epoch'])
            old, token, old_diagnostic = issued[surface]
            try:
                refused, digest = replay.call(surface, 'symbol_inspect', StrictRequest(StrictTarget(old.anchor.selector)))
            except ReadTransportRejected as error:
                observation = InspectionTransportRejected(InspectionStage.STRICT, error.reason, error.provider_failure)
                report = replace(report, strictObservations=report.strictObservations +
                    (StrictObservation(surface, None, observation),))
                raise
            observation = _strict_observation(refused)
            report = replace(report, strictObservations=report.strictObservations +
                (StrictObservation(surface, digest, observation),))
            _require(isinstance(observation, InspectionWireRejected)
                     and observation.refusal is InspectionRefusal.EXACT_SELECTOR_STALE, OverflowFailure.STRICT)
            strict_digests.append(digest)
            replay.reject(AuthorityCaseName.OLD_CURSOR, surface,
                          replace(fresh, page=ContinueSourcePage(token)), AuthorityRefusal.STALE_CONTINUATION)
            replay.page(AuthorityCaseName.FRESH, surface, fresh, observed, 'pageItem00')
            # The old selector is a locator only in this explicit operation; no search result is substituted.
            symbol = replay.inspect(InspectionStage.REVALIDATE, surface, old.anchor.selector, observed)
            replay.inspect(InspectionStage.STRICT, surface, symbol['selector'], observed, symbol)
            reacquired = replace(old, anchor=SymbolAnchor(symbol['selector']))
            replay.page(AuthorityCaseName.FRESH, surface, reacquired, observed, 'pageItem00')
            refused, digest = replay.call(surface, 'symbol_inspect', StrictRequest(StrictTarget(old.anchor.selector)))
            observation = _strict_observation(refused)
            report = replace(report, strictObservations=report.strictObservations +
                (StrictObservation(surface, digest, observation),))
            _require(isinstance(observation, InspectionWireRejected)
                     and observation.refusal is InspectionRefusal.EXACT_SELECTOR_STALE, OverflowFailure.STRICT)
            replay.reject(AuthorityCaseName.OLD_REFERENCE, surface, old, AuthorityRefusal.UNAVAILABLE_REFERENCE)
            replay.reject(AuthorityCaseName.OLD_CURSOR, surface,
                          replace(reacquired, page=ContinueSourcePage(token)), AuthorityRefusal.STALE_CONTINUATION)
            diagnostic, digest = replay.call(surface, 'check_diagnostics', old_diagnostic)
            evidence = _diagnostic_evidence(surface, DiagnosticPhase.STALE, diagnostic, digest, observed)
            report = replace(report, diagnosticObservations=report.diagnosticObservations + (evidence,))
            _require(evidence.passed, OverflowFailure.DIAGNOSTIC)
            candidate_request, source_request = issued_references[surface]
            for family, tool, reference_request in (
                (ReferenceFamily.CANDIDATE, 'symbol_inspect', candidate_request),
                (ReferenceFamily.SOURCE, 'source_read', source_request),
            ):
                report = replace(report, referenceSurface=surface, referencePhase=
                    ReferencePhase.OLD_CANDIDATE if family is ReferenceFamily.CANDIDATE else ReferencePhase.OLD_SOURCE)
                refused, digest = replay.call(surface, tool, reference_request)
                evidence = _reference_refusal(family, surface, refused, digest)
                report = replace(report, referenceObservations=report.referenceObservations + (evidence,))
                _require(evidence.passed, OverflowFailure.REFERENCE)
            for phase in (DiagnosticPhase.FILE, DiagnosticPhase.DIRECTORY):
                evidence = _drain_fresh_diagnostics(transport, surface, observed, phase)
                report = replace(report, diagnosticObservations=report.diagnosticObservations + (evidence,))
                _require(evidence.passed, OverflowFailure.DIAGNOSTIC)
        report = replace(report, outcome=OverflowOutcome.PASSED, strictSchemaDigests=tuple(strict_digests))
    except OverflowRejected as error:
        report = replace(report, failure=error.failure)
    except AuthorityRejected as error:
        report = replace(report, failure=OverflowFailure.AUTHORITY, authorityFailure=error.reason)
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
                report = replace(report, filesRestored=True, readinessTransitions=report.readinessTransitions + 1)
                for surface in AuthoritySurface:
                    report = replace(report, restorationStage=RestorationStage.SEARCH, restorationSurface=surface)
                    fresh, observed = replay.search(surface)
                    report = replace(report, restorationStage=RestorationStage.EPOCH_ADVANCE,
                                     restorationObservedEpoch=observed['epoch'])
                    _require(observed['epoch'] > (report.overflowEpoch or report.beforeEpoch), OverflowFailure.EPOCH)
                    report = replace(report, restorationStage=RestorationStage.BASIS_AGREEMENT)
                    _require(successor is None or observed == successor, OverflowFailure.EPOCH)
                    successor = observed
                    report = replace(report, restorationStage=RestorationStage.SOURCE_PAGE)
                    replay.page(AuthorityCaseName.RESTORED, surface, fresh, observed, 'pageItem00')
                report = replace(report, restoredEpoch=successor['epoch'], restorationStage=RestorationStage.COMPLETE)
            except (OverflowRejected, AuthorityRejected, NativeFixtureProbeError, ReadTransportRejected,
                    AcceptanceRejected, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
                cause = (error.failure if isinstance(error, OverflowRejected) else
                         OverflowFailure.AUTHORITY if isinstance(error, AuthorityRejected) else
                         OverflowFailure.TRANSPORT if isinstance(error, (ReadTransportRejected, AcceptanceRejected)) else
                         OverflowFailure.READINESS if isinstance(error, NativeFixtureProbeError) else OverflowFailure.IO)
                report = replace(report, failure=report.failure or OverflowFailure.RESTORATION,
                                 restorationFailure=cause,
                                 restorationAuthorityFailure=error.reason if isinstance(error, AuthorityRejected) else None,
                                 restorationTransportFailure=error.reason if isinstance(error, ReadTransportRejected) else None)
                successor = None
        if replay is not None:
            report = replace(report, cases=tuple(replay.cases), filesCreated=len(owned))
        if report.failure is not None or not report.filesRestored:
            report = replace(report, outcome=OverflowOutcome.REJECTED)
    return report, successor
