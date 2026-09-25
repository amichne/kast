"""Ordinary owned file edits qualify live read authority; payloads stay in memory."""
from dataclasses import asdict, dataclass, field, replace
from enum import Enum
import hashlib
import json
import os
import stat
import subprocess
from released_acceptance_product import product_executable

from hosted_change_acceptance import admitted_live, AcceptanceRejected
from hosted_read_transport import ReadTransportRejected, ReadTransportFailure, ReadProviderFailure
from hosted_source_read_regression import source_budget_anchor_query, SourceFunctionRequest, SymbolAnchor
from native_fixture_probe import NativeFixtureProbe, NativeFixtureProbeError


class AuthorityOutcome(str, Enum):
    PASSED = 'passed'
    REJECTED = 'rejected'


class AuthorityFailure(str, Enum):
    SOURCE_GUARD = 'AUTHORITY_SOURCE_GUARD'
    ISSUER = 'AUTHORITY_ISSUER_REJECTED'
    EPOCH = 'AUTHORITY_EPOCH_NOT_MOVED'
    REJECTION = 'AUTHORITY_EXPECTED_REJECTION_MISSING'
    READINESS = 'AUTHORITY_READINESS_REJECTED'
    TRANSPORT = 'AUTHORITY_TRANSPORT_REJECTED'
    FOREIGN = 'AUTHORITY_FOREIGN_ROOT_REJECTED'
    REVALIDATION = 'AUTHORITY_EXACT_REVALIDATION_REJECTED'
    IO = 'AUTHORITY_IO_REJECTED'
    RESTORATION = 'AUTHORITY_RESTORATION_REJECTED'
    DOCUMENT_IMAGE = 'AUTHORITY_READINESS_DOCUMENT_IMAGE_CHANGED'
    RESTORATION_DOCUMENT_IMAGE = 'AUTHORITY_RESTORATION_DOCUMENT_IMAGE_CHANGED'


class AuthorityRefusal(str, Enum):
    UNAVAILABLE_REFERENCE = 'reference-unavailable'
    STALE_CONTINUATION = 'source-snapshot-mismatch'
    UNAVAILABLE_CONTINUATION = 'continuation-unavailable'
    UNEXPECTED = 'unexpected-refusal'
    FOREIGN = 'ide-host-unavailable'


@dataclass(frozen=True)
class UnavailableSymbolReference:
    type: str = field(default='reference-rejected', init=False)
    role: str = field(default='symbol', init=False)
    reason: str = field(default='unavailable', init=False)


class InspectionStage(str, Enum):
    REVALIDATE = 'revalidate_exact'
    STRICT = 'exact'


class InspectionOutcome(str, Enum):
    ACCEPTED = 'accepted'
    WIRE_REJECTED = 'wire-rejected'
    UNKNOWN_REFUSAL = 'unknown-refusal'
    CONTRACT_REJECTED = 'contract-rejected'
    TRANSPORT_REJECTED = 'transport-rejected'


class InspectionMismatch(str, Enum):
    DOCUMENT = 'document'
    OPERATION = 'operation'
    STATUS = 'status'
    ACQUISITION = 'acquisition'
    LIVE_BASIS = 'live-basis'
    SELECTOR = 'selector'
    SYMBOL = 'symbol'


