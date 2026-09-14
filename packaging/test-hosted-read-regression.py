#!/usr/bin/env python3
"""Focused proof for fixture isolation, source preservation and bounded read receipts."""
from dataclasses import asdict, dataclass, field
import hashlib
import importlib.util
import json
import sys
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from collections import Counter
from unittest.mock import Mock
from unittest.mock import patch
from contextlib import nullcontext
from threading import Barrier
from hosted_concurrent_read import run_concurrent_read_regression
from hosted_peer_probe import PeerAttempt, PeerCase, PeerFailure, PeerOutcome, TerminalReply
from hosted_transport_observation import TransportSummary, TransportWitnessFailure, TransportWitnessRejected

from hosted_read_fixture import ReadFixtureRejected, prepare_read_fixture
from hosted_enum_read_regression import run_enum_read_regression
from hosted_read_regression import _ReadReplay, _read_observation, _reproduction
from hosted_read_transport import (HostedReadTransport, ReadTransportRejected, _admit_cli_invocations,
    _admit_output_violation_evidence, _provider_result)
from hosted_generated_fixture import (GENERATED_FILE, GENERATED_SOURCE, MOVEMENT_FILE, MOVEMENT_SOURCE,
    prepare_generated_fixture, finalize_generated_fixture, amend_generated_provenance)


REPO = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class SourceCheckpointFixture:
    type: str = 'upstream'
    token: str = 'source-read-continuation-v1|' + 'a' * 64


@dataclass(frozen=True)
class SourceProgressFixture:
    type: str = 'resumable'
    checkpoint: SourceCheckpointFixture = field(default_factory=SourceCheckpointFixture)
    next_action: str = 'resume'


@dataclass(frozen=True)
class SourceCursorFixture:
    type: str = 'available'
    continuation: str = 'source-read-continuation-v1|' + 'a' * 64


@dataclass(frozen=True)
class SourceQualificationFixture:
    knownMinimumEntityCount: int = 2
    limitations: list[str] = field(default_factory=lambda: ['entity-limit-reached', 'work-limit-reached'])
    continuation: SourceCursorFixture = field(default_factory=SourceCursorFixture)
    progress: SourceProgressFixture = field(default_factory=SourceProgressFixture)


@dataclass(frozen=True)
class SourceObservationFixture:
    status: str = 'qualified'
    qualification: SourceQualificationFixture = field(default_factory=SourceQualificationFixture)


@dataclass(frozen=True)
class EnumItemStub:
    name: str
    symbol_ref: str
    symbol_id: str
    signature: str


@dataclass(frozen=True)
class EnumWorkStub:
    effective: int = 32


@dataclass(frozen=True)
class EnumBudgetStub:
    max_work_units: EnumWorkStub = field(default_factory=EnumWorkStub)


@dataclass(frozen=True)
class EnumResponseStub:
    items: tuple[EnumItemStub, ...]
    status: str = 'complete'
    failures: tuple[str, ...] = ()
    live: str = 'private-live-authority'
    execution_budget: EnumBudgetStub = field(default_factory=EnumBudgetStub)


def enum_response(names):
    # Transport stubs retain only fields read by the oracle; they are not wire fixtures.
    return json.loads(json.dumps(asdict(EnumResponseStub(tuple(
        EnumItemStub(name, f'private-reference-{index}', f'private-identity-{index}',
                     f'private-signature-{index}') for index, name in enumerate(names))))))


class HostedReadRegressionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.root.chmod(0o700)
        self.workspace = self.root / 'workspace'
        self.source = self.workspace / 'src/main/kotlin/Fixture.kt'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('package fixture\nclass NativeChangeTarget\n')
        (self.workspace / 'build.gradle.kts').write_text('plugins { kotlin("jvm") version "2.3.10" }\n')
        (self.workspace / 'settings.gradle.kts').write_text('rootProject.name = "hosted-change-acceptance"\n')
        self.receipt = self.root / 'hosted-inputs.json'
        self.receipt.write_text(json.dumps({'fixtureRoot': str(self.root), 'workspaceRoot': str(self.workspace),
            'status': 'prepared', 'nativeAcceptance': 'not-run',
            'sourcePreimageSha256': hashlib.sha256(self.source.read_bytes()).hexdigest()}))

    def test_setup_preserves_change_target_and_detects_later_source_edits(self):
        original = self.source.read_bytes()
        fixture = prepare_read_fixture(self.workspace, REPO)
        self.assertEqual(original, self.source.read_bytes())
        self.assertTrue(fixture.unchanged())
        self.assertGreater(fixture.evidence()['fileCount'], 3)
        self.assertIn('2.3.10', (self.workspace / 'build.gradle.kts').read_text())
        self.source.write_text('class DivergentUserContent\n')
        self.assertFalse(fixture.unchanged())

    def test_wrong_prepared_root_and_changed_source_reject_before_copying(self):
        inputs = json.loads(self.receipt.read_text())
        inputs['workspaceRoot'] = str(self.root / 'foreign')
        self.receipt.write_text(json.dumps(inputs))
        with self.assertRaises(ReadFixtureRejected):
            prepare_read_fixture(self.workspace, REPO)
        self.assertFalse((self.workspace / 'core').exists())
        inputs['workspaceRoot'] = str(self.workspace)
        self.receipt.write_text(json.dumps(inputs))
        self.source.write_text('changed\n')
        with self.assertRaises(ReadFixtureRejected):
            prepare_read_fixture(self.workspace, REPO)
        self.assertFalse((self.workspace / 'logging').exists())

    def test_existing_module_or_symlink_cannot_be_overwritten(self):
        (self.workspace / 'core').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ReadFixtureRejected):
            prepare_read_fixture(self.workspace, REPO)
        self.assertTrue((self.workspace / 'core').is_symlink())

    def test_enum_fixture_is_in_inventory_and_existing_source_cannot_be_overwritten(self):
        fixture = prepare_read_fixture(self.workspace, REPO)
        enum = self.workspace / 'src/main/kotlin/ReadEnumMode.kt'
        self.assertIn('src/main/kotlin/ReadEnumMode.kt', dict(fixture.files))
        self.assertIn('ACTIVE { override fun act() = target() }', enum.read_text())
        enum.write_text('private source edit')
        self.assertFalse(fixture.unchanged())

    def test_existing_enum_source_rejects_before_installing_fixture(self):
        enum = self.workspace / 'src/main/kotlin/ReadEnumMode.kt'
        enum.write_text('private source')
        with self.assertRaises(ReadFixtureRejected):
            prepare_read_fixture(self.workspace, REPO)
        self.assertEqual('private source', enum.read_text())
        self.assertFalse((self.workspace / 'core').exists())

    def enum_replay(self, responses):
        transport = Mock()
        transport.invoke.side_effect = responses
        replay = _ReadReplay(None, None, 'private-live-authority', transport, 'provider', [])
        run_enum_read_regression(replay)
        return replay

    def enum_responses(self):
        return [enum_response(names) for names in
                ((), ('Mode',), ('Mode', 'Nested', 'Ordinary'), ('act', 'act'), ('act', 'act'))]

    def test_enum_oracle_requires_exclusion_and_preserves_member_references_and_signatures(self):
        replay = self.enum_replay(self.enum_responses())
        self.assertEqual(5, len(replay.rows))
        self.assertTrue(all(row['passed'] for row in replay.rows))
        requests = [call.args[2] for call in replay.transport.invoke.call_args_list]
        self.assertTrue(all(request['execution_budget']['max_work_units'] == 32 for request in requests))
        self.assertEqual(['private-reference-0', 'private-reference-1'],
                         list(requests[-1]['source']['symbol_refs']))
        self.assertEqual(('name', 'signature'), requests[-1]['return_fields'])
        self.assertNotIn('private-', json.dumps(replay.rows))

    def test_enum_oracle_rejects_entries_in_any_class_search(self):
        for index in range(3):
            with self.subTest(case=index):
                responses = self.enum_responses()
                responses[index]['items'].extend(enum_response(('ModeEntry00',))['items'])
                replay = self.enum_replay(responses)
                self.assertFalse(replay.rows[index]['passed'])

    def test_enum_oracle_rejects_missing_identity_or_changed_member_projection(self):
        for field in ('symbol_id', 'symbol_ref', 'signature'):
            with self.subTest(field=field):
                responses = self.enum_responses()
                responses[-1]['items'][0].pop(field)
                replay = self.enum_replay(responses)
                self.assertFalse(replay.rows[-1]['passed'])
        responses = self.enum_responses()
        responses[3] = enum_response(('act',))
        replay = self.enum_replay(responses)
        self.assertFalse(replay.rows[-1]['passed'])
        self.assertEqual(4, replay.transport.invoke.call_count)

    def test_authored_oracle_rejects_missing_results_without_learning_from_output(self):
        fixture = prepare_read_fixture(self.workspace, REPO)
        oracle = _reproduction(REPO)
        case = next(case for case in oracle.cases(fixture.oracle) if case.name == 'exact-logger')
        assessment = oracle.assess(case, {'status': 'complete', 'items': []}, self.workspace, fixture.oracle)
        self.assertEqual(oracle.Finding.NOT_REPRODUCED.value, assessment['finding'])
        self.assertFalse(assessment['assertions']['exactIdentities'])

    def test_receipt_rejects_payload_fields_and_unbounded_counts(self):
        replay = _ReadReplay(None, None, None, None, 'cli', [])
        with self.assertRaises(ValueError):
            replay.record('case', 'source_read', {'exactText': 'private source payload'})
        with self.assertRaises(ValueError):
            replay.record('case', 'query_symbols', {'exact': True}, 1001)
        replay.record('case', 'query_symbols', {'exact': True}, 1)
        self.assertEqual({'exact': True}, replay.rows[0]['assertions'])
        self.assertTrue(replay.rows[0]['passed'])

    def test_installed_replay_coordinates_all_156_first_attempts_and_detects_mixed_replies(self):
        cases = [SimpleNamespace(name=f'exact-{index}', identity=index) for index in range(10)]
        oracle = SimpleNamespace(cases=lambda _: cases, ToolSurface=SimpleNamespace(PUBLIC='public'),
            Finding=SimpleNamespace(REPRODUCED=SimpleNamespace(value='reproduced')),
            invocation=lambda case, _: ('query_symbols', [], {'identity': case.identity}),
            assess=lambda case, response, *_: {'finding': 'reproduced',
                'assertions': {'identity': response['identity'] == case.identity}})
        fixture = SimpleNamespace(oracle={}, workspace=self.workspace)
        live = {'host': 'fixture-host'}
        for mix_replies, bad_peer, good_observation in ((False, None, True), (True, None, True),
                (False, PeerCase.SATURATED, True), (False, PeerCase.HEALTH, True), (False, None, False),
                (False, None, None)):
            rendezvous = Barrier(12)
            def invoke(_surface, _tool, arguments):
                rendezvous.wait(timeout=5)
                return {'status': 'complete', 'live': live,
                        'identity': -1 if mix_replies else arguments['identity']}
            transport = SimpleNamespace(invoke=invoke, validate=lambda *_: 'sha256:' + 'a' * 64)
            observed = Mock()
            observed.completed.return_value = set(range(159))
            if good_observation is None:
                observed.await_drained.side_effect = TransportWitnessRejected(TransportWitnessFailure.DEADLINE)
            observed.summary.return_value = TransportSummary(good_observation, 177, 176, 159, 1 if good_observation else 2, True, [])
            def probe(_endpoint, case):
                terminal = TerminalReply.UNOBSERVED if case is PeerCase.DISCONNECTED else TerminalReply.SINGLE
                return PeerAttempt(case, PeerOutcome.REJECTED if case is bad_peer else PeerOutcome.PASSED,
                                   terminal, 5, 0, 1, PeerFailure.SHAPE if case is bad_peer else None)
            with patch('hosted_concurrent_read.admit_peer_endpoint', return_value=object()), \
                 patch('hosted_concurrent_read.admission_capacity', return_value=(16, 'sha256:' + 'b' * 64)), \
                 patch('hosted_concurrent_read.NativeTransportWindow', return_value=nullcontext(observed)), \
                 patch('hosted_concurrent_read.connected_peer', side_effect=lambda _: nullcontext(Mock())), \
                 patch('hosted_concurrent_read.probe_peer', side_effect=probe):
                report = run_concurrent_read_regression(SimpleNamespace(root=self.root), fixture, oracle, transport, live)
            if good_observation is None:
                self.assertEqual(156, report['firstAttempts'])
                self.assertEqual(156, report['passedCount'])
                self.assertEqual(2, report['peerFirstAttempts'])
                self.assertEqual('rejected', report['outcome'])
                self.assertEqual('witness_deadline_exceeded', report['qualificationFailure'])
                self.assertFalse(report['admissionDrained'])
                self.assertIsNone(report['transportObservation'])
                continue
            self.assertEqual([159, 176, 177], [call.args[0] for call in observed.await_drained.call_args_list])
            self.assertEqual(4, report['peerFirstAttempts'])
            self.assertEqual(3 if bad_peer else 4, report['peerPassedCount'])
            self.assertEqual([case.value for case in PeerCase], [row['case'] for row in report['peerAttempts']])
            self.assertTrue(report['admissionDrained'])
            self.assertEqual(156, report['firstAttempts'])
            self.assertEqual(0, report['serialRetries'])
            self.assertEqual(0 if mix_replies else 156, report['passedCount'])
            self.assertEqual('rejected' if mix_replies or bad_peer or not good_observation else 'passed', report['outcome'])
            self.assertEqual(156, len({(row['client'], row['round']) for row in report['attempts']}))
            self.assertNotIn('identity', json.dumps(report))

    def test_source_qualification_observation_retains_finite_causes_without_cursor_payload(self):
        response = asdict(SourceObservationFixture())
        observed = _read_observation(response)['sourceQualification']
        self.assertEqual('observed', observed['outcome'])
        self.assertEqual(('entity-limit-reached', 'work-limit-reached'), observed['limitations'])
        self.assertNotIn(SourceCursorFixture().continuation, json.dumps(observed))
        self.assertEqual('resumable', observed['progress'])
        response['qualification']['limitations'] = ['unknown']
        self.assertEqual({'outcome': 'unrecognized'}, _read_observation(response)['sourceQualification'])

    def test_source_observation_rejects_unknown_or_conflicting_progress(self):
        for progress in ('unknown', 'terminal_incomplete'):
            response = asdict(SourceObservationFixture())
            response['qualification']['progress']['type'] = progress
            self.assertEqual({'outcome': 'unrecognized'}, _read_observation(response)['sourceQualification'])

    @staticmethod
    def schema():
        return {'serverProjection': {'schemaVersion': 10, 'namespace': 'kast',
            'cliInvocations': {'schemaVersion': 3, 'operations': [{
                'toolName': 'source_read', 'operationId': 'source.read',
                'invocation': {'type': 'CLI', 'command': ['source', 'read']}}]},
            'hostedBootstrap': {'schemaVersion': 1, 'tools': [{
                'name': 'source_read', 'operationId': 'source.read', 'effect': 'intellij_read',
                'approvalPolicy': 'none'}]}}}

    def test_cli_uses_staged_operation_command_without_guessing_tool_facade(self):
        transport = HostedReadTransport(None, SimpleNamespace(workspace=self.workspace, environment={}),
                                        self.root / 'product', None, None)
        transport.cli_commands = _admit_cli_invocations(self.schema())
        result = SimpleNamespace(stdout=b'{"status":"complete"}')
        with patch('hosted_read_transport.subprocess.run', return_value=result) as run:
            self.assertEqual('complete', transport.invoke('cli', 'source_read', {})['status'])
            self.assertEqual([str(self.root / 'product/bin/kast'), 'source', 'read'], run.call_args.args[0])
        with self.assertRaises(ReadTransportRejected):
            transport.invoke('cli', 'unpublished_tool', {})

    def test_cli_projection_rejects_duplicate_mismatched_and_option_commands(self):
        for change in ('duplicate', 'mismatch', 'option'):
            schema = self.schema()
            operations = schema['serverProjection']['cliInvocations']['operations']
            if change == 'duplicate':
                operations.append(operations[0])
            elif change == 'mismatch':
                operations[0]['operationId'] = 'change.apply'
            else:
                operations[0]['invocation']['command'] = ['--version']
            with self.assertRaises(ReadTransportRejected):
                _admit_cli_invocations(schema)
        schema = self.schema()
        schema['serverProjection']['hostedBootstrap']['tools'][0]['effect'] = 'source_write'
        self.assertEqual({}, _admit_cli_invocations(schema))

    def test_live_movement_remains_visible_without_paths_or_tokens(self):
        live = {'root': str(self.workspace), 'host': 'fixture-owner', 'epoch': 1,
                'contentView': 'SAVED_PSI_COMMITTED', 'version': 1}
        first = _read_observation({'status': 'complete', 'live': live, 'items': [{'ref': 'private-token'}]})
        second = _read_observation({'status': 'complete', 'live': {**live, 'epoch': 2}})
        self.assertEqual((1, 2), (first['epoch'], second['epoch']))
        self.assertNotEqual(first['authoritySha256'], second['authoritySha256'])
        self.assertNotIn(str(self.workspace), json.dumps(first))
        self.assertNotIn('private-token', json.dumps(first))

    def traversal_page(self, status, continuation=None, edges=True):
        live = {'root': str(self.workspace), 'host': 'fixture-owner', 'epoch': 1,
                'contentView': 'SAVED_PSI_COMMITTED', 'version': 1}
        graph = {'snapshot': {'live': live}, 'nodes': [
            {'id': 'h', 'qualifiedIdentity': 'helper'}, {'id': 'c', 'qualifiedIdentity': 'caller'}],
            'edges': [{'source': 'c', 'target': 'h', 'coverage': 'exact-compiler-confirmed',
                       'provenance': 'k2-authored-source'}] if edges else [],
            'proofs': [{'identity': 'proof'}] if edges else []}
        if not edges:
            graph['nodes'] = []
        result = {'status': status, 'live': live, 'graph': graph}
        if continuation is not None:
            result['qualification'] = {'type': 'resumable', 'limitations': ['time-limit-reached'],
                'relationLimitations': [], 'continuation': continuation}
        return result

    def test_traversal_consumes_returned_checkpoint_with_unchanged_request_and_requires_completion(self):
        first = self.traversal_page('qualified', 'private-checkpoint')
        transport = Mock()
        transport.invoke.side_effect = [first, self.traversal_page('complete', edges=False)]
        replay = _ReadReplay(None, None, first['live'], transport, 'cli', [])
        replay.traversal('original-reference', Counter({'caller': 1}), 'helper')
        self.assertEqual(2, transport.invoke.call_count)
        initial, resumed = [call.args[2] for call in transport.invoke.call_args_list]
        self.assertEqual({'type': 'start'}, initial['position'])
        self.assertEqual({'type': 'breadth_first'}, initial['strategy'])
        self.assertEqual({'exactSelector': 'original-reference', 'relation': 'callers',
                          'maximumDepth': 4, 'maximumResults': 100, 'position': {'type': 'start'},
                          'strategy': {'type': 'breadth_first'}}, json.loads(json.dumps(initial)))
        self.assertEqual({'type': 'resume', 'continuation': 'private-checkpoint'}, resumed['position'])
        self.assertEqual({k: v for k, v in initial.items() if k != 'position'},
                         {k: v for k, v in resumed.items() if k != 'position'})
        self.assertTrue(all(row['passed'] for row in replay.rows))
        self.assertEqual('complete', replay.rows[-1]['observation']['status'])
        self.assertNotIn('private-checkpoint', json.dumps(replay.rows))

    def test_repeated_or_foreign_traversal_checkpoint_never_becomes_complete(self):
        first = self.traversal_page('qualified', 'private-checkpoint')
        for second in (self.traversal_page('qualified', 'private-checkpoint', edges=False),
                       self.traversal_page('qualified', 'different-checkpoint', edges=False),
                       {**self.traversal_page('complete', edges=False), 'live': {**first['live'], 'epoch': 2}}):
            transport = Mock()
            transport.invoke.side_effect = [first, second]
            replay = _ReadReplay(None, None, first['live'], transport, 'provider', [])
            replay.traversal('original-reference', Counter({'caller': 1}), 'helper')
            self.assertEqual(2, transport.invoke.call_count)
            self.assertFalse(replay.rows[-1]['passed'])

    def test_traversal_qualification_keeps_finite_limits_without_checkpoint_payload(self):
        response = {'status': 'qualified', 'qualification': {
            'type': 'resumable', 'limitations': ['work-limit-reached'],
            'relationLimitations': [], 'continuation': 'private-checkpoint'}}
        observed = _read_observation(response)
        self.assertEqual({'type': 'resumable', 'limitations': ['work-limit-reached'],
                          'relationLimitations': [], 'continuationPresent': True},
                         observed['traversalQualification'])
        self.assertNotIn('private-checkpoint', json.dumps(observed))
        response['qualification']['limitations'] = ['private unknown reason']
        rejected = _read_observation(response)
        self.assertEqual({'outcome': 'unadmitted'}, rejected['traversalQualification'])
        self.assertNotIn('private unknown reason', json.dumps(rejected))

    def test_provider_rejection_preserves_closed_failure_without_payload(self):
        with self.assertRaises(ReadTransportRejected) as rejected:
            _provider_result({'kind': 'rejected', 'failure': 'OUTPUT_CONTRACT_REJECTED',
                              'privatePayload': 'must not be retained'})
        self.assertEqual({'reason': 'READ_PROVIDER_REJECTED', 'providerFailure': 'OUTPUT_CONTRACT_REJECTED'},
                         rejected.exception.evidence())
        with self.assertRaises(ReadTransportRejected) as unknown:
            _provider_result({'kind': 'rejected', 'failure': 'arbitrary private contents'})
        self.assertEqual({'reason': 'READ_PROVIDER_PROTOCOL_REJECTED'}, unknown.exception.evidence())
        self.assertEqual({'failure': 'INVALID_ARGUMENTS'},
                         _provider_result({'kind': 'rejected', 'failure': 'INVALID_ARGUMENTS'}))
        self.assertEqual({'status': 'complete'}, _provider_result({
            'kind': 'completed', 'envelope': {'document': {'status': 'complete'}}}))

    def test_invalid_argument_observation_retains_finite_failure_without_diagnostic_payload(self):
        response = _provider_result({'kind': 'rejected', 'failure': 'INVALID_ARGUMENTS'})
        response['diagnostic'] = 'synthetic-private-text'
        observation = _read_observation(response)
        self.assertEqual('INVALID_ARGUMENTS', observation['providerFailure'])
        self.assertNotIn('synthetic-private-text', json.dumps(observation))
        unknown = _read_observation({'failure': 'synthetic-private-text'})
        self.assertNotIn('synthetic-private-text', json.dumps(unknown))

    def test_output_violation_evidence_preserves_only_closed_field_keyword_pairs(self):
        evidence = {'observations': [{'keyword': 'REQUIRED', 'field': 'PROOFS'}]}
        with self.assertRaises(ReadTransportRejected) as failure:
            _provider_result({'kind': 'rejected', 'failure': 'OUTPUT_CONTRACT_REJECTED',
                              'outputViolationEvidence': evidence})
        self.assertEqual(evidence, failure.exception.evidence()['outputViolationEvidence'])
        for malformed in (
                {'observations': []},
                {'observations': [{'keyword': 'private validator text', 'field': 'PROOFS'}]},
                {'observations': [{'keyword': 'REQUIRED', 'field': 'private field value'}]},
                {'observations': [{'keyword': 'REQUIRED', 'field': 'PROOFS', 'message': 'payload'}]},
                {'observations': evidence['observations'] * 2},
                {'observations': evidence['observations'] * 4097},
                {'observations': evidence['observations'], 'payload': 'private'}):
            with self.assertRaises(ReadTransportRejected):
                _admit_output_violation_evidence(malformed)
        with self.assertRaises(ReadTransportRejected):
            _provider_result({'kind': 'rejected', 'failure': 'TIMED_OUT',
                              'outputViolationEvidence': evidence})

    def generated_fixture(self):
        original = prepare_read_fixture(self.workspace, REPO)
        pending = prepare_generated_fixture(original)
        output = self.workspace / GENERATED_FILE
        output.parent.mkdir(parents=True)
        # Unit-test boundary observation only; the native runner must execute
        # pending.gradle_tasks through the actual fixture Gradle installation.
        output.write_text(GENERATED_SOURCE)
        return finalize_generated_fixture(pending)

    def test_generated_setup_requires_real_output_before_inventory_admission(self):
        original = prepare_read_fixture(self.workspace, REPO)
        pending = prepare_generated_fixture(original)
        self.assertEqual(('generateNativeAcceptanceSource',), pending.gradle_tasks)
        self.assertFalse((self.workspace / GENERATED_FILE).exists())
        with self.assertRaises(OSError):
            finalize_generated_fixture(pending)
        output = self.workspace / GENERATED_FILE
        output.parent.mkdir(parents=True)
        output.write_text('class UnexpectedGeneratorOutput\n')
        with self.assertRaises(ReadFixtureRejected):
            finalize_generated_fixture(pending)
        output.write_text(GENERATED_SOURCE)
        fixture = finalize_generated_fixture(pending)
        self.assertTrue(fixture.read_fixture.unchanged())
        self.assertIn(GENERATED_FILE, dict(fixture.read_fixture.files))
        self.assertIn(MOVEMENT_FILE, dict(fixture.read_fixture.files))
        self.assertEqual('pending-native-gradle-and-jps-model-observation', fixture.evidence()['provenanceAuthority'])

    def test_provenance_amendment_preserves_all_source_bytes_and_rejects_replay(self):
        fixture = self.generated_fixture()
        original_target = self.source.read_bytes()
        receipt = amend_generated_provenance(fixture)
        self.assertEqual('gradle-provenance-amended', receipt['outcome'])
        self.assertNotEqual(receipt['beforeSha256'], receipt['afterSha256'])
        self.assertEqual(original_target, self.source.read_bytes())
        self.assertEqual(MOVEMENT_SOURCE, (self.workspace / MOVEMENT_FILE).read_text())
        self.assertEqual(GENERATED_SOURCE, (self.workspace / GENERATED_FILE).read_text())
        self.assertEqual(fixture.amended_build, (self.workspace / 'build.gradle.kts').read_text())
        with self.assertRaises(ReadFixtureRejected):
            amend_generated_provenance(fixture)

    def test_provenance_amendment_cannot_overwrite_changed_build_or_target(self):
        fixture = self.generated_fixture()
        build = self.workspace / 'build.gradle.kts'
        build.write_text('unexpected build\n')
        with self.assertRaises(ReadFixtureRejected):
            amend_generated_provenance(fixture)
        self.assertEqual('unexpected build\n', build.read_text())
        build.write_text(fixture.initial_build)
        target = self.workspace / MOVEMENT_FILE
        target.write_text('class UnexpectedContent\n')
        with self.assertRaises(ReadFixtureRejected):
            amend_generated_provenance(fixture)
        self.assertEqual(fixture.initial_build, build.read_text())
        self.assertEqual('class UnexpectedContent\n', target.read_text())


def load_tests(loader, tests, _pattern):
    for name in ('test-native-provider-qualification.py', 'test-hosted-peer-probe.py',
                 'test-hosted-authority-read.py', 'test-hosted-budget-read-regression.py',
                 'test-hosted-resume-budget-regression.py', 'test-hosted-raw-symbol-regression.py',
                 'test-released-acceptance-product.py', 'test-released-tool-inventory.py'):
        spec = importlib.util.spec_from_file_location(name[:-3].replace('-', '_'), Path(__file__).with_name(name))
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        tests.addTests(loader.loadTestsFromModule(module))
    return tests


if __name__ == '__main__':
    unittest.main()
