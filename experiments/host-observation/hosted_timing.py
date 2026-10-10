"""Bounded observational joins. These records grant no semantic or freshness authority."""
from dataclasses import dataclass, field, fields
from enum import Enum
import json
import os
import time
from uuid import UUID


class TimingFailure(str, Enum):
    NOT_CAPTURED = 'NOT_CAPTURED'
    LOG_UNAVAILABLE = 'LOG_UNAVAILABLE'
    LOG_ROTATED = 'LOG_ROTATED'
    LOG_TRUNCATED = 'LOG_TRUNCATED'
    BYTE_LIMIT = 'BYTE_LIMIT'
    INVALID_RECORD = 'INVALID_RECORD'
    LOG_RECORD_INCOMPLETE = 'LOG_RECORD_INCOMPLETE'
    SEMANTIC_CORRELATION_UNAVAILABLE = 'SEMANTIC_CORRELATION_UNAVAILABLE'
    READ_JOIN_UNAVAILABLE = 'READ_JOIN_UNAVAILABLE'
    READ_JOIN_AMBIGUOUS = 'READ_JOIN_AMBIGUOUS'
    READ_RECEIPT_UNAVAILABLE = 'READ_RECEIPT_UNAVAILABLE'
    TRANSPORT_INCOMPLETE = 'TRANSPORT_INCOMPLETE'
    TRANSPORT_ORDER_REJECTED = 'TRANSPORT_ORDER_REJECTED'
    SMART_MODE_WAIT_INCOMPLETE = 'SMART_MODE_WAIT_INCOMPLETE'


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
    UNSAVED_DOCUMENTS = 'UNSAVED_DOCUMENTS'
    MODEL_REFRESH_REQUIRED = 'MODEL_REFRESH_REQUIRED'
    RESPONSE_REJECTED = 'RESPONSE_REJECTED'
    RESULT_TOO_LARGE = 'RESULT_TOO_LARGE'
    APPROVAL_UNAVAILABLE = 'APPROVAL_UNAVAILABLE'
    APPROVAL_REJECTED = 'APPROVAL_REJECTED'


class SmartModeOutcome(str, Enum):
    STARTED = 'STARTED'
    READY = 'READY'
    TIMED_OUT = 'TIMED_OUT'
    DISPOSED = 'DISPOSED'
    CANCELLED = 'CANCELLED'
    PLATFORM_RUNTIME_FAILURE = 'PLATFORM_RUNTIME_FAILURE'
    PLATFORM_LINKAGE_FAILURE = 'PLATFORM_LINKAGE_FAILURE'


@dataclass(frozen=True)
class ReadJoin:
    connectionId: str
    readId: str


@dataclass(frozen=True)
class TransportRecord:
    connectionId: str
    stage: TransportStage
    outcome: TransportOutcome
    elapsedNanos: int
    bytes: int
    failure: EndpointFailure | None


@dataclass(frozen=True)
class SmartModeWait:
    outcome: SmartModeOutcome
    elapsedNanos: int
    statusPolls: int
    limitMillis: int


@dataclass(frozen=True)
class SmartModeRecord:
    connectionId: str
    wait: SmartModeWait


class TimingState(str, Enum):
    JOINED = 'JOINED'
    UNAVAILABLE = 'UNAVAILABLE'


@dataclass(frozen=True)
class ReadTimingEvidence:
    readId: str
    # Preserve the owner's versioned diagnostic DTO, including readActions/nativeCalls/nativeSearches.
    # Its aggregate clocks and explicit qualifications remain separate from transport clocks.
    diagnostic: dict
    phaseEntries: tuple[dict, ...]


@dataclass(frozen=True)
class JoinedTiming:
    connectionId: str
    readId: str
    readIds: tuple[str, ...]
    transport: tuple[TransportRecord, ...]
    smartModeWaits: tuple[SmartModeRecord, ...]
    reads: tuple[ReadTimingEvidence, ...]
    type: TimingState = field(default=TimingState.JOINED, init=False)


@dataclass(frozen=True)
class UnavailableTiming:
    failures: tuple[TimingFailure, ...] = (TimingFailure.NOT_CAPTURED,)
    # Preserve qualified prefixes; missing terminal evidence never becomes an empty success.
    joins: tuple[ReadJoin, ...] = ()
    transport: tuple[TransportRecord, ...] = ()
    smartModeWaits: tuple[SmartModeRecord, ...] = ()
    type: TimingState = field(default=TimingState.UNAVAILABLE, init=False)


@dataclass(frozen=True)
class ObservationWindow:
    # Existing semantic documents are opaque versioned contracts; their owners remain authoritative.
    diagnostics: tuple[dict, ...] = ()
    phases: tuple[dict, ...] = ()
    joins: tuple[ReadJoin, ...] = ()
    transport: tuple[TransportRecord, ...] = ()
    smartModeWaits: tuple[SmartModeRecord, ...] = ()
    failures: tuple[TimingFailure, ...] = ()


