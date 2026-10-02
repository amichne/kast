#!/usr/bin/env python3
"""Opt-in native proof of independent control upgrades and host installation.

Requires four component release inventories and their checksums. Runs as the current
UID in a new private HOME and IDEA profile; never selects the daily profile.
No fake model, extracted plugin shortcut, hot reload, or semantic substitute is
used. The normal installer and installed Tool RPC own every product decision.
This is deliberately outside routine packaging and unit-test inventories.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import stat
import struct
import subprocess
import sys
import time
from xml.sax.saxutils import quoteattr


class Stage(str, Enum):
    INPUTS = 'INPUTS'
    INSTALL_C1_P1 = 'INSTALL_C1_P1'
    UNAVAILABLE_PREFLIGHT = 'UNAVAILABLE_PREFLIGHT'
    START_P1 = 'START_P1'
    QUERY_C1_P1 = 'QUERY_C1_P1'
    UPGRADE_C2 = 'UPGRADE_C2'
    QUERY_C2_P1 = 'QUERY_C2_P1'
    INSTALL_P2 = 'INSTALL_P2'
    RESTART_P2 = 'RESTART_P2'
    QUERY_C2_P2 = 'QUERY_C2_P2'
    CLEANUP = 'CLEANUP'


class Failure(str, Enum):
    INPUT_REJECTED = 'INPUT_REJECTED'
    INSTALL_REJECTED = 'INSTALL_REJECTED'
    UNAVAILABLE_PREFLIGHT_DID_NOT_PRESERVE_CONTROL = 'UNAVAILABLE_PREFLIGHT_DID_NOT_PRESERVE_CONTROL'
    NATIVE_PROCESS_EXITED = 'NATIVE_PROCESS_EXITED'
    NATIVE_ENDPOINT_UNAVAILABLE = 'NATIVE_ENDPOINT_UNAVAILABLE'
    OWNERSHIP_REJECTED = 'OWNERSHIP_REJECTED'
    LIVE_DESCRIPTION_REJECTED = 'LIVE_DESCRIPTION_REJECTED'
    SEMANTIC_QUERY_REJECTED = 'SEMANTIC_QUERY_REJECTED'
    CONTROL_UPGRADE_CHANGED_HOST = 'CONTROL_UPGRADE_CHANGED_HOST'
    HOST_INSTALL_CHANGED_CONTROL = 'HOST_INSTALL_CHANGED_CONTROL'
    CONTROL_STATUS_REJECTED = 'CONTROL_STATUS_REJECTED'
    COMMAND_TIMED_OUT = 'COMMAND_TIMED_OUT'
    CLEANUP_UNVERIFIED = 'CLEANUP_UNVERIFIED'


class Rejected(Exception):
    def __init__(self, failure: Failure, exit_code: int | None = None):
        self.failure, self.exit_code = failure, exit_code


@dataclass(frozen=True)
class Progress:
    type: str
    stage: Stage
    outcome: str


@dataclass(frozen=True)
class Artifact:
    component: str
    version: str
    archive: str
    sha256: str


@dataclass(frozen=True)
class FileFact:
    relative_path: str
    device: int
    inode: int
    owner: int
    mode: int
    size: int
    sha256: str


@dataclass(frozen=True)
class HostProof:
    pid: int
    process_start: str
    host: str
    hosted_plugin_version: str
    root: str
    socket_device: int
    socket_inode: int
    contract_sha256: str


@dataclass(frozen=True)
class QuerySource:
    type: str = 'SEARCH_DECLARATIONS'
    declarationName: str = 'MixedVersionProof'
    declarationKinds: tuple[str, ...] = ('CLASS',)


@dataclass(frozen=True)
class QueryOutput:
    type: str = 'SYMBOLS'
    fields: tuple[str, ...] = ('NAME', 'LOCATION', 'SIGNATURE')


@dataclass(frozen=True)
class QueryRun:
    type: str
    source: QuerySource
    output: QueryOutput


@dataclass(frozen=True)
class QueryCall:
    request: QueryRun
    verbose: bool = True


@dataclass(frozen=True)
class DescribeRequest:
    root: str
    type: str = 'DESCRIBE'


@dataclass(frozen=True)
class QueryProof:
    control_version: str
    host: str
    epoch: int
    qualified_identity: str
    result_count: int


@dataclass(frozen=True)
class ControlProof:
    pid: int
    process_start: str
    service_generation: str


@dataclass(frozen=True)
class Passed:
    type: str
    uid: int
    isolation: str
    owned_root: str
    idea_build: str
    artifacts: tuple[Artifact, ...]
    host_before: HostProof
    host_after_control_upgrade: HostProof
    host_after_host_restart: HostProof
    plugin_p1_files: tuple[FileFact, ...]
    query_c1_p1: QueryProof
    query_c2_p1: QueryProof
    query_c2_p2: QueryProof
    control_c1: ControlProof
    control_c2: ControlProof
    control_after_host_install: ControlProof
    unavailable_preflight_preserved_control: bool
    host_install_preserved_control: bool
    cleanup_failures: tuple[Failure, ...]


@dataclass(frozen=True)
class Failed:
    type: str
    uid: int
    owned_root: str
    stage: Stage
    failure: Failure
    exit_code: int | None
    cleanup_failures: tuple[Failure, ...]


def encode(document) -> str:
    return json.dumps(asdict(document), default=lambda value: value.value, indent=2) + '\n'


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def process_start(pid: int) -> str:
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'uid=', '-o', 'lstart='],
                            capture_output=True, text=True, check=True, timeout=5)
    match = re.fullmatch(r'\s*(\d+)\s+(.+?)\s*', result.stdout)
    if match is None or int(match.group(1)) != os.getuid():
        raise Rejected(Failure.OWNERSHIP_REJECTED)
    return match.group(2)


def files(root: Path, selected: tuple[str, ...] | None = None) -> tuple[FileFact, ...]:
    paths = sorted(root.rglob('*')) if selected is None else sorted(
        path for name in selected for path in (root / name).rglob('*')
    ) + [root / 'installation.json']
    facts = []
    for path in paths:
        observed = path.lstat()
        if observed.st_uid != os.getuid() or stat.S_ISLNK(observed.st_mode):
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        if stat.S_ISDIR(observed.st_mode):
            continue
        if not stat.S_ISREG(observed.st_mode):
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        facts.append(FileFact(str(path.relative_to(root)), observed.st_dev, observed.st_ino,
                              observed.st_uid, stat.S_IMODE(observed.st_mode), observed.st_size, digest(path)))
    if not facts or len(facts) > 4096:
        raise Rejected(Failure.OWNERSHIP_REJECTED)
    return tuple(sorted(facts, key=lambda fact: fact.relative_path))


class NativeProof:
    def __init__(self, args):
        self.args = args
        self.root = args.owned_root
        self.home = self.root / 'home'
        self.workspace = self.root / 'workspace'
        self.install_root = self.home / '.local/share/kast'
        self.installation = self.install_root / 'installation'
        self.stage = Stage.INPUTS
        self.idea: subprocess.Popen | None = None
        self.idea_start: str | None = None
        self.commands = 0
        self.control_installed = False
        self.env = dict(os.environ)
        for key in tuple(self.env):
            if key.startswith('KAST_') or key in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
                                                  'IDEA_VM_OPTIONS', 'CODEX_HOME', 'GRADLE_USER_HOME'):
                del self.env[key]
        self.env.update(HOME=str(self.home), XDG_DATA_HOME=str(self.home / '.local/share'),
                        XDG_CONFIG_HOME=str(self.home / '.config'), CODEX_HOME=str(self.home / '.codex'),
                        GRADLE_USER_HOME=str(self.home / '.gradle'), KAST_INSTALL_ROOT=str(self.install_root),
                        KAST_BIN_DIR=str(self.home / '.local/bin'), KAST_INSTALL_PROFILE='persistent',
                        KAST_INSTALL_ASSETS_DIRECTORY=str(args.assets),
                        KAST_OPTS=f'-Duser.home={self.home}', JAVA_OPTS=f'-Duser.home={self.home}',
                        JAVA_TOOL_OPTIONS=f'-Duser.home={self.home} -Djava.io.tmpdir={self.root / "tmp"}',
                        JAVA_HOME=str(args.idea_home / 'jbr/Contents/Home'), NO_COLOR='1', KAST_ASCII='1')

    def progress(self, stage: Stage, outcome: str = 'STARTED'):
        self.stage = stage
        event = Progress('NATIVE_ACCEPTANCE_STAGE', stage, outcome)
        encoded = encode(event)
        with (self.root / 'progress.jsonl').open('a') as output:
            output.write(json.dumps(asdict(event)) + '\n')
        print(encoded, flush=True)

    def command(self, arguments, *, input_text=None, timeout=300, reject=Failure.INSTALL_REJECTED,
                expected_nonzero=False) -> subprocess.CompletedProcess:
        self.commands += 1
        prefix = self.root / 'logs' / f'{self.commands:02d}-{self.stage.value.lower()}'
        try:
            result = subprocess.run([str(argument) for argument in arguments], cwd=self.workspace,
                                    env=self.env, input=input_text, capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise Rejected(Failure.COMMAND_TIMED_OUT) from None
        prefix.with_suffix('.stdout').write_text(result.stdout)
        prefix.with_suffix('.stderr').write_text(result.stderr)
        for path in (prefix.with_suffix('.stdout'), prefix.with_suffix('.stderr')):
            path.chmod(0o600)
        if (result.returncode != 0) != expected_nonzero:
            raise Rejected(reject, result.returncode)
        return result

    def installer(self, version, component=None, host_version=None, *, expected_nonzero=False):
        command = ['/bin/bash', self.args.repository / 'install.sh', '--idea-home', self.args.idea_home,
                   '--version', version, '--skip-codex-mcp', '--verbose']
        if component:
            command.append('--' + component + '-only')
        if host_version:
            command.extend(('--host-version', host_version))
        return self.command(command, expected_nonzero=expected_nonzero)

    def setup(self):
        for directory in (self.home, self.workspace, self.root / 'logs', self.root / 'ide/config/options',
                          self.root / 'ide/system', self.root / 'ide/log', self.root / 'tmp'):
            directory.mkdir(parents=True, mode=0o700, exist_ok=True)
        self.metadata = json.loads((self.args.idea_home / 'Resources/product-info.json').read_text())
        self.plugins = self.home / 'Library/Application Support/JetBrains' / self.metadata['dataDirectoryName'] / 'plugins'
        (self.workspace / 'settings.gradle.kts').write_text('rootProject.name = "mixed-version-proof"\n')
        (self.workspace / 'build.gradle.kts').write_text(
            'plugins { kotlin("jvm") version "2.4.20" }\nrepositories { mavenCentral() }\n')
        (self.workspace / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx1g\n')
        source = self.workspace / 'src/main/kotlin/MixedVersionProof.kt'
        source.parent.mkdir(parents=True)
        source.write_text('package mixedproof\nclass MixedVersionProof(val value: String)\n')
        wrapper = self.workspace / 'gradle/wrapper'
        wrapper.mkdir(parents=True)
        for name in ('gradle-wrapper.jar', 'gradle-wrapper.properties'):
            shutil.copyfile(self.args.repository / 'gradle/wrapper' / name, wrapper / name)
        shutil.copyfile(self.args.repository / 'gradlew', self.workspace / 'gradlew')
        (self.workspace / 'gradlew').chmod(0o700)
        (self.workspace / '.idea').mkdir()
        (self.workspace / '.idea/gradle.xml').write_text(
            '<project version="4"><component name="GradleSettings">'
            '<option name="linkedExternalProjectsSettings"><GradleProjectSettings>'
            '<option name="externalProjectPath" value="$PROJECT_DIR$" />'
            '<option name="distributionType" value="DEFAULT_WRAPPED" />'
            '<option name="gradleJvm" value="#JAVA_HOME" />'
            '</GradleProjectSettings></option></component></project>\n')
        (self.root / 'ide/config/options/trusted-paths.xml').write_text(
            '<application><component name="Trusted.Paths"><option name="TRUSTED_PROJECT_PATHS">'
            '<map><entry key=' + quoteattr(str(self.workspace)) + ' value="true" /></map>'
            '</option></component></application>\n')
        options = (self.args.idea_home / 'bin/idea.vmoptions').read_text().splitlines()
        options.extend(f'-Didea.{name}.path={self.root / "ide" / name}' for name in ('config', 'system', 'log'))
        options.extend((f'-Didea.plugins.path={self.plugins}', f'-Duser.home={self.home}',
                        f'-Djava.io.tmpdir={self.root / "tmp"}', '-Didea.initially.ask.config=never',
                        '-Djb.consents.confirmation.enabled=false', '-Dide.experimental.ui.onboarding=false'))
        vmoptions = self.root / 'ide/idea.vmoptions'
        vmoptions.write_text('\n'.join(options) + '\n')
        self.env['IDEA_VM_OPTIONS'] = str(vmoptions)
        self.env['TMPDIR'] = str(self.root / 'tmp')
        self.endpoint = self.home / '.kast/ide-hosted' / hashlib.sha256(str(self.workspace).encode()).hexdigest()[:32]

    def start_idea(self):
        with (self.root / 'logs/idea.stdout').open('a') as output, (self.root / 'logs/idea.stderr').open('a') as error:
            self.idea = subprocess.Popen([str(self.args.idea_home / 'MacOS/idea'), str(self.workspace)],
                                         cwd=self.workspace, env=self.env, stdout=output, stderr=error,
                                         start_new_session=True)
        self.idea_start = process_start(self.idea.pid)
        deadline = time.monotonic() + self.args.readiness_seconds
        while not (self.endpoint / 'endpoint.json').is_file():
            if self.idea.poll() is not None:
                raise Rejected(Failure.NATIVE_PROCESS_EXITED, self.idea.returncode)
            if time.monotonic() >= deadline:
                raise Rejected(Failure.NATIVE_ENDPOINT_UNAVAILABLE)
            time.sleep(0.5)

    def host(self) -> HostProof:
        descriptor = json.loads((self.endpoint / 'endpoint.json').read_text())
        if (self.idea is None or self.idea.poll() is not None or descriptor['hostPid'] != self.idea.pid
                or process_start(self.idea.pid) != self.idea_start or descriptor['root'] != str(self.workspace)):
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        socket_path = self.endpoint / 'host.sock'
        observed = socket_path.lstat()
        if not stat.S_ISSOCK(observed.st_mode) or observed.st_uid != os.getuid():
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        # Observation only. Production Tool RPC and host-admission independently own compatibility decisions.
        payload = encode(DescribeRequest(str(self.workspace))).encode()
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(5)
            client.connect(str(socket_path))
            client.sendall(struct.pack('>I', len(payload)) + payload)
            size = struct.unpack('>I', receive(client, 4))[0]
            if not 1 <= size <= 65536:
                raise Rejected(Failure.LIVE_DESCRIPTION_REJECTED)
            live = json.loads(receive(client, size))
        if (live.get('type') != 'KAST_IDE_HOST' or live.get('root') != str(self.workspace)
                or live.get('hostPid') != self.idea.pid or live.get('host') != descriptor['host']):
            raise Rejected(Failure.LIVE_DESCRIPTION_REJECTED)
        compatibility = live.get('compatibility', {})
        readiness = live.get('readiness', {})
        self.readiness_status = readiness.get('status')
        if self.readiness_status not in ('admission_ready', 'unavailable'):
            raise Rejected(Failure.LIVE_DESCRIPTION_REJECTED)
        version, contract = compatibility.get('hostedPluginVersion'), compatibility.get('hostedContract')
        if not isinstance(version, str) or not isinstance(contract, dict) or contract.get('type') != 'HOSTED_CONTRACT':
            raise Rejected(Failure.LIVE_DESCRIPTION_REJECTED)
        contract_digest = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        return HostProof(self.idea.pid, self.idea_start, descriptor['host'], version, str(self.workspace),
                         observed.st_dev, observed.st_ino, contract_digest)

    def query(self, control_version, expected_host) -> QueryProof:
        deadline = time.monotonic() + self.args.readiness_seconds
        while True:
            observed = self.host()
            if observed.host != expected_host:
                raise Rejected(Failure.OWNERSHIP_REJECTED)
            if self.readiness_status == 'admission_ready':
                break
            if time.monotonic() >= deadline:
                raise Rejected(Failure.NATIVE_ENDPOINT_UNAVAILABLE)
            time.sleep(0.5)
        call = QueryCall(QueryRun('RUN', QuerySource(), QueryOutput()))
        result = self.command([self.installation / 'bin/kast-tool-rpc-complete', 'call', 'query_symbols'],
                              input_text=encode(call), timeout=self.args.readiness_seconds,
                              reject=Failure.SEMANTIC_QUERY_REJECTED)
        reply = json.loads(result.stdout)
        document = reply.get('document', {})
        rows, live = document.get('items', []), document.get('live', {})
        if (reply.get('type') != 'complete' or document.get('status') != 'complete' or len(rows) != 1
                or document.get('coverage', {}).get('exhaustive') is not True
                or document.get('failures') or document.get('omissions')
                or rows[0].get('type') != 'exact-symbol'
                or rows[0].get('name') != 'MixedVersionProof'
                or rows[0].get('signature', {}).get('qualifiedIdentity') != 'mixedproof.MixedVersionProof'
                or live.get('host') != expected_host or live.get('root') != str(self.workspace)
                or not isinstance(live.get('epoch'), int)):
            raise Rejected(Failure.SEMANTIC_QUERY_REJECTED)
        self.status(control_version, expected_host)
        return QueryProof(control_version, live['host'], live['epoch'], 'mixedproof.MixedVersionProof', len(rows))

    def status(self, control_version, expected_host=None):
        result = self.command([self.home / '.config/kast', 'status', '--json'], reject=Failure.CONTROL_STATUS_REJECTED)
        document = json.loads(result.stdout)
        if any(document.get(field, {}).get('value') != control_version for field in ('installedVersion', 'loadedVersion')):
            raise Rejected(Failure.CONTROL_STATUS_REJECTED)
        if expected_host is not None:
            hosts = document.get('hostedServices', {}).get('value')
            if not isinstance(hosts, list) or not any(host.get('type') == 'COMPATIBLE' and host.get('host') == expected_host for host in hosts):
                raise Rejected(Failure.CONTROL_STATUS_REJECTED)

    def control(self) -> ControlProof:
        observed = subprocess.run(['/bin/ps', '-axo', 'pid,uid,command'], check=True, capture_output=True,
                                  text=True, timeout=5)
        candidates = []
        for line in observed.stdout.splitlines():
            fields = line.strip().split(None, 2)
            if (len(fields) == 3 and fields[0].isdigit() and fields[1].isdigit()
                    and str(self.installation) in fields[2] and 'io.github.amichne.kast.cli.KastDaemonMain' in fields[2]):
                if int(fields[1]) != os.getuid():
                    raise Rejected(Failure.OWNERSHIP_REJECTED)
                candidates.append(int(fields[0]))
        readiness = tuple(self.installation.glob('state/broker/*/service-readiness.json'))
        if len(candidates) != 1 or len(readiness) != 1:
            raise Rejected(Failure.CONTROL_STATUS_REJECTED)
        ready = json.loads(readiness[0].read_text())
        if ready.get('state') != 'ready' or ready.get('schemaVersion') != 3:
            raise Rejected(Failure.CONTROL_STATUS_REJECTED)
        return ControlProof(candidates[0], process_start(candidates[0]), ready['serviceInstanceId'])

    def stop_idea(self):
        if self.idea is None or self.idea.poll() is not None:
            return
        if process_start(self.idea.pid) != self.idea_start:
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        self.idea.terminate()
        try:
            self.idea.wait(timeout=60)
        except subprocess.TimeoutExpired:
            raise Rejected(Failure.CLEANUP_UNVERIFIED) from None
        self.idea = None

    def execute(self, artifacts) -> Passed:
        self.progress(Stage.INSTALL_C1_P1)
        self.installer(self.args.control_c1, host_version=self.args.host_p1)
        self.control_installed = True
        self.status(self.args.control_c1)
        control_c1 = self.control()
        prior = files(self.installation, ('bin', 'lib', 'share', 'config'))
        self.progress(Stage.UNAVAILABLE_PREFLIGHT)
        self.installer(self.args.control_c2, 'control', expected_nonzero=True)
        if files(self.installation, ('bin', 'lib', 'share', 'config')) != prior or self.control() != control_c1:
            raise Rejected(Failure.UNAVAILABLE_PREFLIGHT_DID_NOT_PRESERVE_CONTROL)
        self.status(self.args.control_c1)
        self.progress(Stage.START_P1)
        self.start_idea()
        before = self.host()
        if before.hosted_plugin_version != self.args.host_p1:
            raise Rejected(Failure.LIVE_DESCRIPTION_REJECTED)
        plugin = files(self.plugins / 'kast-ide-hosted')
        self.progress(Stage.QUERY_C1_P1)
        query_c1 = self.query(self.args.control_c1, before.host)
        self.progress(Stage.UPGRADE_C2)
        self.installer(self.args.control_c2, 'control')
        after = self.host()
        if after != before or files(self.plugins / 'kast-ide-hosted') != plugin:
            raise Rejected(Failure.CONTROL_UPGRADE_CHANGED_HOST)
        self.progress(Stage.QUERY_C2_P1)
        query_c2 = self.query(self.args.control_c2, before.host)
        control_c2 = self.control()
        if control_c2.pid == control_c1.pid or control_c2.service_generation == control_c1.service_generation:
            raise Rejected(Failure.CONTROL_STATUS_REJECTED)
        control = files(self.installation, ('bin', 'lib', 'share', 'config'))
        self.progress(Stage.INSTALL_P2)
        self.installer(self.args.host_p2, 'host')
        control_after_host = self.control()
        if (files(self.installation, ('bin', 'lib', 'share', 'config')) != control
                or self.host() != before or control_after_host != control_c2):
            raise Rejected(Failure.HOST_INSTALL_CHANGED_CONTROL)
        self.progress(Stage.RESTART_P2)
        self.stop_idea()
        self.start_idea()
        final_host = self.host()
        if final_host.hosted_plugin_version != self.args.host_p2 or final_host.pid == before.pid:
            raise Rejected(Failure.LIVE_DESCRIPTION_REJECTED)
        if final_host.contract_sha256 != before.contract_sha256:
            raise Rejected(Failure.LIVE_DESCRIPTION_REJECTED)
        self.progress(Stage.QUERY_C2_P2)
        final_query = self.query(self.args.control_c2, final_host.host)
        if self.control() != control_c2:
            raise Rejected(Failure.HOST_INSTALL_CHANGED_CONTROL)
        return Passed('PASSED', os.getuid(), 'PRIVATE_HOME_AND_IDEA_PROFILE_REAL_CURRENT_UID', str(self.root),
                      self.metadata['buildNumber'], artifacts, before, after, final_host, plugin,
                      query_c1, query_c2, final_query, control_c1, control_c2, control_after_host, True, True, ())

    def cleanup(self) -> tuple[Failure, ...]:
        failures = []
        if self.control_installed or (self.installation / 'installation.json').is_file():
            try:
                self.command([self.installation / 'share/kast/libexec/kast-service', 'disable'],
                             reject=Failure.CLEANUP_UNVERIFIED, timeout=90)
            except (Rejected, OSError):
                failures.append(Failure.CLEANUP_UNVERIFIED)
        try:
            self.stop_idea()
        except (Rejected, OSError, subprocess.SubprocessError):
            failures.append(Failure.CLEANUP_UNVERIFIED)
        if (self.home / '.gradle/daemon').is_dir():
            try:
                self.command([self.workspace / 'gradlew', '--stop'], reject=Failure.CLEANUP_UNVERIFIED, timeout=60)
            except (Rejected, OSError):
                failures.append(Failure.CLEANUP_UNVERIFIED)
        return tuple(failures)


def receive(client: socket.socket, count: int) -> bytes:
    output = bytearray()
    while len(output) < count:
        block = client.recv(count - len(output))
        if not block:
            raise Rejected(Failure.LIVE_DESCRIPTION_REJECTED)
        output.extend(block)
    return bytes(output)


def inputs(args) -> tuple[Artifact, ...]:
    if (platform.system() != 'Darwin' or platform.machine() != 'arm64' or os.getuid() == 0
            or args.owned_root.exists() or args.owned_root.is_symlink() or not args.owned_root.is_absolute()
            or args.owned_root != args.owned_root.resolve() or not 1 <= args.readiness_seconds <= 1200):
        raise Rejected(Failure.INPUT_REJECTED)
    info = json.loads((args.idea_home / 'Resources/product-info.json').read_text())
    if not info['buildNumber'].startswith('262.'):
        raise Rejected(Failure.INPUT_REJECTED)
    artifacts = []
    def admit_asset(component: str, version: str, name: str):
        archive, checksum = args.assets / name, args.assets / (name + '.sha256')
        if archive.is_symlink() or checksum.is_symlink() or not archive.is_file() or not checksum.is_file():
            raise Rejected(Failure.INPUT_REJECTED)
        expected = checksum.read_text().split()
        observed = digest(archive)
        if len(expected) != 2 or expected[0] != observed or expected[1].lstrip('*') != name:
            raise Rejected(Failure.INPUT_REJECTED)
        artifacts.append(Artifact(component, version, str(archive), observed))

    for component, version in (('control', args.control_c1), ('control', args.control_c2),
                               ('host', args.host_p1), ('host', args.host_p2)):
        if re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version) is None:
            raise Rejected(Failure.INPUT_REJECTED)
        name = (f'kast-control-v{version}-macos-aarch64.tar.gz' if component == 'control'
                else f'kast-ide-hosted-v{version}-idea-262.zip')
        admit_asset(component, version, name)
        if component == 'host':
            admit_asset('host_release_record', version, f'kast-host-release-v{version}.json')
    admit_asset('host_installer', args.host_p2, 'host-installation.py')
    if args.control_c1 == args.control_c2 or args.host_p1 == args.host_p2:
        raise Rejected(Failure.INPUT_REJECTED)
    return tuple(artifacts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument('--idea-home', type=Path, required=True, help='Canonical IDEA Contents directory')
    parser.add_argument('--assets', type=Path, required=True, help='Four archives, host release records/helper, and checksum sidecars')
    parser.add_argument('--owned-root', type=Path, required=True, help='New absolute directory; logs and report are retained')
    parser.add_argument('--control-c1', default='0.50.0')
    parser.add_argument('--control-c2', default='0.50.1')
    parser.add_argument('--host-p1', default='0.49.0')
    parser.add_argument('--host-p2', default='0.49.1')
    parser.add_argument('--readiness-seconds', type=int, default=600)
    args = parser.parse_args()
    try:
        artifacts = inputs(args)
    except (Rejected, OSError, ValueError, KeyError):
        print(encode(Failed('REJECTED', os.getuid(), str(args.owned_root), Stage.INPUTS,
                            Failure.INPUT_REJECTED, None, ())), file=sys.stderr)
        return 1
    args.owned_root.mkdir(mode=0o700)
    proof = NativeProof(args)
    try:
        proof.setup()
        report = proof.execute(artifacts)
    except Rejected as rejected:
        report = Failed('REJECTED', os.getuid(), str(args.owned_root), proof.stage,
                        rejected.failure, rejected.exit_code, ())
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        report = Failed('REJECTED', os.getuid(), str(args.owned_root), proof.stage,
                        Failure.INPUT_REJECTED, None, ())
    cleanup = proof.cleanup()
    if cleanup:
        report = Failed('RECOVERY_REQUIRED', os.getuid(), str(args.owned_root), proof.stage,
                        report.failure if isinstance(report, Failed) else Failure.CLEANUP_UNVERIFIED,
                        report.exit_code if isinstance(report, Failed) else None, cleanup)
    (args.owned_root / 'report.json').write_text(encode(report))
    (args.owned_root / 'report.json').chmod(0o600)
    print(encode(report), flush=True)
    return 0 if isinstance(report, Passed) else 1


if __name__ == '__main__':
    sys.exit(main())
