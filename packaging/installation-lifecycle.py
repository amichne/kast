"""Explicit installed ownership transitions. Never searches or signals arbitrary processes."""
from dataclasses import dataclass
from enum import Enum
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import uuid

# Fixed projections of InstallationOperationalLimits; checked against the generated catalogue.
RETIREMENT_CHILD_TIMEOUT_MILLIS = 60000
STATE_MAXIMUM_ENTRIES = 100000

class Failure(str, Enum):
    MANIFEST_REJECTED = 'MANIFEST_REJECTED'
    STATE_REJECTED = 'STATE_REJECTED'
    REGISTRY_REJECTED = 'REGISTRY_REJECTED'
    UNRESOLVED_INVOCATION = 'UNRESOLVED_INVOCATION'
    RETIREMENT_UNPROVEN = 'RETIREMENT_UNPROVEN'
    ANCHOR_OWNERSHIP_UNPROVEN = 'ANCHOR_OWNERSHIP_UNPROVEN'
    FILESYSTEM_REJECTED = 'FILESYSTEM_REJECTED'
    EXTERNAL_STATE_UNPROVEN = 'EXTERNAL_STATE_UNPROVEN'

class Rejected(Exception):
    def __init__(self, failure):
        self.failure = failure

class RetirementStage(str, Enum):
    COORDINATOR = 'coordinator-retirement'
    WORKSPACE = 'workspace-retirement'

class RetirementOutcome(str, Enum):
    STARTED = 'started'
    COMPLETED = 'completed'
    EXIT_REJECTED = 'exit-rejected'
    DEADLINE_EXCEEDED = 'deadline-exceeded'
    IO_REJECTED = 'io-rejected'

def observe_retirement(stage, outcome, root):
    event = {'component': 'kast-installation', 'stage': stage.value, 'outcome': outcome.value}
    if stage is RetirementStage.WORKSPACE:
        event['workspaceIdentity'] = hashlib.sha256(str(root).encode()).hexdigest()
    print(json.dumps(event, separators=(',', ':')), file=sys.stderr, flush=True)

def retire_child(executable, arguments, root, environment, stage):
    observe_retirement(stage, RetirementOutcome.STARTED, root)
    try:
        result = subprocess.run([str(executable), *arguments], cwd=root, env=environment,
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                check=False, timeout=RETIREMENT_CHILD_TIMEOUT_MILLIS / 1000)
    except subprocess.TimeoutExpired:
        observe_retirement(stage, RetirementOutcome.DEADLINE_EXCEEDED, root)
        raise Rejected(Failure.RETIREMENT_UNPROVEN) from None
    except OSError:
        observe_retirement(stage, RetirementOutcome.IO_REJECTED, root)
        raise Rejected(Failure.RETIREMENT_UNPROVEN) from None
    if result.returncode != 0:
        observe_retirement(stage, RetirementOutcome.EXIT_REJECTED, root)
        raise Rejected(Failure.RETIREMENT_UNPROVEN)
    observe_retirement(stage, RetirementOutcome.COMPLETED, root)

@dataclass(frozen=True)
class FileIdentity:
    device: int
    inode: int
    owner: int
    @staticmethod
    def observe(path):
        observed = path.lstat()
        return FileIdentity(observed.st_dev, observed.st_ino, observed.st_uid)

@dataclass(frozen=True)
class Installation:
    root: Path
    manifest: dict
    identity: FileIdentity
    @staticmethod
    def admit(raw):
        root = Path(raw)
        if not root.is_absolute() or root.resolve(strict=True) != root or root.parent.name != 'versions':
            raise Rejected(Failure.MANIFEST_REJECTED)
        document = read_json(root / 'installation.json', 65536, Failure.MANIFEST_REJECTED)
        payload = document.get('payloadIdentity', '')
        if (document.get('schemaVersion') != 1 or document.get('installationRoot') != str(root)
                or len(payload) != 71 or not payload.startswith('sha256:')
                or any(c not in '0123456789abcdef' for c in payload[7:])
                or root.name != document.get('semanticVersion', '') + '-' + payload[7:]
                or any(document.get(key) != str(root / suffix) for key, suffix in (
                    ('stateRoot', 'state'), ('configuration', 'config/environment'),
                    ('workspaceRegistry', 'config/workspaces.json')))):
            raise Rejected(Failure.MANIFEST_REJECTED)
        if not isinstance(document.get('externalAnchors'), list) or len(document['externalAnchors']) > 16:
            raise Rejected(Failure.MANIFEST_REJECTED)
        verify_payload(root, document)
        return Installation(root, document, FileIdentity.observe(root))
    def revalidate(self):
        if self.root.resolve(strict=True) != self.root or FileIdentity.observe(self.root) != self.identity:
            raise Rejected(Failure.MANIFEST_REJECTED)


