from __future__ import annotations
"""Source failure observations contain finite labels; input and source bytes stay in memory."""
from dataclasses import asdict, dataclass, replace
from enum import Enum
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from hosted_read_transport import ReadProviderFailure, ReadTransportFailure
from hosted_source_read_regression import SourceFunctionRequest, SymbolAnchor


class SourceFailureOrigin(str, Enum):
    REQUEST = 'request-rejected'
    REFERENCE = 'reference-rejected'
    INTERNAL = 'internal-contract-failure'


class SourceFailureCause(str, Enum):
    ANCHOR_TYPE_REQUIRED = 'anchor-type-required'
    WRONG_FAMILY = 'wrong-family'
    UNAVAILABLE = 'unavailable'
    REVALIDATION_UNRETAINED = 'revalidation-unretained'
    STALE_AUTHORITY = 'stale-authority'
    CONTEXT_LEASE = 'context-lease'
    SNAPSHOT_CONTEXT = 'snapshot-context'
    SNAPSHOT_SCOPE = 'snapshot-scope'
    ANCHOR_SNAPSHOT = 'anchor-snapshot'
    DECLARATION_VISIBILITY = 'declaration-visibility'
    REQUEST_REFINEMENT = 'request-refinement'
    RESULT_PROJECTION = 'result-projection'
    QUALIFICATION_PROJECTION = 'qualification-projection'
    PROVIDER_CONTRACT = 'provider-contract'


@dataclass(frozen=True)
class SourceFailureObservation:
    origin: SourceFailureOrigin
    cause: SourceFailureCause


def admit_source_failure(value):
    """Only the bounded native regression vocabulary can enter its receipt."""
    if not isinstance(value, dict):
        raise ValueError('SOURCE_FAILURE_EVIDENCE_REJECTED')
    origin = SourceFailureOrigin(value.get('type'))
    if origin is SourceFailureOrigin.REQUEST:
        field = value.get('field')
        if not isinstance(field, dict) or field.get('path') != 'anchor.type' or value.get('reason') != 'required':
            raise ValueError('SOURCE_FAILURE_EVIDENCE_REJECTED')
        return SourceFailureObservation(origin, SourceFailureCause.ANCHOR_TYPE_REQUIRED)
    if origin is SourceFailureOrigin.REFERENCE:
        reason = value.get('reason')
        if value.get('role') != 'symbol' or reason not in ('wrong-family', 'unavailable', 'stale-authority', 'revalidation-unretained'):
            raise ValueError('SOURCE_FAILURE_EVIDENCE_REJECTED')
        return SourceFailureObservation(origin, SourceFailureCause(reason))
    obligation = SourceFailureCause(value.get('obligation'))
    if obligation in (SourceFailureCause.ANCHOR_TYPE_REQUIRED, SourceFailureCause.WRONG_FAMILY, SourceFailureCause.UNAVAILABLE, SourceFailureCause.STALE_AUTHORITY, SourceFailureCause.REVALIDATION_UNRETAINED):
        raise ValueError('SOURCE_FAILURE_EVIDENCE_REJECTED')
    return SourceFailureObservation(origin, obligation)


@dataclass(frozen=True)
class FormattedFailureRequest(SourceFunctionRequest):
    format: str = 'compact'


def run_source_failure_regression(replay):
    request = FormattedFailureRequest(SymbolAnchor(replay.seeds['logger']['ref']))
    for mode in ('expanded', 'compact'):
        selected = replace(request, format=mode)
        valid, digest = replay.transport.invoke_observed(replay.surface, 'source_read', asdict(selected))
        checks = {'validSchema': bool(digest), 'validRead': valid.get('status') in ('complete', 'qualified')}
        observations = []
        for case, token, expected in (
            (SourceFailureCase.WRONG_FAMILY, 'candidate:v4:' + 'a' * 64, SourceFailureCause.WRONG_FAMILY),
            (SourceFailureCase.UNKNOWN_REFERENCE, 'exact:v4:' + 'a' * 64, SourceFailureCause.REVALIDATION_UNRETAINED),
        ):
            checks[case.value], observation = _observe_source_failure(replay, case,
                asdict(replace(selected, anchor=SymbolAnchor(token))), expected)
            observations.append(observation)
        # Deliberately malformed physical ingress, retaining all otherwise valid fields.
        malformed = asdict(selected)
        del malformed['anchor']['type']
        checks['physicalField'], observation = _observe_source_failure(replay, SourceFailureCase.PHYSICAL_FIELD,
            malformed, SourceFailureCause.ANCHOR_TYPE_REQUIRED)
        observations.append(observation)
        replay.record('source-finite-failures-' + mode, 'source_read', checks, response=valid)
        replay.rows[-1]['observation']['sourceFailures'] = [asdict(item) for item in observations]


class SourceFailureCase(str, Enum):
    WRONG_FAMILY = 'wrongFamily'
    UNKNOWN_REFERENCE = 'unknownReference'
    PHYSICAL_FIELD = 'physicalField'


class SourceFailureBoundary(str, Enum):
    SEMANTIC = 'semantic-response'
    CLI = 'cli-usage-stderr'
    PROVIDER = 'provider-ingress'
    TRANSPORT = 'transport-rejected'
    EVIDENCE = 'evidence-rejected'


class SourceFailureRecovery(str, Enum):
    CORRECT = 'correct_request'
    REACQUIRE = 'reacquire_authority'
    REPORT = 'report_failure'
    UNRECOGNIZED = 'unrecognized'


class SourceSchemaEvidence(str, Enum):
    ADMITTED = 'operation-schema-admitted'
    NOT_CHECKED = 'operation-schema-not-checked'


