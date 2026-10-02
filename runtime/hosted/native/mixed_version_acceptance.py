#!/usr/bin/env python3
"""Opt-in native proof of independent control upgrades and host installation.

Requires four component release inventories and their checksums. Runs as the current
UID using the sole normal installation and normal IDEA profile. Backups are inert
archives, never another installation. Requires IDEA to be closed at entry.
No fake model, extracted plugin shortcut, hot reload, or semantic substitute is
used. The normal installer and installed Tool RPC own every product decision.
This is deliberately outside routine packaging and unit-test inventories.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
import fcntl
from dataclasses import asdict, dataclass, replace
from enum import Enum
import hashlib
import itertools
import json
import os
from pathlib import Path
import platform
import plistlib
import pwd
import re
import shutil
import socket
import stat
import struct
import subprocess
import sys
import tarfile
import time
import uuid


class Stage(str, Enum):
    INPUTS = 'INPUTS'
    SNAPSHOT = 'SNAPSHOT'
    RESTORE = 'RESTORE'
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
    BASELINE_UNPROVEN = 'BASELINE_UNPROVEN'
    PROTECTED_STATE_CHANGED = 'PROTECTED_STATE_CHANGED'
    RESTORATION_UNVERIFIED = 'RESTORATION_UNVERIFIED'
    INVENTORY_LIMIT_EXCEEDED = 'INVENTORY_LIMIT_EXCEEDED'


class InventoryResource(str, Enum):
    TRAVERSED_ENTRIES = 'TRAVERSED_ENTRIES'
    PAYLOAD_BYTES = 'PAYLOAD_BYTES'


# Match ControlDistributionLimits rather than inventing a smaller native snapshot boundary.
SNAPSHOT_MAXIMUM_ENTRIES = 16_384
SNAPSHOT_MAXIMUM_BYTES = 1_073_741_824


@dataclass(frozen=True)
class InventoryLimit:
    resource: InventoryResource
    maximum: int
    observed_at_least: int


class HostedFailure(str, Enum):
    CONFIGURATION_REJECTED = 'CONFIGURATION_REJECTED'
    RETIRED = 'RETIRED'
    WRONG_ENDPOINT = 'WRONG_ENDPOINT'
    WRONG_PROJECT = 'WRONG_PROJECT'
    BUSY = 'BUSY'
    STALE_REQUEST = 'STALE_REQUEST'
    INVALID_SELECTION = 'INVALID_SELECTION'
    DECLARATION_NOT_FOUND = 'DECLARATION_NOT_FOUND'
    AMBIGUOUS_DECLARATION = 'AMBIGUOUS_DECLARATION'
    DECLARATION_IDENTITY_MISMATCH = 'DECLARATION_IDENTITY_MISMATCH'
    PROJECT_UNAVAILABLE = 'PROJECT_UNAVAILABLE'
    INDEXING = 'INDEXING'
    WRONG_THREAD = 'WRONG_THREAD'
    DIRTY_DOCUMENTS = 'DIRTY_DOCUMENTS'
    UNCOMMITTED_DOCUMENTS = 'UNCOMMITTED_DOCUMENTS'
    CONTENT_MOVED = 'CONTENT_MOVED'
    MODEL_MOVED = 'MODEL_MOVED'
    UNSUPPORTED_MODEL = 'UNSUPPORTED_MODEL'
    UNSUPPORTED_DECLARATION = 'UNSUPPORTED_DECLARATION'
    UNRESOLVED_SUPERTYPE = 'UNRESOLVED_SUPERTYPE'
    FILE_UNAVAILABLE = 'FILE_UNAVAILABLE'
    FILE_TOO_LARGE = 'FILE_TOO_LARGE'
    RESULT_LIMIT_EXCEEDED = 'RESULT_LIMIT_EXCEEDED'
    OUTSIDE_SCOPE = 'OUTSIDE_SCOPE'
    AMBIGUOUS_SCOPE = 'AMBIGUOUS_SCOPE'
    READ_PREEMPTED = 'READ_PREEMPTED'
    CANCELLED = 'CANCELLED'
    BUDGET_EXCEEDED = 'BUDGET_EXCEEDED'
    PLATFORM_FAILURE = 'PLATFORM_FAILURE'
    PUBLICATION_REJECTED = 'PUBLICATION_REJECTED'
    PROJECT_ADMISSION_REJECTED = 'PROJECT_ADMISSION_REJECTED'
    MODEL_CAPTURE_REJECTED = 'MODEL_CAPTURE_REJECTED'
    READ_EPOCH_REJECTED = 'READ_EPOCH_REJECTED'
    FRESHNESS_REJECTED = 'FRESHNESS_REJECTED'
    LIVE_AUTHORITY_REJECTED = 'LIVE_AUTHORITY_REJECTED'
    NAMED_SOURCE_SCOPE_REJECTED = 'NAMED_SOURCE_SCOPE_REJECTED'


class HostedStage(str, Enum):
    REQUEST_ADMISSION = 'REQUEST_ADMISSION'
    PROJECT_ADMISSION = 'PROJECT_ADMISSION'
    EPOCH_OBSERVATION = 'EPOCH_OBSERVATION'
    MODEL_CAPTURE = 'MODEL_CAPTURE'
    SEMANTIC_READ = 'SEMANTIC_READ'
    CONTENT_REVALIDATION = 'CONTENT_REVALIDATION'
    RESULT_DETACHED = 'RESULT_DETACHED'


class HostedRecovery(str, Enum):
    INCREASE_HOST_DEADLINE = 'increase_host_deadline'
    AFTER_STATE_CHANGE = 'after_state_change'
    AFTER_INDEXING = 'after_indexing'
    REDUCE_READ_WORK = 'reduce_read_work'
    CANCELLED = 'cancelled'
    SAVE_SOURCE = 'save_source'
    WAIT_FOR_CAPACITY = 'wait_for_capacity'
    RESTART_READ = 'restart_read'
    REVIEW_FAILURE = 'review_failure'
    RETAINED_STATE_UNAVAILABLE = 'retained_state_unavailable'
    REDUCE_RETAINED_WORK = 'reduce_retained_work'


@dataclass(frozen=True)
class SemanticRejection:
    failure: HostedFailure
    # HostedQueryRejectionDocument.detail is the schema-defined opaque diagnostic union.
    detail: str | dict | list
    stage: HostedStage
    recovery: HostedRecovery
    instruction: str | None
    reply_file: str


RESTART_READ_INSTRUCTION = ('The model or source changed during the read. Start a fresh read; '
                            'a continuation from the old epoch cannot establish current evidence.')


def restart_fresh_read(rejection: SemanticRejection) -> bool:
    return (rejection.failure == HostedFailure.FRESHNESS_REJECTED and rejection.detail == 'MOVED'
            and rejection.stage == HostedStage.CONTENT_REVALIDATION
            and rejection.recovery == HostedRecovery.RESTART_READ
            and rejection.instruction == RESTART_READ_INSTRUCTION)


def semantic_rejection(reply, reply_file: str) -> SemanticRejection:
    document = reply['document']
    if (document.get('schemaVersion') != 1 or document.get('outcome') != 'rejected'
            or not isinstance(document.get('detail'), (str, dict, list))):
        raise Rejected(Failure.SEMANTIC_QUERY_REJECTED)
    try:
        return SemanticRejection(HostedFailure(document['failure']), document['detail'],
                                 HostedStage(document['stage']), HostedRecovery(document['recovery']['kind']),
                                 document['recovery'].get('instruction'), reply_file)
    except (ValueError, KeyError, TypeError):
        raise Rejected(Failure.SEMANTIC_QUERY_REJECTED) from None


class Rejected(Exception):
    def __init__(self, failure: Failure, exit_code: int | None = None, limit: InventoryLimit | None = None, semantic: SemanticRejection | None = None):
        self.failure, self.exit_code, self.limit, self.semantic = failure, exit_code, limit, semantic


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
    attempts: int = 1
    fresh_rejections: tuple[SemanticRejection, ...] = ()


@dataclass(frozen=True)
class ControlProof:
    pid: int
    process_start: str
    service_generation: str


class RunnerSourceState(str, Enum):
    COMMITTED = 'COMMITTED'
    MODIFIED = 'MODIFIED'


class ReleaseComponent(str, Enum):
    CONTROL = 'control'
    HOST = 'host'


@dataclass(frozen=True)
class ReleaseSource:
    component: ReleaseComponent
    version: str
    source_revision: str


@dataclass(frozen=True)
class RunnerProvenance:
    runner_sha256: str
    checkout_revision: str
    runner_source_state: RunnerSourceState
    artifact_sources: tuple[ReleaseSource, ...]


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
    restored_original_version: str | None = None
    restored_control: ControlProof | None = None
    runner: RunnerProvenance | None = None


@dataclass(frozen=True)
class Failed:
    type: str
    uid: int
    owned_root: str
    stage: Stage
    failure: Failure
    exit_code: int | None
    cleanup_failures: tuple[Failure, ...]
    limit: InventoryLimit | None = None
    semantic: SemanticRejection | None = None


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
    paths = root.rglob('*') if selected is None else itertools.chain(
        *( (root / name).rglob('*') for name in selected ), (root / 'installation.json',)
    )
    facts, entries, total_bytes = [], 0, 0
    for path in paths:
        entries += 1
        if entries > SNAPSHOT_MAXIMUM_ENTRIES:
            raise Rejected(Failure.INVENTORY_LIMIT_EXCEEDED, limit=InventoryLimit(
                InventoryResource.TRAVERSED_ENTRIES, SNAPSHOT_MAXIMUM_ENTRIES, entries))
        observed = path.lstat()
        if observed.st_uid != os.getuid() or stat.S_ISLNK(observed.st_mode):
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        if stat.S_ISDIR(observed.st_mode):
            continue
        if not stat.S_ISREG(observed.st_mode):
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        total_bytes += observed.st_size
        if total_bytes > SNAPSHOT_MAXIMUM_BYTES:
            raise Rejected(Failure.INVENTORY_LIMIT_EXCEEDED, limit=InventoryLimit(
                InventoryResource.PAYLOAD_BYTES, SNAPSHOT_MAXIMUM_BYTES, total_bytes))
        facts.append(FileFact(str(path.relative_to(root)), observed.st_dev, observed.st_ino,
                              observed.st_uid, stat.S_IMODE(observed.st_mode), observed.st_size, digest(path)))
    if not facts:
        raise Rejected(Failure.OWNERSHIP_REJECTED)

    return tuple(sorted(facts, key=lambda fact: fact.relative_path))


@dataclass(frozen=True)
class Snapshot:
    type: str
    installation: str
    version: str
    control: ControlProof
    control_files: tuple[FileFact, ...]
    plugin_files: tuple[FileFact, ...]
    publications: tuple[FileFact, ...]
    protected_registrations: tuple[FileFact, ...]
    archive_digests: tuple[tuple[str, str], ...]
    login_anchor: tuple[FileFact, ...]


class OriginalProjection(str, Enum):
    VERIFIED = 'VERIFIED'
    UNAVAILABLE = 'UNAVAILABLE'


@dataclass(frozen=True)
class OriginalRestoration:
    type: str
    control_version: str
    control: ControlProof
    runtime_installation_identity: str
    runtime_epoch: str
    public_loaded_version_projection: OriginalProjection


def single_file(path: Path, base: Path | None = None) -> FileFact:
    observed = path.lstat()
    if (not stat.S_ISREG(observed.st_mode) or observed.st_uid != os.getuid()
            or path.resolve() != path):
        raise Rejected(Failure.OWNERSHIP_REJECTED)
    return FileFact(str(path.relative_to(base)) if base else str(path), observed.st_dev, observed.st_ino,
                    observed.st_uid, stat.S_IMODE(observed.st_mode), observed.st_size, digest(path))


def content_facts(facts):
    return tuple((fact.relative_path, fact.owner, fact.mode, fact.size, fact.sha256) for fact in facts)


def runtime_installation_identity(root: Path) -> str:
    # BrokerInstallationState.identity: physical root and immutable control files only.
    inventory = files(root, ('bin', 'lib', 'share'))
    identity = hashlib.sha256(str(root).encode())
    for fact in inventory:
        if fact.relative_path == 'installation.json':
            continue
        path = root / fact.relative_path
        if single_file(path, root) != fact:
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        identity.update(b'\0')
        identity.update(fact.relative_path.encode())
        identity.update(b'\0')
        with path.open('rb') as source:
            while block := source.read(65536):
                identity.update(block)
    return 'sha256:' + identity.hexdigest()


def payload_facts(facts):
    return tuple(fact for fact in facts if not fact.relative_path.startswith('state/'))


def static_files(root: Path) -> tuple[FileFact, ...]:
    result = list(files(root, ('bin', 'lib', 'share', 'config')))
    for path in sorted(root.glob('.kast-*-sha256')) + sorted(root.glob('state/broker/*/service.plist')):
        result.append(single_file(path, root))
    return tuple(sorted(result, key=lambda fact: fact.relative_path))


class NativeProof:
    def __init__(self, args):
        self.args = args
        runner_path = Path(__file__).resolve()
        source = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=args.repository, text=True,
                                capture_output=True, check=True, timeout=5).stdout.strip()
        modified = subprocess.run(['git', 'status', '--porcelain', '--', str(runner_path)], cwd=args.repository,
                                  text=True, capture_output=True, check=True, timeout=5).stdout.strip()
        self.runner = RunnerProvenance(digest(runner_path), source,
            RunnerSourceState.MODIFIED if modified else RunnerSourceState.COMMITTED, args.artifact_sources)
        self.root = args.owned_root
        self.home = Path(pwd.getpwuid(os.getuid()).pw_dir)
        self.workspace = self.root / 'workspace'
        self.install_root = self.home / '.local/share/kast'
        self.installation = self.install_root / 'installation'
        self.stage = Stage.INPUTS
        self.idea: subprocess.Popen | None = None
        self.idea_start: str | None = None
        self.commands = 0
        self.control_installed = False
        self.snapshot_ready = False
        self.original_files = ()
        self.original_plugin = ()
        self.env = dict(os.environ)
        for key in tuple(self.env):
            if key.startswith('KAST_') or key in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
                                                  'IDEA_VM_OPTIONS', 'CODEX_HOME', 'GRADLE_USER_HOME'):
                del self.env[key]
        self.env.update(HOME=str(self.home), KAST_INSTALL_PROFILE='persistent',
                        KAST_INSTALL_ASSETS_DIRECTORY=str(args.assets),
                        JAVA_HOME=str(args.idea_home / 'jbr/Contents/Home'), NO_COLOR='1', KAST_ASCII='1')
        # Canonical control selection is intrinsic; retain the actual normal HOME/profile.
        for key in ('XDG_DATA_HOME', 'XDG_CONFIG_HOME'):
            self.env.pop(key, None)

    def progress(self, stage: Stage, outcome: str = 'STARTED'):
        self.stage = stage
        event = Progress('NATIVE_ACCEPTANCE_STAGE', stage, outcome)
        encoded = encode(event)
        with (self.root / 'progress.jsonl').open('a') as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(json.dumps(asdict(event)) + '\n')
        print(encoded, flush=True)

    def command(self, arguments, *, input_text=None, timeout=300, reject=Failure.INSTALL_REJECTED,
                expected_nonzero=False, cwd=None) -> subprocess.CompletedProcess:
        self.commands += 1
        prefix = self.root / 'logs' / f'{self.commands:02d}-{self.stage.value.lower()}'
        try:
            result = subprocess.run([str(argument) for argument in arguments], cwd=self.workspace if cwd is None else cwd,
                                    env=self.env, input=input_text, capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise Rejected(Failure.COMMAND_TIMED_OUT) from None
        self.last_stdout_log = str(prefix.with_suffix('.stdout'))
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
        result = self.command(command, expected_nonzero=expected_nonzero)
        self.verify_protected()
        return result

    def setup(self):
        for directory in (self.workspace, self.root / 'logs'):
            directory.mkdir(parents=True, mode=0o700, exist_ok=True)
        self.metadata = json.loads((self.args.idea_home / 'Resources/product-info.json').read_text())
        self.plugins = self.home / 'Library/Application Support/JetBrains' / self.metadata['dataDirectoryName'] / 'plugins'
        for directory in (self.home, self.install_root, self.installation, self.plugins, self.plugins / 'kast-ide-hosted'):
            observed = directory.lstat()
            if not stat.S_ISDIR(observed.st_mode) or observed.st_uid != os.getuid() or directory.resolve() != directory:
                raise Rejected(Failure.OWNERSHIP_REJECTED)
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
        self.endpoint = self.home / '.kast/ide-hosted' / hashlib.sha256(str(self.workspace).encode()).hexdigest()[:32]

    def start_idea(self):
        self.idea_closed()
        with (self.root / 'logs/idea.stdout').open('a') as output, (self.root / 'logs/idea.stderr').open('a') as error:
            os.fchmod(output.fileno(), 0o600)
            os.fchmod(error.fileno(), 0o600)
            self.idea = subprocess.Popen([str(self.args.idea_home / 'MacOS/idea'), str(self.workspace)],
                                         cwd=self.workspace, env=self.env, stdout=output, stderr=error,
                                         start_new_session=True)
        self.idea_start = process_start(self.idea.pid)
        deadline = time.monotonic() + self.args.readiness_seconds
        while True:
            endpoint = self.endpoint / 'endpoint.json'
            if endpoint.is_file() and json.loads(endpoint.read_text()).get('hostPid') == self.idea.pid:
                break
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
        rejections = []
        for attempt in range(1, 4):
            if self.host().host != expected_host:
                raise Rejected(Failure.OWNERSHIP_REJECTED)
            # Every attempt is a new RUN with fresh authority. No handle/continuation survives rejection.
            call = QueryCall(QueryRun('RUN', QuerySource(), QueryOutput()))
            result = self.command([self.installation / 'bin/kast-tool-rpc-complete', 'call', 'query_symbols'],
                                  input_text=encode(call), timeout=self.args.readiness_seconds,
                                  reject=Failure.SEMANTIC_QUERY_REJECTED)
            reply = json.loads(result.stdout)
            if reply.get('type') != 'rejected_document':
                break
            rejected = semantic_rejection(reply, self.last_stdout_log)
            rejections.append(rejected)
            evidence = self.root / 'logs' / f'{self.stage.value.lower()}-rejection-{attempt}.json'
            evidence.write_text(encode(rejected));evidence.chmod(0o600)
            if not restart_fresh_read(rejected) or attempt == 3:
                raise Rejected(Failure.SEMANTIC_QUERY_REJECTED, semantic=rejected)
        document = reply.get('document', {})
        rows, live = document.get('items', []), document.get('live', {})
        if (reply.get('type') != 'complete' or document.get('status') != 'complete' or len(rows) != 1
                or document.get('coverage', {}).get('exhaustive') is not True
                or document.get('failures') != [] or document.get('omissions') != []
                or rows[0].get('type') != 'exact-symbol'
                or rows[0].get('name') != 'MixedVersionProof'
                or rows[0].get('signature', {}).get('type') != 'class-like'
                or rows[0].get('signature', {}).get('qualifiedIdentity') != 'mixedproof.MixedVersionProof'
                or live.get('host') != expected_host or live.get('root') != str(self.workspace)
                or type(live.get('epoch')) is not int):
            raise Rejected(Failure.SEMANTIC_QUERY_REJECTED)
        self.status(control_version, expected_host)
        return QueryProof(control_version, live['host'], live['epoch'], 'mixedproof.MixedVersionProof', len(rows), attempt, tuple(rejections))

    def status(self, control_version, expected_host=None):
        result = self.command([self.public_command, 'status', '--json'], reject=Failure.CONTROL_STATUS_REJECTED)
        document = json.loads(result.stdout)
        if any(document.get(field, {}).get('value') != control_version for field in ('installedVersion', 'loadedVersion')):
            raise Rejected(Failure.CONTROL_STATUS_REJECTED)
        if expected_host is not None:
            hosts = document.get('hostedServices', {}).get('value')
            if not isinstance(hosts, list) or not any(host.get('type') == 'COMPATIBLE' and host.get('host') == expected_host for host in hosts):
                raise Rejected(Failure.CONTROL_STATUS_REJECTED)

    def control(self) -> ControlProof:
        # Observe only the service label qualified by the installed launch receipt.
        receipts = tuple(self.installation.glob('state/broker/*/service.plist'))
        if len(receipts) != 1:
            raise Rejected(Failure.CONTROL_STATUS_REJECTED)
        launch = plistlib.loads(receipts[0].read_bytes())
        label = launch.get('Label')
        if not isinstance(label, str) or re.fullmatch(r'io\.github\.amichne\.kast\.broker\.[0-9a-f]{32}', label) is None:
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        observed = subprocess.run(['/bin/launchctl', 'print', f'gui/{os.getuid()}/{label}'],
                                  check=True, capture_output=True, text=True, timeout=5)
        pids = re.findall(r'^\s*pid = ([0-9]+)\s*$', observed.stdout, re.MULTILINE)
        if len(pids) != 1:
            raise Rejected(Failure.CONTROL_STATUS_REJECTED)
        pid = int(pids[0])
        command = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'command='],
                                 check=True, capture_output=True, text=True, timeout=5).stdout
        if str(self.installation) not in command or 'io.github.amichne.kast.cli.KastDaemonMain' not in command:
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        ready = json.loads(receipts[0].with_name('service-readiness.json').read_text())
        if ready.get('state') != 'ready' or ready.get('schemaVersion') != 3:
            raise Rejected(Failure.CONTROL_STATUS_REJECTED)
        return ControlProof(pid, process_start(pid), ready['serviceInstanceId'])

    def idea_closed(self):
        identifier = plistlib.loads((self.args.idea_home / 'Info.plist').read_bytes())['CFBundleIdentifier']
        if re.fullmatch(r'[A-Za-z0-9.]+', identifier) is None:
            raise Rejected(Failure.INPUT_REJECTED)
        result = subprocess.run(['/usr/bin/osascript', '-e', f'application id "{identifier}" is running'],
                                capture_output=True, text=True, timeout=10, check=True)
        if result.stdout.strip() != 'false':
            raise Rejected(Failure.OWNERSHIP_REJECTED)

    def lifecycle(self, operation):
        return self.command([sys.executable, self.installation / 'share/kast/installation-lifecycle.py',
                             '--installation', self.installation, operation, '--json'],
                            reject=Failure.RESTORATION_UNVERIFIED, timeout=120)

    def snapshot(self):
        self.progress(Stage.SNAPSHOT)
        (self.root / 'runner-provenance.json').write_text(encode(self.runner))
        (self.root / 'runner-provenance.json').chmod(0o600)
        self.idea_closed()
        self.lifecycle('inspect')  # Production admission includes payload/configuration and runtime anchors.
        self.original_control = self.control()
        manifest = json.loads((self.installation / 'installation.json').read_text())
        self.original_version = manifest['semanticVersion']
        if self.original_version in (self.args.control_c1, self.args.control_c2):
            raise Rejected(Failure.BASELINE_UNPROVEN)
        self.original_anchors = manifest['externalAnchors']
        self.original_login = tuple(single_file(Path(anchor['path'])) for anchor in self.original_anchors
                                    if anchor['kind'] == 'login')
        if len(self.original_login) != 1:
            raise Rejected(Failure.BASELINE_UNPROVEN)
        receipt_path = self.install_root / 'management.json'
        receipt = json.loads(receipt_path.read_text())
        if receipt['installationRoot'] != str(self.install_root):
            raise Rejected(Failure.BASELINE_UNPROVEN)
        self.public_command = Path(receipt['executable'])
        self.registrations = receipt['registrations']
        self.external = tuple(Path(item['destination']) for item in self.registrations)
        if digest(self.public_command) != receipt['executableSha256'] or any(
                digest(path) != item['payloadSha256'] for path, item in zip(self.external, self.registrations)):
            raise Rejected(Failure.BASELINE_UNPROVEN)
        self.original_registry = json.loads((self.installation / 'config/workspaces.json').read_text())
        if not self.original_registry['roots']:
            raise Rejected(Failure.BASELINE_UNPROVEN)
        self.original_workspace = Path(self.original_registry['roots'][0])
        if not self.original_workspace.is_dir() or self.original_workspace.resolve() != self.original_workspace:
            raise Rejected(Failure.BASELINE_UNPROVEN)
        self.original_files = static_files(self.installation)
        self.original_plugin = files(self.plugins / 'kast-ide-hosted')
        self.original_plugin_mode = stat.S_IMODE((self.plugins / 'kast-ide-hosted').stat().st_mode)
        self.registration_facts = tuple(single_file(path) for path in self.external)
        self.external_original = tuple(single_file(path) for path in (receipt_path, self.public_command))
        self.archive('control-original.tar', self.installation, self.original_files)
        self.archive('plugin-original.tar', self.plugins / 'kast-ide-hosted', self.original_plugin)
        self.archive('publication-original.tar', self.home, tuple(single_file(path, self.home) for path in
                     (receipt_path, self.public_command)))
        launches = tuple(self.installation.glob('state/broker/*/service.plist'))
        arguments = plistlib.loads(launches[0].read_bytes()).get('ProgramArguments', [])
        if arguments[:2] != ['/usr/bin/env', '-i']:
            raise Rejected(Failure.BASELINE_UNPROVEN)
        self.original_environment = dict(self.env)
        for assignment in arguments[2:]:
            if '=' not in assignment or assignment.startswith('/'):
                break
            key, value = assignment.split('=', 1)
            if not re.fullmatch(r'[A-Z][A-Z0-9_]{0,127}', key):
                raise Rejected(Failure.BASELINE_UNPROVEN)
            if key not in ('BROKER_SERVICE_IDENTITY', 'BROKER_READINESS_FILE'):
                self.original_environment[key] = value
        if self.original_environment.get('HOME') != str(self.home):
            raise Rejected(Failure.BASELINE_UNPROVEN)
        self.original_environment['KAST_CONFIGURATION_FILE'] = str(self.installation / 'config/environment')
        self.archive_digests = tuple((name, digest(self.root / name)) for name in
                                    ('control-original.tar', 'plugin-original.tar', 'publication-original.tar'))
        (self.root / 'snapshot.json').write_text(encode(Snapshot('ORIGINAL_NORMAL_INSTALLATION', str(self.installation),
            self.original_version, self.original_control, self.original_files, self.original_plugin,
            self.external_original, self.registration_facts, self.archive_digests, self.original_login)))
        (self.root / 'snapshot.json').chmod(0o600)
        self.snapshot_ready = True
        self.verify_protected()

    def archive(self, name, base, facts):
        path = self.root / name
        with tarfile.open(path, 'w') as archive:
            directories = {ancestor for fact in facts for ancestor in (base / fact.relative_path).parents
                           if ancestor != base and base in ancestor.parents}
            for directory in sorted(directories):
                if directory.is_symlink() or directory.stat().st_uid != os.getuid():
                    raise Rejected(Failure.OWNERSHIP_REJECTED)
                archive.add(directory, arcname=str(directory.relative_to(base)), recursive=False)
            for fact in facts:
                archive.add(base / fact.relative_path, arcname=fact.relative_path, recursive=False)
        path.chmod(0o600)
        if content_facts(facts) != content_facts(tuple(single_file(base / fact.relative_path, base) for fact in facts)):
            raise Rejected(Failure.OWNERSHIP_REJECTED)

    def verify_protected(self):
        if tuple(single_file(path) for path in self.external) != self.registration_facts:
            raise Rejected(Failure.PROTECTED_STATE_CHANGED)
        receipt = json.loads((self.install_root / 'management.json').read_text())
        if receipt['registrations'] != self.registrations or receipt['executable'] != str(self.public_command):
            raise Rejected(Failure.PROTECTED_STATE_CHANGED)
        registry = json.loads((self.installation / 'config/workspaces.json').read_text())
        original = set(self.original_registry['roots'])
        observed = set(registry['roots'])
        if not original <= observed or not observed <= original | {str(self.workspace)}:
            raise Rejected(Failure.PROTECTED_STATE_CHANGED)

    def restore(self):
        self.progress(Stage.RESTORE)
        self.stop_idea()
        self.idea_closed()
        self.verify_protected()
        current_control = payload_facts(static_files(self.installation))
        current_plugin = files(self.plugins / 'kast-ide-hosted')
        publications = tuple(single_file(path) for path in (self.install_root / 'management.json', self.public_command))
        for name, expected in self.archive_digests:
            if digest(self.root / name) != expected:
                raise Rejected(Failure.RESTORATION_UNVERIFIED)
        # Ordinary installed lifecycle owns retirement and runtime/alias cleanup. Never signal a control PID.
        case_payload = runtime_installation_identity(self.installation)
        self.lifecycle('reset')
        reset_state = self.installation / 'state'
        state_identity = reset_state.lstat()
        if (not stat.S_ISDIR(state_identity.st_mode) or state_identity.st_uid != os.getuid()
                or reset_state.resolve() != reset_state or stat.S_IMODE(state_identity.st_mode) != 0o700):
            raise Rejected(Failure.RESTORATION_UNVERIFIED)
        entries = tuple(sorted(path.name for path in reset_state.iterdir()))
        if entries not in ((), ('epoch.json',)):
            raise Rejected(Failure.RESTORATION_UNVERIFIED)
        reset_epoch = single_file(reset_state / 'epoch.json') if entries else None
        if reset_epoch is not None:
            epoch = json.loads((reset_state / 'epoch.json').read_text())
            if (set(epoch) != {'schemaVersion', 'installation', 'epoch'} or epoch['schemaVersion'] != 1
                    or epoch['installation'] != case_payload or str(uuid.UUID(epoch['epoch'])) != epoch['epoch']):
                raise Rejected(Failure.RESTORATION_UNVERIFIED)
        self.idea_closed()
        with ExitStack() as locks:
            for path, host_lock in ((self.install_root / 'activation.lock', False),
                                    (self.install_root / 'management.lock', False),
                                    (self.plugins.parent / '.kast-plugin-recovery/host-install.lock', True)):
                fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
                locks.callback(os.close, fd)
                if not stat.S_ISREG(os.fstat(fd).st_mode) or os.fstat(fd).st_uid != os.getuid():
                    raise Rejected(Failure.OWNERSHIP_REJECTED)
                (fcntl.flock if host_lock else fcntl.lockf)(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if (payload_facts(static_files(self.installation)) != current_control
                    or files(self.plugins / 'kast-ide-hosted') != current_plugin
                    or tuple(single_file(path) for path in (self.install_root / 'management.json', self.public_command)) != publications):
                raise Rejected(Failure.PROTECTED_STATE_CHANGED)
            self.verify_protected()
            current_state = reset_state.lstat()
            if ((current_state.st_dev, current_state.st_ino, current_state.st_uid) !=
                    (state_identity.st_dev, state_identity.st_ino, state_identity.st_uid)
                    or tuple(sorted(path.name for path in reset_state.iterdir())) != entries
                    or reset_epoch is not None and single_file(reset_state / 'epoch.json') != reset_epoch):
                raise Rejected(Failure.PROTECTED_STATE_CHANGED)
            if reset_epoch is not None:
                (reset_state / 'epoch.json').unlink()  # Discard only the exact reset-owned case epoch.
            for name in ('bin', 'lib', 'share', 'config'):
                shutil.rmtree(self.installation / name)
            for path in self.installation.glob('state/broker/*/service.plist'):
                path.unlink()
            self.rehydrate('control-original.tar', self.installation)
            shutil.rmtree(self.plugins / 'kast-ide-hosted')
            (self.plugins / 'kast-ide-hosted').mkdir(mode=self.original_plugin_mode)
            self.rehydrate('plugin-original.tar', self.plugins / 'kast-ide-hosted')
            self.rehydrate('publication-original.tar', self.home)
        original_workspace = self.original_workspace
        if not original_workspace.is_dir() or original_workspace.resolve() != original_workspace:
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        environment = self.env
        try:
            self.env = self.original_environment
            self.command([self.installation / 'share/kast/libexec/kast-service', 'enable'],
                         cwd=original_workspace, reject=Failure.RESTORATION_UNVERIFIED, timeout=120)
            deadline = time.monotonic() + 60
            while True:
                status = json.loads(self.command(
                    [self.installation / 'share/kast/libexec/kast-service', 'status'], cwd=original_workspace,
                    reject=Failure.RESTORATION_UNVERIFIED, timeout=15).stdout)
                observation = status.get('coordinator', {}).get('observation', {})
                if (status.get('transport') == 'ready' and status.get('service', {}).get('ownership') == 'matched'
                        and observation.get('status') == 'READY'):
                    break
                if time.monotonic() >= deadline:
                    raise Rejected(Failure.RESTORATION_UNVERIFIED)
                time.sleep(0.25)
            restored = self.control()
            epoch = json.loads((self.installation / 'state/epoch.json').read_text())
            if (observation.get('installationId') != runtime_installation_identity(self.installation)
                    or observation.get('installationId') != epoch['installation']
                    or observation.get('stateEpoch') != epoch['epoch']
                    or observation.get('serviceGeneration') != restored.service_generation
                    or restored.service_generation == self.original_control.service_generation):
                raise Rejected(Failure.RESTORATION_UNVERIFIED)
            self.lifecycle('inspect')
            public = json.loads(self.command([self.public_command, 'status', '--json'],
                                             cwd=original_workspace, reject=Failure.RESTORATION_UNVERIFIED).stdout)
            installed, loaded = public.get('installedVersion', {}), public.get('loadedVersion', {})
            if installed.get('state') != 'VERIFIED' or installed.get('value') != self.original_version:
                raise Rejected(Failure.RESTORATION_UNVERIFIED)
            if loaded.get('state') == 'VERIFIED' and loaded.get('value') == self.original_version:
                projection = OriginalProjection.VERIFIED
            elif loaded.get('state') == 'UNAVAILABLE' and loaded.get('value') is None:
                projection = OriginalProjection.UNAVAILABLE
            else:
                raise Rejected(Failure.RESTORATION_UNVERIFIED)
        finally:
            self.env = environment
        if (content_facts(static_files(self.installation)) != content_facts(self.original_files)
                or content_facts(files(self.plugins / 'kast-ide-hosted')) != content_facts(self.original_plugin)
                or content_facts(tuple(single_file(path) for path in (self.install_root / 'management.json', self.public_command))) != content_facts(self.external_original)
                or content_facts(tuple(single_file(Path(fact.relative_path)) for fact in self.original_login)) != content_facts(self.original_login)):
            raise Rejected(Failure.RESTORATION_UNVERIFIED)
        self.original_restoration = OriginalRestoration('RESTORED', self.original_version, restored,
                                                       epoch['installation'], epoch['epoch'], projection)
        self.verify_protected()
        self.idea_closed()
        self.restored_control = restored
        (self.root / 'recovery.json').write_text(encode(self.original_restoration))
        (self.root / 'recovery.json').chmod(0o600)
        self.progress(Stage.RESTORE, 'VERIFIED')

    def rehydrate(self, name, base):
        # Archives contain only case-admitted regular files; restore directly into the sole owner.
        with tarfile.open(self.root / name) as archive:
            for member in archive.getmembers():
                relative = Path(member.name)
                if not (member.isfile() or member.isdir()) or relative.is_absolute() or '..' in relative.parts:
                    raise Rejected(Failure.RESTORATION_UNVERIFIED)
                # Traverse by directory capability before any mkdir/write. Reject every alias,
                # including an existing ancestor, without following it into unprotected user data.
                with ExitStack() as descriptors:
                    fd = os.open(base, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                    descriptors.callback(os.close, fd)
                    if os.fstat(fd).st_uid != os.getuid():
                        raise Rejected(Failure.OWNERSHIP_REJECTED)
                    for part in relative.parts[:-1]:
                        try:
                            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                        except FileNotFoundError:
                            os.mkdir(part, mode=0o700, dir_fd=fd)
                            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                        descriptors.callback(os.close, child)
                        if os.fstat(child).st_uid != os.getuid():
                            raise Rejected(Failure.OWNERSHIP_REJECTED)
                        fd = child
                    name = relative.name
                    if member.isdir():
                        try:
                            os.mkdir(name, mode=member.mode, dir_fd=fd)
                        except FileExistsError:
                            pass
                        child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                        descriptors.callback(os.close, child)
                        if os.fstat(child).st_uid != os.getuid():
                            raise Rejected(Failure.OWNERSHIP_REJECTED)
                        os.fchmod(child, member.mode)
                    else:
                        destination = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW,
                                              member.mode, dir_fd=fd)
                        if not stat.S_ISREG(os.fstat(destination).st_mode) or os.fstat(destination).st_uid != os.getuid():
                            os.close(destination)
                            raise Rejected(Failure.OWNERSHIP_REJECTED)
                        os.ftruncate(destination, 0)
                        with archive.extractfile(member) as source, os.fdopen(destination, 'wb') as output:
                            shutil.copyfileobj(source, output)
                            os.fchmod(output.fileno(), member.mode)

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
        self.snapshot()
        self.idea_closed()
        if self.control() != self.original_control:
            raise Rejected(Failure.OWNERSHIP_REJECTED)
        self.progress(Stage.INSTALL_C1_P1)
        self.control_installed = True
        self.installer(self.args.control_c1, host_version=self.args.host_p1)
        self.verify_protected()
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
        return Passed('PASSED', os.getuid(), 'SOLE_NORMAL_INSTALLATION_AND_IDEA_PROFILE_REAL_CURRENT_UID', str(self.root),
                      self.metadata['buildNumber'], artifacts, before, after, final_host, plugin,
                      query_c1, query_c2, final_query, control_c1, control_c2, control_after_host, True, True, ())

    def cleanup(self) -> tuple[Failure, ...]:
        if not self.control_installed:
            return ()
        if not self.snapshot_ready:
            return (Failure.RESTORATION_UNVERIFIED,)
        try:
            self.restore()
            return ()
        except (Rejected, OSError, ValueError, KeyError, subprocess.SubprocessError) as rejected:
            return (rejected.failure if isinstance(rejected, Rejected) else Failure.RESTORATION_UNVERIFIED,)


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
            or Path(os.environ.get('HOME', '')) != Path(pwd.getpwuid(os.getuid()).pw_dir)
            or args.owned_root.exists() or args.owned_root.is_symlink() or not args.owned_root.is_absolute()
            or args.owned_root != args.owned_root.resolve() or not 1 <= args.readiness_seconds <= 1200
            or args.owned_root == Path.home() or Path.home() in args.owned_root.parents and '.local' in args.owned_root.parts):
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
    sources = []
    for component, version in (('control', args.control_c1), ('control', args.control_c2),
                               ('host', args.host_p1), ('host', args.host_p2)):
        name = f'kast-{component}-release-v{version}.json'
        if component == 'control':
            admit_asset(component + '_source_record', version, name)
        source = json.loads((args.assets / name).read_text()).get('sourceRevision')
        if not isinstance(source, str) or re.fullmatch(r'[0-9a-f]{40}', source) is None:
            raise Rejected(Failure.INPUT_REJECTED)
        sources.append(ReleaseSource(ReleaseComponent(component), version, source))
    args.artifact_sources = tuple(sources)
    return tuple(artifacts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument('--idea-home', type=Path, required=True, help='Canonical IDEA Contents directory')
    parser.add_argument('--assets', type=Path, required=True, help='Four archives, host release records/helper, and checksum sidecars')
    parser.add_argument('--owned-root', type=Path, required=True, help='New evidence/workspace directory only; normal installation and IDEA profile are used')
    parser.add_argument('--control-c1', default='0.50.2')
    parser.add_argument('--control-c2', default='0.50.3')
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
                        rejected.failure, rejected.exit_code, (), rejected.limit, rejected.semantic)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        report = Failed('REJECTED', os.getuid(), str(args.owned_root), proof.stage,
                        Failure.INPUT_REJECTED, None, ())
    failure_stage = proof.stage
    cleanup = proof.cleanup()
    if not cleanup and isinstance(report, Passed):
        report = replace(report, restored_original_version=proof.original_version, restored_control=proof.restored_control,
                         runner=proof.runner)
    if cleanup:
        report = Failed('RECOVERY_REQUIRED', os.getuid(), str(args.owned_root), failure_stage,
                        report.failure if isinstance(report, Failed) else Failure.CLEANUP_UNVERIFIED,
                        report.exit_code if isinstance(report, Failed) else None, cleanup,
                        report.limit if isinstance(report, Failed) else None,
                        report.semantic if isinstance(report, Failed) else None)
    (args.owned_root / 'report.json').write_text(encode(report))
    (args.owned_root / 'report.json').chmod(0o600)
    print(encode(report), flush=True)
    return 0 if isinstance(report, Passed) else 1


if __name__ == '__main__':
    sys.exit(main())