def verify_payload(root, manifest):
    expected = manifest.get('payloadFiles')
    if not isinstance(expected, list) or not 1 <= len(expected) <= 4096:
        raise Rejected(Failure.MANIFEST_REJECTED)
    actual = []
    total = 0
    for directory in ('bin', 'lib', 'share'):
        parent = root / directory
        if parent.is_symlink():
            raise Rejected(Failure.MANIFEST_REJECTED)
        if not parent.exists():
            continue
        for current, directories, files in os.walk(parent, followlinks=False):
            for name in directories:
                if (Path(current) / name).is_symlink():
                    raise Rejected(Failure.MANIFEST_REJECTED)
            for name in files:
                candidate = Path(current) / name
                descriptor = os.open(candidate, os.O_RDONLY | os.O_NOFOLLOW)
                digest = hashlib.sha256()
                with os.fdopen(descriptor, 'rb') as source:
                    observed = os.fstat(source.fileno())
                    if not stat.S_ISREG(observed.st_mode):
                        raise Rejected(Failure.MANIFEST_REJECTED)
                    for chunk in iter(lambda: source.read(65536), b''):
                        total += len(chunk)
                        if total > 1073741824:
                            raise Rejected(Failure.MANIFEST_REJECTED)
                        digest.update(chunk)
                actual.append({'path': candidate.relative_to(root).as_posix(),
                               'sha256': 'sha256:' + digest.hexdigest(), 'mode': observed.st_mode & 0o777})
                if len(actual) > 4096:
                    raise Rejected(Failure.MANIFEST_REJECTED)
    if sorted(actual, key=lambda item: item['path']) != sorted(expected, key=lambda item: item['path']):
        raise Rejected(Failure.MANIFEST_REJECTED)


def read_json(path, limit, failure):
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, 'rb') as source:
            observed = os.fstat(source.fileno())
            if not stat.S_ISREG(observed.st_mode) or observed.st_size > limit:
                raise Rejected(failure)
            raw = source.read(limit + 1)
        if len(raw) > limit:
            raise Rejected(failure)
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise Rejected(failure)
                result[key] = value
            return result
        document = json.loads(raw, object_pairs_hook=unique)
        if not isinstance(document, dict):
            raise Rejected(failure)
        return document
    except (OSError, ValueError, TypeError):
        raise Rejected(failure) from None


def inspect_state(installation):
    state = installation.root / 'state'
    if state.is_symlink() or (state.exists() and not state.is_dir()):
        raise Rejected(Failure.STATE_REJECTED)
    if not state.exists():
        return None
    identity = FileIdentity.observe(state)
    count = 0
    for current, directories, files in os.walk(state, followlinks=False):
        count += len(directories) + len(files)
        if count > STATE_MAXIMUM_ENTRIES:
            raise Rejected(Failure.STATE_REJECTED)
        for name in directories + files:
            if (Path(current) / name).is_symlink():
                raise Rejected(Failure.STATE_REJECTED)
        for name in files:
            if name == 'invocations.json':
                journal = read_json(Path(current) / name, 2097152, Failure.UNRESOLVED_INVOCATION)
                records = journal.get('records')
                if (journal.get('schemaVersion') != 1 or not isinstance(records, dict) or len(records) > 4096
                        or any(not isinstance(record, dict) or record.get('phase') != 'COMPLETED' for record in records.values())):
                    raise Rejected(Failure.UNRESOLVED_INVOCATION)
            # Semantic mutation recovery journals are authoritative; reset never guesses completion.
            if 'journal' in name.lower() and name != 'invocations.json':
                raise Rejected(Failure.UNRESOLVED_INVOCATION)
    return identity


