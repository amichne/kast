"""Install only the IntelliJ-owned Kast Host plugin through its ordinary restart lifecycle."""
from __future__ import annotations

from dataclasses import asdict, dataclass, field, replace
from contextlib import contextmanager
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
import time
import uuid
from io import TextIOBase
from typing import Literal
import xml.etree.ElementTree as XML
import zipfile

sys.dont_write_bytecode = True


class Failure(str, Enum):
    PAYLOAD_REJECTED = 'PAYLOAD_REJECTED'
    RELEASE_REJECTED = 'RELEASE_REJECTED'
    COMPATIBILITY_REJECTED = 'COMPATIBILITY_REJECTED'
    OWNERSHIP_UNPROVEN = 'OWNERSHIP_UNPROVEN'
    FILESYSTEM_REJECTED = 'FILESYSTEM_REJECTED'
    RECOVERY_REQUIRED = 'RECOVERY_REQUIRED'
    RELOAD_SIGNAL_REJECTED = 'RELOAD_SIGNAL_REJECTED'


class HostInstallationStage(str, Enum):
    INSTALLATION = 'INSTALLATION'
    ACTIVE_ROOT = 'ACTIVE_ROOT'
    ACTIVE_DESTINATION = 'ACTIVE_DESTINATION'
    ACTIVE_INVENTORY = 'ACTIVE_INVENTORY'
    RETAINED_RECOVERY = 'RETAINED_RECOVERY'
    RELOAD_SIGNAL_ADMISSION = 'RELOAD_SIGNAL_ADMISSION'
    RELOAD_SIGNAL_WRITE = 'RELOAD_SIGNAL_WRITE'
    RELOAD_SIGNAL_PROOF = 'RELOAD_SIGNAL_PROOF'


class Rejected(Exception):
    def __init__(self, failure: Failure, stage: HostInstallationStage = HostInstallationStage.INSTALLATION):
        self.failure = failure
        self.stage = stage


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
    RESET = 'RESET'


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
class ManagedHostAbsent:
    type: str = 'ABSENT'


@dataclass(frozen=True)
class ManagedHostPresent:
    identity: Identity
    inventorySha256: str
    receipt: str
    receiptIdentity: Identity
    receiptSha256: str
    type: str = 'PRESENT'


@dataclass(frozen=True)
class ManagedHostExistingLock:
    identity: Identity
    type: str = 'EXISTING'


@dataclass(frozen=True)
class ManagedHostStagedLock:
    identity: Identity
    staged: str
    retainedIdentity: Identity | None = None
    type: str = 'STAGED'


@dataclass(frozen=True)
class ManagedHostReplacement:
    pluginRoot: str
    pluginRootIdentity: Identity | None
    retainedRootIdentity: Identity | None
    destination: str
    candidate: str
    candidateIdentity: Identity
    candidateInventorySha256: str
    backup: str
    previous: ManagedHostAbsent | ManagedHostPresent
    version: str
    sha256: str
    lock: ManagedHostExistingLock | ManagedHostStagedLock | None = None


class ManagedHostTerminalPhase(str, Enum):
    ACTIVATION_PROVEN = 'ACTIVATION_PROVEN'
    RESTORED = 'RESTORED'


class HostLeaseState(str, Enum):
    HELD = 'HELD'
    RELEASED = 'RELEASED'


@dataclass(frozen=True, init=False)
class HeldHostLease:
    record: ManagedHostReplacement
    _stream: TextIOBase
    _state: HostLeaseState = field(default=HostLeaseState.HELD, init=False, repr=False)

    def __init__(self, *_arguments, **_keywords):
        raise TypeError('a held Host lease comes only from managed_lock')


def _held_host_lease(record: ManagedHostReplacement, stream: TextIOBase) -> HeldHostLease:
    lease = object.__new__(HeldHostLease)
    object.__setattr__(lease, 'record', record)
    object.__setattr__(lease, '_stream', stream)
    object.__setattr__(lease, '_state', HostLeaseState.HELD)
    return lease


class ReloadSignalTarget(str, Enum):
    SELECTED = 'SELECTED'
    RESTORED_PRIOR = 'RESTORED_PRIOR'


class ReloadSignalFailure(str, Enum):
    LEASE_NOT_HELD = 'LEASE_NOT_HELD'
    OWNERSHIP_UNPROVEN = 'OWNERSHIP_UNPROVEN'
    MARKER_UNPROVEN = 'MARKER_UNPROVEN'
    STAMP_RANGE = 'STAMP_RANGE'
    STAMP_PRECISION = 'STAMP_PRECISION'
    FILE_CHANGED = 'FILE_CHANGED'
    IO_FAILURE = 'IO_FAILURE'


class ReloadSignalRejected(Rejected):
    def __init__(self, reason: ReloadSignalFailure, stage: HostInstallationStage):
        super().__init__(Failure.RELOAD_SIGNAL_REJECTED, stage)
        self.reason = reason


@dataclass(frozen=True)
class ReloadSignalEvidence:
    target: ReloadSignalTarget
    identity: Identity
    mode: int
    bytes: int
    sha256: str
    atimeNs: int
    beforeMtimeMillis: int
    retainedMtimeMillis: int
    observedMillis: int
    mtimeMillis: int
    marker: Literal['lib/kast-ide-hosted.jar'] = 'lib/kast-ide-hosted.jar'
    type: Literal['HOST_RELOAD_SIGNAL_STAMPED'] = 'HOST_RELOAD_SIGNAL_STAMPED'


@dataclass(frozen=True)
class ReloadSignalRejectionEvidence:
    stage: HostInstallationStage
    reason: ReloadSignalFailure
    type: Literal['HOST_RELOAD_SIGNAL_REJECTED'] = 'HOST_RELOAD_SIGNAL_REJECTED'


