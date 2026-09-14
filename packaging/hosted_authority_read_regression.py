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
    reason: str | None = None
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


def run_authority_read_regression(isolation, fixture, transport, initial_live):
    return AuthorityReport(AuthorityOutcome.REJECTED, AuthorityFailure.ISSUER)
