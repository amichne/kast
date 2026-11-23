"""Installed payload and uninstall-journal admission. Never searches or signals arbitrary processes."""
from dataclasses import asdict, dataclass, field, replace
from enum import Enum
from typing import TYPE_CHECKING, Optional, Union
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import uuid

if TYPE_CHECKING:
    from managed_product import ProductReference

# Installed inventory includes the separately admitted pinned interpreter.
# The original Control archive retains its independent 16,384-member bound.
CONTROL_MAXIMUM_ENTRIES = 32768
CONTROL_MANIFEST_MAXIMUM_BYTES = 67108864
UNINSTALL_JOURNAL_MAXIMUM_BYTES = 1048576

class Failure(str, Enum):
    MANIFEST_REJECTED = 'MANIFEST_REJECTED'
    CONTROL_LIMIT_EXCEEDED = 'CONTROL_LIMIT_EXCEEDED'
    FILESYSTEM_REJECTED = 'FILESYSTEM_REJECTED'
    RECOVERY_REJECTED = 'RECOVERY_REJECTED'

class Rejected(Exception):
    def __init__(self, failure, limit=None):
        self.failure = failure
        self.limit = limit

class ControlResource(str, Enum):
    TRAVERSED_ENTRIES = 'TRAVERSED_ENTRIES'
    PAYLOAD_FILES = 'PAYLOAD_FILES'
    PAYLOAD_BYTES = 'PAYLOAD_BYTES'

@dataclass(frozen=True)
class ControlLimit:
    resource: ControlResource
    maximum: int
    observedAtLeast: int

@dataclass(frozen=True)
class LifecycleRejection:
    failure: Failure
    limit: Optional[ControlLimit] = None
    status: str = 'rejected'

class AdmissionType(str, Enum):
    PAYLOAD_ADMITTED = 'PAYLOAD_ADMITTED'

@dataclass(frozen=True)
class AdmissionAccepted:
    type: AdmissionType

def observe_admission(observation):
    # Only a finite outcome is emitted; paths, configuration and source data stay private.
    print(json.dumps(asdict(observation), separators=(',', ':')), file=sys.stderr)

@dataclass(frozen=True)
class PayloadFile:
    path: str
    sha256: str
    mode: int

@dataclass(frozen=True)
class FileIdentity:
    device: int
    inode: int
    owner: int
    @staticmethod
    def observe(path):
        observed = path.lstat()
        return FileIdentity(observed.st_dev, observed.st_ino, observed.st_uid)

class UninstallPhase(str, Enum):
    REMOVAL_REQUESTED = 'REMOVAL_REQUESTED'
    CONTROL_RETIRED = 'CONTROL_RETIRED'
    EXTERNALS_CLEANED = 'EXTERNALS_CLEANED'

@dataclass(frozen=True)
class ProductRemovalRoot:
    root: Path
    identity: FileIdentity

    @property
    def managed_root(self):
        return self.root.parent

@dataclass(frozen=True)
class AbsentRecovery:
    type: str = field(default='ABSENT_RECOVERY', init=False)

@dataclass(frozen=True)
class EmptyRecovery:
    installation: str
    recoveryDirectoryIdentity: FileIdentity
    type: str = field(default='EMPTY_RECOVERY', init=False)

@dataclass(frozen=True)
class ProductUninstallRetirementJournal:
    installationRoot: str
    executable: str
    executableSha256: str
    channel: str
    type: UninstallPhase
    managedRootIdentity: FileIdentity
    controlIdentity: FileIdentity
    recoveryProof: Union[AbsentRecovery, EmptyRecovery]
    product: 'ProductReference'

@dataclass(frozen=True)
class WorkspaceRemoval:
    id: str
    identity: FileIdentity
    recordSha256: str

@dataclass(frozen=True)
class ProductWorkspaceUninstallRetirementJournal(ProductUninstallRetirementJournal):
    workspaceRemovals: list[WorkspaceRemoval]

@dataclass(frozen=True)
class RetainedProductUninstallJournal(ProductUninstallRetirementJournal):
    productRemoval: 'ProductRemoval'

