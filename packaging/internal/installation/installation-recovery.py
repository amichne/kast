"""Ownership proofs for the product replacement journal: exact trees, moves, and fences.

The product installer loads this module to capture and move Control payloads by exact
inode-level witnesses. It never executes an installed payload or signals a process.
"""
from dataclasses import asdict, dataclass, fields
from contextlib import contextmanager
from enum import Enum
from pathlib import Path
import types
import ctypes
import hashlib
import fcntl
import json
import importlib.util
import os
import shutil
import stat
import sys
import typing
import uuid

sys.dont_write_bytecode = True

from managed_update_journal import (
    Failure,
    Rejected,
    Identity,
    NoPrevious,
    PhysicalPrevious,
    ManagedFileAbsent,
    ManagedFilePresent,
    ManagedHostAbsent,
    ManagedHostPresent,
    ManagedHostReplacement,
    ManagedHostExistingLock,
    ManagedHostStagedLock,
    ManagedProfileMissing,
    ManagedProfileCreated,
    ManagedTrackStable,
    ManagedTrackMain,
    ManagedPinnedBuild,
    ManagedComposition,
    Replacement,
    ManagedTreeWitness,
    ManagedShutdownFence,
)


class Status(str, Enum):
    PREPARED = 'Prepared'
    PLUGIN_PREPARED = 'PluginPrepared'
    PLANNED = 'Planned'
    ACTIVE = 'Active'
    FINALIZING = 'UpgradeFinalizing'
    SEALED = 'UpgradeFinalized'
    CLEAN = 'CleanBaselineRestored'
    UNRESOLVED = 'DetachedWithUnresolvedState'
    BLOCKED = 'RecoveryBlocked'


@dataclass(frozen=True)
class Link:
    path: str
    target: str
    priorTarget: typing.Optional[str]


@dataclass(frozen=True)
class Plugin:
    destination: str
    candidate: str
    candidateIdentity: Identity
    backup: str
    priorIdentity: typing.Optional[Identity]
    quarantine: str


@dataclass(frozen=True)
class LegacyReceipt:
    schemaVersion: int
    installation: str
    installationIdentity: Identity
    links: list[Link]
    priorInstallation: typing.Optional[str]
    plugin: typing.Optional[Plugin]
    pluginRoot: typing.Optional[str]
    stage: Status


@dataclass(frozen=True)
class Receipt:
    schemaVersion: int
    installation: str
    installationIdentity: Identity
    plugin: typing.Optional[Plugin]
    pluginRoot: typing.Optional[str]
    stage: Status


def stream_digest(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(65536), b''):
        digest.update(block)
    return digest.hexdigest()


def encode(document):
    return json.dumps(asdict(document), separators=(',', ':'))