@contextmanager
def managed_lock(record: ManagedHostReplacement):
    admit_managed_paths(record)
    retained = Path(record.pluginRoot).parent / '.kast-plugin-recovery'
    admit_managed_retained(record, retained)
    path = retained / 'host-install.lock'
    proof = record.lock
    if proof is None:
        raise Rejected(Failure.RECOVERY_REQUIRED)
    if isinstance(proof, ManagedHostStagedLock) and not os.path.lexists(path):
        admit_staged_lock(record, Path(proof.staged))
        os.link(proof.staged, path)
        sync(retained)
    reservation = Path(record.candidate).parent / 'host-lock'
    with locked_managed_descriptor(record, path) as stream:
        if not os.path.lexists(reservation):
            admit_canonical_lock(record, path)
            if path.stat().st_nlink != 1 or path.stat().st_size != 0:
                raise Rejected(Failure.RECOVERY_REQUIRED)
            os.link(path, reservation)
            sync(reservation.parent)
        admit_managed_lock_reservation(record, path, reservation)
        lease = _held_host_lease(record, stream)
        try:
            yield lease
        finally:
            object.__setattr__(lease, '_state', HostLeaseState.RELEASED)


@contextmanager
def locked_managed_descriptor(record: ManagedHostReplacement, path: Path):
    admit_canonical_lock(record, path)
    descriptor = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'r+') as lock:
        value = os.fstat(lock.fileno())
        if Identity(value.st_dev, value.st_ino, value.st_uid) != record.lock.identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        admit_managed_retained(record, path.parent)
        admit_canonical_lock(record, path)
        yield lock


def bind_managed_lock(record: ManagedHostReplacement, retained_identity: Identity) -> ManagedHostReplacement:
    admit_managed_paths(record)
    retained = Path(record.pluginRoot).parent / '.kast-plugin-recovery'
    if Identity.observe(retained) != retained_identity or stat.S_IMODE(retained.lstat().st_mode) != 0o700:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if record.retainedRootIdentity is not None and retained_identity != record.retainedRootIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if isinstance(record.lock, ManagedHostExistingLock):
        if record.retainedRootIdentity is None:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        return record
    if not isinstance(record.lock, ManagedHostStagedLock):
        raise Rejected(Failure.RECOVERY_REQUIRED)
    if record.lock.retainedIdentity is not None and record.lock.retainedIdentity != retained_identity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    return replace(record, lock=replace(record.lock, retainedIdentity=retained_identity))


def finish_managed_lock(record: ManagedHostReplacement, terminal: ManagedHostTerminalPhase) -> None:
    admit_managed_paths(record)
    proof = record.lock
    if not isinstance(proof, (ManagedHostExistingLock, ManagedHostStagedLock)):
        raise Rejected(Failure.RECOVERY_REQUIRED)
    staged = Path(record.candidate).parent / 'host-lock'
    retained = Path(record.pluginRoot).parent / '.kast-plugin-recovery'
    canonical = retained / 'host-install.lock'
    if os.path.lexists(staged):
        admit_managed_lock_link(record, staged)
    if os.path.lexists(canonical):
        admit_managed_retained(record, retained)
        admit_canonical_lock(record, canonical)
        with locked_managed_descriptor(record, canonical):
            expected_links = 2 if os.path.lexists(staged) else 1
            if canonical.stat().st_nlink != expected_links:
                raise Rejected(Failure.RECOVERY_REQUIRED)
            seal_managed(record, terminal)
            if terminal is ManagedHostTerminalPhase.RESTORED and isinstance(proof, ManagedHostStagedLock):
                canonical.unlink()
            if os.path.lexists(staged):
                admit_managed_lock_link(record, staged)
                staged.unlink()
            sync(retained)
            sync(staged.parent)
            return
    elif isinstance(proof, ManagedHostExistingLock):
        raise Rejected(Failure.RECOVERY_REQUIRED)
    elif terminal is not ManagedHostTerminalPhase.RESTORED:
        raise Rejected(Failure.RECOVERY_REQUIRED)
    else:
        seal_managed(record, terminal)
    if os.path.lexists(staged):
        admit_managed_lock_link(record, staged)
        staged.unlink()
        sync(staged.parent)


def admit_managed_lock_link(record: ManagedHostReplacement, path: Path) -> None:
    if (record.lock is None or path != Path(record.candidate).parent / 'host-lock'
            or owned_file(path, private=True) != record.lock.identity or path.stat().st_size != 0):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def admit_managed_lock_reservation(record: ManagedHostReplacement, canonical: Path, reservation: Path) -> None:
    admit_managed_lock_link(record, reservation)
    admit_canonical_lock(record, canonical)
    if canonical.stat().st_nlink != 2 or canonical.stat().st_size != 0:
        raise Rejected(Failure.RECOVERY_REQUIRED)


def admit_unreserved_host_lock(lock) -> None:
    value = os.fstat(lock.fileno())
    if (value.st_uid != os.getuid() or not stat.S_ISREG(value.st_mode)
            or value.st_nlink != 1):
        raise Rejected(Failure.RECOVERY_REQUIRED)