@dataclass(frozen=True)
class RetainedWorkspaceProductUninstallJournal(ProductWorkspaceUninstallRetirementJournal):
    productRemoval: 'ProductRemoval'

def exact_fields(document, fields):
    if not isinstance(document, dict) or set(document) != set(fields):
        raise Rejected(Failure.RECOVERY_REJECTED)

def journal_identity(document):
    exact_fields(document, ('device', 'inode', 'owner'))
    if any(type(value) is not int or not 0 <= value <= 9223372036854775807 for value in document.values()) or document['owner'] != os.getuid():
        raise Rejected(Failure.RECOVERY_REJECTED)
    return FileIdentity(**document)

def workspace_removals(document):
    if not isinstance(document, list) or len(document) > 1024:
        raise Rejected(Failure.RECOVERY_REJECTED)
    result = []
    seen = set()
    for entry in document:
        exact_fields(entry, ('id', 'identity', 'recordSha256'))
        if (not isinstance(entry['id'], str) or not 0 < len(entry['id']) <= 4096
                or entry['id'] in seen or not isinstance(entry['recordSha256'], str)
                or re.fullmatch('[0-9a-f]{64}', entry['recordSha256']) is None):
            raise Rejected(Failure.RECOVERY_REJECTED)
        seen.add(entry['id'])
        result.append(WorkspaceRemoval(entry['id'], journal_identity(entry['identity']), entry['recordSha256']))
    return result

def decode_recovery_proof(document, installation):
    if document == {'type': 'ABSENT_RECOVERY'}:
        return AbsentRecovery()
    exact_fields(document, ('type', 'installation', 'recoveryDirectoryIdentity'))
    if document['type'] != 'EMPTY_RECOVERY' or document['installation'] != str(installation.root):
        raise Rejected(Failure.RECOVERY_REJECTED)
    return EmptyRecovery(document['installation'], journal_identity(document['recoveryDirectoryIdentity']))

def admit_uninstall_journal(path, installation):
    expected = installation.managed_root.parent / ('.kast-uninstall-' +
        hashlib.sha256(str(installation.managed_root).encode()).hexdigest() + '.json')
    if path != expected or path.resolve(strict=True) != path:
        raise Rejected(Failure.RECOVERY_REJECTED)
    attributes = path.lstat()
    if not stat.S_ISREG(attributes.st_mode) or attributes.st_uid != os.getuid() or stat.S_IMODE(attributes.st_mode) != 0o600:
        raise Rejected(Failure.RECOVERY_REJECTED)
    document = read_json(path, UNINSTALL_JOURNAL_MAXIMUM_BYTES, Failure.RECOVERY_REJECTED)
    expected_fields = ('installationRoot', 'executable', 'executableSha256', 'channel', 'type',
                       'managedRootIdentity', 'controlIdentity', 'recoveryProof', 'product')
    optional_fields = tuple(name for name in ('workspaceRemovals', 'productRemoval') if name in document)
    exact_fields(document, expected_fields + optional_fields)
    executable = Path(document['executable'])
    if (document['installationRoot'] != str(installation.managed_root) or not executable.is_absolute()
            or executable.resolve() != executable or document['channel'] not in {'STABLE', 'DEVELOPER'}
            or not isinstance(document['executableSha256'], str)
            or re.fullmatch('[0-9a-f]{64}', document['executableSha256']) is None
            or document['type'] not in {'REMOVAL_REQUESTED', 'CONTROL_RETIRED'}):
        raise Rejected(Failure.RECOVERY_REJECTED)
    from managed_installation import RECOVERY
    from managed_product import ProductReference
    journal = ProductUninstallRetirementJournal(document['installationRoot'], document['executable'],
        document['executableSha256'], document['channel'], UninstallPhase(document['type']),
        journal_identity(document['managedRootIdentity']), journal_identity(document['controlIdentity']),
        decode_recovery_proof(document['recoveryProof'], installation),
        RECOVERY.decode(ProductReference, document['product']))
    if 'workspaceRemovals' in document:
        journal = ProductWorkspaceUninstallRetirementJournal(
            **vars(journal), workspaceRemovals=workspace_removals(document['workspaceRemovals']))
    if 'productRemoval' in document:
        from immutable_store import removal
        kind = (RetainedWorkspaceProductUninstallJournal if 'workspaceRemovals' in document
                else RetainedProductUninstallJournal)
        journal = kind(**vars(journal), productRemoval=removal(document['productRemoval']))
    if (journal.managedRootIdentity != FileIdentity.observe(installation.managed_root)
            or journal.controlIdentity != installation.identity):
        raise Rejected(Failure.RECOVERY_REJECTED)
    return journal, FileIdentity.observe(path)