def decode(kind, value):
    """Strict dataclass boundary: unknown fields and absent fields are rejected."""
    origin = typing.get_origin(kind)
    if origin is typing.Literal:
        allowed = typing.get_args(kind)
        if len(allowed) != 1 or type(value) is not type(allowed[0]) or value != allowed[0]:
            raise Rejected(Failure.RECEIPT)
        return value
    if kind is Replacement and isinstance(value, dict):
        value = {'reservation': None, 'composition': None, 'product': None} | value
    if kind is ManagedHostReplacement and isinstance(value, dict):
        value = {'lock': None} | value
    if kind is ManagedComposition and isinstance(value, dict):
        value = {'profileDirectories': [], 'binding': None, 'notices': [], 'priorRuntimeJson': None} | value
    if origin is typing.Union or (origin is not None and origin is getattr(types, "UnionType", None)):
        if kind == typing.Union[ManagedHostExistingLock, ManagedHostStagedLock, None]:
            if value is None:
                return None
            if not isinstance(value, dict) or value.get('type') not in {'EXISTING', 'STAGED'}:
                raise Rejected(Failure.RECEIPT)
            return decode(ManagedHostExistingLock if value['type'] == 'EXISTING' else ManagedHostStagedLock, value)
        if kind == typing.Union[ManagedTrackStable, ManagedTrackMain, ManagedPinnedBuild]:
            variants = {'TrackStable': ManagedTrackStable, 'TrackMain': ManagedTrackMain, 'PinnedBuild': ManagedPinnedBuild}
            if not isinstance(value, dict) or value.get('type') not in variants:
                raise Rejected(Failure.RECEIPT)
            return decode(variants[value['type']], value)
        if kind == typing.Union[ManagedProfileMissing, ManagedProfileCreated]:
            if not isinstance(value, dict) or value.get('type') not in {'MISSING', 'CREATED'}:
                raise Rejected(Failure.RECEIPT)
            return decode(ManagedProfileMissing if value['type'] == 'MISSING' else ManagedProfileCreated, value)
        if kind in (typing.Union[ManagedFileAbsent, ManagedFilePresent], typing.Union[ManagedHostAbsent, ManagedHostPresent]):
            if not isinstance(value, dict) or value.get('type') not in {'ABSENT', 'PRESENT'}:
                raise Rejected(Failure.RECEIPT)
            variants = (ManagedFileAbsent, ManagedFilePresent) if kind == typing.Union[ManagedFileAbsent, ManagedFilePresent] else (ManagedHostAbsent, ManagedHostPresent)
            return decode(variants[0] if value['type'] == 'ABSENT' else variants[1], value)
        if kind == typing.Union[NoPrevious, PhysicalPrevious]:
            if not isinstance(value, dict) or value.get('type') not in {'NONE', 'PHYSICAL'}:
                raise Rejected(Failure.RECEIPT)
            return decode({'NONE': NoPrevious, 'PHYSICAL': PhysicalPrevious}[value['type']], value)
        if value is None and type(None) in typing.get_args(kind):
            return None
        return decode(next(item for item in typing.get_args(kind) if item is not type(None)), value)
    if origin is list:
        if not isinstance(value, list) or len(value) > 16:
            raise Rejected(Failure.RECEIPT)
        return [decode(typing.get_args(kind)[0], item) for item in value]
    if kind in (str, int):
        if type(value) is not kind or (kind is str and len(value) > 4096):
            raise Rejected(Failure.RECEIPT)
        return value
    if issubclass(kind, Enum):
        try:
            return kind(value)
        except ValueError:
            raise Rejected(Failure.RECEIPT) from None
    if not isinstance(value, dict) or set(value) != {field.name for field in fields(kind)}:
        raise Rejected(Failure.RECEIPT)
    if kind is ManagedComposition:
        raw_runtime = value['priorRuntimeJson']
        if raw_runtime is not None and (not isinstance(raw_runtime, str) or len(raw_runtime.encode('utf-8')) > 262144):
            raise Rejected(Failure.RECEIPT)
    return kind(**{field.name: value[field.name] if kind is ManagedComposition and field.name == 'priorRuntimeJson' else decode(field.type, value[field.name]) for field in fields(kind)})


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Rejected(Failure.RECEIPT)
        result[key] = value
    return result


def physical(path):
    if not path.is_absolute() or path.resolve(strict=True) != path or not path.is_dir():
        raise Rejected(Failure.OWNERSHIP)
    Identity.observe(path)
    if path.stat().st_mode & 0o022:
        raise Rejected(Failure.OWNERSHIP)
    return path


def sync(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def save(path, document):
    temporary = path.with_name('.receipt-' + uuid.uuid4().hex)
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, 'w') as output:
            output.write(encode(document))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        sync(path.parent)
    finally:
        if temporary.exists():
            temporary.unlink()


def load(path, kind=Receipt):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as source:
        observed = os.fstat(source.fileno())
        if not stat.S_ISREG(observed.st_mode) or observed.st_uid != os.getuid() or observed.st_mode & 0o077:
            raise Rejected(Failure.RECEIPT)
        limit = 2_097_152 if kind is Replacement else 65_536
        raw = source.read(limit + 1)
    if len(raw) > limit:
        raise Rejected(Failure.RECEIPT)
    try:
        document = json.loads(raw, object_pairs_hook=unique)
        selected = LegacyReceipt if kind is Receipt and isinstance(document, dict) and document.get('schemaVersion') in (1, 2) else kind
        return decode(selected, document)
    except (ValueError, UnicodeDecodeError):
        raise Rejected(Failure.RECEIPT) from None


LIVE_COORDINATOR_SOCKET = 'state/run/c.sock'