class InspectionRefusal(str, Enum):
    """CLI projection spellings used by both native transport surfaces; wire uses underscores."""
    WORKSPACE_NOT_READY = 'workspace-not-ready'
    SELECTOR_WRONG_KIND = 'selector-wrong-kind'
    SELECTOR_MALFORMED = 'selector-malformed'
    SELECTOR_WORKSPACE_MISMATCH = 'selector-workspace-mismatch'
    CANDIDATE_STALE = 'candidate-stale'
    CANDIDATE_NOT_DECLARATION = 'candidate-not-declaration'
    EXACT_SELECTOR_STALE = 'exact-selector-stale'
    AMBIGUOUS = 'ambiguous'
    NOT_FOUND = 'not-found'
    NATIVE_FAILURE = 'native-failure'
    WORKSPACE_INDEX_UNAVAILABLE = 'workspace-index-unavailable'
    UNSUPPORTED_DECLARATION = 'unsupported-declaration'
    REVALIDATION_UNRETAINED = 'revalidation-unretained'
    REVALIDATION_EXPIRED = 'revalidation-expired'
    REVALIDATION_CAPACITY = 'revalidation-capacity'
    REVALIDATION_WORK_LIMIT_REACHED = 'revalidation-work-limit-reached'
    REVALIDATION_TIME_LIMIT_REACHED = 'revalidation-time-limit-reached'
    REVALIDATION_RETIRED = 'revalidation-retired'
    REVALIDATION_CAPTURE_UNAVAILABLE = 'revalidation-capture-unavailable'
    REVALIDATION_WORKSPACE_MISMATCH = 'revalidation-workspace-mismatch'
    REVALIDATION_OWNER_MISMATCH = 'revalidation-owner-mismatch'
    REVALIDATION_WORKSPACE_NOT_READY = 'revalidation-workspace-not-ready'
    REVALIDATION_BASIS_MOVED = 'revalidation-basis-moved'
    REVALIDATION_CONTENT_CHANGED = 'revalidation-content-changed'
    REVALIDATION_CONTENT_UNCOMMITTED = 'revalidation-content-uncommitted'
    REVALIDATION_SCOPE_REJECTED = 'revalidation-scope-rejected'
    REVALIDATION_DECLARATION_MISSING = 'revalidation-declaration-missing'
    REVALIDATION_UNSUPPORTED_DECLARATION = 'revalidation-unsupported-declaration'
    REVALIDATION_AMBIGUOUS = 'revalidation-ambiguous'
    REVALIDATION_COMPILER_IDENTITY_CHANGED = 'revalidation-compiler-identity-changed'
    REVALIDATION_COMPILER_UNAVAILABLE = 'revalidation-compiler-unavailable'


@dataclass(frozen=True)
class InspectionAccepted:
    stage: InspectionStage
    outcome: InspectionOutcome = field(default=InspectionOutcome.ACCEPTED, init=False)


@dataclass(frozen=True)
class InspectionWireRejected:
    stage: InspectionStage
    refusal: InspectionRefusal
    outcome: InspectionOutcome = field(default=InspectionOutcome.WIRE_REJECTED, init=False)


@dataclass(frozen=True)
class InspectionUnknownRefusal:
    stage: InspectionStage
    outcome: InspectionOutcome = field(default=InspectionOutcome.UNKNOWN_REFUSAL, init=False)


@dataclass(frozen=True)
class InspectionContractRejected:
    stage: InspectionStage
    mismatch: InspectionMismatch
    outcome: InspectionOutcome = field(default=InspectionOutcome.CONTRACT_REJECTED, init=False)


@dataclass(frozen=True)
class InspectionTransportRejected:
    stage: InspectionStage
    refusal: ReadTransportFailure
    providerFailure: ReadProviderFailure | None
    outcome: InspectionOutcome = field(default=InspectionOutcome.TRANSPORT_REJECTED, init=False)


InspectionObservation = (InspectionAccepted | InspectionWireRejected | InspectionUnknownRefusal
    | InspectionContractRejected | InspectionTransportRejected)


