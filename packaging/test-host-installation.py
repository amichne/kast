from dataclasses import asdict, dataclass, replace
import json
from pathlib import Path
import hashlib
import importlib.util
import io
from contextlib import redirect_stderr, redirect_stdout
import os
import shutil
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
    def signal_payload(self, version):
        old = self.make_payload(version)
        with zipfile.ZipFile(old.archive) as archive:
            data = archive.read('kast-ide-hosted/lib/host.jar')
        support = io.BytesIO()
        with zipfile.ZipFile(support, 'w') as archive:
            archive.writestr('unchanged.txt', b'unchanged support bytes')
        path = self.root / ('signal-' + version + '.zip')
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('kast-ide-hosted/lib/kast-ide-hosted.jar', data)
            archive.writestr('kast-ide-hosted/lib/unchanged.jar', support.getvalue())
        return host.HostedPluginPayload(
            path, hashlib.sha256(path.read_bytes()).hexdigest(), version, '263.123.1'
        )

    def signal_record(self):
        host.install(host.HostInstallRequest(self.signal_payload('2.0.0'), self.plugins))
        record = host.prepare_managed(
            host.HostInstallRequest(self.signal_payload('3.0.0'), self.plugins), self.managed_slot()
        )
        return record

    def test_reload_signal_preserves_exact_file_facts_and_changes_millisecond_stamp(self):
        record = self.signal_record()
        with host.managed_lock(record) as lease:
            host.commit_managed(record)
            marker = Path(record.destination) / 'lib/kast-ide-hosted.jar'
            content = marker.read_bytes()
            os.utime(marker, ns=(1600000000000000000, 1600000000000000000))
            before = marker.stat()
            support = Path(record.destination) / 'lib/unchanged.jar'
            support_before = support.stat().st_mtime_ns
            evidence = host.stamp_reload_signal(lease, host.ReloadSignalTarget.SELECTED)
            after = marker.stat()
            self.assertEqual(
                (
                    before.st_dev,
                    before.st_ino,
                    before.st_uid,
                    before.st_mode,
                    before.st_size,
                    before.st_atime_ns,
                ),
                (
                    after.st_dev,
                    after.st_ino,
                    after.st_uid,
                    after.st_mode,
                    after.st_size,
                    after.st_atime_ns,
                ),
            )
            self.assertGreater(
                evidence.mtimeMillis,
                max(
                    evidence.beforeMtimeMillis,
                    evidence.retainedMtimeMillis,
                    evidence.observedMillis,
                ),
            )
            self.assertEqual(after.st_mtime_ns // 1000000, evidence.mtimeMillis)
            self.assertEqual(hashlib.sha256(content).hexdigest(), evidence.sha256)
            self.assertEqual(content, marker.read_bytes())
            self.assertEqual(support_before, support.stat().st_mtime_ns)
            host.admit_managed_candidate(record, Path(record.destination))

    def test_restored_prior_signal_repeats_after_idempotent_file_rollback(self):
        record = self.signal_record()
        with host.managed_lock(record) as lease:
            host.commit_managed(record)
            selected = host.stamp_reload_signal(lease, host.ReloadSignalTarget.SELECTED)
            host.rollback_managed(record)
            first = host.stamp_reload_signal(lease, host.ReloadSignalTarget.RESTORED_PRIOR)
            host.rollback_managed(record)
            second = host.stamp_reload_signal(lease, host.ReloadSignalTarget.RESTORED_PRIOR)
            self.assertEqual(selected.mtimeMillis, first.retainedMtimeMillis)
            self.assertGreater(second.mtimeMillis, first.mtimeMillis)
            self.assertEqual(first.identity, second.identity)
            self.assertEqual(first.sha256, second.sha256)

    def test_reload_signal_rejects_released_or_missing_lease_before_effects(self):
        record = self.signal_record()
        with host.managed_lock(record) as lease:
            host.commit_managed(record)
        for stale in (lease, None):
            with self.subTest(stale=stale), patch.object(host.os, 'utime') as effect:
                with self.assertRaises(host.ReloadSignalRejected) as rejected:
                    host.stamp_reload_signal(stale, host.ReloadSignalTarget.SELECTED)
                self.assertEqual(host.ReloadSignalFailure.LEASE_NOT_HELD, rejected.exception.reason)
                effect.assert_not_called()

    def test_public_lease_construction_rejects_an_actual_unlocked_lock_descriptor(self):
        record = self.signal_record()
        with host.managed_lock(record):
            host.commit_managed(record)
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.lock'
        with path.open('r+') as unlocked, patch.object(host.os, 'utime') as effect:
            self.assertEqual(record.lock.identity.inode, os.fstat(unlocked.fileno()).st_ino)
            with self.assertRaises(TypeError):
                host.HeldHostLease(record, unlocked)
            effect.assert_not_called()

    def test_reload_signal_rejects_symlink_and_changed_hash_before_stamp(self):
        record = self.signal_record()
        with host.managed_lock(record) as lease:
            host.commit_managed(record)
            marker = Path(record.destination) / 'lib/kast-ide-hosted.jar'
            saved = marker.read_bytes()
            marker.write_bytes(b'changed unadmitted JAR bytes')
            with (
                patch.object(host.os, 'utime') as effect,
                self.assertRaises(host.ReloadSignalRejected),
            ):
                host.stamp_reload_signal(lease, host.ReloadSignalTarget.SELECTED)
            effect.assert_not_called()
            marker.write_bytes(saved)
            retained = marker.with_suffix('.retained')
            marker.rename(retained)
            marker.symlink_to(retained)
            with (
                patch.object(host.os, 'utime') as effect,
                self.assertRaises(host.ReloadSignalRejected) as rejected,
            ):
                host.stamp_reload_signal(lease, host.ReloadSignalTarget.SELECTED)
            self.assertEqual(host.ReloadSignalFailure.MARKER_UNPROVEN, rejected.exception.reason)
            effect.assert_not_called()

    def test_reload_signal_rejects_timestamp_precision_alias(self):
        record = self.signal_record()
        with host.managed_lock(record) as lease:
            host.commit_managed(record)
            marker = Path(record.destination) / 'lib/kast-ide-hosted.jar'
            before = marker.stat()
            real = os.utime

            def aliased(descriptor, *, ns):
                real(descriptor, ns=(ns[0], before.st_mtime_ns))

            with (
                patch.object(host.os, 'utime', aliased),
                self.assertRaises(host.ReloadSignalRejected) as rejected,
            ):
                host.stamp_reload_signal(lease, host.ReloadSignalTarget.SELECTED)
            self.assertEqual(host.ReloadSignalFailure.STAMP_PRECISION, rejected.exception.reason)

    def test_reload_signal_rejects_mode_change_after_descriptor_stamp(self):
        record = self.signal_record()
        with host.managed_lock(record) as lease:
            host.commit_managed(record)
            marker = Path(record.destination) / 'lib/kast-ide-hosted.jar'
            marker.chmod(0o644)
            real = os.utime

            def changed(descriptor, *, ns):
                real(descriptor, ns=ns)
                os.fchmod(descriptor, 0o600)

            with (
                patch.object(host.os, 'utime', changed),
                self.assertRaises(host.ReloadSignalRejected) as rejected,
            ):
                host.stamp_reload_signal(lease, host.ReloadSignalTarget.SELECTED)
            self.assertEqual(host.ReloadSignalFailure.FILE_CHANGED, rejected.exception.reason)

    def test_reload_signal_millisecond_range_is_finite(self):
        limit = (2**63 - 1) // 1000000
        self.assertEqual(12, host.next_reload_signal_millis(10, 11, 9))
        for values in ((limit, 1, 1), (1, limit, 1), (1, 1, -1), (True, 1, 1)):
            with (
                self.subTest(values=values),
                self.assertRaises(host.ReloadSignalRejected) as rejected,
            ):
                host.next_reload_signal_millis(*values)
            self.assertEqual(host.ReloadSignalFailure.STAMP_RANGE, rejected.exception.reason)

    def test_reload_signal_rejects_replacement_without_touching_the_new_inode(self):
        record = self.signal_record()
        with host.managed_lock(record) as lease:
            host.commit_managed(record)
            marker = Path(record.destination) / 'lib/kast-ide-hosted.jar'
            content = marker.read_bytes()
            real = os.utime
            replacement = []

            def replaced(descriptor, *, ns):
                real(descriptor, ns=ns)
                marker.rename(marker.with_suffix('.displaced'))
                marker.write_bytes(content)
                replacement.append(marker.stat().st_mtime_ns)

            with (
                patch.object(host.os, 'utime', replaced),
                self.assertRaises(host.ReloadSignalRejected) as rejected,
            ):
                host.stamp_reload_signal(lease, host.ReloadSignalTarget.SELECTED)
            self.assertEqual(host.ReloadSignalFailure.FILE_CHANGED, rejected.exception.reason)
            self.assertEqual(replacement[0], marker.stat().st_mtime_ns)
            self.assertEqual(content, marker.read_bytes())

    def test_managed_lock_excludes_the_existing_host_installer(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        with host.managed_lock(record):
            with self.assertRaises(BlockingIOError):
                host.install(host.HostInstallRequest(self.make_payload('4.0.0'), self.plugins))
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))

    def test_managed_first_absence_lock_stays_owned_until_rollback_cleanup(self):
        self.plugins.parent.mkdir()
        slot = self.managed_slot()
        record = host.prepare_managed(host.HostInstallRequest(self.payload, self.plugins), slot)
        retained = self.plugins.parent / '.kast-plugin-recovery'
        self.assertFalse(retained.exists())
        retained.mkdir(mode=0o700)
        record = host.bind_managed_lock(record, host.Identity.observe(retained))
        self.assertIsNone(record.retainedRootIdentity)
        with host.managed_lock(record):
            host.commit_managed(record)
            host.rollback_managed(record)
            host.seal_managed(record, host.ManagedHostTerminalPhase.RESTORED)
            self.assertTrue((retained / 'host-install.lock').is_file())
        host.finish_managed_lock(record, host.ManagedHostTerminalPhase.RESTORED)
        host.finish_managed_lock(record, host.ManagedHostTerminalPhase.RESTORED)
        self.assertEqual([], list(slot.iterdir()))
        self.assertEqual([], list(retained.iterdir()))
        retained.rmdir()
        self.assertFalse(self.plugins.exists())

    def test_managed_lock_preserves_foreign_canonical_lock(self):
        self.plugins.parent.mkdir()
        record = host.prepare_managed(host.HostInstallRequest(self.payload, self.plugins), self.managed_slot())
        retained = self.plugins.parent / '.kast-plugin-recovery'
        retained.mkdir(mode=0o700)
        record = host.bind_managed_lock(record, host.Identity.observe(retained))
        foreign = retained / 'host-install.lock'
        foreign.write_bytes(b'foreign lock contents')
        with self.assertRaises(host.Rejected):
            with host.managed_lock(record):
                self.fail('foreign lock must reject')
        self.assertEqual(b'foreign lock contents', foreign.read_bytes())

    def test_managed_reservation_blocks_ordinary_owner_after_lease_exit(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        slot = self.managed_slot()
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), slot)
        with host.managed_lock(record):
            pass
        canonical = self.plugins.parent / '.kast-plugin-recovery/host-install.lock'
        self.assertEqual(2, canonical.stat().st_nlink)
        with self.assertRaises(host.Rejected) as rejected:
            host.install(host.HostInstallRequest(self.make_payload('4.0.0'), self.plugins))
        self.assertEqual(host.Failure.RECOVERY_REQUIRED, rejected.exception.failure)
        with self.assertRaises(host.Rejected):
            host.remove_host_files(self.plugins)
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))
        host.seal_managed(record, host.ManagedHostTerminalPhase.RESTORED)
        host.finish_managed_lock(record, host.ManagedHostTerminalPhase.RESTORED)
        host.finish_managed_lock(record, host.ManagedHostTerminalPhase.RESTORED)
        self.assertEqual(1, canonical.stat().st_nlink)
        self.assertEqual([], list(slot.iterdir()))
        host.install(host.HostInstallRequest(self.make_payload('4.0.0'), self.plugins))

    def test_ordinary_owner_lock_excludes_managed_owner(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        canonical = self.plugins.parent / '.kast-plugin-recovery/host-install.lock'
        with canonical.open('r+') as lock:
            host.fcntl.flock(lock, host.fcntl.LOCK_EX | host.fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                with host.managed_lock(record):
                    self.fail('ordinary owner holds the lock')

    def test_managed_activation_keeps_created_canonical_lock_and_releases_reservation(self):
        self.plugins.parent.mkdir()
        slot = self.managed_slot()
        record = host.prepare_managed(host.HostInstallRequest(self.payload, self.plugins), slot)
        retained = self.plugins.parent / '.kast-plugin-recovery'
        retained.mkdir(mode=0o700)
        record = host.bind_managed_lock(record, host.Identity.observe(retained))
        with host.managed_lock(record):
            host.admit_managed_prepared(record)
            host.commit_managed(record)
            host.seal_managed(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        host.finish_managed_lock(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        with host.managed_lock(record):
            self.assertEqual(2, (retained / 'host-install.lock').stat().st_nlink)
        host.finish_managed_lock(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        canonical = retained / 'host-install.lock'
        self.assertEqual(record.lock.identity, host.owned_file(canonical, private=True))
        self.assertEqual(1, canonical.stat().st_nlink)
        self.assertEqual([], list(slot.iterdir()))

    def test_managed_prepared_admission_rejects_changed_prior_before_control(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        with host.managed_lock(record):
            changed = self.plugins / 'kast-ide-hosted/lib/host.jar'
            changed.write_bytes(b'foreign contents')
            with self.assertRaises(host.Rejected):
                host.admit_managed_prepared(record)
            self.assertEqual(b'foreign contents', changed.read_bytes())

    def test_managed_prepare_invalid_payload_leaves_no_unrecorded_artifacts(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        slot = self.managed_slot()
        bad = replace(self.make_payload('3.0.0'), sha256='0' * 64)
        with self.assertRaises(host.Rejected):
            host.prepare_managed(host.HostInstallRequest(bad, self.plugins), slot)
        self.assertEqual([], list(slot.iterdir()))
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))

    def test_managed_prepare_record_failure_reclaims_only_created_staging(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        slot = self.managed_slot()
        request = host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins)
        with patch.object(host, 'ManagedHostReplacement', side_effect=OSError('record unavailable')):
            with self.assertRaises(OSError):
                host.prepare_managed(request, slot)
        self.assertEqual([], list(slot.iterdir()))
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))

    def test_managed_prepare_receipt_write_failure_leaves_no_partial_preimage(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        slot = self.managed_slot()
        request = host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins)
        with patch.object(host.os, 'fsync', side_effect=OSError('receipt flush unavailable')):
            with self.assertRaises(OSError):
                host.prepare_managed(request, slot)
        self.assertEqual([], list(slot.iterdir()))
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))

    def test_managed_prepare_publication_failure_reclaims_exact_complete_artifacts(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        slot = self.managed_slot()
        request = host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins)
        with patch.object(host, 'sync', side_effect=OSError('journal sync unavailable')):
            with self.assertRaises(OSError):
                host.prepare_managed(request, slot)
        self.assertEqual([], list(slot.iterdir()))
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))

    def test_managed_prepare_preserves_ambiguous_changed_publication(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        slot = self.managed_slot()
        request = host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins)
        def change_receipt_then_fail(_):
            (slot / 'host-receipt').write_bytes(b'foreign evidence')
            raise OSError('journal sync unavailable')
        with patch.object(host, 'sync', side_effect=change_receipt_then_fail):
            with self.assertRaises(host.Rejected) as rejected:
                host.prepare_managed(request, slot)
        self.assertEqual(host.Failure.RECOVERY_REQUIRED, rejected.exception.failure)
        self.assertEqual(b'foreign evidence', (slot / 'host-receipt').read_bytes())
        self.assertTrue((slot / 'host-candidate').is_dir())
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))

    def test_managed_seal_repeats_only_after_selected_host_file_proof(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        host.commit_managed(record)
        with self.assertRaises(host.Rejected):
            host.seal_managed(record, host.ManagedHostTerminalPhase.RESTORED)
        self.assertTrue(Path(record.backup).exists())
        self.assertTrue(Path(record.previous.receipt).exists())
        host.seal_managed(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        host.seal_managed(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        self.assertEqual([], list(Path(record.candidate).parent.iterdir()))
        self.assert_control_unchanged()

    def assert_partial_managed_host_cleanup_keeps_retry(self, terminal, interruption):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        with host.managed_lock(record):
            host.commit_managed(record)
            if terminal is host.ManagedHostTerminalPhase.RESTORED:
                host.rollback_managed(record)
        active = host.inventory(self.plugins / 'kast-ide-hosted')
        remove = shutil.rmtree
        calls = []
        def interrupted(path, *arguments, **options):
            target = Path(path)
            files = [entry for entry in target.rglob('*') if entry.is_file()]
            if files:
                calls.append(target)
                files[0].unlink()
                raise interruption('controlled partial Host deletion')
            return remove(path, *arguments, **options)
        interrupted.avoids_symlink_attacks = remove.avoids_symlink_attacks
        output = io.StringIO()
        with patch.object(shutil, 'rmtree', interrupted), redirect_stderr(output):
            try:
                host.seal_managed(record, terminal)
            except (OSError, SystemExit):
                pass
        self.assertEqual(1, len(calls))
        self.assertEqual(Path(record.candidate).parent.parent, calls[0].parent)
        self.assertFalse(Path(record.backup).exists())
        self.assertFalse(Path(record.candidate).exists())
        if interruption is OSError:
            self.assertEqual({'event': 'kast_managed_host_cleanup', 'outcome': 'FAILED',
                              'reason': host.Failure.FILESYSTEM_REJECTED.value}, json.loads(output.getvalue()))
        else:
            self.assertEqual('', output.getvalue())
        host.finish_managed_lock(record, terminal)
        self.assertFalse((Path(record.candidate).parent / 'host-lock').exists())
        self.assertEqual(active, host.inventory(self.plugins / 'kast-ide-hosted'))
        self.assert_control_unchanged()

    def test_partial_prior_host_deletion_keeps_terminal_retry_and_lock_release(self):
        self.assert_partial_managed_host_cleanup_keeps_retry(host.ManagedHostTerminalPhase.ACTIVATION_PROVEN, OSError)

    def test_interrupted_candidate_deletion_keeps_terminal_retry_and_lock_release(self):
        self.assert_partial_managed_host_cleanup_keeps_retry(host.ManagedHostTerminalPhase.RESTORED, SystemExit)

    def test_terminal_host_cleanup_preserves_foreign_completed_storage(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        host.commit_managed(record)
        identity = record.previous.identity
        destination = Path(record.backup).parent.parent / f'completed-host-prior-{identity.device}-{identity.inode}-{identity.owner}'
        destination.mkdir()
        (destination / 'foreign').write_text('preserve')
        before = host.inventory(Path(record.backup))
        with self.assertRaises(host.Rejected) as rejected:
            host.seal_managed(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertEqual('preserve', (destination / 'foreign').read_text())
        self.assertEqual(before, host.inventory(Path(record.backup)))

    def test_host_retirement_native_move_never_replaces_a_racing_empty_directory(self):
        import host_directory_exchange as exchange
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        host.commit_managed(record)
        move = exchange.move_directory_noreplace
        targets = []
        def collision(source, destination):
            destination.mkdir()
            targets.append((destination, destination.stat().st_ino))
            return move(source, destination)
        before = host.inventory(Path(record.backup))
        with patch.object(exchange, 'move_directory_noreplace', collision):
            with self.assertRaises(host.Rejected) as rejected:
                host.seal_managed(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        self.assertEqual(host.Failure.FILESYSTEM_REJECTED, rejected.exception.failure)
        self.assertEqual(1, len(targets))
        self.assertEqual(targets[0][1], targets[0][0].stat().st_ino)
        self.assertEqual([], list(targets[0][0].iterdir()))
        self.assertEqual(before, host.inventory(Path(record.backup)))

    def test_managed_seal_resumes_after_previous_bytes_were_released(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        host.commit_managed(record)
        preimage = Path(record.previous.receipt)
        original = Path.unlink
        def fail_preimage_unlink(path, *arguments, **keywords):
            if path == preimage:
                raise OSError('terminal cleanup interrupted')
            return original(path, *arguments, **keywords)
        with patch.object(Path, 'unlink', fail_preimage_unlink), self.assertRaises(OSError):
            host.seal_managed(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        self.assertFalse(Path(record.backup).exists())
        self.assertTrue(preimage.exists())
        host.seal_managed(record, host.ManagedHostTerminalPhase.ACTIVATION_PROVEN)
        self.assertFalse(preimage.exists())
        self.assert_control_unchanged()

    @unittest.skipUnless(sys.platform == 'darwin', 'macOS atomic Host exchange')
    def test_managed_hot_reload_swaps_and_restores_complete_owned_host(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        previous_inode = (self.plugins / 'kast-ide-hosted').stat().st_ino
        receipt = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        previous_receipt = receipt.read_bytes()
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        host.commit_managed_reload(record)
        self.assertEqual(previous_inode, Path(record.backup).stat().st_ino)
        self.assertEqual(record.candidateIdentity.inode, (self.plugins / 'kast-ide-hosted').stat().st_ino)
        host.rollback_managed_reload(record)
        self.assertEqual(previous_inode, (self.plugins / 'kast-ide-hosted').stat().st_ino)
        self.assertEqual(previous_receipt, receipt.read_bytes())
        host.seal_managed(record, host.ManagedHostTerminalPhase.RESTORED)
        self.assert_control_unchanged()

    @unittest.skipUnless(sys.platform == 'darwin', 'macOS atomic Host exchange')
    def test_managed_hot_reload_recovers_after_swap_before_prior_move(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        original = Path.rename
        def fail_prior_move(path, destination):
            if path == Path(record.candidate):
                raise OSError('failure after atomic swap')
            return original(path, destination)
        with patch.object(Path, 'rename', fail_prior_move), self.assertRaises(OSError):
            host.commit_managed_reload(record)
        self.assertEqual(record.previous.identity.inode, Path(record.candidate).stat().st_ino)
        host.rollback_managed_reload(record)
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))
        host.seal_managed(record, host.ManagedHostTerminalPhase.RESTORED)
        self.assert_control_unchanged()

    def managed_slot(self):
        slot = self.root / 'managed/recovery/replacement'
        slot.mkdir(parents=True)
        return slot

    def test_managed_replacement_keeps_prior_bytes_until_proven_rollback(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = (self.plugins / 'kast-ide-hosted').stat().st_ino
        receipt_path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        receipt = receipt_path.read_bytes()
        replacement = self.make_payload('3.0.0')
        record = host.prepare_managed(host.HostInstallRequest(replacement, self.plugins), self.managed_slot())
        self.assertEqual(before, (self.plugins / 'kast-ide-hosted').stat().st_ino)
        self.assertEqual(receipt, receipt_path.read_bytes())
        self.assertEqual(record, host.decode_managed(asdict(record)))
        host.commit_managed(record)
        self.assertTrue(Path(record.backup).is_dir())
        self.assertNotEqual(before, (self.plugins / 'kast-ide-hosted').stat().st_ino)
        host.rollback_managed(record)
        self.assertEqual(before, (self.plugins / 'kast-ide-hosted').stat().st_ino)
        self.assertEqual(receipt, receipt_path.read_bytes())
        host.seal_managed(record, host.ManagedHostTerminalPhase.RESTORED)
        self.assertEqual([], list(Path(record.candidate).parent.iterdir()))
        self.assert_control_unchanged()

    def test_managed_first_install_restores_admitted_absence(self):
        self.plugins.parent.mkdir()
        record = host.prepare_managed(host.HostInstallRequest(self.payload, self.plugins), self.managed_slot())
        self.assertFalse(self.plugins.exists())
        host.commit_managed(record)
        self.assertTrue((self.plugins / 'kast-ide-hosted').is_dir())
        host.rollback_managed(record)
        self.assertFalse(self.plugins.exists())
        self.assertFalse((self.plugins.parent / '.kast-plugin-recovery').exists())
        host.seal_managed(record, host.ManagedHostTerminalPhase.RESTORED)
        self.assert_control_unchanged()

    def test_managed_candidate_tampering_rejects_before_active_host_changes(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        prior = host.inventory(self.plugins / 'kast-ide-hosted')
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        (Path(record.candidate) / 'lib/host.jar').write_bytes(b'foreign bytes')
        with self.assertRaises(host.Rejected):
            host.commit_managed(record)
        self.assertEqual(prior, host.inventory(self.plugins / 'kast-ide-hosted'))
        self.assertFalse(Path(record.backup).exists())

    def test_managed_failure_after_prior_move_can_restore_exact_previous_host(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        original = Path.rename
        def fail_candidate(path, destination):
            if path == Path(record.candidate):
                raise OSError('candidate exchange failed')
            return original(path, destination)
        with patch.object(Path, 'rename', fail_candidate), self.assertRaises(OSError):
            host.commit_managed(record)
        self.assertTrue(Path(record.backup).exists())
        host.rollback_managed(record)
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))
        host.seal_managed(record, host.ManagedHostTerminalPhase.RESTORED)
        self.assert_control_unchanged()

    def test_managed_rollback_preserves_a_foreign_changed_active_receipt(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        record = host.prepare_managed(host.HostInstallRequest(self.make_payload('3.0.0'), self.plugins), self.managed_slot())
        host.commit_managed(record)
        receipt = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        foreign = b'foreign receipt must survive'
        receipt.write_bytes(foreign)
        before = host.inventory(self.plugins / 'kast-ide-hosted')
        with self.assertRaises(host.Rejected):
            host.rollback_managed(record)
        self.assertEqual(foreign, receipt.read_bytes())
        self.assertEqual(before, host.inventory(self.plugins / 'kast-ide-hosted'))
        self.assertTrue(Path(record.backup).exists())

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

    def record_prior_device(self):
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        receipt = host.read_active_receipt(path)
        device = self.plugins.stat().st_dev + 1
        receipt = replace(receipt,
            pluginRootIdentity=replace(receipt.pluginRootIdentity, device=device),
            destinationIdentity=replace(receipt.destinationIdentity, device=device),
            inventory=tuple(replace(item, identity=replace(item.identity, device=device)) for item in receipt.inventory))
        path.write_text(host.encode(receipt))
        return receipt

    def test_device_change_reinstallation_requires_exact_remaining_inventory(self):
        host.install(host.HostInstallRequest(self.make_payload('1.0.0'), self.plugins))
        self.record_prior_device()
        result = host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual('ACTIVATED', result.type)
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        receipt = json.loads(path.read_text())
        self.assertEqual('2.0.0', receipt['version'])
        self.assertEqual(self.plugins.stat().st_dev, receipt['pluginRootIdentity']['device'])
        with zipfile.ZipFile(self.payload.archive) as archive:
            self.assertEqual(archive.read('kast-ide-hosted/lib/host.jar'),
                             (self.plugins / 'kast-ide-hosted/lib/host.jar').read_bytes())
        self.assert_control_unchanged()

    def test_device_change_owned_removal_keeps_other_plugins(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.record_prior_device()
        foreign = self.plugins / 'foreign-plugin'
        foreign.mkdir()
        sentinel = foreign / 'retain'
        sentinel.write_bytes(b'foreign')
        self.assertEqual('REMOVED', host.remove_host(self.plugins).type)
        self.assertFalse((self.plugins / 'kast-ide-hosted').exists())
        self.assertFalse((self.plugins.parent / '.kast-plugin-recovery').exists())
        self.assertEqual(b'foreign', sentinel.read_bytes())
        self.assert_control_unchanged()

    def test_absent_host_with_active_receipt_can_reinstall(self):
        host.install(host.HostInstallRequest(self.make_payload('1.0.0'), self.plugins))
        shutil.rmtree(self.plugins / 'kast-ide-hosted')
        result = host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual('ACTIVATED', result.type)
        self.assertEqual('2.0.0', host.read_active_receipt(
            self.plugins.parent / '.kast-plugin-recovery/host-install.json').version)
        self.assertTrue((self.plugins / 'kast-ide-hosted/lib/host.jar').is_file())
        self.assert_control_unchanged()

    def test_install_cli_replaces_changed_inventory_and_keeps_owned_removal(self):
        self.plugins = self.root / 'IDEA profile;$literal/plugins'
        record = self.root / 'release.json'
        record.write_text(json.dumps(asdict(HostReleaseFixture(self.payload.version,
            ArtifactFixture(self.payload.archive.name, 'sha256:' + self.payload.sha256, self.payload.archive.stat().st_size)))))
        arguments = ['host-installation.py', '--archive', str(self.payload.archive), '--sha256', self.payload.sha256,
                     '--version', self.payload.version, '--idea-build', self.payload.idea_build,
                     '--plugin-root', str(self.plugins), '--release-record', str(record)]
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(sys, 'argv', arguments), redirect_stdout(stdout), redirect_stderr(stderr):
            result = host.main()
        self.assertEqual(0, result)
        self.assertEqual('', stderr.getvalue())
        self.assertEqual({'type': 'ACTIVATED', 'hostedPluginVersion': '2.0.0',
                         'plugin': str(self.plugins / 'kast-ide-hosted'), 'restartRequired': True},
                         json.loads(stdout.getvalue()))
        self.record_prior_device()
        jar = self.plugins / 'kast-ide-hosted/lib/host.jar'
        jar.write_bytes(b'changed')
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(sys, 'argv', arguments), redirect_stdout(stdout), redirect_stderr(stderr):
            result = host.main()
        self.assertEqual(0, result)
        self.assertEqual('ACTIVATED', json.loads(stdout.getvalue())['type'])
        self.assertEqual('', stderr.getvalue())
        jar.write_bytes(b'changed')
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(sys, 'argv', ['host-installation.py', '--remove', '--plugin-root', str(self.plugins)]), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            result = host.main()
        self.assertEqual(1, result)
        self.assertEqual('', stderr.getvalue())
        self.assertEqual({'type': 'REJECTED', 'failure': 'OWNERSHIP_UNPROVEN'}, json.loads(stdout.getvalue()))
        self.assertEqual(b'changed', jar.read_bytes())
        self.assert_control_unchanged()

    def test_device_change_partial_removal_resumes_from_remaining_inventory(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.record_prior_device()
        (self.plugins / 'kast-ide-hosted/lib/host.jar').unlink()
        self.assertEqual('REMOVED', host.remove_host(self.plugins).type)
        self.assertFalse((self.plugins / 'kast-ide-hosted').exists())
        self.assertFalse((self.plugins.parent / '.kast-plugin-recovery').exists())
        self.assert_control_unchanged()

    def test_removal_rejects_nested_symlink_and_replacement_keeps_its_target(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.record_prior_device()
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        before = path.read_bytes()
        jar = self.plugins / 'kast-ide-hosted/lib/host.jar'
        jar.unlink()
        jar.symlink_to(self.control / 'version')
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertEqual(before, path.read_bytes())
        self.assertTrue(jar.is_symlink())
        self.assertEqual('ACTIVATED', host.install(host.HostInstallRequest(self.payload, self.plugins)).type)
        self.assertFalse(jar.is_symlink())
        self.assert_control_unchanged()

    def test_device_change_replacement_failure_restores_original_receipt(self):
        host.install(host.HostInstallRequest(self.make_payload('1.0.0'), self.plugins))
        self.record_prior_device()
        destination = self.plugins / 'kast-ide-hosted'
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        before = path.read_bytes()
        jar = destination / 'lib/host.jar'
        jar_before = jar.read_bytes()
        original = Path.rename
        def fail_candidate(source, target):
            if source.name.startswith('host-candidate-') and target == destination:
                raise OSError('controlled replacement failure')
            return original(source, target)
        with patch.object(Path, 'rename', fail_candidate):
            with self.assertRaises(host.Rejected) as rejected:
                host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual(host.Failure.FILESYSTEM_REJECTED, rejected.exception.failure)
        self.assertEqual(before, path.read_bytes())
        self.assertEqual(jar_before, jar.read_bytes())
        self.assertEqual(['host-install.json', 'host-install.lock'], sorted(item.name for item in path.parent.iterdir()))
        self.assert_control_unchanged()

    def test_absent_host_replacement_failure_restores_original_receipt(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.record_prior_device()
        destination = self.plugins / 'kast-ide-hosted'
        shutil.rmtree(destination)
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        before = path.read_bytes()
        with patch.object(Path, 'rename', side_effect=OSError('controlled replacement failure')):
            with self.assertRaises(host.Rejected) as rejected:
                host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual(host.Failure.FILESYSTEM_REJECTED, rejected.exception.failure)
        self.assertEqual(before, path.read_bytes())
        self.assertFalse(destination.exists())
        self.assertEqual(['host-install.json', 'host-install.lock'], sorted(item.name for item in path.parent.iterdir()))
        self.assertEqual('ACTIVATED', host.install(host.HostInstallRequest(self.payload, self.plugins)).type)
        self.assert_control_unchanged()

    def test_removal_does_not_admit_changed_content_after_device_change(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.record_prior_device()
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        before = path.read_bytes()
        jar = self.plugins / 'kast-ide-hosted/lib/host.jar'
        jar.write_bytes(b'changed')
        with self.assertRaises(host.Rejected) as rejected:
            host.remove_host(self.plugins)
        self.assertEqual(host.Failure.OWNERSHIP_UNPROVEN, rejected.exception.failure)
        self.assertEqual(before, path.read_bytes())
        self.assertEqual(b'changed', jar.read_bytes())
        self.assert_control_unchanged()

    def test_reinstallation_replaces_stale_receipt_identities(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        receipt = self.record_prior_device()
        path = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        jar = self.plugins / 'kast-ide-hosted/lib/host.jar'
        before = jar.read_bytes()
        changed = (
            replace(receipt, pluginRootIdentity=replace(receipt.pluginRootIdentity, inode=receipt.pluginRootIdentity.inode + 1)),
            replace(receipt, pluginRootIdentity=replace(receipt.pluginRootIdentity, owner=receipt.pluginRootIdentity.owner + 1)),
            replace(receipt, destinationIdentity=replace(receipt.destinationIdentity, inode=receipt.destinationIdentity.inode + 1)),
            replace(receipt, inventory=(receipt.inventory[0], replace(receipt.inventory[1],
                identity=replace(receipt.inventory[1].identity, inode=receipt.inventory[1].identity.inode + 1)))),
            replace(receipt, inventory=(receipt.inventory[0], replace(receipt.inventory[1],
                identity=replace(receipt.inventory[1].identity, device=receipt.inventory[1].identity.device + 1)))),
        )
        for index, invalid in enumerate(changed):
            with self.subTest(case=index):
                path.write_text(host.encode(invalid))
                self.assertEqual('ACTIVATED', host.install(host.HostInstallRequest(self.payload, self.plugins)).type)
                self.assertEqual(before, jar.read_bytes())
        self.assert_control_unchanged()

    def test_reinstallation_keeps_unselected_recovery_artifacts_without_refusing_replacement(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        shutil.rmtree(self.plugins / 'kast-ide-hosted')
        retained = self.plugins.parent / '.kast-plugin-recovery'
        for name in ('host-prior-unproven', 'host-candidate-unproven', 'foreign',
                     '.kast-ide-hosted.baseline-not-owned', '.kast-ide-hosted.install-' + 'a' * 32):
            with self.subTest(name=name):
                foreign = retained / name
                foreign.write_bytes(b'protected recovery')
                self.assertEqual('ACTIVATED', host.install(host.HostInstallRequest(self.payload, self.plugins)).type)
                self.assertEqual(b'protected recovery', foreign.read_bytes())
                self.assertTrue((self.plugins / 'kast-ide-hosted/lib/host.jar').is_file())
                foreign.unlink()
        self.assert_control_unchanged()

    def test_legacy_control_history_links_are_never_traversed_or_removed(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        retained = self.plugins.parent / '.kast-plugin-recovery'
        foreign = self.root / 'foreign-history'
        foreign.mkdir()
        sentinel = foreign / 'preserved'
        sentinel.write_bytes(b'foreign recovery evidence')
        link = retained / ('.replaced-kast-ide-hosted-' + 'a' * 32)
        link.symlink_to(foreign, target_is_directory=True)
        before = link.lstat().st_ino
        host.install(host.HostInstallRequest(self.payload, self.plugins, host.HostInstallMode.PLAN))
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        host.remove_host(self.plugins)
        self.assertTrue(link.is_symlink())
        self.assertEqual(before, link.lstat().st_ino)
        self.assertEqual(b'foreign recovery evidence', sentinel.read_bytes())
        self.assert_control_unchanged()

    def test_legacy_control_backups_do_not_block_host_reinstallation_or_removal(self):
        host.install(host.HostInstallRequest(self.make_payload('1.0.0'), self.plugins))
        retained = self.plugins.parent / '.kast-plugin-recovery'
        protected = []
        for index, prefix in enumerate(('.kast-ide-hosted.baseline-', '.kast-ide-hosted.detached-',
                                        '.replaced-kast-ide-hosted-')):
            backup = retained / (prefix + str(index) * 32)
            backup.mkdir()
            sentinel = backup / 'preserved'
            sentinel.write_bytes(b'legacy Control recovery evidence')
            protected.append((backup, host.Identity.observe(backup), sentinel.read_bytes()))
        result = host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual('ACTIVATED', result.type)
        self.assertEqual(self.payload.version, result.hostedPluginVersion)
        self.assertEqual(self.payload.version, host.read_active_receipt(retained / 'host-install.json').version)
        for _ in range(2):
            self.assertEqual('REMOVED', host.remove_host(self.plugins).type)
            self.assertFalse((self.plugins / 'kast-ide-hosted').exists())
            self.assertEqual({path.name for path, _, _ in protected}, {path.name for path in retained.iterdir()})
            for path, identity, content in protected:
                self.assertEqual(identity, host.Identity.observe(path))
                self.assertEqual(content, (path / 'preserved').read_bytes())
        self.assert_control_unchanged()

    def test_preflight_accepts_unselected_host_recovery_without_filesystem_changes(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        retained = self.plugins.parent / '.kast-plugin-recovery'
        pending = retained / 'host-prior-unproven'
        pending.write_bytes(b'pending Host transaction')
        receipt_before = (retained / 'host-install.json').read_bytes()
        destination_before = host.Identity.observe(self.plugins / 'kast-ide-hosted')
        self.assertEqual('PLANNED', host.install(host.HostInstallRequest(
            self.payload, self.plugins, host.HostInstallMode.PLAN)).type)
        self.assertEqual(receipt_before, (retained / 'host-install.json').read_bytes())
        self.assertEqual(destination_before, host.Identity.observe(self.plugins / 'kast-ide-hosted'))
        self.assertEqual(b'pending Host transaction', pending.read_bytes())
        self.assert_control_unchanged()

    def test_explicit_reset_retires_known_history_after_replacement_without_traversing_links(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        retained = self.plugins.parent / '.kast-plugin-recovery'
        sentinel = self.root / 'protected-history'
        sentinel.mkdir()
        (sentinel / 'keep').write_bytes(b'keep')
        history = []
        for index, prefix in enumerate(('.kast-ide-hosted.baseline-', '.kast-ide-hosted.detached-',
                                        '.replaced-kast-ide-hosted-', 'host-prior-', 'host-candidate-')):
            path = retained / (prefix + str(index) * 32)
            history.append(path)
            if index == 2:
                path.symlink_to(sentinel, target_is_directory=True)
            else:
                path.mkdir()
                (path / 'old').write_bytes(b'obsolete plugin')
        lock = retained / 'host-install.lock'
        lock_inode = lock.stat().st_ino
        self.assertEqual('ACTIVATED', host.install(host.HostInstallRequest(
            self.payload, self.plugins, host.HostInstallMode.RESET)).type)
        self.assertTrue(all(not os.path.lexists(path) for path in history))
        self.assertEqual(b'keep', (sentinel / 'keep').read_bytes())
        self.assertEqual(lock_inode, lock.stat().st_ino)
        self.assertEqual({'host-install.json', 'host-install.lock'}, {path.name for path in retained.iterdir()})
        self.assertEqual(self.payload.version, host.read_active_receipt(retained / 'host-install.json').version)
        self.assert_control_unchanged()

    def test_reinstallation_replaces_partial_existing_inventory(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.record_prior_device()
        (self.plugins / 'kast-ide-hosted/lib/host.jar').unlink()
        self.assertEqual('ACTIVATED', host.install(host.HostInstallRequest(self.payload, self.plugins)).type)
        self.assertTrue((self.plugins / 'kast-ide-hosted/lib/host.jar').is_file())
        self.assert_control_unchanged()

    def test_reinstallation_replaces_corrupt_receipt_and_restores_it_on_failure(self):
        host.install(host.HostInstallRequest(self.payload, self.plugins))
        receipt = self.plugins.parent / '.kast-plugin-recovery/host-install.json'
        receipt.write_bytes(b'corrupt receipt')
        receipt.with_name(receipt.name + '.new').write_bytes(b'abandoned receipt')
        destination = self.plugins / 'kast-ide-hosted'
        identity = host.Identity.observe(destination)
        original_rename = Path.rename

        def fail_candidate(path, target):
            if path.name.startswith('host-candidate-') and target == destination:
                raise OSError('controlled replacement failure')
            return original_rename(path, target)

        with patch.object(Path, 'rename', fail_candidate), self.assertRaises(host.Rejected) as rejected:
            host.install(host.HostInstallRequest(self.payload, self.plugins))
        self.assertEqual(host.Failure.FILESYSTEM_REJECTED, rejected.exception.failure)
        self.assertEqual(b'corrupt receipt', receipt.read_bytes())
        self.assertEqual(identity, host.Identity.observe(destination))
        self.assertEqual('ACTIVATED', host.install(host.HostInstallRequest(self.payload, self.plugins)).type)
        self.assert_control_unchanged()

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