def managed_tree(path, live_coordinator=False):
    """Capture the complete owned tree before a Control move or terminal cleanup.

    Before the prior Control retires its semantic coordinator, that coordinator's exact
    socket is admitted. A socket anywhere else, or after retirement, rejects ownership.
    """
    physical(path)
    digest = hashlib.sha256()
    pending, entries = [path], []
    while pending:
        current = pending.pop()
        if len(entries) >= 100000:
            raise Rejected(Failure.OWNERSHIP)
        attributes = current.lstat()
        relative = current.relative_to(path).as_posix()
        coordinator = live_coordinator and relative == LIVE_COORDINATOR_SOCKET and stat.S_ISSOCK(attributes.st_mode)
        if attributes.st_uid != os.getuid() or not (stat.S_ISDIR(attributes.st_mode) or stat.S_ISREG(attributes.st_mode) or coordinator):
            raise Rejected(Failure.OWNERSHIP)
        identity = Identity(attributes.st_dev, attributes.st_ino, attributes.st_uid)
        content = 'socket' if coordinator else ''
        if stat.S_ISREG(attributes.st_mode):
            descriptor = os.open(current, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, 'rb') as stream:
                if Identity.observe(current) != identity:
                    raise Rejected(Failure.OWNERSHIP)
                content = stream_digest(stream)
            if Identity.observe(current) != identity:
                raise Rejected(Failure.OWNERSHIP)
        elif stat.S_ISDIR(attributes.st_mode):
            with os.scandir(current) as children:
                for child in children:
                    if len(entries) + len(pending) + 1 >= 100000:
                        raise Rejected(Failure.OWNERSHIP)
                    pending.append(Path(child.path))
        entries.append((relative, identity, stat.S_IMODE(attributes.st_mode), content))
    for relative, identity, mode, content in sorted(entries):
        digest.update(relative.encode())
        digest.update(b'\0')
        digest.update(f'{identity.device}:{identity.inode}:{identity.owner}:{mode}:{content}\n'.encode())
    return ManagedTreeWitness(Identity.observe(path), digest.hexdigest())


def managed_move(source, destination, witness):
    """No-replace rename preserves foreign concurrent destinations, including empty directories."""
    if managed_tree(source) != witness or os.path.lexists(destination):
        raise Rejected(Failure.OWNERSHIP)
    physical(destination.parent)
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform == 'darwin':
        rename = library.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = rename(os.fsencode(source), os.fsencode(destination), 4)
    elif sys.platform.startswith('linux') and hasattr(library, 'renameat2'):
        rename = library.renameat2
        rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        result = rename(-100, os.fsencode(source), -100, os.fsencode(destination), 1)
    else:
        raise Rejected(Failure.FILESYSTEM)
    if result != 0:
        raise OSError(ctypes.get_errno(), 'Control directory move rejected')
    sync(source.parent)
    if destination.parent != source.parent:
        sync(destination.parent)
    if managed_tree(destination) != witness:
        raise Rejected(Failure.OWNERSHIP)


