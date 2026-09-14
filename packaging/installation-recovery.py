"""Offline, ownership-only detachment. Never executes an unverified installed payload.

The receipt and this executable live outside bin/lib/share. Quarantined evidence
is never deleted by recovery. An unresolved process is never signalled by PID.
"""
from dataclasses import asdict, dataclass, fields
from enum import Enum
from pathlib import Path
import argparse
import fcntl
import hashlib
import json
import os
import shutil
import stat
import sys
import types
import typing
import uuid


class Status(str, Enum):
    PREPARED = 'Prepared'
    PLANNED = 'Planned'
    ACTIVE = 'Active'
    CLEAN = 'CleanBaselineRestored'
    UNRESOLVED = 'DetachedWithUnresolvedState'
    BLOCKED = 'RecoveryBlocked'


class Failure(str, Enum):
    OWNERSHIP = 'ANCHOR_OWNERSHIP_UNPROVEN'
    RECEIPT = 'RECEIPT_REJECTED'
    FILESYSTEM = 'FILESYSTEM_REJECTED'
    RETIREMENT = 'RETIREMENT_UNPROVEN'
    EVIDENCE = 'UNRESOLVED_STATE_PRESERVED'
    RESTART = 'IDE_RESTART_REQUIRED'


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
        value = path.lstat()
        if value.st_uid != os.getuid():
            raise Rejected(Failure.OWNERSHIP)
        return Identity(value.st_dev, value.st_ino, value.st_uid)


@dataclass(frozen=True)
class Link:
    path: str
    target: str
    priorTarget: str | None


@dataclass(frozen=True)
class Plugin:
    destination: str
    candidate: str
    candidateIdentity: Identity
    backup: str
    priorIdentity: Identity | None
    quarantine: str


@dataclass(frozen=True)
class Receipt:
    schemaVersion: int
    installation: str
    installationIdentity: Identity
    links: list[Link]
    priorInstallation: str | None
    plugin: Plugin | None
    stage: Status


@dataclass(frozen=True)
class Report:
    status: Status
    unresolved: list[Failure]
    recoveryExecutable: str


def encode(document):
    return json.dumps(asdict(document), separators=(',', ':'))


def decode(kind, value):
    """Strict dataclass boundary: unknown fields and absent fields are rejected."""
    origin = typing.get_origin(kind)
    if origin in (typing.Union, types.UnionType):
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
        return kind(value)
    if not isinstance(value, dict) or set(value) != {field.name for field in fields(kind)}:
        raise Rejected(Failure.RECEIPT)
    return kind(**{field.name: decode(field.type, value[field.name]) for field in fields(kind)})


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