@dataclass(frozen=True)
class ObservedSourceFailure:
    case: SourceFailureCase
    boundary: SourceFailureBoundary
    schema: SourceSchemaEvidence
    cause: SourceFailureObservation | None = None
    provider_failure: ReadProviderFailure | None = None
    transport_failure: ReadTransportFailure | None = None
    # Already admitted by the transport schema-evidence boundary; never raw provider JSON.
    schema_violation_evidence: dict | None = None
    recovery: SourceFailureRecovery | None = None
    cli_boundary_failure: SourceCliBoundaryFailure | None = None
    jvm_notices: tuple[SourceJvmNotice, ...] = ()


class SourceJvmNotice(str, Enum):
    TOOL_OPTIONS = 'java-tool-options'
    JAVA_OPTIONS = 'java-options'


class SourceCliBoundaryFailure(str, Enum):
    EXIT = 'exit-not-usage'
    STDOUT = 'stdout-not-empty'
    STDERR = 'stderr-bound'
    JSON = 'json-document'
    ENVELOPE = 'source-envelope'
    CAUSE = 'source-cause'
    RECOVERY = 'source-recovery'


class SourceCliBoundaryRejected(ValueError):
    def __init__(self, failure: SourceCliBoundaryFailure):
        self.failure = failure
        super().__init__(failure.value)


@dataclass(frozen=True)
class SourceCliBoundaryAdmission:
    # Canonical source output stays opaque until the transport's operation-schema admission.
    document: dict
    cause: SourceFailureObservation
    notices: tuple[SourceJvmNotice, ...]


def admit_source_cli_boundary(exit_code, stdout, stderr, environment=None):
    """Accept only usage stderr and exact JVM notices proven by this process's private environment."""
    import json
    if exit_code != 2:
        raise SourceCliBoundaryRejected(SourceCliBoundaryFailure.EXIT)
    if stdout:
        raise SourceCliBoundaryRejected(SourceCliBoundaryFailure.STDOUT)
    if not stderr or len(stderr) > 4096:
        raise SourceCliBoundaryRejected(SourceCliBoundaryFailure.STDERR)
    supplied = environment if environment is not None else {}
    notices = []
    for key, notice in (('JAVA_TOOL_OPTIONS', SourceJvmNotice.TOOL_OPTIONS), ('_JAVA_OPTIONS', SourceJvmNotice.JAVA_OPTIONS)):
        value = supplied.get(key)
        if isinstance(value, str) and value:
            prefix = ('Picked up ' + key + ': ' + value + '\n').encode()
            if stderr.startswith(prefix):
                stderr = stderr[len(prefix):]
                notices.append(notice)
    try:
        document = json.loads(stderr)
    except (ValueError, TypeError):
        raise SourceCliBoundaryRejected(SourceCliBoundaryFailure.JSON) from None
    if (not isinstance(document, dict) or set(document) != {'operation', 'status', 'reason', 'next_action'}
            or document['operation'] != 'source.read' or document['status'] != 'rejected'):
        raise SourceCliBoundaryRejected(SourceCliBoundaryFailure.ENVELOPE)
    try:
        cause = admit_source_failure(document['reason'])
    except (ValueError, TypeError):
        raise SourceCliBoundaryRejected(SourceCliBoundaryFailure.CAUSE) from None
    recovery = {SourceFailureOrigin.REQUEST: 'correct_request', SourceFailureOrigin.REFERENCE: 'reacquire_authority',
                SourceFailureOrigin.INTERNAL: 'report_failure'}[cause.origin]
    if document['next_action'] != recovery:
        raise SourceCliBoundaryRejected(SourceCliBoundaryFailure.RECOVERY)
    return SourceCliBoundaryAdmission(document, cause, tuple(notices))


def _observe_source_failure(replay, case, arguments, expected):
    from hosted_read_transport import ReadTransportRejected, ReadTransportFailure, ReadProviderFailure
    try:
        document, digest = replay.transport.invoke_observed(replay.surface, 'source_read', arguments)
        cause = admit_source_failure(document.get('reason'))
        try:
            recovery = SourceFailureRecovery(document.get('next_action'))
        except (TypeError, ValueError):
            recovery = SourceFailureRecovery.UNRECOGNIZED
        observation = ObservedSourceFailure(case, SourceFailureBoundary.SEMANTIC,
            SourceSchemaEvidence.ADMITTED if digest else SourceSchemaEvidence.NOT_CHECKED, cause, recovery=recovery)
        expected_recovery = SourceFailureRecovery.CORRECT if cause.origin is SourceFailureOrigin.REQUEST else SourceFailureRecovery.REACQUIRE
        return cause.cause == expected and bool(digest) and recovery is expected_recovery, observation
    except ReadTransportRejected as error:
        boundary = (SourceFailureBoundary.CLI if error.reason is ReadTransportFailure.SOURCE_CLI_BOUNDARY
            else SourceFailureBoundary.PROVIDER if error.provider_failure in (ReadProviderFailure.SOURCE_INPUT, ReadProviderFailure.SOURCE_INTERNAL) else SourceFailureBoundary.TRANSPORT)
        observation = ObservedSourceFailure(case, boundary,
            SourceSchemaEvidence.ADMITTED if error.source_schema_admitted else SourceSchemaEvidence.NOT_CHECKED,
            error.source_cause, error.provider_failure, error.reason, error.output_violation_evidence,
            cli_boundary_failure=error.source_cli_boundary_failure, jvm_notices=error.source_jvm_notices)
        return (error.source_cause is not None and error.source_cause.cause == expected
            and (boundary is SourceFailureBoundary.PROVIDER or (boundary is SourceFailureBoundary.CLI and error.source_schema_admitted))), observation
    except (ValueError, TypeError):
        return False, ObservedSourceFailure(case, SourceFailureBoundary.EVIDENCE, SourceSchemaEvidence.NOT_CHECKED)
