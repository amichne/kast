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
            'retirement': None, 'status': 'rejected'}, json.loads(evidence.getvalue()))
        self.assertEqual(before, manifest_path.read_bytes())
        self.assertEqual('selected', (self.stage / 'bin/kast-complete').read_text())


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
                self.assertIsNone(record.composition)
                self.assertEqual(recorded, self.recovery.encode(record))


if __name__ == '__main__':
    unittest.main()