OBSERVATION_POLICY = dict(maxWaitMillis=250, maxAppendedBytes=2 * 1024 * 1024, maxRotations=1)


def exact_fields(value, record):
    if not isinstance(value, dict) or set(value) != {f.name for f in fields(record) if f.init}:
        raise ValueError('fields')


def identity(value):
    if not isinstance(value, str) or str(UUID(value)) != value: raise ValueError('identity')
    return value


def quantity(value):
    if type(value) is not int or not 0 <= value <= 2**63 - 1: raise ValueError('quantity')
    return value


def decode_join(value):
    exact_fields(value, ReadJoin)
    return ReadJoin(identity(value['connectionId']), identity(value['readId']))


def decode_transport(value):
    exact_fields(value, TransportRecord)
    return TransportRecord(identity(value['connectionId']), TransportStage(value['stage']),
        TransportOutcome(value['outcome']), quantity(value['elapsedNanos']), quantity(value['bytes']),
        None if value['failure'] is None else EndpointFailure(value['failure']))


def decode_wait(value):
    exact_fields(value, SmartModeRecord)
    exact_fields(value['wait'], SmartModeWait)
    wait = value['wait']
    return SmartModeRecord(identity(value['connectionId']), SmartModeWait(SmartModeOutcome(wait['outcome']),
        quantity(wait['elapsedNanos']), quantity(wait['statusPolls']), quantity(wait['limitMillis'])))


def unique_fields(pairs):
    value = {}
    for key, item in pairs:
        if key in value: raise ValueError('duplicate')
        value[key] = item
    return value


def decode_window(data):
    diagnostics, phases, joins, transport, waits, failures = [], [], [], [], [], []
    markers = ((b'kast_semantic_read ', diagnostics, None), (b'kast_semantic_phase ', phases, None),
               (b'kast_transport_read ', joins, decode_join), (b'kast_transport ', transport, decode_transport),
               (b'kast_smart_mode_wait ', waits, decode_wait))
    for line in data.splitlines(keepends=True):
        for marker, target, decode in markers:
            if marker not in line: continue
            if not line.endswith(b'\n'):
                failures.append(TimingFailure.LOG_RECORD_INCOMPLETE)
                continue
            try:
                value = json.loads(line.split(marker, 1)[1], object_pairs_hook=unique_fields)
                if not isinstance(value, dict): raise ValueError('document')
                if decode is None and 'readId' in value and not isinstance(value['readId'], str):
                    raise ValueError('semantic identity')
                target.append(decode(value) if decode else value)
            except (ValueError, TypeError, KeyError, RecursionError):
                failures.append(TimingFailure.INVALID_RECORD)
    return ObservationWindow(tuple(diagnostics), tuple(phases), tuple(joins), tuple(transport),
                             tuple(waits), tuple(dict.fromkeys(failures)))


def read_observations(path, before):
    """One exact inode tail and at most its immediate rollover head share the original byte limit."""
    try:
        if path.is_symlink(): return ObservationWindow(failures=(TimingFailure.LOG_UNAVAILABLE,))
        after = path.stat()
        if after.st_ino == before.st_ino:
            windows = [(path, before.st_size, after.st_size - before.st_size, after.st_ino)]
        else:
            previous = path.with_name(path.stem + '.1' + path.suffix)
            if previous.is_symlink() or not previous.is_file():
                return ObservationWindow(failures=(TimingFailure.LOG_ROTATED,))
            rolled = previous.stat()
            if rolled.st_ino != before.st_ino: return ObservationWindow(failures=(TimingFailure.LOG_ROTATED,))
            windows = [(previous, before.st_size, rolled.st_size - before.st_size, rolled.st_ino),
                       (path, 0, after.st_size, after.st_ino)]
        lengths = [length for _, _, length, _ in windows]
        if any(length < 0 for length in lengths): return ObservationWindow(failures=(TimingFailure.LOG_TRUNCATED,))
        if sum(lengths) > OBSERVATION_POLICY['maxAppendedBytes']:
            return ObservationWindow(failures=(TimingFailure.BYTE_LIMIT,))
        chunks = []
        for observed_path, offset, length, inode in windows:
            with observed_path.open('rb') as stream:
                opened = os.fstat(stream.fileno())
                if opened.st_ino != inode or opened.st_size < offset + length:
                    return ObservationWindow(failures=(TimingFailure.LOG_TRUNCATED,))
                stream.seek(offset)
                chunk = stream.read(length)
                if len(chunk) != length: return ObservationWindow(failures=(TimingFailure.LOG_TRUNCATED,))
                chunks.append(chunk)
        return decode_window(b''.join(chunks))
    except OSError:
        return ObservationWindow(failures=(TimingFailure.LOG_UNAVAILABLE,))


