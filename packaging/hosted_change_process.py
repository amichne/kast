"""Owned native processes and strict event transport for hosted change acceptance."""
import hashlib
from enum import Enum
import json
import os
from pathlib import Path
import selectors
import re
import subprocess
import time

from acceptance_environment import GradleRetirement
from hosted_generated_fixture import amend_generated_provenance
from native_fixture_probe import NativeFixtureProbe, NativeFixtureProbeError
from hosted_change_acceptance import (AcceptanceFailure, AcceptanceRejected, admitted_live,
                                      admit_event, event_observation, pending_readiness)


def private_file(path: Path):
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb')


class NativeSetupPhase(Enum):
    AWAITING_NATIVE_OBSERVATION = 'awaiting-native-observation'
    NATIVE_OBSERVATION_COMPLETE = 'native-observation-complete'


class SetupProbeFailure(Enum):
    MALFORMED_RESPONSE = 'MALFORMED_RESPONSE'
    RESPONSE_CORRELATION_REJECTED = 'RESPONSE_CORRELATION_REJECTED'
    PROBE_TIMEOUT = 'PROBE_TIMEOUT'
    TRANSPORT_REJECTED = 'TRANSPORT_REJECTED'

    @classmethod
    def classify(cls, error):
        return next((failure for failure in cls if failure.value == str(error)), cls.TRANSPORT_REJECTED)


