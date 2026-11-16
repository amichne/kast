"""Retire admitted Kast versions and review historical artifacts one at a time.

The selected installation and its recovery evidence are never candidates. A
review answer authorizes only the exact observed entry, not its name or parent.
"""
from dataclasses import asdict, dataclass, replace
from enum import Enum
from pathlib import Path
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
import plistlib
import re
import signal
import stat
import subprocess
import sys
import time
from typing import Optional


def load_neighbor(name, module_name):
    spec = importlib.util.spec_from_file_location(module_name, Path(__file__).with_name(name))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


lifecycle = load_neighbor('installation-lifecycle.py', 'kast_prune_lifecycle')
recovery = load_neighbor('installation-recovery.py', 'kast_prune_recovery')
MAX_CANDIDATES = 1024
MAX_DIRECTORY_ENTRIES = 4096
MAX_PROCESS_OUTPUT = 8388608
KAST_MAINS = ('KastCliMainKt', 'KastMcpMain', 'KastCodexMainKt')


class Kind(str, Enum):
    PROCESS = 'process'
    LOGIN = 'login-item'
    VERSION = 'version'
    RECOVERY = 'recovery-bundle'
    PLUGIN = 'plugin-backup'
    SELECTOR = 'old-selector'
    ANCHOR = 'prior-anchor'


class RetentionReason(str, Enum):
    DECLINED = 'DECLINED'
    OWNER_UNPROVEN = 'OWNER_UNPROVEN'
    PROCESS_ACTIVE = 'PROCESS_ACTIVE'
    PROCESS_UNPROVEN = 'PROCESS_UNPROVEN'
    LOGIN_UNPROVEN = 'LOGIN_UNPROVEN'
    ENTRY_CHANGED = 'ENTRY_CHANGED'


class CleanupStatus(str, Enum):
    COMPLETE = 'complete'
    RETAINED = 'retained'


@dataclass(frozen=True)
class Identity:
    device: int
    inode: int
    owner: int

    @staticmethod
    def observe(path):
        value = path.lstat()
        return Identity(value.st_dev, value.st_ino, value.st_uid)


@dataclass(frozen=True)
class Candidate:
    kind: Kind
    path: str
    reason: str
    identity: Identity
    pid: Optional[int] = None
    command: Optional[str] = None


@dataclass(frozen=True)
class Report:
    status: CleanupStatus
    removed: list[str]
    retained: list['Retained']


@dataclass(frozen=True)
class Retained:
    kind: Kind
    path: str
    reason: RetentionReason


def entries(directory):
    if not directory.exists():
        return []
    if directory.is_symlink() or not directory.is_dir() or directory.stat().st_uid != os.getuid():
        raise ValueError('managed directory ownership is unproven')
    with os.scandir(directory) as stream:
        found = sorted((Path(entry.path) for entry in stream), key=lambda path: path.name)
    if len(found) > MAX_DIRECTORY_ENTRIES:
        raise ValueError('managed directory candidate limit exceeded')
    return found


def candidate(kind, path, reason):
    identity = Identity.observe(path)
    if identity.owner != os.getuid():
        reason = 'OWNER_UNPROVEN; ' + reason
    return Candidate(kind, str(path), reason, identity)


def process_rows(raw):
    if len(raw) > MAX_PROCESS_OUTPUT:
        raise ValueError('process observation limit exceeded')
    result = []
    for line in raw.decode('utf-8', errors='replace').splitlines():
        fields = line.strip().split(None, 2)
        if len(fields) != 3 or not fields[0].isdigit() or not fields[1].isdigit():
            continue
        result.append((int(fields[0]), int(fields[1]), fields[2]))
    return result


def owned_processes(outer, selected, rows):
    prefix = str(outer / 'versions') + '/'
    result = []
    for pid, uid, command in rows:
        if not any(
                token.rsplit('.', 1)[-1] in KAST_MAINS for token in command.split() if '/' not in token):
            continue
        roots = set(re.findall(re.escape(prefix) + r'([^\s/:]+)', command))
        if not roots or selected.name in roots:
            continue
        reason = ', '.join(str(outer / 'versions' / root) for root in sorted(roots))
        if uid != os.getuid():
            reason = 'OWNER_UNPROVEN; ' + reason
        result.append(Candidate(Kind.PROCESS, f'pid:{pid}', reason,
                                Identity(0, pid, uid), pid, command))
    return result


