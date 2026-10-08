"""Install only the IntelliJ-owned Kast Host plugin through its ordinary restart lifecycle."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path
import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tempfile
import uuid
import xml.etree.ElementTree as XML
import zipfile


class Failure(str, Enum):
    PAYLOAD_REJECTED = 'PAYLOAD_REJECTED'
    RELEASE_REJECTED = 'RELEASE_REJECTED'
    COMPATIBILITY_REJECTED = 'COMPATIBILITY_REJECTED'
    OWNERSHIP_UNPROVEN = 'OWNERSHIP_UNPROVEN'
    FILESYSTEM_REJECTED = 'FILESYSTEM_REJECTED'
    RECOVERY_REQUIRED = 'RECOVERY_REQUIRED'


class Rejected(Exception):
    def __init__(self, failure):
        self.failure = failure


@dataclass(frozen=True)
class Identity:
    device: int
    inode: int
    owner: int

    @staticmethod
    def observe(path):
        observed = path.lstat()
        if observed.st_uid != os.getuid() or not stat.S_ISDIR(observed.st_mode):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        return Identity(observed.st_dev, observed.st_ino, observed.st_uid)


class Capability(str, Enum):
    WORKSPACE = 'workspace.lifecycle'
    QUERY = 'query.run'
    SOURCE = 'source.read'
    DIAGNOSTIC = 'diagnostic.check'
    PLAN = 'change.plan'
    APPLY = 'change.apply'
    RECOVER = 'change.recover'


@dataclass(frozen=True)
class HostedContract:
    runtime_protocol_identity: str
    operation_registry_digest: str
    wire_schema_digest: str
    capabilities: tuple[Capability, ...]

    @staticmethod
    def parse(value):
        if (not isinstance(value, dict) or set(value) != {'type', 'runtimeProtocolIdentity', 'operationRegistryDigest', 'wireSchemaDigest', 'capabilities'}
                or value['type'] != 'HOSTED_CONTRACT'
                or not isinstance(value['runtimeProtocolIdentity'], str)
                or len(value['runtimeProtocolIdentity']) > 128
                or re.fullmatch(r'[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\.v[1-9][0-9]*', value['runtimeProtocolIdentity']) is None):
            raise Rejected(Failure.RELEASE_REJECTED)
        for key in ('operationRegistryDigest', 'wireSchemaDigest'):
            if not isinstance(value[key], str) or re.fullmatch(r'sha256:[0-9a-f]{64}', value[key]) is None:
                raise Rejected(Failure.RELEASE_REJECTED)
        raw = value['capabilities']
        if not isinstance(raw, list) or not 1 <= len(raw) <= len(Capability) or any(not isinstance(item, str) for item in raw) or len(set(raw)) != len(raw):
            raise Rejected(Failure.RELEASE_REJECTED)
        try:
            capabilities = tuple(Capability(item) for item in raw)
        except ValueError:
            raise Rejected(Failure.RELEASE_REJECTED) from None
        return HostedContract(value['runtimeProtocolIdentity'], value['operationRegistryDigest'], value['wireSchemaDigest'], capabilities)


def read_document(path):
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise Rejected(Failure.RELEASE_REJECTED)
    with path.open('rb') as stream:
        raw = stream.read(65537)
    if len(raw) > 65536:
        raise Rejected(Failure.RELEASE_REJECTED)
    try:
        return json.loads(raw)
    except ValueError:
        raise Rejected(Failure.RELEASE_REJECTED) from None


def admit_host_release(path, payload, required_control=None):
    value = read_document(path)
    if (not isinstance(value, dict) or set(value) != {'type', 'hostedPluginVersion', 'artifact', 'providedHostedContract', 'supportedIntellijReleaseLine', 'sourceRevision'}
            or value['type'] != 'HOST_RELEASE' or value['hostedPluginVersion'] != payload.version
            or value['supportedIntellijReleaseLine'] != payload.idea_build.split('.')[0]
            or not isinstance(value['sourceRevision'], str) or re.fullmatch(r'[0-9a-f]{40}', value['sourceRevision']) is None):
        raise Rejected(Failure.RELEASE_REJECTED)
    artifact = value['artifact']
    if (not isinstance(artifact, dict) or set(artifact) != {'fileName', 'sha256', 'bytes'}
            or artifact['fileName'] != payload.archive.name or artifact['sha256'] != 'sha256:' + payload.sha256
            or type(artifact['bytes']) is not int or not 1 <= artifact['bytes'] <= 1073741824
            or artifact['bytes'] != payload.archive.stat().st_size):
        raise Rejected(Failure.RELEASE_REJECTED)
    provided = HostedContract.parse(value['providedHostedContract'])
    if required_control is not None:
        control = read_document(required_control)
        if not isinstance(control, dict) or 'requiredHostedContract' not in control:
            raise Rejected(Failure.RELEASE_REJECTED)
        required = HostedContract.parse(control['requiredHostedContract'])
        if required != provided:
            raise Rejected(Failure.COMPATIBILITY_REJECTED)
    return provided


@dataclass(frozen=True)
class HostedPluginPayload:
    archive: Path
    sha256: str
    version: str
    idea_build: str


class HostInstallMode(str, Enum):
    PLAN = 'PLAN'
    APPLY = 'APPLY'


class HostReportType(str, Enum):
    PLANNED = 'PLANNED'
    ACTIVATED = 'ACTIVATED'


class HostReceiptType(str, Enum):
    HOST_PREPARED = 'HOST_PREPARED'
    HOST_ACTIVE = 'HOST_ACTIVE'


class HostRejectionType(str, Enum):
    REJECTED = 'REJECTED'


@dataclass(frozen=True)
class HostInstallRequest:
    payload: HostedPluginPayload
    plugin_root: Path
    mode: HostInstallMode = HostInstallMode.APPLY


@dataclass(frozen=True)
class HostReceipt:
    type: HostReceiptType
    destination: str
    candidate: str
    candidateIdentity: Identity
    backup: str
    priorIdentity: Identity | None
    version: str
    sha256: str


class HostArtifactType(str, Enum):
    DIRECTORY = 'DIRECTORY'
    FILE = 'FILE'


@dataclass(frozen=True)
class HostDirectoryArtifact:
    type: HostArtifactType
    path: str
    identity: Identity


@dataclass(frozen=True)
class HostFileArtifact:
    type: HostArtifactType
    path: str
    identity: Identity
    sha256: str
    bytes: int


@dataclass(frozen=True)
class HostActiveReceipt:
    type: HostReceiptType
    destination: str
    pluginRootIdentity: Identity
    destinationIdentity: Identity
    version: str
    sha256: str
    inventory: tuple[HostDirectoryArtifact | HostFileArtifact, ...]


class HostRemovalType(str, Enum):
    REMOVED = 'REMOVED'


@dataclass(frozen=True)
class HostRemoval:
    type: HostRemovalType
    plugin: str


@dataclass(frozen=True)
class HostReport:
    type: HostReportType
    hostedPluginVersion: str
    plugin: str
    restartRequired: bool = True


@dataclass(frozen=True)
class HostRejection:
    type: HostRejectionType
    failure: Failure


def encode(value):
    return json.dumps(asdict(value), default=lambda item: item.value if isinstance(item, Enum) else str(item)) + '\n'


def sync(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_receipt(path, receipt):
    document = encode(receipt)
    if len(document.encode('utf-8')) > 2097152:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    temporary = path.with_name(path.name + '.new')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(document)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)
    sync(path.parent)


def owned_file(path, private=False):
    observed = path.lstat()
    if (observed.st_uid != os.getuid() or not stat.S_ISREG(observed.st_mode)
            or (private and stat.S_IMODE(observed.st_mode) != 0o600)):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    return Identity(observed.st_dev, observed.st_ino, observed.st_uid)


def file_artifact(path, relative):
    identity = owned_file(path)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    digest, size = hashlib.sha256(), 0
    with os.fdopen(descriptor, 'rb') as stream:
        observed = os.fstat(stream.fileno())
        if Identity(observed.st_dev, observed.st_ino, observed.st_uid) != identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        for block in iter(lambda: stream.read(65536), b''):
            size += len(block)
            if size > 1073741824:
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            digest.update(block)
    if owned_file(path) != identity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    return HostFileArtifact(HostArtifactType.FILE, relative, identity, digest.hexdigest(), size)


def inventory(destination):
    captured = []
    total = 0
    def visit(directory):
        nonlocal total
        if len(directory.relative_to(destination).parts) > 32:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        directory_identity = Identity.observe(directory)
        with os.scandir(directory) as entries:
            for entry in entries:
                if len(captured) >= 4096:
                    raise Rejected(Failure.OWNERSHIP_UNPROVEN)
                path = Path(entry.path)
                relative = path.relative_to(destination).as_posix()
                if len(relative) > 4096 or '\\' in relative:
                    raise Rejected(Failure.OWNERSHIP_UNPROVEN)
                if entry.is_dir(follow_symlinks=False):
                    captured.append(HostDirectoryArtifact(HostArtifactType.DIRECTORY, relative, Identity.observe(path)))
                    visit(path)
                else:
                    artifact = file_artifact(path, relative)
                    total += artifact.bytes
                    if total > 1073741824:
                        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
                    captured.append(artifact)
        if Identity.observe(directory) != directory_identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    visit(destination)
    return tuple(sorted(captured, key=lambda artifact: artifact.path))


def parse_identity(value):
    if (not isinstance(value, dict) or set(value) != {'device', 'inode', 'owner'}
            or any(type(value[key]) is not int or value[key] < 0 for key in value)
            or value['owner'] != os.getuid()):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    return Identity(**value)


def reject_duplicate_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        result[key] = value
    return result


def read_active_receipt(path):
    owned_file(path, private=True)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        raw = stream.read(2097153)
    if len(raw) > 2097152:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    try:
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=reject_duplicate_fields)
    except (ValueError, UnicodeError):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN) from None
    if isinstance(value, dict) and value.get('type') == 'HOST_PREPARED':
        raise Rejected(Failure.RECOVERY_REQUIRED)
    if (not isinstance(value, dict)
            or set(value) != {'type', 'destination', 'pluginRootIdentity', 'destinationIdentity', 'version', 'sha256', 'inventory'}
            or value['type'] != 'HOST_ACTIVE' or not isinstance(value['destination'], str)
            or not isinstance(value['version'], str) or re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', value['version']) is None
            or not isinstance(value['sha256'], str) or re.fullmatch(r'[0-9a-f]{64}', value['sha256']) is None
            or not isinstance(value['inventory'], list) or not 1 <= len(value['inventory']) <= 4096):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    artifacts, total = [], 0
    for entry in value['inventory']:
        if not isinstance(entry, dict) or not isinstance(entry.get('path'), str):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        relative = entry['path']
        if (not relative or len(relative) > 4096 or '\\' in relative
                or len(Path(relative).parts) > 32 or Path(relative).is_absolute()
                or any(part in ('', '.', '..') for part in relative.split('/'))):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        identity = parse_identity(entry.get('identity'))
        if entry.get('type') == 'DIRECTORY' and set(entry) == {'type', 'path', 'identity'}:
            artifacts.append(HostDirectoryArtifact(HostArtifactType.DIRECTORY, relative, identity))
        elif (entry.get('type') == 'FILE' and set(entry) == {'type', 'path', 'identity', 'sha256', 'bytes'}
                and isinstance(entry['sha256'], str) and re.fullmatch(r'[0-9a-f]{64}', entry['sha256'])
                and type(entry['bytes']) is int and 0 <= entry['bytes'] <= 1073741824):
            total += entry['bytes']
            artifacts.append(HostFileArtifact(HostArtifactType.FILE, relative, identity, entry['sha256'], entry['bytes']))
        else:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    paths = [artifact.path for artifact in artifacts]
    directories = {artifact.path for artifact in artifacts if artifact.type is HostArtifactType.DIRECTORY}
    if paths != sorted(set(paths)) or total > 1073741824:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    for path in paths:
        if any(parent.as_posix() not in directories for parent in Path(path).parents if parent != Path('.')):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    return HostActiveReceipt(HostReceiptType.HOST_ACTIVE, value['destination'], parse_identity(value['pluginRootIdentity']),
                             parse_identity(value['destinationIdentity']), value['version'], value['sha256'], tuple(artifacts))


def admit_active(root, receipt, missing=False):
    destination = root / 'kast-ide-hosted'
    if receipt.destination != str(destination) or Identity.observe(root) != receipt.pluginRootIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if not os.path.lexists(destination):
        if missing:
            return
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if Identity.observe(destination) != receipt.destinationIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    actual = inventory(destination)
    expected = {artifact.path: artifact for artifact in receipt.inventory}
    if any(expected.get(artifact.path) != artifact for artifact in actual):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if not missing and actual != receipt.inventory:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def admit_root(root):
    if not root.is_absolute() or root != Path(os.path.normpath(root)) or root.resolve() != root:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def admit_removal_artifact(root, receipt, artifact):
    destination = root / 'kast-ide-hosted'
    if Identity.observe(root) != receipt.pluginRootIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if not os.path.lexists(destination):
        return
    if Identity.observe(destination) != receipt.destinationIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    expected = {item.path: item for item in receipt.inventory}
    for parent in reversed(Path(artifact.path).parents):
        if parent == Path('.'):
            continue
        path = destination / parent
        if not os.path.lexists(path):
            return
        if Identity.observe(path) != expected[parent.as_posix()].identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    path = destination / artifact.path
    if os.path.lexists(path):
        observed = (HostDirectoryArtifact(HostArtifactType.DIRECTORY, artifact.path, Identity.observe(path))
                    if artifact.type is HostArtifactType.DIRECTORY else file_artifact(path, artifact.path))
        if observed != artifact:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def remove_host(root):
    try:
        return remove_host_files(root)
    except OSError:
        raise Rejected(Failure.FILESYSTEM_REJECTED) from None


def admit_removal_root(root):
    admit_root(root)
    ancestor = root
    while not os.path.lexists(ancestor):
        ancestor = ancestor.parent
    Identity.observe(ancestor)
    if stat.S_IMODE(ancestor.lstat().st_mode) & 0o022:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def admit_empty_retained(retained):
    identity = Identity.observe(retained)
    if stat.S_IMODE(retained.lstat().st_mode) != 0o700:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    with os.scandir(retained) as entries:
        if next(entries, None) is not None:
            raise Rejected(Failure.RECOVERY_REQUIRED)
    return identity


def retire_empty_retained(root, retained):
    captured = admit_empty_retained(retained)
    admit_removal_root(root)
    if os.path.lexists(root / 'kast-ide-hosted') or admit_empty_retained(retained) != captured:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    retained.rmdir()
    sync(retained.parent)


def remove_host_files(root):
    admit_removal_root(root)
    destination = root / 'kast-ide-hosted'
    retained = root.parent / '.kast-plugin-recovery'
    receipt_path = retained / 'host-install.json'
    if not os.path.lexists(receipt_path):
        if os.path.lexists(destination):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        if os.path.lexists(retained):
            retire_empty_retained(root, retained)
        return HostRemoval(HostRemovalType.REMOVED, str(destination))
    retained_identity = Identity.observe(retained)
    if stat.S_IMODE(retained.lstat().st_mode) != 0o700:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    lock_path = retained / 'host-install.lock'
    if not os.path.lexists(lock_path):
        # A prior removal may have retired the lock before deleting its final receipt.
        admitted = read_active_receipt(receipt_path)
        admit_active(root, admitted, missing=True)
        descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.close(descriptor)
    lock_identity = owned_file(lock_path, private=True)
    descriptor = os.open(lock_path, os.O_RDWR | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'r+') as lock:
        observed = os.fstat(lock.fileno())
        if Identity(observed.st_dev, observed.st_ino, observed.st_uid) != lock_identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        receipt_identity = owned_file(receipt_path, private=True)
        receipt = read_active_receipt(receipt_path)
        admit_active(root, receipt, missing=True)
        with os.scandir(retained) as entries:
            if any(entry.name not in ('host-install.lock', 'host-install.json') for entry in entries):
                raise Rejected(Failure.RECOVERY_REQUIRED)
        for artifact in sorted(receipt.inventory, key=lambda item: (len(Path(item.path).parts), item.path), reverse=True):
            admit_root(root)
            if Identity.observe(retained) != retained_identity or owned_file(receipt_path, private=True) != receipt_identity:
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            admit_removal_artifact(root, receipt, artifact)
            path = destination / artifact.path
            if os.path.lexists(path):
                if artifact.type is HostArtifactType.DIRECTORY:
                    path.rmdir()
                else:
                    path.unlink()
        admit_active(root, receipt, missing=True)
        if os.path.lexists(destination):
            destination.rmdir()
            sync(root)
        if Identity.observe(retained) != retained_identity or owned_file(receipt_path, private=True) != receipt_identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        if owned_file(lock_path, private=True) != lock_identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        lock_path.unlink()
        if Identity.observe(retained) != retained_identity or owned_file(receipt_path, private=True) != receipt_identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        receipt_path.unlink()
        sync(retained)
        retained.rmdir()
        sync(retained.parent)
    return HostRemoval(HostRemovalType.REMOVED, str(destination))


def validate_payload(payload, stage):
    if (not payload.archive.is_absolute() or payload.archive.is_symlink() or not payload.archive.is_file()
            or re.fullmatch(r'[0-9a-f]{64}', payload.sha256) is None
            or re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', payload.version) is None
            or re.fullmatch(r'[0-9]+(\.[0-9]+)+', payload.idea_build) is None):
        raise Rejected(Failure.PAYLOAD_REJECTED)
    digest = hashlib.sha256()
    with payload.archive.open('rb') as source:
        for block in iter(lambda: source.read(65536), b''):
            digest.update(block)
    if digest.hexdigest() != payload.sha256:
        raise Rejected(Failure.PAYLOAD_REJECTED)
    with zipfile.ZipFile(payload.archive) as archive:
        entries = archive.infolist()
        if not entries or len(entries) > 4096 or sum(entry.file_size for entry in entries) > 1073741824:
            raise Rejected(Failure.PAYLOAD_REJECTED)
        names = set()
        for entry in entries:
            name = entry.filename.rstrip('/')
            path = Path(name)
            mode = entry.external_attr >> 16
            if (name in names or path.is_absolute() or not path.parts or path.parts[0] != 'kast-ide-hosted'
                    or any(part in ('', '.', '..') for part in entry.filename.rstrip('/').split('/'))
                    or '\\' in name or (mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR))):
                raise Rejected(Failure.PAYLOAD_REJECTED)
            names.add(name)
        archive.extractall(stage)
    plugin = stage / 'kast-ide-hosted'
    Identity.observe(plugin)
    descriptors = []
    for jar in sorted((plugin / 'lib').glob('*.jar')):
        with zipfile.ZipFile(jar) as archive:
            if 'META-INF/plugin.xml' in archive.namelist():
                if archive.getinfo('META-INF/plugin.xml').file_size > 1048576:
                    raise Rejected(Failure.PAYLOAD_REJECTED)
                descriptors.append(XML.fromstring(archive.read('META-INF/plugin.xml')))
    if len(descriptors) != 1:
        raise Rejected(Failure.PAYLOAD_REJECTED)
    descriptor = descriptors[0]
    compatibility = descriptor.find('idea-version')
    line = payload.idea_build.split('.')[0]
    if (descriptor.findtext('id') != 'io.github.amichne.kast.ide-hosted'
            or descriptor.findtext('version') != payload.version or compatibility is None
            or compatibility.get('since-build', '').split('.')[0] != line
            or compatibility.get('until-build') != line + '.*'):
        raise Rejected(Failure.PAYLOAD_REJECTED)
    return plugin


def install(request):
    root = request.plugin_root
    admit_root(root)
    with tempfile.TemporaryDirectory(prefix='kast-host-stage-') as temporary:
        plugin = validate_payload(request.payload, Path(temporary))
        if request.mode is HostInstallMode.PLAN:
            return HostReport(HostReportType.PLANNED, request.payload.version, str(root / 'kast-ide-hosted'))
        root.mkdir(parents=True, exist_ok=True)
        Identity.observe(root)
        retained = root.parent / '.kast-plugin-recovery'
        retained.mkdir(mode=0o700, exist_ok=True)
        Identity.observe(retained)
        if stat.S_IMODE(retained.lstat().st_mode) != 0o700:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        lock_descriptor = os.open(retained / 'host-install.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        with os.fdopen(lock_descriptor, 'r+') as lock:
            if os.fstat(lock.fileno()).st_uid != os.getuid():
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            receipt_path = retained / 'host-install.json'
            previous_receipt = None
            if os.path.lexists(receipt_path):
                previous_receipt = read_active_receipt(receipt_path)
                admit_active(root, previous_receipt)
            destination = root / 'kast-ide-hosted'
            prior = Identity.observe(destination) if os.path.lexists(destination) else None
            token = uuid.uuid4().hex
            candidate, backup = retained / ('host-candidate-' + token), retained / ('host-prior-' + token)
            shutil.copytree(plugin, candidate)
            candidate_identity = Identity.observe(candidate)
            receipt = HostReceipt(HostReceiptType.HOST_PREPARED, str(destination), str(candidate), candidate_identity,
                                  str(backup), prior, request.payload.version, request.payload.sha256)
            write_receipt(receipt_path, receipt)
            try:
                if prior is not None:
                    if Identity.observe(destination) != prior:
                        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
                    destination.rename(backup)
                candidate.rename(destination)
                sync(root)
                if Identity.observe(destination) != candidate_identity:
                    raise Rejected(Failure.OWNERSHIP_UNPROVEN)
                active = HostActiveReceipt(HostReceiptType.HOST_ACTIVE, str(destination), Identity.observe(root),
                                           candidate_identity, request.payload.version, request.payload.sha256,
                                           inventory(destination))
                write_receipt(receipt_path, active)
            except (OSError, Rejected):
                try:
                    if os.path.lexists(destination):
                        observed = Identity.observe(destination)
                        if observed == candidate_identity:
                            destination.rename(candidate)
                        elif observed != prior:
                            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
                    if prior is not None and os.path.lexists(backup):
                        if Identity.observe(backup) != prior or os.path.lexists(destination):
                            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
                        backup.rename(destination)
                    if prior is not None and Identity.observe(destination) != prior:
                        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
                    sync(root)
                    shutil.rmtree(candidate)
                    if previous_receipt is None:
                        receipt_path.unlink()
                    else:
                        write_receipt(receipt_path, previous_receipt)
                except (OSError, Rejected):
                    raise Rejected(Failure.RECOVERY_REQUIRED) from None
                raise Rejected(Failure.FILESYSTEM_REJECTED) from None
            if prior is not None:
                if Identity.observe(backup) != prior:
                    raise Rejected(Failure.RECOVERY_REQUIRED)
                shutil.rmtree(backup)
            sync(retained)
            return HostReport(HostReportType.ACTIVATED, request.payload.version, str(destination))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--sha256')
    parser.add_argument('--version')
    parser.add_argument('--idea-build')
    parser.add_argument('--plugin-root', required=True, type=Path)
    parser.add_argument('--release-record', type=Path)
    parser.add_argument('--required-control', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--remove', action='store_true')
    arguments = parser.parse_args()
    try:
        install_values = (arguments.archive, arguments.sha256, arguments.version, arguments.idea_build, arguments.release_record)
        if arguments.remove:
            if any(value is not None for value in install_values) or arguments.required_control is not None or arguments.dry_run:
                raise Rejected(Failure.PAYLOAD_REJECTED)
            print(encode(remove_host(arguments.plugin_root)), end='')
            return 0
        if any(value is None for value in install_values):
            raise Rejected(Failure.PAYLOAD_REJECTED)
        payload = HostedPluginPayload(arguments.archive, arguments.sha256, arguments.version, arguments.idea_build)
        admit_host_release(arguments.release_record, payload, arguments.required_control)
        report = install(HostInstallRequest(payload, arguments.plugin_root, HostInstallMode.PLAN if arguments.dry_run else HostInstallMode.APPLY))
        print(encode(report), end='')
        return 0
    except Rejected as rejected:
        print(encode(HostRejection(HostRejectionType.REJECTED, rejected.failure)), end='', file=sys.stdout if arguments.remove else sys.stderr)
        return 1
    except (OSError, ValueError, zipfile.BadZipFile, XML.ParseError):
        print(encode(HostRejection(HostRejectionType.REJECTED, Failure.FILESYSTEM_REJECTED)), end='', file=sys.stdout if arguments.remove else sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
