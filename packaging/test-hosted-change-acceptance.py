#!/usr/bin/env python3
"""Offline checks for input admission and redacted reports; these do not qualify native behavior."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

from hosted_change_process import NativeProcesses
from native_fixture_probe import NativeFixtureProbeError
from hosted_change_acceptance import (AcceptanceRejected, admit_event, admit_harness, admitted_live,
    CASE_NAMES, bounded_native_report, event_observation, native_workflow_qualified, pending_readiness, receipt_scope_observation, remaining_matrix_gates, tree_identity)


class HostedChangeAcceptanceTest(unittest.TestCase):
    def test_setup_observation_retains_success_and_bounded_protocol_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'Source.kt'
            source.write_text('class Source')
            processes = NativeProcesses(SimpleNamespace(root=root),
                SimpleNamespace(workspace=root, source=source), root, 60)
            processes.generation = 1
            response = {'outcome': 'SETUP_READY', 'readiness': {'scope': 'OBSERVED_SETUP_ONLY'},
                        'evidence': {'savedSha256': hashlib.sha256(source.read_bytes()).hexdigest()}}
            with patch('hosted_change_process.NativeFixtureProbe') as probe:
                probe.return_value.request.return_value = response
                processes.observe_setup()
                self.assertEqual('OBSERVED', processes.readiness_observations[-1]['outcome'])
                self.assertEqual(response['readiness'], processes.readiness_observations[-1]['readiness'])
                for failure, expected in [('MALFORMED_RESPONSE', 'MALFORMED_RESPONSE'),
                                          ('private arbitrary payload', 'TRANSPORT_REJECTED')]:
                    probe.return_value.request.side_effect = NativeFixtureProbeError(failure)
                    with self.assertRaises(AcceptanceRejected):
                        processes.observe_setup()
                    self.assertEqual(expected, processes.readiness_observations[-1]['failure'])
                    self.assertNotIn('private arbitrary payload', json.dumps(processes.readiness_observations))

    def test_harness_contains_only_controller_classes_and_exact_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'controller.jar'
            commit = 'a' * 40
            def jar(extra=None):
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n'
                        f'Kast-Acceptance-Source-Commit: {commit}\r\n')
                    archive.writestr('io/github/amichne/kast/appserver/acceptance/hostedchange/NativeHostedChangeMain.class', b'controller')
                    if extra:
                        archive.writestr(extra, b'production')
            jar()
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), admit_harness(path, commit))
            with self.assertRaises(AcceptanceRejected):
                admit_harness(path, 'b' * 40)
            jar('io/github/amichne/kast/appserver/core/Broker.class')
            with self.assertRaises(AcceptanceRejected):
                admit_harness(path, commit)

    def test_tree_identity_tracks_content_and_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / 'input'
            file.write_bytes(b'first')
            before = tree_identity(root)
            file.write_bytes(b'other')
            self.assertNotEqual(before['sha256'], tree_identity(root)['sha256'])
            (root / 'link').symlink_to(file)
            with self.assertRaises(AcceptanceRejected):
                tree_identity(root)

    def test_only_named_transient_readiness_can_retry(self):
        self.assertTrue(pending_readiness({'failure': 'PROJECT_ADMISSION_REJECTED', 'detail': 'DUMB_MODE'}))
        self.assertTrue(pending_readiness({'failure': 'INDEXING'}))
        for failure in ('DIRTY_DOCUMENTS', 'SEMANTIC_BACKEND_UNAVAILABLE', 'UNKNOWN'):
            self.assertFalse(pending_readiness({'failure': failure}))

    def test_live_proof_requires_exact_root_owner_epoch_and_saved_committed_view(self):
        workspace = Path('/private/fixture/workspace')
        valid = {'root': str(workspace), 'host': '11111111-1111-1111-1111-111111111111',
                 'epoch': 2, 'contentView': 'SAVED_PSI_COMMITTED', 'version': 1}
        self.assertEqual(valid, admitted_live(valid, workspace))
        for field, value in (('root', '/other'), ('host', 'invalid'), ('epoch', True), ('contentView', 'DIRTY')):
            with self.assertRaises(AcceptanceRejected):
                admitted_live(dict(valid, **{field: value}), workspace)

    def test_events_cannot_export_references_or_arbitrary_cases(self):
        event = {'event': 'case', 'case': 'complete-workflow', 'outcome': 'passed'}
        self.assertEqual(event, admit_event(event))
        for invalid in (dict(event, reference='secret'), dict(event, case='unknown'), {'event': 'log', 'payload': 'secret'}):
            with self.assertRaises(AcceptanceRejected):
                admit_event(invalid)

    def test_broker_restart_control_persists_only_identity_digest(self):
        token = 'plan:' + 'a' * 64
        event = {'event': 'control', 'action': 'replace-broker', 'planIdentity': token, 'sourceSha256': 'b' * 64}
        observed = event_observation(admit_event(event))
        self.assertNotIn(token, json.dumps(observed))
        self.assertNotIn('planIdentity', observed)
        self.assertEqual(hashlib.sha256(token.encode()).hexdigest(), observed['planIdentitySha256'])

    def test_model_amendment_control_requires_only_exact_source_image(self):
        event = {'event': 'control', 'action': 'amend-generated-provenance', 'sourceSha256': 'a' * 64}
        self.assertEqual(event, admit_event(event))
        for invalid in (dict(event, path='/arbitrary'), dict(event, sourceSha256='private-source')):
            with self.assertRaises(AcceptanceRejected):
                admit_event(invalid)

    def test_probe_control_accepts_only_named_commands_and_image_digests(self):
        event = {'event': 'control', 'action': 'probe', 'command': 'OBSERVE', 'preimageSha256': 'a' * 64}
        self.assertEqual(event, admit_event(event))
        for invalid in (dict(event, source='private'), dict(event, command='WRITE_ANY_PATH'),
                        dict(event, preimageSha256='private-source')):
            with self.assertRaises(AcceptanceRejected):
                admit_event(invalid)

    def test_report_allows_digest_evidence_but_rejects_source_payload(self):
        workspace = Path('/private/fixture/workspace')
        report = {'schemaVersion': 1, 'metadata': {'upstream': 'scripted-native-protocol-controller',
            'provider': 'staged-production-broker-cli-plugin', 'stockCodexUi': 'unqualified',
            'workspaceRoot': str(workspace), 'status': 'observed', 'failure': None},
            'cases': {'complete-workflow': {'outcome': 'passed', 'evidence': {'referenceSha256': 'a' * 64}},
                      'unsupported-intents': {'outcome': 'rejected', 'evidence': {
                          'expectedRejection': 'BROKER_INVALID_ARGUMENTS', 'observedRejection': 'OTHER_REJECTION'}}}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            path.write_text(json.dumps(report))
            self.assertEqual(report, bounded_native_report(path, workspace))
            report['cases']['complete-workflow']['evidence']['source'] = 'private source'
            path.write_text(json.dumps(report))
            with self.assertRaises(AcceptanceRejected):
                bounded_native_report(path, workspace)

    def test_native_bind_failure_is_bounded_and_only_current_startup_is_observed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / 'ide/log/idea.log'
            log.parent.mkdir(parents=True)
            previous = b'kast_hosted stage=BIND outcome=REJECTED failure=OWNERSHIP_CONFLICT\n'
            log.write_bytes(previous)
            processes = NativeProcesses(SimpleNamespace(root=root), None, None, 30)
            processes.ide_log_start = len(previous)
            processes.reject_failed_bind()
            self.assertEqual([], processes.readiness_observations)
            with log.open('ab') as output:
                output.write(previous)
            with self.assertRaises(AcceptanceRejected):
                processes.reject_failed_bind()
            self.assertEqual([{'generation': 0, 'stage': 'BIND', 'outcome': 'REJECTED',
                               'failure': 'OWNERSHIP_CONFLICT'}], processes.readiness_observations)

    def test_receipt_projection_preserves_root_epoch_and_complete_obligations_without_payload(self):
        repo = Path(__file__).resolve().parent.parent
        plan = json.loads((repo / 'change/verify/src/test/resources/live-add-declaration-plan-v1.json').read_text())
        workspace = Path(plan['workspaceRoot'])
        body = {'plan': plan, 'after': {'root': str(workspace), 'owner': plan['owner'],
            'epoch': plan['epoch'] + 1, 'contentView': plan['contentView'], 'version': plan['referenceVersion']},
            'semanticObligations': plan['semanticObligations'], 'liveObligations': plan['liveObligations'],
            'source': 'private-source-payload', 'approval': 'private-approval'}
        observed = receipt_scope_observation(body, 'a' * 64, workspace)
        self.assertEqual(plan['epoch'] + 1, observed['afterEpoch'])
        self.assertEqual(11, len(observed['dischargedObligations']))
        self.assertNotIn('private-source-payload', json.dumps(observed))
        self.assertNotIn('private-approval', json.dumps(observed))
        for field, value in (('root', '/foreign'), ('owner', '11111111-1111-1111-1111-111111111111'),
                             ('epoch', plan['epoch'])):
            invalid = copy.deepcopy(body)
            invalid['after'][field] = value
            with self.assertRaises(AcceptanceRejected):
                receipt_scope_observation(invalid, 'a' * 64, workspace)
        invalid = copy.deepcopy(body)
        invalid['semanticObligations'] = invalid['semanticObligations'][:-1]
        with self.assertRaises(AcceptanceRejected):
            receipt_scope_observation(invalid, 'a' * 64, workspace)
        invalid = copy.deepcopy(body)
        invalid['plan']['verificationScope']['diagnostics'] = [['/foreign/Source.kt']]
        with self.assertRaises(AcceptanceRejected):
            receipt_scope_observation(invalid, 'a' * 64, workspace)

    def test_matrix_clears_only_scenarios_with_complete_named_native_evidence(self):
        native = {'cases': {'psi-structure': {'outcome': 'passed'}}}
        names = {item['scenario'] for item in remaining_matrix_gates(native)}
        self.assertIn('psi-structure-and-undo', names)
        native['cases']['production-undo'] = {'outcome': 'passed'}
        names = {item['scenario'] for item in remaining_matrix_gates(native)}
        self.assertNotIn('psi-structure-and-undo', names)
        self.assertIn('complete-workflow-and-exact-approval', names)
        self.assertIn('foreign-root-generated-ambiguous-and-model-movement', names)
        names = {item['scenario'] for item in remaining_matrix_gates(native, {'outcome': 'passed', 'sourceUnchanged': False})}
        self.assertIn('complete-hosted-read-regression', names)

    def test_cleanup_qualification_requires_clean_source_and_complete_read_and_mutation_evidence(self):
        evidence = {'status': 'observed-with-unqualified-matrix', 'source': {'clean': True},
                    'native': {'metadata': {'status': 'observed'},
                               'cases': {name: {'outcome': 'passed'} for name in CASE_NAMES}},
                    'readRegression': {'outcome': 'passed', 'sourceUnchanged': True},
                    'events': [{'event': 'stage', 'stage': name, 'outcome': 'completed'} for name in (
                        'post-save-interrupted', 'plugin-owner-retired', 'fixture-broker-process-replaced')]}
        self.assertTrue(native_workflow_qualified(evidence))
        for path, value in ((('readRegression', 'outcome'), 'rejected'),
                            (('source', 'clean'), False), (('native', 'metadata', 'status'), 'rejected')):
            invalid = copy.deepcopy(evidence)
            destination = invalid
            for key in path[:-1]:
                destination = destination[key]
            destination[path[-1]] = value
            self.assertFalse(native_workflow_qualified(invalid))

    def test_missing_matrix_evidence_never_becomes_passed(self):
        self.assertTrue(remaining_matrix_gates())
        self.assertTrue(all(item['outcome'] == 'unqualified' for item in remaining_matrix_gates()))


if __name__ == '__main__':
    unittest.main()