def workspaces(installation):
    registry = installation.root / 'config/workspaces.json'
    if not registry.exists() and not registry.is_symlink():
        state = installation.root / 'state'
        if state.exists() and any(entry.name != 'epoch.json' for entry in state.iterdir()):
            raise Rejected(Failure.REGISTRY_REJECTED)
        return []
    document = read_json(registry, 262144, Failure.REGISTRY_REJECTED)
    roots = document.get('roots')
    if (set(document) != {'schemaVersion', 'revision', 'roots'} or document['schemaVersion'] != 2
            or not isinstance(document['revision'], int) or document['revision'] < 1
            or not isinstance(roots, list) or not 1 <= len(roots) <= 256):
        raise Rejected(Failure.REGISTRY_REJECTED)
    admitted = []
    for raw in roots:
        if not isinstance(raw, str):
            raise Rejected(Failure.REGISTRY_REJECTED)
        root = Path(raw)
        if not root.is_absolute() or not root.is_dir() or root.resolve() != root or root in admitted:
            raise Rejected(Failure.REGISTRY_REJECTED)
        admitted.append(root)
    return admitted


def owned_aliases(installation):
    result = []
    run = installation.root / 'state/run'
    expected = Path('/tmp/kast-uds-' + hashlib.sha256(str(run).encode()).hexdigest()[:32])
    for anchor in installation.manifest['externalAnchors']:
        if not isinstance(anchor, dict):
            raise Rejected(Failure.MANIFEST_REJECTED)
        if anchor.get('kind') != 'socket-alias':
            continue
        if (anchor.get('path') != str(expected) or anchor.get('expectedLinkTarget') != str(run)
                or anchor.get('identityReceipt') != str(run / 'endpoint-alias.json')):
            raise Rejected(Failure.MANIFEST_REJECTED)
        if not os.path.lexists(expected):
            continue
        receipt = read_json(run / 'endpoint-alias.json', 8192, Failure.ANCHOR_OWNERSHIP_UNPROVEN)
        alias_identity = FileIdentity.observe(expected)
        target_identity = FileIdentity.observe(run)
        def matches(actual, recorded):
            return recorded == {'device': actual.device, 'inode': actual.inode, 'owner': actual.owner}
        if (receipt.get('schemaVersion') != 1 or receipt.get('alias') != str(expected) or receipt.get('target') != str(run)
                or not expected.is_symlink() or os.readlink(expected) != str(run)
                or not matches(alias_identity, receipt.get('aliasIdentity'))
                or not matches(target_identity, receipt.get('targetIdentity'))):
            raise Rejected(Failure.ANCHOR_OWNERSHIP_UNPROVEN)
        result.append((expected, alias_identity))
    return result


def retire(installation, roots):
    executable = installation.root / 'bin/kast-complete'
    if executable.is_symlink() or not executable.is_file() or not os.access(executable, os.X_OK):
        raise Rejected(Failure.RETIREMENT_UNPROVEN)
    environment = {key: os.environ[key] for key in ('PATH', 'HOME', 'JAVA_HOME', 'CODEX_EXECUTABLE') if key in os.environ}
    # Java does not derive user.home from HOME. Preserve an admitted private home
    # across exact retirement children without forwarding arbitrary JVM options.
    home = environment.get('HOME')
    if home is None or not Path(home).is_absolute() or any(character in home for character in ('"', '\n', '\r', '\x00')):
        raise Rejected(Failure.RETIREMENT_UNPROVEN)
    temporary = os.environ.get('TMPDIR', '/tmp')
    if not Path(temporary).is_absolute() or any(character in temporary for character in ('"', '\n', '\r', '\x00')):
        raise Rejected(Failure.RETIREMENT_UNPROVEN)
    environment['TMPDIR'] = temporary
    environment['JAVA_TOOL_OPTIONS'] = f'-Duser.home="{home}" -Djava.io.tmpdir="{temporary}"'
    environment['KAST_CONFIGURATION_FILE'] = str(installation.root / 'config/environment')
    # Host selection is retained from installation admission, never inferred from a workspace.
    if 'codexHome' in installation.manifest:
        environment['CODEX_HOME'] = installation.manifest['codexHome']
    retire_child(executable, ['app-server', 'disable'], installation.root, environment, RetirementStage.COORDINATOR)
    for root in roots:
        retire_child(executable, ['stop'], root, environment, RetirementStage.WORKSPACE)