def load(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as source:
        observed = os.fstat(source.fileno())
        if not stat.S_ISREG(observed.st_mode) or observed.st_uid != os.getuid() or observed.st_mode & 0o077:
            raise Rejected(Failure.RECEIPT)
        raw = source.read(65537)
    if len(raw) > 65536:
        raise Rejected(Failure.RECEIPT)
    return decode(Receipt, json.loads(raw, object_pairs_hook=unique))


def location(root):
    physical(root)
    if root.parent.name != 'versions':
        raise Rejected(Failure.OWNERSHIP)
    physical(root.parent)
    outer = physical(root.parent.parent)
    return outer, outer / 'recovery' / root.name


def retained_script(bundle):
    return bundle / 'installation-recovery.py'


def prepare(root, bin_directory):
    outer, bundle = location(root)
    physical(bin_directory)
    if bundle.exists():
        validate(root, load(bundle / 'receipt.json'))
        return Report(Status.PREPARED, [], str(retained_script(bundle)))
    (outer / 'recovery').mkdir(mode=0o700, exist_ok=True)
    physical(outer / 'recovery')
    bundle.mkdir(mode=0o700)
    current = outer / 'current'
    previous = None
    if os.path.lexists(current):
        if not current.is_symlink():
            raise Rejected(Failure.OWNERSHIP)
        previous = os.readlink(current)
        prior = outer / previous
        if Path(previous).is_absolute() or len(Path(previous).parts) != 2 or prior.parent != root.parent:
            raise Rejected(Failure.OWNERSHIP)
        physical(prior)
    links = [Link(str(current), 'versions/' + root.name, previous)]
    for name in ('kast', 'kast-codex'):
        path = bin_directory / name
        target = str(current / 'bin' / (name + '-complete'))
        prior = None
        if os.path.lexists(path):
            if not path.is_symlink() or os.readlink(path) != target:
                raise Rejected(Failure.OWNERSHIP)
            prior = target
        links.append(Link(str(path), target, prior))
    receipt = Receipt(1, str(root), Identity.observe(root), links,
                      str(outer / previous) if previous else None, None, Status.PREPARED)
    # Copy the trusted executing bundle, not a file from the damaged installation.
    shutil.copyfile(Path(__file__).resolve(), retained_script(bundle))
    os.chmod(retained_script(bundle), 0o600)
    sync(retained_script(bundle))
    lifecycle = Path(__file__).resolve().with_name('installation-lifecycle.py')
    if lifecycle.is_file():
        shutil.copyfile(lifecycle, bundle / lifecycle.name)
        os.chmod(bundle / lifecycle.name, 0o600)
        sync(bundle / lifecycle.name)
    save(bundle / 'receipt.json', receipt)
    sync(bundle.parent)
    return Report(Status.PREPARED, [], str(retained_script(bundle)))


def validate(root, receipt):
    if receipt.schemaVersion != 1 or receipt.installation != str(root) or receipt.installationIdentity != Identity.observe(root):
        raise Rejected(Failure.RECEIPT)
    outer, bundle = location(root)
    physical(bundle.parent)
    physical(bundle)
    if len(receipt.links) != 3:
        raise Rejected(Failure.RECEIPT)
    current, command, codex = receipt.links
    if current.path != str(outer / 'current') or current.target != 'versions/' + root.name:
        raise Rejected(Failure.RECEIPT)
    for link, name in ((command, 'kast'), (codex, 'kast-codex')):
        path = Path(link.path)
        physical(path.parent)
        if path.name != name or link.target != str(outer / 'current/bin' / (name + '-complete')):
            raise Rejected(Failure.RECEIPT)
    if Path(command.path).parent != Path(codex.path).parent:
        raise Rejected(Failure.RECEIPT)
    return bundle


def replace_stage(receipt, stage, plugin=None):
    from dataclasses import replace
    return replace(receipt, stage=stage, plugin=receipt.plugin if plugin is None else plugin)


def activate_plugin(root, staged, plugin_root):
    outer, bundle = location(root)
    receipt = load(bundle / 'receipt.json')
    validate(root, receipt)
    physical(staged)
    plugin_root.mkdir(parents=True, exist_ok=True)
    physical(plugin_root)
    destination = plugin_root / 'kast-ide-hosted'
    if receipt.plugin is None:
        prior = Identity.observe(physical(destination)) if os.path.lexists(destination) else None
        token = uuid.uuid4().hex
        candidate = plugin_root / ('.kast-ide-hosted.install-' + token)
        shutil.copytree(physical(staged / 'kast-ide-hosted'), candidate, symlinks=True)
        # Source archive was admitted by the bootstrap; reject unexpected links on copy as well.
        for current, directories, files in os.walk(candidate, followlinks=False):
            for name in directories + files:
                path = Path(current) / name
                if path.is_symlink():
                    raise Rejected(Failure.OWNERSHIP)
                if path.is_file():
                    sync(path)
            sync(Path(current))
        plugin = Plugin(str(destination), str(candidate), Identity.observe(candidate),
                        str(plugin_root / ('.kast-ide-hosted.baseline-' + token)), prior,
                        str(plugin_root / ('.kast-ide-hosted.detached-' + token)))
        receipt = replace_stage(receipt, Status.PREPARED, plugin)
        save(bundle / 'receipt.json', receipt)
    plugin = receipt.plugin
    candidate, backup = Path(plugin.candidate), Path(plugin.backup)
    if plugin.destination != str(destination):
        raise Rejected(Failure.OWNERSHIP)
    if destination.exists() and Identity.observe(destination) == plugin.candidateIdentity:
        return Report(Status.ACTIVE, [Failure.RESTART], str(retained_script(bundle)))
    if plugin.priorIdentity is not None and not backup.exists():
        if Identity.observe(physical(destination)) != plugin.priorIdentity:
            raise Rejected(Failure.OWNERSHIP)
        destination.rename(backup)
        sync(plugin_root)
    if os.path.lexists(destination) or Identity.observe(physical(candidate)) != plugin.candidateIdentity:
        raise Rejected(Failure.OWNERSHIP)
    candidate.rename(destination)
    sync(plugin_root)
    save(bundle / 'receipt.json', replace_stage(receipt, Status.ACTIVE))
    return Report(Status.ACTIVE, [Failure.RESTART], str(retained_script(bundle)))


def detach(root, dry_run):
    outer, bundle = location(root)
    receipt = load(bundle / 'receipt.json')
    validate(root, receipt)
    if dry_run:
        return Report(Status.PLANNED, [], str(retained_script(bundle)))
    # Fence is independent of payload admission and remains until explicit reinstall.
    fence = root / '.recovery-detached'
    if not os.path.lexists(fence):
        descriptor = os.open(fence, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.fsync(descriptor)
        os.close(descriptor)
        sync(root)
    elif not stat.S_ISREG(fence.lstat().st_mode):
        raise Rejected(Failure.OWNERSHIP)
    save(bundle / 'receipt.json', replace_stage(receipt, Status.UNRESOLVED))
    unresolved = [Failure.RETIREMENT]
    # Command links are only owned by this activation while current selects this version.
    current = Path(receipt.links[0].path)
    selector_matches = current.is_symlink() and os.readlink(current) == receipt.links[0].target
    selector_absent = not os.path.lexists(current)
    for link in reversed(receipt.links):
        path = Path(link.path)
        if not os.path.lexists(path):
            continue
        if (selector_matches or selector_absent) and path.is_symlink() and os.readlink(path) == link.target:
            Identity.observe(path)
            path.unlink()
            sync(path.parent)
        else:
            unresolved.append(Failure.OWNERSHIP)
    if receipt.plugin is not None:
        plugin = receipt.plugin
        destination, quarantine = Path(plugin.destination), Path(plugin.quarantine)
        physical(destination.parent)
        if quarantine.parent != destination.parent or not quarantine.name.startswith('.kast-ide-hosted.detached-'):
            raise Rejected(Failure.RECEIPT)
        if os.path.lexists(destination):
            if destination.is_dir() and not destination.is_symlink() and Identity.observe(destination) == plugin.candidateIdentity and not os.path.lexists(quarantine):
                destination.rename(quarantine)
                sync(destination.parent)
            else:
                unresolved.append(Failure.OWNERSHIP)
        unresolved.append(Failure.RESTART)
    # A missing/broken executable cannot establish retirement. Preserve state in place;
    # it may still be held open by an old process. No deletion or recursive state scan.
    if os.path.lexists(root / 'state'):
        unresolved.append(Failure.EVIDENCE)
    return Report(Status.UNRESOLVED, sorted(set(unresolved), key=lambda value: value.value), str(retained_script(bundle)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('prepare', 'activate-plugin', 'detach'))
    parser.add_argument('--installation', required=True, type=Path)
    parser.add_argument('--bin-directory', type=Path)
    parser.add_argument('--plugin-root', type=Path)
    parser.add_argument('--staged-plugin', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    arguments = parser.parse_args()
    try:
        outer, bundle = location(arguments.installation)
        if arguments.dry_run:
            if arguments.operation != 'detach':
                raise Rejected(Failure.RECEIPT)
            report = detach(arguments.installation, True)
        else:
            descriptor = os.open(outer / 'activation.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                    raise Rejected(Failure.OWNERSHIP)
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                if arguments.operation == 'prepare' and arguments.bin_directory is not None:
                    report = prepare(arguments.installation, arguments.bin_directory)
                elif arguments.operation == 'activate-plugin' and arguments.staged_plugin is not None and arguments.plugin_root is not None:
                    report = activate_plugin(arguments.installation, arguments.staged_plugin, arguments.plugin_root)
                elif arguments.operation == 'detach':
                    report = detach(arguments.installation, False)
                else:
                    raise Rejected(Failure.RECEIPT)
            finally:
                os.close(descriptor)
    except Rejected as rejected:
        report = Report(Status.BLOCKED, [rejected.failure], '')
    except (OSError, ValueError, TypeError, KeyError):
        report = Report(Status.BLOCKED, [Failure.FILESYSTEM], '')
    print(encode(report))
    return 0 if report.status in (Status.PREPARED, Status.PLANNED, Status.ACTIVE, Status.CLEAN) else 1


if __name__ == '__main__':
    raise SystemExit(main())