def observe_processes():
    output = subprocess.run(['ps', '-axo', 'pid=,uid=,command='], check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10).stdout
    return process_rows(output)


def current_receipt(selected):
    path = recovery.location(selected)[1] / 'receipt.json'
    receipt = recovery.load(path)
    recovery.validate(selected, receipt)
    if isinstance(receipt, recovery.LegacyReceipt) and receipt.priorInstallation is not None:
        raise ValueError('selected recovery still depends on a prior installation')
    return receipt


def service_label(selected):
    return 'io.github.amichne.kast.broker.' + hashlib.sha256(str(selected).encode()).hexdigest()[:32]


def plan(selected, rows):
    admitted = lifecycle.Installation.admit(str(selected))
    lifecycle.require_selected(admitted)
    outer = lifecycle.Installation.admit(str(selected)).managed_root
    if outer.is_symlink() or outer.stat().st_uid != os.getuid():
        raise ValueError('installation parent ownership is unproven')
    receipt = current_receipt(selected)
    protected = {str(selected), str(outer / 'recovery' / selected.name), str(outer / 'recovery/replacement')}
    protected.update(anchor.get('path') for anchor in admitted.manifest['externalAnchors']
                     if isinstance(anchor, dict) and isinstance(anchor.get('path'), str))
    if receipt.plugin is not None:
        protected.update((receipt.plugin.destination, receipt.plugin.candidate,
                          receipt.plugin.backup, receipt.plugin.quarantine))
    processes = owned_processes(outer, selected, rows)
    planned = []
    legacy_roots = []
    for path in entries(outer / 'versions'):
        if path == selected:
            continue
        legacy_roots.append(path)
        try:
            prior = lifecycle.Installation.admit(str(path))
            lifecycle.execute(prior, 'remove', True)
            reason = 'admitted installation'
        except lifecycle.Rejected as rejected:
            reason = rejected.failure.value
        except (OSError, ValueError, TypeError, KeyError):
            reason = lifecycle.Failure.FILESYSTEM_REJECTED.value
        planned.append(candidate(Kind.VERSION, path, reason))
    for path in entries(outer / 'recovery'):
        if str(path) not in protected:
            planned.append(candidate(Kind.RECOVERY, path, 'prior recovery evidence'))
    if receipt.plugin is not None:
        plugin_root = Path(receipt.plugin.destination).parent.parent / '.kast-plugin-recovery'
        for path in entries(plugin_root):
            if str(path) not in protected and 'kast' in path.name.lower():
                planned.append(candidate(Kind.PLUGIN, path, 'unselected plugin backup'))
    agents = Path(os.environ['HOME']) / 'Library/LaunchAgents'
    selected_label = service_label(selected)
    for path in entries(agents):
        if 'kast' in path.name.lower() and not path.name.startswith(selected_label + '.'):
            planned.append(candidate(Kind.LOGIN, path, 'unselected Kast login item'))
    for path in entries(outer):
        if path.name.startswith('.replaced-current-'):
            planned.append(candidate(Kind.SELECTOR, path, 'old Kast selector'))
    known = {item.path for item in planned} | protected
    for root in legacy_roots:
        try:
            document = lifecycle.read_json(root / 'installation.json',
                                           lifecycle.CONTROL_MANIFEST_MAXIMUM_BYTES,
                                           lifecycle.Failure.MANIFEST_REJECTED)
        except lifecycle.Rejected:
            continue
        anchors = document.get('externalAnchors')
        if not isinstance(anchors, list) or len(anchors) > 16:
            continue
        for anchor in anchors:
            if not isinstance(anchor, dict) or anchor.get('kind') not in {
                    'socket-alias', 'upstream-directory', 'command', 'codex-command', 'login'}:
                continue
            raw = anchor.get('path')
            if not isinstance(raw, str):
                continue
            path = Path(raw)
            if (not path.is_absolute() or 'kast' not in path.name.lower() or
                    str(path) in known or not os.path.lexists(path)):
                continue
            try:
                if path.resolve(strict=False).is_relative_to(selected):
                    continue
            except (OSError, RuntimeError):
                continue
            planned.append(candidate(Kind.ANCHOR, path, 'listed by prior Kast manifest'))
            known.add(str(path))
    if len(planned) + len(processes) > MAX_CANDIDATES:
        raise ValueError('cleanup candidate limit exceeded')
    return admitted, processes, planned


