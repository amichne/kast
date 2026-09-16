#!/usr/bin/env python3
"""Offline frame/oracle evidence; native listener and compiler qualification remain separate."""
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field, replace
import io
import hashlib
from types import SimpleNamespace
import json
from pathlib import Path
import select
import struct
import unittest
import tempfile
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from unittest.mock import Mock, patch

from hosted_peer_probe import (HostedPeerEndpoint, PeerAttempt, PeerCase, PeerFailure, PeerOutcome,
    TerminalReply, probe_peer, admit_peer_endpoint, EndpointAdmissionFailure, EndpointAdmissionRejected)
from hosted_transport_observation import (EndpointFailure, NativeTransportWindow, TransportOutcome,
    TransportRecord, TransportStage, admit_transport_record)


@dataclass(frozen=True)
class RejectionFixture:
    failure: str = 'INVALID_REQUEST'
    type: str = 'HOST_REJECTED'


@dataclass(frozen=True)
class ReadyFixture:
    # HostedReadinessDocument uses @JsonClassDiscriminator("status").
    status: str = 'admission_ready'


@dataclass(frozen=True)
class HostFixture:
    root: str = '/private/workspace'
    host: str = 'private-host'
    protocol: int = 3
    querySchema: str = 'kast.query.run.v2'
    type: str = 'KAST_IDE_HOST'
    readiness: ReadyFixture = field(default_factory=ReadyFixture)


@dataclass(frozen=True)
class EndpointFixture:
    root: str
    socket: str
    host: str = 'private-host'
    protocol: int = 3
    querySchema: str = 'kast.query.run.v2'
    type: str = 'KAST_IDE_ENDPOINT'


@dataclass(frozen=True)
class ObservationFixture:
    connectionId: str = '00000000-0000-0000-0000-000000000001'
    stage: str = 'ACCEPT'
    outcome: str = 'COMPLETED'
    elapsedNanos: int = 11
    bytes: int = 0
    failure: str | None = None