def _inspect_observation(stage, response, current, old, expected_symbol):
    if not isinstance(response, dict):
        return InspectionContractRejected(stage, InspectionMismatch.DOCUMENT)
    if response.get('operation') != 'symbol.inspect':
        return InspectionContractRejected(stage, InspectionMismatch.OPERATION)
    if response.get('status') == 'rejected':
        try:
            return InspectionWireRejected(stage, InspectionRefusal(response.get('reason')))
        except (ValueError, TypeError):
            return InspectionUnknownRefusal(stage)
    if response.get('status') != 'complete':
        return InspectionContractRejected(stage, InspectionMismatch.STATUS)
    acquisition = 'reacquired' if stage is InspectionStage.REVALIDATE else 'strict'
    if response.get('acquisition') != acquisition:
        return InspectionContractRejected(stage, InspectionMismatch.ACQUISITION)
    if response.get('live') != current:
        return InspectionContractRejected(stage, InspectionMismatch.LIVE_BASIS)
    symbol = response.get('symbol')
    if not isinstance(symbol, dict):
        return InspectionContractRejected(stage, InspectionMismatch.SYMBOL)
    if stage is InspectionStage.REVALIDATE:
        selector = symbol.get('selector')
        if not isinstance(selector, str) or not selector or selector == old:
            return InspectionContractRejected(stage, InspectionMismatch.SELECTOR)
        if symbol.get('name') != 'ReadPageBudget':
            return InspectionContractRejected(stage, InspectionMismatch.SYMBOL)
    elif symbol != expected_symbol:
        return InspectionContractRejected(stage, InspectionMismatch.SYMBOL)
    return InspectionAccepted(stage)


class AuthorityRejected(ValueError):
    def __init__(self, reason):
        self.reason = reason
        super().__init__(reason.value)


class AuthoritySurface(str, Enum):
    CLI = 'cli'
    PROVIDER = 'provider'


class AuthorityCaseName(str, Enum):
    ISSUED = 'current-authority-issued'
    VALID_CURSOR = 'current-continuation-resumes'
    OLD_REFERENCE = 'old-epoch-reference-reacquired'
    REVALIDATED = 'explicit-exact-reacquired-under-fresh-basis'
    NEW_STRICT = 'reacquired-exact-accepted-by-strict-read'
    OLD_CURSOR = 'fresh-anchor-old-continuation-rejected'
    FRESH = 'fresh-authority-reacquired'
    RESTORED = 'restored-source-fresh-authority-reacquired'
    FOREIGN_REFERENCE = 'foreign-workspace-reference-refused'
    FOREIGN_CURSOR = 'foreign-workspace-continuation-refused'


@dataclass(frozen=True)
class InspectExactTarget:
    selector: str
    type: str = 'revalidate_exact'


@dataclass(frozen=True)
class InspectExactRequest:
    target: InspectExactTarget


@dataclass(frozen=True)
class ContinueSourcePage:
    continuation: str
    type: str = field(default='continue', init=False)


@dataclass(frozen=True)
class AuthorityCase:
    name: AuthorityCaseName
    surface: AuthoritySurface
    schemaDigest: str | None
    actualProviderEnvelope: bool
    reason: AuthorityRefusal | None = None
    passed: bool = True
    observedReason: AuthorityRefusal | None = None
    inspection: InspectionObservation | None = None


@dataclass(frozen=True)
class AuthorityReport:
    outcome: AuthorityOutcome
    failure: AuthorityFailure | None = None
    cases: tuple[AuthorityCase, ...] = ()
    beforeEpoch: int | None = None
    editedEpoch: int | None = None
    restoredEpoch: int | None = None
    preimageSha256: str | None = None
    editedSha256: str | None = None
    restoredSha256: str | None = None
    sourceRestored: bool = False
    readinessTransitions: int = 0
    schemaVersion: int = 1
    foreignScope: str = 'unenrolled-owned-root-refuses-before-alternate-host-admission'
    hostedWireEnvelopeSchema: str = 'unqualified'
    sourcePayloadsLogged: bool = False


def _demand(condition, failure):
    if not condition:
        raise AuthorityRejected(failure)


def _source_bytes(path):
    info = path.lstat()
    _demand(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
        and 0 < info.st_size <= 1024 * 1024, AuthorityFailure.SOURCE_GUARD)
    return path.read_bytes()


