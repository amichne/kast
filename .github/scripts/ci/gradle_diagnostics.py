"""Bounded thread evidence for a Gradle daemon associated with this invocation."""

from __future__ import annotations

import os
import re
import selectors
import signal
import subprocess
import tempfile
import time
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path

OWNER_KEY = "KAST_CI_GRADLE_INVOCATION"
PROCESS_LIMIT = 1024 * 1024
DUMP_LIMIT = 2 * 1024 * 1024
DAEMON = b"org.gradle.launcher.daemon.bootstrap.GradleDaemon"


class Outcome(StrEnum):
    NOT_REQUESTED = "NOT_REQUESTED"
    RETAINED = "RETAINED"
    UNAVAILABLE = "UNAVAILABLE"
    TIMED_OUT = "TIMED_OUT"
    TRUNCATED = "TRUNCATED"
    ATTACH_FAILED = "ATTACH_FAILED"
    IDENTITY_UNCONFIRMED = "IDENTITY_UNCONFIRMED"


@dataclass(frozen=True, slots=True)
class Captured:
    output: bytes
    returncode: int


@dataclass(slots=True)
class DaemonPids:
    """Retain at most two candidates and a bounded fragment across streamed chunks."""

    _values: set[int] = field(default_factory=set[int])
    _tail: bytes = b""
    _rejected: bool = False

    def observe(self, block: bytes) -> None:
        if self._rejected or len(self._values) > 1:
            return
        data = self._tail + block
        for value in re.findall(
            rb"The client will now receive all logging from the daemon \(pid: ([0-9]+)\)", data
        ):
            if len(value) > 10 or not 1 < int(value) <= 2147483647:
                self._rejected = True
                break
            self._values.add(int(value))
            if len(self._values) > 1:
                break
        self._tail = data[-128:]

    def single(self) -> int | None:
        if self._rejected or len(self._values) != 1:
            return None
        return next(iter(self._values))


def capture(command: tuple[str, ...], *, seconds: float, limit: int) -> Captured | Outcome:
    """Bound time and bytes while reading; never spool unbounded process output."""
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    except OSError:
        return Outcome.UNAVAILABLE
    output = bytearray()
    deadline = time.monotonic() + seconds
    stream = process.stdout
    try:
        if stream is None:
            return Outcome.UNAVAILABLE
        with selectors.DefaultSelector() as selector:
            selector.register(stream, selectors.EVENT_READ)
            ended = False
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return Outcome.TIMED_OUT
                for _key, _events in selector.select(min(remaining, 0.05)):
                    block = os.read(stream.fileno(), min(65536, limit + 1 - len(output)))
                    if block:
                        output.extend(block)
                        if len(output) > limit:
                            return Outcome.TRUNCATED
                    else:
                        selector.unregister(stream)
                        ended = True
                code = process.poll()
                if ended and code is not None:
                    return Captured(bytes(output), code)
    except OSError:
        return Outcome.UNAVAILABLE
    finally:
        # The helper's own group may outlive its leader. Never signal the observed daemon.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
        if stream is not None:
            stream.close()


def identity(pid: int, token: str) -> bytes | Outcome:
    observed = capture(
        ("ps", "eww", "-p", str(pid), "-o", "lstart=,command="),
        seconds=2,
        limit=PROCESS_LIMIT,
    )
    if isinstance(observed, Outcome):
        return observed
    if observed.returncode != 0:
        return Outcome.UNAVAILABLE
    fields = observed.output.split(None, 5)
    owner = OWNER_KEY.encode() + b"=" + token.encode()
    if (
        len(fields) != 6
        or re.search(rb"(?<!\S)" + re.escape(owner) + rb"(?!\S)", fields[5]) is None
        or re.search(rb"(?<!\S)" + re.escape(DAEMON) + rb"(?!\S)", fields[5]) is None
    ):
        return Outcome.UNAVAILABLE
    # ps combines argv and environment. Retain only start identity, never its raw output.
    return b" ".join(fields[:5])


def snapshot(daemons: DaemonPids, destination: Path, token: str, java_home: Path | None) -> Outcome:
    """A log PID alone grants no attachment authority; association is checked twice."""
    try:
        pid = daemons.single()
        if pid is None or java_home is None:
            return Outcome.UNAVAILABLE
        jcmd = java_home / "bin/jcmd"
        if not jcmd.is_file():
            return Outcome.UNAVAILABLE
        before = identity(pid, token)
        if isinstance(before, Outcome):
            return before
        dump = capture((str(jcmd), str(pid), "Thread.print", "-l"), seconds=3, limit=DUMP_LIMIT)
        if isinstance(dump, Outcome):
            return dump
        if dump.returncode != 0 or not dump.output.strip():
            return Outcome.ATTACH_FAILED
        if identity(pid, token) != before:
            return Outcome.IDENTITY_UNCONFIRMED
        with tempfile.TemporaryDirectory(
            dir=destination.parent, prefix=".daemon-diagnostic-"
        ) as temporary:
            retained = Path(temporary) / "threads.txt"
            retained.write_bytes(dump.output)
            retained.replace(destination)
    except (OSError, ValueError):
        return Outcome.UNAVAILABLE
    return Outcome.RETAINED
