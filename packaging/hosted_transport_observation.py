"""Bounded admission/release witnesses from the native endpoint's structured records."""
from collections import Counter
from dataclasses import dataclass
from enum import Enum
import json
import os
import re
import select
import stat
import time


class TransportWitnessFailure(str, Enum):
    UNAVAILABLE = 'observation_unavailable'
    OWNERSHIP = 'log_ownership_rejected'
    CHANGED = 'log_changed'
    BOUND = 'observation_bound_exceeded'
    RECORD = 'record_rejected'
    DEADLINE = 'witness_deadline_exceeded'
    SATURATION_LOST = 'saturation_peer_released_before_witness'
    IO = 'observation_io_rejected'


class TransportWitnessRejected(ValueError):
    def __init__(self, failure):
        self.failure = failure
        super().__init__(failure.value)


class TransportStage(str, Enum):
    ACCEPT = 'ACCEPT'
    REQUEST_READ = 'REQUEST_READ'
    SEMANTIC_ADMISSION = 'SEMANTIC_ADMISSION'
    EXECUTION = 'EXECUTION'
    ENCODING = 'ENCODING'
    REPLY_WRITE = 'REPLY_WRITE'
    CONNECTION_RELEASE = 'CONNECTION_RELEASE'


class TransportOutcome(str, Enum):
    STARTED = 'STARTED'
    COMPLETED = 'COMPLETED'
    QUALIFIED = 'QUALIFIED'
    REJECTED = 'REJECTED'
    CANCELLED = 'CANCELLED'


class EndpointFailure(str, Enum):
    ADMISSION_CAPACITY_EXCEEDED = 'ADMISSION_CAPACITY_EXCEEDED'
    ADMISSION_DEADLINE_EXCEEDED = 'ADMISSION_DEADLINE_EXCEEDED'
    INVALID_REQUEST = 'INVALID_REQUEST'
    REQUEST_TOO_LARGE = 'REQUEST_TOO_LARGE'
    REQUEST_INCOMPLETE = 'REQUEST_INCOMPLETE'
    IO_UNAVAILABLE = 'IO_UNAVAILABLE'
    DEADLINE_EXCEEDED = 'DEADLINE_EXCEEDED'
    WRONG_ROOT = 'WRONG_ROOT'
    OWNERSHIP_CONFLICT = 'OWNERSHIP_CONFLICT'
    DIRECTORY_REJECTED = 'DIRECTORY_REJECTED'
    SOCKET_UNAVAILABLE = 'SOCKET_UNAVAILABLE'
    PLATFORM_UNAVAILABLE = 'PLATFORM_UNAVAILABLE'
    RESPONSE_REJECTED = 'RESPONSE_REJECTED'
    RESULT_TOO_LARGE = 'RESULT_TOO_LARGE'
    APPROVAL_UNAVAILABLE = 'APPROVAL_UNAVAILABLE'
    APPROVAL_REJECTED = 'APPROVAL_REJECTED'


@dataclass(frozen=True)
class TransportRecord:
    connection: str
    stage: TransportStage
    outcome: TransportOutcome
    elapsed_nanos: int
    bytes: int
    failure: EndpointFailure | None


@dataclass(frozen=True)
class TransportStageSummary:
    stage: TransportStage
    outcome: TransportOutcome
    count: int
    maximumElapsedNanos: int
    totalElapsedNanos: int
    maximumBytes: int
    totalBytes: int


@dataclass(frozen=True)
class TransportSummary:
    passed: bool
    connections: int
    released: int
    completeReplies: int
    maximumCompleteRepliesPerConnection: int
    correlatedReplies: bool
    stages: list[TransportStageSummary]
    evidence: str = 'bounded-structured-native-transport-records'


def admit_transport_record(raw):
    if (not isinstance(raw, dict)
            or set(raw) != {'connectionId', 'stage', 'outcome', 'elapsedNanos', 'bytes', 'failure'}
            or not isinstance(raw['connectionId'], str)
            or not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', raw['connectionId'])
            or any(type(raw[key]) is not int or not 0 <= raw[key] <= 2**63 - 1
                   for key in ('elapsedNanos', 'bytes'))):
        raise TransportWitnessRejected(TransportWitnessFailure.RECORD)
    try:
        return TransportRecord(raw['connectionId'], TransportStage(raw['stage']),
            TransportOutcome(raw['outcome']), raw['elapsedNanos'], raw['bytes'],
            None if raw['failure'] is None else EndpointFailure(raw['failure']))
    except (TypeError, ValueError):
        raise TransportWitnessRejected(TransportWitnessFailure.RECORD) from None


