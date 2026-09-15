"""Single-request native timing witnesses on the existing owned log window."""
from dataclasses import dataclass
from enum import Enum
import json

from hosted_transport_observation import (
    NativeTransportWindow, TransportStage, TransportWitnessFailure, TransportWitnessRejected,
)


class ReadStage(str, Enum):
    REQUEST_ADMISSION = 'REQUEST_ADMISSION'
    PROJECT_ADMISSION = 'PROJECT_ADMISSION'
    EPOCH_OBSERVATION = 'EPOCH_OBSERVATION'
    MODEL_CAPTURE = 'MODEL_CAPTURE'
    SEMANTIC_READ = 'SEMANTIC_READ'
    CONTENT_REVALIDATION = 'CONTENT_REVALIDATION'
    RESULT_DETACHED = 'RESULT_DETACHED'


@dataclass(frozen=True)
class ReadStageDuration:
    stage: ReadStage
    startedNanos: int
    durationNanos: int


@dataclass(frozen=True)
class NativeTimeEvidence:
    durationNanos: int
    remainingHostMillis: int
    completionReserveMillis: int
    semanticMillis: int
    stages: tuple[ReadStageDuration, ...]


def admit_native_time(raw, live):
    def reject():
        raise TransportWitnessRejected(TransportWitnessFailure.RECORD)
    def amount(value, positive=False):
        return type(value) is int and (1 if positive else 0) <= value <= 2**63 - 1
    if (not isinstance(raw, dict) or raw.get('schemaVersion') != 4
            or raw.get('correlation') != {'type': 'bound', 'host': live.get('host'), 'epoch': live.get('epoch')}
            or raw.get('outcome') not in ({'type': 'evaluated', 'outcome': 'COMPLETE'},
                                        {'type': 'evaluated', 'outcome': 'QUALIFIED'})
            or not amount(raw.get('durationNanos'))):
        reject()
    budget = raw.get('semanticBudget', {})
    keys = ('remainingHostMillis', 'completionReserveMillis', 'semanticMillis', 'diagnosticScopeMillis')
    if (not isinstance(budget, dict) or set(budget) != set(keys) | {'type'}
            or budget['type'] != 'admitted' or any(not amount(budget[k], True) for k in keys)
            or budget['semanticMillis'] + budget['completionReserveMillis'] > budget['remainingHostMillis']):
        reject()
    stages = raw.get('stages')
    if not isinstance(stages, list) or len(stages) != len(ReadStage):
        reject()
    admitted, previous_end = [], 0
    for expected, stage in zip(ReadStage, stages):
        if (not isinstance(stage, dict) or set(stage) != {'stage', 'startedNanos', 'durationNanos'}
                or stage['stage'] != expected or not amount(stage['startedNanos'])
                or not amount(stage['durationNanos']) or stage['startedNanos'] < previous_end):
            reject()
        previous_end = stage['startedNanos'] + stage['durationNanos']
        if previous_end > raw['durationNanos']:
            reject()
        admitted.append(ReadStageDuration(expected, stage['startedNanos'], stage['durationNanos']))
    return NativeTimeEvidence(raw['durationNanos'], budget['remainingHostMillis'],
        budget['completionReserveMillis'], budget['semanticMillis'], tuple(admitted))


class NativeRepairTimeWindow(NativeTransportWindow):
    """No other Kast calls run inside this sequential request window; reject extra reads."""
    def __init__(self, path, live):
        super().__init__(path)
        self.live = live
        self.semantic = []

    def admit_line(self, line):
        super().admit_line(line)
        _, found, body = line.partition(b'kast_semantic_read ')
        if found:
            if self.semantic:
                raise TransportWitnessRejected(TransportWitnessFailure.BOUND)
            try:
                self.semantic.append(admit_native_time(json.loads(body), self.live))
            except (ValueError, UnicodeError):
                raise TransportWitnessRejected(TransportWitnessFailure.RECORD) from None

    def completed_timing(self):
        self.await_condition(lambda: len(self.semantic) == 1
            and bool(self.completed(TransportStage.CONNECTION_RELEASE)))
        summary = self.summary()
        if not summary.passed or summary.connections != 1 or summary.released != 1 or summary.completeReplies != 1:
            raise TransportWitnessRejected(TransportWitnessFailure.RECORD)
        return self.semantic[0], summary