def prompt(item, reader, writer):
    writer.write(f'Kast cleanup: {item.kind.value}: {item.path}\n')
    writer.write(f'  Evidence: {item.reason}\n')
    action = 'Stop this exact process' if item.kind is Kind.PROCESS else 'Remove this exact item'
    writer.write(f'  {action}? Type yes to approve: ')
    writer.flush()
    return reader.readline().strip() == 'yes'


def remove_entry(item):
    path = Path(item.path)
    if Identity.observe(path) != item.identity or item.identity.owner != os.getuid():
        raise ValueError('candidate changed after review')
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.is_dir():
        lifecycle.delete_tree(path)
    else:
        raise ValueError('candidate file type is unsupported')


def launchd_labels():
    if sys.platform != 'darwin':
        return {}
    result = subprocess.run(['launchctl', 'list'], check=True, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=10)
    if len(result.stdout) > MAX_PROCESS_OUTPUT:
        raise ValueError('login service observation limit exceeded')
    labels = {}
    for line in result.stdout.decode('utf-8', errors='replace').splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[2].startswith('io.github.amichne.kast.broker.'):
            labels[fields[2]] = int(fields[0]) if fields[0].isdigit() else None
    return labels


def bootout(label):
    if sys.platform != 'darwin' or label not in launchd_labels():
        return
    result = subprocess.run(['launchctl', 'bootout', f'gui/{os.getuid()}/{label}'],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
    if result.returncode != 0 or label in launchd_labels():
        raise ValueError('reviewed login service did not retire')


def remove_login(item, selected):
    path = Path(item.path)
    if path.is_symlink() or not path.is_file() or Identity.observe(path) != item.identity:
        raise ValueError('login item changed after review')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as source:
        observed = os.fstat(source.fileno())
        if Identity(observed.st_dev, observed.st_ino, observed.st_uid) != item.identity:
            raise ValueError('login item changed while reading')
        raw = source.read(65537)
    if len(raw) > 65536:
        raise ValueError('login item exceeds review limit')
    document = plistlib.loads(raw)
    label = document.get('Label') if isinstance(document, dict) else None
    if not isinstance(label, str) or not label.startswith('io.github.amichne.kast.broker.'):
        raise ValueError('login service label is unproven')
    if label in {service_label(selected), service_label(selected) + '.login'}:
        raise ValueError('selected login service is protected')
    bootout(label)
    remove_entry(item)


def stop_process(item, outer, selected):
    if item.identity.owner != os.getuid():
        raise ValueError('process owner is unproven')
    rows = observe_processes()
    current = owned_processes(outer, selected, rows)
    if item not in current:
        if any(pid == item.pid for pid, _, _ in rows):
            raise ValueError('process identity changed after review')
        return
    labels = [label for label, pid in launchd_labels().items() if pid == item.pid]
    if len(labels) > 1:
        raise ValueError('process has ambiguous login ownership')
    if labels and labels[0] in {service_label(selected), service_label(selected) + '.login'}:
        raise ValueError('selected login service is protected')
    if labels:
        bootout(labels[0])
    else:
        os.kill(item.pid, signal.SIGTERM)
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if item not in owned_processes(outer, selected, observe_processes()):
            return
        time.sleep(0.1)
    raise ValueError('reviewed process did not retire')


def process_holds_version(item, rows):
    path = Path(item.path)
    roots = [path]
    relocated = re.fullmatch(
        r'\.replaced-(.+)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',
        path.name)
    if relocated:
        roots.append(path.with_name(relocated.group(1)))
    return any(re.search(re.escape(str(root)) + r'(?=/|:|\s|$)', command)
               for _, _, command in rows for root in roots)


def run(selected, reader=None, writer=None):
    outer = lifecycle.Installation.admit(str(selected)).managed_root
    lock = outer / 'activation.lock'
    descriptor = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise ValueError('activation lock is not regular')
        fcntl.lockf(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        admitted, processes, planned = plan(selected, observe_processes())
        removed, retained = [], []
        def keep(item, reason):
            retained.append(Retained(item.kind, item.path, reason))
        for item in planned:
            if item.kind is not Kind.LOGIN:
                continue
            lifecycle.require_selected(admitted)
            if reader is None or not prompt(item, reader, writer):
                keep(item, RetentionReason.DECLINED)
                continue
            try:
                remove_login(item, selected)
            except (OSError, ValueError, plistlib.InvalidFileException, subprocess.SubprocessError):
                keep(item, RetentionReason.LOGIN_UNPROVEN)
            else:
                removed.append(item.path)
        for item in processes:
            if reader is None or not prompt(item, reader, writer):
                keep(item, RetentionReason.DECLINED)
                continue
            try:
                stop_process(item, outer, selected)
            except (OSError, ValueError, subprocess.SubprocessError):
                keep(item, RetentionReason.PROCESS_UNPROVEN)
            else:
                removed.append(item.path)
        for item in planned:
            if item.kind is Kind.LOGIN:
                continue
            lifecycle.require_selected(admitted)
            if item.kind is Kind.ANCHOR and any(row.kind is Kind.PROCESS for row in retained):
                keep(item, RetentionReason.PROCESS_ACTIVE)
                continue
            if item.kind is Kind.VERSION:
                if process_holds_version(item, observe_processes()):
                    keep(item, RetentionReason.PROCESS_ACTIVE)
                    continue
                if item.reason == 'admitted installation':
                    try:
                        prior = lifecycle.Installation.admit(item.path)
                        lifecycle.execute(prior, 'remove', False)
                    except lifecycle.Rejected as rejected:
                        item = replace(item, reason=rejected.failure.value)
                    except (OSError, ValueError, TypeError, KeyError):
                        item = replace(item, reason=lifecycle.Failure.FILESYSTEM_REJECTED.value)
                    else:
                        removed.append(item.path)
                        continue
            if reader is None or not prompt(item, reader, writer):
                keep(item, RetentionReason.DECLINED)
                continue
            if item.kind is Kind.VERSION and process_holds_version(item, observe_processes()):
                keep(item, RetentionReason.PROCESS_ACTIVE)
                continue
            try:
                remove_entry(item)
            except (OSError, ValueError, lifecycle.Rejected):
                keep(item, RetentionReason.OWNER_UNPROVEN if item.identity.owner != os.getuid()
                     else RetentionReason.ENTRY_CHANGED)
            else:
                removed.append(item.path)
        lifecycle.require_selected(admitted)
        return Report(CleanupStatus.COMPLETE if not retained else CleanupStatus.RETAINED, removed, retained)
    finally:
        os.close(descriptor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--installation', required=True, type=Path)
    arguments = parser.parse_args()
    reader = writer = None
    try:
        try:
            terminal = open('/dev/tty', 'r+', encoding='utf-8')
        except OSError:
            terminal = None
        if terminal is not None:
            reader = writer = terminal
        report = run(arguments.installation, reader, writer)
        print(json.dumps(asdict(report), separators=(',', ':')))
        if report.retained:
            print(f'kast-install: {len(report.retained)} prior Kast entries retained for review', file=sys.stderr)
        return 0
    except (OSError, ValueError, lifecycle.Rejected, recovery.Rejected) as error:
        failure = getattr(error, 'failure', None)
        print(json.dumps({'status': 'rejected', 'failure': getattr(failure, 'value', 'CLEANUP_UNPROVEN')}))
        return 1
    finally:
        if reader is not None:
            reader.close()


if __name__ == '__main__':
    raise SystemExit(main())