class NativeTransportWindow:
    MAX_BYTES = 16 * 1024 * 1024
    MAX_RECORDS = 8192

    def __init__(self, path):
        self.path = path
        self.records = []
        self.stream = None
        self.notifications = None
        self.consumed = 0
        self.pending = b''

    def __enter__(self):
        if not hasattr(select, 'kqueue') or self.path.is_symlink():
            raise TransportWitnessRejected(TransportWitnessFailure.UNAVAILABLE)
        self.stream = self.path.open('rb')
        info = os.fstat(self.stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            self.stream.close()
            raise TransportWitnessRejected(TransportWitnessFailure.OWNERSHIP)
        self.identity = info.st_dev, info.st_ino
        self.stream.seek(0, os.SEEK_END)
        self.notifications = select.kqueue()
        self.notifications.control([select.kevent(self.stream.fileno(), filter=select.KQ_FILTER_VNODE,
            flags=select.KQ_EV_ADD | select.KQ_EV_CLEAR,
            fflags=select.KQ_NOTE_WRITE | select.KQ_NOTE_EXTEND | select.KQ_NOTE_RENAME | select.KQ_NOTE_DELETE)], 0)
        return self

    def __exit__(self, *_):
        if self.notifications is not None:
            self.notifications.close()
        if self.stream is not None:
            self.stream.close()

    def read(self):
        info = self.path.stat()
        if (info.st_dev, info.st_ino) != self.identity or info.st_size < self.stream.tell():
            raise TransportWitnessRejected(TransportWitnessFailure.CHANGED)
        data = self.stream.read(self.MAX_BYTES - self.consumed + 1)
        self.consumed += len(data)
        if self.consumed > self.MAX_BYTES:
            raise TransportWitnessRejected(TransportWitnessFailure.BOUND)
        lines = (self.pending + data).split(b'\n')
        self.pending = lines.pop()
        for line in lines:
            _, found, body = line.partition(b'kast_transport ')
            if found:
                if len(self.records) == self.MAX_RECORDS:
                    raise TransportWitnessRejected(TransportWitnessFailure.BOUND)
                try:
                    self.records.append(admit_transport_record(json.loads(body)))
                except (ValueError, UnicodeError):
                    raise TransportWitnessRejected(TransportWitnessFailure.RECORD) from None

    def completed(self, stage):
        return {row.connection for row in self.records
                if row.stage is stage and row.outcome is TransportOutcome.COMPLETED}

    def await_condition(self, condition):
        deadline = time.monotonic() + 10
        while True:
            self.read()
            if condition():
                return
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self.notifications.control([], 1, remaining):
                raise TransportWitnessRejected(TransportWitnessFailure.DEADLINE)

    def await_drained(self, minimum_connections):
        def drained():
            overloaded = {row.connection for row in self.records
                          if row.failure is EndpointFailure.ADMISSION_CAPACITY_EXCEEDED}
            return (len(self.completed(TransportStage.ACCEPT)) >= minimum_connections and
                    self.completed(TransportStage.ACCEPT) <= self.completed(TransportStage.CONNECTION_RELEASE) | overloaded)
        self.await_condition(drained)

    def await_reading(self, before, expected):
        def reading():
            entered = {row.connection for row in self.records
                       if row.stage is TransportStage.REQUEST_READ and row.outcome is TransportOutcome.STARTED} - before
            if entered & self.completed(TransportStage.CONNECTION_RELEASE):
                raise TransportWitnessRejected(TransportWitnessFailure.SATURATION_LOST)
            return len(entered) == expected
        self.await_condition(reading)

    def summary(self):
        self.read()
        accepted = self.completed(TransportStage.ACCEPT)
        replies = Counter(row.connection for row in self.records
                          if row.stage is TransportStage.REPLY_WRITE and row.outcome is TransportOutcome.COMPLETED)
        encoded = self.completed(TransportStage.ENCODING)
        correlated = all(connection in accepted and connection in encoded for connection in replies)
        maximum = max(replies.values(), default=0)
        summaries = []
        for stage in TransportStage:
            for outcome in TransportOutcome:
                rows = [row for row in self.records if row.stage is stage and row.outcome is outcome]
                if rows:
                    summaries.append(TransportStageSummary(stage, outcome, len(rows),
                        max(row.elapsed_nanos for row in rows), sum(row.elapsed_nanos for row in rows),
                        max(row.bytes for row in rows), sum(row.bytes for row in rows)))
        return TransportSummary(bool(replies) and maximum == 1 and correlated,
            len(accepted), len(self.completed(TransportStage.CONNECTION_RELEASE)), sum(replies.values()),
            maximum, correlated, summaries)
