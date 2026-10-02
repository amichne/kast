from dataclasses import asdict, dataclass
import json
from pathlib import Path
import hashlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('host_installation', Path(__file__).with_name('host-installation.py'))
host = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = host
spec.loader.exec_module(host)


@dataclass(frozen=True)
class ContractFixture:
    type: str = 'HOSTED_CONTRACT'
    runtimeProtocolIdentity: str = 'kast.ide-hosted.runtime.v2'
    operationRegistryDigest: str = 'sha256:' + 'a' * 64
    wireSchemaDigest: str = 'sha256:' + 'b' * 64
    capabilities: tuple[str, ...] = ('query.run',)


@dataclass(frozen=True)
class ArtifactFixture:
    fileName: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class HostReleaseFixture:
    hostedPluginVersion: str
    artifact: ArtifactFixture
    supportedIntellijReleaseLine: str = '263'
    type: str = 'HOST_RELEASE'
    providedHostedContract: ContractFixture = ContractFixture()
    sourceRevision: str = '1' * 40


@dataclass(frozen=True)
class ControlFixture:
    productVersion: str
    requiredHostedContract: ContractFixture = ContractFixture()


class HostInstallationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.plugins = self.root / 'idea-profile/plugins'
        self.control = self.root / 'control'
        self.control.mkdir()
        (self.control / 'version').write_text('C2')
        self.control_identity = (self.control.stat().st_ino, (self.control / 'version').read_bytes())
        self.payload = self.make_payload('2.0.0')

    def make_payload(self, version):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('META-INF/plugin.xml', '<idea-plugin><id>io.github.amichne.kast.ide-hosted</id>'
                             f'<version>{version}</version><idea-version since-build="263" until-build="263.*"/>'
                             '</idea-plugin>')
        path = self.root / ('host-' + version + '.zip')
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('kast-ide-hosted/lib/host.jar', jar.getvalue())
        return host.HostedPluginPayload(path, hashlib.sha256(path.read_bytes()).hexdigest(), version, '263.123.1')

    def test_pair_admits_different_versions_only_by_exact_contract(self):
        record = self.root / 'release.json'
        record.write_text(json.dumps(asdict(HostReleaseFixture(self.payload.version,
            ArtifactFixture(self.payload.archive.name, 'sha256:' + self.payload.sha256, self.payload.archive.stat().st_size)))))
        control = self.root / 'control.json'
        control.write_text(json.dumps(asdict(ControlFixture('3.0.0'))))
        admitted = host.admit_host_release(record, self.payload, control)
        self.assertEqual('kast.ide-hosted.runtime.v2', admitted.runtime_protocol_identity)
        control.write_text(json.dumps(asdict(ControlFixture('3.0.0', ContractFixture(wireSchemaDigest='sha256:' + 'c' * 64)))))
        with self.assertRaises(host.Rejected) as rejected:
            host.admit_host_release(record, self.payload, control)
        self.assertEqual(host.Failure.COMPATIBILITY_REJECTED, rejected.exception.failure)
        self.assertFalse(self.plugins.exists())
        self.assert_control_unchanged()

    def test_capability_order_is_part_of_exact_contract_equality(self):
        provided = ContractFixture(capabilities=('workspace.lifecycle', 'query.run'))
        record = self.root / 'release.json'
        record.write_text(json.dumps(asdict(HostReleaseFixture(self.payload.version,
            ArtifactFixture(self.payload.archive.name, 'sha256:' + self.payload.sha256,
                            self.payload.archive.stat().st_size), providedHostedContract=provided))))
        control = self.root / 'control.json'
        control.write_text(json.dumps(asdict(ControlFixture('3.0.0', provided))))
        host.admit_host_release(record, self.payload, control)
        reordered = ContractFixture(capabilities=('query.run', 'workspace.lifecycle'))
        control.write_text(json.dumps(asdict(ControlFixture('3.0.0', reordered))))
        with self.assertRaises(host.Rejected) as rejected:
            host.admit_host_release(record, self.payload, control)
        self.assertEqual(host.Failure.COMPATIBILITY_REJECTED, rejected.exception.failure)
        self.assertFalse(self.plugins.exists())
        self.assert_control_unchanged()

    def assert_control_unchanged(self):
        self.assertEqual(self.control_identity, (self.control.stat().st_ino, (self.control / 'version').read_bytes()))

    def test_install_independent_host_preserves_control(self):
        result = host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual('ACTIVATED', result.type)
        self.assertEqual('2.0.0', result.hostedPluginVersion)
        self.assertTrue(result.restartRequired)
        self.assertTrue((self.plugins / 'kast-ide-hosted/lib/host.jar').is_file())
        self.assert_control_unchanged()

    def test_dry_run_and_checksum_rejection_do_not_create_plugin_profile(self):
        result = host.install(host.HostInstallRequest(self.payload, self.plugins, host.HostInstallMode.PLAN))
        self.assertEqual('PLANNED', result.type)
        self.assertFalse(self.plugins.exists())
        bad = host.HostedPluginPayload(self.payload.archive, '0' * 64, self.payload.version, self.payload.idea_build)
        with self.assertRaises(host.Rejected) as rejected:
            host.install(host.HostInstallRequest(bad, self.plugins))
        self.assertEqual(host.Failure.PAYLOAD_REJECTED, rejected.exception.failure)
        self.assertFalse(self.plugins.exists())
        self.assert_control_unchanged()

    def test_foreign_plugin_symlink_is_rejected_without_following_it(self):
        self.plugins.mkdir(parents=True)
        (self.plugins / 'kast-ide-hosted').symlink_to(self.control, target_is_directory=True)
        with self.assertRaises(host.Rejected) as rejected:
            host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assert_control_unchanged()

    def test_replacement_move_failure_restores_exact_prior_plugin(self):
        host.install(host.HostInstallRequest(self.make_payload('1.0.0'), self.plugins))
        destination = self.plugins / 'kast-ide-hosted'
        identity = host.Identity.observe(destination)
        bytes_before = (destination / 'lib/host.jar').read_bytes()
        original_rename = Path.rename
        def fail_candidate(path, target):
            if path.name.startswith('host-candidate-') and target == destination:
                raise OSError('controlled replacement failure')
            return original_rename(path, target)
        with patch.object(Path, 'rename', fail_candidate):
            with self.assertRaises(host.Rejected) as rejected:
                host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual(host.Failure.FILESYSTEM_REJECTED, rejected.exception.failure)
        self.assertEqual(identity, host.Identity.observe(destination))
        self.assertEqual(bytes_before, (destination / 'lib/host.jar').read_bytes())
        self.assert_control_unchanged()

    def test_failed_restoration_preserves_receipt_and_prior_files(self):
        host.install(host.HostInstallRequest(self.make_payload('1.0.0'), self.plugins))
        destination = self.plugins / 'kast-ide-hosted'
        original_rename = Path.rename
        def fail_moves(path, target):
            if target == destination and path.name.startswith(('host-candidate-', 'host-prior-')):
                raise OSError('controlled restoration failure')
            return original_rename(path, target)
        with patch.object(Path, 'rename', fail_moves):
            with self.assertRaises(host.Rejected) as rejected:
                host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual(host.Failure.RECOVERY_REQUIRED, rejected.exception.failure)
        retained = self.plugins.parent / '.kast-plugin-recovery'
        self.assertTrue((retained / 'host-install.json').exists())
        self.assertEqual(1, len(list(retained.glob('host-prior-*'))))
        self.assert_control_unchanged()


if __name__ == '__main__':
    unittest.main()
