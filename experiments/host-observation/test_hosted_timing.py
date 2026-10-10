"""Pure ownership/encoding checks and owned log windows; no native latency claims."""
from dataclasses import asdict, replace
import json
from pathlib import Path
import re
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import hosted_timing as h
import reproduce_semantic_queries as replay


CONNECTION = '11111111-1111-4111-8111-111111111111'
OTHER = '22222222-2222-4222-8222-222222222222'
READ = '33333333-3333-4333-8333-333333333333'
RETRY = '44444444-4444-4444-8444-444444444444'


def transport(connection=CONNECTION):
    # Independent producer order, excluding pre-invocation idle ACCEPT.
    result = []
    for stage, elapsed, count in ((h.TransportStage.REQUEST_READ, 11, 19),
        (h.TransportStage.SEMANTIC_ADMISSION, 12, 0), (h.TransportStage.EXECUTION, 130, 0),
        (h.TransportStage.ENCODING, 14, 29), (h.TransportStage.REPLY_WRITE, 15, 31),
        (h.TransportStage.CONNECTION_RELEASE, 16, 0)):
        result.extend((h.TransportRecord(connection, stage, h.TransportOutcome.STARTED, 0, 0, None),
                       h.TransportRecord(connection, stage, h.TransportOutcome.COMPLETED, elapsed, count, None)))
    return tuple(result)


def wait(outcome=h.SmartModeOutcome.READY, connection=CONNECTION):
    return (h.SmartModeRecord(connection, h.SmartModeWait(h.SmartModeOutcome.STARTED, 0, 0, 15000)),
            h.SmartModeRecord(connection, h.SmartModeWait(outcome, 90, 2, 15000)))


def window():
    # Semantic documents are deliberately minimal opaque inputs to this ownership rule.
    return h.ObservationWindow(diagnostics=({'readId': READ},), joins=(h.ReadJoin(CONNECTION, READ),),
                               transport=transport(), smartModeWaits=wait())


def lines(observation):
    records = [(b'kast_semantic_read ', doc) for doc in observation.diagnostics]
    records += [(b'kast_semantic_phase ', doc) for doc in observation.phases]
    records += [(b'kast_transport_read ', asdict(record)) for record in observation.joins]
    records += [(b'kast_transport ', asdict(record)) for record in observation.transport]
    records += [(b'kast_smart_mode_wait ', asdict(record)) for record in observation.smartModeWaits]
    return b''.join(b'INFO ' + marker + json.dumps(value).encode() + b'\n' for marker, value in records)


