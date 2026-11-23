"""Recovery uses only private fixtures; no real installations or processes."""
import contextlib
import io
from dataclasses import asdict, dataclass
from pathlib import Path
import json
import hashlib
import importlib.util
import socket
import os
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / 'internal/installation/installation-recovery.py'
LIFECYCLE = Path(__file__).parents[1] / 'internal/installation/installation-lifecycle.py'

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


@dataclass(frozen=True)
class ManagedControlManifestFixture(ControlManifestFixture):
    controlSha256: str = 'sha256:' + 'b' * 64


class ManagedControlRecoveryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='kast-managed-control-recovery-')
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name).resolve()
        self.outer = self.home / 'kast'
        self.root = self.outer / 'installation'
        self.outer.mkdir()
        self.transaction = self.outer / 'recovery/replacement'
        self.transaction.mkdir(parents=True)
        self.stage = self.outer / '.install-selected'
        specification = importlib.util.spec_from_file_location('kast_managed_control_recovery_fixture', SCRIPT)
        self.recovery = importlib.util.module_from_spec(specification)
        sys.modules[specification.name] = self.recovery
        specification.loader.exec_module(self.recovery)

    def payload(self, path, version, label, control_digest):
        (path / 'bin').mkdir(parents=True)
        (path / 'state/mutation').mkdir(parents=True)
        (path / 'state/mutation/receipt').write_text(label + ' unresolved evidence')
        executable = path / 'bin/kast-complete'
        executable.write_text(label)
        executable.chmod(0o644)
        manifest = ManagedControlManifestFixture(str(self.root), str(self.root / 'state'),
            str(self.root / 'config/environment'), str(self.root / 'config/workspaces.json'),
            (PayloadFileFixture('bin/kast-complete', 'sha256:' + hashlib.sha256(executable.read_bytes()).hexdigest(), 420),),
            semanticVersion=version, payloadIdentity='sha256:' + control_digest, controlSha256='sha256:' + control_digest)
        (path / 'installation.json').write_text(json.dumps(asdict(manifest)))

    def bind_socket(self, directory):
        """A relative bind keeps the Unix socket path within the platform sun_path limit."""
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        working = Path.cwd()
        os.chdir(directory)
        try:
            listener.bind('c.sock')
        finally:
            os.chdir(working)

    def test_live_coordinator_socket_is_admitted_only_at_its_exact_path_before_retirement(self):
        r = self.recovery
        run = self.root / 'state/run'
        run.mkdir(parents=True)
        (run / 'c.sock.lock').write_bytes(b'')
        self.bind_socket(run)
        with self.assertRaises(r.Rejected) as retired:
            r.managed_tree(self.root)
        self.assertEqual(r.Failure.OWNERSHIP, retired.exception.failure)
        live = r.managed_tree(self.root, live_coordinator=True)
        self.assertEqual(live, r.managed_tree(self.root, live_coordinator=True))
        (run / 'c.sock').unlink()
        self.assertNotEqual(live, r.managed_tree(self.root))
        foreign = self.root / 'state/broker'
        foreign.mkdir()
        self.bind_socket(foreign)
        with self.assertRaises(r.Rejected) as misplaced:
            r.managed_tree(self.root, live_coordinator=True)
        self.assertEqual(r.Failure.OWNERSHIP, misplaced.exception.failure)

    def test_installed_inventory_limit_retains_typed_failure_and_bounded_evidence(self):
        r = self.recovery
        self.payload(self.stage, '2.0.0', 'selected', 'b' * 64)
        manifest_path = self.stage / 'installation.json'
        manifest = ManagedControlManifestFixture(str(self.root), str(self.root / 'state'),
            str(self.root / 'config/environment'), str(self.root / 'config/workspaces.json'),
            (PayloadFileFixture('bin/kast-complete', 'sha256:' + 'b' * 64, 420),) * 32769)
        manifest_path.write_text(json.dumps(asdict(manifest)))
        before = manifest_path.read_bytes()
        evidence = io.StringIO()
        with contextlib.redirect_stderr(evidence), self.assertRaises(r.Rejected) as rejected:
            r.managed_payload(self.stage, self.root, r.Identity.observe(self.stage))
        self.assertEqual('CONTROL_LIMIT_EXCEEDED', rejected.exception.failure.value)
        self.assertEqual({'failure': 'CONTROL_LIMIT_EXCEEDED',
            'limit': {'resource': 'PAYLOAD_FILES', 'maximum': 32768, 'observedAtLeast': 32769},
            'status': 'rejected'}, json.loads(evidence.getvalue()))
        self.assertEqual(before, manifest_path.read_bytes())
        self.assertEqual('selected', (self.stage / 'bin/kast-complete').read_text())


