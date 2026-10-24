"""Offline ownership-transition proofs; never selects a real installation."""
import sys
import json
import hashlib
import importlib.util
import io
import os
from contextlib import redirect_stderr
from dataclasses import asdict, dataclass
from unittest.mock import patch
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest
import uuid

SCRIPT = Path(__file__).with_name('installation-lifecycle.py')
spec = importlib.util.spec_from_file_location('lifecycle_under_test', SCRIPT)
lifecycle = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = lifecycle
spec.loader.exec_module(lifecycle)


@dataclass(frozen=True)
class ThreadStoreLayoutFixture:
    version: int = 3


@dataclass(frozen=True)
class InvocationStoreLayoutFixture:
    schemaVersion: int = 2


@dataclass(frozen=True)
class InstalledToolInvocationFixture:
    pid: int
    schemaVersion: int = 1
    startEpochMillis: int = 1


def initialized_stopped_profile(state):
    profile = state / 'broker' / ('a' * 16)
    profile.mkdir(parents=True, mode=0o700)
    (profile / 'stopped').write_bytes(b'stopped\n')
    (profile / 'protocol').mkdir(mode=0o700)
    discovery = profile / 'desktop-discovery'
    discovery.mkdir(mode=0o700)
    (discovery / '.lock').touch(mode=0o600)
    # These fixtures use the fields of ThreadStoreLayout and InvocationStoreLayout.
    for name, records, marker in (
            ('threads.json.d', 'records-v3', ThreadStoreLayoutFixture()),
            ('invocations.json.d', 'records-v2', InvocationStoreLayoutFixture())):
        store = profile / name
        store.mkdir(mode=0o700)
        (store / '.lock').touch(mode=0o600)
        (store / records).mkdir(mode=0o700)
        layout = store / 'layout.json'
        layout.write_text(json.dumps(asdict(marker), separators=(',', ':')) + '\n')
        layout.chmod(0o600)
    return profile


class UnregisteredBrokerStateTest(unittest.TestCase):
    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory(prefix='kast-unregistered-state-')
        self.root = Path(self.fixture.name).resolve()
        self.state = self.root / 'state'
        self.state.mkdir(mode=0o700)
        (self.state / 'run').mkdir(mode=0o700)
        self.profile = initialized_stopped_profile(self.state)

    def tearDown(self):
        self.fixture.cleanup()

    def test_current_empty_stores_prove_no_durable_workspace_history(self):
        self.assertTrue(lifecycle.admits_unregistered_broker_state(self.state))

    def test_durable_record_or_partial_shard_blocks_missing_registry_admission(self):
        for store, records in (('threads.json.d', 'records-v3'), ('invocations.json.d', 'records-v2')):
            with self.subTest(store=store):
                shard = self.profile / store / records / 'ab'
                shard.mkdir(mode=0o700)
                self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
                record = shard / ('ab' + 'c' * 62 + '.json')
                record.write_text('{"phase":"COMPLETED"}\n')
                self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
                record.unlink()
                shard.rmdir()

    def test_unknown_layout_or_extra_store_entry_blocks_admission(self):
        for store, marker in (('threads.json.d', ThreadStoreLayoutFixture()),
                              ('invocations.json.d', InvocationStoreLayoutFixture())):
            with self.subTest(store=store):
                layout = self.profile / store / 'layout.json'
                for malformed in ('{}', '{"version":99}', '{"schemaVersion":2,"unknown":true}'):
                    layout.write_text(malformed)
                    self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
                layout.write_text(json.dumps(asdict(marker), separators=(',', ':')) + '\n')
                unknown = self.profile / store / 'unknown'
                unknown.touch()
                self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
                unknown.unlink()

    def test_layout_versions_need_exact_integers_and_unique_fields(self):
        cases = (
            ('threads.json.d', ThreadStoreLayoutFixture(),
             ('{"version":3.0}', '{"version":true}', '{"version":3,"version":3}')),
            ('invocations.json.d', InvocationStoreLayoutFixture(),
             ('{"schemaVersion":2.0}', '{"schemaVersion":true}', '{"schemaVersion":2,"schemaVersion":2}')),
        )
        for store, valid, rejected in cases:
            layout = self.profile / store / 'layout.json'
            for document in rejected:
                with self.subTest(store=store, document=document):
                    layout.write_text(document)
                    self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
            layout.write_text(json.dumps(asdict(valid), separators=(',', ':')) + '\n')

    def test_live_or_unknown_runtime_evidence_blocks_admission(self):
        for relative in ('service-readiness.json', 'protocol/partial-schema.json',
                         'desktop-discovery/ownership.json'):
            with self.subTest(path=relative):
                evidence = self.profile / relative
                evidence.write_text('{}')
                self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
                evidence.unlink()
        workers = self.state / 'workers'
        workers.mkdir()
        (workers / 'unknown.json').write_text('{}')
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))

    def test_missing_stop_marker_and_active_run_state_block_admission(self):
        marker = self.profile / 'stopped'
        marker.unlink()
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        marker.write_bytes(b'stopped\n')
        (self.state / 'run/public.sock').touch()
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))

    def test_private_store_links_foreign_ownership_and_permissions_block_admission(self):
        records = self.profile / 'threads.json.d/records-v3'
        records.rmdir()
        external = self.root / 'foreign'
        external.mkdir(mode=0o700)
        records.symlink_to(external, target_is_directory=True)
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        records.unlink()
        records.mkdir(mode=0o700)
        lock = self.profile / 'invocations.json.d/.lock'
        lock.chmod(0o666)
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        lock.chmod(0o600)
        with patch.object(lifecycle.os, 'getuid', return_value=lifecycle.os.getuid() + 1):
            self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))


