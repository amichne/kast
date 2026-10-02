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
    temporary = path.with_name(path.name + '.new')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(encode(receipt))
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)
    sync(path.parent)


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
    if not root.is_absolute() or root != Path(os.path.normpath(root)):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    with tempfile.TemporaryDirectory(prefix='kast-host-stage-') as temporary:
        plugin = validate_payload(request.payload, Path(temporary))
        if request.mode is HostInstallMode.PLAN:
            return HostReport(HostReportType.PLANNED, request.payload.version, str(root / 'kast-ide-hosted'))
        root.mkdir(parents=True, exist_ok=True)
        Identity.observe(root)
        retained = root.parent / '.kast-plugin-recovery'
        retained.mkdir(mode=0o700, exist_ok=True)
        Identity.observe(retained)
        lock_descriptor = os.open(retained / 'host-install.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        with os.fdopen(lock_descriptor, 'r+') as lock:
            if os.fstat(lock.fileno()).st_uid != os.getuid():
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            receipt_path = retained / 'host-install.json'
            if os.path.lexists(receipt_path):
                raise Rejected(Failure.RECOVERY_REQUIRED)
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
                    receipt_path.unlink()
                except (OSError, Rejected):
                    raise Rejected(Failure.RECOVERY_REQUIRED) from None
                raise Rejected(Failure.FILESYSTEM_REJECTED) from None
            if prior is not None:
                if Identity.observe(backup) != prior:
                    raise Rejected(Failure.RECOVERY_REQUIRED)
                shutil.rmtree(backup)
            receipt_path.unlink()
            sync(retained)
            return HostReport(HostReportType.ACTIVATED, request.payload.version, str(destination))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--idea-build', required=True)
    parser.add_argument('--plugin-root', required=True, type=Path)
    parser.add_argument('--release-record', required=True, type=Path)
    parser.add_argument('--required-control', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    arguments = parser.parse_args()
    try:
        payload = HostedPluginPayload(arguments.archive, arguments.sha256, arguments.version, arguments.idea_build)
        admit_host_release(arguments.release_record, payload, arguments.required_control)
        report = install(HostInstallRequest(payload, arguments.plugin_root, HostInstallMode.PLAN if arguments.dry_run else HostInstallMode.APPLY))
        print(encode(report), end='')
        return 0
    except Rejected as rejected:
        print(encode(HostRejection(HostRejectionType.REJECTED, rejected.failure)), end='', file=sys.stderr)
        return 1
    except (OSError, ValueError, zipfile.BadZipFile, XML.ParseError):
        print(encode(HostRejection(HostRejectionType.REJECTED, Failure.FILESYSTEM_REJECTED)), end='', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
