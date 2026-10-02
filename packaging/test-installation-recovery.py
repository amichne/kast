"""Recovery uses only private fixtures; no real installations or processes."""
from dataclasses import asdict, dataclass
from pathlib import Path
import json
import hashlib
import importlib.util
import plistlib
import shutil
from unittest.mock import patch
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('installation-recovery.py')

@dataclass(frozen=True)
class PayloadFileFixture:
    path: str
    sha256: str
    mode: int

@dataclass(frozen=True)
class ControlManifestFixture:
    installationRoot: str
    stateRoot: str
    configuration: str
    workspaceRegistry: str
    payloadFiles: tuple[PayloadFileFixture, ...]
    schemaVersion: int = 3
    semanticVersion: str = '1.2.3'
    payloadIdentity: str = 'sha256:' + 'a' * 64
    externalAnchors: tuple = ()

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
        if operation == 'detach': args = ('--control-only',) + args
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

    def direct_login(self):
        report = self.prepare()
        bundle = Path(report['recoveryExecutable']).parent
        label = 'io.github.amichne.kast.broker.' + hashlib.sha256(str(self.root).encode()).hexdigest()[:32]
        codex_home = str(self.home / '.codex')
        profile = hashlib.sha256(codex_home.encode()).hexdigest()[:16]
        state = self.root / 'state/broker' / profile
        state.mkdir(parents=True)
        document = {
            'Label': label,
            'ProgramArguments': ['/usr/bin/env', '-i', 'HOME=' + str(self.home),
                                 'CODEX_HOME=' + codex_home,
                                 str(self.root / 'share/kast/libexec/kast-daemon')],
            'RunAtLoad': True, 'KeepAlive': {'SuccessfulExit': False}, 'ThrottleInterval': 10,
            'StandardOutPath': str(state / 'service.log'), 'StandardErrorPath': str(state / 'service.log'),
        }
        raw = plistlib.dumps(document)
        daemon_argument = ('<string>' + str(self.root / 'share/kast/libexec/kast-daemon') + '</string>').encode()
        login = raw.replace(daemon_argument, daemon_argument + b'<string>--login</string>')
        receipt = state / 'service.plist'
        receipt.write_bytes(raw)
        receipt.chmod(0o600)
        agent = self.home / 'Library/LaunchAgents' / (label + '.login.plist')
        agent.parent.mkdir(parents=True)
        agent.write_bytes(login)
        agent.chmod(0o600)
        spec = importlib.util.spec_from_file_location('recovery_login_under_test', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        return module, bundle, agent, login

    def test_control_seal_and_detach_leave_independently_owned_host_untouched(self):
        spec = importlib.util.spec_from_file_location('control_recovery_under_test', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        root = self.home / 'control/installation'
        root.mkdir(parents=True)
        plugins = self.home / 'idea/plugins/kast-ide-hosted'
        plugins.mkdir(parents=True)
        marker = plugins / 'host.jar'
        marker.write_text('P1')
        identity = module.Identity.observe(plugins)
        report = module.prepare(root, self.bin)
        self.assertEqual(module.Status.PREPARED, report.status)
        with patch.object(module, 'migrate_plugin_chain', side_effect=AssertionError('control cannot migrate host')):
            sealed = module.seal_upgrade(root)
        self.assertEqual(module.Status.SEALED, sealed.status)
        with patch.object(module, 'retire_and_preserve', return_value=[]) as retire, patch.object(module, 'detach_login', return_value=[]) as login:
            detached = module.detach(root, False)
        retire.assert_called_once()
        login.assert_called_once()
        self.assertEqual(module.Status.CLEAN, detached.status)
        self.assertEqual(identity, module.Identity.observe(plugins))
        self.assertEqual('P1', marker.read_text())

    def test_offline_recovery_detaches_only_exact_direct_service_login(self):
        module, bundle, agent, raw = self.direct_login()
        with patch.dict(os.environ, HOME=str(self.home)):
            self.assertEqual([], module.detach_login(self.root, bundle))
        self.assertFalse(agent.exists())
        self.assertEqual(raw, (bundle / 'login.plist').read_bytes())

    def test_offline_recovery_preserves_changed_direct_service_login(self):
        module, bundle, agent, raw = self.direct_login()
        agent.write_bytes(raw.replace(b'<integer>10</integer>', b'<integer>11</integer>'))
        with patch.dict(os.environ, HOME=str(self.home)):
            self.assertEqual([module.Failure.OWNERSHIP], module.detach_login(self.root, bundle))
        self.assertTrue(agent.exists())
        self.assertFalse((bundle / 'login.plist').exists())

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

    def test_completed_upgrade_seals_recovery_before_prior_payload_removal(self):
        prior = self.root.with_name('0.39.2-' + 'b' * 64)
        prior.mkdir()
        plugins = self.home / 'selected-plugins'
        plugins.mkdir()
        self.root, selected = prior, self.root
        self.prepare()
        self.root = selected
        code, prepared = self.run_recovery('prepare', '--bin-directory', self.bin, '--plugin-root', plugins)
        self.assertEqual(0, code, prepared)
        (self.outer / 'current').unlink()
        (self.outer / 'current').symlink_to('versions/' + selected.name)
        bundle = Path(prepared['recoveryExecutable']).parent
        receipt = json.loads((bundle / 'receipt.json').read_text())
        self.assertEqual(str(prior), receipt['priorInstallation'])
        code, rejected = self.run_recovery('seal-upgrade')
        self.assertNotEqual(0, code)
        self.assertEqual('RecoveryBlocked', rejected['status'])
        self.assertEqual(str(prior), json.loads((bundle / 'receipt.json').read_text())['priorInstallation'])
        staged = self.home / 'staged'
        (staged / 'kast-ide-hosted').mkdir(parents=True)
        (staged / 'kast-ide-hosted/plugin.txt').write_text('selected')
        code, activated = self.run_recovery('activate-plugin', '--staged-plugin', staged, '--plugin-root', plugins)
        self.assertEqual(0, code, activated)
        code, sealed = self.run_recovery('seal-upgrade')
        self.assertEqual(0, code, sealed)
        self.assertEqual('UpgradeFinalized', sealed['status'])
        receipt = json.loads((bundle / 'receipt.json').read_text())
        self.assertIsNone(receipt['priorInstallation'])
        self.assertIsNone(receipt['links'][0]['priorTarget'])
        shutil.rmtree(prior)
        spec = importlib.util.spec_from_file_location('recovery_seal_test', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        self.assertEqual(module.Status.ACTIVE, module.migrate_plugin_chain(self.root).stage)

    def test_standalone_bundle_survives_missing_payload(self):
        report = self.prepare()
        bundle = Path(report['recoveryExecutable'])
        self.assertTrue(bundle.is_file())
        result = subprocess.run([sys.executable, str(bundle), 'detach', '--control-only', '--installation', str(self.root)],
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
        self.assertEqual(1, len(list((plugins.parent / '.kast-plugin-recovery').glob('.kast-ide-hosted.baseline-*'))))
        module.activate_plugin(self.root, staged, plugins)
        self.assertEqual('new plugin', (plugins / 'kast-ide-hosted/new').read_text())
        self.assertEqual('working baseline', next((plugins.parent / '.kast-plugin-recovery').glob('.kast-ide-hosted.baseline-*/old')).read_text())
        code, report = self.run_recovery('detach-legacy-pair')
        self.assertNotEqual(0, code)
        self.assertIn('IDE_RESTART_REQUIRED', report['unresolved'])
        self.assertFalse((plugins / 'kast-ide-hosted').exists())
        self.assertEqual('working baseline', next((plugins.parent / '.kast-plugin-recovery').glob('.kast-ide-hosted.baseline-*/old')).read_text())

    def recovery_module(self):
        spec = importlib.util.spec_from_file_location('recovery_retention_test', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        return module

    def plugin_fixture(self, module, *, legacy=False):
        self.prepare()
        plugins = self.home / 'plugins'
        active = plugins / 'kast-ide-hosted'
        active.mkdir(parents=True)
        (active / 'bytes').write_bytes(b'prior active plugin')
        staged = self.home / 'staged'
        (staged / 'kast-ide-hosted').mkdir(parents=True)
        (staged / 'kast-ide-hosted/bytes').write_bytes(b'next plugin')
        if legacy:
            token = 'a' * 32
            backup = plugins / ('.kast-ide-hosted.baseline-' + token)
            backup.mkdir()
            (backup / 'bytes').write_bytes(b'older retained plugin')
            bundle = self.outer / 'recovery' / self.root.name
            receipt = module.load(bundle / 'receipt.json')
            plugin = module.Plugin(str(active), str(plugins / ('.kast-ide-hosted.install-' + token)),
                                   module.Identity.observe(active), str(backup), module.Identity.observe(backup),
                                   str(plugins / ('.kast-ide-hosted.detached-' + token)))
            module.save(bundle / 'receipt.json', module.replace_stage(receipt, module.Status.ACTIVE, plugin))
        return plugins, staged

    def test_force_replaces_a_plugin_symlink_without_touching_its_target(self):
        module = self.recovery_module()
        plugins, staged = self.plugin_fixture(module)
        active = plugins / 'kast-ide-hosted'
        target = self.home / 'unrelated-plugin'
        active.rename(target)
        active.symlink_to(target, target_is_directory=True)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), 'activate-plugin', '--installation', str(self.root),
             '--plugin-root', str(plugins), '--staged-plugin', str(staged), '--force'],
            env=self.environment, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertFalse(active.is_symlink())
        self.assertEqual(b'next plugin', (active / 'bytes').read_bytes())
        self.assertEqual(b'prior active plugin', (target / 'bytes').read_bytes())
        retained = plugins.parent / '.kast-plugin-recovery'
        self.assertTrue(any(path.is_symlink() for path in retained.iterdir()))

    def test_inactive_plugin_paths_never_enter_discovery(self):
        module = self.recovery_module()
        plugins, staged = self.plugin_fixture(module)
        before = module.Identity.observe(plugins / 'kast-ide-hosted')
        module.activate_plugin(self.root, staged, plugins)
        bundle = self.outer / 'recovery' / self.root.name
        receipt = module.load(bundle / 'receipt.json')
        retained = plugins.parent / '.kast-plugin-recovery'
        self.assertEqual([plugins / 'kast-ide-hosted'], list(plugins.iterdir()))
        self.assertEqual([retained] * 3, [Path(raw).parent for raw in
                         (receipt.plugin.candidate, receipt.plugin.backup, receipt.plugin.quarantine)])
        self.assertEqual(before, module.Identity.observe(Path(receipt.plugin.backup)))
        self.assertEqual(b'prior active plugin', (Path(receipt.plugin.backup) / 'bytes').read_bytes())
        self.assertEqual(retained.stat().st_dev, plugins.stat().st_dev)
        code, report = self.run_recovery('detach-legacy-pair')
        self.assertIn('IDE_RESTART_REQUIRED', report['unresolved'])
        self.assertEqual([], list(plugins.iterdir()))
        self.assertEqual(b'next plugin', (Path(receipt.plugin.quarantine) / 'bytes').read_bytes())
        self.assertEqual(b'prior active plugin', (Path(receipt.plugin.backup) / 'bytes').read_bytes())

    def test_adjacent_upgrade_moves_receipted_legacy_backup_without_touching_prior_payload(self):
        module = self.recovery_module()
        plugins, staged = self.plugin_fixture(module, legacy=True)
        prior = self.root
        (prior / 'installation.json').write_bytes(b'original manifest')
        (prior / 'config').mkdir()
        (prior / 'config/environment').write_bytes(b'original config')
        (prior / 'config/workspaces.json').write_bytes(b'original registry')
        target = self.outer / 'versions' / ('0.39.4-' + 'b' * 64)
        target.mkdir()
        module.prepare(target, self.bin, plugins)
        module.activate_plugin(target, staged, plugins)
        self.assertEqual([plugins / 'kast-ide-hosted'], list(plugins.iterdir()))
        prior_receipt = module.load(self.outer / 'recovery' / prior.name / 'receipt.json')
        self.assertEqual(b'older retained plugin', (Path(prior_receipt.plugin.backup) / 'bytes').read_bytes())
        target_receipt = module.load(self.outer / 'recovery' / target.name / 'receipt.json')
        self.assertEqual(b'prior active plugin', (Path(target_receipt.plugin.backup) / 'bytes').read_bytes())
        self.assertEqual(b'original manifest', (prior / 'installation.json').read_bytes())
        self.assertEqual(b'original config', (prior / 'config/environment').read_bytes())
        self.assertEqual(b'original registry', (prior / 'config/workspaces.json').read_bytes())

    def test_legacy_migration_resumes_after_saved_intent_and_preserves_inode(self):
        module = self.recovery_module()
        plugins, staged = self.plugin_fixture(module, legacy=True)
        backup = next(plugins.glob('.kast-ide-hosted.baseline-*'))
        identity = module.Identity.observe(backup)
        original = Path.rename
        interrupted = False
        def interrupt(path, target):
            nonlocal interrupted
            if path == backup and not interrupted:
                interrupted = True
                raise OSError('interruption after migration intent')
            return original(path, target)
        with patch.object(Path, 'rename', interrupt):
            with self.assertRaises(OSError):
                module.activate_plugin(self.root, staged, plugins)
        receipt = module.load(self.outer / 'recovery' / self.root.name / 'receipt.json')
        self.assertEqual(plugins.parent / '.kast-plugin-recovery', Path(receipt.plugin.backup).parent)
        module.activate_plugin(self.root, staged, plugins)
        self.assertEqual(identity, module.Identity.observe(Path(receipt.plugin.backup)))
        self.assertFalse(backup.exists())
        self.assertEqual([plugins / 'kast-ide-hosted'], list(plugins.iterdir()))

    def test_forged_legacy_backup_is_not_moved(self):
        module = self.recovery_module()
        plugins, staged = self.plugin_fixture(module, legacy=True)
        backup = next(plugins.glob('.kast-ide-hosted.baseline-*'))
        backup.rename(backup.with_name('foreign-retained'))
        backup.mkdir()
        (backup / 'bytes').write_bytes(b'foreign replacement')
        with self.assertRaises(module.Rejected) as rejected:
            module.activate_plugin(self.root, staged, plugins)
        self.assertEqual(module.Failure.OWNERSHIP, rejected.exception.failure)
        self.assertEqual(b'foreign replacement', (backup / 'bytes').read_bytes())

    def test_mixed_or_unrelated_plugin_layout_is_a_finite_receipt_failure(self):
        module = self.recovery_module()
        plugins, _ = self.plugin_fixture(module, legacy=True)
        bundle = self.outer / 'recovery' / self.root.name
        receipt = module.load(bundle / 'receipt.json')
        foreign = self.home / 'foreign'
        foreign.mkdir()
        for backup in (foreign / Path(receipt.plugin.backup).name,
                       plugins.parent / '.kast-plugin-recovery' / Path(receipt.plugin.backup).name,
                       plugins / ('.kast-ide-hosted.baseline-' + 'b' * 32)):
            with self.subTest(backup=backup):
                malformed = module.replace(receipt, plugin=module.replace(receipt.plugin, backup=str(backup)))
                module.save(bundle / 'receipt.json', malformed)
                code, report = self.run_recovery('detach')
                self.assertEqual('RecoveryBlocked', report['status'])
                self.assertEqual(['RECEIPT_REJECTED'], report['unresolved'])
                self.assertFalse((self.root / '.recovery-detached').exists())
                self.assertEqual(b'prior active plugin', (plugins / 'kast-ide-hosted/bytes').read_bytes())

    def test_legacy_detach_relocates_backup_and_quarantine_outside_discovery(self):
        module = self.recovery_module()
        plugins, _ = self.plugin_fixture(module, legacy=True)
        code, report = self.run_recovery('detach-legacy-pair')
        self.assertEqual('DetachedWithUnresolvedState', report['status'])
        self.assertEqual([], list(plugins.iterdir()))
        receipt = module.load(self.outer / 'recovery' / self.root.name / 'receipt.json')
        self.assertEqual(b'older retained plugin', (Path(receipt.plugin.backup) / 'bytes').read_bytes())
        self.assertEqual(b'prior active plugin', (Path(receipt.plugin.quarantine) / 'bytes').read_bytes())
        self.assertEqual((code, report), self.run_recovery('detach-legacy-pair'))

    def test_legacy_pending_candidate_activates_from_retained_storage(self):
        module = self.recovery_module()
        plugins, staged = self.plugin_fixture(module, legacy=True)
        bundle = self.outer / 'recovery' / self.root.name
        receipt = module.load(bundle / 'receipt.json')
        old_backup = Path(receipt.plugin.backup)
        old_backup.rename(self.home / 'unrelated-retained-bytes')
        candidate = Path(receipt.plugin.candidate)
        (staged / 'kast-ide-hosted').rename(candidate)
        active = plugins / 'kast-ide-hosted'
        pending = module.replace(receipt.plugin, candidateIdentity=module.Identity.observe(candidate),
                                 priorIdentity=module.Identity.observe(active))
        module.save(bundle / 'receipt.json', module.replace_stage(receipt, module.Status.PREPARED, pending))
        candidate_identity = module.Identity.observe(candidate)
        module.activate_plugin(self.root, staged, plugins)
        self.assertEqual([active], list(plugins.iterdir()))
        self.assertEqual(candidate_identity, module.Identity.observe(active))
        self.assertEqual(b'next plugin', (active / 'bytes').read_bytes())
        admitted = module.load(bundle / 'receipt.json')
        self.assertEqual(b'prior active plugin', (Path(admitted.plugin.backup) / 'bytes').read_bytes())

    def test_legacy_detached_quarantine_is_migrated_without_reactivation(self):
        module = self.recovery_module()
        plugins, _ = self.plugin_fixture(module, legacy=True)
        bundle = self.outer / 'recovery' / self.root.name
        receipt = module.load(bundle / 'receipt.json')
        active = plugins / 'kast-ide-hosted'
        identity = module.Identity.observe(active)
        active.rename(Path(receipt.plugin.quarantine))
        module.save(bundle / 'receipt.json', module.replace_stage(receipt, module.Status.UNRESOLVED))
        code, report = self.run_recovery('detach-legacy-pair')
        self.assertEqual('DetachedWithUnresolvedState', report['status'])
        self.assertEqual([], list(plugins.iterdir()))
        migrated = module.load(bundle / 'receipt.json')
        self.assertEqual(identity, module.Identity.observe(Path(migrated.plugin.quarantine)))
        self.assertEqual(b'prior active plugin', (Path(migrated.plugin.quarantine) / 'bytes').read_bytes())

    def test_control_detach_preserves_populated_historical_host_receipt(self):
        module = self.recovery_module()
        plugins, _ = self.plugin_fixture(module, legacy=True)
        active = plugins / 'kast-ide-hosted'
        before = module.Identity.observe(active)
        bundle = self.outer / 'recovery' / self.root.name
        historical = module.load(bundle / 'receipt.json').plugin
        backup = Path(historical.backup)
        backup_identity = module.Identity.observe(backup)
        code, report = self.run_recovery('detach')
        self.assertEqual('DetachedWithUnresolvedState', report['status'])
        self.assertEqual(before, module.Identity.observe(active))
        self.assertEqual(b'prior active plugin', (active / 'bytes').read_bytes())
        self.assertEqual(backup_identity, module.Identity.observe(backup))
        self.assertEqual(b'older retained plugin', (backup / 'bytes').read_bytes())
        self.assertEqual(historical, module.load(bundle / 'receipt.json').plugin)
        self.assertNotIn('IDE_RESTART_REQUIRED', report['unresolved'])

    def test_public_control_removal_preserves_populated_historical_host_receipt(self):
        module = self.recovery_module()
        root = self.home / 'data/kast/installation'
        (root / 'bin').mkdir(parents=True)
        script = root / 'share/kast/installation-lifecycle.py'
        script.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT.with_name('installation-lifecycle.py'), script)
        executable = root / 'bin/kast-complete'
        executable.write_text("#!/bin/sh\n[ \"$*\" = 'app-server disable' ] || exit 95\n")
        executable.chmod(0o700)
        manifest = ControlManifestFixture(str(root), str(root / 'state'), str(root / 'config/environment'),
            str(root / 'config/workspaces.json'), (PayloadFileFixture('bin/kast-complete',
            'sha256:' + hashlib.sha256(executable.read_bytes()).hexdigest(), 448),
            PayloadFileFixture('share/kast/installation-lifecycle.py',
            'sha256:' + hashlib.sha256(script.read_bytes()).hexdigest(), script.stat().st_mode & 0o777)))
        (root / 'installation.json').write_text(json.dumps(asdict(manifest)))
        module.prepare(root, self.bin)
        plugins = self.home / 'idea-profile/plugins'
        active = plugins / 'kast-ide-hosted'
        active.mkdir(parents=True)
        marker = active / 'host.jar'
        marker.write_bytes(b'P1')
        identity = module.Identity.observe(active)
        bundle = root.parent / 'recovery/installation'
        receipt = module.load(bundle / 'receipt.json')
        token = 'c' * 32
        historical = module.Plugin(str(active), str(plugins / ('.kast-ide-hosted.install-' + token)),
            identity, str(plugins / ('.kast-ide-hosted.baseline-' + token)), None,
            str(plugins / ('.kast-ide-hosted.detached-' + token)))
        module.save(bundle / 'receipt.json', module.replace(receipt, plugin=historical,
            pluginRoot=str(plugins), stage=module.Status.ACTIVE))
        result = subprocess.run(['/bin/bash', str(SCRIPT.parent.parent / 'install.sh'), 'uninstall',
            '--managed-registrations'], env=dict(self.environment, XDG_DATA_HOME=str(self.home / 'data'),
            NO_COLOR='1'), capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(root.exists())
        self.assertEqual(identity, module.Identity.observe(active))
        self.assertEqual(b'P1', marker.read_bytes())
        self.assertEqual(historical, module.load(bundle / 'receipt.json').plugin)

    def test_unmarked_control_detach_rejects_before_any_effect(self):
        self.prepare()
        result = subprocess.run([sys.executable, str(SCRIPT), 'detach', '--installation', str(self.root)],
            env=self.environment, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('RECEIPT_REJECTED', json.loads(result.stdout)['unresolved'][0])
        self.assertFalse((self.root / '.recovery-detached').exists())
        self.assertTrue((self.outer / 'current').is_symlink())

    def test_historical_helper_rejects_control_flag_before_effects(self):
        self.prepare()
        historical = self.home / 'historical-recovery.py'
        historical.write_text("import argparse\nparser = argparse.ArgumentParser()\n"
            "parser.add_argument('operation', choices=('detach',))\n"
            "parser.add_argument('--installation', required=True)\nparser.parse_args()\n"
            "raise AssertionError('old helper must reject before its effect boundary')\n")
        result = subprocess.run([sys.executable, str(historical), 'detach', '--control-only',
                                 '--installation', str(self.root)], env=self.environment,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(2, result.returncode)
        self.assertIn('unrecognized arguments: --control-only', result.stderr)
        self.assertFalse((self.root / '.recovery-detached').exists())
        self.assertTrue((self.outer / 'current').is_symlink())

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


class SingleInstallationRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory(prefix='kast-single-recovery-')
        self.addCleanup(self.fixture.cleanup)
        self.home = Path(self.fixture.name).resolve()
        self.outer = self.home / 'kast'
        self.root = self.outer / 'installation'
        self.root.mkdir(parents=True)
        self.bin = self.home / 'bin'
        self.bin.mkdir()
        self.plugins = self.home / 'plugins'
        self.plugins.mkdir()
        spec = importlib.util.spec_from_file_location('single_recovery_under_test', SCRIPT)
        self.recovery = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = self.recovery
        spec.loader.exec_module(self.recovery)
        self.recovery.prepare(self.root, self.bin, self.plugins)
        self.bundle = self.outer / 'recovery/installation'

    def staged(self, name, text):
        staged = self.home / name
        (staged / 'kast-ide-hosted').mkdir(parents=True)
        (staged / 'kast-ide-hosted/plugin.txt').write_text(text)
        return staged

    def pending(self, previous=None, stage=None):
        r = self.recovery
        transaction = self.outer / 'recovery/replacement'
        transaction.mkdir()
        r.save(transaction / 'receipt.json', r.Replacement(1, stage or r.ReplacementStage.COMMITTED,
            str(self.root), r.Identity.observe(self.root), previous or r.NoPrevious('NONE')))
        return transaction

    def test_new_receipt_has_one_physical_identity_and_no_selectors(self):
        document = json.loads((self.bundle / 'receipt.json').read_text())
        self.assertEqual({'schemaVersion', 'installation', 'installationIdentity', 'plugin', 'pluginRoot', 'stage'}, set(document))
        self.assertEqual(3, document['schemaVersion'])
        self.assertEqual(str(self.root), document['installation'])
        self.assertFalse((self.outer / 'current').exists())
        self.assertFalse((self.outer / 'versions').exists())
        self.assertFalse(any(path.is_symlink() for path in self.outer.rglob('*')))

    def test_fresh_activation_seals_and_removes_only_pending_transaction(self):
        r = self.recovery
        transaction = self.pending()
        r.activate_plugin(self.root, self.staged('candidate', 'new'), self.plugins)
        self.assertEqual(r.Status.SEALED, r.seal_upgrade(self.root).status)
        self.assertFalse(transaction.exists())
        self.assertEqual('new', (self.plugins / 'kast-ide-hosted/plugin.txt').read_text())
        self.assertTrue((self.bundle / 'receipt.json').is_file())

    def test_replacement_installs_new_plugin_and_discards_transient_previous_payload(self):
        r = self.recovery
        r.activate_plugin(self.root, self.staged('first', 'old'), self.plugins)
        receipt = r.load(self.bundle / 'receipt.json')
        r.save(self.bundle / 'receipt.json', r.replace(receipt, stage=r.Status.PREPARED))
        transaction = self.outer / 'recovery/replacement'
        transaction.mkdir()
        payload = transaction / 'payload'
        payload.mkdir()
        (payload / 'old.txt').write_text('old payload')
        previous_recovery = transaction / 'recovery'
        previous_recovery.mkdir()
        previous = r.PhysicalPrevious('PHYSICAL', str(self.root), r.Identity.observe(payload), str(payload), str(previous_recovery))
        r.save(transaction / 'receipt.json', r.Replacement(1, r.ReplacementStage.COMMITTED, str(self.root), r.Identity.observe(self.root), previous))
        r.activate_plugin(self.root, self.staged('second', 'new'), self.plugins)
        new_receipt = r.load(self.bundle / 'receipt.json')
        backup = Path(new_receipt.plugin.backup)
        self.assertEqual('old', (backup / 'plugin.txt').read_text())
        self.assertEqual('new', (self.plugins / 'kast-ide-hosted/plugin.txt').read_text())
        r.seal_upgrade(self.root)
        self.assertFalse(transaction.exists())
        self.assertFalse(backup.exists())
        self.assertIsNone(r.load(self.bundle / 'receipt.json').plugin.priorIdentity)

    def test_replacement_resumes_admitted_candidate_after_interruption_before_backup(self):
        r = self.recovery
        r.activate_plugin(self.root, self.staged('first', 'old'), self.plugins)
        baseline = r.load(self.bundle / 'receipt.json')
        r.save(self.bundle / 'receipt.json', r.replace(baseline, stage=r.Status.PREPARED))
        destination = self.plugins / 'kast-ide-hosted'
        old_identity = r.Identity.observe(destination)
        staged = self.staged('second', 'new')
        attempts = []

        def interrupt(path, target):
            pending = r.load(self.bundle / 'receipt.json')
            self.assertEqual(destination, path)
            self.assertEqual(Path(pending.plugin.backup), target)
            attempts.append((path, target))
            raise OSError('interruption before receipted baseline rename')

        with patch.object(Path, 'rename', interrupt):
            with self.assertRaises(OSError):
                r.activate_plugin(self.root, staged, self.plugins)
        self.assertEqual(1, len(attempts))
        pending = r.load(self.bundle / 'receipt.json')
        candidate = Path(pending.plugin.candidate)
        self.assertEqual(old_identity, r.Identity.observe(destination))
        self.assertEqual('old', (destination / 'plugin.txt').read_text())
        self.assertEqual('new', (candidate / 'plugin.txt').read_text())
        self.assertFalse(Path(pending.plugin.backup).exists())
        self.assertEqual(r.Status.ACTIVE, r.activate_plugin(self.root, staged, self.plugins).status)
        self.assertEqual(pending.plugin.candidateIdentity, r.Identity.observe(destination))
        self.assertEqual(old_identity, r.Identity.observe(Path(pending.plugin.backup)))
        self.assertEqual('new', (destination / 'plugin.txt').read_text())
        self.assertFalse(candidate.exists())

    def test_replacement_resumes_admitted_candidate_after_interruption_after_backup(self):
        r = self.recovery
        r.activate_plugin(self.root, self.staged('first', 'old'), self.plugins)
        baseline = r.load(self.bundle / 'receipt.json')
        r.save(self.bundle / 'receipt.json', r.replace(baseline, stage=r.Status.PREPARED))
        destination = self.plugins / 'kast-ide-hosted'
        old_identity = r.Identity.observe(destination)
        staged = self.staged('second', 'new')
        attempts = []
        rename = Path.rename

        def interrupt(path, target):
            pending = r.load(self.bundle / 'receipt.json')
            attempts.append((path, target))
            if len(attempts) == 1:
                self.assertEqual((destination, Path(pending.plugin.backup)), (path, target))
                return rename(path, target)
            self.assertEqual(2, len(attempts))
            self.assertEqual((Path(pending.plugin.candidate), destination), (path, target))
            raise OSError('interruption before receipted candidate rename')

        with patch.object(Path, 'rename', interrupt):
            with self.assertRaises(OSError):
                r.activate_plugin(self.root, staged, self.plugins)
        self.assertEqual(2, len(attempts))
        pending = r.load(self.bundle / 'receipt.json')
        candidate, backup = Path(pending.plugin.candidate), Path(pending.plugin.backup)
        self.assertFalse(destination.exists())
        self.assertEqual(old_identity, r.Identity.observe(backup))
        self.assertEqual('old', (backup / 'plugin.txt').read_text())
        self.assertEqual('new', (candidate / 'plugin.txt').read_text())
        self.assertEqual(r.Status.ACTIVE, r.activate_plugin(self.root, staged, self.plugins).status)
        self.assertEqual(pending.plugin.candidateIdentity, r.Identity.observe(destination))
        self.assertEqual(old_identity, r.Identity.observe(backup))
        self.assertEqual('new', (destination / 'plugin.txt').read_text())
        self.assertFalse(candidate.exists())

    def test_activation_retry_persists_active_receipt_after_candidate_was_renamed(self):
        r = self.recovery
        transaction = self.pending()
        staged = self.staged('candidate', 'new')
        destination = self.plugins / 'kast-ide-hosted'
        receipt_path = self.bundle / 'receipt.json'
        writes = []
        save = r.save

        def interrupt(path, document):
            self.assertEqual(receipt_path, path)
            self.assertIsInstance(document, r.Receipt)
            writes.append(document.stage)
            self.assertLessEqual(len(writes), 2)
            if document.stage is r.Status.ACTIVE:
                raise OSError('interruption before active receipt publication')
            return save(path, document)

        with patch.object(r, 'save', interrupt):
            with self.assertRaises(OSError):
                r.activate_plugin(self.root, staged, self.plugins)
        self.assertEqual([r.Status.PLUGIN_PREPARED, r.Status.ACTIVE], writes)
        pending = r.load(receipt_path)
        self.assertEqual(r.Status.PLUGIN_PREPARED, pending.stage)
        self.assertEqual(pending.plugin.candidateIdentity, r.Identity.observe(destination))
        self.assertEqual('new', (destination / 'plugin.txt').read_text())
        self.assertEqual(r.Status.ACTIVE, r.activate_plugin(self.root, staged, self.plugins).status)
        self.assertEqual(r.Status.ACTIVE, r.load(receipt_path).stage)
        self.assertEqual(pending.plugin.candidateIdentity, r.Identity.observe(destination))
        self.assertEqual(r.Status.SEALED, r.seal_upgrade(self.root).status)
        self.assertFalse(transaction.exists())

    def test_finalization_retries_after_backup_deletion_before_receipt_publication(self):
        r = self.recovery
        r.activate_plugin(self.root, self.staged('first', 'old'), self.plugins)
        baseline = r.load(self.bundle / 'receipt.json')
        r.save(self.bundle / 'receipt.json', r.replace(baseline, stage=r.Status.PREPARED))
        transaction = self.outer / 'recovery/replacement'
        transaction.mkdir()
        payload, recovery = transaction / 'payload', transaction / 'recovery'
        payload.mkdir()
        (payload / 'old.txt').write_text('old payload')
        recovery.mkdir()
        previous = r.PhysicalPrevious('PHYSICAL', str(self.root), r.Identity.observe(payload), str(payload), str(recovery))
        r.save(transaction / 'receipt.json', r.Replacement(1, r.ReplacementStage.COMMITTED,
            str(self.root), r.Identity.observe(self.root), previous))
        staged = self.staged('second', 'new')
        r.activate_plugin(self.root, staged, self.plugins)
        active = r.load(self.bundle / 'receipt.json')
        backup = Path(active.plugin.backup)
        writes = []
        save = r.save

        def interrupt(path, document):
            writes.append(path)
            if len(writes) == 1:
                self.assertEqual(transaction / 'receipt.json', path)
                self.assertIsInstance(document, r.Replacement)
                self.assertEqual(r.ReplacementStage.FINALIZING, document.stage)
            elif len(writes) == 2:
                self.assertEqual(self.bundle / 'receipt.json', path)
                self.assertIsInstance(document, r.Receipt)
                self.assertEqual(r.Status.FINALIZING, document.stage)
                self.assertEqual(active.plugin.priorIdentity, document.plugin.priorIdentity)
            else:
                self.assertEqual(3, len(writes))
                self.assertEqual(self.bundle / 'receipt.json', path)
                self.assertIsInstance(document, r.Receipt)
                self.assertEqual(r.Status.ACTIVE, document.stage)
                self.assertIsNone(document.plugin.priorIdentity)
                raise OSError('interruption after baseline removal before final receipt publication')
            return save(path, document)

        with patch.object(r, 'save', interrupt):
            with self.assertRaises(OSError):
                r.seal_upgrade(self.root)
        self.assertEqual(3, len(writes))
        pending = r.load(self.bundle / 'receipt.json')
        self.assertEqual(r.Status.FINALIZING, pending.stage)
        self.assertEqual(active.plugin.priorIdentity, pending.plugin.priorIdentity)
        self.assertFalse(backup.exists())
        self.assertEqual(r.ReplacementStage.FINALIZING,
                         r.load(transaction / 'receipt.json', r.Replacement).stage)
        self.assertEqual(previous.identity, r.Identity.observe(payload))
        self.assertEqual('old payload', (payload / 'old.txt').read_text())
        self.assertTrue(recovery.is_dir())
        self.assertEqual(r.Status.ACTIVE, r.activate_plugin(self.root, staged, self.plugins).status)
        self.assertEqual(r.Status.FINALIZING, r.load(self.bundle / 'receipt.json').stage)
        self.assertEqual(r.Status.SEALED, r.seal_upgrade(self.root).status)
        self.assertFalse(transaction.exists())
        sealed = r.load(self.bundle / 'receipt.json')
        self.assertEqual(r.Status.ACTIVE, sealed.stage)
        self.assertIsNone(sealed.plugin.priorIdentity)
        self.assertEqual(active.plugin.candidateIdentity, r.Identity.observe(self.plugins / 'kast-ide-hosted'))
        self.assertEqual('new', (self.plugins / 'kast-ide-hosted/plugin.txt').read_text())

    def test_finalization_retries_after_legacy_cleanup_before_transaction_receipt_removal(self):
        r = self.recovery
        prior = self.outer / 'versions' / ('1.2.3-' + 'a' * 64)
        executable = prior / 'bin/kast-complete'
        executable.parent.mkdir(parents=True)
        executable.write_text('#!/bin/sh\nexit 0\n')
        executable.chmod(0o700)
        selector = self.outer / 'current'
        target = 'versions/' + prior.name
        selector.symlink_to(target)
        manifest = {
            'schemaVersion': 2, 'semanticVersion': '1.2.3', 'installationRoot': str(prior),
            'payloadIdentity': 'sha256:' + 'a' * 64, 'stateRoot': str(prior / 'state'),
            'configuration': str(prior / 'config/environment'),
            'workspaceRegistry': str(prior / 'config/workspaces.json'),
            'externalAnchors': [{'kind': 'current', 'path': str(selector), 'expectedLinkTarget': target}],
            'payloadFiles': [{'path': 'bin/kast-complete',
                             'sha256': 'sha256:' + hashlib.sha256(executable.read_bytes()).hexdigest(), 'mode': 448}],
        }
        (prior / 'installation.json').write_text(json.dumps(manifest))
        transaction = self.outer / 'recovery/replacement'
        transaction.mkdir()
        recovery = transaction / 'recovery'
        recovery.mkdir()
        previous = r.LegacyPrevious('LEGACY', str(prior), r.Identity.observe(prior), str(selector), target, str(recovery))
        transaction_receipt = transaction / 'receipt.json'
        r.save(transaction_receipt, r.Replacement(1, r.ReplacementStage.COMMITTED,
            str(self.root), r.Identity.observe(self.root), previous))
        r.activate_plugin(self.root, self.staged('candidate', 'new'), self.plugins)
        active_identity = r.Identity.observe(self.plugins / 'kast-ide-hosted')
        attempts = []
        unlink = Path.unlink

        def interrupt(path, *arguments, **options):
            if path == transaction_receipt:
                attempts.append(path)
                self.assertEqual(1, len(attempts))
                raise OSError('interruption after legacy cleanup before final receipt removal')
            self.assertEqual(selector, path)
            return unlink(path, *arguments, **options)

        with patch.object(Path, 'unlink', interrupt):
            with self.assertRaises(OSError):
                r.seal_upgrade(self.root)
        self.assertEqual([transaction_receipt], attempts)
        self.assertFalse(prior.exists())
        self.assertFalse(selector.is_symlink())
        self.assertFalse(recovery.exists())
        self.assertEqual(r.ReplacementStage.FINALIZING, r.load(transaction_receipt, r.Replacement).stage)
        self.assertEqual(active_identity, r.Identity.observe(self.plugins / 'kast-ide-hosted'))
        self.assertEqual(r.Status.SEALED, r.seal_upgrade(self.root).status)
        self.assertFalse(transaction.exists())
        self.assertEqual(active_identity, r.Identity.observe(self.plugins / 'kast-ide-hosted'))
        self.assertEqual('new', (self.plugins / 'kast-ide-hosted/plugin.txt').read_text())

    def test_uncommitted_replacement_is_preserved_on_finalization_rejection(self):
        r = self.recovery
        transaction = self.pending(stage=r.ReplacementStage.PREPARED)
        r.activate_plugin(self.root, self.staged('candidate', 'new'), self.plugins)
        with self.assertRaises(r.Rejected) as rejected:
            r.seal_upgrade(self.root)
        self.assertEqual(r.Failure.RECEIPT, rejected.exception.failure)
        self.assertTrue(transaction.exists())
        self.assertEqual('new', (self.plugins / 'kast-ide-hosted/plugin.txt').read_text())

    def test_symlink_installation_is_rejected_without_touching_target(self):
        r = self.recovery
        other = self.outer / 'alias'
        other.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(r.Rejected):
            r.location(other)
        self.assertTrue(other.is_symlink())
        self.assertTrue((self.bundle / 'receipt.json').is_file())


if __name__ == '__main__':
    unittest.main()
