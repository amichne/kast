"""One bounded native frame per peer; receipts exclude descriptors and response payloads."""
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
from enum import Enum
from hosted_transport_observation import EndpointFailure
import hashlib
import json
import socket
import struct
import time


class PeerCase(str, Enum):
    DISCONNECTED = 'disconnected_peer'
    MALFORMED = 'malformed_input'
    SATURATED = 'saturated_admission'
    HEALTH = 'listener_health'


class PeerOutcome(str, Enum):
    PASSED = 'passed'
    REJECTED = 'rejected'


class PeerFailure(str, Enum):
    IO = 'io_rejected'
    TIMEOUT = 'deadline_exceeded'
    INCOMPLETE = 'incomplete_frame'
    BOUND = 'frame_bound_exceeded'
    EXTRA = 'extra_response'
    SHAPE = 'response_shape_rejected'
    AUTHORITY = 'response_authority_rejected'
    SCHEMA = 'response_schema_rejected'


class EndpointAdmissionFailure(str, Enum):
    DESCRIPTOR = 'endpoint_descriptor_rejected'
    ROOT = 'endpoint_root_mismatch'
    HOST = 'endpoint_host_mismatch'
    SOCKET = 'endpoint_socket_mismatch'
    CONTRACT = 'endpoint_contract_rejected'


class EndpointAdmissionRejected(ValueError):
    def __init__(self, failure):
        self.failure = failure
        super().__init__(failure.value)


class TerminalReply(str, Enum):
    UNOBSERVED = 'unobserved_after_disconnect'
    SINGLE = 'one_complete_frame_then_eof'
    UNPROVEN = 'unproven'


@dataclass(frozen=True)
class HostedPeerEndpoint:
    root: str
    host: str
    socket: str
    protocol: int
    query_schema: str


@dataclass(frozen=True)
class DescribeRequest:
    root: str
    type: str = field(default='DESCRIBE', init=False)


@dataclass(frozen=True)
class ExpectedPeerRejection:
    failure: EndpointFailure
    type: str = field(default='HOST_REJECTED', init=False)


@dataclass(frozen=True)
class ExpectedReady:
    status: str = field(default="admission_ready", init=False)


@dataclass(frozen=True)
class PeerAttempt:
    case: PeerCase
    outcome: PeerOutcome
    terminalReply: TerminalReply
    requestBytes: int
    responseBytes: int
    elapsedNanos: int
    failure: PeerFailure | None = None
    attempts: int = 1
    schemaDigest: str | None = None


class PeerRejected(ValueError):
    def __init__(self, failure):
        self.failure = failure
        super().__init__(failure.value)


def admit_peer_endpoint(isolation, workspace, live):
    root_digest = hashlib.sha256(str(workspace).encode()).hexdigest()[:32]
    descriptor = isolation.root / 'home/.kast/ide-hosted' / root_digest / 'endpoint.json'
    if descriptor.is_symlink() or not descriptor.is_file() or descriptor.stat().st_size > 16384:
        raise EndpointAdmissionRejected(EndpointAdmissionFailure.DESCRIPTOR)
    try:
        endpoint = json.loads(descriptor.read_text())
    except (ValueError, UnicodeError):
        raise EndpointAdmissionRejected(EndpointAdmissionFailure.CONTRACT) from None
    if not isinstance(endpoint, dict):
        raise EndpointAdmissionRejected(EndpointAdmissionFailure.CONTRACT)
    for admitted, failure in (
            (endpoint.get('root') == str(workspace), EndpointAdmissionFailure.ROOT),
            (endpoint.get('host') == live['host'], EndpointAdmissionFailure.HOST),
            (endpoint.get('socket') == str(descriptor.parent / 'host.sock'), EndpointAdmissionFailure.SOCKET),
            (endpoint.get('type') == 'KAST_IDE_ENDPOINT' and endpoint.get('protocol') == 3
             and isinstance(endpoint.get('querySchema'), str), EndpointAdmissionFailure.CONTRACT)):
        if not admitted:
            raise EndpointAdmissionRejected(failure)
    return HostedPeerEndpoint(str(workspace), live['host'], endpoint['socket'],
                              endpoint['protocol'], endpoint['querySchema'])


@contextmanager
def connected_peer(endpoint):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as peer:
        peer.settimeout(10)
        peer.connect(endpoint.socket)
        yield peer


def _receive(peer, count, deadline):
    parts = bytearray()
    while len(parts) < count:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise PeerRejected(PeerFailure.TIMEOUT)
        peer.settimeout(remaining)
        part = peer.recv(count - len(parts))
        if not part:
            raise PeerRejected(PeerFailure.INCOMPLETE)
        parts.extend(part)
    return bytes(parts)


def receive_terminal_reply(peer):
    deadline = time.monotonic() + 10
    length = struct.unpack('>I', _receive(peer, 4, deadline))[0]
    if not 1 <= length <= 65536:
        raise PeerRejected(PeerFailure.BOUND)
    body = _receive(peer, length, deadline)
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise PeerRejected(PeerFailure.TIMEOUT)
    peer.settimeout(remaining)
    if peer.recv(1):
        raise PeerRejected(PeerFailure.EXTRA)
    try:
        document = json.loads(body)
    except (ValueError, UnicodeError):
        raise PeerRejected(PeerFailure.SHAPE) from None
    return document, length + 4


def probe_peer(endpoint, case, schema=None):
    started = time.monotonic_ns()
    sent = received = 0
    terminal = TerminalReply.UNPROVEN
    failure = None
    schema_digest = None
    try:
        # Deliberately malformed JSON is the negative fixture, not a request DTO.
        payload = b'{' if case is PeerCase.MALFORMED else json.dumps(asdict(DescribeRequest(endpoint.root))).encode()
        with connected_peer(endpoint) as peer:
            peer.sendall(struct.pack('>I', len(payload)) + payload)
            sent = len(payload) + 4
            if case is PeerCase.DISCONNECTED:
                terminal = TerminalReply.UNOBSERVED
            else:
                document, received = receive_terminal_reply(peer)
                terminal = TerminalReply.SINGLE
                if schema is not None:
                    if not schema.admits(document):
                        raise PeerRejected(PeerFailure.SCHEMA)
                    schema_digest = schema.digest
                if case in (PeerCase.MALFORMED, PeerCase.SATURATED):
                    expected = (EndpointFailure.INVALID_REQUEST if case is PeerCase.MALFORMED else EndpointFailure.ADMISSION_CAPACITY_EXCEEDED)
                    if document != asdict(ExpectedPeerRejection(expected)):
                        raise PeerRejected(PeerFailure.SHAPE)
                elif (not isinstance(document, dict) or document.get('type') != 'KAST_IDE_HOST'
                      or document.get('root') != endpoint.root or document.get('host') != endpoint.host
                      or document.get('protocol') != endpoint.protocol
                      or document.get('querySchema') != endpoint.query_schema
                      or document.get('readiness') != asdict(ExpectedReady())):
                    raise PeerRejected(PeerFailure.AUTHORITY)
    except PeerRejected as error:
        failure = error.failure
    except TimeoutError:
        failure = PeerFailure.TIMEOUT
    except OSError:
        failure = PeerFailure.IO
    return PeerAttempt(case, PeerOutcome.PASSED if failure is None else PeerOutcome.REJECTED,
                       terminal, sent, received, time.monotonic_ns() - started, failure, schemaDigest=schema_digest)
