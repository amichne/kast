"""Compare installer-owned files with the original release archive bytes."""
from enum import Enum
import hashlib
from pathlib import PurePosixPath
import stat
import tarfile
import zipfile

from acceptance_idea import digest


class PayloadFailure(Enum):
    FILE = 'release-file-rejected'
    INVENTORY = 'release-inventory-rejected'
    CONTROL_BOUND = 'release-control-bound-rejected'
    CONTROL_ENTRY = 'release-control-entry-rejected'
    CONTROL_IDENTITY = 'release-control-identity-rejected'
    CONTROL_MODE = 'release-control-mode-rejected'
    PLUGIN_BOUND = 'release-plugin-bound-rejected'
    PLUGIN_ENTRY = 'release-plugin-entry-rejected'
    PLUGIN_IDENTITY = 'release-plugin-identity-rejected'
    PLUGIN_INVENTORY = 'release-plugin-inventory-rejected'


class PayloadRejected(ValueError):
    def __init__(self, failure: PayloadFailure):
        self.failure = failure
        super().__init__(failure.value)


def _file(root, name):
    relative = PurePosixPath(name)
    candidate = root / relative
    if (not relative.parts or relative.is_absolute() or '..' in relative.parts or '\\' in name
            or candidate.resolve() != candidate or not candidate.is_file() or candidate.is_symlink()):
        raise PayloadRejected(PayloadFailure.FILE)
    return candidate


def verify_payloads(product, plugins, control, plugin, manifest):
    verify_control(product, control, manifest)
    verify_plugin(plugins, plugin)


def verify_control(product, control, manifest):
    inventory = manifest['payloadFiles']
    if not isinstance(inventory, list) or not 1 <= len(inventory) <= 16384:
        raise PayloadRejected(PayloadFailure.INVENTORY)
    names = set()
    for item in inventory:
        path = _file(product, item['path'])
        if (item['path'] in names or PurePosixPath(item['path']).parts[0] not in ('bin', 'lib', 'share')
                or item['sha256'] != 'sha256:' + digest(path) or item['mode'] != path.stat().st_mode & 0o777):
            raise PayloadRejected(PayloadFailure.INVENTORY)
        names.add(item['path'])
    actual = set()
    for directory in ('bin', 'lib', 'share'):
        for path in (product / directory).rglob('*'):
            if path.is_symlink():
                raise PayloadRejected(PayloadFailure.INVENTORY)
            if path.is_file():
                actual.add(path.relative_to(product).as_posix())
            if len(actual) > 16384:
                raise PayloadRejected(PayloadFailure.INVENTORY)
    if names != actual or 'bin/kast-complete' not in names:
        raise PayloadRejected(PayloadFailure.INVENTORY)
    with tarfile.open(control, 'r:gz') as archive:
        total, seen = 0, set()
        for index, member in enumerate(archive):
            total += member.size
            if index >= 16384 or total > 1073741824 or member.name in seen:
                raise PayloadRejected(PayloadFailure.CONTROL_BOUND)
            seen.add(member.name)
            if member.isdir():
                continue
            if not member.isreg():
                raise PayloadRejected(PayloadFailure.CONTROL_ENTRY)
            path = _file(product, member.name)
            with archive.extractfile(member) as stream:
                if digest(path) != hashlib.file_digest(stream, 'sha256').hexdigest():
                    raise PayloadRejected(PayloadFailure.CONTROL_IDENTITY)
            if path.stat().st_mode & 0o777 != member.mode & 0o777:
                raise PayloadRejected(PayloadFailure.CONTROL_MODE)


def verify_plugin(plugins, plugin):
    with zipfile.ZipFile(plugin) as archive:
        members = archive.infolist()
        if not members or len(members) > 16384 or sum(item.file_size for item in members) > 256 * 1024 * 1024:
            raise PayloadRejected(PayloadFailure.PLUGIN_BOUND)
        names = set()
        for member in members:
            if member.is_dir():
                continue
            if (member.filename in names or stat.S_ISLNK(member.external_attr >> 16)
                    or PurePosixPath(member.filename).parts[0] != 'kast-ide-hosted'):
                raise PayloadRejected(PayloadFailure.PLUGIN_ENTRY)
            names.add(member.filename)
            path = _file(plugins, member.filename)
            with archive.open(member) as stream:
                if digest(path) != hashlib.file_digest(stream, 'sha256').hexdigest():
                    raise PayloadRejected(PayloadFailure.PLUGIN_IDENTITY)
        actual = {path.relative_to(plugins).as_posix() for path in (plugins / 'kast-ide-hosted').rglob('*') if path.is_file()}
        if names != actual:
            raise PayloadRejected(PayloadFailure.PLUGIN_INVENTORY)
