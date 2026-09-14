#!/usr/bin/env python3
"""Owned fake-installer checks; these are not released-product or native evidence."""
from dataclasses import asdict, dataclass
import hashlib
import io
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import tarfile
import zipfile

from acceptance_environment import AcceptanceEnvironment
from acceptance_idea import digest
from released_acceptance_product import (ReleaseFailure, ReleaseInputs, ReleaseRejected,
    admit_release, install_release, product_executable)


@dataclass(frozen=True)
class PayloadFile:
    path: str
    sha256: str
    mode: int


@dataclass(frozen=True)
class Manifest:
    schemaVersion: int
    semanticVersion: str
    installationRoot: str
    payloadIdentity: str
    controlSha256: str
    hostedPluginSha256: str
    codexHome: str
    configuration: str
    workspaceRegistry: str
    stateRoot: str
    payloadFiles: tuple[PayloadFile, ...]


@dataclass(frozen=True)
class IdeaMetadata:
    dataDirectoryName: str = 'IntelliJIdea262'


class ReleasedProductTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name).resolve()
        self.isolation = AcceptanceEnvironment({'bash': Path('/bin/bash')}, parent=self.base)
        self.root = self.isolation.root
        self.repo = self.base / 'repo'
        self.repo.mkdir()
        self.assets = self.base / 'assets'
        self.assets.mkdir()
        self.idea = SimpleNamespace(home=self.base / 'idea', build='262.1')
        (self.idea.home / 'Resources').mkdir(parents=True)
        (self.idea.home / 'Resources/product-info.json').write_text(json.dumps(asdict(IdeaMetadata())))
        self.control = self.assets / 'kast-control-v1.2.3-macos-aarch64.tar.gz'
        self.plugin = self.assets / 'kast-ide-hosted-v1.2.3-idea-262.zip'
        with tarfile.open(self.control, 'w:gz') as archive:
            info = tarfile.TarInfo('bin/kast')
            contents = b'#!/bin/sh\nexit 0\n'
            info.size, info.mode = len(contents), 0o755
            archive.addfile(info, io.BytesIO(contents))
        with zipfile.ZipFile(self.plugin, 'w') as archive:
            archive.writestr('kast-ide-hosted/lib/released.jar', b'original plugin fixture')
        for archive in (self.control, self.plugin):
            archive.with_name(archive.name + '.sha256').write_text(f'{digest(archive)}  {archive.name}\n')
        payload = hashlib.sha256((digest(self.control) + '\n' + digest(self.plugin) + '\n').encode()).hexdigest()
        self.product = self.root / 'installation/versions' / ('1.2.3-' + payload)
        self.plugins = self.root / 'home/Library/Application Support/JetBrains/IntelliJIdea262/plugins'
        self.template = self.base / 'template'
        for directory in ('bin', 'lib', 'share', 'config'):
            (self.template / directory).mkdir(parents=True)
        (self.template / 'bin/kast').write_bytes(contents)
        (self.template / 'bin/kast').chmod(0o755)
        (self.template / 'bin/kast-complete').write_text('#!/bin/sh\nexit 0\n')
        (self.template / 'bin/kast-complete').chmod(0o755)
        inventory = tuple(PayloadFile(path.relative_to(self.template).as_posix(), 'sha256:' + digest(path),
                                      path.stat().st_mode & 0o777) for path in sorted((self.template / 'bin').iterdir()))
        self.manifest = Manifest(2, '1.2.3', str(self.product), 'sha256:' + payload,
            'sha256:' + digest(self.control), 'sha256:' + digest(self.plugin), self.isolation.environment['CODEX_HOME'],
            str(self.product / 'config/environment'), str(self.product / 'config/workspaces.json'),
            str(self.product / 'state'), inventory)
        (self.template / 'installation.json').write_text(json.dumps(asdict(self.manifest)))
        self.installer = self.repo / 'install.sh'
        q = lambda value: shlex.quote(str(value))
        self.installer.write_text('#!/bin/bash\nset -eu\n'
            '[[ "$1" == --version && "$2" == 1.2.3 && "$3" == --idea-home ]]\n'
            f'[[ "$HOME" == {q(self.root / "home")} && "$KAST_ENABLE_APP_SERVER" == 0 ]]\n'
            f'/bin/mkdir -p {q(self.product.parent)} {q(self.root / "bin")} {q(self.plugins / "kast-ide-hosted/lib")}\n'
            f'/bin/cp -R {q(self.template)} {q(self.product)}\n'
            f'/bin/ln -s {q(self.product)} {q(self.root / "installation/current")}\n'
            f'/bin/ln -s {q(self.product / "bin/kast-complete")} {q(self.root / "bin/kast")}\n'
            f'/bin/echo -n "original plugin fixture" > {q(self.plugins / "kast-ide-hosted/lib/released.jar")}\n')
        self.release = ReleaseInputs('1.2.3', 'a' * 40, self.installer, digest(self.installer), self.control,
            digest(self.control), self.plugin, digest(self.plugin), 'IntelliJIdea262')

    def tearDown(self):
        self.temporary.cleanup()

    def test_supported_installer_uses_original_assets_and_installed_wrapper(self):
        admitted = install_release(self.isolation, self.release, self.idea)
        self.assertEqual(product_executable(self.product, self.root), self.product / 'bin/kast-complete')
        self.assertEqual(admitted.pluginsDirectory, str(self.plugins))
        self.assertEqual(admitted.installationManifestSha256, digest(self.product / 'installation.json'))
        self.assertEqual(digest(self.root / 'released-assets' / self.control.name), digest(self.control))
        self.assertFalse((self.root / 'product').exists())

    def test_archive_checksum_or_dirty_source_cannot_be_admitted(self):
        source = SimpleNamespace(commit='a' * 40, clean=True)
        result = subprocess.CompletedProcess([], 0, stdout=source.commit + '\n')
        with patch('released_acceptance_product.subprocess.run', return_value=result):
            self.assertEqual(admit_release(self.repo, self.assets, '1.2.3', self.idea, source).commit, source.commit)
            self.control.with_name(self.control.name + '.sha256').write_text('wrong\n')
            with self.assertRaises(ReleaseRejected) as rejected:
                admit_release(self.repo, self.assets, '1.2.3', self.idea, source)
            self.assertEqual(rejected.exception.failure, ReleaseFailure.ASSETS)
            source.clean = False
            with self.assertRaises(ReleaseRejected) as rejected:
                admit_release(self.repo, self.assets, '1.2.3', self.idea, source)
            self.assertEqual(rejected.exception.failure, ReleaseFailure.SOURCE)

    def test_installer_cannot_replace_original_control_bytes_and_rehash_manifest(self):
        (self.template / 'bin/kast').write_text('#!/bin/sh\nexit 1\n')
        altered = tuple(PayloadFile(item.path, 'sha256:' + digest(self.template / item.path), item.mode)
                        for item in self.manifest.payloadFiles)
        from dataclasses import replace
        (self.template / 'installation.json').write_text(json.dumps(asdict(replace(self.manifest, payloadFiles=altered))))
        with self.assertRaises(ReleaseRejected) as rejected:
            install_release(self.isolation, self.release, self.idea)
        self.assertEqual(rejected.exception.failure, ReleaseFailure.PAYLOAD)

    def test_outside_product_and_changed_manifest_refused(self):
        install_release(self.isolation, self.release, self.idea)
        with self.assertRaises(ReleaseRejected):
            product_executable(self.product, Path('/'))
        with self.assertRaises(ReleaseRejected):
            product_executable(self.template, self.root)
        (self.product / 'installation.json').write_text('{}')  # Deliberately malformed ownership witness.
        with self.assertRaises(ReleaseRejected):
            product_executable(self.product, self.root)

    def test_installer_target_symlink_cannot_escape_owned_root(self):
        self.product.parent.mkdir(parents=True)
        self.product.symlink_to(self.template, target_is_directory=True)
        with self.assertRaises(ReleaseRejected):
            install_release(self.isolation, self.release, self.idea)

    def test_source_built_product_keeps_original_command(self):
        product = self.root / 'product'
        shutil.copytree(self.template, product)
        self.assertEqual(product_executable(product, self.root), product / 'bin/kast')
        with self.assertRaises(ReleaseRejected):
            product_executable(self.template, self.root)


if __name__ == '__main__':
    unittest.main()
