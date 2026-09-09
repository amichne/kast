"""Offline ownership-transition proofs; never selects a real installation."""
import sys
import json
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

SCRIPT = Path(__file__).with_name('installation-lifecycle.py')

class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory(prefix='kast-lifecycle-')
        self.outer = Path(self.fixture.name).resolve()
        self.root = self.outer / 'versions' / ('0.36.1-' + 'c' * 64)
        for directory in ('bin', 'config', 'state/broker/profile', 'state/run'):
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        self.workspace = self.outer / 'workspace'
        self.workspace.mkdir()
        self.log = self.outer / 'commands'
        self.kast = self.root / 'bin/kast-complete'
        self.kast.write_text('#!/bin/sh\n'
            'if [ "$1 $2" = "app-server disable" ] && [ "${KAST_ENABLE_APP_SERVER-}" != 1 ]; then exit 9; fi\n'
            'printf "%s\\n" "$*" >> "' + str(self.log) + '"\n'
            'echo \'{"status":"complete"}\'\n')
        self.kast.chmod(0o700)
        self.epoch = {'schemaVersion': 1, 'installation': 'sha256:' + 'd' * 64, 'epoch': str(uuid.uuid4())}
        (self.root / 'state/epoch.json').write_text(json.dumps(self.epoch))
        (self.root / 'config/environment').write_text('KAST_INDEXER_MAX_HEAP=8g\n')
        (self.root / 'config/workspaces.json').write_text(json.dumps({'schemaVersion': 2, 'revision': 1, 'roots': [str(self.workspace)]}))
        self.manifest = {'schemaVersion': 1, 'semanticVersion': '0.36.1', 'installationRoot': str(self.root),
            'payloadIdentity': 'sha256:' + 'c' * 64, 'controlSha256': 'sha256:' + 'a' * 64,
            'runtimeSha256': 'sha256:' + 'b' * 64, 'stateRoot': str(self.root / 'state'),
            'configuration': str(self.root / 'config/environment'), 'workspaceRegistry': str(self.root / 'config/workspaces.json'),
            'externalAnchors': []}
        self.manifest['payloadFiles'] = self.inventory()
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
    def inventory(self):
        return [{'path': 'bin/kast-complete', 'sha256': 'sha256:' + hashlib.sha256(self.kast.read_bytes()).hexdigest(), 'mode': 448}]
    def tearDown(self):
        self.fixture.cleanup()
    def invoke(self, *arguments):
        result = subprocess.run([sys.executable, str(SCRIPT), '--installation', str(self.root), *arguments], capture_output=True, text=True, timeout=10, env={"PATH": "/usr/bin:/bin", "HOME": str(self.outer), "TMPDIR": str(self.outer)})
        self.assertTrue(result.stdout, result.stderr)
        self.last_stderr = result.stderr
        return result.returncode, json.loads(result.stdout)
    def test_remove_deletes_only_matching_version_anchors_and_retains_outer_lock(self):
        current = self.outer / 'current'
        target = 'versions/' + self.root.name
        current.symlink_to(target)
        self.manifest['externalAnchors'] = [{'kind': 'current', 'path': str(current), 'expectedLinkTarget': target}]
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('remove')
        self.assertEqual(0, code, result)
        self.assertFalse(self.root.exists())
        self.assertFalse(current.is_symlink())
        self.assertTrue((self.outer / 'activation.lock').is_file())
        self.assertTrue(self.workspace.exists())
    def test_remove_preserves_selector_that_now_names_another_version(self):
        current = self.outer / 'current'
        current.symlink_to('versions/newer')
        self.manifest['externalAnchors'] = [{'kind': 'current', 'path': str(current), 'expectedLinkTarget': 'versions/' + self.root.name}]
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('remove')
        self.assertEqual(0, code, result)
        self.assertEqual('versions/newer', current.readlink().as_posix())
    def test_dry_run_is_passive(self):
        code, result = self.invoke('reset', '--dry-run')
        self.assertEqual(0, code, result)
        self.assertFalse(self.log.exists())
        self.assertEqual(self.epoch, json.loads((self.root / 'state/epoch.json').read_text()))
    def test_reset_retires_exact_commands_preserves_config_and_changes_epoch(self):
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        self.assertEqual(['app-server disable', 'stop'], self.log.read_text().splitlines())
        new_epoch = json.loads((self.root / 'state/epoch.json').read_text())
        self.assertNotEqual(self.epoch['epoch'], new_epoch['epoch'])
        self.assertEqual(self.epoch['installation'], new_epoch['installation'])
        self.assertEqual('KAST_INDEXER_MAX_HEAP=8g\n', (self.root / 'config/environment').read_text())
        self.assertTrue(self.kast.exists())
    def test_reset_removes_only_the_receipted_private_upstream_directory(self):
        run = self.root / 'state/run'
        upstream = Path('/tmp').resolve() / ('kast-codex-' + hashlib.sha256(str(run).encode()).hexdigest()[:32])
        upstream.mkdir(mode=0o700)
        def identity(path):
            observed = path.lstat()
            return {'device': observed.st_dev, 'inode': observed.st_ino, 'owner': observed.st_uid}
        receipt = run / 'upstream-directory.json'
        receipt.write_text(json.dumps({'schemaVersion': 1, 'directory': str(upstream),
            'physicalDirectory': str(run), 'directoryIdentity': identity(upstream),
            'physicalDirectoryIdentity': identity(run)}))
        receipt.chmod(0o600)
        self.manifest['externalAnchors'] = [{'kind': 'upstream-directory', 'path': str(upstream),
            'expectedPhysicalDirectory': str(run), 'identityReceipt': str(receipt),
            'ownership': 'declared-not-observed'}]
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        try:
            code, result = self.invoke('reset')
            self.assertEqual(0, code, result)
            self.assertFalse(upstream.exists())
        finally:
            if upstream.exists():
                upstream.rmdir()
    def test_uncertain_journal_blocks_before_retirement(self):
        journal = self.root / 'state/broker/profile/invocations.json'
        journal.write_text(json.dumps({'schemaVersion': 1, 'records': {'a' * 64: {'fingerprint': 'b' * 64, 'phase': 'UNCERTAIN'}}}))
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('UNRESOLVED_INVOCATION', result['failure'])
        self.assertFalse(self.log.exists())
        self.assertTrue(journal.exists())

    def test_recover_read_only_discards_only_a_structurally_matched_cancelled_query(self):
        thread = 'thread-1'
        turn = 'turn-1'
        call = 'call-1'
        identity = json.dumps([thread, turn, call], separators=(',', ':'))
        key = hashlib.sha256(identity.encode()).hexdigest()
        journal = self.root / 'state/broker/profile/invocations.json'
        journal.write_text(json.dumps({'schemaVersion': 1, 'records': {
            key: {'fingerprint': 'b' * 64, 'phase': 'UNCERTAIN'},
            'c' * 64: {'fingerprint': 'd' * 64, 'phase': 'COMPLETED'}}}))
        service_log = journal.with_name('service.log')
        service_log.write_text('\n'.join((
            json.dumps({'component': 'kast-broker', 'event': 'tool-call-started',
                'threadId': thread, 'turnId': turn, 'callId': call, 'namespace': 'kast', 'tool': 'query'}),
            json.dumps({'component': 'kast-broker', 'event': 'tool-call-finished',
                'threadId': thread, 'turnId': turn, 'callId': call, 'namespace': 'kast', 'tool': 'query',
                'completion': 'cancelled'}))) + '\n')

        code, result = self.invoke('recover-read-only')

        self.assertEqual(0, code, result)
        self.assertEqual(1, result['recoveredInvocationCount'])
        self.assertEqual({'c' * 64: {'fingerprint': 'd' * 64, 'phase': 'COMPLETED'}},
                         json.loads(journal.read_text())['records'])
        evidence = Path(result['evidence'])
        self.assertTrue(evidence.is_file())
        self.assertEqual(0o600, evidence.stat().st_mode & 0o777)
        self.assertEqual(key, json.loads(evidence.read_text())['recoveries'][0]['invocationKey'])
        self.assertEqual(['app-server disable', 'stop'], self.log.read_text().splitlines())
        code, result = self.invoke('inspect')
        self.assertEqual(0, code, result)

    def test_recover_read_only_rejects_an_unmatched_or_mutating_invocation(self):
        thread = 'thread-1'
        turn = 'turn-1'
        call = 'call-1'
        identity = json.dumps([thread, turn, call], separators=(',', ':'))
        key = hashlib.sha256(identity.encode()).hexdigest()
        journal = self.root / 'state/broker/profile/invocations.json'
        original = {'schemaVersion': 1, 'records': {
            key: {'fingerprint': 'b' * 64, 'phase': 'UNCERTAIN'}}}
        journal.write_text(json.dumps(original))
        journal.with_name('service.log').write_text(json.dumps({
            'component': 'kast-broker', 'event': 'tool-call-started', 'threadId': thread,
            'turnId': turn, 'callId': call, 'namespace': 'kast', 'tool': 'mutate'}) + '\n')

        code, result = self.invoke('recover-read-only')

        self.assertNotEqual(0, code)
        self.assertEqual('RECOVERY_REJECTED', result['failure'])
        self.assertEqual(original, json.loads(journal.read_text()))
        self.assertFalse(self.log.exists())
    def test_failed_retirement_preserves_state(self):
        self.kast.write_text('#!/bin/sh\nexit 7\n')
        self.manifest['payloadFiles'] = self.inventory()
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('RETIREMENT_UNPROVEN', result['failure'])
        self.assertEqual(self.epoch, json.loads((self.root / 'state/epoch.json').read_text()))
        self.assertTrue((self.root / '.lifecycle-transition.json').is_file())
        events = [json.loads(line) for line in self.last_stderr.splitlines()]
        self.assertEqual(['started', 'exit-rejected'], [event['outcome'] for event in events])
        self.assertTrue(all(event['stage'] == 'coordinator-retirement' for event in events))
    def test_successful_retirement_reports_each_bounded_stage(self):
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        events = [json.loads(line) for line in self.last_stderr.splitlines()]
        self.assertEqual(['coordinator-retirement'] * 2 + ['workspace-retirement'] * 2,
                         [event['stage'] for event in events])
        self.assertEqual(['started', 'completed'] * 2, [event['outcome'] for event in events])
        self.assertTrue(all(set(event) <= {'component', 'stage', 'outcome', 'workspaceIdentity'} for event in events))
    def test_unresolved_worker_receipt_blocks_reset_even_after_stop_reports_success(self):
        workers = self.root / 'state/workers'
        workers.mkdir()
        receipt = workers / 'pending.json'
        receipt.write_text(json.dumps({'phase': 'QUARANTINED_STARTUP'}))
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('RETIREMENT_UNPROVEN', result['failure'])
        self.assertEqual(['app-server disable', 'stop'], self.log.read_text().splitlines())
        self.assertTrue(receipt.exists())
        self.assertEqual(self.epoch, json.loads((self.root / 'state/epoch.json').read_text()))
        self.assertTrue((self.root / '.lifecycle-transition.json').is_file())
    def test_manifest_cannot_widen_deletion_root(self):
        self.manifest['stateRoot'] = str(self.outer)
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('MANIFEST_REJECTED', result['failure'])
        self.assertTrue(self.kast.exists())
    def test_external_cache_is_rejected_without_retiring_or_deleting_it(self):
        (self.root / 'config/environment').write_text('KAST_CACHE_ROOT=' + str(self.workspace) + '\n')
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('EXTERNAL_STATE_UNPROVEN', result['failure'])
        self.assertFalse(self.log.exists())
    def test_missing_registry_does_not_manufacture_zero_workers(self):
        (self.root / 'config/workspaces.json').unlink()
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('REGISTRY_REJECTED', result['failure'])
        self.assertFalse(self.log.exists())
    def test_hidden_journal_directory_is_rejected(self):
        (self.root / 'state/hidden').symlink_to(self.workspace, target_is_directory=True)
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('STATE_REJECTED', result['failure'])
    def test_changed_payload_is_rejected_before_execution(self):
        self.kast.write_text('#!/bin/sh\nexit 0\n')
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('MANIFEST_REJECTED', result['failure'])
        self.assertFalse(self.log.exists())
    def test_state_symlink_cannot_redirect_reset(self):
        import shutil
        shutil.rmtree(self.root / 'state')
        (self.root / 'state').symlink_to(self.workspace, target_is_directory=True)
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('STATE_REJECTED', result['failure'])
        self.assertTrue(self.workspace.is_dir())

if __name__ == '__main__':
    unittest.main()
