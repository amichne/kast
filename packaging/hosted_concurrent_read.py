"""Barrier-controlled installed CLI replay; retain bounded verdicts, never source payloads."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager, ExitStack
from dataclasses import asdict, dataclass
from enum import Enum
import hashlib
import json
import struct
from threading import Barrier, BrokenBarrierError

from hosted_wire_schema import load_hosted_wire_schema
from hosted_read_transport import ReadTransportRejected, ReadTransportFailure, ReadProviderFailure
from hosted_peer_probe import (PeerAttempt, PeerCase, PeerOutcome, admit_peer_endpoint, connected_peer, probe_peer)
from hosted_transport_observation import NativeTransportWindow, TransportStage, TransportSummary, TransportWitnessFailure, TransportWitnessRejected

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
    peerAttempts: list[PeerAttempt]
    transportObservation: TransportSummary | None
    qualificationFailure: TransportWitnessFailure | None
    admissionCapacity: int
    configurationDigest: str
    disconnectedPeer: bool
    malformedPeer: bool
    saturatedAdmission: bool
    admissionDrained: bool
    listenerHealthy: bool
    peerFirstAttempts: int
    peerPassedCount: int
    schemaVersion: int = 2
    clients: int = CLIENTS
    rounds: int = ROUNDS
    serialRetries: int = 0
    blockedPeer: bool = True
    sourcePayloadsLogged: bool = False


def run_concurrent_read_regression(isolation, fixture, oracle, transport, live):
    cases = tuple(case for case in oracle.cases(fixture.oracle) if case.name.startswith('exact-'))
    if len(cases) != 10:
        raise ValueError('CONCURRENT_FIXTURE_REJECTED')
    barrier = Barrier(CLIENTS + 1)

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

    endpoint = admit_peer_endpoint(isolation, fixture.workspace, live)
    capacity, configuration_digest = admission_capacity(isolation, transport)
    wire_schema = load_hosted_wire_schema(transport.product)

    def faulty_peers():
        results = []
        for round_number in range(ROUNDS):
            try:
                barrier.wait(timeout=30)
            except BrokenBarrierError:
                return results
            if round_number in (0, 1):
                results.append(probe_peer(endpoint, PeerCase.DISCONNECTED if round_number == 0 else PeerCase.MALFORMED, wire_schema))
        return results

    attempts, peer_attempts = [], []
    observation = None
    qualification_failure = None
    drained = False
    try:
        with NativeTransportWindow(isolation.root / 'ide/log/idea.log') as observed:
            with blocked_peer(endpoint):
                with ThreadPoolExecutor(max_workers=CLIENTS + 1, thread_name_prefix='kast-installed-read') as executor:
                    faults = executor.submit(faulty_peers)
                    futures = [executor.submit(worker, client) for client in range(CLIENTS)]
                    attempts = [attempt for future in futures for attempt in future.result()]
                    peer_attempts = faults.result()
            observed.await_drained(CLIENTS * ROUNDS + 3)
            before = observed.completed(TransportStage.ACCEPT)
            with ExitStack() as peers:
                for _ in range(capacity):
                    peer = peers.enter_context(connected_peer(endpoint))
                    peer.sendall(struct.pack('>I', 128) + b'{')
                observed.await_reading(before, capacity)
                peer_attempts.append(probe_peer(endpoint, PeerCase.SATURATED, wire_schema))
            observed.await_drained(len(before) + capacity + 1)
            peer_attempts.append(probe_peer(endpoint, PeerCase.HEALTH, wire_schema))
            observed.await_drained(len(before) + capacity + 2)
            observation = observed.summary()
            drained = True
    except TransportWitnessRejected as error:
        qualification_failure = error.failure
    except OSError:
        qualification_failure = TransportWitnessFailure.IO
    passed_peers = {attempt.case for attempt in peer_attempts if attempt.outcome is PeerOutcome.PASSED}
    passed = (len(attempts) == CLIENTS * ROUNDS and all(attempt.outcome is ConcurrentOutcome.PASSED for attempt in attempts)
              and passed_peers == set(PeerCase) and qualification_failure is None
              and all(attempt.schemaDigest == wire_schema.digest for attempt in peer_attempts
                      if attempt.case is not PeerCase.DISCONNECTED)
              and observation is not None and observation.passed
              and observation.completeReplies >= CLIENTS * ROUNDS + 3)
    return asdict(ConcurrentReplay(ReplayOutcome.PASSED if passed else ReplayOutcome.REJECTED,
        len(attempts), sum(attempt.outcome is ConcurrentOutcome.PASSED for attempt in attempts), attempts,
        peer_attempts, observation, qualification_failure, capacity, configuration_digest,
        PeerCase.DISCONNECTED in passed_peers, PeerCase.MALFORMED in passed_peers,
        PeerCase.SATURATED in passed_peers, drained, PeerCase.HEALTH in passed_peers,
        len(peer_attempts), len(passed_peers)))


@contextmanager
def blocked_peer(endpoint):
    with connected_peer(endpoint) as peer:
        # An incomplete frame cannot occupy the serialized semantic lane.
        peer.sendall(struct.pack('>I', 128) + b'{')
        yield


def admission_capacity(isolation, transport):
    schema = transport.product / 'share/kast/configuration-schema.json'
    if schema.is_symlink() or not schema.is_file() or schema.stat().st_size > 1024 * 1024:
        raise ValueError('CONCURRENT_CONFIGURATION_REJECTED')
    raw = schema.read_bytes()
    parameters = json.loads(raw).get('parameters', [])
    selected = [entry for entry in parameters if entry.get('key') == 'KAST_READ_HOST_CONNECTIONS']
    if (len(selected) != 1 or selected[0].get('defaultAuthority') != 'ReadLimitParameter.HOST_CONNECTIONS'
            or not isinstance(selected[0].get('defaultValue'), str)
            or not selected[0]['defaultValue'].isdigit()):
        raise ValueError('CONCURRENT_CONFIGURATION_REJECTED')
    capacity = int(selected[0]['defaultValue'])
    # This owned fixture uses catalog defaults; reject an override rather than guessing active admission.
    options = (isolation.root / 'ide/idea.vmoptions').read_text()
    if (not 1 <= capacity <= 32 or 'kast.read.host.connections' in options
            or 'KAST_READ_HOST_CONNECTIONS' in transport.fixture.environment):
        raise ValueError('CONCURRENT_CONFIGURATION_REJECTED')
    return capacity, 'sha256:' + hashlib.sha256(raw).hexdigest()