class InstalledPayloadAdmissionTest(unittest.TestCase):
    """The retained lifecycle owner admits one exact Control payload and nothing it does not name."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='kast-payload-admission-')
        self.addCleanup(temporary.cleanup)
        self.outer = Path(temporary.name).resolve() / 'kast'
        self.root = self.outer / 'installation'
        (self.root / 'bin').mkdir(parents=True)
        self.executable = self.root / 'bin/kast-complete'
        self.executable.write_text('#!/bin/sh\nexit 0\n')
        self.executable.chmod(0o700)
        specification = importlib.util.spec_from_file_location('kast_payload_admission_fixture', LIFECYCLE)
        self.lifecycle = importlib.util.module_from_spec(specification)
        sys.modules[specification.name] = self.lifecycle
        specification.loader.exec_module(self.lifecycle)
        self.manifest = asdict(ControlManifestFixture(str(self.root), str(self.root / 'state'),
            str(self.root / 'config/environment'), str(self.root / 'config/workspaces.json'),
            (PayloadFileFixture('bin/kast-complete',
                'sha256:' + hashlib.sha256(self.executable.read_bytes()).hexdigest(), 0o700),)))
        self.save()

    def save(self):
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))

    def assert_manifest_rejected(self):
        with self.assertRaises(self.lifecycle.Rejected) as rejected:
            self.lifecycle.Installation.admit(str(self.root))
        self.assertEqual(self.lifecycle.Failure.MANIFEST_REJECTED, rejected.exception.failure)

    def test_exact_payload_is_admitted_at_its_single_installation_root(self):
        admitted = self.lifecycle.Installation.admit(str(self.root))
        self.assertEqual(self.outer, admitted.managed_root)
        self.assertEqual(self.lifecycle.FileIdentity.observe(self.root), admitted.identity)

    def test_manifest_large_enough_for_current_inventory_is_admitted(self):
        self.manifest['boundedPadding'] = 'x' * 1_750_000
        self.save()
        self.lifecycle.Installation.admit(str(self.root))

    def test_changed_payload_bytes_are_rejected(self):
        self.executable.write_text('#!/bin/sh\nexit 1\n')
        self.assert_manifest_rejected()
        self.assertEqual('#!/bin/sh\nexit 1\n', self.executable.read_text())

    def test_manifest_cannot_widen_owned_roots(self):
        for key in ('stateRoot', 'configuration', 'workspaceRegistry'):
            with self.subTest(key=key):
                original = self.manifest[key]
                self.manifest[key] = str(self.outer)
                self.save()
                self.assert_manifest_rejected()
                self.manifest[key] = original

    def test_versioned_manifest_schemas_are_rejected(self):
        digest = self.manifest['payloadIdentity'][7:]
        versioned = self.outer / 'versions' / ('1.2.3-' + digest)
        versioned.parent.mkdir()
        self.root.rename(versioned)
        self.root = versioned
        for key, suffix in (('installationRoot', ''), ('stateRoot', 'state'),
                            ('configuration', 'config/environment'), ('workspaceRegistry', 'config/workspaces.json')):
            self.manifest[key] = str(versioned / suffix) if suffix else str(versioned)
        for schema in (1, 2):
            with self.subTest(schema=schema):
                self.manifest['schemaVersion'] = schema
                self.save()
                self.assert_manifest_rejected()

    def test_selector_anchor_is_rejected_and_foreign_entry_preserved(self):
        foreign = self.outer / 'current'
        foreign.write_text('protected foreign entry')
        self.manifest['externalAnchors'] = [
            {'kind': 'current', 'path': str(foreign), 'expectedLinkTarget': 'versions/old'}]
        self.save()
        self.assert_manifest_rejected()
        self.assertEqual('protected foreign entry', foreign.read_text())


class VersionedPayloadRecoveryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='kast-versioned-payload-')
        self.addCleanup(temporary.cleanup)
        digest = 'c' * 64
        self.root = Path(temporary.name).resolve() / 'kast/versions' / ('1.2.3-' + digest)
        (self.root / 'bin').mkdir(parents=True)
        executable = self.root / 'bin/kast-complete'
        executable.write_text('versioned')
        executable.chmod(0o644)
        self.manifest = asdict(ManagedControlManifestFixture(str(self.root), str(self.root / 'state'),
            str(self.root / 'config/environment'), str(self.root / 'config/workspaces.json'),
            (PayloadFileFixture('bin/kast-complete', 'sha256:' + hashlib.sha256(b'versioned').hexdigest(), 420),),
            payloadIdentity='sha256:' + digest))
        specification = importlib.util.spec_from_file_location('kast_versioned_payload_recovery_fixture', SCRIPT)
        self.recovery = importlib.util.module_from_spec(specification)
        sys.modules[specification.name] = self.recovery
        specification.loader.exec_module(self.recovery)

    def test_versioned_manifest_schemas_are_rejected_before_payload_admission(self):
        r = self.recovery
        for schema in (1, 2):
            with self.subTest(schema=schema):
                self.manifest['schemaVersion'] = schema
                (self.root / 'installation.json').write_text(json.dumps(self.manifest))
                evidence = io.StringIO()
                with contextlib.redirect_stderr(evidence), self.assertRaises(r.Rejected) as rejected:
                    r.managed_payload(self.root, self.root, r.Identity.observe(self.root))
                self.assertEqual(r.Failure.RECEIPT, rejected.exception.failure)
                self.assertEqual('', evidence.getvalue())


RECEIPTS = Path(__file__).parents[2] / 'distribution/contract/src/test/resources/replacement-receipt'


class ProductReplacementReceiptShapeTest(unittest.TestCase):
    """The Kotlin contract fixtures must remain exactly what this journal writes and strictly decodes."""

    def setUp(self):
        specification = importlib.util.spec_from_file_location('kast_product_receipt_shape_fixture', SCRIPT)
        self.recovery = importlib.util.module_from_spec(specification)
        sys.modules[specification.name] = self.recovery
        specification.loader.exec_module(self.recovery)

    def test_kotlin_contract_fixtures_are_canonical_product_journals(self):
        for name in ('product-install.json', 'product-upgrade.json'):
            with self.subTest(name=name):
                recorded = (RECEIPTS / name).read_text().removesuffix('\n')
                record = self.recovery.decode(self.recovery.Replacement, json.loads(recorded))
                self.assertIsNotNone(record.product)
                self.assertEqual(recorded, self.recovery.encode(record))

    def test_a_journal_that_still_records_a_null_composition_is_rejected(self):
        document = json.loads((RECEIPTS / 'product-install.json').read_text())
        document['composition'] = None
        with self.assertRaises(self.recovery.Rejected) as rejected:
            self.recovery.decode(self.recovery.Replacement, document)
        self.assertEqual(self.recovery.Failure.RECEIPT, rejected.exception.failure)

    def test_an_unwritten_product_phase_or_stage_is_rejected(self):
        recorded = json.loads((RECEIPTS / 'product-install.json').read_text())
        for field, value in (
            ('phase', 'HOST_COMMITTED'),
            ('phase', 'RESTORING'),
            ('phase', 'RESTORED'),
            ('stage', 'RECOVERY_REQUIRED'),
        ):
            with self.subTest(value=value):
                document = json.loads(json.dumps(recorded))
                (document['product'] if field == 'phase' else document)[field] = value
                with self.assertRaises(self.recovery.Rejected) as rejected:
                    self.recovery.decode(self.recovery.Replacement, document)
                self.assertEqual(self.recovery.Failure.RECEIPT, rejected.exception.failure)


if __name__ == '__main__':
    unittest.main()
