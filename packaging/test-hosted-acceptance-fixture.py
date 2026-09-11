"""Offline native-fixture admission: no IDE launch or user-profile writes."""
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from acceptance_environment import AcceptanceEnvironment
from hosted_acceptance_fixture import (FixtureFailure, FixtureRejected, admit_hosted_idea,
                                       prepare_hosted_fixture, stage_hosted_plugin)


class HostedFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.home = self.root / 'IDEA.app/Contents'
        self.metadata = self.home / 'Resources/product-info.json'
        self.metadata.parent.mkdir(parents=True)
        self.metadata.write_text(json.dumps({'buildNumber': '262.10315.125',
                                            'launch': [{'os': 'macOS', 'arch': 'aarch64'}]}))
        for name in ('MacOS/idea', 'jbr/Contents/Home/bin/java'):
            path = self.home / name
            path.parent.mkdir(parents=True)
            path.write_text('#!/bin/sh\nexit 0\n')
            path.chmod(0o700)
        (self.home / 'bin').mkdir()
        (self.home / 'bin/idea.vmoptions').write_text('-Xmx2g\n')
        self.catalog = self.root / 'versions.toml'
        self.catalog.write_text('[versions]\nide-host-build = "262.10315.125"\n'
                                'ide-kotlin-plugin-build = "262.10315.125-IJ"\n')
        self.idea = admit_hosted_idea(self.home, self.catalog)
        self.archive = self.root / 'plugin.zip'
        self.write_plugin()

    def write_plugin(self, build='262.10315.125', extra=None):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as jar:
            jar.writestr('kast-hosted-query.properties', f'ideBuild={build}\nkotlinBuild={build}-IJ\n')
        with zipfile.ZipFile(self.archive, 'w') as bundle:
            bundle.writestr('kast-ide-hosted/lib/hosted.jar', inner.getvalue())
            if extra:
                bundle.writestr(extra, 'foreign')

    def reject(self, condition, operation):
        with self.assertRaises(FixtureRejected) as result:
            operation()
        self.assertIs(result.exception.condition, condition)

    def test_hosted_identity_is_admitted_independently_of_installed_worker_pin(self):
        self.assertEqual(self.idea.build, '262.10315.125')

    def test_wrong_idea_build_rejects(self):
        self.metadata.write_text(json.dumps({'buildNumber': '262.9437.185'}))
        self.reject(FixtureFailure.IDEA_BUILD, lambda: admit_hosted_idea(self.home, self.catalog))

    def test_compile_only_sdk_rejects(self):
        self.idea.java.unlink()
        self.reject(FixtureFailure.IDEA_LAUNCHER, lambda: admit_hosted_idea(self.home, self.catalog))

    def test_plugin_compiled_for_other_sdk_rejects_before_staging(self):
        self.write_plugin(build='262.9437.185')
        destination = self.root / 'plugins'
        self.reject(FixtureFailure.PLUGIN_IDENTITY,
                    lambda: stage_hosted_plugin(self.archive, destination, self.idea))
        self.assertFalse(destination.exists())

    def test_archive_cannot_escape_private_plugins_directory(self):
        self.write_plugin(extra='kast-ide-hosted/../../foreign')
        destination = self.root / 'plugins'
        self.reject(FixtureFailure.PLUGIN_ARCHIVE,
                    lambda: stage_hosted_plugin(self.archive, destination, self.idea))
        self.assertFalse(destination.exists())
        self.assertFalse((self.root / 'foreign').exists())

    def test_existing_plugin_directory_cannot_be_overwritten(self):
        destination = self.root / 'plugins'
        destination.mkdir()
        self.reject(FixtureFailure.FIXTURE_OWNERSHIP,
                    lambda: stage_hosted_plugin(self.archive, destination, self.idea))

    def test_only_named_transient_readiness_states_can_retry(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('hosted_runner', Path(__file__).with_name('run-hosted-acceptance-fixture.py'))
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        self.assertTrue(runner.pending_readiness({'failure': 'PROJECT_ADMISSION_REJECTED', 'detail': 'GRADLE_MODEL_UNAVAILABLE'}))
        self.assertTrue(runner.pending_readiness({'failure': 'INDEXING'}))
        self.assertTrue(runner.pending_readiness({'failure': 'PROJECT_ADMISSION_REJECTED', 'detail': 'DUMB_MODE'}))
        for document in ({}, {'failure': 'DIRTY_DOCUMENTS'}, {'failure': 'PROJECT_ADMISSION_REJECTED', 'detail': 'WRONG_OWNER'}):
            self.assertFalse(runner.pending_readiness(document))

    def test_preparation_has_private_paths_and_claims_no_native_success(self):
        repo = Path(__file__).resolve().parent.parent
        with AcceptanceEnvironment({'python3': Path(sys.executable)}) as isolation:
            prepared = prepare_hosted_fixture(isolation, repo, self.idea, self.archive)
            receipt = json.loads(prepared.input_receipt.read_text())
            self.assertEqual(receipt['nativeAcceptance'], 'not-run')
            self.assertEqual(prepared.environment['HOME'], str(isolation.root / 'home'))
            self.assertEqual(prepared.command, (str(self.idea.launcher), str(prepared.workspace)))
            self.assertTrue(all(Path(path).is_relative_to(isolation.root)
                                for path in receipt['writableIdePaths'].values()))
            self.assertIn('-Didea.config.path=' + str(isolation.root / 'ide/config'),
                          Path(prepared.environment['IDEA_VM_OPTIONS']).read_text())
            self.assertNotIn('NativeChangeTarget', prepared.input_receipt.read_text())
            trust = (isolation.root / 'ide/config/options/trusted-paths.xml').read_text()
            self.assertIn('TRUSTED_PROJECT_PATHS', trust)
            self.assertEqual(trust.count('<entry '), 1)
            self.assertNotIn('trust.all.projects', Path(prepared.environment['IDEA_VM_OPTIONS']).read_text())
            isolation.mark_passed()


if __name__ == '__main__':
    unittest.main()
