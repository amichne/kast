"""Offline admission checks; fake app trees, no downloads or IDE process."""
import json
import importlib.util
import sys
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest
from acceptance_idea import BUILD, admit_home


class IdeaAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name).resolve() / 'IDEA.app/Contents'
        (self.home / 'Resources').mkdir(parents=True)
        self.info = self.home / 'Resources/product-info.json'
        self.document = {'buildNumber': BUILD, 'launch': [{'os': 'macOS', 'arch': 'aarch64'}]}
        self.info.write_text(json.dumps(self.document))
        self.java = self.home / 'jbr/Contents/Home/bin/java'
        self.java.parent.mkdir(parents=True)
        self.java.write_text('#!/bin/sh\nexit 0\n')
        self.java.chmod(0o700)

    def test_exact_full_distribution_is_admitted_without_launching_it(self):
        self.assertEqual(admit_home(self.home), self.home)

    def test_newer_build_is_not_substituted_for_exact_runtime_authority(self):
        self.document['buildNumber'] = '262.10315.125'
        self.info.write_text(json.dumps(self.document))
        with self.assertRaisesRegex(ValueError, 'IDEA_BUILD_REJECTED'):
            admit_home(self.home)

    def test_compile_distribution_without_bundled_runtime_is_rejected(self):
        self.java.unlink()
        with self.assertRaisesRegex(ValueError, 'IDEA_BUNDLED_RUNTIME_REJECTED'):
            admit_home(self.home)

    def test_intel_only_distribution_is_rejected(self):
        self.document['launch'][0]['arch'] = 'x86_64'
        self.info.write_text(json.dumps(self.document))
        with self.assertRaisesRegex(ValueError, 'IDEA_PLATFORM_REJECTED'):
            admit_home(self.home)

    def test_metadata_redirect_does_not_establish_identity(self):
        outside = self.home.parent / 'foreign.json'
        self.info.rename(outside)
        self.info.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'IDEA_METADATA_REJECTED'):
            admit_home(self.home)


class ResourceAdmissionTest(unittest.TestCase):
    def test_unqualified_ci_runner_fails_before_download_or_fixture(self):
        spec = importlib.util.spec_from_file_location('two_workspace_gate', Path(__file__).with_name('run-two-workspace-acceptance.py'))
        gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gate)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            report = root / 'report.json'
            arguments = ['gate', '--product', str(root), '--runtime', str(root / 'runtime.zip'),
                         '--idea-cache', str(root / 'inputs'), '--harness-classpath-file', str(root / 'classpath'), '--report', str(report), '--profile', 'ci-small']
            with patch.object(sys, 'argv', arguments), patch.object(gate.platform, 'system', return_value='Darwin'), \
                    patch.object(gate.platform, 'machine', return_value='arm64'), \
                    patch.object(gate.subprocess, 'check_output', return_value=str(7 * 1024 ** 3)), \
                    patch.object(gate, 'provision') as provision, patch.object(gate.subprocess, 'run') as run:
                with self.assertRaises(SystemExit) as rejected:
                    gate.main()
                self.assertEqual(rejected.exception.code, 1)
                provision.assert_not_called()
                run.assert_not_called()
            evidence = json.loads(report.with_suffix('.prerequisite.json').read_text())
            self.assertEqual(evidence['status'], 'rejected')
            self.assertIn('ACCEPTANCE_MEMORY_REJECTED', evidence['reason'])


if __name__ == '__main__':
    unittest.main()
