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
        for surface in AuthoritySurface:
            request, observed = replay.search(surface)
            _require(observed == current, OverflowFailure.EPOCH)
            token = replay.page(AuthorityCaseName.ISSUED, surface, request, current, 'pageItem00')
            issued[surface] = request, token
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
            old, token = issued[surface]
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
