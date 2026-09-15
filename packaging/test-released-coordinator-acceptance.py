#!/usr/bin/env python3
"""Lifecycle orchestration checks with controlled child boundaries; no service is launched."""
from dataclasses import asdict, dataclass
import io
import json
from pathlib import Path
import socket
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from installed_codex_lifecycle import (AcceptanceFailure, HostCheck, HostObservationPhase,
    ServiceObservation, exercise_private_service, qualify_installed_lifecycle)
from released_coordinator_acceptance import admitted_facade, coordinator_qualified, qualify_released_coordinator
from acceptance_idea import digest
from released_acceptance_product import ReleaseRejected


@dataclass(frozen=True)
class State:
    state: str


@dataclass(frozen=True)
class Ownership:
    ownership: str = 'matched'


@dataclass(frozen=True)
class Attachment:
    attachment: str


@dataclass(frozen=True)
class Status:
    host: Attachment
    coordinator: State = State('ready')
    service: Ownership = Ownership()
    registry: State = State('registered')
    protocol: str = 'unobserved'
    catalog: str = 'unobserved'


@dataclass(frozen=True)
class Authority:
    userAgent: str = 'codex-cli fixture'


@dataclass(frozen=True)
class Thread:
    id: str = 'owned-fixture-thread'


@dataclass(frozen=True)
class Started:
    cwd: str
    thread: Thread = Thread()


@dataclass(frozen=True)
class Response:
    result: Authority | Started


@dataclass(frozen=True)
class PayloadEntry:
    path: str
    sha256: str


@dataclass(frozen=True)
class PayloadManifest:
    payloadFiles: tuple[PayloadEntry, ...]


class CoordinatorLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.product = self.root / 'product'
        self.home = self.root / 'home'
        self.home.mkdir()
        (self.product / 'state/run').mkdir(parents=True)
        self.process = Mock(stdin=io.StringIO())
        self.process.poll.return_value = 0
        self.isolation = SimpleNamespace(spawn=Mock(return_value=self.process))
        self.kast, self.facade = self.product / 'bin/kast-complete', self.product / 'bin/kast-codex-complete'
        self.commands = []

    def tearDown(self):
        self.temporary.cleanup()

    def run_command(self, arguments, **_kwargs):
        self.commands.append(arguments[-1])
        return subprocess.CompletedProcess(arguments, 0, '', '')

    def observed(self, *_arguments):
        return ServiceObservation(_arguments[-1], 'sha256:' + 'a' * 64, HostCheck.VALIDATED, 'private')

    def invoke(self):
        return qualify_installed_lifecycle(self.isolation, self.kast, self.facade, {'KAST_APP_SERVER_PUBLIC_ENDPOINT': 'private'}, self.home, self.root, self.product)

    def test_real_frame_sequence_detaches_and_disables_owned_service(self):
        responses = [asdict(Response(Authority())), asdict(Response(Started(str(self.root))))]
        with patch('installed_codex_lifecycle.subprocess.run', side_effect=self.run_command), \
             patch('installed_codex_lifecycle.exercise_private_service', side_effect=self.observed), \
             patch('installed_codex_lifecycle.receive_response', side_effect=responses), \
             patch('installed_codex_lifecycle.drain_jsonl_until_exit', return_value=0):
            result = self.invoke()
        self.assertEqual(['enable', 'disable'], self.commands)
        self.assertTrue(self.process.stdin.closed)
        self.assertEqual(HostObservationPhase.COORDINATOR_ONLY, result.beforeAttachment.phase)
        self.assertEqual(HostObservationPhase.FRONTEND_PREPARED, result.afterDetach.phase)
        self.assertEqual(HostCheck.VALIDATED, result.serviceDisable)
        self.assertEqual(HostCheck.UNQUALIFIED, result.stockDesktopUi)
        self.assertEqual(str(self.facade), self.isolation.spawn.call_args.args[0][0])
        record = asdict(result)
        receipt = dict(lifecycle=record)
        self.assertTrue(coordinator_qualified(receipt))
        for field in ('initialize', 'threadStart', 'parentClosure', 'serviceDisable'):
            missing = dict(record)
            missing.pop(field)
            self.assertFalse(coordinator_qualified(dict(lifecycle=missing)))

    def test_failed_attachment_still_disables_service(self):
        with patch('installed_codex_lifecycle.subprocess.run', side_effect=self.run_command), \
             patch('installed_codex_lifecycle.exercise_private_service', side_effect=self.observed), \
             patch('installed_codex_lifecycle.receive_response', side_effect=AcceptanceFailure('missing initialize')):
            with self.assertRaises(AcceptanceFailure):
                self.invoke()
        self.assertEqual(['enable', 'disable'], self.commands)

    def test_failed_enable_still_attempts_disable(self):
        def fail_enable(arguments, **kwargs):
            value = self.run_command(arguments, **kwargs)
            if arguments[-1] == 'enable':
                value.returncode = 1
            return value
        with patch('installed_codex_lifecycle.subprocess.run', side_effect=fail_enable):
            with self.assertRaises(AcceptanceFailure):
                self.invoke()
        self.assertEqual(['enable', 'disable'], self.commands)
        self.isolation.spawn.assert_not_called()

    def test_cleanup_failure_cannot_return_qualified_receipt(self):
        def fail_disable(arguments, **kwargs):
            value = self.run_command(arguments, **kwargs)
            if arguments[-1] == 'disable':
                value.returncode = 1
            return value
        responses = [asdict(Response(Authority())), asdict(Response(Started(str(self.root))))]
        with patch('installed_codex_lifecycle.subprocess.run', side_effect=fail_disable), \
             patch('installed_codex_lifecycle.exercise_private_service', side_effect=self.observed), \
             patch('installed_codex_lifecycle.receive_response', side_effect=responses), \
             patch('installed_codex_lifecycle.drain_jsonl_until_exit', return_value=0):
            with self.assertRaisesRegex(AcceptanceFailure, 'cleanup failed'):
                self.invoke()

    def test_status_requires_exact_attachment_and_ownership_without_payload_receipt(self):
        with socket.socket(socket.AF_UNIX) as private:
            private.bind(str(self.product / 'state/run/c.sock'))
            (self.product / 'state/run/c.sock').chmod(0o600)
            def status(phase):
                return subprocess.CompletedProcess([], 0, json.dumps(asdict(Status(Attachment(phase)))), '')
            with patch('installed_codex_lifecycle.subprocess.run', return_value=status('pending')):
                result = exercise_private_service(self.kast, {'KAST_APP_SERVER_PUBLIC_ENDPOINT': 'private'}, self.home, self.root, self.product, HostObservationPhase.COORDINATOR_ONLY)
            self.assertEqual({'phase', 'statusSha256', 'publicSocketAndOwnership', 'publicEndpointKind'}, set(asdict(result)))
            with patch('installed_codex_lifecycle.subprocess.run', return_value=status('pending')):
                with self.assertRaises(AcceptanceFailure):
                    exercise_private_service(self.kast, {'KAST_APP_SERVER_PUBLIC_ENDPOINT': 'private'}, self.home, self.root, self.product, HostObservationPhase.FRONTEND_PREPARED)

    def test_release_facade_matches_original_manifest_and_rejects_changed_bytes(self):
        self.facade.parent.mkdir()
        self.facade.write_text('original complete wrapper')
        self.facade.chmod(0o700)
        manifest = PayloadManifest((PayloadEntry('bin/kast-codex-complete', 'sha256:' + digest(self.facade)),))
        (self.product / 'installation.json').write_text(json.dumps(asdict(manifest)))
        self.assertEqual(self.facade, admitted_facade(self.product))
        self.facade.write_text('changed wrapper')
        with self.assertRaises(ReleaseRejected):
            admitted_facade(self.product)

    def test_released_lifecycle_preserves_private_environment_and_inventory_selection(self):
        self.facade.parent.mkdir()
        for file in (self.facade, self.kast):
            file.write_text('original fixture wrapper')
        codex = self.root / 'explicit-codex'
        codex.write_text('admitted authority')
        isolation = SimpleNamespace(root=self.root, tools=dict(codex=codex))
        installed = SimpleNamespace(product=str(self.product))
        inventory = SimpleNamespace(configuredDefaultTools=('registry_selected_read',), installedSchemaSha256='a' * 64)
        private = dict(HOME=str(self.home), CODEX_HOME=str(self.home / '.codex'),
                       JAVA_TOOL_OPTIONS='private JVM home', KAST_RUNTIME_DIRECTORY=str(self.product / 'state/run'))
        fixture = SimpleNamespace(environment=private, workspace=self.root)
        with patch('released_coordinator_acceptance.product_executable', return_value=self.kast), \
             patch('released_coordinator_acceptance.admitted_facade', return_value=self.facade), \
             patch('released_coordinator_acceptance.subprocess.run', return_value=subprocess.CompletedProcess([], 0, 'codex-cli fixture', '')), \
             patch('released_coordinator_acceptance.qualify_installed_lifecycle') as lifecycle:
            qualify_released_coordinator(isolation, installed, inventory, fixture)
        arguments = lifecycle.call_args.args
        self.assertEqual((self.kast, self.facade), arguments[1:3])
        environment = arguments[3]
        for key, value in private.items():
            self.assertEqual(value, environment[key])
        self.assertEqual('registry_selected_read', environment['KAST_APP_SERVER_TOOLS'])
        self.assertEqual(str(codex), environment['KAST_REAL_CODEX_EXECUTABLE'])
        self.assertEqual('1', environment['KAST_ENABLE_APP_SERVER'])

    def test_release_facade_cannot_fall_back_to_raw_launcher(self):
        (self.product / 'bin').mkdir()
        (self.product / 'bin/kast-codex').write_text('raw launcher')
        with self.assertRaises(ReleaseRejected):
            admitted_facade(self.product)


if __name__ == '__main__':
    unittest.main()