def _digest(contents):
    return hashlib.sha256(contents).hexdigest()


class _AuthorityReplay:
    def __init__(self, transport, workspace, live):
        self.transport, self.workspace, self.live = transport, workspace, live
        self.cases = []

    def call(self, surface, tool, request):
        return self.transport.invoke_observed(surface.value, tool, asdict(request))

    def record(self, name, surface, digest, reason=None):
        self.cases.append(AuthorityCase(name, surface, digest, surface is AuthoritySurface.PROVIDER, reason))

    def search(self, surface):
        response, digest = self.call(surface, 'query_symbols', source_budget_anchor_query())
        items = response.get('items', [])
        _demand(response.get('status') == 'complete' and len(items) == 1
            and isinstance(items[0].get('ref'), str) and bool(items[0]['ref']), AuthorityFailure.ISSUER)
        current = admitted_live(response.get('live'), self.workspace)
        _demand(current['host'] == self.live['host'], AuthorityFailure.EPOCH)
        return SourceFunctionRequest(SymbolAnchor(items[0]['ref'])), current

    def page(self, name, surface, request, live, expected_name, reacquired_from=None):
        response, digest = self.call(surface, 'source_read', request)
        qualification = response.get('qualification', {})
        progress = qualification.get('progress', {})
        checkpoint = progress.get('checkpoint', {})
        token = checkpoint.get('token')
        current = response.get('live', response.get('snapshot', {}).get('live'))
        _demand(response.get('status') == 'qualified'
            and [entity.get('name') for entity in response.get('entities', [])] == [expected_name]
            and current == live and qualification.get('limitations') == ['entity-limit-reached']
            and progress.get('type') == 'resumable' and progress.get('next_action') == 'resume'
            and checkpoint.get('type') == 'upstream' and isinstance(token, str) and bool(token), AuthorityFailure.ISSUER)
        if reacquired_from is not None:
            acquired = response.get('reference_acquisitions', {}).get('references', [])
            _demand(len(acquired) == 1 and acquired[0].get('previous') == reacquired_from
                and isinstance(acquired[0].get('current'), str) and bool(acquired[0]['current'])
                and acquired[0]['current'] != reacquired_from, AuthorityFailure.REVALIDATION)
        self.record(name, surface, digest)
        return token

    def inspect(self, stage, surface, selector, current, expected_symbol=None):
        name = (AuthorityCaseName.REVALIDATED if stage is InspectionStage.REVALIDATE
                else AuthorityCaseName.NEW_STRICT)
        request = InspectExactRequest(InspectExactTarget(selector, stage.value))
        try:
            response, digest = self.call(surface, 'symbol_inspect', request)
        except ReadTransportRejected as error:
            observation = InspectionTransportRejected(stage, error.reason, error.provider_failure)
            self.cases.append(AuthorityCase(name, surface, None, False, passed=False,
                inspection=observation))
            raise
        observation = _inspect_observation(stage, response, current, selector, expected_symbol)
        passed = isinstance(observation, InspectionAccepted)
        self.cases.append(AuthorityCase(name, surface, digest, surface is AuthoritySurface.PROVIDER,
            passed=passed, inspection=observation))
        _demand(passed, AuthorityFailure.REVALIDATION)
        return response['symbol']

    def revalidate(self, surface, old, current):
        symbol = self.inspect(InspectionStage.REVALIDATE, surface, old, current)
        self.inspect(InspectionStage.STRICT, surface, symbol['selector'], current, symbol)

    def reject(self, name, surface, request, reason):
        response, digest = self.call(surface, 'source_read', request)
        raw = response.get('reason')
        if raw == asdict(UnavailableSymbolReference()):
            observed = AuthorityRefusal.UNAVAILABLE_REFERENCE
        else:
            try:
                observed = AuthorityRefusal(raw)
            except (ValueError, TypeError):
                observed = AuthorityRefusal.UNEXPECTED
        passed = (response.get('status') == 'rejected' and response.get('operation') == 'source.read'
                  and observed == reason)
        self.cases.append(AuthorityCase(name, surface, digest, surface is AuthoritySurface.PROVIDER,
                                        reason, passed, observed))
        _demand(passed, AuthorityFailure.REJECTION)