def join_timing(window, read_id):
    def reject(failure):
        return UnavailableTiming(tuple(dict.fromkeys((*window.failures, failure))), window.joins,
                                 window.transport, window.smartModeWaits)
    if window.failures: return reject(window.failures[0])
    matches = [join for join in window.joins if join.readId == read_id]
    if not matches: return reject(TimingFailure.READ_JOIN_UNAVAILABLE)
    # Duplicated joins are rejected even when the duplicate carries identical values.
    if len(matches) != 1: return reject(TimingFailure.READ_JOIN_AMBIGUOUS)
    connection = matches[0].connectionId
    joins = [join for join in window.joins if join.connectionId == connection]
    read_ids = tuple(join.readId for join in joins)
    if len(set(read_ids)) != len(read_ids) or any(
        sum(join.readId == read for join in window.joins) != 1 for read in read_ids):
        return reject(TimingFailure.READ_JOIN_AMBIGUOUS)
    if any(sum(doc.get('readId') == read for doc in window.diagnostics) != 1 for read in read_ids):
        return reject(TimingFailure.READ_RECEIPT_UNAVAILABLE)
    transport = tuple(record for record in window.transport if record.connectionId == connection)
    # ACCEPT starts before a replay call's append window and includes idle listener time.
    # Preserve it as raw evidence, but do not require or attribute it to query latency.
    measured = [record for record in transport if record.stage != TransportStage.ACCEPT]
    required = list(TransportStage)[1:]
    if len(measured) != len(required) * 2: return reject(TimingFailure.TRANSPORT_INCOMPLETE)
    for index, stage in enumerate(required):
        start, terminal = measured[2*index:2*index+2]
        if (start.stage != stage or terminal.stage != stage or start.outcome != TransportOutcome.STARTED or
                terminal.outcome == TransportOutcome.STARTED or terminal.elapsedNanos < start.elapsedNanos):
            return reject(TimingFailure.TRANSPORT_ORDER_REJECTED)
    if measured[-1].outcome != TransportOutcome.COMPLETED:
        return reject(TimingFailure.TRANSPORT_INCOMPLETE)
    waits = tuple(record for record in window.smartModeWaits if record.connectionId == connection)
    if len(waits) % 2: return reject(TimingFailure.SMART_MODE_WAIT_INCOMPLETE)
    for index in range(0, len(waits), 2):
        start, terminal = waits[index].wait, waits[index+1].wait
        if (start.outcome != SmartModeOutcome.STARTED or terminal.outcome == SmartModeOutcome.STARTED or
                start.limitMillis != 15000 or terminal.limitMillis != start.limitMillis or
                terminal.elapsedNanos < start.elapsedNanos or terminal.statusPolls < start.statusPolls):
            return reject(TimingFailure.SMART_MODE_WAIT_INCOMPLETE)
    reads = tuple(ReadTimingEvidence(read, next(doc for doc in window.diagnostics if doc.get('readId') == read),
        tuple(phase for phase in window.phases if phase.get('readId') == read)) for read in read_ids)
    return JoinedTiming(connection, read_id, read_ids, transport, waits, reads)


def release_observed(window, read_ids):
    released = {record.connectionId for record in window.transport
                if record.stage == TransportStage.CONNECTION_RELEASE and record.outcome == TransportOutcome.COMPLETED}
    return any(join.readId in read_ids and join.connectionId in released for join in window.joins)


def any_read_released(window):
    return release_observed(window, {doc.get('readId') for doc in window.diagnostics})


def collect_observations(path, before, observe=read_observations, clock=time.monotonic, pause=time.sleep,
                         ready=any_read_released):
    deadline = clock() + OBSERVATION_POLICY['maxWaitMillis'] / 1000
    while True:
        window = observe(path, before)
        if (any(failure != TimingFailure.LOG_RECORD_INCOMPLETE for failure in window.failures) or
            (not window.failures and ready(window))): return window
        if clock() >= deadline: return window
        pause(0.01)


def load_window(value):
    """Restore typed boundary records; correlation is re-derived from the preserved actual documents."""
    exact_fields(value, ObservationWindow)
    for key in ('diagnostics', 'phases', 'joins', 'transport', 'smartModeWaits', 'failures'):
        if not isinstance(value[key], list): raise ValueError('INVALID_OBSERVATION_SEQUENCE')
    for key in ('diagnostics', 'phases'):
        if not isinstance(value[key], list) or any(not isinstance(item, dict) for item in value[key]):
            raise ValueError('INVALID_SEMANTIC_OBSERVATIONS')
    failures = tuple(TimingFailure(item) for item in value['failures'])
    if len(set(failures)) != len(failures): raise ValueError('INVALID_OBSERVATION_FAILURES')
    return ObservationWindow(tuple(value['diagnostics']), tuple(value['phases']),
        tuple(decode_join(item) for item in value['joins']),
        tuple(decode_transport(item) for item in value['transport']),
        tuple(decode_wait(item) for item in value['smartModeWaits']), failures)