class HostedTimingTest(unittest.TestCase):
    def test_rejected_completion_joins_original_allocation_without_claiming_live_basis(self):
        response = {'status': 'rejected', 'rejection': {'type': 'COMPLETION_UNPROVEN',
                    'detail': {'diagnosticReadId': READ}}}
        own = {'readId': READ, 'correlation': {'type': 'unbound'}}
        other = {'readId': RETRY, 'correlation': {'type': 'unbound'}}
        matched = replay.correlate_diagnostic(response, (other, own))
        self.assertIsInstance(matched, replay.MatchedDiagnostic)
        self.assertEqual(own, matched.document)
        observations = replace(window(), diagnostics=(other, own))
        self.assertTrue(replay.response_read_released(response, observations))
        self.assertFalse(replay.response_read_released(response, replace(observations, transport=transport()[:-2])))
        for documents, expected in (((other,), replay.DiagnosticCorrelationFailure.MISMATCHED),
                                    ((own, own), replay.DiagnosticCorrelationFailure.AMBIGUOUS)):
            self.assertEqual(expected, replay.correlate_diagnostic(response, documents).failure)
        for identity in (None, '', 'read', 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA'):
            malformed = {'status': 'rejected', 'live': {'host': 'host', 'epoch': 4},
                         'rejection': {'type': 'COMPLETION_UNPROVEN', 'detail': {'diagnosticReadId': identity}}}
            self.assertEqual(replay.DiagnosticCorrelationFailure.MISMATCHED,
                             replay.correlate_diagnostic(malformed, (own,)).failure)
        self.assertEqual(replay.DiagnosticCorrelationFailure.UNAVAILABLE,
                         replay.correlate_diagnostic({'status': 'rejected'}, (own,)).failure)

    def rejected(self, observation, failure, read=READ):
        result = h.join_timing(observation, read)
        self.assertIsInstance(result, h.UnavailableTiming)
        self.assertIn(failure, result.failures)
        self.assertEqual(observation.transport, result.transport)
        return result

    def test_exact_join_keeps_stage_and_nested_wait_clocks_separate(self):
        result = h.join_timing(window(), READ)
        self.assertIsInstance(result, h.JoinedTiming)
        self.assertEqual((CONNECTION, READ, (READ,)), (result.connectionId, result.readId, result.readIds))
        self.assertEqual([11, 12, 130, 14, 15, 16],
                         [record.elapsedNanos for record in result.transport if record.outcome != 'STARTED'])
        self.assertEqual(90, result.smartModeWaits[1].wait.elapsedNanos)

    def test_overlapping_connection_records_do_not_join_by_proximity(self):
        original = window()
        other = transport(OTHER)
        interleaved = tuple(record for pair in zip(original.transport, other) for record in pair)
        combined = replace(original, diagnostics=(*original.diagnostics, {'readId': RETRY}),
            joins=(*original.joins, h.ReadJoin(OTHER, RETRY)), transport=interleaved,
            smartModeWaits=(*wait(connection=OTHER), *original.smartModeWaits))
        result = h.join_timing(combined, READ)
        self.assertEqual(original.transport, result.transport)
        self.assertEqual(original.smartModeWaits, result.smartModeWaits)
        self.assertEqual(OTHER, h.join_timing(combined, RETRY).connectionId)

    def test_presemantic_retry_read_ids_share_only_their_explicit_connection(self):
        original = window()
        retried = replace(original, diagnostics=({'readId': RETRY}, *original.diagnostics),
                          joins=(h.ReadJoin(CONNECTION, RETRY), *original.joins))
        self.assertEqual((RETRY, READ), h.join_timing(retried, READ).readIds)
        self.rejected(replace(retried, diagnostics=original.diagnostics), h.TimingFailure.READ_RECEIPT_UNAVAILABLE)

    def test_missing_duplicate_and_cross_connection_joins_are_finite_failures(self):
        original = window()
        self.rejected(replace(original, joins=()), h.TimingFailure.READ_JOIN_UNAVAILABLE)
        self.rejected(replace(original, joins=original.joins * 2), h.TimingFailure.READ_JOIN_AMBIGUOUS)
        self.rejected(replace(original, joins=(*original.joins, h.ReadJoin(OTHER, READ))),
                      h.TimingFailure.READ_JOIN_AMBIGUOUS)

    def test_partial_out_of_order_and_duplicate_transport_preserve_prefix(self):
        original = window()
        self.rejected(replace(original, transport=original.transport[:-1]), h.TimingFailure.TRANSPORT_INCOMPLETE)
        self.rejected(replace(original, transport=original.transport[::-1]), h.TimingFailure.TRANSPORT_ORDER_REJECTED)
        self.rejected(replace(original, transport=(*original.transport, original.transport[-1])),
                      h.TimingFailure.TRANSPORT_INCOMPLETE)
        lowered = (*original.transport[:-1], replace(original.transport[-1], outcome=h.TransportOutcome.CANCELLED))
        self.rejected(replace(original, transport=lowered), h.TimingFailure.TRANSPORT_INCOMPLETE)

    def test_idle_accept_is_retained_without_becoming_query_latency(self):
        original = window()
        accept = h.TransportRecord(CONNECTION, h.TransportStage.ACCEPT, h.TransportOutcome.COMPLETED, 99999, 0, None)
        result = h.join_timing(replace(original, transport=(accept, *original.transport)), READ)
        self.assertEqual(accept, result.transport[0])

    def test_every_finite_wait_outcome_preserves_original_grant_and_status_polls(self):
        for outcome in h.SmartModeOutcome:
            if outcome == h.SmartModeOutcome.STARTED: continue
            with self.subTest(outcome=outcome):
                observed = replace(window(), smartModeWaits=wait(outcome))
                self.assertEqual(outcome, h.join_timing(observed, READ).smartModeWaits[1].wait.outcome)
                self.assertEqual(observed, h.load_window(json.loads(json.dumps(asdict(observed)))))
        for records in (wait()[:1], wait()[::-1], (*wait(), wait()[0]),
            (wait()[0], replace(wait()[1], wait=replace(wait()[1].wait, limitMillis=30000)))):
            self.rejected(replace(window(), smartModeWaits=records), h.TimingFailure.SMART_MODE_WAIT_INCOMPLETE)

    def test_parser_rejects_unknown_fields_vocabulary_duplicate_keys_and_invalid_quantities(self):
        valid = asdict(transport()[1])
        variants = [{**valid, 'extra': 0}, {**valid, 'stage': 'GUESS'}, {**valid, 'outcome': 'GUESS'},
                    {**valid, 'failure': 'GUESS'}, {**valid, 'elapsedNanos': -1},
                    {**valid, 'elapsedNanos': True}, {**valid, 'bytes': 2**63},
                    {**valid, 'connectionId': 'unqualified'}]
        for value in variants:
            with self.subTest(value=value):
                observed = h.decode_window(b'kast_transport ' + json.dumps(value).encode() + b'\n')
                self.assertEqual((h.TimingFailure.INVALID_RECORD,), observed.failures)
                self.assertEqual((), observed.transport)
        for data in (b'kast_transport {bad}', b'kast_transport_read {"connectionId":"x","connectionId":"y","readId":"z"}'):
            self.assertEqual((h.TimingFailure.INVALID_RECORD,), h.decode_window(data + b'\n').failures)

    def test_native_endpoint_failure_is_preserved_in_typed_transport(self):
        original = window()
        rejected = replace(original.transport[5], outcome=h.TransportOutcome.REJECTED,
                           failure=h.EndpointFailure.DEADLINE_EXCEEDED)
        observed = replace(original, transport=(*original.transport[:5], rejected, *original.transport[6:]))
        result = h.join_timing(h.decode_window(lines(observed)), READ)
        self.assertEqual(h.EndpointFailure.DEADLINE_EXCEEDED, result.transport[5].failure)

    def test_exact_immediate_rotation_retains_join_and_terminal_release(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / 'idea.log'
            path.write_bytes(b'earlier unrelated bytes\n')
            before = path.stat()
            original = window()
            prefix = replace(original, transport=original.transport[:-2])
            with path.open('ab') as stream: stream.write(lines(prefix))
            path.rename(path.with_name('idea.1.log'))
            path.write_bytes(lines(h.ObservationWindow(transport=original.transport[-2:])))
            captured = h.read_observations(path, before)
            self.assertEqual(original, captured)
            self.assertIsInstance(h.join_timing(captured, READ), h.JoinedTiming)

    def test_original_byte_bound_truncation_symlink_and_missing_log_fail_closed(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / 'idea.log'
            path.write_bytes(b'old\n')
            before = path.stat()
            with path.open('ab') as stream: stream.write(b'x' * (h.OBSERVATION_POLICY['maxAppendedBytes'] + 1))
            self.assertEqual((h.TimingFailure.BYTE_LIMIT,), h.read_observations(path, before).failures)
            path.write_bytes(b'')
            self.assertEqual((h.TimingFailure.LOG_TRUNCATED,), h.read_observations(path, before).failures)
            path.unlink()
            self.assertEqual((h.TimingFailure.LOG_UNAVAILABLE,), h.read_observations(path, before).failures)
            target = path.with_name('target.log'); target.write_bytes(b'')
            path.symlink_to(target)
            self.assertEqual((h.TimingFailure.LOG_UNAVAILABLE,), h.read_observations(path, before).failures)

    def test_partial_last_record_is_reobserved_within_original_window(self):
        complete = lines(window())
        partial = h.decode_window(complete[:-3])
        self.assertEqual((h.TimingFailure.LOG_RECORD_INCOMPLETE,), partial.failures)
        observations = iter((partial, h.decode_window(complete)))
        captured = h.collect_observations(None, None, observe=lambda *_: next(observations),
            clock=iter((7.0, 7.01)).__next__, pause=lambda duration: self.assertEqual(0.01, duration))
        self.assertIsInstance(h.join_timing(captured, READ), h.JoinedTiming)
        with self.assertRaises(StopIteration): next(observations)

    def test_response_bound_read_waits_for_own_release_despite_other_completed_connection(self):
        basis = {'host': 'host', 'epoch': 4}
        doc = {'readId': READ, 'correlation': {'type': 'bound', **basis}}
        other_doc = {'readId': RETRY, 'correlation': {'type': 'bound', 'host': 'other', 'epoch': 4}}
        original = replace(window(), diagnostics=(doc, other_doc), joins=(h.ReadJoin(OTHER, RETRY), *window().joins),
                           transport=(*transport(OTHER), *transport()))
        partial = replace(original, transport=(*transport(OTHER), *transport()[:-2]))
        response = {'live': basis}
        self.assertTrue(h.any_read_released(partial))
        self.assertFalse(replay.response_read_released(response, partial))
        observed = iter((partial, original))
        captured = h.collect_observations(None, None, observe=lambda *_: next(observed),
            clock=iter((3.0, 3.01)).__next__, pause=lambda duration: self.assertEqual(0.01, duration),
            ready=lambda value: replay.response_read_released(response, value))
        self.assertEqual(original, captured)
        self.assertEqual(CONNECTION, h.join_timing(captured, READ).connectionId)
        self.assertFalse(replay.response_read_released(response,
            replace(original, diagnostics=(doc, {**doc, 'readId': RETRY}))))

    def test_persisted_window_rejects_shape_changes_and_retains_exact_records(self):
        original = window()
        saved = json.loads(json.dumps(asdict(original)))
        self.assertEqual(original, h.load_window(saved))
        for changed in ({**saved, 'invented': 0}, {**saved, 'transport': {}},
                        {**saved, 'failures': ['UNKNOWN']}, {**saved, 'diagnostics': [42]}):
            with self.subTest(changed=changed), self.assertRaises((ValueError, TypeError)):
                h.load_window(changed)

    def test_collector_waits_for_release_after_receipt_without_reissuing_query(self):
        original = window()
        partial = replace(original, transport=original.transport[:-2])
        path, before = object(), object()
        expected = [('clock', (), 12.0), ('observe', (path, before), partial), ('clock', (), 12.01),
                    ('pause', (0.01,), None), ('observe', (path, before), original)]
        def step(kind, *args):
            self.assertTrue(expected, 'Unexpected or excess observation callback')
            wanted, arguments, result = expected.pop(0)
            self.assertEqual((wanted, arguments), (kind, args))
            return result
        with patch.object(replay, 'capture', side_effect=AssertionError('Capture reinvoked query')) as query:
            captured = h.collect_observations(path, before, observe=lambda *args: step('observe', *args),
                clock=lambda: step('clock'), pause=lambda *args: step('pause', *args))
            query.assert_not_called()
        self.assertEqual([], expected, 'Unconsumed observation callback')
        self.assertEqual(original, captured)

    def test_collector_original_250ms_deadline_retains_unfinished_release(self):
        original = replace(window(), transport=transport()[:-1])
        with patch.object(replay, 'capture', side_effect=AssertionError('Capture reinvoked query')) as query:
            captured = h.collect_observations(None, None, observe=lambda *_: original,
                clock=iter((5.0, 5.25)).__next__, pause=lambda _: self.fail('No pause after deadline'))
            query.assert_not_called()
        self.assertEqual(original, captured)
        self.rejected(captured, h.TimingFailure.TRANSPORT_INCOMPLETE)

    def test_timing_vocabulary_matches_authoritative_kotlin_boundaries(self):
        root = Path(__file__).resolve().parents[2]
        hosted = root / 'runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted'
        for name, vocabulary, filename in (
            ('HostedTransportStage', h.TransportStage, 'HostedTransportObservation.kt'),
            ('HostedEndpointOutcome', h.TransportOutcome, 'HostedEndpointService.kt'),
            ('HostedEndpointFailure', h.EndpointFailure, 'HostedEndpointProtocol.kt'),
            ('HostedSmartModeWaitOutcome', h.SmartModeOutcome, 'HostedSmartModeWait.kt')):
            source = (hosted / filename).read_text()
            body = re.search(r'enum class ' + name + r'\s*\{([^}]+)\}', source).group(1)
            self.assertEqual(set(re.findall(r'\b([A-Z][A-Z_]*)\s*,', body)), {item.value for item in vocabulary})


if __name__ == '__main__': unittest.main()
