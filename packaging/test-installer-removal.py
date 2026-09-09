#!/usr/bin/env python3
"""Offline legacy entry-point proof: no live services and no effective removal command."""
import hashlib
import json
import subprocess
import unittest
from pathlib import Path
from acceptance_environment import AcceptanceEnvironment, admitted_tools

INSTALLER = Path(__file__).resolve().parent.parent / 'install.sh'

class InstallerRemovalTest(unittest.TestCase):
    def setUp(self):
        self.fixture = AcceptanceEnvironment(admitted_tools())
        self.fixture.__enter__()
        self.env = self.fixture.environment.copy()
        for key in tuple(self.env):
            if key.startswith('XDG_') or key in {'KAST_RUNTIME_STORE', 'KAST_RUNTIME_DIRECTORY', 'KAST_CACHE_ROOT'}:
                self.env.pop(key)
        self.root = Path(self.env['HOME'])
        self.outer = self.root / '.local/share/kast'
        self.product = self.outer / 'versions/1.0.0-fixture'
        for directory in ('bin', 'lib', 'share/kast'):
            (self.product / directory).mkdir(parents=True)
        lifecycle = self.product / 'share/kast/installation-lifecycle.py'
        lifecycle.write_text('import json, sys\nprint(json.dumps(sys.argv[1:]))\n')
        launcher = self.product / 'bin/kast-complete'
        launcher.write_text('#!/bin/bash\nexit 0\n')
        launcher.chmod(0o755)
        payload = []
        for directory in ('bin', 'lib', 'share'):
            for file in sorted((self.product / directory).rglob('*')):
                if file.is_file():
                    payload.append({'path': file.relative_to(self.product).as_posix(),
                                    'sha256': 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest(),
                                    'mode': file.stat().st_mode & 0o777})
        (self.product / 'installation.json').write_text(json.dumps({'schemaVersion': 1,
            'installationRoot': str(self.product), 'payloadFiles': payload}))
        (self.outer / 'current').symlink_to('versions/' + self.product.name)
        fake = self.root / 'no-effects'
        fake.write_text('#!/bin/bash\nexit 0\n')
        fake.chmod(0o755)
        self.env['KAST_INSTALL_PROCESS_TABLE_COMMAND'] = str(fake)
        self.env['KAST_INSTALL_PROCESS_KILL_COMMAND'] = str(fake)
        # Even the old implementation's broad rm has no effects during RED.
        rm = Path(self.env['PATH']) / 'rm'
        rm.unlink()
        rm.symlink_to(fake)
        self.addCleanup(self.cleanup)

    def cleanup(self):
        result = self._outcome.result
        failed = any(test is self for test, _ in result.failures + result.errors)
        if not failed:
            self.fixture.mark_passed()
        self.fixture.__exit__(None, None, None)

    def uninstall(self):
        return subprocess.run(['bash', str(INSTALLER), 'uninstall', '--installation-only'],
                              env=self.env, capture_output=True, text=True)

    def test_legacy_uninstall_dispatches_only_exact_manifest_owned_release(self):
        result = self.uninstall()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), json.dumps(['--installation', str(self.product), 'remove']),
                         'legacy entry point must invoke bounded lifecycle removal, never broad rm')

    def test_manifestless_legacy_installation_requires_explicit_migration(self):
        (self.product / 'installation.json').unlink()
        result = self.uninstall()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('migration', result.stderr)
        self.assertTrue(self.product.exists())

if __name__ == '__main__':
    unittest.main()
