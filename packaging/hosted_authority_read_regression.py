"""Ordinary owned file edits qualify live read authority; payloads stay in memory."""
from dataclasses import asdict, dataclass, field, replace
from enum import Enum
import hashlib
import json
import os
import stat
import subprocess

from hosted_change_acceptance import admitted_live, AcceptanceRejected
from hosted_read_transport import ReadTransportRejected
from hosted_source_read_regression import SourceBudgetAnchorSearch, SourceFunctionRequest, SymbolAnchor
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
    IO = 'AUTHORITY_IO_REJECTED'
    RESTORATION = 'AUTHORITY_RESTORATION_REJECTED'


class AuthorityRefusal(str, Enum):
    STALE_REFERENCE = 'stale-generation'
    STALE_CONTINUATION = 'contract-violation'
    FOREIGN = 'ide-host-unavailable'


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
    OLD_REFERENCE = 'old-epoch-reference-rejected'
    OLD_CURSOR = 'fresh-anchor-old-continuation-rejected'
    FRESH = 'fresh-authority-reacquired'
    RESTORED = 'restored-source-fresh-authority-reacquired'
    FOREIGN_REFERENCE = 'foreign-workspace-reference-refused'
    FOREIGN_CURSOR = 'foreign-workspace-continuation-refused'


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
        response, digest = self.call(surface, 'search_classes', SourceBudgetAnchorSearch())
        items = response.get('items', [])
        _demand(response.get('status') == 'complete' and len(items) == 1
            and isinstance(items[0].get('symbol_ref'), str) and bool(items[0]['symbol_ref']), AuthorityFailure.ISSUER)
        current = admitted_live(response.get('live'), self.workspace)
        _demand(current['host'] == self.live['host'], AuthorityFailure.EPOCH)
        return SourceFunctionRequest(SymbolAnchor(items[0]['symbol_ref'])), current

    def page(self, name, surface, request, live, expected_name):
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
        self.record(name, surface, digest)
        return token

    def reject(self, name, surface, request, reason):
        response, digest = self.call(surface, 'source_read', request)
        _demand(response.get('status') == 'rejected' and response.get('operation') == 'source.read'
            and response.get('reason') == reason, AuthorityFailure.REJECTION)
        self.record(name, surface, digest, reason)


def _ready(probe, contents):
    observed = probe.request('AWAIT_REOPEN_READY', _digest(contents), timeout=60)
    evidence = observed.get('evidence', {})
    _demand(observed.get('outcome') == 'SETUP_READY'
        and evidence.get('savedSha256') == _digest(contents)
        and evidence.get('documentSha256') == _digest(contents)
        and evidence.get('documentState') == 'SAVED_COMMITTED', AuthorityFailure.READINESS)


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
    result = subprocess.run([str(transport.product / 'bin/kast'), *transport.cli_commands['source_read']],
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
            replay.reject(AuthorityCaseName.OLD_REFERENCE, surface, old, AuthorityRefusal.STALE_REFERENCE)
            replay.reject(AuthorityCaseName.OLD_CURSOR, surface,
                replace(fresh, page=ContinueSourcePage(token)), AuthorityRefusal.STALE_CONTINUATION)
            replay.page(AuthorityCaseName.FRESH, surface, fresh, current, 'pageItem00')
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
            except (AuthorityRejected, NativeFixtureProbeError, ReadTransportRejected, AcceptanceRejected,
                    OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
                report = replace(report, failure=AuthorityFailure.RESTORATION)
        if replay is not None:
            report = replace(report, cases=tuple(replay.cases))
        if report.failure is not None or not report.sourceRestored:
            report = replace(report, outcome=AuthorityOutcome.REJECTED)
    return report
