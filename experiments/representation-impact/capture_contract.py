"""Finite capture records; public responses and diagnostic documents stay opaque contracts."""
from dataclasses import dataclass
from enum import Enum


class Failure(str, Enum):
    INPUT_UNAVAILABLE = 'INPUT_UNAVAILABLE'
    OUTPUT_NOT_FRESH = 'OUTPUT_NOT_FRESH'
    OUTSIDE_OWNED_ROOT = 'OUTSIDE_OWNED_ROOT'
    REQUEST_SCHEMA_REJECTED = 'REQUEST_SCHEMA_REJECTED'
    SCHEMA_UNAVAILABLE = 'SCHEMA_UNAVAILABLE'
    RUNNING_IDEA_MISMATCH = 'RUNNING_IDEA_MISMATCH'
    IDEA_OBSERVATION_TIMEOUT = 'IDEA_OBSERVATION_TIMEOUT'
    IDEA_OBSERVATION_UNAVAILABLE = 'IDEA_OBSERVATION_UNAVAILABLE'
    PROCESS_LAUNCH_REJECTED = 'PROCESS_LAUNCH_REJECTED'
    PROCESS_TIMEOUT = 'PROCESS_TIMEOUT'
    PROCESS_EXIT_REJECTED = 'PROCESS_EXIT_REJECTED'
    INVALID_RPC_RESPONSE = 'INVALID_RPC_RESPONSE'
    IO_UNAVAILABLE = 'IO_UNAVAILABLE'


class RpcOutcome(str, Enum):
    COMPLETE = 'complete'
    QUALIFIED = 'qualified'
    PRODUCT_REJECTED = 'rejected_document'
    RPC_REJECTED = 'rejected'


class Uncertainty(str, Enum):
    LOG_ROTATED = 'LOG_ROTATED'
    LOG_APPEND_LIMIT = 'LOG_APPEND_LIMIT'
    MALFORMED_DIAGNOSTIC = 'MALFORMED_DIAGNOSTIC'
    FOREIGN_PID = 'FOREIGN_PID'
    NO_PID_RECEIPT = 'NO_PID_RECEIPT'
    RESPONSE_BASIS_UNAVAILABLE = 'RESPONSE_BASIS_UNAVAILABLE'
    SEED_BASIS_UNAVAILABLE = 'SEED_BASIS_UNAVAILABLE'
    UNSUPPORTED_SEED_BASIS = 'UNSUPPORTED_SEED_BASIS'
    CONFLICTING_SEED_BASES = 'CONFLICTING_SEED_BASES'
    AMBIGUOUS_RECEIPTS = 'AMBIGUOUS_RECEIPTS'
    BASIS_MISMATCH = 'BASIS_MISMATCH'
    COUNTERS_UNAVAILABLE = 'COUNTERS_UNAVAILABLE'
    COUNTER_SATURATED = 'COUNTER_SATURATED'


class Correlation(str, Enum):
    MATCHED = 'MATCHED'
    UNAVAILABLE = 'UNAVAILABLE'
    AMBIGUOUS = 'AMBIGUOUS'
    MISMATCHED = 'MISMATCHED'


@dataclass(frozen=True)
class Counter:
    counter: str
    contributor: str
    count: int


@dataclass(frozen=True)
class NativeSeedBasis:
    root: str
    host: str
    epoch: int
    contentView: str
    referenceVersion: int
    type: str = 'LIVE'


@dataclass(frozen=True)
class ExpectedSeedBasis:
    basis: NativeSeedBasis
    type: str = 'EMITTED_INVESTIGATION_SEED_BASIS'


@dataclass(frozen=True)
class ExpectedBasisUnavailable:
    reason: Uncertainty
    type: str = 'EXPECTED_BASIS_UNAVAILABLE'


@dataclass(frozen=True)
class Observations:
    type: str
    appendedBytes: int
    parsedRecordCount: int
    selectedRecordCount: int
    foreignPidRecordCount: int
    malformedRecordCount: int
    correlation: Correlation
    expectedBasis: ExpectedSeedBasis | ExpectedBasisUnavailable
    counters: tuple[Counter, ...]
    uncertainty: tuple[Uncertainty, ...]
    # Opaque existing schemaVersion=6 kast_semantic_read documents, without IDE log prefixes.
    receipts: tuple[dict, ...]


@dataclass(frozen=True)
class Environment:
    HOME: str
    XDG_CONFIG_HOME: str
    XDG_DATA_HOME: str
    XDG_STATE_HOME: str
    XDG_CACHE_HOME: str
    XDG_RUNTIME_DIR: str
    JAVA_HOME: str
    JAVA_OPTS: str
    KAST_INSTALL_IDEA_HOME: str
    PATH: str
    TMPDIR: str
    LANG: str = 'en_US.UTF-8'
    LC_ALL: str = 'en_US.UTF-8'
    TZ: str = 'UTC'


@dataclass(frozen=True)
class Invocation:
    argv: tuple[str, ...]
    cwd: str
    elapsedNanos: int
    exitCode: int | None
    stdoutFile: str
    stderrFile: str
    stdoutBytes: int
    stderrBytes: int
    executableSha256: str
    requestSha256: str
    generatedSchemaSha256: str
    environment: Environment


@dataclass(frozen=True)
class Captured:
    invocation: Invocation
    ideaPid: int
    rpcOutcome: RpcOutcome
    observations: Observations
    evidenceLevel: str = 'INSTALLED_PUBLIC_CAPTURE_REQUIRES_REVIEW'
    type: str = 'CAPTURED'
    schemaVersion: int = 1


@dataclass(frozen=True)
class Rejected:
    failure: Failure
    invocation: Invocation | None = None
    type: str = 'HARNESS_REJECTED'
    schemaVersion: int = 1


@dataclass(frozen=True)
class RequestAdmitted:
    requestBytes: int
    schemaSha256: str
    type: str = 'REQUEST_SCHEMA_ADMITTED'
