"""Source failure observations contain finite labels; input and source bytes stay in memory."""
from dataclasses import asdict, dataclass, replace
from enum import Enum
from hosted_source_read_regression import SourceFunctionRequest, SymbolAnchor


class SourceFailureOrigin(str, Enum):
    REQUEST = 'request-rejected'
    REFERENCE = 'reference-rejected'
    INTERNAL = 'internal-contract-failure'


class SourceFailureCause(str, Enum):
    ANCHOR_TYPE_REQUIRED = 'anchor-type-required'
    WRONG_FAMILY = 'wrong-family'
    UNAVAILABLE = 'unavailable'
    STALE_AUTHORITY = 'stale-authority'


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
        if value.get('field', {}).get('path') != 'anchor.type' or value.get('reason') != 'required':
            raise ValueError('SOURCE_FAILURE_EVIDENCE_REJECTED')
        return SourceFailureObservation(origin, SourceFailureCause.ANCHOR_TYPE_REQUIRED)
    if origin is SourceFailureOrigin.REFERENCE:
        reason = value.get('reason')
        if value.get('role') != 'symbol' or reason not in ('wrong-family', 'unavailable', 'stale-authority'):
            raise ValueError('SOURCE_FAILURE_EVIDENCE_REJECTED')
        return SourceFailureObservation(origin, SourceFailureCause(reason))
    raise ValueError('SOURCE_FAILURE_EVIDENCE_REJECTED')


@dataclass(frozen=True)
class FormattedFailureRequest(SourceFunctionRequest):
    format: str = 'compact'


def run_source_failure_regression(replay):
    from hosted_read_transport import ReadTransportRejected
    request = FormattedFailureRequest(SymbolAnchor(replay.seeds['logger']['ref']))
    for mode in ('expanded', 'compact'):
        selected = replace(request, format=mode)
        valid, digest = replay.transport.invoke_observed(replay.surface, 'source_read', asdict(selected))
        checks = {'validSchema': bool(digest), 'validRead': valid.get('status') in ('complete', 'partial')}
        for label, token, expected in (
            ('wrongFamily', 'candidate:v4:' + 'a' * 64, 'wrong-family'),
            ('unknownReference', 'exact:v4:' + 'a' * 64, 'unavailable'),
        ):
            arguments = asdict(replace(selected, anchor=SymbolAnchor(token)))
            try:
                document, failure_digest = replay.transport.invoke_observed(replay.surface, 'source_read', arguments)
                observation = admit_source_failure(document.get('reason'))
                checks[label] = bool(failure_digest) and observation.cause == expected and document.get('next_action') == 'reacquire_authority'
            except ReadTransportRejected as error:
                checks[label] = error.source_cause is not None and error.source_cause.cause == expected
        # Deliberately malformed physical ingress, retaining all otherwise valid fields.
        malformed = asdict(selected)
        del malformed['anchor']['type']
        try:
            document = replay.transport.invoke(replay.surface, 'source_read', malformed)
            checks['physicalField'] = admit_source_failure(document.get('reason')).cause == 'anchor-type-required'
        except ReadTransportRejected as error:
            checks['physicalField'] = error.source_cause is not None and error.source_cause.cause == 'anchor-type-required'
        replay.record('source-finite-failures-' + mode, 'source_read', checks)