def admit_staged_lock(record: ManagedHostReplacement, path: Path) -> None:
    proof = record.lock
    if (not isinstance(proof, ManagedHostStagedLock)
            or path != Path(record.candidate).parent / 'host-lock'
            or owned_file(path, private=True) != proof.identity or path.stat().st_size != 0):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def admit_canonical_lock(record: ManagedHostReplacement, path: Path) -> None:
    if record.lock is None or owned_file(path, private=True) != record.lock.identity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if isinstance(record.lock, ManagedHostStagedLock) and path.stat().st_size != 0:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def admit_managed_retained(record: ManagedHostReplacement, retained: Path) -> None:
    expected = record.retainedRootIdentity
    if expected is None and isinstance(record.lock, ManagedHostStagedLock):
        expected = record.lock.retainedIdentity
    if expected is None:
        if os.path.lexists(retained):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    elif Identity.observe(retained) != expected or stat.S_IMODE(retained.lstat().st_mode) != 0o700:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def inventory_digest(directory: Path) -> str:
    return hashlib.sha256(json.dumps([asdict(item) for item in inventory(directory)],
                                    sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def prepare_managed(request: HostInstallRequest, recovery: Path) -> ManagedHostReplacement:
    """Stage Host bytes under the existing replacement journal, without profile effects.

    The installation coordinator owns its stable lock and persists this value
    in that journal before commit_managed. This function creates no journal.
    """
    root = request.plugin_root
    admit_root(root)
    if recovery.name != 'replacement' or recovery.parent.name != 'recovery':
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    Identity.observe(recovery)
    if recovery.resolve() != recovery:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    candidate, backup = recovery / 'host-candidate', recovery / 'host-prior'
    preimage = recovery / 'host-receipt'
    lock_stage = recovery / 'host-lock'
    if any(os.path.lexists(path) for path in (candidate, backup, preimage, lock_stage)):
        raise Rejected(Failure.RECOVERY_REQUIRED)
    previous_receipt = admit_installation_target(root)
    destination = root / 'kast-ide-hosted'
    previous: ManagedHostAbsent | ManagedHostPresent = ManagedHostAbsent()
    if os.path.lexists(destination):
        if previous_receipt is None:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        active_path = root.parent / '.kast-plugin-recovery/host-install.json'
        active = admit_active(root, read_active_receipt(active_path))
        if inventory(destination) != active.inventory:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        previous_identity = Identity.observe(destination)
        previous_inventory = inventory_digest(destination)
    elif previous_receipt is not None:
        # A missing recorded Host is unresolved state, not admitted absence.
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    with tempfile.TemporaryDirectory(prefix='.host-preparation-', dir=recovery) as temporary:
        staged = validate_payload(request.payload, Path(temporary))
        staged_receipt = Path(temporary) / 'receipt'
        if previous_receipt is not None:
            with staged_receipt.open('xb') as saved:
                saved.write(previous_receipt)
                saved.flush()
                os.fsync(saved.fileno())
            staged_receipt.chmod(0o600)
            previous = ManagedHostPresent(previous_identity, previous_inventory, str(preimage),
                                          owned_file(staged_receipt, private=True),
                                          hashlib.sha256(previous_receipt).hexdigest())
        retained = root.parent / '.kast-plugin-recovery'
        canonical_lock = retained / 'host-install.lock'
        if os.path.lexists(canonical_lock):
            lock_proof = ManagedHostExistingLock(owned_file(canonical_lock, private=True))
            if canonical_lock.stat().st_nlink != 1 or canonical_lock.stat().st_size != 0:
                raise Rejected(Failure.RECOVERY_REQUIRED)
        else:
            staged_lock = Path(temporary) / 'lock'
            staged_lock.touch(mode=0o600, exist_ok=False)
            lock_proof = ManagedHostStagedLock(owned_file(staged_lock, private=True), str(lock_stage))
        record = ManagedHostReplacement(
            str(root), Identity.observe(root) if os.path.lexists(root) else None,
            Identity.observe(retained) if os.path.lexists(retained) else None,
            str(destination), str(candidate), Identity.observe(staged), inventory_digest(staged), str(backup),
            previous, request.payload.version, request.payload.sha256, lock_proof,
        )
        if any(os.path.lexists(path) for path in (candidate, backup, preimage, lock_stage)):
            raise Rejected(Failure.RECOVERY_REQUIRED)
        candidate_published = receipt_published = lock_published = False
        try:
            staged.rename(candidate)
            candidate_published = True
            if isinstance(previous, ManagedHostPresent):
                os.link(staged_receipt, preimage)
                receipt_published = True
            if isinstance(lock_proof, ManagedHostStagedLock):
                os.link(staged_lock, lock_stage)
                lock_published = True
            sync(recovery)
            return record
        except (OSError, Rejected):
            discard_unrecorded_managed(record, candidate_published, receipt_published, lock_published)
            raise


def discard_unrecorded_managed(record: ManagedHostReplacement, candidate_published: bool,
                              receipt_published: bool, lock_published: bool) -> None:
    """Discard only this call's complete staged artifacts after their exact readmission."""
    candidate = Path(record.candidate)
    if candidate_published:
        admit_managed_candidate(record, candidate)
    if receipt_published:
        previous = record.previous
        if (not isinstance(previous, ManagedHostPresent)
                or owned_file(Path(previous.receipt), private=True) != previous.receiptIdentity
                or hashlib.sha256(Path(previous.receipt).read_bytes()).hexdigest() != previous.receiptSha256):
            raise Rejected(Failure.RECOVERY_REQUIRED)
    if lock_published:
        admit_staged_lock(record, Path(record.lock.staged))
    if candidate_published:
        shutil.rmtree(candidate)
    if receipt_published:
        Path(record.previous.receipt).unlink()
    if lock_published:
        Path(record.lock.staged).unlink()


def admit_managed_paths(record: ManagedHostReplacement) -> tuple[Path, Path, Path, Path]:
    root, candidate, backup, destination = (Path(record.pluginRoot), Path(record.candidate),
                                            Path(record.backup), Path(record.destination))
    recovery = candidate.parent
    if (recovery.name != 'replacement' or recovery.parent.name != 'recovery'
            or candidate.name != 'host-candidate' or backup != recovery / 'host-prior'
            or destination != root / 'kast-ide-hosted' or recovery.resolve() != recovery):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    admit_root(root)
    Identity.observe(recovery)
    return root, candidate, backup, destination


def admit_managed_prior(record: ManagedHostReplacement, path: Path) -> None:
    previous = record.previous
    if (not isinstance(previous, ManagedHostPresent) or Identity.observe(path) != previous.identity
            or inventory_digest(path) != previous.inventorySha256):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def admit_managed_candidate(record: ManagedHostReplacement, path: Path) -> None:
    if Identity.observe(path) != record.candidateIdentity or inventory_digest(path) != record.candidateInventorySha256:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def admit_managed_prepared(record: ManagedHostReplacement) -> None:
    """Readmit candidate, prior, profile roots, and the bound Host lock before Control effects."""
    root, candidate, backup, destination = admit_managed_paths(record)
    if record.pluginRootIdentity is None:
        if os.path.lexists(root):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    elif Identity.observe(root) != record.pluginRootIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    retained = root.parent / '.kast-plugin-recovery'
    admit_managed_retained(record, retained)
    admit_managed_candidate(record, candidate)
    if os.path.lexists(backup):
        raise Rejected(Failure.RECOVERY_REQUIRED)
    if isinstance(record.previous, ManagedHostPresent):
        admit_managed_prior(record, destination)
        preimage = Path(record.previous.receipt)
        if (owned_file(preimage, private=True) != record.previous.receiptIdentity
                or hashlib.sha256(preimage.read_bytes()).hexdigest() != record.previous.receiptSha256
                or (retained / 'host-install.json').read_bytes() != preimage.read_bytes()):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    elif os.path.lexists(destination):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    elif os.path.lexists(retained / 'host-install.json'):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if isinstance(record.lock, ManagedHostExistingLock) or bound_staged_lock(record):
        admit_canonical_lock(record, retained / 'host-install.lock')


def commit_managed(record: ManagedHostReplacement) -> HostActiveReceipt:
    """Exchange admitted files only after the outer coordinator proves Host settlement or exit."""
    admit_managed_prepared(record)
    root, candidate, backup, destination = admit_managed_paths(record)
    retained = root.parent / '.kast-plugin-recovery'
    if record.pluginRootIdentity is None:
        root.mkdir()
    if not os.path.lexists(retained):
        retained.mkdir(mode=0o700)
    if isinstance(record.previous, ManagedHostPresent):
        destination.rename(backup)
    candidate.rename(destination)
    sync(root)
    active = HostActiveReceipt(HostReceiptType.HOST_ACTIVE, str(destination), Identity.observe(root),
                               record.candidateIdentity, record.version, record.sha256, inventory(destination))
    write_receipt(retained / 'host-install.json', active)
    admit_managed_candidate(record, destination)
    return active


def commit_managed_reload(record: ManagedHostReplacement) -> HostActiveReceipt:
    """Exchange complete admitted Host directories without an absent-destination interval."""
    from host_directory_exchange import DirectoryExchangeOutcome, exchange_directories

    admit_managed_prepared(record)
    root, candidate, backup, destination = admit_managed_paths(record)
    retained = root.parent / '.kast-plugin-recovery'
    if (not isinstance(record.previous, ManagedHostPresent)
            or record.pluginRootIdentity is None or record.retainedRootIdentity is None
            or Identity.observe(root) != record.pluginRootIdentity
            or Identity.observe(retained) != record.retainedRootIdentity
            or os.path.lexists(backup)):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    admit_managed_candidate(record, candidate)
    admit_managed_prior(record, destination)
    preimage = Path(record.previous.receipt)
    if (preimage != candidate.parent / 'host-receipt'
            or owned_file(preimage, private=True) != record.previous.receiptIdentity
            or hashlib.sha256(preimage.read_bytes()).hexdigest() != record.previous.receiptSha256
            or (retained / 'host-install.json').read_bytes() != preimage.read_bytes()):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    exchanged = exchange_directories(candidate, destination)
    if exchanged is not DirectoryExchangeOutcome.EXCHANGED:
        raise Rejected(Failure.FILESYSTEM_REJECTED)
    # A crash here leaves the exact prior inode at candidate, which rollback_managed_reload admits.
    admit_managed_prior(record, candidate)
    candidate.rename(backup)
    sync(root)
    active = HostActiveReceipt(HostReceiptType.HOST_ACTIVE, str(destination), Identity.observe(root),
                               record.candidateIdentity, record.version, record.sha256, inventory(destination))
    write_receipt(retained / 'host-install.json', active)
    admit_managed_candidate(record, destination)
    return active


def rollback_managed_reload(record: ManagedHostReplacement) -> None:
    """Admit both atomic-swap states before the ordinary exact-preimage restoration."""
    from host_directory_exchange import DirectoryExchangeOutcome, exchange_directories

    root, candidate, backup, destination = admit_managed_paths(record)
    if not isinstance(record.previous, ManagedHostPresent):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    admit_managed_rollback_receipt(record, root, candidate, backup, destination)
    if Identity.observe(destination) == record.candidateIdentity:
        admit_managed_candidate(record, destination)
        prior = candidate if os.path.lexists(candidate) else backup
        admit_managed_prior(record, prior)
        if prior == candidate and os.path.lexists(backup):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        if exchange_directories(prior, destination) is not DirectoryExchangeOutcome.EXCHANGED:
            raise Rejected(Failure.FILESYSTEM_REJECTED)
        if prior == backup:
            backup.rename(candidate)
    else:
        admit_managed_prior(record, destination)
        if os.path.lexists(backup):
            admit_managed_candidate(record, backup)
            if os.path.lexists(candidate):
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            backup.rename(candidate)
    rollback_managed(record)


def admit_signal_lease(lease: HeldHostLease) -> ManagedHostReplacement:
    if (
        not isinstance(lease, HeldHostLease)
        or lease._state is not HostLeaseState.HELD
        or not isinstance(lease._stream, TextIOBase)
        or lease._stream.closed
    ):
        raise ReloadSignalRejected(
            ReloadSignalFailure.LEASE_NOT_HELD, HostInstallationStage.RELOAD_SIGNAL_ADMISSION
        )
    record = lease.record
    if record.lock is None:
        raise ReloadSignalRejected(
            ReloadSignalFailure.LEASE_NOT_HELD, HostInstallationStage.RELOAD_SIGNAL_ADMISSION
        )
    try:
        observed = os.fstat(lease._stream.fileno())
    except (OSError, ValueError):
        raise ReloadSignalRejected(
            ReloadSignalFailure.LEASE_NOT_HELD, HostInstallationStage.RELOAD_SIGNAL_ADMISSION
        ) from None
    if (
        not stat.S_ISREG(observed.st_mode)
        or Identity(observed.st_dev, observed.st_ino, observed.st_uid) != record.lock.identity
    ):
        raise ReloadSignalRejected(
            ReloadSignalFailure.LEASE_NOT_HELD, HostInstallationStage.RELOAD_SIGNAL_ADMISSION
        )
    root, _, _, _ = admit_managed_paths(record)
    if Identity.observe(root) != record.pluginRootIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    retained = root.parent / '.kast-plugin-recovery'
    admit_managed_retained(record, retained)
    admit_managed_lock_reservation(
        record, retained / 'host-install.lock', Path(record.candidate).parent / 'host-lock'
    )
    return record


def admit_signal_layout(
    record: ManagedHostReplacement, target: ReloadSignalTarget
) -> tuple[HostActiveReceipt, Path]:
    root, candidate, backup, destination = admit_managed_paths(record)
    if target is ReloadSignalTarget.SELECTED:
        admit_managed_candidate(record, destination)
        admit_managed_prior(record, backup)
        counterpart = backup
        expected_identity = record.candidateIdentity
    elif target is ReloadSignalTarget.RESTORED_PRIOR:
        admit_managed_prior(record, destination)
        admit_managed_candidate(record, candidate)
        counterpart = candidate
        expected_identity = record.previous.identity
    else:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    admit_managed_rollback_receipt(record, root, candidate, backup, destination)
    active = admit_active(
        root, read_active_receipt(root.parent / '.kast-plugin-recovery/host-install.json')
    )
    if active.destinationIdentity != expected_identity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    return active, counterpart


def next_reload_signal_millis(before: int, retained: int, observed: int) -> int:
    limit = (2**63 - 1) // 1_000_000
    if (
        any(
            type(value) is not int or not -limit <= value < limit
            for value in (before, retained, observed)
        )
        or observed < 0
    ):
        raise ReloadSignalRejected(
            ReloadSignalFailure.STAMP_RANGE, HostInstallationStage.RELOAD_SIGNAL_WRITE
        )
    return max(before, retained, observed) + 1


def signal_file_key(value: os.stat_result) -> tuple[int, int, int, int, int]:
    return value.st_dev, value.st_ino, value.st_uid, value.st_mode, value.st_size


def signal_file_digest(descriptor: int) -> str:
    os.lseek(descriptor, 0, os.SEEK_SET)
    digest, size = hashlib.sha256(), 0
    while True:
        block = os.read(descriptor, 65536)
        if not block:
            return digest.hexdigest()
        size += len(block)
        if size > 1073741824:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        digest.update(block)


def stamp_reload_signal(lease: HeldHostLease, target: ReloadSignalTarget) -> ReloadSignalEvidence:
    stage = HostInstallationStage.RELOAD_SIGNAL_ADMISSION
    try:
        record = admit_signal_lease(lease)
        marker = Path(record.destination) / 'lib/kast-ide-hosted.jar'
        try:
            identity = owned_file(marker)
        except (Rejected, OSError):
            raise ReloadSignalRejected(ReloadSignalFailure.MARKER_UNPROVEN, stage) from None
        descriptor = os.open(marker, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, 'rb') as stream:
            before = os.fstat(stream.fileno())
            if Identity(before.st_dev, before.st_ino, before.st_uid) != identity:
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            digest = signal_file_digest(stream.fileno())
            active, counterpart = admit_signal_layout(record, target)
            admitted = next(
                (
                    item
                    for item in active.inventory
                    if isinstance(item, HostFileArtifact) and item.path == 'lib/kast-ide-hosted.jar'
                ),
                None,
            )
            if (
                admitted is None
                or admitted.identity != identity
                or admitted.sha256 != digest
                or admitted.bytes != before.st_size
                or signal_file_key(marker.lstat()) != signal_file_key(before)
            ):
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            retained_marker = counterpart / 'lib/kast-ide-hosted.jar'
            owned_file(retained_marker)
            retained_stamp = retained_marker.lstat().st_mtime_ns // 1_000_000
            observed = time.time_ns() // 1_000_000
            selected = next_reload_signal_millis(
                before.st_mtime_ns // 1_000_000, retained_stamp, observed
            )
            stage = HostInstallationStage.RELOAD_SIGNAL_WRITE
            os.utime(stream.fileno(), ns=(before.st_atime_ns, selected * 1_000_000))
            os.fsync(stream.fileno())
            stage = HostInstallationStage.RELOAD_SIGNAL_PROOF
            after = os.fstat(stream.fileno())
            if after.st_mtime_ns // 1_000_000 != selected:
                raise ReloadSignalRejected(ReloadSignalFailure.STAMP_PRECISION, stage)
            if (
                signal_file_key(after) != signal_file_key(before)
                or signal_file_digest(stream.fileno()) != digest
                or signal_file_key(marker.lstat()) != signal_file_key(before)
            ):
                raise ReloadSignalRejected(ReloadSignalFailure.FILE_CHANGED, stage)
            admit_signal_layout(record, target)
            if retained_marker.lstat().st_mtime_ns // 1_000_000 != retained_stamp:
                raise ReloadSignalRejected(ReloadSignalFailure.FILE_CHANGED, stage)
            # Inventory and descriptor hash reads can change atime. Restore it after those reads.
            os.utime(stream.fileno(), ns=(before.st_atime_ns, after.st_mtime_ns))
            os.fsync(stream.fileno())
            final = os.fstat(stream.fileno())
            if (
                signal_file_key(final) != signal_file_key(before)
                or final.st_atime_ns != before.st_atime_ns
                or final.st_mtime_ns != after.st_mtime_ns
                or signal_file_key(marker.lstat()) != signal_file_key(final)
            ):
                raise ReloadSignalRejected(ReloadSignalFailure.FILE_CHANGED, stage)
            admit_signal_lease(lease)
            return ReloadSignalEvidence(
                target,
                identity,
                stat.S_IMODE(final.st_mode),
                final.st_size,
                digest,
                final.st_atime_ns,
                before.st_mtime_ns // 1_000_000,
                retained_stamp,
                observed,
                final.st_mtime_ns // 1_000_000,
            )
    except ReloadSignalRejected:
        raise
    except Rejected:
        raise ReloadSignalRejected(ReloadSignalFailure.OWNERSHIP_UNPROVEN, stage) from None
    except OSError:
        raise ReloadSignalRejected(ReloadSignalFailure.IO_FAILURE, stage) from None


def rollback_managed(record: ManagedHostReplacement) -> None:
    """Restore the admitted prior Host or prior absence. Previous bytes stay until this proof succeeds."""
    root, candidate, backup, destination = admit_managed_paths(record)
    admit_managed_rollback_receipt(record, root, candidate, backup, destination)
    retained = root.parent / '.kast-plugin-recovery'
    receipt_path = retained / 'host-install.json'
    if os.path.lexists(destination):
        if Identity.observe(destination) == record.candidateIdentity:
            admit_managed_candidate(record, destination)
            if os.path.lexists(candidate):
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            destination.rename(candidate)
        elif isinstance(record.previous, ManagedHostPresent):
            admit_managed_prior(record, destination)
        else:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if isinstance(record.previous, ManagedHostPresent):
        if os.path.lexists(backup):
            admit_managed_prior(record, backup)
            if os.path.lexists(destination):
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            backup.rename(destination)
        admit_managed_prior(record, destination)
        preimage = Path(record.previous.receipt)
        if (preimage != candidate.parent / 'host-receipt'
                or owned_file(preimage, private=True) != record.previous.receiptIdentity
                or hashlib.sha256(preimage.read_bytes()).hexdigest() != record.previous.receiptSha256):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        write_receipt_bytes(receipt_path, preimage.read_bytes())
        admit_active(root, read_active_receipt(receipt_path))
    else:
        if os.path.lexists(destination) or os.path.lexists(backup):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        if os.path.lexists(receipt_path):
            active = read_active_receipt(receipt_path)
            if active.destinationIdentity != record.candidateIdentity or active.sha256 != record.sha256:
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            receipt_path.unlink()
        if (record.retainedRootIdentity is None and os.path.lexists(retained)
                and not bound_staged_lock(record)):
            retained.rmdir()
        if record.pluginRootIdentity is None and os.path.lexists(root):
            root.rmdir()
    if os.path.lexists(root):
        sync(root)


def admit_managed_rollback_receipt(record: ManagedHostReplacement, root: Path, candidate: Path,
                                   backup: Path, destination: Path) -> None:
    retained = root.parent / '.kast-plugin-recovery'
    receipt_path = retained / 'host-install.json'
    if os.path.lexists(receipt_path):
        owned_file(receipt_path)
        receipt_bytes = receipt_path.read_bytes()
        previous_bytes = None
        if isinstance(record.previous, ManagedHostPresent):
            preimage = Path(record.previous.receipt)
            if (preimage != candidate.parent / 'host-receipt'
                    or owned_file(preimage, private=True) != record.previous.receiptIdentity
                    or hashlib.sha256(preimage.read_bytes()).hexdigest() != record.previous.receiptSha256):
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            previous_bytes = preimage.read_bytes()
        if receipt_bytes != previous_bytes:
            candidates = [path for path in (destination, candidate, backup)
                          if os.path.lexists(path) and Identity.observe(path) == record.candidateIdentity]
            if len(candidates) != 1:
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            selected = candidates[0]
            admit_managed_candidate(record, selected)
            expected = HostActiveReceipt(HostReceiptType.HOST_ACTIVE, str(destination), Identity.observe(root),
                                        record.candidateIdentity, record.version, record.sha256, inventory(selected))
            if receipt_bytes != encode(expected).encode():
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    elif isinstance(record.previous, ManagedHostPresent):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)


def seal_managed(record: ManagedHostReplacement, terminal: ManagedHostTerminalPhase) -> None:
    """Release only exact preimages after the coordinator admits activation or restored prior state."""
    root, candidate, backup, destination = admit_managed_paths(record)
    retained = root.parent / '.kast-plugin-recovery'
    receipt_path = retained / 'host-install.json'
    if record.pluginRootIdentity is not None and Identity.observe(root) != record.pluginRootIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if record.retainedRootIdentity is not None and Identity.observe(retained) != record.retainedRootIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if terminal is ManagedHostTerminalPhase.ACTIVATION_PROVEN:
        admit_managed_candidate(record, destination)
        active = admit_active(root, read_active_receipt(receipt_path))
        if (active.destinationIdentity != record.candidateIdentity or active.version != record.version
                or active.sha256 != record.sha256 or inventory(destination) != active.inventory):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    elif terminal is ManagedHostTerminalPhase.RESTORED:
        if isinstance(record.previous, ManagedHostPresent):
            admit_managed_prior(record, destination)
            active = admit_active(root, read_active_receipt(receipt_path))
            if (active.destinationIdentity != record.previous.identity
                    or hashlib.sha256(receipt_path.read_bytes()).hexdigest() != record.previous.receiptSha256):
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        elif (os.path.lexists(destination) or os.path.lexists(receipt_path)
                or (record.pluginRootIdentity is None and os.path.lexists(root))):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        elif record.retainedRootIdentity is None and os.path.lexists(retained):
            admit_restored_lock_metadata(record, retained)
    else:
        raise Rejected(Failure.RECOVERY_REQUIRED)
    if os.path.lexists(candidate):
        retire_managed_host(candidate, record.candidateIdentity, record.candidateInventorySha256)
    if os.path.lexists(backup):
        if not isinstance(record.previous, ManagedHostPresent):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        retire_managed_host(backup, record.previous.identity, record.previous.inventorySha256)
    if isinstance(record.previous, ManagedHostPresent):
        preimage = Path(record.previous.receipt)
        if preimage != candidate.parent / 'host-receipt':
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        if os.path.lexists(preimage):
            if (owned_file(preimage) != record.previous.receiptIdentity
                    or hashlib.sha256(preimage.read_bytes()).hexdigest() != record.previous.receiptSha256):
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
            preimage.unlink()
    if isinstance(record.lock, ManagedHostStagedLock) and record.lock.retainedIdentity is None:
        staged_lock = Path(record.lock.staged)
        if os.path.lexists(staged_lock):
            admit_staged_lock(record, staged_lock)
            staged_lock.unlink()
    sync(candidate.parent)


@dataclass(frozen=True)
class ManagedHostCleanupObservation:
    reason: Failure
    event: Literal['kast_managed_host_cleanup'] = 'kast_managed_host_cleanup'
    outcome: Literal['FAILED'] = 'FAILED'


def retire_managed_host(path: Path, identity: Identity, digest: str) -> None:
    from host_directory_exchange import DirectoryMoveOutcome, move_directory_noreplace

    if Identity.observe(path) != identity or inventory_digest(path) != digest:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    parent = path.parent.parent
    if parent.resolve() != parent:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    Identity.observe(parent)
    name = f'completed-{path.name}-{identity.device}-{identity.inode}-{identity.owner}'
    completed = parent / name
    if os.path.lexists(completed):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if move_directory_noreplace(path, completed) is not DirectoryMoveOutcome.MOVED:
        raise Rejected(Failure.FILESYSTEM_REJECTED)
    sync(path.parent)
    sync(parent)
    try:
        if (not shutil.rmtree.avoids_symlink_attacks or Identity.observe(completed) != identity
                or inventory_digest(completed) != digest):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        shutil.rmtree(completed)
        sync(parent)
    except (OSError, Rejected) as problem:
        reason = problem.failure if isinstance(problem, Rejected) else Failure.FILESYSTEM_REJECTED
        print(encode(ManagedHostCleanupObservation(reason)), file=sys.stderr)


def bound_staged_lock(record: ManagedHostReplacement) -> bool:
    return isinstance(record.lock, ManagedHostStagedLock) and record.lock.retainedIdentity is not None


def admit_restored_lock_metadata(record: ManagedHostReplacement, retained: Path) -> None:
    if not bound_staged_lock(record):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    admit_managed_retained(record, retained)
    entries = {path.name for path in retained.iterdir()}
    if not entries.issubset({'host-install.lock'}):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    if entries:
        admit_canonical_lock(record, retained / 'host-install.lock')


def decode_managed(value: object) -> ManagedHostReplacement:
    """Decode the Host field of the existing replacement journal without a second receipt owner."""
    if not isinstance(value, dict) or set(value) != set(ManagedHostReplacement.__dataclass_fields__):
        raise Rejected(Failure.RECOVERY_REQUIRED)
    previous = value['previous']
    if not isinstance(previous, dict):
        raise Rejected(Failure.RECOVERY_REQUIRED)
    try:
        if previous.get('type') == 'ABSENT' and set(previous) == {'type'}:
            prior = ManagedHostAbsent()
        elif previous.get('type') == 'PRESENT' and set(previous) == set(ManagedHostPresent.__dataclass_fields__):
            prior = ManagedHostPresent(**(previous | {'identity': parse_identity(previous['identity']),
                                                      'receiptIdentity': parse_identity(previous['receiptIdentity'])}))
        else:
            raise Rejected(Failure.RECOVERY_REQUIRED)
        lock_value = value['lock']
        if lock_value is None:
            lock = None
        elif isinstance(lock_value, dict) and lock_value.get('type') == 'EXISTING' and set(lock_value) == {'type', 'identity'}:
            lock = ManagedHostExistingLock(parse_identity(lock_value['identity']))
        elif isinstance(lock_value, dict) and lock_value.get('type') == 'STAGED' and set(lock_value) == {'type', 'identity', 'staged', 'retainedIdentity'}:
            lock = ManagedHostStagedLock(parse_identity(lock_value['identity']), lock_value['staged'],
                                        None if lock_value['retainedIdentity'] is None else parse_identity(lock_value['retainedIdentity']))
            if Path(lock.staged) != Path(value['candidate']).parent / 'host-lock':
                raise Rejected(Failure.RECOVERY_REQUIRED)
        else:
            raise Rejected(Failure.RECOVERY_REQUIRED)
        record = ManagedHostReplacement(**(value | {
            'pluginRootIdentity': None if value['pluginRootIdentity'] is None else parse_identity(value['pluginRootIdentity']),
            'retainedRootIdentity': None if value['retainedRootIdentity'] is None else parse_identity(value['retainedRootIdentity']),
            'candidateIdentity': parse_identity(value['candidateIdentity']), 'previous': prior, 'lock': lock,
        }))
        strings = (record.pluginRoot, record.destination, record.candidate, record.backup, record.version,
                   record.candidateInventorySha256, record.sha256)
        if any(not isinstance(item, str) or not 1 <= len(item) <= 4096 for item in strings):
            raise Rejected(Failure.RECOVERY_REQUIRED)
        if not all(re.fullmatch('[0-9a-f]{64}', item) for item in (record.candidateInventorySha256, record.sha256)):
            raise Rejected(Failure.RECOVERY_REQUIRED)
        admit_managed_paths(record)
        return record
    except (KeyError, TypeError, ValueError):
        raise Rejected(Failure.RECOVERY_REQUIRED) from None


@dataclass(frozen=True)
class HostRejection:
    type: HostRejectionType
    failure: Failure


@dataclass(frozen=True)
class HostInstallRejection(HostRejection):
    stage: HostInstallationStage


def encode(value):
    return json.dumps(asdict(value), default=lambda item: item.value if isinstance(item, Enum) else str(item)) + '\n'


def sync(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_receipt(path, receipt):
    write_receipt_bytes(path, encode(receipt).encode('utf-8'))


def write_receipt_bytes(path: Path, document: bytes) -> None:
    if len(document) > 2097152:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    descriptor, name = tempfile.mkstemp(prefix='.host-receipt-', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(document)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
        sync(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


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


def admit_active(root: Path, receipt: HostActiveReceipt) -> HostActiveReceipt:
    destination = root / 'kast-ide-hosted'
    current_root = Identity.observe(root)
    recorded_root = receipt.pluginRootIdentity
    if (receipt.destination != str(destination) or current_root.inode != recorded_root.inode
            or current_root.owner != recorded_root.owner):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN, HostInstallationStage.ACTIVE_ROOT)
    if current_root.device != recorded_root.device:
        def current_identity(identity: Identity) -> Identity:
            if identity.device != recorded_root.device:
                raise Rejected(Failure.OWNERSHIP_UNPROVEN, HostInstallationStage.ACTIVE_INVENTORY)
            return replace(identity, device=current_root.device)
        receipt = replace(receipt, pluginRootIdentity=current_root,
                          destinationIdentity=current_identity(receipt.destinationIdentity),
                          inventory=tuple(replace(artifact, identity=current_identity(artifact.identity))
                                          for artifact in receipt.inventory))
    if not os.path.lexists(destination):
        return receipt
    if Identity.observe(destination) != receipt.destinationIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN, HostInstallationStage.ACTIVE_DESTINATION)
    actual = inventory(destination)
    expected = {artifact.path: artifact for artifact in receipt.inventory}
    if any(expected.get(artifact.path) != artifact for artifact in actual):
        raise Rejected(Failure.OWNERSHIP_UNPROVEN, HostInstallationStage.ACTIVE_INVENTORY)
    if Identity.observe(root) != current_root or Identity.observe(destination) != receipt.destinationIdentity:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN, HostInstallationStage.ACTIVE_DESTINATION)
    return receipt


def is_control_history(name: str) -> bool:
    # Ordinary installation keeps these legacy Control entries. Explicit reset can retire them.
    return re.fullmatch(r'(?:\.kast-ide-hosted\.(?:baseline|detached)-|\.replaced-kast-ide-hosted-)[0-9a-f]{32}', name) is not None


def admit_retained_entries(retained: Path) -> None:
    with os.scandir(retained) as entries:
        if any(entry.name not in ('host-install.lock', 'host-install.json')
               and not is_control_history(entry.name) for entry in entries):
            raise Rejected(Failure.RECOVERY_REQUIRED, HostInstallationStage.RETAINED_RECOVERY)


def admit_installation_target(root: Path) -> bytes | None:
    if os.path.lexists(root):
        Identity.observe(root)
    retained = root.parent / '.kast-plugin-recovery'
    receipt = None
    if os.path.lexists(retained):
        Identity.observe(retained)
        if stat.S_IMODE(retained.lstat().st_mode) != 0o700:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        receipt_path = retained / 'host-install.json'
        if os.path.lexists(receipt_path):
            owned_file(receipt_path)
            with os.fdopen(os.open(receipt_path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
                receipt = stream.read(2097153)
            if len(receipt) > 2097152:
                raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    destination = root / 'kast-ide-hosted'
    if os.path.lexists(destination):
        Identity.observe(destination)
    return receipt


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
    Identity.observe(retained)
    if stat.S_IMODE(retained.lstat().st_mode) != 0o700:
        raise Rejected(Failure.OWNERSHIP_UNPROVEN)
    with os.scandir(retained) as entries:
        names = tuple(entry.name for entry in entries)
    if names and all(is_control_history(name) for name in names):
        admit_removal_root(root)
        if os.path.lexists(root / 'kast-ide-hosted'):
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        return
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
        admit_active(root, admitted)
        descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.close(descriptor)
    lock_identity = owned_file(lock_path, private=True)
    descriptor = os.open(lock_path, os.O_RDWR | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'r+') as lock:
        observed = os.fstat(lock.fileno())
        if Identity(observed.st_dev, observed.st_ino, observed.st_uid) != lock_identity:
            raise Rejected(Failure.OWNERSHIP_UNPROVEN)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        admit_unreserved_host_lock(lock)
        receipt_identity = owned_file(receipt_path, private=True)
        receipt = read_active_receipt(receipt_path)
        receipt = admit_active(root, receipt)
        admit_retained_entries(retained)
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
        admit_active(root, receipt)
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
        retire_empty_retained(root, retained)
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
            admit_installation_target(root)
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
            admit_unreserved_host_lock(lock)
            receipt_path = retained / 'host-install.json'
            previous_receipt = admit_installation_target(root)
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
                        write_receipt_bytes(receipt_path, previous_receipt)
                except (OSError, Rejected):
                    raise Rejected(Failure.RECOVERY_REQUIRED) from None
                raise Rejected(Failure.FILESYSTEM_REJECTED) from None
            if prior is not None:
                if Identity.observe(backup) != prior:
                    raise Rejected(Failure.RECOVERY_REQUIRED)
                shutil.rmtree(backup)
            if request.mode is HostInstallMode.RESET:
                for path in retained.iterdir():
                    if (is_control_history(path.name) or path.name == 'host-install.json.new'
                            or re.fullmatch(r'host-(?:prior|candidate)-[0-9a-f]{32}', path.name)):
                        if path.is_symlink() or not path.is_dir():
                            path.unlink()
                        else:
                            shutil.rmtree(path)
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
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--remove', action='store_true')
    arguments = parser.parse_args()
    try:
        install_values = (arguments.archive, arguments.sha256, arguments.version, arguments.idea_build, arguments.release_record)
        if arguments.remove:
            if any(value is not None for value in install_values) or arguments.required_control is not None or arguments.dry_run or arguments.force:
                raise Rejected(Failure.PAYLOAD_REJECTED)
            print(encode(remove_host(arguments.plugin_root)), end='')
            return 0
        if any(value is None for value in install_values):
            raise Rejected(Failure.PAYLOAD_REJECTED)
        payload = HostedPluginPayload(arguments.archive, arguments.sha256, arguments.version, arguments.idea_build)
        admit_host_release(arguments.release_record, payload, arguments.required_control)
        mode = HostInstallMode.PLAN if arguments.dry_run else HostInstallMode.RESET if arguments.force else HostInstallMode.APPLY
        report = install(HostInstallRequest(payload, arguments.plugin_root, mode))
        print(encode(report), end='')
        return 0
    except Rejected as rejected:
        report = (HostRejection(HostRejectionType.REJECTED, rejected.failure) if arguments.remove else
                  HostInstallRejection(HostRejectionType.REJECTED, rejected.failure, rejected.stage))
        print(encode(report), end='', file=sys.stdout if arguments.remove else sys.stderr)
        return 1
    except (OSError, ValueError, zipfile.BadZipFile, XML.ParseError):
        report = (HostRejection(HostRejectionType.REJECTED, Failure.FILESYSTEM_REJECTED) if arguments.remove else
                  HostInstallRejection(HostRejectionType.REJECTED, Failure.FILESYSTEM_REJECTED, HostInstallationStage.INSTALLATION))
        print(encode(report), end='', file=sys.stdout if arguments.remove else sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
