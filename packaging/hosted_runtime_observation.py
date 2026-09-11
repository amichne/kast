"""Bounded observations of an owned fixture; never an absence-of-effects proof.

Artifact locations follow WorkspaceRuntimeControl and BrokerInstallationLayout.
IDE import counters recognize the pinned IDE's explicit log messages. They are
text observations, not complete import telemetry. No source, argv, log messages,
process IDs, or private paths are returned in reports.
"""
from dataclasses import dataclass
from enum import Enum
import hashlib
import os
from pathlib import Path
import re
import stat
import subprocess


class ObservationFailure(str, Enum):
    OWNERSHIP = 'ownership-unproven'
    BOUND = 'observation-bound-exceeded'
    IO = 'observation-io-failed'
    PROCESS = 'process-snapshot-unavailable'
    LOG_CHANGED = 'log-identity-or-prefix-changed'


class ObservationRejected(Exception):
    def __init__(self, failure):
        self.failure = failure
        super().__init__(failure.value)


@dataclass(frozen=True)
class LogCursor:
    generation: int
    device: int
    inode: int
    size: int
    digest: str


class OwnedRuntimeObserver:
    MAX_ENTRIES = 256
    MAX_BYTES = 4 * 1024 * 1024
    MAX_LOG_BYTES = 16 * 1024 * 1024

    def __init__(self, root: Path, product: Path, workspace: Path, ps: Path):
        self.root, self.product, self.workspace, self.ps = map(Path, (root, product, workspace, ps))
        self.cursor = None
        self.previous_artifacts = None
        self._admit_root()
        self._owned(self.product)
        self._owned(self.workspace)

    def _admit_root(self):
        info = self.root.lstat()
        if (not self.root.is_absolute() or self.root.resolve() != self.root
                or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o700):
            raise ObservationRejected(ObservationFailure.OWNERSHIP)

    def _owned(self, path):
        if not path.is_relative_to(self.root):
            raise ObservationRejected(ObservationFailure.OWNERSHIP)
        for current in (path, *path.parents):
            if current == self.root:
                break
            try:
                info = current.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(info.st_mode) or info.st_uid != os.getuid():
                raise ObservationRejected(ObservationFailure.OWNERSHIP)
        return path

    def _file(self, path, bound):
        self._owned(path)
        with path.open('rb') as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size > bound:
                raise ObservationRejected(ObservationFailure.BOUND)
            content = stream.read(bound + 1)
            after = os.fstat(stream.fileno())
        if len(content) > bound or (before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_ino, after.st_size, after.st_mtime_ns):
            raise ObservationRejected(ObservationFailure.IO)
        return before, content

    def _artifacts(self):
        inventories = {}
        for category in ('workers', 'run', 'broker'):
            location = self._owned(self.product / 'state' / category)
            records, pending, total = [], [location], 0
            while pending:
                path = self._owned(pending.pop())
                try:
                    info = path.lstat()
                except FileNotFoundError:
                    continue
                relative = str(path.relative_to(location))
                if stat.S_ISDIR(info.st_mode):
                    records.append((relative, 'directory', ''))
                    with os.scandir(path) as entries:
                        for entry in entries:
                            pending.append(Path(entry.path))
                            if len(pending) + len(records) > self.MAX_ENTRIES:
                                raise ObservationRejected(ObservationFailure.BOUND)
                elif stat.S_ISREG(info.st_mode):
                    _, content = self._file(path, self.MAX_BYTES - total)
                    total += len(content)
                    records.append((relative, 'file', hashlib.sha256(content).hexdigest()))
                elif stat.S_ISSOCK(info.st_mode):
                    records.append((relative, 'socket', ''))
                else:
                    raise ObservationRejected(ObservationFailure.OWNERSHIP)
            encoded = repr(sorted(records)).encode()
            inventories[category] = {'entries': len(records),
                'files': sum(kind == 'file' for _, kind, _ in records),
                'sockets': sum(kind == 'socket' for _, kind, _ in records),
                'sha256': hashlib.sha256(encoded).hexdigest()}
        return inventories

    def _imports(self, generation):
        path = self.root / 'ide/log/idea.log'
        info, content = self._file(path, self.MAX_LOG_BYTES)
        previous = self.cursor
        baseline = previous is None or previous.generation != generation
        offset = 0 if baseline else previous.size
        if not baseline and ((info.st_dev, info.st_ino) != (previous.device, previous.inode)
                or len(content) < offset or hashlib.sha256(content[:offset]).hexdigest() != previous.digest):
            raise ObservationRejected(ObservationFailure.LOG_CHANGED)
        # Only complete records are consumed; a partial append is carried to the next capture.
        end = content.rfind(b'\n') + 1
        window = content[offset:end].decode('utf-8', errors='strict')
        workspace = re.escape(str(self.workspace))
        resolution = re.compile(r'#c\.i\.o\.e\.u\.ExternalSystemUtil - External project \['
                                + workspace + r'\] resolution task executed in \d+ ms\.$')
        sync = re.compile(r'#c\.i\.w\.i\.i\.WorkspaceModelImpl - Workspace model updated in '
                          r'\d+ ms: The Gradle project sync$')
        self.cursor = LogCursor(generation, info.st_dev, info.st_ino, end,
                               hashlib.sha256(content[:end]).hexdigest())
        return {'scope': 'generation-startup-baseline' if baseline else 'since-previous-capture',
                'evidence': 'pinned-ide-log-text', 'bytesObserved': end - offset,
                'resolutionCompleted': sum(bool(resolution.search(line)) for line in window.splitlines()),
                'gradleModelSyncCompleted': sum(bool(sync.search(line)) for line in window.splitlines()),
                'absenceProven': False}

    def _processes(self, owned_pids):
        # Callers supply Popen identities they own. Only pid/ppid topology is read
        # globally; command lines of unrelated user processes are never requested.
        if not owned_pids or any(type(pid) is not int or pid <= 0 for pid in owned_pids):
            raise ObservationRejected(ObservationFailure.OWNERSHIP)
        result = subprocess.run([str(self.ps), '-axo', 'pid=,ppid='],
                                capture_output=True, text=True, timeout=5, check=True)
        if len(result.stdout) > self.MAX_BYTES:
            raise ObservationRejected(ObservationFailure.BOUND)
        parents = {}
        for line in result.stdout.splitlines():
            fields = line.split()
            if len(fields) != 2 or any(not field.isdecimal() for field in fields):
                raise ObservationRejected(ObservationFailure.PROCESS)
            parents[int(fields[0])] = int(fields[1])
        selected = set(owned_pids) & parents.keys()
        while True:
            expanded = selected | {pid for pid, parent in parents.items() if parent in selected}
            if expanded == selected:
                break
            selected = expanded
        if len(selected) > self.MAX_ENTRIES:
            raise ObservationRejected(ObservationFailure.BOUND)
        return {'evidence': 'owned-process-descendant-snapshot',
                'ownedRootsPresent': len(set(owned_pids) & parents.keys()),
                'descendantsPresent': len(selected - set(owned_pids)),
                'absenceProven': False,
                'limitations': ['short-lived-and-reparented-processes-not-covered']}

    def capture(self, *, generation: int, owned_pids: tuple[int, ...]):
        """Return a bounded report. Rejection never becomes a zero observation."""
        previous_cursor = self.cursor
        try:
            if type(generation) is not int or generation < 1:
                raise ObservationRejected(ObservationFailure.OWNERSHIP)
            self._admit_root()
            artifacts = self._artifacts()
            changed = [] if self.previous_artifacts is None else [
                key for key, value in artifacts.items() if self.previous_artifacts[key] != value]
            imports = self._imports(generation)
            processes = self._processes(owned_pids)
            report = {'outcome': 'observed', 'generation': generation,
                'artifacts': {'evidence': 'private-state-inventory', 'categories': artifacts,
                    'scope': 'baseline' if self.previous_artifacts is None else 'since-previous-capture',
                    'changedCategories': changed, 'absenceProven': False},
                'imports': imports, 'processes': processes,
                'structuralRouteProof': 'separate-required-evidence'}
            self.previous_artifacts = artifacts
            return report
        except ObservationRejected as error:
            self.cursor = previous_cursor
            return {'outcome': 'unqualified', 'reason': error.failure.value}
        except (OSError, UnicodeError, ValueError, subprocess.SubprocessError):
            self.cursor = previous_cursor
            return {'outcome': 'unqualified', 'reason': ObservationFailure.IO.value}