def delete_tree(path):
    # CPython's fd-based implementation resists symlink replacement during recursive deletion.
    if not shutil.rmtree.avoids_symlink_attacks:
        raise Rejected(Failure.FILESYSTEM_REJECTED)
    shutil.rmtree(path)


def execute(installation, operation, dry_run):
    installation.revalidate()
    state_identity = inspect_state(installation)
    roots = workspaces(installation)
    validate_owned_configuration(installation)
    aliases = owned_aliases(installation)
    report = {'operation': 'installation.' + operation, 'installation': str(installation.root),
              'state': str(installation.root / 'state'), 'retained': ['config', 'payload'],
              'workspaceCount': len(roots), 'externalAnchors': installation.manifest['externalAnchors'],
              'status': 'planned' if dry_run else 'inspected'}
    if operation == 'inspect' or dry_run:
        return report
    transition = begin_transition(installation, operation)
    if state_identity is not None:
        retire(installation, roots)
    installation.revalidate()
    if inspect_state(installation) != state_identity:
        raise Rejected(Failure.STATE_REJECTED)
    worker_receipts = installation.root / "state/workers"
    if worker_receipts.exists() and any(worker_receipts.iterdir()):
        raise Rejected(Failure.RETIREMENT_UNPROVEN)
    if owned_aliases(installation) != aliases:
        raise Rejected(Failure.ANCHOR_OWNERSHIP_UNPROVEN)
    state = installation.root / 'state'
    epoch = None
    if state_identity is not None:
        epoch_file = state / 'epoch.json'
        if epoch_file.exists():
            epoch = read_json(epoch_file, 1024, Failure.STATE_REJECTED)
            if (set(epoch) != {'schemaVersion', 'installation', 'epoch'} or epoch['schemaVersion'] != 1
                    or not isinstance(epoch['installation'], str) or not epoch['installation'].startswith('sha256:')
                    or str(uuid.UUID(epoch['epoch'])) != epoch['epoch']):
                raise Rejected(Failure.STATE_REJECTED)
        for alias, identity in aliases:
            if FileIdentity.observe(alias) != identity:
                raise Rejected(Failure.ANCHOR_OWNERSHIP_UNPROVEN)
            alias.unlink()
        retired = installation.root / ('.retired-state-' + uuid.uuid4().hex)
        state.rename(retired)
        state.mkdir(mode=0o700)
        if epoch is not None:
            epoch['epoch'] = str(uuid.uuid4())
            descriptor = os.open(state / 'epoch.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as output:
                json.dump(epoch, output, separators=(',', ':'))
                output.flush()
                os.fsync(output.fileno())
        delete_tree(retired)
    if operation == 'remove':
        remove_anchors(installation)
        installation.revalidate()
        delete_tree(installation.root)
        report['status'] = 'removed'
        report['retained'] = ['activation.lock', 'external dependencies']
    else:
        finish_transition(installation, transition)
        report['status'] = 'reset'
    return report


def validate_owned_configuration(installation):
    file = installation.root / 'config/environment'
    if not file.exists():
        return
    descriptor = os.open(file, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as source:
        raw = source.read(262145)
    if len(raw) > 262144:
        raise Rejected(Failure.EXTERNAL_STATE_UNPROVEN)
    for line in raw.decode('utf-8').splitlines():
        key, separator, value = line.partition('=')
        if key.strip() in {'KAST_CACHE_ROOT', 'KAST_RUNTIME_DIRECTORY', 'KAST_RUNTIME_STORE', 'KAST_IDE_CONFIG_HOME'}:
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
                value = value[1:-1]
            selected = Path(value)
            if (not separator or not selected.is_absolute() or selected.resolve() != selected
                    or not selected.is_relative_to(installation.root)):
                raise Rejected(Failure.EXTERNAL_STATE_UNPROVEN)


def begin_transition(installation, operation):
    path = installation.root / '.lifecycle-transition.json'
    document = {'schemaVersion': 1, 'installation': str(installation.root),
                'payloadIdentity': installation.manifest['payloadIdentity'], 'operation': operation}
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError:
        # A failed attempt keeps admission closed; only this explicit lifecycle command retries it.
        previous = read_json(path, 2048, Failure.STATE_REJECTED)
        if previous != document:
            raise Rejected(Failure.STATE_REJECTED)
    else:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(document, output, separators=(',', ':'))
            output.flush()
            os.fsync(output.fileno())
    return FileIdentity.observe(path)


def finish_transition(installation, identity):
    path = installation.root / '.lifecycle-transition.json'
    if FileIdentity.observe(path) != identity:
        raise Rejected(Failure.STATE_REJECTED)
    path.unlink()


def remove_anchors(installation):
    outer = installation.root.parent.parent
    current = outer / 'current'
    expected_current = 'versions/' + installation.root.name
    current_matches = current.is_symlink() and os.readlink(current) == expected_current
    selected = []
    for anchor in installation.manifest['externalAnchors']:
        kind = anchor.get('kind')
        path = Path(anchor.get('path', ''))
        target = anchor.get('expectedLinkTarget')
        if kind == 'current':
            if path != current or target != expected_current:
                raise Rejected(Failure.MANIFEST_REJECTED)
            if current_matches:
                selected.append((path, FileIdentity.observe(path), target))
        elif kind in ('command', 'codex-command'):
            name = 'kast' if kind == 'command' else 'kast-codex'
            executable = 'kast-complete' if kind == 'command' else 'kast-codex-complete'
            if (not path.is_absolute() or path.name != name or target != str(current / 'bin' / executable)
                    or anchor.get('requiresCurrentTarget') != expected_current):
                raise Rejected(Failure.MANIFEST_REJECTED)
            if current_matches and path.is_symlink() and os.readlink(path) == target:
                selected.append((path, FileIdentity.observe(path), target))
        elif kind == 'login':
            # The exact executable's disable operation owns login cleanup, not this manifest reader.
            if path.exists() or path.is_symlink():
                raise Rejected(Failure.ANCHOR_OWNERSHIP_UNPROVEN)
        elif kind != 'socket-alias':
            raise Rejected(Failure.MANIFEST_REJECTED)
    for path, identity, target in selected:
        if FileIdentity.observe(path) != identity or not path.is_symlink() or os.readlink(path) != target:
            raise Rejected(Failure.ANCHOR_OWNERSHIP_UNPROVEN)
    for path, identity, target in selected:
        if FileIdentity.observe(path) != identity:
            raise Rejected(Failure.ANCHOR_OWNERSHIP_UNPROVEN)
        path.unlink()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--installation', required=True)
    parser.add_argument('operation', choices=('inspect', 'reset', 'remove'))
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--json', action='store_true')
    arguments = parser.parse_args()
    try:
        installation = Installation.admit(arguments.installation)
        if arguments.operation == 'inspect' or arguments.dry_run:
            report = execute(installation, arguments.operation, arguments.dry_run)
        else:
            lock = installation.root.parent.parent / 'activation.lock'
            descriptor = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                    raise Rejected(Failure.MANIFEST_REJECTED)
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                report = execute(installation, arguments.operation, arguments.dry_run)
            finally:
                os.close(descriptor)
        print(json.dumps(report, separators=(',', ':')))
        return 0
    except Rejected as rejected:
        print(json.dumps({'status': 'rejected', 'failure': rejected.failure.value}))
        return 1
    except (OSError, ValueError, TypeError, KeyError):
        print(json.dumps({'status': 'rejected', 'failure': Failure.FILESYSTEM_REJECTED.value}))
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