class StoppedToolInvocationStateTest(unittest.TestCase):
    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory(prefix='kast-stopped-tools-')
        self.root = Path(self.fixture.name).resolve()
        self.state = self.root / 'state'
        self.state.mkdir(mode=0o700)
        (self.state / 'run').mkdir(mode=0o700)
        initialized_stopped_profile(self.state)
        self.run = self.state / 'run'
        self.records = self.run / 'tool-sessions'
        self.records.mkdir(mode=0o755)
        (self.run / 'one-shot').mkdir(mode=0o755)
        self.lock = self.run / 'c.sock.lock'
        self.lock.touch(mode=0o600)
        child = subprocess.Popen([sys.executable, '-c', 'pass'])
        self.finished_pid = child.pid
        self.assertEqual(0, child.wait(timeout=10))
        self.record = self.records / (str(uuid.uuid4()) + '.json')
        self.write_record(InstalledToolInvocationFixture(self.finished_pid))

    def tearDown(self):
        self.fixture.cleanup()

    def write_record(self, value):
        self.record.write_text(json.dumps(asdict(value)))
        self.record.chmod(0o600)

    def test_finished_transport_records_and_socket_lock_admit(self):
        self.assertTrue(lifecycle.admits_unregistered_broker_state(self.state))
        self.assertTrue(self.record.exists())

    def test_live_or_reused_pid_rejects_without_signaling_it(self):
        self.write_record(InstalledToolInvocationFixture(os.getpid()))
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))

    def test_unavailable_process_observation_rejects(self):
        with patch.object(lifecycle.os, 'kill', side_effect=PermissionError):
            self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))

    def test_unknown_record_fields_and_invalid_identity_reject(self):
        invalid = ('{}', '{"schemaVersion":1,"pid":1,"startEpochMillis":1,"extra":0}',
                   '{"schemaVersion":1,"pid":true,"startEpochMillis":1}',
                   '{"schemaVersion":1,"pid":0,"startEpochMillis":1}',
                   '{"schemaVersion":1,"pid":1,"startEpochMillis":0}',
                   '{"schemaVersion":1.0,"pid":1,"startEpochMillis":1}',
                   '{"schemaVersion":1,"pid":1,"pid":2,"startEpochMillis":1}')
        for raw in invalid:
            with self.subTest(raw=raw):
                self.record.write_text(raw)
                self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))

    def test_unknown_files_and_unbounded_records_reject(self):
        unknown = self.run / 'unknown'
        unknown.touch()
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        unknown.unlink()
        self.record.rename(self.records / 'invalid.json')
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        (self.records / 'invalid.json').unlink()
        for number in range(257):
            self.record = self.records / (str(uuid.UUID(int=number)) + '.json')
            self.write_record(InstalledToolInvocationFixture(self.finished_pid))
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))

    def test_record_links_and_writable_or_foreign_state_reject(self):
        self.record.chmod(0o666)
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        self.record.chmod(0o600)
        self.records.chmod(0o777)
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        self.records.chmod(0o755)
        with patch.object(lifecycle.os, 'getuid', return_value=os.getuid() + 1):
            self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        outside = self.root / 'record.json'
        self.record.rename(outside)
        self.record.symlink_to(outside)
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))

    def test_active_or_nonempty_socket_lock_rejects(self):
        self.lock.write_text('unknown')
        self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
        self.lock.write_bytes(b'')
        with subprocess.Popen([sys.executable, '-c',
                               'import fcntl,sys; f=open(sys.argv[1],"r+"); '
                               'fcntl.lockf(f,fcntl.LOCK_EX); print("locked",flush=True); sys.stdin.read()',
                               str(self.lock)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as child:
            self.assertEqual('locked\n', child.stdout.readline())
            self.assertFalse(lifecycle.admits_unregistered_broker_state(self.state))
            child.communicate(timeout=10)


class ScriptedRetirementExecutor:
    """One exact interaction; violations survive even if product code catches the assertion."""
    def __init__(self, expected, observation, transcript):
        self.expected = expected
        self.observation = observation
        self.transcript = transcript
        self.requests = []
        self.violations = []

    def execute(self, command):
        self.requests.append(command)
        self.transcript.append(command)
        if len(self.requests) != 1 or command != self.expected:
            # Command repr deliberately excludes environment values.
            self.violations.append(f'unexpected or repeated request: {command!r}')
            raise AssertionError(self.violations[-1])
        return self.observation

    def assert_consumed(self, case):
        case.assertEqual([], self.violations)
        case.assertEqual([self.expected], self.requests)


class RetirementBoundaryTest(unittest.TestCase):
    # Budget: one scripted request, explicit immutable values, captured observations.
    # No filesystem fixture, real child, clock, sleep, or installation. Runner excluded.
    def test_child_observations_preserve_stage_and_finite_failure(self):
        cases = (
            (lifecycle.Exited(0), lifecycle.RetirementOutcome.COMPLETED),
            (lifecycle.Exited(7), lifecycle.RetirementOutcome.EXIT_REJECTED),
            (lifecycle.DeadlineExceeded(), lifecycle.RetirementOutcome.DEADLINE_EXCEEDED),
            (lifecycle.IoFailure(), lifecycle.RetirementOutcome.IO_REJECTED),
        )
        for stage, arguments in ((lifecycle.RetirementStage.COORDINATOR, ('app-server', 'disable')),
                                 (lifecycle.RetirementStage.WORKSPACE, ('stop',))):
            for observation, expected in cases:
                with self.subTest(stage=stage, observation=observation):
                    command = lifecycle.RetirementCommand(Path('/fixture/bin/kast-complete'), arguments,
                        Path('/fixture/workspace'), (('HOME', '/fixture/home'),), 60000)
                    transcript = []
                    executor = ScriptedRetirementExecutor(command, observation, transcript)
                    output = io.StringIO()
                    def observe(actual_stage, outcome, root):
                        transcript.append((actual_stage, outcome, root))
                        lifecycle.observe_retirement(actual_stage, outcome, root)
                    try:
                        with redirect_stderr(output):
                            if expected is lifecycle.RetirementOutcome.COMPLETED:
                                lifecycle.execute_retirement(command, stage, executor, observe)
                            else:
                                with self.assertRaises(lifecycle.Rejected) as rejection:
                                    lifecycle.execute_retirement(command, stage, executor, observe)
                                self.assertEqual(lifecycle.Failure.RETIREMENT_UNPROVEN, rejection.exception.failure)
                                self.assertEqual(lifecycle.RetirementRejection(stage, expected),
                                                 rejection.exception.retirement)
                    finally:
                        executor.assert_consumed(self)
                    self.assertEqual([
                        (stage, lifecycle.RetirementOutcome.STARTED, command.working_directory),
                        command, (stage, expected, command.working_directory),
                    ], transcript)
                    events = [json.loads(line) for line in output.getvalue().splitlines()]
                    expected_events = [{'component': 'kast-installation', 'stage': stage.value, 'outcome': outcome}
                                       for outcome in ('started', expected.value)]
                    if stage is lifecycle.RetirementStage.WORKSPACE:
                        for event in expected_events:
                            event['workspaceIdentity'] = hashlib.sha256(b'/fixture/workspace').hexdigest()
                    self.assertEqual(expected_events, events)

    def test_script_refuses_missing_unexpected_and_repeated_requests(self):
        command = lifecycle.RetirementCommand(Path('/fixture/child'), ('stop',), Path('/fixture'), (), 60000)
        different = lifecycle.RetirementCommand(Path('/fixture/other'), ('stop',), Path('/fixture'), (), 60000)
        for requests in ((), (different,), (command, command)):
            with self.subTest(requests=requests):
                executor = ScriptedRetirementExecutor(command, lifecycle.Exited(0), [])
                for request in requests:
                    try:
                        executor.execute(request)
                    except AssertionError:
                        pass  # Simulate a consumer catching the fixture's exception.
                with self.assertRaises(AssertionError):
                    executor.assert_consumed(self)


class RetirementAdapterTest(unittest.TestCase):
    # Budget: one private directory and one trivial executable; no installation.
    def test_subprocess_binds_exact_arguments_directory_environment_and_exit(self):
        with tempfile.TemporaryDirectory(prefix='kast-retirement-adapter-') as directory:
            root = Path(directory).resolve()
            executable = root / 'child'
            executable.write_text('#!/bin/sh\n'
                '[ "$#" = 2 ] && [ "$1" = "literal ; argument" ] && [ "$2" = second ] || exit 81\n'
                '[ "$PWD" = "$HOME" ] && [ "$BINDING_INPUT" = explicit ] || exit 82\n'
                'read -r unexpected && exit 83\n'
                'printf discarded\nprintf discarded >&2\nexit 7\n')
            executable.chmod(0o700)
            command = lifecycle.RetirementCommand(executable, ('literal ; argument', 'second'), root,
                (('HOME', str(root)), ('BINDING_INPUT', 'explicit')), 60000)
            self.assertEqual(lifecycle.Exited(7), lifecycle.SubprocessRetirementExecutor().execute(command))

    def test_os_exceptions_translate_without_waiting_or_retrying(self):
        # Adapter translation only: no child or filesystem; timeout is an observation.
        command = lifecycle.RetirementCommand(Path('/fixture/child'), ('stop',), Path('/fixture'),
            (('HOME', '/fixture/home'),), 60000)
        for error, expected in ((subprocess.TimeoutExpired('/fixture/child', 60), lifecycle.DeadlineExceeded()),
                                (OSError('scripted I/O failure'), lifecycle.IoFailure())):
            with self.subTest(observation=expected), patch.object(lifecycle.subprocess, 'run', side_effect=error) as run:
                self.assertEqual(expected, lifecycle.SubprocessRetirementExecutor().execute(command))
                run.assert_called_once_with(['/fixture/child', 'stop'], cwd=Path('/fixture'),
                    env={'HOME': '/fixture/home'}, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL, shell=False, check=False, timeout=60)

class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory(prefix='kast-lifecycle-')
        self.outer = Path(self.fixture.name).resolve()
        self.root = self.outer / 'versions' / ('0.36.1-' + 'c' * 64)
        for directory in ('bin', 'config', 'state/broker/profile', 'state/run'):
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        self.workspace = self.outer / 'workspace'
        self.workspace.mkdir()
        self.log = self.outer / 'commands'
        self.kast = self.root / 'bin/kast-complete'
        self.kast.write_text('#!/bin/sh\n'
            'if [ "$1 $2" = "app-server disable" ] && [ "${KAST_ENABLE_APP_SERVER-}" != 1 ]; then exit 9; fi\n'
            'printf "%s\\n" "$*" >> "' + str(self.log) + '"\n'
            'echo \'{"status":"complete"}\'\n')
        self.kast.chmod(0o700)
        self.epoch = {'schemaVersion': 1, 'installation': 'sha256:' + 'd' * 64, 'epoch': str(uuid.uuid4())}
        (self.root / 'state/epoch.json').write_text(json.dumps(self.epoch))
        (self.root / 'config/environment').write_text('KAST_INDEXER_MAX_HEAP=8g\nKAST_ENABLE_APP_SERVER=0\n')
        (self.root / 'config/workspaces.json').write_text(json.dumps({'schemaVersion': 2, 'revision': 1, 'roots': [str(self.workspace)]}))
        self.manifest = {'schemaVersion': 1, 'semanticVersion': '0.36.1', 'installationRoot': str(self.root),
            'payloadIdentity': 'sha256:' + 'c' * 64, 'controlSha256': 'sha256:' + 'a' * 64,
            'runtimeSha256': 'sha256:' + 'b' * 64, 'stateRoot': str(self.root / 'state'),
            'configuration': str(self.root / 'config/environment'), 'workspaceRegistry': str(self.root / 'config/workspaces.json'),
            'externalAnchors': []}
        self.manifest['payloadFiles'] = self.inventory()
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
    def inventory(self):
        return [{'path': 'bin/kast-complete', 'sha256': 'sha256:' + hashlib.sha256(self.kast.read_bytes()).hexdigest(), 'mode': 448}]
    def tearDown(self):
        self.fixture.cleanup()
    def invoke(self, *arguments):
        result = subprocess.run([sys.executable, str(SCRIPT), '--installation', str(self.root), *arguments], capture_output=True, text=True, timeout=10, env={"PATH": "/usr/bin:/bin", "HOME": str(self.outer), "TMPDIR": str(self.outer)})
        self.assertTrue(result.stdout, result.stderr)
        self.last_stderr = result.stderr
        return result.returncode, json.loads(result.stdout)
    def prior_version(self):
        prior = self.root.with_name('0.36.0-' + 'd' * 64)
        shutil.copytree(self.root, prior)
        document = dict(self.manifest,
            semanticVersion='0.36.0', installationRoot=str(prior), payloadIdentity='sha256:' + 'd' * 64,
            stateRoot=str(prior / 'state'), configuration=str(prior / 'config/environment'),
            workspaceRegistry=str(prior / 'config/workspaces.json'))
        (prior / 'installation.json').write_text(json.dumps(document))
        (self.outer / 'current').symlink_to('versions/' + self.root.name)
        return prior
    def test_prune_removes_only_admitted_unselected_version(self):
        prior = self.prior_version()
        marker = (self.outer / 'foreign').write_text('preserve')
        code, result = self.invoke('prune')
        self.assertEqual(0, code, result)
        self.assertEqual('pruned', result['status'])
        self.assertEqual([str(prior)], result['removed'])
        self.assertFalse(prior.exists())
        self.assertEqual('versions/' + self.root.name, (self.outer / 'current').readlink().as_posix())
        self.assertTrue(self.root.exists())
        self.assertEqual('preserve', (self.outer / 'foreign').read_text())
    def test_prune_refuses_untrusted_version_before_any_deletion(self):
        prior = self.prior_version()
        foreign = self.outer / 'versions/foreign'
        foreign.mkdir()
        code, result = self.invoke('prune')
        self.assertNotEqual(0, code)
        self.assertEqual('MANIFEST_REJECTED', result['failure'])
        self.assertTrue(prior.exists())
        self.assertTrue(foreign.exists())
        self.assertTrue(self.root.exists())
    def test_prune_refuses_changed_selection(self):
        prior = self.prior_version()
        (self.outer / 'current').unlink()
        (self.outer / 'current').symlink_to('versions/' + prior.name)
        code, result = self.invoke('prune')
        self.assertNotEqual(0, code)
        self.assertEqual('ANCHOR_OWNERSHIP_UNPROVEN', result['failure'])
        self.assertTrue(prior.exists())
        self.assertTrue(self.root.exists())
    def test_remove_deletes_only_matching_version_anchors_and_retains_outer_lock(self):
        current = self.outer / 'current'
        target = 'versions/' + self.root.name
        current.symlink_to(target)
        self.manifest['externalAnchors'] = [{'kind': 'current', 'path': str(current), 'expectedLinkTarget': target}]
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('remove')
        self.assertEqual(0, code, result)
        self.assertFalse(self.root.exists())
        self.assertFalse(current.is_symlink())
        self.assertTrue((self.outer / 'activation.lock').is_file())
        self.assertTrue(self.workspace.exists())
    def test_remove_preserves_selector_that_now_names_another_version(self):
        current = self.outer / 'current'
        current.symlink_to('versions/newer')
        self.manifest['externalAnchors'] = [{'kind': 'current', 'path': str(current), 'expectedLinkTarget': 'versions/' + self.root.name}]
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('remove')
        self.assertEqual(0, code, result)
        self.assertEqual('versions/newer', current.readlink().as_posix())
    def test_dry_run_is_passive(self):
        code, result = self.invoke('reset', '--dry-run')
        self.assertEqual(0, code, result)
        self.assertFalse(self.log.exists())
        self.assertEqual(self.epoch, json.loads((self.root / 'state/epoch.json').read_text()))
    def test_manifest_large_enough_for_current_inventory_is_admitted(self):
        self.manifest['boundedPadding'] = 'x' * 1_750_000
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('inspect')
        self.assertEqual(0, code, result)
    def test_reset_retires_exact_commands_preserves_config_and_changes_epoch(self):
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        self.assertEqual(['app-server disable', 'stop'], self.log.read_text().splitlines())
        new_epoch = json.loads((self.root / 'state/epoch.json').read_text())
        self.assertNotEqual(self.epoch['epoch'], new_epoch['epoch'])
        self.assertEqual(self.epoch['installation'], new_epoch['installation'])
        self.assertEqual('KAST_INDEXER_MAX_HEAP=8g\nKAST_ENABLE_APP_SERVER=0\n', (self.root / 'config/environment').read_text())
        self.assertTrue(self.kast.exists())
    def test_current_configuration_retirement_does_not_inject_retired_enable_override(self):
        (self.root / 'config/environment').write_text('KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n')
        self.kast.write_text('#!/bin/sh\n'
            'test -z "${KAST_ENABLE_APP_SERVER+x}" || exit 9\n'
            'printf "%s\\n" "$*" >> "' + str(self.log) + '"\n'
            'echo \'{"status":"complete"}\'\n')
        self.manifest['payloadFiles'] = self.inventory()
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        self.assertEqual(['app-server disable', 'stop'], self.log.read_text().splitlines())

    def test_reset_removes_only_the_receipted_private_upstream_directory(self):
        run = self.root / 'state/run'
        upstream = Path('/tmp').resolve() / ('kast-codex-' + hashlib.sha256(str(run).encode()).hexdigest()[:32])
        upstream.mkdir(mode=0o700)
        def identity(path):
            observed = path.lstat()
            return {'device': observed.st_dev, 'inode': observed.st_ino, 'owner': observed.st_uid}
        receipt = run / 'upstream-directory.json'
        receipt.write_text(json.dumps({'schemaVersion': 1, 'directory': str(upstream),
            'physicalDirectory': str(run), 'directoryIdentity': identity(upstream),
            'physicalDirectoryIdentity': identity(run)}))
        receipt.chmod(0o600)
        self.manifest['externalAnchors'] = [{'kind': 'upstream-directory', 'path': str(upstream),
            'expectedPhysicalDirectory': str(run), 'identityReceipt': str(receipt),
            'ownership': 'declared-not-observed'}]
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        try:
            code, result = self.invoke('reset')
            self.assertEqual(0, code, result)
            self.assertFalse(upstream.exists())
        finally:
            if upstream.exists():
                upstream.rmdir()
    def test_uncertain_journal_blocks_before_retirement(self):
        journal = self.root / 'state/broker/profile/invocations.json'
        journal.write_text(json.dumps({'schemaVersion': 1, 'records': {'a' * 64: {'fingerprint': 'b' * 64, 'phase': 'UNCERTAIN'}}}))
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('UNRESOLVED_INVOCATION', result['failure'])
        self.assertFalse(self.log.exists())
        self.assertTrue(journal.exists())

    def test_recover_read_only_discards_only_a_structurally_matched_cancelled_query(self):
        thread = 'thread-1'
        turn = 'turn-1'
        call = 'call-1'
        identity = json.dumps([thread, turn, call], separators=(',', ':'))
        key = hashlib.sha256(identity.encode()).hexdigest()
        journal = self.root / 'state/broker/profile/invocations.json'
        journal.write_text(json.dumps({'schemaVersion': 1, 'records': {
            key: {'fingerprint': 'b' * 64, 'phase': 'UNCERTAIN'},
            'c' * 64: {'fingerprint': 'd' * 64, 'phase': 'COMPLETED'}}}))
        service_log = journal.with_name('service.log')
        service_log.write_text('\n'.join((
            json.dumps({'component': 'kast-broker', 'event': 'tool-call-started',
                'threadId': thread, 'turnId': turn, 'callId': call, 'namespace': 'kast', 'tool': 'query'}),
            json.dumps({'component': 'kast-broker', 'event': 'tool-call-finished',
                'threadId': thread, 'turnId': turn, 'callId': call, 'namespace': 'kast', 'tool': 'query',
                'completion': 'cancelled'}))) + '\n')

        code, result = self.invoke('recover-read-only')

        self.assertEqual(0, code, result)
        self.assertEqual(1, result['recoveredInvocationCount'])
        self.assertEqual({'c' * 64: {'fingerprint': 'd' * 64, 'phase': 'COMPLETED'}},
                         json.loads(journal.read_text())['records'])
        evidence = Path(result['evidence'])
        self.assertTrue(evidence.is_file())
        self.assertEqual(0o600, evidence.stat().st_mode & 0o777)
        self.assertEqual(key, json.loads(evidence.read_text())['recoveries'][0]['invocationKey'])
        self.assertEqual(['app-server disable', 'stop'], self.log.read_text().splitlines())
        code, result = self.invoke('inspect')
        self.assertEqual(0, code, result)

    def test_recover_read_only_rejects_an_unmatched_or_mutating_invocation(self):
        thread = 'thread-1'
        turn = 'turn-1'
        call = 'call-1'
        identity = json.dumps([thread, turn, call], separators=(',', ':'))
        key = hashlib.sha256(identity.encode()).hexdigest()
        journal = self.root / 'state/broker/profile/invocations.json'
        original = {'schemaVersion': 1, 'records': {
            key: {'fingerprint': 'b' * 64, 'phase': 'UNCERTAIN'}}}
        journal.write_text(json.dumps(original))
        journal.with_name('service.log').write_text(json.dumps({
            'component': 'kast-broker', 'event': 'tool-call-started', 'threadId': thread,
            'turnId': turn, 'callId': call, 'namespace': 'kast', 'tool': 'mutate'}) + '\n')

        code, result = self.invoke('recover-read-only')

        self.assertNotEqual(0, code)
        self.assertEqual('RECOVERY_REJECTED', result['failure'])
        self.assertEqual(original, json.loads(journal.read_text()))
        self.assertFalse(self.log.exists())
    def test_hosted_installation_retires_coordinator_without_retired_workspace_stop(self):
        self.manifest['schemaVersion'] = 2
        self.manifest['hostedPluginSha256'] = 'sha256:' + 'b' * 64
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        self.assertEqual(['app-server disable'], self.log.read_text().splitlines())

    def test_hosted_retirement_does_not_require_a_deleted_source_workspace(self):
        self.manifest['schemaVersion'] = 2
        self.manifest['hostedPluginSha256'] = 'sha256:' + 'b' * 64
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        (self.root / 'config/workspaces.json').write_text(json.dumps({
            'schemaVersion': 2, 'revision': 1, 'roots': [str(self.root / 'removed-worktree')]}))
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        self.assertEqual(['app-server disable'], self.log.read_text().splitlines())

    def test_hosted_installation_uses_owned_private_service_control(self):
        self.manifest['schemaVersion'] = 2
        self.manifest['hostedPluginSha256'] = 'sha256:' + 'b' * 64
        service = self.root / 'share/kast/libexec/kast-service'
        service.parent.mkdir(parents=True)
        service.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "' + str(self.log) + '"\n')
        service.chmod(0o700)
        self.manifest['payloadFiles'].append({'path': 'share/kast/libexec/kast-service',
            'sha256': 'sha256:' + hashlib.sha256(service.read_bytes()).hexdigest(), 'mode': 448})
        self.manifest['payloadFiles'].sort(key=lambda entry: entry['path'])
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        self.assertEqual(['disable'], self.log.read_text().splitlines())

    def test_failed_retirement_preserves_state(self):
        self.kast.write_text('#!/bin/sh\nexit 7\n')
        self.manifest['payloadFiles'] = self.inventory()
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('RETIREMENT_UNPROVEN', result['failure'])
        self.assertEqual({'stage': 'coordinator-retirement', 'outcome': 'exit-rejected'}, result['retirement'])
        self.assertEqual(self.epoch, json.loads((self.root / 'state/epoch.json').read_text()))
        self.assertEqual('KAST_INDEXER_MAX_HEAP=8g\nKAST_ENABLE_APP_SERVER=0\n', (self.root / 'config/environment').read_text())
        self.assertEqual(self.manifest, json.loads((self.root / 'installation.json').read_text()))
        self.assertEqual('#!/bin/sh\nexit 7\n', self.kast.read_text())
        self.assertTrue((self.root / 'state/broker/profile').is_dir())
        self.assertTrue(self.workspace.is_dir())
        self.assertTrue((self.root / '.lifecycle-transition.json').is_file())
    def test_unresolved_worker_receipt_blocks_reset_even_after_stop_reports_success(self):
        workers = self.root / 'state/workers'
        workers.mkdir()
        receipt = workers / 'pending.json'
        receipt.write_text(json.dumps({'phase': 'QUARANTINED_STARTUP'}))
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('RETIREMENT_UNPROVEN', result['failure'])
        self.assertEqual(['app-server disable', 'stop'], self.log.read_text().splitlines())
        self.assertTrue(receipt.exists())
        self.assertEqual(self.epoch, json.loads((self.root / 'state/epoch.json').read_text()))
        self.assertTrue((self.root / '.lifecycle-transition.json').is_file())
    def test_manifest_cannot_widen_deletion_root(self):
        self.manifest['stateRoot'] = str(self.outer)
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('MANIFEST_REJECTED', result['failure'])
        self.assertTrue(self.kast.exists())
    def test_external_cache_is_rejected_without_retiring_or_deleting_it(self):
        (self.root / 'config/environment').write_text('KAST_CACHE_ROOT=' + str(self.workspace) + '\n')
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('EXTERNAL_STATE_UNPROVEN', result['failure'])
        self.assertFalse(self.log.exists())
    def test_missing_registry_does_not_manufacture_zero_workers(self):
        (self.root / 'config/workspaces.json').unlink()
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('REGISTRY_REJECTED', result['failure'])
        self.assertFalse(self.log.exists())
    def test_stopped_broker_without_registry_has_no_workspace_roots(self):
        (self.root / 'config/workspaces.json').unlink()
        profile = self.root / 'state/broker' / ('a' * 16)
        profile.mkdir()
        (profile / 'stopped').write_bytes(b'stopped\n')
        (profile / 'launch-environment').write_text('private fixture')
        (profile / 'service-start.lock').touch()
        shutil.rmtree(self.root / 'state/broker/profile')
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        self.assertEqual('reset', result['status'])
        self.assertEqual(0, result['workspaceCount'])
        self.assertEqual(['app-server disable'], self.log.read_text().splitlines())
    def test_initialized_stopped_broker_without_registry_retires_without_workspace_stop(self):
        (self.root / 'config/workspaces.json').unlink()
        shutil.rmtree(self.root / 'state/broker/profile')
        profile = initialized_stopped_profile(self.root / 'state')
        (profile / 'launch-environment').write_text('private fixture')
        (profile / 'service-start.lock').touch()
        (profile / 'service.log').touch()
        (profile / 'service.plist').write_text('fixture')
        code, result = self.invoke('reset')
        self.assertEqual(0, code, result)
        self.assertEqual(0, result['workspaceCount'])
        self.assertEqual(['app-server disable'], self.log.read_text().splitlines())
    def test_unknown_broker_evidence_without_registry_blocks_retirement(self):
        (self.root / 'config/workspaces.json').unlink()
        profile = self.root / 'state/broker' / ('a' * 16)
        profile.mkdir()
        (profile / 'stopped').write_bytes(b'stopped\n')
        (profile / 'unexpected-worker').write_text('unproven')
        shutil.rmtree(self.root / 'state/broker/profile')
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('REGISTRY_REJECTED', result['failure'])
        self.assertFalse(self.log.exists())
    def test_hidden_journal_directory_is_rejected(self):
        (self.root / 'state/hidden').symlink_to(self.workspace, target_is_directory=True)
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('STATE_REJECTED', result['failure'])
    def test_payload_admission_defers_live_state_inspection(self):
        (self.root / 'state/live-link').symlink_to(self.workspace, target_is_directory=True)
        code, result = self.invoke('inspect-payload')
        self.assertEqual(0, code, result)
        self.assertEqual({'type': 'PAYLOAD_ADMITTED', 'payloadIdentity': self.manifest['payloadIdentity']}, result)
        self.assertEqual({'type': 'PAYLOAD_ADMITTED'}, json.loads(self.last_stderr))
        self.assertFalse(self.log.exists())
        code, result = self.invoke('inspect')
        self.assertNotEqual(0, code)
        self.assertEqual('STATE_REJECTED', result['failure'])
        self.assertEqual({'type': 'ADMISSION_REJECTED', 'failure': 'STATE_REJECTED'},
                         json.loads(self.last_stderr))
    def test_payload_admission_rejects_changed_executable_without_retirement(self):
        self.kast.write_text('#!/bin/sh\nexit 0\n')
        code, result = self.invoke('inspect-payload')
        self.assertNotEqual(0, code)
        self.assertEqual('MANIFEST_REJECTED', result['failure'])
        self.assertEqual({'type': 'ADMISSION_REJECTED', 'failure': 'MANIFEST_REJECTED'},
                         json.loads(self.last_stderr))
        self.assertFalse(self.log.exists())
    def test_changed_payload_is_rejected_before_execution(self):
        self.kast.write_text('#!/bin/sh\nexit 0\n')
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('MANIFEST_REJECTED', result['failure'])
        self.assertFalse(self.log.exists())
    def test_state_symlink_cannot_redirect_reset(self):
        import shutil
        shutil.rmtree(self.root / 'state')
        (self.root / 'state').symlink_to(self.workspace, target_is_directory=True)
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('STATE_REJECTED', result['failure'])
        self.assertTrue(self.workspace.is_dir())


class SingleInstallationLifecycleTest(unittest.TestCase):
    def setUp(self):
        fixture = tempfile.TemporaryDirectory(prefix='kast-single-lifecycle-')
        self.addCleanup(fixture.cleanup)
        self.outer = Path(fixture.name).resolve() / 'kast'
        self.root = self.outer / 'installation'
        (self.root / 'bin').mkdir(parents=True)
        executable = self.root / 'bin/kast-complete'
        executable.write_text('#!/bin/sh\nexit 0\n')
        executable.chmod(0o700)
        self.manifest = {'schemaVersion': 3, 'semanticVersion': '1.0.0',
            'installationRoot': str(self.root), 'payloadIdentity': 'sha256:' + 'a' * 64,
            'stateRoot': str(self.root / 'state'), 'configuration': str(self.root / 'config/environment'),
            'workspaceRegistry': str(self.root / 'config/workspaces.json'), 'externalAnchors': [],
            'payloadFiles': [{'path': 'bin/kast-complete', 'sha256': 'sha256:' + hashlib.sha256(executable.read_bytes()).hexdigest(), 'mode': 0o700}]}
        self.save()

    def save(self):
        (self.root / 'installation.json').write_text(json.dumps(self.manifest))

    def test_one_ordinary_payload_is_admitted_without_selector(self):
        admitted = lifecycle.Installation.admit(str(self.root))
        lifecycle.require_selected(admitted)
        self.assertEqual(self.outer, admitted.managed_root)
        self.assertEqual([], lifecycle.prune_other_versions(admitted, False).removed)
        self.assertFalse((self.outer / 'versions').exists())
        self.assertFalse((self.outer / 'current').exists())

    def test_selector_anchor_is_rejected_and_preserved_in_schema_three(self):
        foreign = self.outer / 'current'
        foreign.write_text('protected foreign entry')
        self.manifest['externalAnchors'] = [{'kind': 'current', 'path': str(foreign), 'expectedLinkTarget': 'versions/old'}]
        self.save()
        state = self.root / 'state'
        state.mkdir()
        evidence = state / 'protected.txt'
        evidence.write_text('protected invocation')
        identity = lifecycle.FileIdentity.observe(state)
        with self.assertRaises(lifecycle.Rejected) as rejected:
            lifecycle.Installation.admit(str(self.root))
        self.assertEqual(lifecycle.Failure.MANIFEST_REJECTED, rejected.exception.failure)
        self.assertEqual(identity, lifecycle.FileIdentity.observe(state))
        self.assertEqual('protected invocation', evidence.read_text())
        self.assertEqual('protected foreign entry', foreign.read_text())

    def test_pending_replacement_blocks_removal_and_preserves_both_payloads(self):
        pending = self.outer / 'recovery/replacement/payload'
        pending.mkdir(parents=True)
        admitted = lifecycle.Installation.admit(str(self.root))
        with self.assertRaises(lifecycle.Rejected) as rejected:
            lifecycle.execute(admitted, 'remove', False)
        self.assertEqual(lifecycle.Failure.RECOVERY_REJECTED, rejected.exception.failure)
        self.assertTrue(pending.is_dir())
        self.assertTrue(self.root.is_dir())

    def removal_journal(self):
        path = self.outer.parent / ('.kast-uninstall-' + hashlib.sha256(str(self.outer).encode()).hexdigest() + '.json')
        record = {'installationRoot': str(self.outer), 'executable': str(self.outer.parent / 'kast'),
            'executableSha256': 'a' * 64, 'channel': 'STABLE', 'type': 'REMOVAL_REQUESTED',
            'managedRootIdentity': lifecycle.asdict(lifecycle.FileIdentity.observe(self.outer)),
            'controlIdentity': lifecycle.asdict(lifecycle.FileIdentity.observe(self.root)),
            'homeConfiguration': {'type': 'ABSENT_CONFIGURATION'}, 'recoveryProof': {'type': 'ABSENT_RECOVERY'}}
        path.write_text(json.dumps(record))
        path.chmod(0o600)
        return path, record

    def test_retirement_proof_survives_failure_after_payload_deletion_before_parent_return(self):
        journal, expected = self.removal_journal()
        admitted = lifecycle.Installation.admit(str(self.root))
        original = lifecycle.delete_tree
        def fail_after_delete(path):
            original(path)
            if path == self.root:
                raise OSError('controlled parent-return boundary failure')
        with patch.object(lifecycle, 'delete_tree', side_effect=fail_after_delete):
            with self.assertRaises(OSError):
                lifecycle.execute(admitted, 'remove', False, journal)
        self.assertFalse(self.root.exists())
        expected['type'] = 'CONTROL_RETIRED'
        self.assertEqual(expected, json.loads(journal.read_text()))

    def test_failed_retirement_keeps_requested_journal_and_exact_state(self):
        state = self.root / 'state'
        state.mkdir(mode=0o700)
        identity = lifecycle.FileIdentity.observe(state)
        journal, expected = self.removal_journal()
        admitted = lifecycle.Installation.admit(str(self.root))
        failure = lifecycle.retirement_unproven(lifecycle.RetirementStage.COORDINATOR,
                                                lifecycle.RetirementOutcome.EXIT_REJECTED)
        with patch.object(lifecycle, 'retire', side_effect=failure) as retirement:
            with self.assertRaises(lifecycle.Rejected) as rejected:
                lifecycle.execute(admitted, 'remove', False, journal)
            retirement.assert_called_once_with(admitted, [])
        self.assertEqual(lifecycle.RetirementRejection(lifecycle.RetirementStage.COORDINATOR,
            lifecycle.RetirementOutcome.EXIT_REJECTED), rejected.exception.retirement)
        self.assertEqual(identity, lifecycle.FileIdentity.observe(state))
        self.assertEqual(expected, json.loads(journal.read_text()))
        self.assertTrue(self.root.exists())

    def test_changed_control_identity_rejects_before_state_or_payload_deletion(self):
        journal, record = self.removal_journal()
        record['controlIdentity']['inode'] += 1
        journal.write_text(json.dumps(record))
        admitted = lifecycle.Installation.admit(str(self.root))
        with self.assertRaises(lifecycle.Rejected) as rejected:
            lifecycle.execute(admitted, 'remove', False, journal)
        self.assertEqual(lifecycle.Failure.RECOVERY_REJECTED, rejected.exception.failure)
        self.assertTrue(self.root.exists())
        self.assertEqual('REMOVAL_REQUESTED', json.loads(journal.read_text())['type'])

    def test_ordinary_payload_removal_keeps_managed_parent_and_foreign_state(self):
        protected = self.outer / 'management.json'
        protected.write_text('protected receipt')
        admitted = lifecycle.Installation.admit(str(self.root))
        lifecycle.execute(admitted, 'remove', False)
        self.assertFalse(self.root.exists())
        self.assertEqual('protected receipt', protected.read_text())


if __name__ == '__main__':
    unittest.main()