def commit_control_retirement(path, installation, admitted, *, next_journal=None):
    journal, identity = admitted
    current, current_identity = admit_uninstall_journal(path, installation)
    if current != journal or current_identity != identity:
        raise Rejected(Failure.RECOVERY_REJECTED)
    successor = next_journal if next_journal is not None else replace(journal, type=UninstallPhase.CONTROL_RETIRED)
    raw = json.dumps(asdict(successor), separators=(',', ':'))
    if len(raw.encode()) > UNINSTALL_JOURNAL_MAXIMUM_BYTES:
        raise Rejected(Failure.RECOVERY_REJECTED)
    temporary = path.parent / ('.kast-uninstall-stage-' + uuid.uuid4().hex)
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if temporary.exists():
            temporary.unlink()

@dataclass(frozen=True)
class Installation:
    root: Path
    manifest: dict
    identity: FileIdentity
    @staticmethod
    def admit(raw, *, retired_product=None):
        root = Path(raw)
        if not root.is_absolute() or root.resolve(strict=True) != root :
            raise Rejected(Failure.MANIFEST_REJECTED)
        document = read_json(root / 'installation.json', CONTROL_MANIFEST_MAXIMUM_BYTES, Failure.MANIFEST_REJECTED)
        payload = document.get('payloadIdentity', '')
        if (document.get('schemaVersion') != 3 or document.get('installationRoot') != str(root)
                or len(payload) != 71 or not payload.startswith('sha256:')
                or any(c not in '0123456789abcdef' for c in payload[7:])
                or root.name != 'installation'
                or any(document.get(key) != str(root / suffix) for key, suffix in (
                    ('stateRoot', 'state'), ('configuration', 'config/environment'),
                    ('workspaceRegistry', 'config/workspaces.json')))):
            raise Rejected(Failure.MANIFEST_REJECTED)
        if not isinstance(document.get('externalAnchors'), list) or len(document['externalAnchors']) > 16:
            raise Rejected(Failure.MANIFEST_REJECTED)
        if any(not isinstance(anchor, dict) or anchor.get('kind') not in {'login', 'socket-alias', 'upstream-directory'}
                for anchor in document['externalAnchors']):
            raise Rejected(Failure.MANIFEST_REJECTED)
        verify_payload(root, document, retired_product=retired_product)
        return Installation(root, document, FileIdentity.observe(root))
    @property
    def managed_root(self):
        return self.root.parent

    def revalidate(self):
        if self.root.resolve(strict=True) != self.root or FileIdentity.observe(self.root) != self.identity:
            raise Rejected(Failure.MANIFEST_REJECTED)


