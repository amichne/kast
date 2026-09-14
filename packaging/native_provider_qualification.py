"""Closed, payload-free qualification evidence emitted by the native acceptance harness."""
from dataclasses import asdict, dataclass, field
from enum import Enum


class QualificationStage(str, Enum):
    PROVIDER = 'PROVIDER_QUALIFICATION'


class QualificationOutcome(str, Enum):
    ADMITTED = 'admitted'
    REJECTED = 'rejected'


class QualificationCause(str, Enum):
    VERSION_UNAVAILABLE = 'VERSION_UNAVAILABLE'
    VERSION_INVALID = 'VERSION_INVALID'
    SCHEMA_UNAVAILABLE = 'SCHEMA_UNAVAILABLE'
    SCHEMA_SIZE_LIMIT = 'SCHEMA_SIZE_LIMIT'
    SCHEMA_INVALID = 'SCHEMA_INVALID'
    SCHEMA_INCOMPATIBLE = 'SCHEMA_INCOMPATIBLE'


@dataclass(frozen=True)
class QualificationAdmitted:
    stage: QualificationStage = field(default=QualificationStage.PROVIDER, init=False)
    outcome: QualificationOutcome = field(default=QualificationOutcome.ADMITTED, init=False)


@dataclass(frozen=True)
class QualificationRejected:
    cause: QualificationCause
    stage: QualificationStage = field(default=QualificationStage.PROVIDER, init=False)
    outcome: QualificationOutcome = field(default=QualificationOutcome.REJECTED, init=False)


def admit_qualification(raw):
    """Only exact known fields and finite values can enter a report."""
    if not isinstance(raw, dict) or raw.get('stage') != QualificationStage.PROVIDER:
        raise ValueError('QUALIFICATION_EVIDENCE_REJECTED')
    if raw.get('outcome') == QualificationOutcome.ADMITTED and set(raw) == {'stage', 'outcome'}:
        return QualificationAdmitted()
    if raw.get('outcome') == QualificationOutcome.REJECTED and set(raw) == {'stage', 'outcome', 'cause'}:
        return QualificationRejected(QualificationCause(raw['cause']))
    raise ValueError('QUALIFICATION_EVIDENCE_REJECTED')


def qualification_document(observation):
    return asdict(observation)
