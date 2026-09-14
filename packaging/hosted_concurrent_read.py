"""Barrier-controlled installed CLI replay; retain bounded verdicts, never source payloads."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from enum import Enum
import hashlib
import json
import socket
import struct
from threading import Barrier, BrokenBarrierError

from hosted_read_transport import ReadTransportRejected, ReadTransportFailure, ReadProviderFailure

CLIENTS = 12
ROUNDS = 13


class ConcurrentOutcome(str, Enum):
    PASSED = 'passed'
    SEMANTIC_REJECTED = 'semantic_rejected'
    TRANSPORT_REJECTED = 'transport_rejected'
    BARRIER_REJECTED = 'barrier_rejected'


@dataclass(frozen=True)
class ConcurrentAttempt:
    client: int
    round: int
    case: str
    tool: str
    outcome: ConcurrentOutcome
    schemaDigest: str | None = None
    failure: ReadTransportFailure | None = None
    providerFailure: ReadProviderFailure | None = None
    outputViolationEvidence: dict | None = None


class ReplayOutcome(str, Enum):
    PASSED = 'passed'
    REJECTED = 'rejected'


@dataclass(frozen=True)
class ConcurrentReplay:
    outcome: ReplayOutcome
    firstAttempts: int
    passedCount: int
    attempts: list[ConcurrentAttempt]
    schemaVersion: int = 1
    clients: int = CLIENTS
    rounds: int = ROUNDS
    serialRetries: int = 0
    blockedPeer: bool = True
    sourcePayloadsLogged: bool = False


def run_concurrent_read_regression(isolation, fixture, oracle, transport, live):
    cases = tuple(case for case in oracle.cases(fixture.oracle) if case.name.startswith('exact-'))
    if len(cases) != 10:
        raise ValueError('CONCURRENT_FIXTURE_REJECTED')
    barrier = Barrier(CLIENTS)

    def worker(client):
        attempts = []
        for round_number in range(ROUNDS):
            case = cases[(client + round_number) % len(cases)]
            tool, _, arguments = oracle.invocation(case, oracle.ToolSurface.PUBLIC)
            try:
                barrier.wait(timeout=30)
                response = transport.invoke('cli', tool, arguments)
                digest = transport.validate(tool, response)
                assessment = oracle.assess(case, response, fixture.workspace, fixture.oracle)
                passed = (response.get('live') == live and response.get('status') == 'complete'
                          and assessment['finding'] == oracle.Finding.REPRODUCED.value
                          and all(assessment['assertions'].values()))
                attempts.append(ConcurrentAttempt(client, round_number, case.name, tool,
                    ConcurrentOutcome.PASSED if passed else ConcurrentOutcome.SEMANTIC_REJECTED, digest))
            except ReadTransportRejected as error:
                attempts.append(ConcurrentAttempt(client, round_number, case.name, tool,
                    ConcurrentOutcome.TRANSPORT_REJECTED, failure=error.reason, providerFailure=error.provider_failure,
                    outputViolationEvidence=error.output_violation_evidence))
            except BrokenBarrierError:
                attempts.append(ConcurrentAttempt(client, round_number, case.name, tool,
                    ConcurrentOutcome.BARRIER_REJECTED))
                break
        return attempts

    with blocked_peer(isolation, fixture.workspace, live):
        with ThreadPoolExecutor(max_workers=CLIENTS, thread_name_prefix='kast-installed-read') as executor:
            futures = [executor.submit(worker, client) for client in range(CLIENTS)]
            attempts = [attempt for future in futures for attempt in future.result()]
    passed = len(attempts) == CLIENTS * ROUNDS and all(attempt.outcome is ConcurrentOutcome.PASSED for attempt in attempts)
    return asdict(ConcurrentReplay(ReplayOutcome.PASSED if passed else ReplayOutcome.REJECTED,
        len(attempts), sum(attempt.outcome is ConcurrentOutcome.PASSED for attempt in attempts), attempts))



@contextmanager
def blocked_peer(isolation, workspace, live):
    root_digest = hashlib.sha256(str(workspace).encode()).hexdigest()[:32]
    descriptor = isolation.root / 'home/.kast/ide-hosted' / root_digest / 'endpoint.json'
    if descriptor.is_symlink() or not descriptor.is_file() or descriptor.stat().st_size > 16384:
        raise ValueError('CONCURRENT_ENDPOINT_REJECTED')
    endpoint = json.loads(descriptor.read_text())
    if endpoint.get('root') != str(workspace) or endpoint.get('host') != live['host']:
        raise ValueError('CONCURRENT_ENDPOINT_REJECTED')
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as peer:
        peer.settimeout(10)
        peer.connect(endpoint['socket'])
        # An incomplete frame cannot occupy the serialized semantic lane.
        peer.sendall(struct.pack('>I', 128) + b'{')
        yield
