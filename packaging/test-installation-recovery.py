"""Recovery uses only private fixtures; no real installations or processes."""
from pathlib import Path
import json
import hashlib
import importlib.util
from unittest.mock import patch
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('installation-recovery.py')

class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='kast-recovery-')
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name).resolve()
        self.outer = self.home / 'kast'
        self.root = self.outer / 'versions' / ('0.39.3-' + 'a' * 64)
        self.root.mkdir(parents=True)
        self.bin = self.home / 'bin'
        self.bin.mkdir()
        self.environment = dict(os.environ, HOME=str(self.home))

    def run_recovery(self, operation, *args):
        result = subprocess.run([sys.executable, str(SCRIPT), operation, '--installation', str(self.root), *map(str, args)],
                                env=self.environment, capture_output=True, text=True, timeout=10)
        self.assertTrue(result.stdout, result.stderr)
        return result.returncode, json.loads(result.stdout)

    def prepare(self):
        plugins = self.home / 'selected-plugins'
        plugins.mkdir(exist_ok=True)
        code, report = self.run_recovery('prepare', '--bin-directory', self.bin, '--plugin-root', plugins)
        self.assertEqual(0, code, report)
        (self.outer / 'current').symlink_to('versions/' + self.root.name)
        (self.bin / 'kast').symlink_to(self.outer / 'current/bin/kast-complete')
        return report

    def test_damaged_payload_detaches_and_preserves_unresolved_evidence(self):
        self.prepare()
        state = self.root / 'state'
        state.mkdir()
        evidence = state / 'mutation-journal.json'
        evidence.write_text('unresolved source change')
        code, report = self.run_recovery('detach')
        self.assertNotEqual(0, code)
        self.assertEqual('DetachedWithUnresolvedState', report['status'])
        self.assertFalse((self.outer / 'current').is_symlink())
        self.assertFalse((self.bin / 'kast').is_symlink())
        self.assertEqual('unresolved source change', evidence.read_text())
        self.assertTrue((self.root / '.recovery-detached').is_file())
        code2, report2 = self.run_recovery('detach')
        self.assertEqual((code, report), (code2, report2))

    def test_foreign_replacement_is_preserved_and_reported(self):
        self.prepare()
        (self.bin / 'kast').unlink()
        (self.bin / 'kast').write_text('foreign command')
        code, report = self.run_recovery('detach')
        self.assertNotEqual(0, code)
        self.assertIn('ANCHOR_OWNERSHIP_UNPROVEN', report['unresolved'])
        self.assertEqual('foreign command', (self.bin / 'kast').read_text())

    def test_dry_run_writes_no_fence_and_detaches_nothing(self):
        self.prepare()
        code, report = self.run_recovery('detach', '--dry-run')
        self.assertEqual(0, code, report)
        self.assertTrue((self.outer / 'current').is_symlink())
        self.assertFalse((self.root / '.recovery-detached').exists())

    def test_standalone_bundle_survives_missing_payload(self):
        report = self.prepare()
        bundle = Path(report['recoveryExecutable'])
        self.assertTrue(bundle.is_file())
        result = subprocess.run([sys.executable, str(bundle), 'detach', '--installation', str(self.root)],
                                env=self.environment, capture_output=True, text=True, timeout=10)
        self.assertEqual('DetachedWithUnresolvedState', json.loads(result.stdout)['status'])
        self.assertFalse((self.outer / 'current').is_symlink())

    def test_verified_retirement_preserves_state_in_quarantine(self):
        self.prepare()
        (self.root / 'bin').mkdir()
        executable = self.root / 'bin/kast-complete'
        executable.write_text('#!/bin/sh\nexit 0\n')
        executable.chmod(0o700)
        (self.root / 'state').mkdir()
        manifest = {
            'schemaVersion': 2, 'semanticVersion': '0.39.3', 'installationRoot': str(self.root),
            'payloadIdentity': 'sha256:' + 'a' * 64, 'stateRoot': str(self.root / 'state'),
            'configuration': str(self.root / 'config/environment'),
            'workspaceRegistry': str(self.root / 'config/workspaces.json'), 'externalAnchors': [],
            'payloadFiles': [{'path': 'bin/kast-complete',
                             'sha256': 'sha256:' + hashlib.sha256(executable.read_bytes()).hexdigest(), 'mode': 448}],
        }
        (self.root / 'installation.json').write_text(json.dumps(manifest))
        code, report = self.run_recovery('detach')
        self.assertEqual(0, code, report)
        self.assertEqual('CleanBaselineRestored', report['status'])
        self.assertFalse((self.root / 'state').exists())
        self.assertEqual(1, len(list(self.root.glob('.recovered-state-*'))))
        self.assertEqual((code, report), self.run_recovery('detach'))

    def test_plugin_interruption_retains_backup_and_resumes(self):
        self.prepare()
        spec = importlib.util.spec_from_file_location('recovery_under_test', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        staged = self.home / 'staged'
        (staged / 'kast-ide-hosted').mkdir(parents=True)
        (staged / 'kast-ide-hosted/new').write_text('new plugin')
        plugins = self.home / 'plugins'
        (plugins / 'kast-ide-hosted').mkdir(parents=True)
        (plugins / 'kast-ide-hosted/old').write_text('working baseline')
        original = Path.rename
        interrupted = False
        def fail_after_backup(path, target):
            nonlocal interrupted
            result = original(path, target)
            if '.baseline-' in str(target) and not interrupted:
                interrupted = True
                raise OSError('simulated interruption after durable intent')
            return result
        with patch.object(Path, 'rename', fail_after_backup):
            with self.assertRaises(OSError):
                module.activate_plugin(self.root, staged, plugins)
        self.assertEqual(1, len(list(plugins.glob('.kast-ide-hosted.baseline-*'))))
        module.activate_plugin(self.root, staged, plugins)
        self.assertEqual('new plugin', (plugins / 'kast-ide-hosted/new').read_text())
        self.assertEqual('working baseline', next(plugins.glob('.kast-ide-hosted.baseline-*/old')).read_text())
        code, report = self.run_recovery('detach')
        self.assertNotEqual(0, code)
        self.assertIn('IDE_RESTART_REQUIRED', report['unresolved'])
        self.assertFalse((plugins / 'kast-ide-hosted').exists())
        self.assertEqual('working baseline', next(plugins.glob('.kast-ide-hosted.baseline-*/old')).read_text())

    def test_unknown_receipt_fields_fail_closed(self):
        self.prepare()
        path = self.outer / 'recovery' / self.root.name / 'receipt.json'
        document = json.loads(path.read_text())
        document['unexpected'] = 'unsupported'
        path.write_text(json.dumps(document))
        code, report = self.run_recovery('detach')
        self.assertNotEqual(0, code)
        self.assertEqual('RecoveryBlocked', report['status'])
        self.assertTrue((self.outer / 'current').is_symlink())

    def test_interruption_after_unlink_resumes_from_durable_receipt(self):
        self.prepare()
        spec = importlib.util.spec_from_file_location('recovery_unlink_test', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        original = Path.unlink
        def interrupted(path, *args, **kwargs):
            result = original(path, *args, **kwargs)
            if path == self.bin / 'kast':
                raise OSError('interrupted after unlink')
            return result
        with patch.object(Path, 'unlink', interrupted):
            with self.assertRaises(OSError):
                module.detach(self.root, False)
        self.assertTrue((self.root / '.lifecycle-transition.json').is_file())
        code, report = self.run_recovery('detach')
        self.assertEqual('DetachedWithUnresolvedState', report['status'])
        self.assertFalse((self.outer / 'current').is_symlink())
        self.assertFalse((self.bin / 'kast').is_symlink())

    def test_lock_contention_prevents_fence_or_link_changes(self):
        import fcntl
        self.prepare()
        with (self.outer / 'activation.lock').open('r+') as lock:
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            code, report = self.run_recovery('detach')
        self.assertEqual('RecoveryBlocked', report['status'])
        self.assertTrue((self.outer / 'current').is_symlink())
        self.assertFalse((self.root / '.recovery-detached').exists())

if __name__ == '__main__':
    unittest.main()
