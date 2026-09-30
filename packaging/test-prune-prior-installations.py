"""Disposable ownership and review proofs for historical Kast cleanup."""
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import hashlib
import importlib.util
import io
import json
import os
import plistlib
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('prune-prior-installations.py')
spec = importlib.util.spec_from_file_location('prior_cleanup_under_test', SCRIPT)
cleanup = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = cleanup
spec.loader.exec_module(cleanup)


class PriorCleanupTest(unittest.TestCase):
    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory(prefix='kast-prior-cleanup-')
        self.home = Path(self.fixture.name).resolve()
        self.outer = self.home / 'kast'
        self.versions = self.outer / 'versions'
        self.versions.mkdir(parents=True)
        (self.home / 'Library/LaunchAgents').mkdir(parents=True)
        self.selected = self.version('2.0-' + 'a' * 64, '2.0', 'a')
        (self.outer / 'current').symlink_to('versions/' + self.selected.name)
        self.receipt = SimpleNamespace(plugin=None, priorInstallation=None)

    def tearDown(self):
        self.fixture.cleanup()

    def version(self, name, semantic, digest):
        root = self.versions / name
        (root / 'bin').mkdir(parents=True)
        executable = root / 'bin/kast-complete'
        executable.write_text('#!/bin/sh\nexit 0\n')
        executable.chmod(0o700)
        manifest = {
            'schemaVersion': 1, 'semanticVersion': semantic,
            'installationRoot': str(root), 'payloadIdentity': 'sha256:' + digest * 64,
            'stateRoot': str(root / 'state'),
            'configuration': str(root / 'config/environment'),
            'workspaceRegistry': str(root / 'config/workspaces.json'),
            'externalAnchors': [],
            'payloadFiles': [{'path': 'bin/kast-complete',
                              'sha256': 'sha256:' + hashlib.sha256(executable.read_bytes()).hexdigest(),
                              'mode': 0o700}],
        }
        (root / 'installation.json').write_text(json.dumps(manifest))
        return root

    def run_review(self, answers=None):
        writer = io.StringIO()
        reader = None if answers is None else io.StringIO(answers)
        with patch.dict(os.environ, {'HOME': str(self.home)}), \
             patch.object(cleanup, 'current_receipt', return_value=self.receipt), \
             patch.object(cleanup, 'observe_processes', return_value=[]):
            report = cleanup.run(self.selected, reader, writer if reader else None)
        return report, writer.getvalue()

    def test_single_install_review_protects_managed_parent_and_pending_replacement(self):
        original = self.selected
        self.selected = self.outer / 'installation'
        original.rename(self.selected)
        document = json.loads((self.selected / 'installation.json').read_text())
        document.update(schemaVersion=3, installationRoot=str(self.selected),
            stateRoot=str(self.selected / 'state'), configuration=str(self.selected / 'config/environment'),
            workspaceRegistry=str(self.selected / 'config/workspaces.json'))
        (self.selected / 'installation.json').write_text(json.dumps(document))
        (self.outer / 'current').unlink()
        self.versions.rmdir()
        protected = self.outer / 'management.json'
        protected.write_text('protected management receipt')
        pending = self.outer / 'recovery/replacement/payload'
        pending.mkdir(parents=True)
        report, _ = self.run_review()
        self.assertEqual('complete', report.status)
        self.assertEqual([], report.removed)
        self.assertEqual('protected management receipt', protected.read_text())
        self.assertTrue(pending.is_dir())
        self.assertTrue(self.selected.is_dir())
        self.assertFalse(self.versions.exists())

    def test_admitted_prior_is_removed_and_unproven_entry_requires_exact_yes(self):
        prior = self.version('1.0-' + 'b' * 64, '1.0', 'b')
        unknown = self.versions / 'kast-older'
        unknown.mkdir()
        (unknown / 'private').write_text('owned data')
        report, prompt = self.run_review('yes\n')
        self.assertEqual('complete', report.status)
        self.assertEqual({str(prior), str(unknown)}, set(report.removed))
        self.assertIn(str(unknown), prompt)
        self.assertIn('MANIFEST_REJECTED', prompt)
        self.assertFalse(prior.exists())
        self.assertFalse(unknown.exists())
        self.assertTrue(self.selected.exists())

    def test_no_terminal_retains_unproven_and_still_removes_admitted_prior(self):
        prior = self.version('1.0-' + 'b' * 64, '1.0', 'b')
        unknown = self.versions / 'kast-older'
        unknown.mkdir()
        report, _ = self.run_review()
        self.assertEqual('retained', report.status)
        self.assertEqual([str(prior)], report.removed)
        self.assertEqual([cleanup.Retained(cleanup.Kind.VERSION, str(unknown), cleanup.RetentionReason.DECLINED)], report.retained)
        self.assertTrue(unknown.exists())
        self.assertTrue(self.selected.exists())

    def test_rejection_or_changed_identity_preserves_candidate(self):
        unknown = self.versions / 'kast-older'
        unknown.mkdir()
        report, _ = self.run_review('no\n')
        self.assertEqual([cleanup.Retained(cleanup.Kind.VERSION, str(unknown), cleanup.RetentionReason.DECLINED)], report.retained)
        item = cleanup.candidate(cleanup.Kind.VERSION, unknown, 'MANIFEST_REJECTED')
        unknown.rmdir()
        unknown.mkdir()
        with self.assertRaises(ValueError):
            cleanup.remove_entry(item)
        self.assertTrue(unknown.exists())

    def test_process_trace_identifies_prior_root_and_marks_foreign_owner(self):
        prefix = str(self.versions)
        rows = cleanup.process_rows((
            f'12 {os.getuid()} java -cp {prefix}/1.0-{"b" * 64}/lib/kast.jar io.github.KastMcpMain\n'
            f'13 {os.getuid()} java -cp {self.selected}/lib/kast.jar io.github.KastMcpMain\n'
            f'14 {os.getuid() + 1} java -cp {prefix}/1.0-{"b" * 64}/lib/kast.jar io.github.KastMcpMain\n'
            f'15 {os.getuid()} editor {prefix}/1.0-{"b" * 64}/KastMcpMain\n'
        ).encode())
        found = cleanup.owned_processes(self.outer, self.selected, rows)
        self.assertEqual(['pid:12', 'pid:14'], [item.path for item in found])
        self.assertEqual(str(self.versions / ('1.0-' + 'b' * 64)), found[0].reason)
        self.assertTrue(found[1].reason.startswith('OWNER_UNPROVEN; '))
        with patch.object(cleanup.os, 'kill') as kill:
            with self.assertRaises(ValueError):
                cleanup.stop_process(found[1], self.outer, self.selected)
        kill.assert_not_called()

    def test_selected_recovery_and_plugin_backup_are_never_reviewed(self):
        prior_bundle = self.outer / 'recovery' / 'old-kast'
        prior_bundle.mkdir(parents=True)
        selected_bundle = self.outer / 'recovery' / self.selected.name
        selected_bundle.mkdir()
        plugin_root = self.home / 'idea/.kast-plugin-recovery'
        plugin_root.mkdir(parents=True)
        kept = plugin_root / '.kast-ide-hosted.baseline-current'
        kept.mkdir()
        older = plugin_root / '.kast-ide-hosted.baseline-old'
        older.mkdir()
        destination = self.home / 'idea/plugins/kast-ide-hosted'
        self.receipt.plugin = SimpleNamespace(destination=str(destination), candidate=str(plugin_root / 'candidate'),
                                              backup=str(kept), quarantine=str(plugin_root / 'quarantine'))
        with patch.dict(os.environ, {'HOME': str(self.home)}), \
             patch.object(cleanup, 'current_receipt', return_value=self.receipt):
            _, _, items = cleanup.plan(self.selected, [])
        self.assertEqual({str(prior_bundle), str(older)}, {item.path for item in items})

    def test_reviewed_login_item_boots_out_exact_label_before_removal(self):
        label = 'io.github.amichne.kast.broker.older'
        login = self.home / 'Library/LaunchAgents' / (label + '.login.plist')
        login.write_bytes(plistlib.dumps({'Label': label}))
        with patch.dict(os.environ, {'HOME': str(self.home)}), \
             patch.object(cleanup, 'current_receipt', return_value=self.receipt), \
             patch.object(cleanup, 'observe_processes', return_value=[]), \
             patch.object(cleanup, 'bootout') as bootout:
            report = cleanup.run(self.selected, io.StringIO('yes\n'), io.StringIO())
        self.assertEqual([str(login)], report.removed)
        self.assertFalse(login.exists())
        bootout.assert_called_once_with(label)

    def test_unretired_login_item_is_reported_and_preserved(self):
        label = 'io.github.amichne.kast.broker.older'
        login = self.home / 'Library/LaunchAgents' / (label + '.login.plist')
        login.write_bytes(plistlib.dumps({'Label': label}))
        with patch.dict(os.environ, {'HOME': str(self.home)}), \
             patch.object(cleanup, 'current_receipt', return_value=self.receipt), \
             patch.object(cleanup, 'observe_processes', return_value=[]), \
             patch.object(cleanup, 'bootout', side_effect=ValueError('loaded')):
            report = cleanup.run(self.selected, io.StringIO('yes\n'), io.StringIO())
        self.assertEqual([cleanup.Retained(cleanup.Kind.LOGIN, str(login),
                                           cleanup.RetentionReason.LOGIN_UNPROVEN)], report.retained)
        self.assertTrue(login.exists())

    def test_prior_named_plist_cannot_boot_out_selected_service(self):
        login = self.home / 'Library/LaunchAgents/io.github.amichne.kast.broker.older.plist'
        login.write_bytes(plistlib.dumps({'Label': cleanup.service_label(self.selected)}))
        with patch.dict(os.environ, {'HOME': str(self.home)}), \
             patch.object(cleanup, 'current_receipt', return_value=self.receipt), \
             patch.object(cleanup, 'observe_processes', return_value=[]), \
             patch.object(cleanup, 'bootout') as bootout:
            report = cleanup.run(self.selected, io.StringIO('yes\n'), io.StringIO())
        self.assertEqual(cleanup.RetentionReason.LOGIN_UNPROVEN, report.retained[0].reason)
        self.assertTrue(login.exists())
        bootout.assert_not_called()

    def test_reviewed_process_uses_its_loaded_kast_service(self):
        root = self.versions / ('1.0-' + 'b' * 64)
        command = f'java -cp {root}/lib/kast.jar io.github.KastCliMainKt'
        rows = [(27, os.getuid(), command)]
        item = cleanup.owned_processes(self.outer, self.selected, rows)[0]
        label = 'io.github.amichne.kast.broker.older'
        with patch.object(cleanup, 'observe_processes', side_effect=[rows, []]), \
             patch.object(cleanup, 'launchd_labels', return_value={label: 27}), \
             patch.object(cleanup, 'bootout') as bootout, \
             patch.object(cleanup.os, 'kill') as kill:
            cleanup.stop_process(item, self.outer, self.selected)
        bootout.assert_called_once_with(label)
        kill.assert_not_called()

    def test_reused_process_id_is_never_signalled(self):
        root = self.versions / ('1.0-' + 'b' * 64)
        row = (27, os.getuid(), f'java -cp {root}/lib/kast.jar io.github.KastMcpMain')
        item = cleanup.owned_processes(self.outer, self.selected, [row])[0]
        with patch.object(cleanup, 'observe_processes', return_value=[(27, os.getuid(), 'unrelated')]), \
             patch.object(cleanup.os, 'kill') as kill:
            with self.assertRaises(ValueError):
                cleanup.stop_process(item, self.outer, self.selected)
        kill.assert_not_called()

    def test_relocated_version_retains_original_process_root_binding(self):
        original = self.versions / ('1.0-' + 'b' * 64)
        relocated = self.versions / ('.replaced-' + original.name + '-12345678-1234-1234-1234-123456789abc')
        item = cleanup.Candidate(cleanup.Kind.VERSION, str(relocated), 'MANIFEST_REJECTED',
                                 cleanup.Identity(1, 2, os.getuid()))
        rows = [(27, os.getuid(), f'java -cp {original}/lib/kast.jar io.github.KastMcpMain')]
        self.assertTrue(cleanup.process_holds_version(item, rows))
        self.assertFalse(cleanup.process_holds_version(item, [(27, os.getuid(), str(original) + '-foreign')]))

    def test_manifest_listed_prior_anchor_is_reviewed_and_selected_anchor_is_protected(self):
        prior = self.version('1.0-' + 'b' * 64, '1.0', 'b')
        old_alias = self.home / 'prior-kast-link'
        old_alias.symlink_to(prior)
        selected_alias = self.home / 'selected-kast-link'
        selected_alias.symlink_to(self.selected)
        for root, alias in ((prior, old_alias), (self.selected, selected_alias)):
            manifest = json.loads((root / 'installation.json').read_text())
            manifest['externalAnchors'] = [{'kind': 'command', 'path': str(alias)}]
            (root / 'installation.json').write_text(json.dumps(manifest))
        with patch.dict(os.environ, {'HOME': str(self.home)}), \
             patch.object(cleanup, 'current_receipt', return_value=self.receipt):
            _, _, items = cleanup.plan(self.selected, [])
        self.assertIn(str(old_alias), [item.path for item in items if item.kind is cleanup.Kind.ANCHOR])
        self.assertNotIn(str(selected_alias), [item.path for item in items])


if __name__ == '__main__':
    unittest.main()
