"""Offline ownership-transition proofs; never selects a real installation."""
import sys
import json
import hashlib
import importlib.util
import io
from contextlib import redirect_stderr
from unittest.mock import patch
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

SCRIPT = Path(__file__).with_name('installation-lifecycle.py')
spec = importlib.util.spec_from_file_location('lifecycle_under_test', SCRIPT)
lifecycle = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = lifecycle
spec.loader.exec_module(lifecycle)


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
    def test_hidden_journal_directory_is_rejected(self):
        (self.root / 'state/hidden').symlink_to(self.workspace, target_is_directory=True)
        code, result = self.invoke('reset')
        self.assertNotEqual(0, code)
        self.assertEqual('STATE_REJECTED', result['failure'])
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

if __name__ == '__main__':
    unittest.main()
