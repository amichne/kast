"""Recovery uses only private fixtures; no real installations or processes."""
from pathlib import Path
import json
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
        code, report = self.run_recovery('prepare', '--bin-directory', self.bin)
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

if __name__ == '__main__':
    unittest.main()
