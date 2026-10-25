# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["pydantic==2.12.3"]
# [tool.uv]
# no-build = true
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Local byte-preserving capture. JSON receipts grant no semantic authority."""

from __future__ import annotations

import argparse
import hashlib
import os
import stat
import sys
import time
from collections.abc import Callable
from contextlib import ExitStack
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import BinaryIO, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

MAX_CAPTURE = 64 * 1024 * 1024
MAX_OPTIONS = 256 * 1024
DEBUG_OPTION = b"-Didea.log.debug.categories="
KAST_CATEGORY = b"io.github.amichne.kast"


class Document(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class Failure(StrEnum):
    MISSING = "MISSING"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    IO_ERROR = "IO_ERROR"
    NOT_REGULAR_FILE = "NOT_REGULAR_FILE"
    SYMLINK_REJECTED = "SYMLINK_REJECTED"
    OUTPUT_EXISTS = "OUTPUT_EXISTS"
    PREFIX_OMITTED = "PREFIX_OMITTED"
    BYTE_LIMIT = "BYTE_LIMIT"
    ROTATED = "ROTATED"
    TRUNCATED_OR_REWRITTEN = "TRUNCATED_OR_REWRITTEN"
    SOURCE_CHANGED_DURING_READ = "SOURCE_CHANGED_DURING_READ"
    INTERRUPTED = "INTERRUPTED"
    CORRELATION_INDEX_LIMIT = "CORRELATION_INDEX_LIMIT"
    OPTIONS_REJECTED = "OPTIONS_REJECTED"
    RESTORE_CONFLICT = "RESTORE_CONFLICT"
    RECEIPT_REJECTED = "RECEIPT_REJECTED"


class Issue(Document):
    code: Failure
    path: str


class IssueEvent(Document):
    type: Literal["ISSUE"] = "ISSUE"
    issue: Issue


class Source(Document):
    path: str
    device: int
    inode: int
    size: int
    modified_ns: int
    initial_offset: int


class Segment(Document):
    source: str
    device: int
    inode: int
    start: int
    end: int
    captured_at: str
    file: str
    sha256: str
    starts_midline: bool
    ends_midline: bool
    # Literal matches only. These offsets do not establish a semantic join.
    read_id_matches: tuple[int, ...] = ()


class CaptureManifest(Document):
    schema_version: Literal[1] = 1
    command: Literal["snapshot", "watch"]
    status: Literal["CAPTURED", "QUALIFIED"]
    started_at: str
    ended_at: str
    max_bytes: int
    read_id: str | None
    sources: tuple[Source, ...]
    segments: tuple[Segment, ...]
    issues: tuple[Issue, ...]


class StartedEvent(Document):
    type: Literal["STARTED"] = "STARTED"
    sources: tuple[Source, ...]
    started_at: str


class SegmentEvent(Document):
    type: Literal["SEGMENT"] = "SEGMENT"
    segment: Segment
    text: str


class ManifestEvent(Document):
    type: Literal["MANIFEST"] = "MANIFEST"
    manifest: CaptureManifest


class Discovery(Document):
    schema_version: Literal[1] = 1
    expected_control_log: str
    sources: tuple[Source, ...]
    issues: tuple[Issue, ...]
    authority: Literal["CONVENTIONAL_PATHS_NOT_LOADED_PROCESS"] = (
        "CONVENTIONAL_PATHS_NOT_LOADED_PROCESS"
    )


class DebugReceipt(Document):
    schema_version: Literal[1] = 1
    vm_options: str
    before_sha256: str
    after_sha256: str
    original_mode: int = Field(ge=0, le=0o777)
    category: Literal["io.github.amichne.kast"] = "io.github.amichne.kast"
    application: Literal["RESTART_REQUIRED_NOT_LOADED_PROOF"] = "RESTART_REQUIRED_NOT_LOADED_PROOF"


class SelfTestResult(Document):
    status: Literal["PASSED"] = "PASSED"
    checks: Literal["BYTE_CAPTURE_AND_DEBUG_RESTORE"] = "BYTE_CAPTURE_AND_DEBUG_RESTORE"


class DebugResult(Document):
    status: Literal["CONFIGURED", "RESTORED", "ALREADY_RESTORED"]
    vm_options: str
    receipt: str
    loaded: Literal["NOT_EXAMINED"] = "NOT_EXAMINED"


@dataclass(frozen=True)
class Done:
    """The requested effect completed."""


@dataclass(frozen=True)
class FileBytes:
    value: bytes
    mode: int


@dataclass(frozen=True)
class Chunk:
    value: bytes
    starts_midline: bool
    ends_midline: bool
    changed_during_read: bool


def issue(code: Failure, path: Path) -> Issue:
    return Issue(code=code, path=str(path.absolute()))


def now() -> str:
    return datetime.now(UTC).isoformat()


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def io_failure(error: OSError, path: Path) -> Issue:
    if isinstance(error, FileNotFoundError):
        return issue(Failure.MISSING, path)
    if isinstance(error, PermissionError):
        return issue(Failure.PERMISSION_DENIED, path)
    if isinstance(error, FileExistsError):
        return issue(Failure.OUTPUT_EXISTS, path)
    return issue(Failure.IO_ERROR, path)


def private_directory(path: Path) -> Done | Issue:
    try:
        path.mkdir(mode=0o700)
        return Done()
    except OSError as error:
        return io_failure(error, path)


def write_new(path: Path, value: bytes) -> Done | Issue:
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        return Done()
    except OSError as error:
        return io_failure(error, path)


def open_regular(path: Path) -> BinaryIO | Issue:
    try:
        if path.is_symlink():
            return issue(Failure.SYMLINK_REJECTED, path)
        with ExitStack() as cleanup:
            fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
            stream = cleanup.enter_context(os.fdopen(fd, "rb"))
            if not stat.S_ISREG(os.fstat(fd).st_mode):
                return issue(Failure.NOT_REGULAR_FILE, path)
            cleanup.pop_all()
            return stream
    except OSError as error:
        return io_failure(error, path)


def bounded_read(path: Path, limit: int) -> FileBytes | Issue:
    opened = open_regular(path)
    if isinstance(opened, Issue):
        return opened
    try:
        with opened as stream:
            mode = stat.S_IMODE(os.fstat(stream.fileno()).st_mode)
            value = stream.read(limit + 1)
            if len(value) > limit or mode > 0o777:
                return issue(Failure.OPTIONS_REJECTED, path)
            return FileBytes(value, mode)
    except OSError as error:
        return io_failure(error, path)


def emit(document: Document, *, stderr: bool = False, indent: int | None = None) -> Done | Issue:
    try:
        print(
            document.model_dump_json(indent=indent),
            file=sys.stderr if stderr else sys.stdout,
            flush=True,
        )
        return Done()
    except OSError:
        return Issue(code=Failure.IO_ERROR, path="<stderr>" if stderr else "<stdout>")


def source_record(path: Path, info: os.stat_result, offset: int) -> Source:
    return Source(
        path=str(path.absolute()),
        device=info.st_dev,
        inode=info.st_ino,
        size=info.st_size,
        modified_ns=info.st_mtime_ns,
        initial_offset=offset,
    )


@dataclass
class Reader:
    path: Path
    stream: BinaryIO
    offset: int
    anchor: bytes
    source: Source

    @classmethod
    def start(cls, path: Path, offset: int) -> Reader | Issue:
        return cls._open(path, lambda _size: offset)

    @classmethod
    def tail(cls, path: Path, allowance: int) -> Reader | Issue:
        return cls._open(path, lambda size: max(0, size - allowance))

    @classmethod
    def at_end(cls, path: Path) -> Reader | Issue:
        return cls._open(path, lambda size: size)

    @classmethod
    def _open(cls, path: Path, offset_for_size: Callable[[int], int]) -> Reader | Issue:
        stream = open_regular(path)
        if isinstance(stream, Issue):
            return stream
        try:
            with ExitStack() as cleanup:
                cleanup.enter_context(stream)
                info = os.fstat(stream.fileno())
                offset = offset_for_size(info.st_size)
                stream.seek(max(0, offset - 64))
                anchor = stream.read(min(64, offset))
                reader = cls(path, stream, offset, anchor, source_record(path, info, offset))
                cleanup.pop_all()
                return reader
        except OSError as error:
            return io_failure(error, path)

    def close(self) -> Done | Issue:
        try:
            self.stream.close()
            return Done()
        except OSError as error:
            return io_failure(error, self.path)

    def reset_if_rewritten(self) -> bool | Issue:
        try:
            self.stream.seek(max(0, self.offset - 64))
            previous = self.stream.read(min(64, self.offset))
            if os.fstat(self.stream.fileno()).st_size < self.offset or previous != self.anchor:
                self.offset = 0
                self.anchor = b""
                return True
            return False
        except OSError as error:
            return io_failure(error, self.path)

    def rotated(self) -> bool | Issue:
        try:
            current = self.path.stat(follow_symlinks=False)
            return (current.st_dev, current.st_ino) != (self.source.device, self.source.inode)
        except OSError as error:
            return io_failure(error, self.path)

    def take(self, allowance: int) -> Chunk | Issue:
        try:
            self.stream.seek(max(0, self.offset - 1))
            midline = self.offset > 0 and self.stream.read(1) != b"\n"
            self.stream.seek(self.offset)
            value = self.stream.read(allowance)
            self.offset += len(value)
            self.stream.seek(max(0, self.offset - 64))
            self.anchor = self.stream.read(min(64, self.offset))
            return Chunk(
                value,
                midline,
                bool(value) and not value.endswith(b"\n"),
                os.fstat(self.stream.fileno()).st_size < self.offset,
            )
        except OSError as error:
            return io_failure(error, self.path)


def discover(idea_directory: Path, installation: Path, codex_home: Path) -> Discovery:
    host_profile = digest(str(codex_home.absolute()).encode())[:16]
    control = installation / "state" / "broker" / host_profile / "service.log"
    sources: list[Source] = []
    issues: list[Issue] = []
    for path in (idea_directory / "idea.log", control):
        reader = Reader.start(path, 0)
        if isinstance(reader, Issue):
            issues.append(reader)
        else:
            sources.append(reader.source)
            closed = reader.close()
            if isinstance(closed, Issue):
                issues.append(closed)
    return Discovery(
        expected_control_log=str(control.absolute()), sources=tuple(sources), issues=tuple(issues)
    )


class Capture:
    """One owner for raw files and their monotone receipt."""

    def __init__(self, output: Path, maximum: int, read_id: str | None, stream: bool):
        self.output = output
        self.maximum = maximum
        self.remaining = maximum
        self.read_id = read_id
        self.sources: list[Source] = []
        self.segments: list[Segment] = []
        self.issues: list[Issue] = []
        self.started = now()
        self.stream = stream

    @classmethod
    def start(
        cls, output: Path, maximum: int, read_id: str | None, stream: bool = False
    ) -> Capture | Issue:
        created = private_directory(output)
        if isinstance(created, Issue):
            return created
        return cls(output, maximum, read_id, stream)

    def record(self, problem: Issue) -> None:
        if problem not in self.issues:
            self.issues.append(problem)
            if self.stream:
                emitted = emit(IssueEvent(issue=problem))
                if isinstance(emitted, Issue) and emitted not in self.issues:
                    self.issues.append(emitted)

    def issue(self, code: Failure, path: Path) -> None:
        self.record(issue(code, path))

    def take(self, reader: Reader) -> Done | Issue:
        start = reader.offset
        chunk = reader.take(self.remaining)
        if isinstance(chunk, Issue):
            return chunk
        value = chunk.value
        if not value:
            return Done()
        name = f"segment-{len(self.segments):06d}.log"
        written = write_new(self.output / name, value)
        if isinstance(written, Issue):
            return written
        matches: list[int] = []
        if self.read_id:
            needle = self.read_id.encode()
            pos = value.find(needle)
            while pos >= 0:
                if len(matches) == 4096:
                    self.issue(Failure.CORRELATION_INDEX_LIMIT, reader.path)
                    break
                matches.append(start + pos)
                pos = value.find(needle, pos + len(needle))
        self.segments.append(
            Segment(
                source=str(reader.path.absolute()),
                device=reader.source.device,
                inode=reader.source.inode,
                start=start,
                end=reader.offset,
                captured_at=now(),
                file=name,
                sha256=digest(value),
                starts_midline=chunk.starts_midline,
                ends_midline=chunk.ends_midline,
                read_id_matches=tuple(matches),
            )
        )
        self.remaining -= len(value)
        if chunk.changed_during_read:
            self.issue(Failure.SOURCE_CHANGED_DURING_READ, reader.path)
        if self.stream:
            return emit(
                SegmentEvent(
                    segment=self.segments[-1], text=value.decode("utf-8", errors="replace")
                )
            )
        return Done()

    def finish(self, command: Literal["snapshot", "watch"]) -> CaptureManifest | Issue:
        result = CaptureManifest(
            command=command,
            status="QUALIFIED" if self.issues else "CAPTURED",
            started_at=self.started,
            ended_at=now(),
            max_bytes=self.maximum,
            read_id=self.read_id,
            sources=tuple(self.sources),
            segments=tuple(self.segments),
            issues=tuple(self.issues),
        )
        written = write_new(
            self.output / "manifest.json", result.model_dump_json(indent=2).encode()
        )
        return written if isinstance(written, Issue) else result


def snapshot(
    paths: tuple[Path, ...], output: Path, max_bytes: int, read_id: str | None = None
) -> CaptureManifest | Issue:
    capture = Capture.start(output, max_bytes, read_id)
    if isinstance(capture, Issue):
        return capture
    for path in paths:
        reader = Reader.tail(path, capture.remaining)
        if isinstance(reader, Issue):
            capture.record(reader)
            continue
        try:
            capture.sources.append(reader.source)
            if reader.offset:
                capture.issue(Failure.PREFIX_OMITTED, path)
            result = capture.take(reader)
            if isinstance(result, Issue):
                capture.record(result)
        finally:
            closed = reader.close()
            if isinstance(closed, Issue):
                capture.record(closed)
    return capture.finish("snapshot")


def poll(reader: Reader, capture: Capture) -> Reader | Issue:
    """Drain the open inode before a replacement. Every rotation remains qualified."""
    reset = reader.reset_if_rewritten()
    if isinstance(reset, Issue):
        return reset
    if reset:
        capture.issue(Failure.TRUNCATED_OR_REWRITTEN, reader.path)
    taken = capture.take(reader)
    if isinstance(taken, Issue):
        return taken
    rotated = reader.rotated()
    if isinstance(rotated, Issue):
        return rotated
    if not rotated:
        return reader
    capture.issue(Failure.ROTATED, reader.path)
    replacement = Reader.start(reader.path, 0)
    if isinstance(replacement, Issue):
        return replacement
    capture.sources.append(replacement.source)
    taken = capture.take(replacement)
    if isinstance(taken, Issue):
        closed = replacement.close()
        if isinstance(closed, Issue):
            capture.record(closed)
        return taken
    closed = reader.close()
    if isinstance(closed, Issue):
        capture.record(closed)
    return replacement


def watch(
    paths: tuple[Path, ...],
    output: Path,
    max_bytes: int,
    seconds: float,
    interval: float,
    read_id: str | None = None,
    stream: bool = False,
) -> CaptureManifest | Issue:
    capture = Capture.start(output, max_bytes, read_id, stream)
    if isinstance(capture, Issue):
        return capture
    readers: list[Reader] = []
    try:
        for path in paths:
            reader = Reader.at_end(path)
            if isinstance(reader, Issue):
                capture.record(reader)
            else:
                capture.sources.append(reader.source)
                readers.append(reader)
        if stream:
            emitted = emit(StartedEvent(sources=tuple(capture.sources), started_at=capture.started))
            if isinstance(emitted, Issue):
                capture.record(emitted)
        deadline = time.monotonic() + seconds
        while readers:
            for index, reader in enumerate(readers):
                polled = poll(reader, capture)
                if isinstance(polled, Issue):
                    capture.record(polled)
                else:
                    readers[index] = polled
            if capture.remaining == 0:
                for reader in readers:
                    capture.issue(Failure.BYTE_LIMIT, reader.path)
                break
            remaining_time = deadline - time.monotonic()
            if remaining_time <= 0:
                break
            time.sleep(min(interval, remaining_time))
    except KeyboardInterrupt:
        capture.issue(Failure.INTERRUPTED, output)
    finally:
        for reader in readers:
            closed = reader.close()
            if isinstance(closed, Issue):
                capture.record(closed)
    return capture.finish("watch")


def debug_contents(before: bytes, path: Path) -> bytes | Issue:
    try:
        before.decode("utf-8")
    except UnicodeError:
        return issue(Failure.OPTIONS_REJECTED, path)
    if b"\x00" in before:
        return issue(Failure.OPTIONS_REJECTED, path)
    lines = before.splitlines(keepends=True)
    if any(
        line.strip().startswith(b"-Didea.log.debug.categories")
        and not line.strip().startswith(DEBUG_OPTION)
        for line in lines
    ):
        return issue(Failure.OPTIONS_REJECTED, path)
    selected = [i for i, line in enumerate(lines) if line.strip().startswith(DEBUG_OPTION)]
    if len(selected) > 1:
        return issue(Failure.OPTIONS_REJECTED, path)
    newline = b"\r\n" if b"\r\n" in before else b"\n"
    if selected:
        index = selected[0]
        line = lines[index]
        contents = line.strip()[len(DEBUG_OPTION) :]
        if KAST_CATEGORY in contents.split(b",") or b"#" + KAST_CATEGORY in contents.split(b","):
            return issue(Failure.OPTIONS_REJECTED, path)
        ending = b"\r\n" if line.endswith(b"\r\n") else b"\n" if line.endswith(b"\n") else b""
        lines[index] = (
            DEBUG_OPTION + contents + (b"," if contents else b"") + KAST_CATEGORY + ending
        )
        return b"".join(lines)
    separator = newline if before and not before.endswith(b"\n") else b""
    return before + separator + DEBUG_OPTION + KAST_CATEGORY + newline


def replace_file(temporary: Path, path: Path, mode: int) -> Done | Issue:
    try:
        os.chmod(temporary, mode)
        os.replace(temporary, path)
        return Done()
    except OSError as error:
        return io_failure(error, path)


def unlink_owned(path: Path) -> Done | Issue:
    try:
        path.unlink(missing_ok=True)
        return Done()
    except OSError as error:
        return io_failure(error, path)


def replace_guarded(path: Path, expected: bytes, replacement: bytes, mode: int) -> Done | Issue:
    # The sibling is exclusive. Compare again immediately before atomic replacement.
    temporary = path.with_name(path.name + ".kast-log-change")
    written = write_new(temporary, replacement)
    if isinstance(written, Issue):
        return written
    current = bounded_read(path, MAX_OPTIONS)
    if isinstance(current, Issue):
        result = current
    elif current.value != expected or current.mode != mode:
        result = issue(Failure.RESTORE_CONFLICT, path)
    else:
        result = replace_file(temporary, path, mode)
    cleaned = unlink_owned(temporary)
    return cleaned if isinstance(cleaned, Issue) else result


def debug_enable(path: Path, receipt_directory: Path) -> DebugResult | Issue:
    before = bounded_read(path, MAX_OPTIONS)
    if isinstance(before, Issue):
        return before
    after = debug_contents(before.value, path)
    if isinstance(after, Issue):
        return after
    receipt = DebugReceipt(
        vm_options=str(path.absolute()),
        before_sha256=digest(before.value),
        after_sha256=digest(after),
        original_mode=before.mode,
    )
    created = private_directory(receipt_directory)
    if isinstance(created, Issue):
        return created
    backup = write_new(receipt_directory / "original.vmoptions", before.value)
    if isinstance(backup, Issue):
        return backup
    written = write_new(
        receipt_directory / "receipt.json", receipt.model_dump_json(indent=2).encode()
    )
    if isinstance(written, Issue):
        return written
    replaced = replace_guarded(path, before.value, after, before.mode)
    if isinstance(replaced, Issue):
        return replaced
    return DebugResult(
        status="CONFIGURED",
        vm_options=str(path.absolute()),
        receipt=str((receipt_directory / "receipt.json").absolute()),
    )


def parse_receipt(raw: bytes, path: Path) -> DebugReceipt | Issue:
    try:
        return DebugReceipt.model_validate_json(raw)
    except ValidationError:
        return issue(Failure.RECEIPT_REJECTED, path)


def debug_restore(receipt_directory: Path) -> DebugResult | Issue:
    receipt_path = receipt_directory / "receipt.json"
    raw = bounded_read(receipt_path, MAX_OPTIONS)
    if isinstance(raw, Issue):
        return raw
    receipt = parse_receipt(raw.value, receipt_path)
    if isinstance(receipt, Issue):
        return receipt
    original = bounded_read(receipt_directory / "original.vmoptions", MAX_OPTIONS)
    if isinstance(original, Issue):
        return original
    if digest(original.value) != receipt.before_sha256:
        return issue(Failure.RECEIPT_REJECTED, receipt_path)
    path = Path(receipt.vm_options)
    current = bounded_read(path, MAX_OPTIONS)
    if isinstance(current, Issue):
        return current
    if digest(current.value) == receipt.before_sha256:
        status = "ALREADY_RESTORED"
    elif digest(current.value) == receipt.after_sha256:
        replaced = replace_guarded(path, current.value, original.value, receipt.original_mode)
        if isinstance(replaced, Issue):
            return replaced
        status = "RESTORED"
    else:
        return issue(Failure.RESTORE_CONFLICT, path)
    return DebugResult(status=status, vm_options=str(path), receipt=str(receipt_path.absolute()))


class Arguments(argparse.Namespace):
    command: str
    log: list[Path]
    out: Path
    max_bytes: int
    read_id: str | None
    seconds: float
    interval: float
    idea_log_dir: Path
    installation: Path
    codex_home: Path
    vm_options: Path
    stream: bool
    receipt_dir: Path


def self_test() -> int:
    with TemporaryDirectory(prefix="kast log self-test ") as directory:
        root = Path(directory)
        source = root / "idea.log"
        source.write_bytes(b"Exception\n  frame\n")
        result = snapshot((source,), root / "capture", 100)
        if isinstance(result, Issue):
            emit(result, stderr=True)
            return 2
        captured = b"".join((root / "capture" / item.file).read_bytes() for item in result.segments)
        if captured != source.read_bytes() or result.issues:
            raise RuntimeError("Byte capture self-test failed")
        options = root / "idea.vmoptions"
        original = b"-Xmx2g\n"
        options.write_bytes(original)
        enabled = debug_enable(options, root / "receipt")
        if isinstance(enabled, Issue):
            emit(enabled, stderr=True)
            return 2
        restored = debug_restore(root / "receipt")
        if isinstance(restored, Issue):
            emit(restored, stderr=True)
            return 2
        if options.read_bytes() != original:
            raise RuntimeError("Debug restore self-test failed")
    print(SelfTestResult().model_dump_json())
    return 0


def main(argv: list[str] | None = None) -> int:
    if (sys.argv[1:] if argv is None else argv) == ["--self-test"]:
        return self_test()
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    find = commands.add_parser("discover", help="Report conventional paths, including missing logs")
    find.add_argument("--idea-log-dir", type=Path, required=True)
    find.add_argument("--installation", type=Path, required=True)
    find.add_argument("--codex-home", type=Path, required=True)
    for name in ("snapshot", "watch"):
        command = commands.add_parser(name)
        command.add_argument("--log", action="append", type=Path, required=True)
        command.add_argument("--out", type=Path, required=True)
        command.add_argument("--max-bytes", type=int, default=8 * 1024 * 1024)
        command.add_argument("--read-id")
        if name == "watch":
            command.add_argument("--seconds", type=float, default=30)
            command.add_argument("--interval", type=float, default=0.25)
            command.add_argument(
                "--stream", action="store_true", help="Emit live NDJSON with raw-text views"
            )
    enable = commands.add_parser(
        "debug-enable", help="Change existing custom VM options, then restart IDEA"
    )
    enable.add_argument("--vm-options", type=Path, required=True)
    enable.add_argument("--receipt-dir", type=Path, required=True)
    restore = commands.add_parser(
        "debug-restore", help="Restore only when bytes match the owned change"
    )
    restore.add_argument("--receipt-dir", type=Path, required=True)
    args = parser.parse_args(argv, namespace=Arguments())
    result: CaptureManifest | Discovery | DebugResult | Issue
    match args.command:
        case "discover":
            result = discover(args.idea_log_dir, args.installation, args.codex_home)
        case "snapshot" | "watch":
            if not 1 <= args.max_bytes <= MAX_CAPTURE or not 1 <= len(args.log) <= 16:
                parser.error("Use 1..67108864 bytes and 1..16 log paths")
            paths = tuple(dict.fromkeys(path.absolute() for path in args.log))
            if args.read_id is not None and not 1 <= len(args.read_id) <= 256:
                parser.error("read-id must contain 1..256 characters")
            if args.command == "snapshot":
                result = snapshot(paths, args.out, args.max_bytes, args.read_id)
            else:
                if not 0 < args.seconds <= 600 or not 0.1 <= args.interval <= 60:
                    parser.error("Use seconds in (0,600] and interval in [0.1,60]")
                result = watch(
                    paths,
                    args.out,
                    args.max_bytes,
                    args.seconds,
                    args.interval,
                    args.read_id,
                    args.stream,
                )
        case "debug-enable":
            result = debug_enable(args.vm_options, args.receipt_dir)
        case "debug-restore":
            result = debug_restore(args.receipt_dir)
        case _:
            parser.error("Unknown command")
    if isinstance(result, Issue):
        emit(result, stderr=True)
        return 2
    if args.command == "watch" and args.stream and isinstance(result, CaptureManifest):
        emitted = emit(ManifestEvent(manifest=result))
    else:
        emitted = emit(result, indent=2)
    if isinstance(emitted, Issue):
        emit(emitted, stderr=True)
        return 2
    return 2 if isinstance(result, (CaptureManifest, Discovery)) and result.issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