class PeerProbeTest(unittest.TestCase):
    endpoint = HostedPeerEndpoint('/private/workspace', 'private-host', '/private/socket', 3, 'kast.query.run.v2')

    def test_endpoint_admission_retains_exact_failure_without_descriptor_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workspace = root / 'workspace'
            digest = hashlib.sha256(str(workspace).encode()).hexdigest()[:32]
            descriptor = root / 'home/.kast/ide-hosted' / digest / 'endpoint.json'
            descriptor.parent.mkdir(parents=True)
            isolation = SimpleNamespace(root=root)
            live = {'host': 'private-host'}
            with self.assertRaises(EndpointAdmissionRejected) as rejected:
                admit_peer_endpoint(isolation, workspace, live)
            self.assertIs(EndpointAdmissionFailure.DESCRIPTOR, rejected.exception.failure)
            expected = EndpointFixture(str(workspace), str(descriptor.parent / 'host.sock'))
            descriptor.write_text(json.dumps(asdict(expected)))
            self.assertEqual(expected.socket, admit_peer_endpoint(isolation, workspace, live).socket)
            for changed, failure in ((replace(expected, root='/foreign'), EndpointAdmissionFailure.ROOT),
                    (replace(expected, host='foreign'), EndpointAdmissionFailure.HOST),
                    (replace(expected, socket='/foreign'), EndpointAdmissionFailure.SOCKET),
                    (replace(expected, protocol=4), EndpointAdmissionFailure.CONTRACT)):
                descriptor.write_text(json.dumps(asdict(changed)))
                with self.assertRaises(EndpointAdmissionRejected) as rejected:
                    admit_peer_endpoint(isolation, workspace, live)
                self.assertIs(failure, rejected.exception.failure)
                self.assertNotIn('foreign', str(rejected.exception))

    def frame(self, document):
        body = json.dumps(asdict(document)).encode()
        return struct.pack('>I', len(body)) + body

    @contextmanager
    def connection(self, output):
        reader = io.BytesIO(output)
        peer = Mock()
        peer.recv.side_effect = lambda count: reader.read(min(count, 3))
        self.peer = peer
        yield peer

    def probe(self, case, output, schema=None):
        with patch('hosted_peer_probe.connected_peer', side_effect=lambda _: self.connection(output)):
            return probe_peer(self.endpoint, case, schema)

    def test_actual_terminal_frame_requires_schema_admission_and_retains_only_digest(self):
        schema = Mock(digest='a' * 64)
        schema.admits.return_value = True
        result = self.probe(PeerCase.MALFORMED, self.frame(RejectionFixture()), schema)
        self.assertEqual(PeerOutcome.PASSED, result.outcome)
        self.assertEqual(schema.digest, result.schemaDigest)
        schema.admits.assert_called_once_with(asdict(RejectionFixture()))
        schema.admits.return_value = False
        result = self.probe(PeerCase.MALFORMED, self.frame(RejectionFixture()), schema)
        self.assertEqual(PeerFailure.SCHEMA, result.failure)
        self.assertIsNone(result.schemaDigest)

    def test_disconnected_peer_sends_one_complete_typed_request_and_does_not_claim_a_reply(self):
        result = self.probe(PeerCase.DISCONNECTED, b'')
        self.assertEqual(PeerOutcome.PASSED, result.outcome)
        self.assertEqual(TerminalReply.UNOBSERVED, result.terminalReply)
        self.assertEqual(1, result.attempts)
        self.peer.recv.assert_not_called()
        payload = self.peer.sendall.call_args.args[0]
        self.assertEqual(len(payload) - 4, struct.unpack('>I', payload[:4])[0])
        self.assertEqual({'type': 'DESCRIBE', 'root': self.endpoint.root}, json.loads(payload[4:]))
        self.assertNotIn('private', json.dumps(asdict(result)))

    def test_malformed_and_saturated_peers_require_the_exact_finite_terminal_reply(self):
        for case, failure in ((PeerCase.MALFORMED, 'INVALID_REQUEST'),
                              (PeerCase.SATURATED, 'ADMISSION_CAPACITY_EXCEEDED')):
            with self.subTest(case=case):
                output = self.frame(RejectionFixture(failure))
                result = self.probe(case, output)
                self.assertEqual(PeerOutcome.PASSED, result.outcome)
                self.assertEqual(TerminalReply.SINGLE, result.terminalReply)
                self.assertEqual(len(output), result.responseBytes)
                self.peer.sendall.assert_called_once()
                wrong = self.probe(case, self.frame(RejectionFixture('PRIVATE_UNKNOWN_FAILURE')))
                self.assertEqual(PeerFailure.SHAPE, wrong.failure)
                self.assertNotIn('PRIVATE_UNKNOWN_FAILURE', json.dumps(asdict(wrong)))

    def test_connected_peers_reject_extra_incomplete_and_oversized_frames_without_retry(self):
        normal = self.frame(RejectionFixture())
        for raw, failure in ((normal + normal, PeerFailure.EXTRA), (normal[:-1], PeerFailure.INCOMPLETE),
                             (struct.pack('>I', 65537), PeerFailure.BOUND)):
            with self.subTest(failure=failure):
                result = self.probe(PeerCase.MALFORMED, raw)
                self.assertEqual(PeerOutcome.REJECTED, result.outcome)
                self.assertEqual(failure, result.failure)
                self.peer.sendall.assert_called_once()

    def test_listener_health_requires_the_original_authority_and_one_complete_reply(self):
        result = self.probe(PeerCase.HEALTH, self.frame(HostFixture()))
        self.assertEqual(PeerOutcome.PASSED, result.outcome)
        for changed in (HostFixture(root='/foreign'), HostFixture(host='foreign'), HostFixture(protocol=4),
                        HostFixture(querySchema='foreign-schema'),
                        HostFixture(readiness=ReadyFixture(status='unavailable'))):
            result = self.probe(PeerCase.HEALTH, self.frame(changed))
            self.assertEqual(PeerFailure.AUTHORITY, result.failure)

    def test_listener_health_uses_the_encoded_readiness_discriminator_and_rejects_unproven_shapes(self):
        schema_path = (Path(__file__).resolve().parents[1] / 'protocol/contract/src/main/resources'
                       / 'ide-hosted/hosted-endpoint.schema.json')
        ready_schema = json.loads(schema_path.read_text())['$defs']['readiness']['oneOf'][0]
        readiness = asdict(ReadyFixture())
        self.assertEqual(set(ready_schema['required']), set(readiness))
        self.assertEqual(ready_schema['properties']['status']['const'], readiness['status'])
        result = self.probe(PeerCase.HEALTH, self.frame(HostFixture()))
        self.assertEqual(PeerOutcome.PASSED, result.outcome)
        self.assertEqual(TerminalReply.SINGLE, result.terminalReply)
        self.assertEqual(1, result.attempts)
        self.peer.sendall.assert_called_once()
        # Deliberately invalid readiness documents prove that the legacy key, unknown
        # fields, and missing readiness cannot supply passive admission evidence.
        for malformed in ({'type': 'admission_ready'}, {'status': 'unknown'},
                          {'status': 'admission_ready', 'extra': True}, {}, None):
            with self.subTest(readiness=malformed):
                document = asdict(HostFixture())
                document['readiness'] = malformed
                body = json.dumps(document).encode()
                rejected = self.probe(PeerCase.HEALTH, struct.pack('>I', len(body)) + body)
                self.assertEqual(PeerOutcome.REJECTED, rejected.outcome)
                self.assertEqual(PeerFailure.AUTHORITY, rejected.failure)
                self.peer.sendall.assert_called_once()

    def test_io_and_deadline_failures_remain_finite_without_exception_text(self):
        for failure, expected in ((OSError('private socket'), PeerFailure.IO),
                                  (TimeoutError('private timeout'), PeerFailure.TIMEOUT)):
            with patch('hosted_peer_probe.connected_peer', side_effect=failure):
                result = probe_peer(self.endpoint, PeerCase.MALFORMED)
            self.assertEqual(expected, result.failure)
            self.assertNotIn('private', json.dumps(asdict(result)))


