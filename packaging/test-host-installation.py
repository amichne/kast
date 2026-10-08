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
    runtimeProtocolIdentity: str = 'kast.ide-hosted.runtime.v3'
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
    def test_completed_host_retains_exact_active_inventory_for_owned_removal(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        self.assertTrue(path.exists())
        receipt = json.loads(path.read_text())
        self.assertEqual('HOST_ACTIVE', receipt['type'])
        self.assertEqual(str(self.plugins / 'kast-ide-hosted'), receipt['destination'])
        self.assertEqual(self.payload.version, receipt['version'])
        self.assertEqual(self.payload.sha256, receipt['sha256'])
        self.assertEqual(['lib', 'lib/host.jar'], [item['path'] for item in receipt['inventory']])
        self.assertEqual(['DIRECTORY', 'FILE'], [item['type'] for item in receipt['inventory']])
        self.assert_control_unchanged()

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
        self.assertEqual('kast.ide-hosted.runtime.v3', admitted.runtime_protocol_identity)
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

    def test_owned_host_removal_preserves_other_plugins_and_control_and_retries(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        other = self.plugins / 'another-plugin'
        other.mkdir()
        sentinel = other / 'retain'
        sentinel.write_bytes(b'foreign plugin')
        result = host.remove_host(self.plugins)
        self.assertEqual({'type': 'REMOVED', 'plugin': str(self.plugins / 'kast-ide-hosted')}, json.loads(host.encode(result)))
        self.assertFalse((self.plugins / 'kast-ide-hosted').exists())
        self.assertFalse((self.plugins.parent / '.kast-plugin-recovery').exists())
        self.assertEqual(b'foreign plugin', sentinel.read_bytes())
        self.assertEqual(result, host.remove_host(self.plugins))
        self.assert_control_unchanged()

    def test_absent_intended_host_root_requires_no_profile_or_lock_effects(self):
        before = tuple(sorted(path.relative_to(self.root).as_posix() for path in self.root.rglob('*')))
        result = host.remove_host(self.plugins)
        self.assertEqual({'type': 'REMOVED', 'plugin': str(self.plugins / 'kast-ide-hosted')}, json.loads(host.encode(result)))
        self.assertEqual(before, tuple(sorted(path.relative_to(self.root).as_posix() for path in self.root.rglob('*'))))
        self.assert_control_unchanged()

    def test_absent_host_target_with_foreign_existing_ancestor_is_rejected(self):
        before = tuple(sorted(path.relative_to(self.root).as_posix() for path in self.root.rglob('*')))
        with patch.object(host.os, 'getuid', return_value=os.getuid() + 1):
            with self.assertRaises(host.Rejected) as rejected:
                host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertEqual(before, tuple(sorted(path.relative_to(self.root).as_posix() for path in self.root.rglob('*'))))

    def test_retry_removes_exact_empty_private_retained_container(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        retained = self.plugins.parent / '.kast-plugin-recovery'
        original = Path.rmdir
        def interrupted(path):
            if path == retained:
                raise OSError('controlled final container retirement failure')
            return original(path)
        with patch.object(Path, 'rmdir', interrupted):
            with self.assertRaises(host.Rejected) as rejected:
                host.remove_host(self.plugins)
        self.assertEqual(host.Failure.FILESYSTEM_REJECTED, rejected.exception.failure)
        self.assertFalse((self.plugins / 'kast-ide-hosted').exists())
        self.assertEqual([], list(retained.iterdir()))
        self.assertEqual('REMOVED', host.remove_host(self.plugins).type)
        self.assertFalse(retained.exists())
        self.assert_control_unchanged()

    def test_unproven_empty_retained_container_mode_is_protected(self):
        self.plugins.mkdir(parents=True)
        retained = self.plugins.parent / '.kast-plugin-recovery'
        retained.mkdir(mode=0o755)
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertTrue(retained.exists())
        self.assert_control_unchanged()

    def test_retained_symlink_cannot_authorize_foreign_container_removal(self):
        self.plugins.mkdir(parents=True)
        foreign = self.root / 'foreign-retained'
        foreign.mkdir(mode=0o700)
        retained = self.plugins.parent / '.kast-plugin-recovery'
        retained.symlink_to(foreign, target_is_directory=True)
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertTrue(foreign.exists())
        self.assertTrue(retained.is_symlink())
        self.assert_control_unchanged()

    def test_empty_retained_identity_is_revalidated_before_removal(self):
        self.plugins.mkdir(parents=True)
        retained = self.plugins.parent / '.kast-plugin-recovery'
        retained.mkdir(mode=0o700)
        original = host.admit_removal_root
        calls = 0
        def change_retained_mode(root):
            nonlocal calls
            calls += 1
            original(root)
            if calls == 2:
                retained.chmod(0o755)
        with patch.object(host, 'admit_removal_root', change_retained_mode):
            with self.assertRaises(host.Rejected) as rejected:
                host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertTrue(retained.exists())
        self.assert_control_unchanged()

    def test_altered_owned_artifact_is_rejected_before_any_removal(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        jar = self.plugins / 'kast-ide-hosted/lib/host.jar'
        jar.write_bytes(b'changed after install')
        receipt = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        before = receipt.read_bytes()
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertEqual(b'changed after install', jar.read_bytes())
        self.assertEqual(before, receipt.read_bytes())
        self.assert_control_unchanged()

    def test_additional_artifact_and_symlink_are_protected_before_removal(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        extra = self.plugins / 'kast-ide-hosted/lib/foreign'
        extra.symlink_to(self.control / 'version')
        jar = self.plugins / 'kast-ide-hosted/lib/host.jar'
        before = jar.read_bytes()
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertEqual(before, jar.read_bytes())
        self.assertTrue(extra.is_symlink())
        self.assert_control_unchanged()

    def test_partial_removal_resumes_from_retained_exact_inventory(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        (self.plugins / 'kast-ide-hosted/lib/host.jar').unlink()
        result = host.remove_host(self.plugins)
        self.assertEqual('REMOVED', result.type)
        self.assertFalse((self.plugins / 'kast-ide-hosted').exists())
        self.assert_control_unchanged()

    def test_failed_removal_keeps_inventory_for_next_attempt(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        library = self.plugins / 'kast-ide-hosted/lib'
        original_rmdir = Path.rmdir
        def fail_library(path):
            if path == library:
                raise OSError('controlled directory removal failure')
            return original_rmdir(path)
        with patch.object(Path, 'rmdir', fail_library):
            with self.assertRaises(host.Rejected) as rejected:
                host.remove_host(self.plugins)
        self.assertEqual(host.Failure.FILESYSTEM_REJECTED, rejected.exception.failure)
        self.assertTrue((self.plugins.parent / '.kast-plugin-recovery/host-install.json').exists())
        self.assertFalse((library / 'host.jar').exists())
        self.assertEqual('REMOVED', host.remove_host(self.plugins).type)
        self.assert_control_unchanged()

    def test_absent_host_does_not_erase_unproven_retained_candidate(self):
        retained = self.plugins.parent / '.kast-plugin-recovery'
        retained.mkdir(parents=True, mode=0o700)
        candidate = retained / 'host-candidate-unproven'
        candidate.mkdir()
        sentinel = candidate / 'retain'
        sentinel.write_bytes(b'unproven')
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.RECOVERY_REQUIRED, rejected.exception.failure)
        self.assertEqual(b'unproven', sentinel.read_bytes())
        self.assert_control_unchanged()

    def test_final_receipt_deletion_failure_can_retry_after_lock_retirement(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        receipt = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        original_unlink = Path.unlink
        def fail_receipt(path, *args, **kwargs):
            if path == receipt:
                raise OSError('controlled final receipt failure')
            return original_unlink(path, *args, **kwargs)
        with patch.object(Path, 'unlink', fail_receipt):
            with self.assertRaises(host.Rejected) as rejected:
                host.remove_host(self.plugins)
        self.assertEqual(host.Failure.FILESYSTEM_REJECTED, rejected.exception.failure)
        self.assertTrue(receipt.exists())
        self.assertFalse((self.plugins / 'kast-ide-hosted').exists())
        self.assertEqual('REMOVED', host.remove_host(self.plugins).type)
        self.assert_control_unchanged()

    def test_legacy_host_without_receipt_requires_verified_reinstallation(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        receipt = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        receipt.unlink()
        jar = self.plugins / 'kast-ide-hosted/lib/host.jar'
        before = jar.read_bytes()
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertEqual(before, jar.read_bytes())
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual('REMOVED', host.remove_host(self.plugins).type)
        self.assert_control_unchanged()

    def test_pending_recovery_and_foreign_retained_artifacts_are_rejected(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        retained = self.plugins.parent / '.kast-plugin-recovery'
        foreign = retained / 'host-prior-unproven'
        foreign.mkdir()
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.RECOVERY_REQUIRED, rejected.exception.failure)
        self.assertTrue((self.plugins / 'kast-ide-hosted/lib/host.jar').exists())
        self.assertTrue(foreign.exists())
        foreign.rmdir()
        (retained / 'host-install.json').write_bytes(b'{"type":"HOST_PREPARED"}')
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.RECOVERY_REQUIRED, rejected.exception.failure)
        self.assert_control_unchanged()

    def test_invalid_receipt_utf8_duplicate_fields_and_root_identity_are_rejected(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        receipt = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        original = receipt.read_bytes()
        wrong_root = json.loads(original)
        wrong_root['pluginRootIdentity']['inode'] += 1
        for invalid in (b'\xff', b'{"type":"HOST_ACTIVE","type":"HOST_ACTIVE"}', json.dumps(wrong_root).encode()):
            receipt.write_bytes(invalid)
            with self.assertRaises(host.Rejected) as rejected:
                host.remove_host(self.plugins)
            self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
            self.assertTrue((self.plugins / 'kast-ide-hosted/lib/host.jar').exists())
        receipt.write_bytes(original)
        self.assertEqual('REMOVED', host.remove_host(self.plugins).type)
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
        receipt_path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        receipt_before = receipt_path.read_bytes()
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
        self.assertEqual(receipt_before, receipt_path.read_bytes())
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
