"""Own workspace restoration and preserve finalized usage after worker failure."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import subprocess
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, JsonValue, TypeAdapter, ValidationError


@dataclass(frozen=True, slots=True)
class OriginalFile:
    contents: bytes
    mode: int


@dataclass(frozen=True, slots=True)
class OriginalLink:
    target: str


type Original = OriginalFile | OriginalLink


def workspace_paths(root: Path) -> tuple[str, ...]:
    result = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root,
        capture_output=True,
        check=True,
    )
    return tuple(sorted(set(os.fsdecode(item) for item in result.stdout.split(b"\0") if item)))


def original(path: Path) -> Original:
    if path.is_symlink():
        return OriginalLink(os.readlink(path))
    return OriginalFile(path.read_bytes(), stat.S_IMODE(path.stat().st_mode))


@dataclass(frozen=True, slots=True)
class WorkspaceDelta:
    changed: tuple[str, ...]
    created: tuple[str, ...]


class PreservationFailure(StrEnum):
    INVALID_BUDGET = "INVALID_BUDGET"
    DESTINATION_EXISTS = "DESTINATION_EXISTS"
    DESTINATION_OVERLAP = "DESTINATION_OVERLAP"
    UNSAFE_PATH = "UNSAFE_PATH"
    SYMLINK = "SYMLINK"
    UNSUPPORTED_FILE = "UNSUPPORTED_FILE"
    INPUT_UNREADABLE = "INPUT_UNREADABLE"
    INPUT_CHANGED = "INPUT_CHANGED"
    FILE_LIMIT = "FILE_LIMIT"
    BYTE_LIMIT = "BYTE_LIMIT"
    DISCOVERY_FAILED = "DISCOVERY_FAILED"
    DESTINATION_IO = "DESTINATION_IO"


@dataclass(frozen=True, slots=True)
class PreservationRejected:
    reason: PreservationFailure
    kind: Literal["REJECTED"] = field(default="REJECTED", init=False)


@dataclass(frozen=True, slots=True)
class PreservedFile:
    relative_path: str
    byte_count: int
    mode: int
    sha256: str
    artifact_path: Path
    kind: Literal["FILE"] = field(default="FILE", init=False)


@dataclass(frozen=True, slots=True)
class PreservedDeletion:
    relative_path: str
    kind: Literal["DELETION"] = field(default="DELETION", init=False)


type PreservedEntry = Annotated[PreservedFile | PreservedDeletion, Field(discriminator="kind")]


@dataclass(frozen=True, slots=True)
class PreservedDelta:
    destination: Path
    entries: tuple[PreservedEntry, ...]
    kind: Literal["PRESERVED"] = field(default="PRESERVED", init=False)


@dataclass(frozen=True, slots=True)
class PreservationCleanupFailed:
    rejection: PreservationRejected
    destination: Path
    kind: Literal["CLEANUP_FAILED"] = field(default="CLEANUP_FAILED", init=False)


type PreservationOutcome = Annotated[
    PreservedDelta | PreservationRejected | PreservationCleanupFailed,
    Field(discriminator="kind"),
]


@dataclass(frozen=True, slots=True)
class _Unchanged:
    pass


def _check_no_links(path: Path) -> _Unchanged | PreservationRejected:
    """Examine each component without a link traversal."""
    for component in (*reversed(path.parents), path):
        try:
            if stat.S_ISLNK(component.lstat().st_mode):
                return PreservationRejected(PreservationFailure.SYMLINK)
        except FileNotFoundError:
            continue
        except OSError:
            return PreservationRejected(PreservationFailure.INPUT_UNREADABLE)
    return _Unchanged()


def _preserve_regular(
    path: Path,
    relative: str,
    prior: Original | None,
    artifact: Path,
    remaining_bytes: int,
    remaining_files: int,
) -> PreservedFile | _Unchanged | PreservationRejected:
    """Keep changed regular files with bounded reads."""
    try:
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), "rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode):
                return PreservationRejected(PreservationFailure.UNSUPPORTED_FILE)
            mode = stat.S_IMODE(info.st_mode)
            if not mode & 0o444:
                return PreservationRejected(PreservationFailure.INPUT_UNREADABLE)
            potentially_unchanged = (
                isinstance(prior, OriginalFile)
                and len(prior.contents) == info.st_size
                and prior.mode == mode
            )
            if not potentially_unchanged:
                if remaining_files == 0:
                    return PreservationRejected(PreservationFailure.FILE_LIMIT)
                if info.st_size > remaining_bytes:
                    return PreservationRejected(PreservationFailure.BYTE_LIMIT)
            digest = hashlib.sha256()
            count = 0
            while chunk := source.read(min(65536, info.st_size + 1 - count)):
                count += len(chunk)
                if count > info.st_size:
                    return PreservationRejected(PreservationFailure.INPUT_CHANGED)
                digest.update(chunk)
            if count != info.st_size:
                return PreservationRejected(PreservationFailure.INPUT_CHANGED)
            if (
                potentially_unchanged
                and isinstance(prior, OriginalFile)
                and digest.digest() == hashlib.sha256(prior.contents).digest()
            ):
                return _Unchanged()
            if remaining_files == 0:
                return PreservationRejected(PreservationFailure.FILE_LIMIT)
            if count > remaining_bytes:
                return PreservationRejected(PreservationFailure.BYTE_LIMIT)
            _ = source.seek(0)
            copied = 0
            copied_digest = hashlib.sha256()
            try:
                with os.fdopen(
                    os.open(artifact, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb"
                ) as output:
                    while chunk := source.read(min(65536, count + 1 - copied)):
                        copied += len(chunk)
                        if copied > count:
                            return PreservationRejected(PreservationFailure.INPUT_CHANGED)
                        copied_digest.update(chunk)
                        _ = output.write(chunk)
            except OSError:
                return PreservationRejected(PreservationFailure.DESTINATION_IO)
            if copied != count or copied_digest.digest() != digest.digest():
                return PreservationRejected(PreservationFailure.INPUT_CHANGED)
            return PreservedFile(relative, count, mode, digest.hexdigest(), artifact)
    except OSError:
        return PreservationRejected(PreservationFailure.INPUT_UNREADABLE)


@dataclass(slots=True)
class WorkspaceSnapshot:
    root: Path
    files: dict[str, Original]

    @classmethod
    def capture(cls, root: Path) -> WorkspaceSnapshot:
        root = root.resolve()
        return cls(
            root,
            {
                relative: original(root / relative)
                for relative in workspace_paths(root)
                if (root / relative).exists() or (root / relative).is_symlink()
            },
        )

    def examine(self) -> WorkspaceDelta:
        changed: list[str] = []
        for relative, prior in self.files.items():
            path = self.root / relative
            if not path.exists() and not path.is_symlink():
                changed.append(relative)
            elif original(path) != prior:
                changed.append(relative)
        created = tuple(
            relative for relative in workspace_paths(self.root) if relative not in self.files
        )
        return WorkspaceDelta(tuple(changed), created)

    def preserve_delta(
        self, destination: Path, *, maximum_files: int, maximum_bytes: int
    ) -> PreservationOutcome:
        """Keep a private overlay through sequential file reads.

        Budgets cover artifact bytes, changed files, created files, and deletions.
        Comparison reads stream unchanged files without a copy.
        The destination's parent must exist.
        Metadata excludes source bytes.
        The capture supplies no consistent view across concurrent writers.
        """
        if maximum_files < 0 or maximum_bytes < 0:
            return PreservationRejected(PreservationFailure.INVALID_BUDGET)
        destination = Path(os.path.abspath(destination))
        root = Path(os.path.abspath(self.root))
        if destination.is_relative_to(root) or root.is_relative_to(destination):
            return PreservationRejected(PreservationFailure.DESTINATION_OVERLAP)
        for path in (root, destination):
            checked = _check_no_links(path)
            if isinstance(checked, PreservationRejected):
                return checked
        try:
            destination.lstat()
        except FileNotFoundError:
            pass
        except OSError:
            return PreservationRejected(PreservationFailure.DESTINATION_IO)
        else:
            return PreservationRejected(PreservationFailure.DESTINATION_EXISTS)
        try:
            candidates = tuple(sorted(set(self.files) | set(workspace_paths(root))))
        except (OSError, subprocess.CalledProcessError):
            return PreservationRejected(PreservationFailure.DISCOVERY_FAILED)
        try:
            destination.mkdir(mode=0o700)
        except FileExistsError:
            return PreservationRejected(PreservationFailure.DESTINATION_EXISTS)
        except OSError:
            return PreservationRejected(PreservationFailure.DESTINATION_IO)
        outcome = self._preserve_candidates(
            root, destination, candidates, maximum_files, maximum_bytes
        )
        if isinstance(outcome, PreservationRejected):
            try:
                shutil.rmtree(destination)
            except OSError:
                return PreservationCleanupFailed(outcome, destination)
        return outcome

    def _preserve_candidates(
        self,
        root: Path,
        destination: Path,
        candidates: tuple[str, ...],
        maximum_files: int,
        maximum_bytes: int,
    ) -> PreservationOutcome:
        entries: list[PreservedEntry] = []
        byte_count = 0
        for relative in candidates:
            name = Path(relative)
            if name.is_absolute() or not name.parts or ".." in name.parts:
                return PreservationRejected(PreservationFailure.UNSAFE_PATH)
            path = root / name
            checked = _check_no_links(path.parent)
            if isinstance(checked, PreservationRejected):
                return checked
            try:
                info = path.lstat()
            except FileNotFoundError:
                if relative not in self.files:
                    return PreservationRejected(PreservationFailure.INPUT_CHANGED)
                if len(entries) == maximum_files:
                    return PreservationRejected(PreservationFailure.FILE_LIMIT)
                entries.append(PreservedDeletion(relative))
                continue
            except OSError:
                return PreservationRejected(PreservationFailure.INPUT_UNREADABLE)
            if stat.S_ISLNK(info.st_mode):
                prior = self.files.get(relative)
                try:
                    if isinstance(prior, OriginalLink) and os.readlink(path) == prior.target:
                        continue
                except OSError:
                    return PreservationRejected(PreservationFailure.INPUT_UNREADABLE)
                return PreservationRejected(PreservationFailure.SYMLINK)
            if not stat.S_ISREG(info.st_mode):
                return PreservationRejected(PreservationFailure.UNSUPPORTED_FILE)
            record = _preserve_regular(
                path,
                relative,
                self.files.get(relative),
                destination / f"{len(entries):08d}.source",
                maximum_bytes - byte_count,
                maximum_files - len(entries),
            )
            if isinstance(record, PreservationRejected):
                return record
            if isinstance(record, PreservedFile):
                entries.append(record)
                byte_count += record.byte_count
        return PreservedDelta(destination, tuple(entries))

    def restore(self) -> None:
        delta = self.examine()
        for relative in delta.created:
            path = self.root / relative
            path.unlink()
        for relative in delta.changed:
            path = self.root / relative
            prior = self.files[relative]
            if path.is_symlink():
                path.unlink()
            path.parent.mkdir(parents=True, exist_ok=True)
            match prior:
                case OriginalFile(contents, mode):
                    _ = path.write_bytes(contents)
                    path.chmod(mode)
                case OriginalLink(target):
                    if path.exists():
                        path.unlink()
                    path.symlink_to(target)


class FinalUsage(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)
    type: Literal["usage"]
    input: int = Field(ge=0)
    output: int = Field(ge=0)
    cacheRead: int = Field(ge=0)
    cacheWrite: int = Field(ge=0)
    totalTokens: int = Field(ge=0)
    catalogCostUsd: float | None = Field(ge=0)


@dataclass(frozen=True, slots=True)
class JournalTotals:
    qualification: Literal["FINALIZED_PREFIX_ONLY", "NO_JOURNAL", "MALFORMED_JOURNAL"]
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    known_catalog_cost_usd: float = 0.0
    provider_requests: int = 0
    finalized_responses: int = 0
    tool_calls: int = 0


def read_journal(path: Path) -> JournalTotals:
    if not path.is_file():
        return JournalTotals("NO_JOURNAL")
    incoming = outgoing = cached = written = requests = responses = tools = 0
    cost = 0.0
    qualification: Literal["FINALIZED_PREFIX_ONLY", "MALFORMED_JOURNAL"] = "FINALIZED_PREFIX_ONLY"
    for line in path.read_bytes().splitlines():
        try:
            entry = TypeAdapter(dict[str, JsonValue]).validate_json(line)
            match entry.get("type"):
                case "provider_request":
                    requests += 1
                case "tool_call":
                    tools += 1
                case "assistant_end":
                    usage = FinalUsage.model_validate(entry["usage"])
                    if (
                        usage.totalTokens
                        != usage.input + usage.output + usage.cacheRead + usage.cacheWrite
                    ):
                        qualification = "MALFORMED_JOURNAL"
                        break
                    incoming += usage.input
                    outgoing += usage.output
                    cached += usage.cacheRead
                    written += usage.cacheWrite
                    cost += usage.catalogCostUsd or 0.0
                    responses += 1
                case "stage" | "tool_end" | "session_ready" | "provider_denied":
                    pass
                case _:
                    qualification = "MALFORMED_JOURNAL"
                    break
        except (ValidationError, KeyError, json.JSONDecodeError):
            qualification = "MALFORMED_JOURNAL"
            break
    return JournalTotals(
        qualification,
        incoming + cached + written,
        outgoing,
        cached,
        written,
        cost,
        requests,
        responses,
        tools,
    )
