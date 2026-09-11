#!/usr/bin/env python3
"""Focused proof for fixture isolation, source preservation and bounded read receipts."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from hosted_read_fixture import ReadFixtureRejected, prepare_read_fixture
from hosted_read_regression import _ReadReplay, _read_observation, _reproduction
from hosted_read_transport import (HostedReadTransport, ReadTransportRejected, _admit_cli_invocations,
    _admit_output_violation_evidence, _provider_result)
from hosted_generated_fixture import (GENERATED_FILE, GENERATED_SOURCE, MOVEMENT_FILE, MOVEMENT_SOURCE,
    prepare_generated_fixture, finalize_generated_fixture, amend_generated_provenance)


REPO = Path(__file__).resolve().parent.parent


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


if __name__ == '__main__':
    unittest.main()