class NativeProcesses:
    def __init__(self, isolation, fixture, product, readiness_seconds):
        self.isolation, self.fixture, self.product = isolation, fixture, product
        self.readiness_seconds = readiness_seconds
        self.ide = None
        self.generation = 0
        self.native = None
        self.readiness_observations = []
        self.ide_log_start = 0
        self.generated_fixture = None

    def start_ide(self):
        self.generation += 1
        log = self.isolation.root / 'ide/log/idea.log'
        self.ide_log_start = log.stat().st_size if log.exists() else 0
        with private_file(self.isolation.root / f'ide/stdout-{self.generation}.log') as output, \
                private_file(self.isolation.root / f'ide/stderr-{self.generation}.log') as error:
            self.ide = self.isolation.spawn(self.fixture.command, cwd=self.fixture.workspace,
                env=self.fixture.environment, stdout=output, stderr=error)
        return self.ready()

    def ready(self):
        deadline = time.monotonic() + self.readiness_seconds
        setup_phase = NativeSetupPhase.AWAITING_NATIVE_OBSERVATION
        root_digest = hashlib.sha256(str(self.fixture.workspace).encode()).hexdigest()[:32]
        endpoint = self.isolation.root / 'home/.kast/ide-hosted' / root_digest / 'endpoint.json'
        while time.monotonic() < deadline:
            if self.ide.poll() is not None:
                raise AcceptanceRejected(AcceptanceFailure.NATIVE_PROCESS)
            self.reject_failed_bind()
            if endpoint.is_file():
                if endpoint.stat().st_size > 16384 or endpoint.is_symlink():
                    raise AcceptanceRejected(AcceptanceFailure.READINESS)
                descriptor = json.loads(endpoint.read_text())
                if descriptor.get('hostPid') != self.ide.pid:
                    time.sleep(0.1)
                    continue
                result = subprocess.run([str(self.product / 'bin/kast'), 'tool', 'search_classes'],
                    cwd=self.fixture.workspace, env=self.fixture.environment,
                    input=json.dumps({'class_name': 'NativeChangeTarget', 'name_match': None, 'scope': None}),
                    capture_output=True, text=True, timeout=30)
                if len(result.stdout) > 4 * 1024 * 1024:
                    raise AcceptanceRejected(AcceptanceFailure.READINESS)
                value = json.loads(result.stdout)
                if result.returncode == 0 and value.get('status') == 'complete' and len(value.get('items', [])) == 1:
                    live = admitted_live(value.get('live'), self.fixture.workspace)
                    if setup_phase is NativeSetupPhase.AWAITING_NATIVE_OBSERVATION:
                        self.observe_setup()
                        setup_phase = NativeSetupPhase.NATIVE_OBSERVATION_COMPLETE
                        continue
                    with private_file(self.isolation.root / f'ready-{self.generation}.private.json') as output:
                        output.write(json.dumps({'live': live, 'resultCount': 1,
                            'responseSha256': hashlib.sha256(result.stdout.encode()).hexdigest()}).encode())
                    return live
                if not pending_readiness(value):
                    raise AcceptanceRejected(AcceptanceFailure.READINESS)
            time.sleep(0.5)
        raise AcceptanceRejected(AcceptanceFailure.READINESS_TIMEOUT)

    def observe_setup(self):
        command = 'AWAIT_SETUP_READY' if self.generation == 1 else 'AWAIT_REOPEN_READY'
        current_digest = hashlib.sha256(self.fixture.source.read_bytes()).hexdigest()
        try:
            response = NativeFixtureProbe(self.isolation.root, self.fixture.workspace).request(command, current_digest, timeout=65)
        except NativeFixtureProbeError as error:
            self.readiness_observations.append({'generation': self.generation, 'stage': 'SETUP_READINESS',
                'outcome': 'REJECTED', 'failure': SetupProbeFailure.classify(error).value})
            raise AcceptanceRejected(AcceptanceFailure.READINESS) from None
        if response['outcome'] != 'SETUP_READY':
            self.readiness_observations.append({'generation': self.generation, 'stage': 'SETUP_READINESS',
                'outcome': 'REJECTED', 'failure': response['failure']})
            raise AcceptanceRejected(AcceptanceFailure.READINESS)
        self.readiness_observations.append({'generation': self.generation, 'stage': 'SETUP_READINESS',
            'outcome': 'OBSERVED', 'readiness': response['readiness'],
            'sourceSavedSha256': response['evidence']['savedSha256']})

    def reject_failed_bind(self):
        log = self.isolation.root / 'ide/log/idea.log'
        if not log.is_file():
            return
        with log.open('rb') as source:
            source.seek(self.ide_log_start)
            observed = source.read(4 * 1024 * 1024)
        match = re.search(rb'kast_hosted stage=BIND outcome=REJECTED failure=([A-Z_]+)', observed)
        if match:
            condition = match.group(1).decode('ascii')
            self.readiness_observations.append({'generation': self.generation, 'stage': 'BIND', 'outcome': 'REJECTED',
                'failure': condition if condition == 'OWNERSHIP_CONFLICT' else 'OTHER_BIND_REJECTION'})
            raise AcceptanceRejected(AcceptanceFailure.READINESS)

    @staticmethod
    def stop(process):
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def retire(self):
        self.stop(self.native)
        self.stop(self.ide)
        daemons = self.isolation.capture_gradle_daemons()
        result = subprocess.run([str(self.isolation.tools['bash']), str(self.fixture.workspace / 'gradlew'), '--stop'],
            cwd=self.fixture.workspace, env=self.fixture.environment, capture_output=True, timeout=60)
        if result.returncode != 0 or self.isolation.await_gradle_retirement(daemons) is not GradleRetirement.RETIRED:
            raise AcceptanceRejected(AcceptanceFailure.RETIREMENT)

    def await_retirement(self, log, offset):
        deadline = time.monotonic() + 30
        root_digest = hashlib.sha256(str(self.fixture.workspace).encode()).hexdigest()[:32]
        endpoint = self.isolation.root / 'home/.kast/ide-hosted' / root_digest / 'endpoint.json'
        while time.monotonic() < deadline:
            with log.open('rb') as source:
                source.seek(offset)
                observation = source.read(1024 * 1024)
            if b'kast_hosted stage=RETIREMENT outcome=COMPLETED' in observation and not endpoint.exists():
                return
            time.sleep(0.05)
        raise AcceptanceRejected(AcceptanceFailure.RETIREMENT)

    def replace_broker(self, java, harness, schemas, event):
        directory = self.isolation.root / 'replacement-broker'
        directory.mkdir(mode=0o700)
        command = [str(java), '-cp', str(self.product / 'lib/*') + os.pathsep + str(harness),
            'io.github.amichne.kast.appserver.acceptance.hostedchange.NativeBrokerRestartMain',
            str(self.product), str(self.fixture.workspace), str(schemas), str(directory), str(directory / 'report.json')]
        with private_file(directory / 'stderr.log') as error:
            replacement = self.isolation.spawn(command, cwd=self.fixture.workspace, env=self.fixture.environment,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=error)
            try:
                output, _ = replacement.communicate(json.dumps({key: event[key] for key in ('planIdentity', 'sourceSha256')}).encode() + b'\n', timeout=190)
            except subprocess.TimeoutExpired:
                self.stop(replacement)
                raise AcceptanceRejected(AcceptanceFailure.NATIVE_TIMEOUT) from None
        if replacement.returncode != 0 or len(output) > 4096:
            raise AcceptanceRejected(AcceptanceFailure.NATIVE_PROCESS)
        response = json.loads(output)
        if (set(response) != {'outcome', 'evidence'} or response['outcome'] != 'PASSED'
                or not {'sourcePreimageSha256', 'sourcePostimageSha256', 'retrievedState'} <= set(response['evidence'])
                or not set(response['evidence']) <= {'sourcePreimageSha256', 'sourcePostimageSha256', 'retrievedState', 'receiptIdentitySha256'}
                or response['evidence']['retrievedState'] not in ('verified', 'applied_unverified', 'recovery_required')
                or ('receiptIdentitySha256' in response['evidence'] and not re.fullmatch('[0-9a-f]{64}', response['evidence']['receiptIdentitySha256']))
                or response['evidence']['sourcePreimageSha256'] != event['sourceSha256']
                or response['evidence']['sourcePostimageSha256'] != event['sourceSha256']):
            raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
        return response

    def run(self, java, harness, schemas, private, report, seconds, record):
        command = [str(java), '-cp', str(self.product / 'lib/*') + os.pathsep + str(harness),
            'io.github.amichne.kast.appserver.acceptance.hostedchange.NativeHostedChangeMain',
            str(self.product), str(self.fixture.workspace), str(schemas), str(private), str(report)]
        deadline, pending, completed = time.monotonic() + seconds, b'', False
        with private_file(private / 'stdout.log') as output, private_file(private / 'stderr.log') as error:
            self.native = self.isolation.spawn(command, cwd=self.fixture.workspace, env=self.fixture.environment,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=error)
            with selectors.DefaultSelector() as selector:
                selector.register(self.native.stdout, selectors.EVENT_READ)
                while selector.get_map():
                    if time.monotonic() >= deadline:
                        raise AcceptanceRejected(AcceptanceFailure.NATIVE_TIMEOUT)
                    for key, _ in selector.select(timeout=0.5):
                        chunk = os.read(key.fileobj.fileno(), 4096)
                        if not chunk:
                            selector.unregister(key.fileobj)
                            continue
                        pending += chunk
                        if len(pending) > 8192:
                            raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
                        while b'\n' in pending:
                            line, pending = pending.split(b'\n', 1)
                            event = admit_event(json.loads(line))
                            observation = event_observation(event)
                            output.write((json.dumps(observation, separators=(',', ':')) + '\n').encode())
                            output.flush()
                            record(observation)
                            if event == {'event': 'control', 'action': 'restart-ide'}:
                                self.stop(self.ide)
                                self.start_ide()
                                record({"event": "stage", "stage": "owner-reopened", "outcome": "completed"})
                                self.native.stdin.write(b'restart-completed\n')
                                self.native.stdin.flush()
                            if event.get('action') == 'probe':
                                retirement_log = self.isolation.root / 'ide/log/idea.log'
                                retirement_offset = retirement_log.stat().st_size
                                response = NativeFixtureProbe(self.isolation.root, self.fixture.workspace).request(
                                    event['command'], event['preimageSha256'], event.get('postimageSha256'))
                                if event['command'] == 'UNLOAD_PRODUCTION_PLUGIN' and response['outcome'] == 'LIFECYCLE_COMPLETED':
                                    self.await_retirement(retirement_log, retirement_offset)
                                    record({'event': 'stage', 'stage': 'plugin-owner-retired', 'outcome': 'completed'})
                                self.native.stdin.write((json.dumps(response, separators=(',', ':')) + '\n').encode())
                                self.native.stdin.flush()
                            if event.get('action') == 'amend-generated-provenance':
                                if self.generated_fixture is None:
                                    raise AcceptanceRejected(AcceptanceFailure.INPUT)
                                source_digest = hashlib.sha256(self.fixture.source.read_bytes()).hexdigest()
                                if source_digest != event['sourceSha256']:
                                    raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
                                amendment = amend_generated_provenance(self.generated_fixture)
                                response = NativeFixtureProbe(self.isolation.root, self.fixture.workspace).request(
                                    'REIMPORT_GRADLE', source_digest, timeout=180,
                                    expected_build_sha256=amendment['afterSha256'])
                                if response['outcome'] != 'SETUP_READY':
                                    raise AcceptanceRejected(AcceptanceFailure.READINESS)
                                record({'event': 'stage', 'stage': 'gradle-model-reimported', 'outcome': 'completed'})
                                result = {'outcome': 'MODEL_REIMPORTED', 'evidence': {
                                    'buildSha256': amendment['afterSha256'], 'readiness': response['readiness']}}
                                self.native.stdin.write((json.dumps(result, separators=(',', ':')) + '\n').encode())
                                self.native.stdin.flush()
                            if event.get('action') == 'wait-save-barrier-restart':
                                NativeFixtureProbe(self.isolation.root, self.fixture.workspace).await_save_barrier(
                                    event['barrierId'], event['preimageSha256'], event['postimageSha256'])
                                if self.ide.poll() is not None:
                                    raise AcceptanceRejected(AcceptanceFailure.NATIVE_PROCESS)
                                self.ide.kill()
                                self.ide.wait(timeout=5)
                                record({'event': 'stage', 'stage': 'post-save-interrupted', 'outcome': 'completed'})
                                self.start_ide()
                                self.native.stdin.write(b'barrier-restart-completed\n')
                                self.native.stdin.flush()
                            if event.get('action') == 'replace-broker':
                                response = self.replace_broker(java, harness, schemas, event)
                                record({'event': 'stage', 'stage': 'fixture-broker-process-replaced', 'outcome': 'completed'})
                                self.native.stdin.write((json.dumps(response, separators=(',', ':')) + '\n').encode())
                                self.native.stdin.flush()
                            if event == {'event': 'completed'}:
                                completed = True
            if pending or self.native.wait(timeout=10) != 0 or not completed:
                raise AcceptanceRejected(AcceptanceFailure.NATIVE_PROCESS)
