#!/usr/bin/env python3
"""Offline removal entry-point proof: no live services and no effective removal command."""
import hashlib
import json
import subprocess
import tarfile
import unittest
from pathlib import Path
from installer_fixture import InstallerFixture, admitted_tools

INSTALLER = Path(__file__).resolve().parent.parent / 'install.sh'

class InstallerRemovalTest(unittest.TestCase):
    def setUp(self):
        self.fixture = InstallerFixture(admitted_tools())
        self.fixture.__enter__()
        self.env = self.fixture.environment.copy()
        for key in tuple(self.env):
            if key.startswith('XDG_') or key in {'KAST_RUNTIME_STORE', 'KAST_RUNTIME_DIRECTORY', 'KAST_CACHE_ROOT'}:
                self.env.pop(key)
        self.root = Path(self.env['HOME'])
        self.outer = self.root / '.local/share/kast'
        self.product = self.outer / 'installation'
        for directory in ('bin', 'lib', 'share/kast'):
            (self.product / directory).mkdir(parents=True)
        lifecycle = self.product / 'share/kast/installation-lifecycle.py'
        lifecycle.write_text(
            'import json, pathlib, sys\n'
            'root = pathlib.Path(sys.argv[sys.argv.index("--installation") + 1])\n'
            'if not (root / "installation.json").is_file():\n'
            '    raise SystemExit("installation manifest rejected")\n'
            'print(json.dumps(sys.argv[1:]))\n'
        )
        launcher = self.product / 'bin/kast-complete'
        launcher.write_text(
            '#!/bin/bash\n'
            'if [[ ${1:-} == installation ]]; then\n'
            '  shift\n'
            f'  exec python3 "{lifecycle}" --installation "{self.product}" "$@"\n'
            'fi\n'
            'exit 90\n'
        )
        launcher.chmod(0o755)
        payload = []
        for directory in ('bin', 'lib', 'share'):
            for file in sorted((self.product / directory).rglob('*')):
                if file.is_file():
                    payload.append({'path': file.relative_to(self.product).as_posix(),
                                    'sha256': 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest(),
                                    'mode': file.stat().st_mode & 0o777})
        (self.product / 'installation.json').write_text(json.dumps({'schemaVersion': 3,
            'installationRoot': str(self.product), 'payloadFiles': payload}))
        command = self.root / '.local/bin/kast'
        command.parent.mkdir(parents=True)
        command.write_text('#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
        command.chmod(0o755)
        (self.outer / 'management.json').write_text(json.dumps({
            'schemaVersion': 2, 'installationRoot': str(self.outer), 'executable': str(command),
            'executableSha256': hashlib.sha256(command.read_bytes()).hexdigest(),
            'channel': 'STABLE', 'registrations': [],
        }))
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

    def uninstall(self, *options):
        return subprocess.run(['bash', str(INSTALLER), 'uninstall', *options],
                              env=self.env, capture_output=True, text=True)

    def test_uninstall_dispatches_only_to_selected_installation_lifecycle(self):
        result = self.uninstall('--managed-registrations', '--verbose')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), json.dumps(['--installation', str(self.product), 'remove', '--control-only', '--json']),
                         'entry point must invoke bounded lifecycle removal, never broad rm')

    def test_manifestless_installation_is_rejected(self):
        (self.product / 'installation.json').unlink()
        result = self.uninstall('--managed-registrations')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('manifest', result.stderr)
        self.assertTrue(self.product.exists())

    def test_explicit_root_binds_only_the_sole_user_lifecycle(self):
        alternate = self.root / 'alternate-control'
        alternate.mkdir()
        marker = alternate / 'protected'
        marker.write_text('preserve')
        before = (marker.stat().st_dev, marker.stat().st_ino, marker.read_bytes())
        result = self.uninstall('--install-root', str(self.outer), '--managed-registrations', '--verbose')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(result.stdout.strip(), json.dumps([
            '--installation', str(self.product), 'remove', '--control-only', '--json']))
        self.assertEqual(before, (marker.stat().st_dev, marker.stat().st_ino, marker.read_bytes()))
        rejected = self.uninstall('--install-root', str(alternate), '--verbose')
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn('sole per-user installation', rejected.stderr)
        self.assertEqual('', rejected.stdout)
        self.assertEqual(before, (marker.stat().st_dev, marker.stat().st_ino, marker.read_bytes()))

    def test_public_uninstall_uses_receipted_management_owner(self):
        result = self.uninstall('--verbose')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['uninstall'], json.loads(result.stdout))


    def test_public_retry_does_not_require_retired_private_payload(self):
        self.product.rename(self.outer / 'retired-fixture')
        result = self.uninstall('--verbose')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['uninstall'], json.loads(result.stdout))

    def test_public_uninstall_rejects_foreign_executable_before_dispatch(self):
        command = self.root / '.local/bin/kast'
        command.write_text('#!/bin/bash\nexit 88\n')
        result = self.uninstall('--verbose')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertTrue(self.product.exists())

    def test_public_retry_without_binary_uses_checksum_admitted_release_management(self):
        (self.root / '.local/bin/kast').unlink()
        self.product.rename(self.outer / 'retired-fixture')
        self.recovery_asset()
        result = self.uninstall('--verbose')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['uninstall'], json.loads(result.stdout))

    def terminal_journal(self):
        receipt = json.loads((self.outer / 'management.json').read_text())
        attributes = self.outer.stat()
        control = self.product.stat()
        token = hashlib.sha256(str(self.outer).encode()).hexdigest()
        journal = self.outer.parent / ('.kast-uninstall-' + token + '.json')
        document = {'type': 'EXTERNALS_CLEANED', 'installationRoot': str(self.outer),
            'executable': receipt['executable'], 'executableSha256': receipt['executableSha256'],
            'channel': 'STABLE', 'managedRootIdentity': {'device': attributes.st_dev, 'inode': attributes.st_ino, 'owner': attributes.st_uid},
            'controlIdentity': {'device': control.st_dev, 'inode': control.st_ino, 'owner': control.st_uid},
            'homeConfiguration': {'type': 'ABSENT_CONFIGURATION'}, 'recoveryProof': {'type': 'ABSENT_RECOVERY'}}
        journal.write_text(json.dumps(document))
        journal.chmod(0o600)
        return journal

    def test_public_retry_after_managed_root_disappearance_uses_terminal_exterior_proof(self):
        journal = self.terminal_journal()
        self.outer.rename(self.outer.with_name('protected-retired-fixture'))
        (self.root / '.local/bin/kast').unlink()
        self.recovery_asset()
        result = self.uninstall('--verbose')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['uninstall'], json.loads(result.stdout))
        self.assertTrue(journal.exists())  # Scripted management has no cleanup effects.

    def test_replacement_managed_root_does_not_inherit_terminal_exterior_proof(self):
        journal = self.terminal_journal()
        self.outer.rename(self.outer.with_name('protected-retired-fixture'))
        self.outer.mkdir()
        sentinel = self.outer / 'protected'
        sentinel.write_text('foreign')
        result = self.uninstall('--verbose')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertEqual('foreign', sentinel.read_text())
        self.assertTrue(journal.exists())

    def test_private_removal_forwards_only_the_explicit_durable_journal_path(self):
        journal = self.terminal_journal()
        result = self.uninstall('--managed-registrations', '--uninstall-journal', str(journal), '--verbose')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['--installation', str(self.product), 'remove', '--control-only', '--json',
                          '--uninstall-journal', str(journal)], json.loads(result.stdout))

    def test_recovery_archive_checksum_rejection_preserves_cleanup_state(self):
        (self.root / '.local/bin/kast').unlink()
        archive = self.recovery_asset()
        archive.write_bytes(b'corrupted archive')
        before = (self.outer / 'management.json').read_bytes()
        result = self.uninstall('--verbose')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('SHA-256 mismatch', result.stderr)
        self.assertEqual('', result.stdout)
        self.assertEqual(before, (self.outer / 'management.json').read_bytes())
        self.assertTrue(self.product.exists())

    def recovery_asset(self):
        assets = self.root / 'assets'
        assets.mkdir()
        payload = self.root / 'recovery-payload/share/kast/libexec/kast-management'
        payload.parent.mkdir(parents=True)
        payload.write_text('#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
        payload.chmod(0o755)
        name = 'kast-control-v1.2.3-macos-aarch64.tar.gz'
        archive = assets / name
        with tarfile.open(archive, 'w:gz') as target:
            target.add(payload, arcname='share/kast/libexec/kast-management')
        (assets / (name + '.sha256')).write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + ' ' + name + '\n')
        self.env['KAST_VERSION'] = '1.2.3'
        self.env['KAST_INSTALL_ASSETS_DIRECTORY'] = str(assets)
        return archive

    def test_explicit_removal_root_rejects_invalid_paths_before_dispatch(self):
        alias = self.root / 'installation-alias'
        alias.symlink_to(self.outer, target_is_directory=True)
        for selected, failure in (('relative', 'sole per-user installation'), (str(alias), 'sole per-user installation'),
                                  (str(self.outer / '..' / 'kast'), 'sole per-user installation')):
            with self.subTest(selected=selected):
                result = self.uninstall('--install-root', selected, '--verbose')
                self.assertNotEqual(0, result.returncode)
                self.assertIn(failure, result.stderr)
                self.assertEqual('', result.stdout)
                self.assertTrue(self.product.exists())

if __name__ == '__main__':
    unittest.main()