def _ready(probe, contents):
    observed = probe.request('AWAIT_REOPEN_READY', _digest(contents), timeout=60)
    evidence = observed.get('evidence', {})
    _demand(observed.get('failure') != 'DOCUMENT_IMAGE_CHANGED', AuthorityFailure.DOCUMENT_IMAGE)
    _demand(observed.get('outcome') == 'SETUP_READY'
        and evidence.get('savedSha256') == _digest(contents)
        and evidence.get('documentState') == 'SAVED_COMMITTED', AuthorityFailure.READINESS)
    _demand(evidence.get('documentSha256') == _digest(contents), AuthorityFailure.DOCUMENT_IMAGE)


@dataclass(frozen=True)
class ForeignBoundaryRefusal:
    status: str = 'rejected'
    boundary: str = 'runtime'
    reason: str = 'ide-host-unavailable'


def _foreign_refusal(transport, fixture, request):
    # This owned root has no IDE enrollment. It must refuse before selecting root A's host.
    foreign = fixture.workspace.parent / 'foreign-read-workspace'
    created = not foreign.exists()
    if created:
        foreign.mkdir(mode=0o700)
        (foreign / 'settings.gradle.kts').write_text('rootProject.name = "foreign-read-refusal"\n')
    _demand(foreign.resolve() == foreign and not foreign.is_symlink()
        and stat.S_IMODE(foreign.stat().st_mode) == 0o700, AuthorityFailure.FOREIGN)
    settings = foreign / 'settings.gradle.kts'
    before = _digest(_source_bytes(settings))
    result = subprocess.run([str(product_executable(transport.product, fixture.workspace.parent)), *transport.cli_commands['source_read']],
        cwd=foreign, env=fixture.environment, input=json.dumps(asdict(request)).encode(),
        capture_output=True, timeout=30)
    _demand(result.returncode != 0 and not result.stdout and len(result.stderr) <= 65536,
        AuthorityFailure.FOREIGN)
    raw = b'\n'.join(line for line in result.stderr.splitlines()
        if not line.startswith((b'Picked up JAVA_TOOL_OPTIONS:', b'Picked up _JAVA_OPTIONS:')))
    _demand(json.loads(raw) == asdict(ForeignBoundaryRefusal())
        and sorted(path.name for path in foreign.iterdir()) == ['settings.gradle.kts']
        and _digest(_source_bytes(settings)) == before, AuthorityFailure.FOREIGN)


