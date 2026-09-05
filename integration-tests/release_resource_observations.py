#!/usr/bin/env python3
"""Bounded resource samples for explicitly owned installed state.

Call observe() after a semantic command; the enclosing release receipt owns source
and archive identity. RSS is a sample, never a continuous peak or a performance
threshold. Disk bytes are apparent non-directory entry sizes from lstat; links
are counted as links and never traversed. Hard links count once per selected
entry, not once per physical allocation. No command line or file payload leaves
this boundary. A not-running sample reports the observed absence reason, not RSS 0.

Select canonical absolute state roots under owner_root, excluding workspace source.
For an enterprise host use (installation, host.runtime) and cache_root =
host.runtime / 'intellij-caches'. Missing selected roots contribute zero disk bytes.
The sampler's count/time limits bound observation work, not product resource use.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess
import time
from typing import Callable


MAX_PS_BYTES = 65_536
PS_TIMEOUT_SECONDS = 2
MAX_CACHE_IDENTITIES = 64
MAX_STATE_ENTRIES = 200_000
MAX_STATE_DEPTH = 64
DISK_TIMEOUT_SECONDS = 5
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


class ResourceStatus(str, Enum):
    OBSERVED = "observed"
    NOT_RUNNING = "not-running"
    REJECTED = "rejected"


class ResourceStage(str, Enum):
    AFTER_START = "after-start"
    AFTER_READ = "after-read"
    AFTER_RESTART = "after-restart"
    AFTER_STOP = "after-stop"


class Cause(str, Enum):
    SCOPE_INVALID = "scope-invalid"
    STATE_ROOTS_OVERLAP = "state-roots-overlap"
    CACHE_LIMIT = "cache-limit"
    PID_ABSENT = "pid-marker-absent"
    PROCESS_NOT_LISTED = "process-not-listed"
    PID_INVALID = "pid-marker-invalid"
    PID_CHANGED = "pid-marker-changed"
    PS_FAILED = "ps-failed"
    PS_TIMEOUT = "ps-timeout"
    PS_OUTPUT_LIMIT = "ps-output-limit"
    PS_RECORD_INVALID = "ps-record-invalid"
    OWNERSHIP_MISMATCH = "process-ownership-mismatch"
    SYSTEM_PATH_UNREPRESENTABLE = "system-path-unrepresentable"
    DISK_LIMIT = "disk-observation-limit"
    FILESYSTEM_UNAVAILABLE = "filesystem-unavailable"


class ObservationRejected(Exception):
    def __init__(self, cause: Cause):
        self.cause = cause
        super().__init__(cause.value)


class _ProcessAbsent(Enum):
    NOT_LISTED = "not-listed"


@dataclass(frozen=True)
class _ResidentBytes:
    value: int


@dataclass(frozen=True)
class _PsOutput:
    returncode: int
    stdout: bytes


def _run_ps(pid: int) -> _PsOutput:
    """Read only one requested PID; cap bytes while reading, with no stderr payload."""
    try:
        process = subprocess.Popen(
            ['/bin/ps', '-ww', '-p', str(pid), '-o', 'pid=,rss=,command='],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
    except OSError:
        raise ObservationRejected(Cause.PS_FAILED) from None
    output = bytearray()
    deadline = time.monotonic() + PS_TIMEOUT_SECONDS
    try:
        assert process.stdout is not None
        with selectors.DefaultSelector() as ready:
            ready.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not ready.select(remaining):
                    raise ObservationRejected(Cause.PS_TIMEOUT)
                block = os.read(process.stdout.fileno(), min(4096, MAX_PS_BYTES - len(output) + 1))
                if not block:
                    break
                output.extend(block)
                if len(output) > MAX_PS_BYTES:
                    raise ObservationRejected(Cause.PS_OUTPUT_LIMIT)
        try:
            code = process.wait(timeout=max(0.001, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            raise ObservationRejected(Cause.PS_TIMEOUT) from None
        return _PsOutput(code, bytes(output))
    except OSError:
        raise ObservationRejected(Cause.PS_FAILED) from None
    finally:
        try:
            if process.poll() is None:
                process.kill()  # Only the ps child created by this boundary.
            process.wait(timeout=PS_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            raise ObservationRejected(Cause.PS_TIMEOUT) from None
        except OSError:
            raise ObservationRejected(Cause.PS_FAILED) from None
        finally:
            if process.stdout is not None:
                process.stdout.close()


def _scope(owner: Path, cache: Path, roots: tuple[Path, ...]) -> tuple[Path, Path, tuple[Path, ...]]:
    if not owner.is_absolute() or not roots or len(roots) > 8:
        raise ObservationRejected(Cause.SCOPE_INVALID)
    owner = owner.resolve(strict=True)
    if not owner.is_dir():
        raise ObservationRejected(Cause.SCOPE_INVALID)
    for path in (cache, *roots):
        if not path.is_absolute() or '..' in path.parts or path == owner or not path.is_relative_to(owner):
            raise ObservationRejected(Cause.SCOPE_INVALID)
        cursor = owner
        for part in path.relative_to(owner).parts:
            cursor /= part
            if cursor.is_symlink():
                raise ObservationRejected(Cause.SCOPE_INVALID)
    for index, root in enumerate(roots):
        if any(root.is_relative_to(other) or other.is_relative_to(root) for other in roots[index + 1:]):
            raise ObservationRejected(Cause.STATE_ROOTS_OVERLAP)
    if not any(cache.is_relative_to(root) for root in roots):
        raise ObservationRejected(Cause.SCOPE_INVALID)
    return owner, cache, roots


def _directory(owner: Path, path: Path) -> int:
    """Open every owned path component without following symlinks, including races."""
    descriptor = os.open(owner, DIRECTORY_FLAGS)
    try:
        for part in path.relative_to(owner).parts:
            child = os.open(part, DIRECTORY_FLAGS, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _pid(system_descriptor: int) -> int:
    try:
        descriptor = os.open('.pid', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=system_descriptor)
    except FileNotFoundError:
        raise
    except OSError:
        raise ObservationRejected(Cause.PID_INVALID) from None
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 32:
            raise ObservationRejected(Cause.PID_INVALID)
        payload = os.read(descriptor, 33)
        if len(payload) > 32:
            raise ObservationRejected(Cause.PID_INVALID)
        value = payload.strip()
        if not re.fullmatch(rb'[1-9][0-9]{0,9}', value) or int(value) > 2_147_483_647:
            raise ObservationRejected(Cause.PID_INVALID)
        return int(value)
    finally:
        os.close(descriptor)


def _rss(pid: int, system: Path, output: _PsOutput) -> _ResidentBytes | _ProcessAbsent:
    if len(output.stdout) > MAX_PS_BYTES:
        raise ObservationRejected(Cause.PS_OUTPUT_LIMIT)
    if output.returncode == 1 and not output.stdout.strip():
        return _ProcessAbsent.NOT_LISTED
    if output.returncode != 0:
        raise ObservationRejected(Cause.PS_FAILED)
    try:
        line = output.stdout.decode('utf-8').strip()
    except UnicodeDecodeError:
        raise ObservationRejected(Cause.PS_RECORD_INVALID) from None
    parts = line.split(maxsplit=2)
    if (len(parts) != 3 or '\n' in line or not re.fullmatch(r'[0-9]{1,10}', parts[0])
            or int(parts[0]) != pid or not re.fullmatch(r'[0-9]{1,16}', parts[1])):
        raise ObservationRejected(Cause.PS_RECORD_INVALID)
    if any(character.isspace() for character in str(system)):
        raise ObservationRejected(Cause.SYSTEM_PATH_UNREPRESENTABLE)
    arguments = parts[2].split()
    markers = [argument for argument in arguments if argument.startswith('-Didea.system.path=')]
    if markers != [f'-Didea.system.path={system}']:
        raise ObservationRejected(Cause.OWNERSHIP_MISMATCH)
    # macOS ps(1): rss is resident set size in 1024-byte units.
    return _ResidentBytes(int(parts[1]) * 1024)


def _memory(owner: Path, cache: Path, reader: Callable[[int], _PsOutput]) -> dict:
    found = []
    markers = 0
    try:
        cache_descriptor = _directory(owner, cache)
    except FileNotFoundError:
        return {'status': ResourceStatus.NOT_RUNNING.value, 'cause': Cause.PID_ABSENT.value}
    try:
        with os.scandir(cache_descriptor) as entries:
            names = []
            for entry in entries:
                names.append(entry.name)
                if len(names) > MAX_CACHE_IDENTITIES:
                    raise ObservationRejected(Cause.CACHE_LIMIT)
        for name in sorted(names):
            metadata = os.stat(name, dir_fd=cache_descriptor, follow_symlinks=False)
            if stat.S_ISLNK(metadata.st_mode):
                raise ObservationRejected(Cause.SCOPE_INVALID)
            if not stat.S_ISDIR(metadata.st_mode):
                continue
            system = cache / name / 'system'
            try:
                system_descriptor = _directory(owner, system)
            except FileNotFoundError:
                continue
            try:
                try:
                    pid = _pid(system_descriptor)
                except FileNotFoundError:
                    continue
                markers += 1
                rss = _rss(pid, system, reader(pid))
                try:
                    unchanged = _pid(system_descriptor) == pid
                except FileNotFoundError:
                    unchanged = False
                if not unchanged:
                    raise ObservationRejected(Cause.PID_CHANGED)
                if isinstance(rss, _ResidentBytes):
                    found.append(rss.value)
            finally:
                os.close(system_descriptor)
    finally:
        os.close(cache_descriptor)
    if not found:
        return {'status': ResourceStatus.NOT_RUNNING.value,
                'cause': (Cause.PROCESS_NOT_LISTED if markers else Cause.PID_ABSENT).value}
    return {'status': ResourceStatus.OBSERVED.value, 'rssBytes': sum(found), 'processCount': len(found)}


def _disk(owner: Path, roots: tuple[Path, ...]) -> dict:
    deadline = time.monotonic() + DISK_TIMEOUT_SECONDS
    count = total = symlinks = 0
    def visit(descriptor: int, depth: int) -> None:
        nonlocal count, total, symlinks
        if depth > MAX_STATE_DEPTH:
            raise ObservationRejected(Cause.DISK_LIMIT)
        with os.scandir(descriptor) as entries:
            for entry in entries:
                count += 1
                if count > MAX_STATE_ENTRIES or time.monotonic() > deadline:
                    raise ObservationRejected(Cause.DISK_LIMIT)
                metadata = entry.stat(follow_symlinks=False)
                if stat.S_ISDIR(metadata.st_mode):
                    child = os.open(entry.name, DIRECTORY_FLAGS, dir_fd=descriptor)
                    try:
                        visit(child, depth + 1)
                    finally:
                        os.close(child)
                else:
                    total += metadata.st_size
                    symlinks += int(stat.S_ISLNK(metadata.st_mode))
    for root in roots:
        try:
            descriptor = _directory(owner, root)
        except FileNotFoundError:
            continue
        try:
            visit(descriptor, 0)
        finally:
            os.close(descriptor)
    return {'apparentStateBytes': total, 'stateEntryCount': count,
            'symlinkCount': symlinks, 'selectedStateRootCount': len(roots)}


def observe(stage: ResourceStage, *, owner_root: Path, cache_root: Path,
            state_roots: tuple[Path, ...],
            process_reader: Callable[[int], _PsOutput] = _run_ps) -> dict:
    """Return observed/not-running/rejected finite data; never serialize process arguments.

    A missing marker or unlisted PID leaves rssBytes absent. Rejection never emits
    partial numerical evidence. Multiple proven owned processes contribute their
    summed sampled RSS, with processCount making that aggregation explicit.
    """
    document = {'schemaVersion': 1, 'stage': stage.value}
    try:
        owner, cache, roots = _scope(owner_root, cache_root, state_roots)
        memory = _memory(owner, cache, process_reader)
        disk = _disk(owner, roots)
        return {**document, **memory, **disk}
    except ObservationRejected as failure:
        return {**document, 'status': ResourceStatus.REJECTED.value, 'cause': failure.cause.value}
    except OSError:
        return {**document, 'status': ResourceStatus.REJECTED.value, 'cause': Cause.FILESYSTEM_UNAVAILABLE.value}