class TransportObservationTest(unittest.TestCase):
    def record(self, stage=TransportStage.ACCEPT, outcome=TransportOutcome.COMPLETED, failure=None):
        return admit_transport_record(asdict(ObservationFixture(stage=stage.value, outcome=outcome.value,
                                                                 failure=failure)))

    def window(self, records):
        result = NativeTransportWindow(Path('/private/idea.log'))
        result.records = records
        result.read = lambda: None
        return result

    def test_unknown_unbounded_or_payload_bearing_observations_are_rejected(self):
        for field, value in (('stage', 'UNKNOWN'), ('outcome', 'UNKNOWN'), ('failure', 'PRIVATE'),
                             ('elapsedNanos', -1), ('elapsedNanos', True), ('bytes', 2**63),
                             ('connectionId', 'private token')):
            raw = asdict(ObservationFixture())
            raw[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                admit_transport_record(raw)
        raw = asdict(ObservationFixture())
        raw['payload'] = 'private source'
        with self.assertRaises(ValueError):
            admit_transport_record(raw)

    def test_reply_correlation_and_at_most_once_are_proven_per_native_connection(self):
        records = [self.record(stage) for stage in (TransportStage.ACCEPT, TransportStage.ENCODING,
                   TransportStage.REPLY_WRITE, TransportStage.CONNECTION_RELEASE)]
        report = self.window(records).summary()
        self.assertTrue(report.passed)
        self.assertEqual(1, report.completeReplies)
        self.assertEqual(1, report.maximumCompleteRepliesPerConnection)
        self.assertNotIn('00000000', json.dumps(asdict(report)))
        self.assertFalse(self.window(records + [self.record(TransportStage.REPLY_WRITE)]).summary().passed)
        self.assertFalse(self.window(records[1:]).summary().passed)

    def test_named_drain_requires_actual_release_not_reply_or_cancellation(self):
        records = [self.record(), self.record(TransportStage.REPLY_WRITE),
                   self.record(TransportStage.EXECUTION, TransportOutcome.CANCELLED)]
        window = self.window(records)
        observed = []
        window.await_condition = lambda condition: observed.append(condition())
        window.await_drained(1)
        self.assertEqual([False], observed)
        records.append(self.record(TransportStage.CONNECTION_RELEASE))
        window.await_drained(1)
        self.assertEqual([False, True], observed)
        window.await_drained(2)
        self.assertEqual([False, True, False], observed)

    def test_log_notification_advances_named_release_condition_without_polling_or_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'idea.log'
            def append(stage):
                with path.open('ab') as output:
                    output.write(b'INFO - kast_transport ' + json.dumps(asdict(ObservationFixture(stage=stage))).encode() + b'\n')
            append('ACCEPT')
            with path.open('rb') as stream:
                window = NativeTransportWindow(path)
                window.stream = stream
                window.identity = path.stat().st_dev, path.stat().st_ino
                window.notifications = Mock()
                def released(*_):
                    append('CONNECTION_RELEASE')
                    return [object()]
                window.notifications.control.side_effect = released
                window.await_drained(1)
                window.notifications.control.assert_called_once()
                self.assertEqual(1, len(window.completed(TransportStage.CONNECTION_RELEASE)))

    @unittest.skipUnless(hasattr(select, 'kqueue'), 'Native IDEA log notification requires macOS kqueue')
    def test_native_log_notification_wakes_on_the_correlated_release_record(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'idea.log'
            path.touch()
            with NativeTransportWindow(path) as window, ThreadPoolExecutor(max_workers=1) as executor:
                native = window.notifications
                waiting = Event()
                def wait_for_log(*arguments):
                    waiting.set()
                    return native.control(*arguments)
                window.notifications = Mock(wraps=native)
                window.notifications.control.side_effect = wait_for_log
                waiter = executor.submit(window.await_drained, 1)
                self.assertTrue(waiting.wait(timeout=2))
                with path.open('ab') as output:
                    for stage in ('ACCEPT', 'CONNECTION_RELEASE'):
                        output.write(b'INFO - kast_transport ' + json.dumps(asdict(ObservationFixture(stage=stage))).encode() + b'\n')
                waiter.result(timeout=2)
                self.assertEqual(1, len(window.completed(TransportStage.CONNECTION_RELEASE)))

    def test_saturated_rejection_does_not_claim_an_admitted_permit(self):
        records = [self.record(), self.record(TransportStage.SEMANTIC_ADMISSION,
            TransportOutcome.REJECTED, EndpointFailure.ADMISSION_CAPACITY_EXCEEDED.value)]
        window = self.window(records)
        observed = []
        window.await_condition = lambda condition: observed.append(condition())
        window.await_drained(1)
        self.assertEqual([True], observed)
        self.assertEqual(0, window.summary().released)

    def test_saturation_witness_rejects_a_peer_that_drained_before_admission_was_observed(self):
        records = [self.record(), self.record(TransportStage.REQUEST_READ, TransportOutcome.STARTED),
                   self.record(TransportStage.CONNECTION_RELEASE)]
        window = self.window(records)
        window.await_condition = lambda condition: condition()
        with self.assertRaisesRegex(ValueError, 'released_before_witness'):
            window.await_reading(set(), 1)


if __name__ == '__main__':
    unittest.main()
