#!/usr/bin/env python3
"""Compare explicit native input identities with Git blobs, without repository mutation.

This observation proves byte attribution only. It does not prove native execution
or source stability through extraction. The Kotlin capture coordinator owns those
separate obligations. Limits: 10,000 files, 8 MiB request, 32 MiB per file, and a
10-second limit per Git call. Paths use literal repository-relative UTF-8 names.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, field
from enum import StrEnum
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
from typing import Literal

MAX_REQUEST_BYTES = 8 * 1024 * 1024
MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_FILES = 10_000
OBJECT_ID = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")
CONTENT_ID = re.compile(r"[0-9a-f]{64}\Z")


class Failure(StrEnum):
    INVALID_REQUEST = "INVALID_REQUEST"
    CHECKOUT_UNAVAILABLE = "CHECKOUT_UNAVAILABLE"
    CHECKOUT_ROOT_MISMATCH = "CHECKOUT_ROOT_MISMATCH"
    UNSUPPORTED_CHECKOUT_PATH = "UNSUPPORTED_CHECKOUT_PATH"
    GIT_UNAVAILABLE = "GIT_UNAVAILABLE"
    GIT_FAILED = "GIT_FAILED"
    TIME_EXHAUSTED = "TIME_EXHAUSTED"
    CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED"
    REVISION_UNAVAILABLE = "REVISION_UNAVAILABLE"
    TRACKED_SOURCE_UNAVAILABLE = "TRACKED_SOURCE_UNAVAILABLE"
    UNSUPPORTED_GIT_ENTRY = "UNSUPPORTED_GIT_ENTRY"
    CHECKOUT_CONTENT_MISMATCH = "CHECKOUT_CONTENT_MISMATCH"
    REVISION_CONTENT_MISMATCH = "REVISION_CONTENT_MISMATCH"


@dataclass(frozen=True)
class Source:
    path: str
    content: str


@dataclass(frozen=True)
class Request:
    repository: str
    commit: str
    files: tuple[Source, ...]


@dataclass(frozen=True)
class Revision:
    repository: str
    commit: str
    tree: str


@dataclass(frozen=True)
class AttributedSource:
    path: str
    content: str
    blob: str


@dataclass
class Work:
    gitCalls: int = 0
    filesCompared: int = 0
    bytesExamined: int = 0


@dataclass(frozen=True)
class Attributed:
    revision: Revision
    files: tuple[AttributedSource, ...]
    work: Work
    status: Literal["attributed"] = field(default="attributed", init=False)


@dataclass(frozen=True)
class Rejected:
    cause: Failure
    work: Work
    status: Literal["rejected"] = field(default="rejected", init=False)


def parse_request(raw: object) -> Request | Failure:
    if not isinstance(raw, dict) or set(raw) != {"repository", "commit", "files"}:
        return Failure.INVALID_REQUEST
    repository, commit, files = raw["repository"], raw["commit"], raw["files"]
    if (not isinstance(repository, str) or not repository.strip() or len(repository) > 4096
            or ":" not in repository or repository.startswith("/")
            or any(ord(character) < 32 or ord(character) == 127 for character in repository)):
        return Failure.INVALID_REQUEST
    if not isinstance(commit, str) or not OBJECT_ID.fullmatch(commit):
        return Failure.INVALID_REQUEST
    if not isinstance(files, list):
        return Failure.INVALID_REQUEST
    if len(files) > MAX_FILES:
        return Failure.CAPACITY_EXCEEDED
    parsed: list[Source] = []
    seen: set[str] = set()
    for item in files:
        if not isinstance(item, dict) or set(item) != {"path", "content"}:
            return Failure.INVALID_REQUEST
        path, content = item["path"], item["content"]
        if not isinstance(path, str) or not path or len(path.encode("utf-8")) > 4096:
            return Failure.INVALID_REQUEST
        if "\x00" in path or "\\" in path or path.startswith("/") or path in seen:
            return Failure.INVALID_REQUEST
        if any(part in {"", ".", "..", ".git"} for part in path.split("/")):
            return Failure.INVALID_REQUEST
        if not isinstance(content, str) or not CONTENT_ID.fullmatch(content):
            return Failure.INVALID_REQUEST
        seen.add(path)
        parsed.append(Source(path, content))
    return Request(repository, commit, tuple(sorted(parsed, key=lambda source: source.path)))


class GitObservation:
    def __init__(self, checkout: Path, work: Work) -> None:
        self.checkout = checkout
        self.work = work

    def call(self, *args: str) -> bytes | Failure:
        self.work.gitCalls += 1
        try:
            result = subprocess.run(
                ["git", "--no-optional-locks", "--no-replace-objects", "-C", str(self.checkout), *args],
                check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10,
                env={key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
            )
        except FileNotFoundError:
            return Failure.GIT_UNAVAILABLE
        except subprocess.TimeoutExpired:
            return Failure.TIME_EXHAUSTED
        except OSError:
            return Failure.GIT_FAILED
        return result.stdout if result.returncode == 0 else Failure.GIT_FAILED

    def object_id(self, expression: str) -> str | Failure:
        output = self.call("rev-parse", "--verify", expression)
        if isinstance(output, Failure):
            return output
        value = output.decode("ascii", errors="replace").strip()
        return value if OBJECT_ID.fullmatch(value) else Failure.REVISION_UNAVAILABLE

    def source(self, source: Source, tree: str) -> AttributedSource | Failure:
        entry = self.call("ls-tree", "--full-tree", "-z", tree, "--", source.path)
        if isinstance(entry, Failure):
            return entry
        fields = entry.rstrip(b"\0").split(b"\t")
        if len(fields) != 2 or fields[1] != source.path.encode("utf-8"):
            return Failure.TRACKED_SOURCE_UNAVAILABLE
        metadata = fields[0].split(b" ")
        if len(metadata) != 3 or metadata[0] not in {b"100644", b"100755"} or metadata[1] != b"blob":
            return Failure.UNSUPPORTED_GIT_ENTRY
        blob = metadata[2].decode("ascii", errors="replace")
        if not OBJECT_ID.fullmatch(blob):
            return Failure.UNSUPPORTED_GIT_ENTRY
        size = self.call("cat-file", "-s", blob)
        if isinstance(size, Failure):
            return size
        if not size.strip().isdigit() or int(size.strip()) > MAX_FILE_BYTES:
            return Failure.CAPACITY_EXCEEDED
        blob_bytes = self.call("cat-file", "blob", blob)
        if isinstance(blob_bytes, Failure):
            return blob_bytes
        self.work.bytesExamined += len(blob_bytes)
        if len(blob_bytes) > MAX_FILE_BYTES:
            return Failure.CAPACITY_EXCEEDED
        if hashlib.sha256(blob_bytes).hexdigest() != source.content:
            return Failure.REVISION_CONTENT_MISMATCH
        path = self.checkout.joinpath(*PurePosixPath(source.path).parts)
        try:
            if path.resolve(strict=True) != path or not path.is_file():
                return Failure.UNSUPPORTED_CHECKOUT_PATH
            with path.open("rb") as stream:
                current = stream.read(MAX_FILE_BYTES + 1)
        except OSError:
            return Failure.CHECKOUT_UNAVAILABLE
        self.work.bytesExamined += len(current)
        if len(current) > MAX_FILE_BYTES:
            return Failure.CAPACITY_EXCEEDED
        if hashlib.sha256(current).hexdigest() != source.content:
            return Failure.CHECKOUT_CONTENT_MISMATCH
        self.work.filesCompared += 1
        return AttributedSource(source.path, source.content, blob)


def attribute(checkout: Path, request: Request, work: Work) -> Attributed | Rejected:
    try:
        root = checkout.resolve(strict=True)
    except OSError:
        return Rejected(Failure.CHECKOUT_UNAVAILABLE, work)
    git = GitObservation(root, work)
    observed_root = git.call("rev-parse", "--show-toplevel")
    if isinstance(observed_root, Failure):
        return Rejected(observed_root, work)
    if observed_root.rstrip(b"\n") != os.fsencode(root):
        return Rejected(Failure.CHECKOUT_ROOT_MISMATCH, work)
    commit = git.object_id(request.commit + "^{commit}")
    if isinstance(commit, Failure):
        return Rejected(commit, work)
    if commit != request.commit:
        return Rejected(Failure.REVISION_UNAVAILABLE, work)
    tree = git.object_id(commit + "^{tree}")
    if isinstance(tree, Failure):
        return Rejected(tree, work)
    sources: list[AttributedSource] = []
    for source in request.files:
        captured = git.source(source, tree)
        if isinstance(captured, Failure):
            return Rejected(captured, work)
        sources.append(captured)
    return Attributed(Revision(request.repository, commit, tree), tuple(sources), work)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    args = parser.parse_args()
    work = Work()
    try:
        with args.request.open("rb") as stream:
            raw = stream.read(MAX_REQUEST_BYTES + 1)
        request = parse_request(json.loads(raw)) if len(raw) <= MAX_REQUEST_BYTES else Failure.CAPACITY_EXCEEDED
    except (OSError, ValueError, UnicodeError):
        request = Failure.INVALID_REQUEST
    result = Rejected(request, work) if isinstance(request, Failure) else attribute(args.checkout, request, work)
    print(json.dumps(asdict(result), sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