def run_authority_read_regression(isolation, fixture, transport, initial_live):
    report = AuthorityReport(AuthorityOutcome.REJECTED)
    replay, original, edited, source, probe = None, None, None, None, None
    changed = False
    try:
        workspace = fixture.workspace
        _demand(workspace == isolation.root / 'workspace' and workspace.resolve() == workspace,
            AuthorityFailure.SOURCE_GUARD)
        source = workspace / 'src/main/kotlin/Fixture.kt'
        original = _source_bytes(source)
        edited = original + b'\n// Native acceptance ordinary edit: authority must move.\n'
        live = admitted_live(initial_live, workspace)
        replay = _AuthorityReplay(transport, workspace, live)
        probe = NativeFixtureProbe(isolation.root, workspace)
        report = replace(report, beforeEpoch=live['epoch'], preimageSha256=_digest(original),
            editedSha256=_digest(edited))
        issued = {}
        for surface in AuthoritySurface:
            request, current = replay.search(surface)
            _demand(current == live, AuthorityFailure.EPOCH)
            token = replay.page(AuthorityCaseName.ISSUED, surface, request, live, 'pageItem00')
            resume = replace(request, page=ContinueSourcePage(token))
            replay.page(AuthorityCaseName.VALID_CURSOR, surface, resume, live, 'pageItem01')
            issued[surface] = (request, token)
        request, token = issued[AuthoritySurface.CLI]
        for name, foreign_request in (
            (AuthorityCaseName.FOREIGN_REFERENCE, request),
            (AuthorityCaseName.FOREIGN_CURSOR, replace(request, page=ContinueSourcePage(token))),
        ):
            _foreign_refusal(transport, fixture, foreign_request)
            replay.record(name, AuthoritySurface.CLI, None, AuthorityRefusal.FOREIGN)
        _demand(_source_bytes(source) == original, AuthorityFailure.SOURCE_GUARD)
        source.write_bytes(edited)
        changed = True
        _ready(probe, edited)
        report = replace(report, readinessTransitions=1)
        edited_live = None
        for surface in AuthoritySurface:
            fresh, current = replay.search(surface)
            _demand(current['epoch'] > live['epoch']
                and (edited_live is None or current == edited_live), AuthorityFailure.EPOCH)
            edited_live = current
            report = replace(report, editedEpoch=current['epoch'])
            old, token = issued[surface]
            replay.page(AuthorityCaseName.OLD_REFERENCE, surface, old, current, 'pageItem00', old.anchor.selector)
            replay.reject(AuthorityCaseName.OLD_CURSOR, surface,
                replace(fresh, page=ContinueSourcePage(token)), AuthorityRefusal.STALE_CONTINUATION)
            replay.page(AuthorityCaseName.FRESH, surface, fresh, current, 'pageItem00')
            replay.revalidate(surface, old.anchor.selector, current)
        report = replace(report, outcome=AuthorityOutcome.PASSED)
    except AuthorityRejected as error:
        report = replace(report, failure=error.reason)
    except NativeFixtureProbeError:
        report = replace(report, failure=AuthorityFailure.READINESS)
    except (ReadTransportRejected, AcceptanceRejected):
        report = replace(report, failure=AuthorityFailure.TRANSPORT)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        report = replace(report, failure=AuthorityFailure.IO)
    finally:
        if changed:
            try:
                _demand(_source_bytes(source) == edited, AuthorityFailure.RESTORATION)
                source.write_bytes(original)
                report = replace(report, sourceRestored=_source_bytes(source) == original,
                    restoredSha256=_digest(_source_bytes(source)))
                _ready(probe, original)
                report = replace(report, readinessTransitions=report.readinessTransitions + 1,
                    sourceRestored=_source_bytes(source) == original, restoredSha256=_digest(_source_bytes(source)))
                for surface in AuthoritySurface:
                    request, current = replay.search(surface)
                    if report.editedEpoch is not None:
                        _demand(current['epoch'] > report.editedEpoch, AuthorityFailure.EPOCH)
                    if report.restoredEpoch is not None:
                        _demand(current['epoch'] == report.restoredEpoch, AuthorityFailure.EPOCH)
                    report = replace(report, restoredEpoch=current['epoch'])
                    replay.page(AuthorityCaseName.RESTORED, surface, request, current, 'pageItem00')
            except AuthorityRejected as error:
                cause = (AuthorityFailure.RESTORATION_DOCUMENT_IMAGE
                    if error.reason == AuthorityFailure.DOCUMENT_IMAGE else AuthorityFailure.RESTORATION)
                report = replace(report, failure=cause)
            except (NativeFixtureProbeError, ReadTransportRejected, AcceptanceRejected,
                    OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
                report = replace(report, failure=AuthorityFailure.RESTORATION)
        if replay is not None:
            report = replace(report, cases=tuple(replay.cases))
        if report.failure is not None or not report.sourceRestored:
            report = replace(report, outcome=AuthorityOutcome.REJECTED)
    return report