@contextmanager
def managed_control_lock(outer):
    """The caller holds management.lock. This lock also excludes the private JVM installer."""
    physical(outer)
    descriptor = os.open(outer / 'activation.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        attributes = os.fstat(descriptor)
        if not stat.S_ISREG(attributes.st_mode) or attributes.st_uid != os.getuid() or attributes.st_mode & 0o077:
            raise Rejected(Failure.OWNERSHIP)
        fcntl.lockf(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)


def managed_payload(path, expected_root, identity, reservation=None, live_coordinator=False):
    if Identity.observe(physical(path)) != identity:
        raise Rejected(Failure.OWNERSHIP)
    manifest_path = path / 'installation.json'
    descriptor = os.open(manifest_path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        attributes = os.fstat(stream.fileno())
        if not stat.S_ISREG(attributes.st_mode) or attributes.st_uid != os.getuid():
            raise Rejected(Failure.OWNERSHIP)
        raw = stream.read(67108865)
    if len(raw) > 67108864:
        raise Rejected(Failure.RECEIPT)
    try:
        document = json.loads(raw, object_pairs_hook=unique)
    except (ValueError, UnicodeError):
        raise Rejected(Failure.RECEIPT) from None
    if not isinstance(document, dict) or document.get('installationRoot') != str(expected_root) or document.get('schemaVersion') not in (1, 2, 3):
        raise Rejected(Failure.RECEIPT)
    if document['schemaVersion'] in (1, 2) and (expected_root.parent.name != 'versions' or expected_root.name != document.get('semanticVersion', '') + '-' + document.get('payloadIdentity', '')[7:]):
        raise Rejected(Failure.RECEIPT)
    if reservation is not None and (hashlib.sha256(raw).hexdigest() != reservation.manifestSha256 or document.get('semanticVersion') != reservation.version or document.get('controlSha256') != 'sha256:' + reservation.controlSha256):
        raise Rejected(Failure.RECEIPT)
    # Reuse the existing complete payload-inventory owner for a temporarily detached root.
    specification = importlib.util.spec_from_file_location('kast_managed_restore_lifecycle', Path(__file__).with_name('installation-lifecycle.py'))
    if specification is None or specification.loader is None:
        raise Rejected(Failure.RECEIPT)
    lifecycle = importlib.util.module_from_spec(specification)
    sys.modules[specification.name] = lifecycle
    specification.loader.exec_module(lifecycle)
    try:
        lifecycle.verify_payload(path, document)
    except lifecycle.Rejected as problem:
        lifecycle.observe_admission(lifecycle.LifecycleRejection(problem.failure, problem.limit, problem.retirement))
        if problem.failure is lifecycle.Failure.CONTROL_LIMIT_EXCEEDED:
            raise Rejected(Failure.CONTROL_LIMIT) from None
        raise Rejected(Failure.OWNERSHIP) from None
    lifecycle.observe_admission(lifecycle.AdmissionAccepted(lifecycle.AdmissionType.PAYLOAD_ADMITTED))
    return managed_tree(path, live_coordinator)


def managed_previous(root, transaction, previous, live_coordinator=False):
    if isinstance(previous, PhysicalPrevious):
        if previous.installation != str(root) or previous.payload != str(transaction / 'payload') or previous.recovery != str(transaction / 'recovery'):
            raise Rejected(Failure.RECEIPT)
        options = [path for path in (root, transaction / 'payload') if os.path.lexists(path) and not path.is_symlink() and Identity.observe(path) == previous.identity]
        if len(options) != 1:
            raise Rejected(Failure.OWNERSHIP)
        return options[0], root, root.parent / 'recovery/installation', managed_payload(options[0], root, previous.identity, live_coordinator=live_coordinator)
    if isinstance(previous, NoPrevious) and previous.type == 'NONE':
        if os.path.lexists(transaction / 'payload') or os.path.lexists(transaction / 'recovery'):
            raise Rejected(Failure.OWNERSHIP)
        return None
    raise Rejected(Failure.RECEIPT)


def managed_shutdown_fence(root):
    path = root.parent / 'shutdown.json'
    if not os.path.lexists(path):
        return None
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        attributes = os.fstat(stream.fileno())
        identity = Identity(attributes.st_dev, attributes.st_ino, attributes.st_uid)
        if not stat.S_ISREG(attributes.st_mode) or attributes.st_uid != os.getuid() or attributes.st_mode & 0o022:
            raise Rejected(Failure.OWNERSHIP)
        raw = stream.read(4097)
    if len(raw) > 4096 or Identity.observe(path) != identity:
        raise Rejected(Failure.RECEIPT)
    try:
        fence = decode(ManagedShutdownFence, json.loads(raw, object_pairs_hook=unique))
    except (ValueError, UnicodeError):
        raise Rejected(Failure.RECEIPT) from None
    if fence != ManagedShutdownFence(str(root), 1):
        raise Rejected(Failure.RECEIPT)
    return identity


@dataclass(frozen=True)
class ManagedStorageCleanupObservation:
    reason: Failure
    event: typing.Literal['kast_managed_storage_cleanup'] = 'kast_managed_storage_cleanup'
    outcome: typing.Literal['FAILED'] = 'FAILED'


def cleanup_retired_storage(completed: Path, witness: ManagedTreeWitness) -> None:
    try:
        if not shutil.rmtree.avoids_symlink_attacks or managed_tree(completed) != witness:
            raise Rejected(Failure.OWNERSHIP)
        shutil.rmtree(completed)
        sync(completed.parent)
    except (OSError, Rejected) as problem:
        reason = problem.failure if isinstance(problem, Rejected) else Failure.FILESYSTEM
        print(json.dumps(asdict(ManagedStorageCleanupObservation(reason))), file=sys.stderr)


def close_managed_journal(transaction: Path, receipt: Replacement, transaction_id: uuid.UUID) -> None:
    """Publish terminal completion before deleting its sole durable receipt."""
    if receipt.reservation is None or str(transaction_id) != receipt.reservation.transaction:
        raise Rejected(Failure.RECEIPT)
    if load(transaction / 'receipt.json', Replacement) != receipt:
        raise Rejected(Failure.RECEIPT)
    completed = transaction.with_name('completed-replacement-' + str(transaction_id))
    witness = managed_tree(transaction)
    if load(transaction / 'receipt.json', Replacement) != receipt:
        raise Rejected(Failure.RECEIPT)
    managed_move(transaction, completed, witness)
    # Closed storage does not block admission of the next transaction.
    cleanup_retired_storage(completed, witness)