def verify_payload(root, manifest, *, retired_product=None):
    if retired_product is not None and (
            not isinstance(retired_product, ProductUninstallRetirementJournal)
            or retired_product.type is not UninstallPhase.CONTROL_RETIRED
            or root != Path(retired_product.installationRoot) / 'installation'
            or FileIdentity.observe(root) != retired_product.controlIdentity
            or FileIdentity.observe(root.parent) != retired_product.managedRootIdentity):
        raise Rejected(Failure.RECOVERY_REJECTED)
    expected = manifest.get('payloadFiles')
    if not isinstance(expected, list) or not expected:
        raise Rejected(Failure.MANIFEST_REJECTED)
    if len(expected) > CONTROL_MAXIMUM_ENTRIES:
        raise Rejected(Failure.CONTROL_LIMIT_EXCEEDED, ControlLimit(ControlResource.PAYLOAD_FILES, CONTROL_MAXIMUM_ENTRIES, len(expected)))
    if any(not isinstance(item, dict) or set(item) != {'path', 'sha256', 'mode'} for item in expected):
        raise Rejected(Failure.MANIFEST_REJECTED)
    expected = [PayloadFile(**item) for item in expected]
    if any(not isinstance(item.path, str) or not item.path.startswith(('bin/', 'lib/', 'share/'))
            or '..' in Path(item.path).parts or Path(item.path).as_posix() != item.path
            or not isinstance(item.sha256, str) or re.fullmatch('sha256:[0-9a-f]{64}', item.sha256) is None
            or type(item.mode) is not int or not 0 <= item.mode <= 0o777 for item in expected):
        raise Rejected(Failure.MANIFEST_REJECTED)
    expected_by_path = {item.path: item for item in expected}
    if len(expected_by_path) != len(expected):
        raise Rejected(Failure.MANIFEST_REJECTED)
    actual = []
    total = 0
    traversed = 0
    for directory in ('bin', 'lib', 'share'):
        parent = root / directory
        if parent.is_symlink():
            raise Rejected(Failure.MANIFEST_REJECTED)
        if not parent.exists():
            continue
        pending = [parent]
        while pending:
            current = pending.pop()
            traversed += 1
            if traversed > CONTROL_MAXIMUM_ENTRIES:
                raise Rejected(Failure.CONTROL_LIMIT_EXCEEDED, ControlLimit(ControlResource.TRAVERSED_ENTRIES, CONTROL_MAXIMUM_ENTRIES, traversed))
            with os.scandir(current) as children:
                for child in children:
                    candidate = Path(child.path)
                    if child.is_symlink():
                        raise Rejected(Failure.MANIFEST_REJECTED)
                    if child.is_dir(follow_symlinks=False):
                        if traversed + len(pending) >= CONTROL_MAXIMUM_ENTRIES:
                            raise Rejected(Failure.CONTROL_LIMIT_EXCEEDED, ControlLimit(ControlResource.TRAVERSED_ENTRIES, CONTROL_MAXIMUM_ENTRIES, traversed + len(pending) + 1))
                        pending.append(candidate)
                        continue
                    traversed += 1
                    if traversed + len(pending) > CONTROL_MAXIMUM_ENTRIES:
                        raise Rejected(Failure.CONTROL_LIMIT_EXCEEDED, ControlLimit(ControlResource.TRAVERSED_ENTRIES, CONTROL_MAXIMUM_ENTRIES, traversed + len(pending)))
                    descriptor = os.open(candidate, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                    digest = hashlib.sha256()
                    with os.fdopen(descriptor, 'rb') as source:
                        observed = os.fstat(source.fileno())
                        if not stat.S_ISREG(observed.st_mode):
                            raise Rejected(Failure.MANIFEST_REJECTED)
                        for chunk in iter(lambda: source.read(65536), b''):
                            total += len(chunk)
                            if total > 1073741824:
                                raise Rejected(Failure.CONTROL_LIMIT_EXCEEDED, ControlLimit(ControlResource.PAYLOAD_BYTES, 1073741824, total))
                            digest.update(chunk)
                    actual.append(PayloadFile(candidate.relative_to(root).as_posix(),
                                              'sha256:' + digest.hexdigest(), observed.st_mode & 0o777))
                    if len(actual) > CONTROL_MAXIMUM_ENTRIES:
                        raise Rejected(Failure.MANIFEST_REJECTED)
    if (any(expected_by_path.get(item.path) != item for item in actual)
            or (retired_product is None and len(actual) != len(expected))):
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


def delete_tree(path):
    # CPython's fd-based implementation resists symlink replacement during recursive deletion.
    if not shutil.rmtree.avoids_symlink_attacks:
        raise Rejected(Failure.FILESYSTEM_REJECTED)
    shutil.rmtree(path)
